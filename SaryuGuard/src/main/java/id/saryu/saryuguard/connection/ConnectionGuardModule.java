package id.saryu.saryuguard.connection;

import id.saryu.saryuguard.SaryuGuardPlugin;
import id.saryu.saryuguard.compat.CompatService;
import id.saryu.saryuguard.module.ModuleStatus;
import id.saryu.saryuguard.module.SaryuGuardModule;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.jetbrains.annotations.NotNull;

import java.net.InetAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Connection Guard (Phase E3).
 *
 * <p>Defends against connection flooding, ping spam, username exploits, and bot waves.
 * Integrates dynamically with Sonar and AuthMe (delegating responsibilities when present).</p>
 */
public class ConnectionGuardModule implements SaryuGuardModule, Listener {

    private static final String ID = "connection-guard";
    private static final String DISPLAY = "Connection Guard";

    private final SaryuGuardPlugin plugin;

    // Config snapshots
    private volatile boolean enabled = true;
    private volatile long ipThrottleMs = 3000L;
    private volatile int maxPingsPerSecond = 5;
    private volatile boolean attackModeEnabled = true;
    private volatile long slowdownNewPlayersMs = 5000L;
    private volatile int maxAccountsPerIp = 5;
    private volatile boolean rejoinCheck = false;
    private volatile int rejoinDelaySeconds = 3;
    private volatile Pattern usernamePattern = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");

    private volatile String compatSonar = "auto";
    private volatile String compatAuthMe = "auto";
    private volatile String onMissing = "full";

    // Dynamic Delegation Flags
    private volatile boolean delegateThrottleToSonar = false;
    private volatile boolean delegateAccountToAuthMe = false;

    // Rate limiting tracking structures (bounded)
    private final ConcurrentHashMap<String, Long> lastIpConnect = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Set<String>> accountsPerIp = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastDisconnect = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, PingCounter> pingsPerIp = new ConcurrentHashMap<>();

    // Attack mode wave detection
    private final AtomicInteger recentConnections = new AtomicInteger(0);
    private final AtomicBoolean underAttack = new AtomicBoolean(false);
    private volatile long lastAttackModeCheck = System.currentTimeMillis();

    // Metrics
    private final AtomicLong totalChecked = new AtomicLong(0);
    private final AtomicLong throttledConnections = new AtomicLong(0);
    private final AtomicLong blockedUsernames = new AtomicLong(0);
    private final AtomicLong blockedPings = new AtomicLong(0);

    private volatile ModuleStatus status = ModuleStatus.DISABLED;
    private volatile String statusMsg = "Belum diaktifkan.";

    private static class PingCounter {
        long secondEpoch;
        int count;

        PingCounter(long secondEpoch, int count) {
            this.secondEpoch = secondEpoch;
            this.count = count;
        }
    }

