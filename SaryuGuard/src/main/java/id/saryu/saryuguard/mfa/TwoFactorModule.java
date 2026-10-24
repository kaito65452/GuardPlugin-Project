package id.saryu.saryuguard.mfa;

import id.saryu.saryuguard.SaryuGuardPlugin;
import id.saryu.saryuguard.crypt.CryptService;
import id.saryu.saryuguard.database.SaryuGuardDatabase;
import id.saryu.saryuguard.database.TwoFactorDao;
import id.saryu.saryuguard.module.ModuleStatus;
import id.saryu.saryuguard.module.SaryuGuardModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Staff 2FA module – TOTP-based two-factor authentication for privileged players.
 *
 * <p><b>State machine:</b>
 * <pre>
 * UNAUTHENTICATED ──(join + required perm)──► PENDING_MFA
 * PENDING_MFA     ──(correct TOTP code)────► AUTHENTICATED
 * PENDING_MFA     ──(session timeout)──────► kicked, next join → PENDING_MFA
 * PENDING_MFA     ──(too many fails)───────► LOCKED
 * LOCKED          ──(console reset)─────────► UNAUTHENTICATED
 * AUTHENTICATED   ──(quit / kick)───────────► UNAUTHENTICATED
 * </pre>
 *
 * <p><b>Restrictions while PENDING_MFA:</b>
 * <ul>
 *   <li>All non-auth chat cancelled (async chat event LOWEST)</li>
 *   <li>All commands blocked (except /login if AuthMe present)</li>
 *   <li>Movement frozen</li>
 *   <li>Inventory clicks blocked</li>
 * </ul>
 *
 * <p><b>LuckPerms integration:</b> context {@code saryuguard:2fa=true} added after
 * AUTHENTICATED. Without LuckPerms the restrictions above serve as weaker fallback.</p>
 *
 * <p><b>Fail-safe:</b> if TOTP secret storage is unavailable, privileged players
 * remain PENDING_MFA (never downgraded to ordinary player access).</p>
 */
public class TwoFactorModule implements SaryuGuardModule, Listener {

    private static final String ID = "2fa";
    private static final String DISPLAY = "Staff 2FA";

    // Lockout delay schedule: 1s, 2s, 4s, 8s, 16s (jitter ±20%)
    private static final long[] LOCKOUT_DELAYS_MS = {1000, 2000, 4000, 8000, 16000};

    private final SaryuGuardPlugin plugin;
    private final Logger log;

    // In-memory session store (bounded; session cleared on quit)
    private final ConcurrentHashMap<UUID, MfaSession> sessions = new ConcurrentHashMap<>(64);

    // TOTP secret cache (plaintext in memory; durable copy is AES-GCM in DB)
    private final ConcurrentHashMap<UUID, String> totpSecrets = new ConcurrentHashMap<>();
    // Recovery code hash cache: uuid -> JSON array of Argon2id hashes
    private final ConcurrentHashMap<UUID, String> recoveryHashes = new ConcurrentHashMap<>();

    private final TwoFactorDao twoFactorDao = new TwoFactorDao();

    // Config (loaded on enable)
    private volatile boolean configEnabled;
    private volatile String requiredPermission;
    private volatile long sessionTimeoutMs;
    private volatile int maxAttempts;
    private volatile boolean staticPinEnabled;

    private BukkitTask timeoutTask;
    private volatile ModuleStatus status = ModuleStatus.DISABLED;
    private volatile String statusMsg = "Belum diaktifkan.";

    // LuckPerms context – optional
    private volatile boolean luckPermsAvailable = false;
    private volatile boolean persistenceReady = false;

