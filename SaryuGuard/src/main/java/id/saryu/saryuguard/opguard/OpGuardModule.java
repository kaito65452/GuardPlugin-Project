package id.saryu.saryuguard.opguard;

import id.saryu.saryuguard.SaryuGuardPlugin;
import id.saryu.saryuguard.audit.AuditEventType;
import id.saryu.saryuguard.audit.AuditModule;
import id.saryu.saryuguard.database.SaryuGuardDatabase;
import id.saryu.saryuguard.module.ModuleStatus;
import id.saryu.saryuguard.module.SaryuGuardModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OpGuard module – verifies the OP list against an admin-approved UUID baseline.
 *
 * <p>Does NOT block commands. Only verifies OP membership on join and periodically.
 * On violation:
 * <ul>
 *   <li>{@code enforce} mode: de-ops the player on the main thread and logs an alert.</li>
 *   <li>{@code warn} mode: broadcasts an alert without de-opping.</li>
 * </ul>
 *
 * <p>Allowed OPs are loaded from both config.yml and the sg_op_baseline database table.
 * The database is the canonical store for add/remove/import commands.</p>
 *
 * <p>Documentation: Staf sebaiknya tidak OP; batasi via LuckPerms.
 * Status: ACTIVE / DEGRADED / DISABLED / ERROR.</p>
 */
public class OpGuardModule implements SaryuGuardModule, Listener {

    /** Bukkit-safe singleton per check; used in tests to override. */
    public interface OpProvider {
        boolean isOp(UUID uuid);
        Set<UUID> getOpUuids();
        void deop(UUID uuid, String playerName);
        void broadcastAlert(String message);
    }

    private static final String ID = "opguard";
    private static final String DISPLAY = "OpGuard";

    private final SaryuGuardPlugin plugin;
    private final Logger log;
    private final OpGuardDao dao = new OpGuardDao();

    // Config snapshot (set atomically during enable/reload)
    private volatile boolean configEnabled;
    private volatile String mode;          // "enforce" | "warn"
    private volatile long intervalTicks;
    private volatile boolean manageFromGame; // allow /sg opguard add from in-game
    private volatile boolean onlineMode;

    // Allowed ops: union of config UUIDs + DB entries
    private volatile Set<UUID> allowedOps = Set.of(); // immutable snapshot
    // Full entries from DB for list command
    private volatile List<OpGuardEntry> dbEntries = List.of();

    // Name resolver
    private volatile NameResolver nameResolver;

    private BukkitTask periodicTask;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile ModuleStatus status = ModuleStatus.DISABLED;
    private volatile String statusMsg = "Belum diaktifkan.";

    public OpGuardModule(@NotNull SaryuGuardPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    @Override
    public @NotNull String id() { return ID; }

    @Override
    public @NotNull String displayName() { return DISPLAY; }

    @Override
    public boolean isEnabled() { return status == ModuleStatus.ACTIVE || status == ModuleStatus.DEGRADED; }

    @Override
    public @NotNull ModuleStatus getStatus() { return status; }

    @Override
    public @NotNull String getStatusMessage() { return statusMsg; }

    @Override
    public void enable() {
        loadConfig();
        if (!configEnabled) {
            status = ModuleStatus.DISABLED;
            statusMsg = "Dinonaktifkan di config.yml";
            return;
        }

        try {
            // Initialize name resolver
            Path serverRoot = Bukkit.getWorldContainer().toPath();
            nameResolver = new NameResolver(log, serverRoot);
            nameResolver.loadConfig(plugin.getConfigManager().getConfig());

            // Load allowed ops from config + DB
            reloadAllowedOps();

            // Detect online mode
            onlineMode = Bukkit.getOnlineMode();

            // Register listener
            Bukkit.getPluginManager().registerEvents(this, plugin);

            // Start periodic check
            periodicTask = Bukkit.getScheduler().runTaskTimer(plugin,
                    this::runCheck, intervalTicks, intervalTicks);

            running.set(true);
            status = ModuleStatus.ACTIVE;
            statusMsg = "Aktif. Mode: " + mode + " | OP sah: " + allowedOps.size()
                    + " | Online-mode: " + onlineMode;
            log.info("[OpGuard] Aktif. Mode: " + mode + ", interval: " + intervalTicks
                    + " ticks, " + allowedOps.size() + " OP sah."
                    + (onlineMode ? "" : " PERINGATAN: Server offline-mode!"));
        } catch (Exception e) {
            status = ModuleStatus.ERROR;
            statusMsg = "Gagal mengaktifkan: " + e.getMessage();
            log.severe("[OpGuard] Gagal: " + e.getMessage());
        }
    }

    @Override
    public void disable() {
        running.set(false);
        HandlerList.unregisterAll(this);
        if (periodicTask != null) {
            periodicTask.cancel();
            periodicTask = null;
        }
        status = ModuleStatus.DISABLED;
        statusMsg = "Dinonaktifkan.";
        log.info("[OpGuard] Dinonaktifkan.");
    }

    // -------------------------------------------------------------------------
    // Event handler: check on join
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (!running.get()) return;
        Player player = event.getPlayer();
        if (!player.isOp()) return;
        UUID uuid = player.getUniqueId();
        if (!allowedOps.contains(uuid)) {
            if ("enforce".equalsIgnoreCase(mode)) {
                player.setOp(false);
            }
            handleViolation(uuid, player.getName());
        }
    }