    public ConnectionGuardModule(@NotNull SaryuGuardPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public @NotNull String id() { return ID; }
    @Override public @NotNull String displayName() { return DISPLAY; }
    @Override public boolean isEnabled() { return status == ModuleStatus.ACTIVE || status == ModuleStatus.DEGRADED; }
    @Override public @NotNull ModuleStatus getStatus() { return status; }
    @Override public @NotNull String getStatusMessage() { return statusMsg; }

    @Override
    public synchronized void enable() {
        loadConfig();
        if (!enabled) {
            status = ModuleStatus.DISABLED;
            statusMsg = "Dinonaktifkan di konfigurasi.";
            return;
        }

        evaluateCompatibility();

        Bukkit.getPluginManager().registerEvents(this, plugin);
        status = ModuleStatus.ACTIVE;
        statusMsg = "Proteksi koneksi aktif" + (delegateThrottleToSonar ? " (Sonar delegasi)" : "")
                + (delegateAccountToAuthMe ? " (AuthMe delegasi)" : "") + ".";
        plugin.getLogger().info("[ConnectionGuard] Aktif. SonarDelegasi=" + delegateThrottleToSonar
                + " AuthMeDelegasi=" + delegateAccountToAuthMe);
    }

    @Override
    public synchronized void disable() {
        HandlerList.unregisterAll(this);
        lastIpConnect.clear();
        accountsPerIp.clear();
        lastDisconnect.clear();
        pingsPerIp.clear();
        status = ModuleStatus.DISABLED;
        statusMsg = "Nonaktif.";
    }

    @Override
    public synchronized void reloadConfig() {
        loadConfig();
        evaluateCompatibility();
    }

    public void evaluateCompatibility() {
        CompatService compat = plugin.getCompatService();
        boolean hasSonar = compat != null && compat.isSonarPresent();
        boolean hasAuthMe = compat != null && compat.isAuthMePresent();

        // 1. Sonar delegation: connection throttle, ping limits, attack mode
        if ("on".equalsIgnoreCase(compatSonar) || ("auto".equalsIgnoreCase(compatSonar) && hasSonar)) {
            delegateThrottleToSonar = true;
        } else {
            delegateThrottleToSonar = false;
        }

        // 2. AuthMe delegation: account limit per IP, username validation
        if ("on".equalsIgnoreCase(compatAuthMe) || ("auto".equalsIgnoreCase(compatAuthMe) && hasAuthMe)) {
            delegateAccountToAuthMe = true;
        } else {
            delegateAccountToAuthMe = false;
        }
    }

    private void loadConfig() {
        var cfg = plugin.getConfigManager().getConfig();
        this.enabled = cfg.getBoolean("connection-guard.enabled", true);
        this.ipThrottleMs = cfg.getLong("connection-guard.ip-throttle-ms", 3000L);
        this.maxPingsPerSecond = cfg.getInt("connection-guard.max-pings-per-second", 5);
        this.attackModeEnabled = cfg.getBoolean("connection-guard.attack-mode.enabled", true);
        this.slowdownNewPlayersMs = cfg.getLong("connection-guard.attack-mode.slowdown-new-players-ms", 5000L);
        this.maxAccountsPerIp = cfg.getInt("connection-guard.max-accounts-per-ip", 5);
        this.rejoinCheck = cfg.getBoolean("connection-guard.rejoin-check", false);
        this.rejoinDelaySeconds = cfg.getInt("connection-guard.rejoin-delay-seconds", 3);

        String regex = cfg.getString("connection-guard.username-regex", "^[a-zA-Z0-9_]{3,16}$");
        try {
            this.usernamePattern = Pattern.compile(regex);
        } catch (Exception e) {
            this.usernamePattern = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");
        }

        this.compatSonar = cfg.getString("connection-guard.compat.sonar", "auto");
        this.compatAuthMe = cfg.getString("connection-guard.compat.authme", "auto");
        this.onMissing = cfg.getString("connection-guard.compat.on-missing", "full");
    }

    // -------------------------------------------------------------------------
    // Event Listeners
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (!isEnabled()) return;

        totalChecked.incrementAndGet();
        InetAddress addr = event.getAddress();
        String ip = addr != null ? addr.getHostAddress() : "unknown";
        String name = event.getName();
        UUID uuid = event.getUniqueId();
        long now = System.currentTimeMillis();

        // 1. Username Regex Validation (passive if delegated to AuthMe)
        if (!delegateAccountToAuthMe) {
            if (name == null || !usernamePattern.matcher(name).matches()) {
                blockedUsernames.incrementAndGet();
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        Component.text("Nama pengguna tidak valid! Gunakan 3-16 karakter alfanumerik."));
                return;
            }
        }