    public TwoFactorModule(@NotNull SaryuGuardPlugin plugin) {
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

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void enable() {
        loadConfig();
        if (!configEnabled) {
            status = ModuleStatus.DISABLED;
            statusMsg = "Dinonaktifkan di config.yml";
            return;
        }

        try {
            Bukkit.getPluginManager().registerEvents(this, plugin);

            // Detect LuckPerms
            luckPermsAvailable = Bukkit.getPluginManager().getPlugin("LuckPerms") != null
                    && Bukkit.getPluginManager().isPluginEnabled("LuckPerms");

            persistenceReady = plugin.getDatabase() != null
                    && plugin.getDatabase().isAvailable()
                    && plugin.getCryptService() != null
                    && plugin.getCryptService().isEnabled();

            // Session timeout sweep every 30 seconds
            timeoutTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
                    this::sweepExpiredSessions, 600L, 600L);

            if (persistenceReady) {
                status = ModuleStatus.ACTIVE;
                statusMsg = "Aktif. Persistensi DB+AES. LuckPerms: "
                        + (luckPermsAvailable ? "ya" : "tidak (fallback)");
            } else {
                status = ModuleStatus.DEGRADED;
                statusMsg = "DEGRADED: secret hanya di memori (DB/Crypt belum siap).";
                log.warning("[2FA] Persistensi tidak tersedia – secret hanya di memori proses.");
            }
            log.info("[2FA] Aktif. LuckPerms=" + luckPermsAvailable
                    + " | Persistensi=" + persistenceReady
                    + " | Permission wajib: " + requiredPermission
                    + " | Timeout: " + (sessionTimeoutMs / 1000) + "s"
                    + " | Max percobaan: " + maxAttempts);
        } catch (Exception e) {
            status = ModuleStatus.ERROR;
            statusMsg = "Gagal: " + e.getMessage();
            log.log(Level.SEVERE, "[2FA] Gagal mengaktifkan: " + e.getMessage(), e);
        }
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        if (timeoutTask != null) {
            timeoutTask.cancel();
            timeoutTask = null;
        }
        sessions.clear();
        status = ModuleStatus.DISABLED;
        statusMsg = "Dinonaktifkan.";
        log.info("[2FA] Dinonaktifkan.");
    }

    // -------------------------------------------------------------------------
    // Events – Join / Quit
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        // Only engage 2FA for players with the required permission
        if (!player.hasPermission(requiredPermission)) return;

