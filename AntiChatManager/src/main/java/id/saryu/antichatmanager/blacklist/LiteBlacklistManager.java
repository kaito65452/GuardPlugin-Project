package id.saryu.antichatmanager.blacklist;

import id.saryu.antichatmanager.policy.BlacklistSnapshot;
import id.saryu.antichatmanager.policy.PolicyStatus;
import id.saryu.antichatmanager.storage.LiteStorage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Manages atomic blacklist snapshots and policy status transitions (READY, DEGRADED, UNAVAILABLE).
 *
 * <p>Requirements:
 * - Empty cache is NEVER considered an empty blacklist.
 * - Failed reload preserves previous valid configuration and cache.
 * - Command event never touches the database. Cache is an immutable snapshot replaced atomically.
 * - add validation: alphanumeric, _, -, :, bounded length, reject duplicates.</p>
 */
public class LiteBlacklistManager {

    private static final Pattern VALID_COMMAND = Pattern.compile("^[a-zA-Z0-9_\\-:]+$");
    private static final int MAX_COMMAND_LENGTH = 64;

    private final JavaPlugin plugin;
    private final LiteStorage storage;

    private final AtomicReference<BlacklistSnapshot> snapshot = new AtomicReference<>(BlacklistSnapshot.empty());
    private volatile PolicyStatus status = PolicyStatus.UNAVAILABLE;
    private final List<String> emergencyBlacklist = new ArrayList<>(List.of("op", "deop", "stop", "reload", "restart"));

    private final AtomicLong lastDegradedAlert = new AtomicLong(0);
    private static final long DEGRADED_ALERT_COOLDOWN_MS = 60_000L; // 1 minute throttle

    public LiteBlacklistManager(@NotNull JavaPlugin plugin, @NotNull LiteStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    public synchronized void setEmergencyList(@NotNull List<String> list) {
        emergencyBlacklist.clear();
        if (list.isEmpty()) {
            emergencyBlacklist.addAll(List.of("op", "deop", "stop", "reload", "restart"));
        } else {
            emergencyBlacklist.addAll(list);
        }
    }

    /**
     * Load policy from database.
     *
     * @return CompletableFuture resolving to true if policy is READY, false if DEGRADED or UNAVAILABLE
     */
    public synchronized CompletableFuture<Boolean> loadPolicy() {
        return storage.loadEntries().handle((entries, ex) -> {
            if (ex != null || entries == null) {
                // Database failed
                if (!snapshot.get().isEmpty() && status != PolicyStatus.UNAVAILABLE) {
                    // Previous valid cache exists -> DEGRADED
                    status = PolicyStatus.DEGRADED;
                    alertDegraded("Database gagal merespon; menggunakan cache kebijakan valid sebelumnya.");
                    return false;
                } else {
                    // No valid cache ever loaded -> UNAVAILABLE
                    status = PolicyStatus.UNAVAILABLE;
                    snapshot.set(BlacklistSnapshot.ofEmergency(emergencyBlacklist));
                    logUnavailableError("Database tidak dapat diakses saat startup dan cache kosong! AntiChatManager beralih ke EMERGENCY-BLACKLIST (op, deop, stop, reload, restart).");
                    return false;
                }
            }

            if (entries.isEmpty()) {
                // Cache kosong tidak pernah dianggap blacklist kosong jika belum ada kebijakan
                if (!snapshot.get().isEmpty()) {
                    // Keep previous valid cache
                    status = PolicyStatus.DEGRADED;
                    alertDegraded("Database mengembalikan tabel blacklist kosong. Mempertahankan cache sebelumnya.");
                    return false;
                } else {
                    status = PolicyStatus.UNAVAILABLE;
                    snapshot.set(BlacklistSnapshot.ofEmergency(emergencyBlacklist));
                    logUnavailableError("Tabel blacklist kosong! Mengaktifkan emergency-blacklist statis demi keamanan server.");
                    return false;
                }
            }

            // Successfully loaded valid entries -> READY
            snapshot.set(new BlacklistSnapshot(entries));
            status = PolicyStatus.READY;
            plugin.getLogger().info("Kebijakan blacklist berhasil dimuat: " + entries.size() + " entri aktif (Status: READY).");
            return true;
        });
    }

    /**
     * Validates command string for adding.
     *
     * @param command Raw command input
     * @return null if valid, or error message string if invalid
     */
    public @Nullable String validateCommand(@Nullable String command) {
        if (command == null || command.isBlank()) {
            return "Perintah tidak boleh kosong.";
        }
        String clean = BlacklistSnapshot.normalize(command);
        if (clean.length() > MAX_COMMAND_LENGTH) {
            return "Panjang perintah melebihi batas maksimum (" + MAX_COMMAND_LENGTH + " karakter).";
        }
        if (!VALID_COMMAND.matcher(clean).matches()) {
            return "Perintah hanya boleh mengandung huruf, angka, '_', '-', dan ':'.";
        }
        if (snapshot.get().matches(clean)) {
            return "Perintah '" + clean + "' sudah ada di dalam blacklist.";
        }
        return null;
    }

    public CompletableFuture<Boolean> addCommand(@NotNull String command, @NotNull String addedBy) {
        String clean = BlacklistSnapshot.normalize(command);
        return storage.addEntry(clean, "MANUAL", addedBy).thenCompose(success -> {
            if (success) {
                return loadPolicy();
            }
            return CompletableFuture.completedFuture(false);
        });
    }

    public CompletableFuture<Boolean> removeCommand(@NotNull String command) {
        String clean = BlacklistSnapshot.normalize(command);
        return storage.removeEntry(clean).thenCompose(success -> {
            if (success) {
                return loadPolicy();
            }
            return CompletableFuture.completedFuture(false);
        });
    }

    public boolean isBlocked(@NotNull String cmdToken) {
        return snapshot.get().matches(cmdToken);
    }

    public boolean isBlockedAlias(@NotNull String canonicalName, @Nullable List<String> aliases) {
        return snapshot.get().matchesAlias(canonicalName, aliases);
    }

    public @NotNull BlacklistSnapshot getSnapshot() {
        return snapshot.get();
    }

    public @NotNull PolicyStatus getStatus() {
        return status;
    }

    public void alertDegraded(@NotNull String reason) {
        long now = System.currentTimeMillis();
        long last = lastDegradedAlert.get();
        if (now - last < DEGRADED_ALERT_COOLDOWN_MS) {
            return;
        }
        if (!lastDegradedAlert.compareAndSet(last, now)) {
            return;
        }

        plugin.getLogger().warning("[POLICY DEGRADED] " + reason);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("antichatmanager.admin")) {
                p.sendMessage("§6[ACM DEGRADED] §c" + reason);
            }
        }
    }

    private void logUnavailableError(@NotNull String message) {
        plugin.getLogger().severe("======================================================================");
        plugin.getLogger().severe("[SECURITY CRITICAL - POLICY UNAVAILABLE]");
        plugin.getLogger().severe(message);
        plugin.getLogger().severe("======================================================================");
    }
}