        // 2. Rejoin Throttle (if enabled)
        if (rejoinCheck && uuid != null) {
            Long lastQuit = lastDisconnect.get(uuid);
            if (lastQuit != null && (now - lastQuit) < (rejoinDelaySeconds * 1000L)) {
                throttledConnections.incrementAndGet();
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        Component.text("Harap tunggu beberapa detik sebelum masuk kembali."));
                return;
            }
        }

        // 3. IP Connection Throttle (passive if delegated to Sonar)
        if (!delegateThrottleToSonar) {
            Long lastConn = lastIpConnect.get(ip);
            long requiredThrottle = ipThrottleMs;

            // Attack Mode Check
            updateAttackMode(now);
            if (underAttack.get()) {
                OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid != null ? uuid : UUID.nameUUIDFromBytes(name.getBytes()));
                boolean isNewcomer = !offline.hasPlayedBefore();
                if (isNewcomer) {
                    requiredThrottle += slowdownNewPlayersMs;
                }
            }

            if (lastConn != null && (now - lastConn) < requiredThrottle) {
                throttledConnections.incrementAndGet();
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        Component.text("Terlalu banyak upaya koneksi dari IP Anda. Coba lagi dalam beberapa detik."));
                return;
            }
            lastIpConnect.put(ip, now);
        }

        // 4. Accounts per IP limit (CGNAT tolerant; passive if delegated to AuthMe)
        if (!delegateAccountToAuthMe && maxAccountsPerIp > 0 && name != null) {
            Set<String> accounts = accountsPerIp.computeIfAbsent(ip, k -> ConcurrentHashMap.newKeySet());
            if (accounts.size() >= maxAccountsPerIp && !accounts.contains(name.toLowerCase(Locale.ROOT))) {
                throttledConnections.incrementAndGet();
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        Component.text("Batas maksimum akun untuk alamat IP ini telah tercapai (" + maxAccountsPerIp + ")."));
                return;
            }
            accounts.add(name.toLowerCase(Locale.ROOT));
            if (accountsPerIp.size() > 5000) {
                accountsPerIp.clear(); // bound memory
            }
        }

        // Clean up lastIpConnect map if it grows too large
        if (lastIpConnect.size() > 5000) {
            lastIpConnect.entrySet().removeIf(e -> (now - e.getValue()) > 60000L);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onServerListPing(ServerListPingEvent event) {
        if (!isEnabled()) return;
        if (delegateThrottleToSonar) return; // delegate to Sonar

        InetAddress addr = event.getAddress();
        if (addr == null) return;
        String ip = addr.getHostAddress();
        long currentSecond = System.currentTimeMillis() / 1000L;

        PingCounter counter = pingsPerIp.compute(ip, (k, existing) -> {
            if (existing == null || existing.secondEpoch != currentSecond) {
                return new PingCounter(currentSecond, 1);
            }
            existing.count++;
            return existing;
        });

        if (counter.count > maxPingsPerSecond) {
            blockedPings.incrementAndGet();
            // Silence / minimize ping info during flood
            event.setMotd("");
            event.setMaxPlayers(0);
        }

        if (pingsPerIp.size() > 3000) {
            pingsPerIp.entrySet().removeIf(e -> (currentSecond - e.getValue().secondEpoch) > 5);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        if (rejoinCheck && event.getPlayer() != null) {
            lastDisconnect.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
            if (lastDisconnect.size() > 2000) {
                long cutoff = System.currentTimeMillis() - (rejoinDelaySeconds * 2000L);
                lastDisconnect.entrySet().removeIf(e -> e.getValue() < cutoff);
            }
        }
    }

    private void updateAttackMode(long now) {
        if (!attackModeEnabled) {
            underAttack.set(false);
            return;
        }

        int count = recentConnections.incrementAndGet();
        if ((now - lastAttackModeCheck) > 5000L) {
            synchronized (this) {
                if ((now - lastAttackModeCheck) > 5000L) {
                    // If more than 15 connections in last 5 seconds, activate attack mode
                    boolean attack = count > 15;
                    if (attack && !underAttack.get()) {
                        plugin.getLogger().warning("[ConnectionGuard] Mode serangan OTOMATIS AKTIF (laju koneksi tinggi: " + count + "/5s).");
                    } else if (!attack && underAttack.get()) {
                        plugin.getLogger().info("[ConnectionGuard] Mode serangan berakhir, kembali normal.");
                    }
                    underAttack.set(attack);
                    recentConnections.set(0);
                    lastAttackModeCheck = now;
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Metrics & Getters
    // -------------------------------------------------------------------------

    public boolean isDelegateThrottleToSonar() { return delegateThrottleToSonar; }
    public boolean isDelegateAccountToAuthMe() { return delegateAccountToAuthMe; }
    public boolean isUnderAttack() { return underAttack.get(); }
    public long getTotalChecked() { return totalChecked.get(); }
    public long getThrottledConnections() { return throttledConnections.get(); }
    public long getBlockedUsernames() { return blockedUsernames.get(); }
    public long getBlockedPings() { return blockedPings.get(); }
}