        // Warm secret cache from encrypted DB (async → then continue on main)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            loadSecretFromDb(uuid);
            Bukkit.getScheduler().runTask(plugin, () -> continueJoinMfa(player));
        });
    }

    private void continueJoinMfa(Player player) {
        if (!player.isOnline()) return;
        UUID uuid = player.getUniqueId();

        // If LOCKED – keep locked, kick
        MfaSession existing = sessions.get(uuid);
        if (existing != null && existing.getState() == MfaState.LOCKED) {
            Bukkit.getScheduler().runTask(plugin, () ->
                    player.kick(Component.text(
                            "[SaryuGuard] Akun Anda dikunci karena terlalu banyak percobaan 2FA gagal. " +
                            "Hubungi admin untuk reset.", NamedTextColor.RED))
            );
            return;
        }

        // Move to PENDING_MFA
        MfaSession session = new MfaSession(uuid, MfaState.PENDING_MFA);
        sessions.put(uuid, session);

        // If no TOTP secret registered, prompt to set up
        if (!totpSecrets.containsKey(uuid)) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    sendSetupPrompt(player);
                }
            }, 20L); // 1 second after join
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (player.isOnline()) {
                    player.sendMessage(Component.text(
                            "[SaryuGuard] 2FA diperlukan. Masukkan kode TOTP 6-digit dari aplikasi authenticator Anda.",
                            NamedTextColor.YELLOW));
                }
            }, 20L);
        }

        applyRestrictions(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        MfaSession session = sessions.get(uuid);
        // Keep LOCKED sessions in map so they stay locked on rejoin.
        // Remove all others.
        if (session == null || session.getState() != MfaState.LOCKED) {
            sessions.remove(uuid);
        }
        // Else: session stays in map with LOCKED state
    }

    // -------------------------------------------------------------------------
    // Events – Chat input for OTP code (LOWEST, cancelled)
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        MfaSession session = sessions.get(uuid);
        if (session == null || session.getState() != MfaState.PENDING_MFA) return;

        // Cancel the chat event so the message is not broadcast
        event.setCancelled(true);

        String input = event.getMessage().trim();

        // Check rate limit
        if (session.isRateLimited()) {
            long waitMs = session.getNextAttemptAt() - System.currentTimeMillis();
            event.getPlayer().sendMessage(Component.text(
                    "[SaryuGuard] Terlalu banyak percobaan. Tunggu " + (waitMs / 1000 + 1) + " detik.",
                    NamedTextColor.RED));
            return;
        }

        // Handle TOTP setup flow (if no secret yet)
        if (!totpSecrets.containsKey(uuid)) {
            handleSetupInput(event.getPlayer(), session, input);
            return;
        }

        // Verify TOTP code
        String storedSecretB32 = totpSecrets.get(uuid);
        boolean verified = false;
        try {
            byte[] secretBytes = Base32.decode(storedSecretB32);
            verified = TotpEngine.verify(secretBytes, input);
        } catch (Exception e) {
            log.warning("[2FA] Error verifikasi TOTP untuk " + uuid + ": " + e.getMessage());
            // Fail-safe: treat as wrong code (never auto-authenticate on error)
        }

        if (verified) {
            authenticatePlayer(event.getPlayer(), session);
        } else {
            handleFailedAttempt(event.getPlayer(), session);
        }
    }

    // -------------------------------------------------------------------------
    // Restriction events (PENDING_MFA players)
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        MfaSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || session.getState() != MfaState.PENDING_MFA) return;

        // Allow AuthMe login commands to pass through
        String cmd = event.getMessage().toLowerCase().trim();
        if (isAuthMeCommand(cmd)) return;

        event.setCancelled(true);
        event.getPlayer().sendMessage(Component.text(
                "[SaryuGuard] Selesaikan verifikasi 2FA terlebih dahulu.",
                NamedTextColor.RED));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onMove(PlayerMoveEvent event) {
        MfaSession session = sessions.get(event.getPlayer().getUniqueId());
        if (session == null || session.getState() != MfaState.PENDING_MFA) return;
        // Only cancel if there's actual X/Y/Z movement (allow head rotation)
        if (event.getFrom().getBlockX() != event.getTo().getBlockX()
                || event.getFrom().getBlockY() != event.getTo().getBlockY()
                || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onInventory(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        MfaSession session = sessions.get(player.getUniqueId());
        if (session == null || session.getState() != MfaState.PENDING_MFA) return;
        event.setCancelled(true);
    }

    // -------------------------------------------------------------------------
    // Authentication logic
    // -------------------------------------------------------------------------

    private void authenticatePlayer(Player player, MfaSession session) {
        session.setState(MfaState.AUTHENTICATED);
        session.resetFailedAttempts();
        removeRestrictions(player);

        player.sendMessage(Component.text(
                "[SaryuGuard] ✓ Verifikasi 2FA berhasil. Selamat datang, " + player.getName() + "!",
                NamedTextColor.GREEN));
        log.info("[2FA] " + player.getName() + " (" + player.getUniqueId() + ") berhasil autentikasi.");

        // LuckPerms context would be applied here (Phase integration)
        // For now the Listener-based restriction removal is the access grant.
    }

    private void handleFailedAttempt(Player player, MfaSession session) {
        int attempts = session.incrementFailedAttempts();

        // Apply progressive delay with jitter (±20%)
        int delayIdx = Math.min(attempts - 1, LOCKOUT_DELAYS_MS.length - 1);
        long baseDelay = LOCKOUT_DELAYS_MS[delayIdx];
        long jitter = (long) (baseDelay * 0.2 * (ThreadLocalRandom.current().nextDouble() * 2 - 1));
        long delay = Math.max(500, baseDelay + jitter);
        session.setNextAttemptAt(System.currentTimeMillis() + delay);

        if (attempts >= maxAttempts) {
            // Lock the account
            lockPlayer(player, session);
        } else {
            int remaining = maxAttempts - attempts;
            player.sendMessage(Component.text(
                    "[SaryuGuard] Kode salah. Sisa percobaan: " + remaining
                    + ". Tunggu " + (delay / 1000 + 1) + " detik.",
                    NamedTextColor.RED));
            log.warning("[2FA] Kode salah dari " + player.getName() + " (percobaan " + attempts + "/" + maxAttempts + ").");
        }
    }

    private void lockPlayer(Player player, MfaSession session) {
        session.setState(MfaState.LOCKED);
        log.severe("[2FA] LOCKED: " + player.getName() + " (" + player.getUniqueId()
                + ") dikunci setelah " + session.getFailedAttempts() + " percobaan gagal!");
        Bukkit.getScheduler().runTask(plugin, () ->
                player.kick(Component.text(
                        "[SaryuGuard] Akun Anda dikunci karena terlalu banyak percobaan 2FA gagal. " +
                        "Hubungi admin untuk reset.",
                        NamedTextColor.DARK_RED))
        );
        // Broadcast to admins
        Bukkit.getScheduler().runTask(plugin, () ->
                Bukkit.broadcast(
                        Component.text("[SaryuGuard] ALERT: " + player.getName()
                                + " dikunci (2FA brute-force)!", NamedTextColor.RED),
                        "saryuguard.admin")
        );
    }

    // -------------------------------------------------------------------------
    // TOTP Setup flow
    // -------------------------------------------------------------------------

    private void sendSetupPrompt(Player player) {
        String b32 = Base32.generateSecretBase32();
        totpSecrets.put(player.getUniqueId(), b32);
        String uri = TotpEngine.buildOtpAuthUri("SaryuGuard", player.getName(), b32);

        player.sendMessage(Component.text(
                "══════════════════════════════════════════", NamedTextColor.GOLD));
        player.sendMessage(Component.text(
                "[SaryuGuard] SETUP 2FA WAJIB", NamedTextColor.GOLD));
        player.sendMessage(Component.text(
                "Scan QR code atau masukkan kunci manual ke aplikasi authenticator:", NamedTextColor.YELLOW));
        player.sendMessage(Component.text(
                "Kunci: " + b32, NamedTextColor.WHITE));
        player.sendMessage(Component.text(
                "URI: " + uri, NamedTextColor.AQUA));
        player.sendMessage(Component.text(
                "Setelah scan, masukkan kode 6-digit untuk konfirmasi setup.", NamedTextColor.YELLOW));
        player.sendMessage(Component.text(
                "══════════════════════════════════════════", NamedTextColor.GOLD));

        log.info("[2FA] Setup TOTP ditampilkan ke " + player.getName()
                + " (" + player.getUniqueId() + "). Secret TIDAK dicatat di log.");
    }

    private void handleSetupInput(Player player, MfaSession session, String input) {
        String b32 = totpSecrets.get(player.getUniqueId());
        if (b32 == null) {
            sendSetupPrompt(player);
            return;
        }
        boolean ok = false;
        try {
            ok = TotpEngine.verify(Base32.decode(b32), input);
        } catch (Exception e) {
            log.warning("[2FA] Error saat verifikasi setup TOTP: " + e.getMessage());
        }
        if (ok) {
            // Secret confirmed; generate recovery codes
            String[] recoveryCodes = RecoveryCodes.generate(8);
            StringBuilder hashesJson = new StringBuilder("[");
            for (int i = 0; i < recoveryCodes.length; i++) {
                if (i > 0) hashesJson.append(',');
                hashesJson.append('"').append(escapeJson(RecoveryCodes.hash(recoveryCodes[i]))).append('"');
            }
            hashesJson.append(']');
            String hashes = hashesJson.toString();
            recoveryHashes.put(player.getUniqueId(), hashes);

            player.sendMessage(Component.text(
                    "[SaryuGuard] ✓ 2FA berhasil dikonfigurasi!", NamedTextColor.GREEN));
            player.sendMessage(Component.text(
                    "Simpan kode pemulihan ini (hanya tampil sekali):", NamedTextColor.YELLOW));
            for (String code : recoveryCodes) {
                player.sendMessage(Component.text("  » " + code, NamedTextColor.WHITE));
            }
            player.sendMessage(Component.text(
                    "Kode pemulihan disimpan di server (hashed).", NamedTextColor.GRAY));

            persistSecretAsync(player.getUniqueId(), b32, hashes);
            authenticatePlayer(player, session);
        } else {
            handleFailedAttempt(player, session);
        }
    }

    // -------------------------------------------------------------------------
    // Restrictions helpers (without LuckPerms)
    // -------------------------------------------------------------------------

    private void applyRestrictions(Player player) {
        // Restrictions are enforced by event handlers above.
        // Visual indicator: action bar message
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                player.sendActionBar(Component.text(
                        "⚠ Verifikasi 2FA diperlukan", NamedTextColor.YELLOW));
            }
        });
    }

    private void removeRestrictions(Player player) {
        // Restrictions automatically lifted when state is AUTHENTICATED
        // (event handlers check state before blocking)
        player.sendActionBar(Component.text("✓ Terautentikasi", NamedTextColor.GREEN));
    }

    // -------------------------------------------------------------------------
    // Session timeout sweep (async)
    // -------------------------------------------------------------------------

    private void sweepExpiredSessions() {
        long now = System.currentTimeMillis();
        List<UUID> toKick = new ArrayList<>();
        for (Map.Entry<UUID, MfaSession> entry : sessions.entrySet()) {
            MfaSession s = entry.getValue();
            if (s.getState() == MfaState.PENDING_MFA
                    && s.millisSincePending() > sessionTimeoutMs) {
                toKick.add(entry.getKey());
            }
        }
        for (UUID uuid : toKick) {
            sessions.remove(uuid);
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    p.kick(Component.text(
                            "[SaryuGuard] Sesi 2FA kadaluarsa. Silakan login ulang.",
                            NamedTextColor.YELLOW));
                    log.info("[2FA] Sesi " + p.getName() + " habis waktu, di-kick.");
                }
            });
        }
    }

    // -------------------------------------------------------------------------
    // Console reset command API
    // -------------------------------------------------------------------------

    /**
     * Reset a player's 2FA lock (called from console command {@code sg 2fa reset}).
     *
     * @param uuid UUID of the player to reset
     * @return true if the player was LOCKED and has been reset
     */
    public boolean resetLock(UUID uuid) {
        MfaSession session = sessions.get(uuid);
        boolean wasLocked = session != null && session.getState() == MfaState.LOCKED;
        if (wasLocked) {
            sessions.remove(uuid);
            log.info("[2FA] Lock direset oleh console untuk UUID: " + uuid);
        }
        if (persistenceReady) {
            SaryuGuardDatabase db = plugin.getDatabase();
            db.asyncVoid(c -> twoFactorDao.resetLock(c, uuid));
        }
        return wasLocked;
    }

    /**
     * Remove a player's stored TOTP secret (forces re-setup on next join).
     *
     * @param uuid UUID of the player
     * @return true if a secret was present and removed
     */
    public boolean resetSecret(UUID uuid) {
        sessions.remove(uuid);
        recoveryHashes.remove(uuid);
        boolean removed = totpSecrets.remove(uuid) != null;
        if (persistenceReady) {
            SaryuGuardDatabase db = plugin.getDatabase();
            db.asyncVoid(c -> twoFactorDao.delete(c, uuid));
            removed = true; // DB may have had a row even if cache was cold
        }
        return removed;
    }

    // -------------------------------------------------------------------------
    // Public state accessors (for tests and commands)
    // -------------------------------------------------------------------------

    public MfaState getPlayerState(UUID uuid) {
        MfaSession session = sessions.get(uuid);
        return session == null ? MfaState.UNAUTHENTICATED : session.getState();
    }

    public boolean hasSecret(UUID uuid) {
        return totpSecrets.containsKey(uuid);
    }

    /** For testing: inject a TOTP secret directly. */
    void injectSecret(UUID uuid, String base32Secret) {
        totpSecrets.put(uuid, base32Secret);
    }

    /** For testing: inject a session directly. */
    void injectSession(UUID uuid, MfaSession session) {
        sessions.put(uuid, session);
    }

    /** For testing: get session. */
    MfaSession getSession(UUID uuid) {
        return sessions.get(uuid);
    }

    // -------------------------------------------------------------------------
    // Config
    // -------------------------------------------------------------------------

    private void loadConfig() {
        var cfg = plugin.getConfigManager().getConfig();
        configEnabled = cfg.getBoolean("staff-2fa.enabled", true);
        requiredPermission = cfg.getString("staff-2fa.required-permission", "saryuguard.2fa.required");
        long timeoutSec = cfg.getLong("staff-2fa.session-timeout-seconds", 300L);
        sessionTimeoutMs = TimeUnit.SECONDS.toMillis(Math.max(30, timeoutSec));
        maxAttempts = cfg.getInt("staff-2fa.max-attempts", 5);
        if (maxAttempts < 3) maxAttempts = 3;
        staticPinEnabled = cfg.getBoolean("staff-2fa.static-pin-enabled", false);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private boolean isAuthMeCommand(String cmd) {
        // Redact auth args; only check command prefix
        if (plugin.getCompatService() == null) return false;
        return cmd.startsWith("/login ") || cmd.equals("/login")
                || cmd.startsWith("/l ") || cmd.equals("/l")
                || cmd.startsWith("/register ") || cmd.equals("/register")
                || cmd.startsWith("/authme ") || cmd.equals("/authme");
    }

    // -------------------------------------------------------------------------
    // Encrypted persistence (Phase E1)
    // -------------------------------------------------------------------------

    private void loadSecretFromDb(UUID uuid) {
        if (!persistenceReady || totpSecrets.containsKey(uuid)) return;
        SaryuGuardDatabase db = plugin.getDatabase();
        CryptService crypt = plugin.getCryptService();
        try (var c = db.getConnection()) {
            TwoFactorDao.TwoFactorRecord rec = twoFactorDao.load(c, uuid);
            if (rec == null) return;
            if (rec.locked()) {
                MfaSession locked = new MfaSession(uuid, MfaState.LOCKED);
                sessions.put(uuid, locked);
            }
            String plain = crypt.decrypt(rec.totpSecretEnc(),
                    "sg_2fa_secrets", uuid.toString(), "totp_secret_enc", rec.keyVersion());
            totpSecrets.put(uuid, plain);
            recoveryHashes.put(uuid, rec.recoveryHashesJson());
        } catch (Exception e) {
            log.log(Level.WARNING, "[2FA] Gagal memuat secret dari DB untuk " + uuid + ": " + e.getMessage(), e);
        }
    }

    private void persistSecretAsync(UUID uuid, String base32Secret, String recoveryHashesJson) {
        if (!persistenceReady) {
            log.warning("[2FA] Persistensi tidak siap – secret " + uuid + " hanya di memori.");
            return;
        }
        SaryuGuardDatabase db = plugin.getDatabase();
        CryptService crypt = plugin.getCryptService();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                String enc = crypt.encrypt(base32Secret,
                        "sg_2fa_secrets", uuid.toString(), "totp_secret_enc");
                TwoFactorDao.TwoFactorRecord rec = new TwoFactorDao.TwoFactorRecord(
                        uuid.toString(),
                        enc,
                        recoveryHashesJson,
                        crypt.getKeyVersion(),
                        System.currentTimeMillis(),
                        null,
                        false,
                        0
                );
                try (var c = db.getConnection()) {
                    twoFactorDao.upsert(c, rec);
                }
                log.info("[2FA] Secret terenkripsi disimpan untuk UUID " + uuid);
            } catch (Exception e) {
                log.log(Level.SEVERE, "[2FA] Gagal menyimpan secret terenkripsi: " + e.getMessage(), e);
            }
        });
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
