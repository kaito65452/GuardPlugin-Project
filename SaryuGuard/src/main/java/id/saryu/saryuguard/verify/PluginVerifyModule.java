package id.saryu.saryuguard.verify;

import id.saryu.libcore.database.DatabaseModule;
import id.saryu.saryuguard.SaryuGuardPlugin;
import id.saryu.saryuguard.database.SaryuGuardDatabase;
import id.saryu.saryuguard.module.ModuleStatus;
import id.saryu.saryuguard.module.SaryuGuardModule;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/**
 * Verifies plugin jar hashes against local baseline and Modrinth API.
 *
 * <p>Requirements:
 * 3.2 Plugin Verify:
 * - Status: VERIFIED / UNVERIFIED / MISMATCH / SCAN_ERROR. SCAN_ERROR tidak pernah dianggap bersih.
 * - Hash SHA-256 jar. Lookup hash Modrinth = VERIFIED.
 * - Hangar dan Spigot: cocok nama/author saja = UNVERIFIED berconfidence rendah, TIDAK PERNAH VERIFIED.
 * - Baseline lokal disimpan di database dengan waktu dan penyetuju. Hash baru atau berubah =
 *   MISMATCH/UNKNOWN sampai admin menjalankan /sg plugins approve <plugin>. Tidak pernah otomatis dipercaya.
 * - Pemicu: WatchService + polling cadangan berkala (dedup dengan hash).
 * - Hanya kirim hash/nama, opt-in, cache, rate limit. API key tidak wajib.
 * - Docs: VERIFIED hanya berarti cocok dengan referensi, bukan bebas malware.</p>
 */
public class PluginVerifyModule implements SaryuGuardModule {

    private final SaryuGuardPlugin plugin;
    private final File pluginsDir;

    private boolean enabled = true;
    private boolean networkLookup = false;
    private String userAgent = "SaryuGuard/{VERSION} (admin-contact)";
    private int fallbackPollInterval = 300; // seconds

    private ModuleStatus status = ModuleStatus.DISABLED;
    private String statusMessage = "Not initialized";

    private final Map<String, PluginVerifyInfo> verifiedPlugins = new ConcurrentHashMap<>();
    private final Map<String, BaselineEntry> baselineCache = new ConcurrentHashMap<>();

    private WatchService watchService;
    private Thread watchThread;
    private ScheduledExecutorService scheduler;
    private final AtomicBoolean isScanning = new AtomicBoolean(false);

    public static class BaselineEntry {
        public String pluginName;
        public String jarHash;
        public long jarSize;
        public long approvedAt;
        public String approvedBy;
        public String status;
    }

    public PluginVerifyModule(@NotNull SaryuGuardPlugin plugin) {
        this.plugin = plugin;
        this.pluginsDir = plugin.getDataFolder().getParentFile(); // plugins/ directory
    }

    @Override
    public @NotNull String id() {
        return "plugin-verify";
    }

    @Override
    public @NotNull String displayName() {
        return "Plugin Verify";
    }

    @Override
    public synchronized void enable() throws Exception {
        var cfg = plugin.getConfigManager().getConfig();
        this.enabled = cfg.getBoolean("plugin-verify.enabled", true);
        this.networkLookup = cfg.getBoolean("plugin-verify.network-lookup", false);
        this.userAgent = cfg.getString("plugin-verify.user-agent", "SaryuGuard/{VERSION} (admin-contact)")
                .replace("{VERSION}", plugin.getDescription().getVersion());
        this.fallbackPollInterval = cfg.getInt("plugin-verify.fallback-poll-interval", 300);

        if (!enabled) {
            this.status = ModuleStatus.DISABLED;
            this.statusMessage = "Disabled in config";
            return;
        }

        this.status = ModuleStatus.ACTIVE;
        this.statusMessage = "Initializing baseline";

        // 1. Ensure baseline database table
        initDatabase();

        // 2. Load baseline from database into cache
        loadBaselineFromDb();

        // 3. Run initial scan async
        scanAllAsync();

        // 4. Start WatchService
        startWatchService();

        // 5. Start periodic fallback poll
        startFallbackPoll();

        this.status = ModuleStatus.ACTIVE;
        this.statusMessage = networkLookup ? "Active (WatchService + Modrinth)" : "Active (Local Baseline only)";
    }

