package id.saryu.saryuguard.opguard;

import id.saryu.saryuguard.SaryuGuardPlugin;
import id.saryu.saryuguard.module.ModuleStatus;
import id.saryu.saryuguard.module.SaryuGuardModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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

    // Config snapshot (set atomically during enable/reload)
    private volatile boolean configEnabled;
    private volatile String mode;          // "enforce" | "warn"
    private volatile long intervalTicks;
    private volatile Set<UUID> allowedOps; // immutable snapshot

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
            // Register listener
            Bukkit.getPluginManager().registerEvents(this, plugin);

            // Start periodic check
            periodicTask = Bukkit.getScheduler().runTaskTimer(plugin,
                    this::runCheck, intervalTicks, intervalTicks);

            running.set(true);
            status = ModuleStatus.ACTIVE;
            statusMsg = "Aktif. Mode: " + mode + " | OP sah: " + allowedOps.size();
            log.info("[OpGuard] Aktif. Mode: " + mode + ", interval: " + intervalTicks + " ticks, " + allowedOps.size() + " OP sah.");
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerLogin(PlayerLoginEvent event) {
        if (!running.get()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        if (!event.getPlayer().isOp()) return;
        if (!allowedOps.contains(uuid)) {
            handleViolation(uuid, event.getPlayer().getName());
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
        if ("enforce".equalsIgnoreCase(mode)) {
            // de-op must happen on main thread
            Bukkit.getScheduler().runTask(plugin, () -> {
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
            });
        } else {
            String msg = "[SaryuGuard] [OpGuard] WARN: " + name + " (" + uuid + ") OP tetapi tidak ada di daftar sah!";
            log.warning(msg);
            Bukkit.getScheduler().runTask(plugin, () ->
                    Bukkit.broadcast(
                            net.kyori.adventure.text.Component.text(msg),
                            "saryuguard.admin"
                    )
            );
        }
    }

    private void handleOfflineViolation(UUID uuid, String name) {
        if ("enforce".equalsIgnoreCase(mode)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                org.bukkit.OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                if (op.isOp()) {
                    op.setOp(false);
                    log.severe("[OpGuard] ENFORCE(offline): " + name + " (" + uuid + ") dide-op.");
                }
            });
        } else {
            log.warning("[OpGuard] WARN(offline): " + name + " (" + uuid + ") OP tapi tidak sah.");
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
        intervalTicks = intervalSec * 20L; // 20 ticks/s

        List<String> raw = cfg.getStringList("opguard.allowed-ops");
        Set<UUID> uuids = new HashSet<>();
        for (String s : raw) {
            try {
                uuids.add(UUID.fromString(s.trim()));
            } catch (IllegalArgumentException e) {
                log.warning("[OpGuard] UUID tidak valid di allowed-ops: '" + s + "'");
            }
        }
        allowedOps = Collections.unmodifiableSet(uuids);
    }

    // -------------------------------------------------------------------------
    // Public API for tests / command
    // -------------------------------------------------------------------------

    /** Returns current allowed-op UUID set (immutable snapshot). */
    public Set<UUID> getAllowedOps() { return allowedOps; }

    /** Returns current mode ("enforce" or "warn"). */
    public String getMode() { return mode; }

    /**
     * Check a specific UUID against the allowed-op list (for test/command use).
     * @return true if the UUID is in the allowed list
     */
    public boolean isAllowed(UUID uuid) {
        return allowedOps.contains(uuid);
    }
}