    // -------------------------------------------------------------------------
    // Periodic check (main thread)
    // -------------------------------------------------------------------------

    private void runCheck() {
        if (!running.get()) return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.isOp() && !allowedOps.contains(p.getUniqueId())) {
                handleViolation(p.getUniqueId(), p.getName());
            }
        }
        // Also check all server ops (even offline ones)
        for (org.bukkit.OfflinePlayer op : Bukkit.getOperators()) {
            UUID uuid = op.getUniqueId();
            if (!allowedOps.contains(uuid)) {
                handleOfflineViolation(uuid, op.getName() != null ? op.getName() : uuid.toString());
            }
        }
    }

    private void handleViolation(UUID uuid, String name) {
        Runnable action = () -> {
            if ("enforce".equalsIgnoreCase(mode)) {
                org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                if (op.isOp()) {
                    op.setOp(false);
                    String msg = "[SaryuGuard] [OpGuard] ENFORCE: " + name + " (" + uuid + ") dide-op karena tidak ada di daftar OP sah!";
                    log.severe(msg);
                    Bukkit.broadcast(
                            net.kyori.adventure.text.Component.text(msg),
                            "saryuguard.admin"
                    );
                }
            } else {
                String msg = "[SaryuGuard] [OpGuard] WARN: " + name + " (" + uuid + ") OP tetapi tidak ada di daftar sah!";
                log.warning(msg);
                Bukkit.broadcast(
                        net.kyori.adventure.text.Component.text(msg),
                        "saryuguard.admin"
                );
            }
        };

        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    private void handleOfflineViolation(UUID uuid, String name) {
        Runnable action = () -> {
            if ("enforce".equalsIgnoreCase(mode)) {
                org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                if (op.isOp()) {
                    op.setOp(false);
                    String msg = "[SaryuGuard] [OpGuard] ENFORCE (offline): " + name + " (" + uuid + ") dide-op karena tidak ada di daftar OP sah!";
                    log.severe(msg);
                    Bukkit.broadcast(
                            net.kyori.adventure.text.Component.text(msg),
                            "saryuguard.admin"
                    );
                }
            } else {
                String msg = "[SaryuGuard] [OpGuard] WARN (offline): " + name + " (" + uuid + ") OP tetapi tidak ada di daftar sah!";
                log.warning(msg);
                Bukkit.broadcast(
                        net.kyori.adventure.text.Component.text(msg),
                        "saryuguard.admin"
                );
            }
        };

        if (Bukkit.isPrimaryThread()) {
            action.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, action);
        }
    }

    // -------------------------------------------------------------------------
    // Config
    // -------------------------------------------------------------------------

    private void loadConfig() {
        var cfg = plugin.getConfigManager().getConfig();
        configEnabled = cfg.getBoolean("opguard.enabled", true);
        mode = cfg.getString("opguard.mode", "enforce");
        if (!"enforce".equalsIgnoreCase(mode) && !"warn".equalsIgnoreCase(mode)) {
            log.warning("[OpGuard] Mode tidak dikenal: '" + mode + "'. Memakai 'warn'.");
            mode = "warn";
        }
        long intervalSec = cfg.getLong("opguard.check-interval-seconds", 60L);
        if (intervalSec < 10) {
            log.warning("[OpGuard] Interval terlalu pendek (" + intervalSec + "s). Minimum 10 detik.");
            intervalSec = 10;
        }
        intervalTicks = intervalSec * 20L;
        manageFromGame = cfg.getBoolean("opguard.manage-from-game", false);
    }

    /**
     * Reload the allowed-op set from config UUIDs + database entries.
     * Safe to call from any thread (result set is immutable).
     * Gagal membaca DB tidak pernah menghapus atau mengosongkan daftar yang sudah ada.
     */
    public synchronized void reloadAllowedOps() {
        Set<UUID> uuids = new HashSet<>();

        // 1. Config UUIDs
        List<String> raw = plugin.getConfigManager().getConfig().getStringList("opguard.allowed-ops");
        for (String s : raw) {
            try {
                uuids.add(UUID.fromString(s.trim()));
            } catch (IllegalArgumentException e) {
                log.warning("[OpGuard] UUID tidak valid di allowed-ops: '" + s + "'");
            }
        }

        // 2. DB entries
        SaryuGuardDatabase db = plugin.getDatabase();
        if (db != null && db.isAvailable()) {
            try (Connection c = db.getConnection()) {
                List<OpGuardDao.OpEntry> rows = dao.loadAll(c);
                List<OpGuardEntry> entryList = new ArrayList<>(rows.size());
                for (OpGuardDao.OpEntry row : rows) {
                    try {
                        OpGuardEntry entry = OpGuardEntry.fromDao(row);
                        entryList.add(entry);
                        uuids.add(entry.uuid());
                    } catch (IllegalArgumentException e) {
                        log.warning("[OpGuard] UUID tidak valid di database: '" + row.uuid() + "'");
                    }
                }
                this.dbEntries = List.copyOf(entryList);
                this.allowedOps = Collections.unmodifiableSet(uuids);
            } catch (Exception e) {
                log.log(Level.WARNING, "[OpGuard] Gagal memuat daftar OP dari database: " + e.getMessage(), e);
                // Rule: Jangan pernah menghapus atau mengosongkan daftar yang sudah ada bila DB error
            }
        } else {
            this.allowedOps = Collections.unmodifiableSet(uuids);
        }
    }

    // -------------------------------------------------------------------------
    // Public API for commands
    // -------------------------------------------------------------------------

    /** Returns current allowed-op UUID set (immutable snapshot). */
    public Set<UUID> getAllowedOps() { return allowedOps; }

    /** Returns current mode ("enforce" or "warn"). */
    public String getMode() { return mode; }

    /** Returns all DB entries for the list command. */
    public List<OpGuardEntry> getAllEntries() { return dbEntries; }

    /** Returns the name resolver instance. */
    public @Nullable NameResolver getNameResolver() { return nameResolver; }

    /** Set custom name resolver (for tests). */
    public void setNameResolver(@Nullable NameResolver resolver) { this.nameResolver = resolver; }

    /** Returns whether management from in-game is allowed. */
    public boolean isManageFromGame() { return manageFromGame; }

    /** Set manage-from-game toggle (for tests). */
    public void setManageFromGame(boolean allow) { this.manageFromGame = allow; }

    /** Returns whether the server is in online mode. */
    public boolean isOnlineMode() { return onlineMode; }

    /** Set online mode flag (for tests). */
    public void setOnlineMode(boolean online) { this.onlineMode = online; }

    /** Returns the DAO for direct access. */
    public @NotNull OpGuardDao getDao() { return dao; }

    /**
     * Check a specific UUID against the allowed-op list (for test/command use).
     * @return true if the UUID is in the allowed list
     */
    public boolean isAllowed(UUID uuid) {
        return allowedOps.contains(uuid);
    }

    /**
     * Add a UUID to the approved OP list (DB storage).
     * Idempotent: if UUID already exists, updates info and returns false.
     *
     * @param uuid       the player UUID
     * @param playerName last known name (may be null)
     * @param approver   who added it (console or player name)
     * @param notes      optional notes (e.g. resolution source)
     * @return true if newly added, false if already existed (idempotent update)
     */
    public boolean addAllowedOp(@NotNull UUID uuid, @Nullable String playerName,
                                @NotNull String approver, @Nullable String notes) throws Exception {
        SaryuGuardDatabase db = plugin.getDatabase();
        if (db == null || !db.isAvailable()) {
            throw new IllegalStateException("Database tidak tersedia.");
        }

        OpGuardDao.OpEntry entry = new OpGuardDao.OpEntry(
                uuid.toString(), playerName, approver,
                System.currentTimeMillis(), notes
        );

        boolean existed;
        try (Connection c = db.getConnection()) {
            existed = dao.exists(c, uuid.toString());
            dao.upsert(c, entry);
        }

        reloadAllowedOps();

        // Audit logging
        logAudit(AuditEventType.OP_CHANGE, approver, uuid, playerName,
                "opguard.add uuid=" + uuid + " name=" + playerName
                        + " source=" + (notes != null ? notes : "manual")
                        + (existed ? " result=updated" : " result=added"));

        return !existed;
    }

    /**
     * Remove a UUID from the approved OP list (DB storage).
     * If mode is enforce, also deops the player on the main thread.
     *
     * @param uuid     the player UUID
     * @param approver who removed it
     * @return true if removed, false if wasn't in list
     */
    public boolean removeAllowedOp(@NotNull UUID uuid, @NotNull String approver) throws Exception {
        SaryuGuardDatabase db = plugin.getDatabase();
        if (db == null || !db.isAvailable()) {
            throw new IllegalStateException("Database tidak tersedia.");
        }

        boolean removed;
        try (Connection c = db.getConnection()) {
            removed = dao.remove(c, uuid.toString());
        }

        if (removed) {
            reloadAllowedOps();

            // In enforce mode, de-op player on main thread
            if ("enforce".equalsIgnoreCase(mode)) {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    try {
                        org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                        if (op.isOp()) {
                            op.setOp(false);
                            log.info("[OpGuard] Pemain " + uuid + " dide-op setelah dihapus dari daftar OP sah.");
                        }
                    } catch (Throwable t) {
                        log.log(Level.WARNING, "[OpGuard] Gagal de-op pemain " + uuid + ": " + t.getMessage(), t);
                    }
                });
            }

            logAudit(AuditEventType.OP_CHANGE, approver, uuid, null,
                    "opguard.remove uuid=" + uuid + " result=removed");
        }

        return removed;
    }

    /**
     * Import all entries from ops.json into the approved list.
     *
     * @param approver who ran the import
     * @return number of newly imported entries
     */
    public int importFromOpsJson(@NotNull String approver) throws Exception {
        SaryuGuardDatabase db = plugin.getDatabase();
        if (db == null || !db.isAvailable()) {
            throw new IllegalStateException("Database tidak tersedia.");
        }

        if (nameResolver == null) {
            throw new IllegalStateException("NameResolver belum diinisialisasi.");
        }

        String json = nameResolver.readOpsJson();

        List<NameResolver.OpsJsonEntry> opsEntries = nameResolver.parseAllOpsJson(json);
        if (opsEntries.isEmpty()) {
            return 0;
        }

        List<OpGuardDao.OpEntry> toInsert = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (NameResolver.OpsJsonEntry oe : opsEntries) {
            toInsert.add(new OpGuardDao.OpEntry(
                    oe.uuid().toString(), oe.name(), approver, now,
                    "imported from ops.json"
            ));
        }

        int count;
        try (Connection c = db.getConnection()) {
            count = dao.bulkInsert(c, toInsert);
        }

        reloadAllowedOps();

        logAudit(AuditEventType.OP_CHANGE, approver, null, null,
                "opguard.import count=" + count + " total_in_ops=" + opsEntries.size() + " result=success");

        return count;
    }

    // -------------------------------------------------------------------------
    // Audit helper
    // -------------------------------------------------------------------------

    private void logAudit(@NotNull AuditEventType type, @NotNull String actor,
                          @Nullable UUID target, @Nullable String targetName,
                          @NotNull String details) {
        try {
            var m = plugin.getModule("audit");
            if (m instanceof AuditModule am && am.isEnabled()) {
                am.log(type, null, actor, target, targetName, null, details);
            }
        } catch (Throwable ignored) {}
    }
}