    @Override
    public synchronized void disable() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {}
            watchService = null;
        }
        if (watchThread != null && watchThread.isAlive()) {
            watchThread.interrupt();
            watchThread = null;
        }
        verifiedPlugins.clear();
        baselineCache.clear();
        this.status = ModuleStatus.DISABLED;
        this.statusMessage = "Disabled";
    }

    @Override
    public boolean isEnabled() {
        return enabled && status != ModuleStatus.DISABLED;
    }

    @Override
    public @NotNull ModuleStatus getStatus() {
        return status;
    }

    @Override
    public @NotNull String getStatusMessage() {
        return statusMessage;
    }

    private void initDatabase() {
        SaryuGuardDatabase db = plugin.getDatabase();
        if (db == null || !db.isAvailable()) {
            this.status = ModuleStatus.DEGRADED;
            this.statusMessage = "Database unavailable; running with memory baseline";
            return;
        }

        db.asyncVoid(conn -> {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE IF NOT EXISTS sg_plugin_baseline ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "plugin_name TEXT NOT NULL, "
                        + "jar_hash TEXT NOT NULL, "
                        + "jar_size INTEGER DEFAULT 0, "
                        + "hash_algo TEXT NOT NULL DEFAULT 'SHA-256', "
                        + "status TEXT NOT NULL DEFAULT 'UNVERIFIED', "
                        + "approver TEXT, "
                        + "approved_at INTEGER, "
                        + "created_at INTEGER NOT NULL, "
                        + "UNIQUE (plugin_name, jar_hash))");
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to create sg_plugin_baseline table: " + e.getMessage(), e);
            }
        });
    }

    private void loadBaselineFromDb() {
        SaryuGuardDatabase db = plugin.getDatabase();
        if (db == null || !db.isAvailable()) return;

        db.async(conn -> {
            Map<String, BaselineEntry> loaded = new HashMap<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT plugin_name, jar_hash, jar_size, approved_at, approver, status FROM sg_plugin_baseline");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    BaselineEntry b = new BaselineEntry();
                    b.pluginName = rs.getString("plugin_name");
                    b.jarHash = rs.getString("jar_hash");
                    b.jarSize = rs.getLong("jar_size");
                    b.approvedAt = rs.getLong("approved_at");
                    b.approvedBy = rs.getString("approver");
                    b.status = rs.getString("status");
                    loaded.put(b.pluginName.toLowerCase(Locale.ROOT), b);
                }
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load plugin baselines from DB: " + e.getMessage(), e);
            }
            return loaded;
        }).thenAccept(map -> {
            baselineCache.putAll(map);
            plugin.getLogger().info("Loaded " + map.size() + " plugin baseline(s) from database.");
        });
    }

    public CompletableFuture<Void> scanAllAsync() {
        if (!isScanning.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.runAsync(() -> {
            try {
                scanAllSync();
            } finally {
                isScanning.set(false);
            }
        });
    }

    public synchronized void scanAllSync() {
        if (pluginsDir == null || !pluginsDir.exists()) return;

        File[] jarFiles = pluginsDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jarFiles == null) return;

        for (File jar : jarFiles) {
            processJarFile(jar);
        }
    }

    private void processJarFile(@NotNull File jar) {
        String fileName = jar.getName();
        String pluginGuess = fileName.substring(0, fileName.length() - 4); // strip .jar

        // Match against loaded Bukkit plugin names if possible
        for (Plugin p : Bukkit.getPluginManager().getPlugins()) {
            if (fileName.toLowerCase(Locale.ROOT).startsWith(p.getName().toLowerCase(Locale.ROOT))) {
                pluginGuess = p.getName();
                break;
            }
        }

        String sha256;
        try {
            sha256 = JarHasher.sha256(jar);
        } catch (Exception e) {
            PluginVerifyInfo err = new PluginVerifyInfo(pluginGuess, jar, "UNKNOWN", jar.length(), jar.lastModified());
            err.status = VerifyStatus.SCAN_ERROR;
            err.errorMessage = e.getMessage();
            err.details = "Gagal menghitung hash SHA-256";
            verifiedPlugins.put(pluginGuess.toLowerCase(Locale.ROOT), err);
            return;
        }

        PluginVerifyInfo info = new PluginVerifyInfo(pluginGuess, jar, sha256, jar.length(), jar.lastModified());

        // Check against local baseline
        BaselineEntry baseline = baselineCache.get(pluginGuess.toLowerCase(Locale.ROOT));
        if (baseline != null) {
            if (baseline.jarHash.equalsIgnoreCase(sha256)) {
                info.status = VerifyStatus.VERIFIED;
                info.confidence = 100;
                info.source = "BASELINE_APPROVED";
                info.approvedBy = baseline.approvedBy;
                info.approvedAt = baseline.approvedAt;
                info.details = "Disetujui oleh " + baseline.approvedBy;
                verifiedPlugins.put(pluginGuess.toLowerCase(Locale.ROOT), info);
                return;
            } else {
                info.status = VerifyStatus.MISMATCH;
                info.confidence = 0;
                info.source = "BASELINE_MISMATCH";
                info.approvedBy = baseline.approvedBy;
                info.approvedAt = baseline.approvedAt;
                info.details = "Hash berbeda dari baseline yang disetujui! Baseline: " + baseline.jarHash.substring(0, 10) + "...";
                verifiedPlugins.put(pluginGuess.toLowerCase(Locale.ROOT), info);
                plugin.getLogger().warning("[SECURITY ALERT] Hash jar untuk plugin '" + pluginGuess
                        + "' BERUBAH! Kemungkinan jar telah dimodifikasi atau diperbarui tanpa approval.");
                return;
            }
        }

        // Not in baseline: Check Modrinth if network lookup is enabled
        if (networkLookup) {
            checkModrinthHash(sha256, info);
        } else {
            info.status = VerifyStatus.UNVERIFIED;
            info.confidence = 0;
            info.source = "LOCAL_UNKNOWN";
            info.details = "Belum terdaftar di baseline lokal (jalankan /sg plugins approve <plugin>)";
        }

        verifiedPlugins.put(pluginGuess.toLowerCase(Locale.ROOT), info);
    }

    private void checkModrinthHash(String sha256, PluginVerifyInfo info) {
        try {
            // Modrinth API: https://api.modrinth.com/v2/version_file/<sha256>?algorithm=sha256
            URI uri = URI.create("https://api.modrinth.com/v2/version_file/" + sha256 + "?algorithm=sha256");
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(4000);

            // Optional auth token from credentials.toml
            String token = plugin.getConfigManager().getCredential("modrinth", "api_token");
            if (token != null && !token.isBlank()) {
                conn.setRequestProperty("Authorization", token.trim());
            }

            int code = conn.getResponseCode();
            if (code == 200) {
                try (InputStream in = conn.getInputStream()) {
                    String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    info.status = VerifyStatus.VERIFIED;
                    info.confidence = 95;
                    info.source = "MODRINTH";
                    info.details = "Terverifikasi via Modrinth SHA-256";
                }
            } else if (code == 404) {
                info.status = VerifyStatus.UNVERIFIED;
                info.confidence = 0;
                info.source = "MODRINTH_NOT_FOUND";
                info.details = "Tidak ditemukan di Modrinth";
            } else {
                info.status = VerifyStatus.UNVERIFIED;
                info.source = "MODRINTH_HTTP_" + code;
                info.details = "Modrinth HTTP response: " + code;
            }
        } catch (Exception e) {
            info.status = VerifyStatus.SCAN_ERROR;
            info.errorMessage = e.getMessage();
            info.details = "Koneksi ke Modrinth gagal: " + e.getMessage();
        }
    }

    /**
     * Admin explicitly approves a plugin's current jar hash.
     */
    public synchronized CompletableFuture<Boolean> approvePlugin(@NotNull String pluginName, @NotNull String approvedBy) {
        PluginVerifyInfo info = verifiedPlugins.get(pluginName.toLowerCase(Locale.ROOT));
        if (info == null || info.status == VerifyStatus.SCAN_ERROR) {
            return CompletableFuture.completedFuture(false);
        }

        long now = System.currentTimeMillis();
        BaselineEntry b = new BaselineEntry();
        b.pluginName = info.pluginName;
        b.jarHash = info.sha256;
        b.jarSize = info.jarSize;
        b.approvedAt = now;
        b.approvedBy = approvedBy;
        b.status = "APPROVED";

        baselineCache.put(info.pluginName.toLowerCase(Locale.ROOT), b);

        info.status = VerifyStatus.VERIFIED;
        info.confidence = 100;
        info.source = "ADMIN_APPROVED";
        info.approvedBy = approvedBy;
        info.approvedAt = now;
        info.details = "Disetujui secara manual oleh " + approvedBy;

        SaryuGuardDatabase db = plugin.getDatabase();
        if (db == null || !db.isAvailable()) {
            return CompletableFuture.completedFuture(true);
        }

        return db.async(conn -> {
            String sql = switch (db.getDbType()) {
                case SQLITE -> "INSERT INTO sg_plugin_baseline (plugin_name, jar_hash, jar_size, approved_at, approver, status, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT(plugin_name, jar_hash) DO UPDATE SET "
                        + "jar_size=excluded.jar_size, approved_at=excluded.approved_at, "
                        + "approver=excluded.approver, status=excluded.status";
                case H2 -> "MERGE INTO sg_plugin_baseline (plugin_name, jar_hash, jar_size, approved_at, approver, status, created_at) "
                        + "KEY(plugin_name, jar_hash) VALUES (?, ?, ?, ?, ?, ?, ?)";
            };

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, b.pluginName);
                ps.setString(2, b.jarHash);
                ps.setLong(3, b.jarSize);
                ps.setLong(4, b.approvedAt);
                ps.setString(5, b.approvedBy);
                ps.setString(6, b.status);
                ps.setLong(7, now);
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to persist plugin baseline for " + pluginName + ": " + e.getMessage(), e);
                return false;
            }
        });
    }

    private void startWatchService() {
        try {
            this.watchService = FileSystems.getDefault().newWatchService();
            pluginsDir.toPath().register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);

            watchThread = new Thread(() -> {
                while (!Thread.currentThread().isInterrupted() && watchService != null) {
                    WatchKey key;
                    try {
                        key = watchService.take();
                    } catch (InterruptedException e) {
                        break;
                    } catch (ClosedWatchServiceException e) {
                        break;
                    }

                    for (WatchEvent<?> event : key.pollEvents()) {
                        Path changed = (Path) event.context();
                        if (changed.toString().toLowerCase(Locale.ROOT).endsWith(".jar")) {
                            File jar = new File(pluginsDir, changed.toString());
                            if (jar.exists()) {
                                processJarFile(jar);
                            } else {
                                // Deleted jar
                                String nameGuess = changed.toString().replace(".jar", "");
                                verifiedPlugins.remove(nameGuess.toLowerCase(Locale.ROOT));
                            }
                        }
                    }

                    if (!key.reset()) {
                        break;
                    }
                }
            }, "SaryuGuard-JarWatcher");
            watchThread.setDaemon(true);
            watchThread.start();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Could not start WatchService on plugins folder: " + e.getMessage(), e);
            this.status = ModuleStatus.DEGRADED;
            this.statusMessage = "WatchService failed; falling back to periodic polling";
        }
    }

    private void startFallbackPoll() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "SaryuGuard-PluginVerifyPoll");
            t.setDaemon(true);
            return t;
        });

        scheduler.scheduleWithFixedDelay(() -> {
            try {
                scanAllSync();
            } catch (Throwable t) {
                plugin.getLogger().log(Level.FINE, "Fallback poll scan error: " + t.getMessage(), t);
            }
        }, fallbackPollInterval, fallbackPollInterval, TimeUnit.SECONDS);
    }

    public @NotNull Map<String, PluginVerifyInfo> getVerifiedPlugins() {
        return Collections.unmodifiableMap(verifiedPlugins);
    }

    public @Nullable PluginVerifyInfo getPluginInfo(@NotNull String name) {
        return verifiedPlugins.get(name.toLowerCase(Locale.ROOT));
    }
}
