package id.saryu.saryuguard.audit;

import id.saryu.saryuguard.SaryuGuardPlugin;
import id.saryu.saryuguard.crypt.CryptService;
import id.saryu.saryuguard.database.SaryuGuardDatabase;
import id.saryu.saryuguard.module.ModuleStatus;
import id.saryu.saryuguard.module.SaryuGuardModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tamper-evident audit log – single-writer async queue, SHA-256 hash chain,
 * checkpoint file, retention cleanup, and graceful flush (&lt; 5s).
 */
public class AuditModule implements SaryuGuardModule, Listener {

    private static final String ID = "audit";
    private static final String DISPLAY = "Audit & Forensics";
    private static final int QUEUE_CAPACITY = 4000;
    private static final long FLUSH_TIMEOUT_MS = 4_500L;
    private static final String CHECKPOINT_FILE = "audit-checkpoint.txt";

    private final SaryuGuardPlugin plugin;
    private final Logger log;
    private final AuditDao dao = new AuditDao();

    private final BlockingQueue<AuditRecord> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong dropped = new AtomicLong();
    private final Object writeLock = new Object();

    private volatile String headHash = AuditChain.GENESIS_HASH;
    private volatile long headId = 0L;
    private volatile boolean maskIp = true;
    private volatile int retentionDays = 30;
    private volatile String onWriteFailure = "alert";

    private Thread writerThread;
    private BukkitTask checkpointTask;
    private BukkitTask retentionTask;

    private volatile ModuleStatus status = ModuleStatus.DISABLED;
    private volatile String statusMsg = "Belum diaktifkan.";

    public AuditModule(@NotNull SaryuGuardPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    @Override public @NotNull String id() { return ID; }
    @Override public @NotNull String displayName() { return DISPLAY; }
    @Override public boolean isEnabled() {
        return status == ModuleStatus.ACTIVE || status == ModuleStatus.DEGRADED;
    }
    @Override public @NotNull ModuleStatus getStatus() { return status; }
    @Override public @NotNull String getStatusMessage() { return statusMsg; }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void enable() {
        loadConfig();
        SaryuGuardDatabase db = plugin.getDatabase();
        if (db == null || !db.isAvailable()) {
            status = ModuleStatus.ERROR;
            statusMsg = "Database tidak tersedia.";
            log.severe("[Audit] Database tidak siap – modul audit dinonaktifkan.");
            return;
        }

        try {
            loadHeadFromDb(db);
            loadOrCreateCheckpoint();
            running.set(true);
            writerThread = new Thread(this::writerLoop, "sg-audit-writer");
            writerThread.setDaemon(true);
            writerThread.start();

            Bukkit.getPluginManager().registerEvents(this, plugin);

            // Checkpoint every 5 minutes
            checkpointTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
                    this::writeCheckpointSafe, 20L * 60 * 5, 20L * 60 * 5);
            // Retention daily
            retentionTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin,
                    this::runRetention, 20L * 60, 20L * 60 * 60 * 24);

            status = ModuleStatus.ACTIVE;
            statusMsg = "Aktif. head_id=" + headId + " antrian=" + QUEUE_CAPACITY;
            log.info("[Audit] Aktif. Hash chain head=" + shortHash(headHash)
                    + " id=" + headId + " mask-ip=" + maskIp);
            offer(AuditRecord.of(AuditEventType.SYSTEM, null, "SYSTEM", "audit module enabled"));
        } catch (Exception e) {
            status = ModuleStatus.ERROR;
            statusMsg = "Gagal: " + e.getMessage();
            log.log(Level.SEVERE, "[Audit] Gagal mengaktifkan: " + e.getMessage(), e);
        }
    }

    @Override
    public void disable() {
        running.set(false);
        HandlerList.unregisterAll(this);
        if (checkpointTask != null) {
            checkpointTask.cancel();
            checkpointTask = null;
        }
        if (retentionTask != null) {
            retentionTask.cancel();
            retentionTask = null;
        }
        flushAndStop();
        writeCheckpointSafe();
        status = ModuleStatus.DISABLED;
        statusMsg = "Dinonaktifkan.";
        log.info("[Audit] Dinonaktifkan. Dropped=" + dropped.get());
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Enqueue an audit event (non-blocking; drops oldest on overflow). */
    public void offer(@NotNull AuditRecord record) {
        if (!running.get()) return;
        if (!queue.offer(record)) {
            queue.poll(); // drop oldest
            if (!queue.offer(record)) {
                long n = dropped.incrementAndGet();
                if ("alert".equalsIgnoreCase(onWriteFailure) && n % 50 == 1) {
                    log.warning("[Audit] Antrian penuh – peristiwa dibuang (total dropped=" + n + ")");
                }
            } else {
                dropped.incrementAndGet();
            }
        }
    }

    public void log(@NotNull AuditEventType type,
                    @Nullable UUID actor,
                    @Nullable String actorName,
                    @Nullable String details) {
        offer(new AuditRecord(type, System.currentTimeMillis(),
                actor != null ? actor.toString() : null, actorName,
                null, null, null, details));
    }

    public void log(@NotNull AuditEventType type,
                    @Nullable UUID actor,
                    @Nullable String actorName,
                    @Nullable UUID target,
                    @Nullable String targetName,
                    @Nullable String ip,
                    @Nullable String details) {
        offer(new AuditRecord(type, System.currentTimeMillis(),
                actor != null ? actor.toString() : null, actorName,
                target != null ? target.toString() : null, targetName,
                ip, details));
    }

    public long getDroppedCount() { return dropped.get(); }
    public @NotNull String getHeadHash() { return headHash; }
    public long getHeadId() { return headId; }

    public @NotNull List<AuditDao.StoredRow> listRecent(int limit) throws Exception {
        SaryuGuardDatabase db = plugin.getDatabase();
        if (db == null || !db.isAvailable()) return List.of();
        try (Connection c = db.getConnection()) {
            return dao.listRecent(c, limit);
        }
    }

    /**
     * Verify the entire hash chain by decrypting details/IP and recomputing hashes.
     *
     * @return verification result
     */
    public @NotNull VerifyResult verifyChain() throws Exception {
        SaryuGuardDatabase db = plugin.getDatabase();
        CryptService crypt = plugin.getCryptService();
        if (db == null || !db.isAvailable()) {
            return VerifyResult.failed("Database tidak tersedia");
        }
        // Ensure writer is idle for consistent snapshot
        synchronized (writeLock) {
            try (Connection c = db.getConnection()) {
                List<AuditDao.ChainLink> links = dao.listAscending(c);
                String prev = db.getRetentionAnchorHash(c);
                int checked = 0;
                for (AuditDao.ChainLink link : links) {
                    String detailsPlain = decryptField(crypt, link.detailsEnc(),
                            "sg_audit_log", Long.toString(link.id()), "details_enc", link.keyVersion());
                    String ipPlain = decryptField(crypt, link.ipEnc(),
                            "sg_audit_log", Long.toString(link.id()), "ip_enc", link.keyVersion());
                    byte[] canonical = AuditChain.canonicalBytes(
                            link.eventType(), link.createdAt(),
                            link.actorUuid(), link.actorName(),
                            link.targetUuid(), link.targetName(),
                            detailsPlain, ipPlain);
                    String expected = AuditChain.nextHash(prev, canonical);
                    if (!expected.equalsIgnoreCase(link.chainHash())) {
                        return VerifyResult.failed(
                                "Rantai rusak pada id=" + link.id()
                                        + " (diharapkan " + shortHash(expected)
                                        + ", didapat " + shortHash(link.chainHash()) + ")");
                    }
                    prev = link.chainHash();
                    checked++;
                }
                if (!prev.equalsIgnoreCase(headHash) && checked > 0) {
                    // Head may lag if checkpoint stale; update from DB
                    headHash = prev;
                    headId = links.get(links.size() - 1).id();
                }
                return VerifyResult.ok(checked, prev);
            }
        }
    }

    /**
     * Export recent audit rows to a JSONL file under {@code exports/}.
     *
     * @return path of written file
     */
    public @NotNull Path exportRecent(int limit) throws Exception {
        List<AuditDao.StoredRow> rows = listRecent(limit);
        CryptService crypt = plugin.getCryptService();
        Path dir = plugin.getDataFolder().toPath().resolve("exports");
        Files.createDirectories(dir);
        Path out = dir.resolve("audit-export-" + System.currentTimeMillis() + ".jsonl");
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            for (int i = rows.size() - 1; i >= 0; i--) { // chronological
                AuditDao.StoredRow r = rows.get(i);
                String details = decryptField(crypt, r.detailsEnc(),
                        "sg_audit_log", Long.toString(r.id()), "details_enc", r.keyVersion());
                String ip = decryptField(crypt, r.ipEnc(),
                        "sg_audit_log", Long.toString(r.id()), "ip_enc", r.keyVersion());
                w.write("{\"id\":" + r.id()
                        + ",\"type\":\"" + esc(r.eventType()) + "\""
                        + ",\"created_at\":" + r.createdAt()
                        + ",\"actor\":\"" + esc(r.actorName()) + "\""
                        + ",\"target\":\"" + esc(r.targetName()) + "\""
                        + ",\"ip\":\"" + esc(ip) + "\""
                        + ",\"details\":\"" + esc(details) + "\""
                        + ",\"chain_hash\":\"" + esc(r.chainHash()) + "\"}");
                w.newLine();
            }
        }
        return out;
    }

    public record VerifyResult(boolean ok, int checked, @NotNull String headHash, @Nullable String error) {
        public static VerifyResult ok(int checked, String head) {
            return new VerifyResult(true, checked, head, null);
        }
        public static VerifyResult failed(String error) {
            return new VerifyResult(false, 0, AuditChain.GENESIS_HASH, error);
        }
    }

    // -------------------------------------------------------------------------
    // Event listeners
    // -------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        String ip = null;
        if (p.getAddress() != null && p.getAddress().getAddress() != null) {
            ip = p.getAddress().getAddress().getHostAddress();
        }
        log(AuditEventType.PLAYER_JOIN, p.getUniqueId(), p.getName(),
                null, null, ip, "join");
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        log(AuditEventType.PLAYER_QUIT, p.getUniqueId(), p.getName(), "quit");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String msg = event.getMessage();
        if (AuditRedaction.isAuthCommand(msg)) {
            // Never log auth arguments – record label only if we log at all.
            // Per threat model: auth args never logged/saved. Skip entirely.
            return;
        }
        if (!AuditRedaction.isSensitiveCommand(msg)) return;
        Player p = event.getPlayer();
        String redacted = AuditRedaction.redactCommand(msg);
        log(AuditEventType.SENSITIVE_COMMAND, p.getUniqueId(), p.getName(), redacted);
    }

    // -------------------------------------------------------------------------
    // Writer loop
    // -------------------------------------------------------------------------

    private void writerLoop() {
        while (running.get() || !queue.isEmpty()) {
            AuditRecord rec = null;
            try {
                rec = queue.poll(200, TimeUnit.MILLISECONDS);
                if (rec == null) continue;

                boolean written = false;
                for (int attempt = 0; attempt < 3; attempt++) {
                    try {
                        persist(rec);
                        written = true;
                        break;
                    } catch (Exception ex) {
                        if (attempt < 2) {
                            try {
                                Thread.sleep(50L * (attempt + 1));
                            } catch (InterruptedException ie) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        } else {
                            throw ex;
                        }
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.log(Level.WARNING, "[Audit] Gagal menulis peristiwa: " + e.getMessage(), e);
                if ("alert".equalsIgnoreCase(onWriteFailure)) {
                    status = ModuleStatus.DEGRADED;
                    statusMsg = "DEGRADED: write failure – " + e.getMessage();
                }
            }
        }
    }

    private void persist(@NotNull AuditRecord rec) throws Exception {
        SaryuGuardDatabase db = plugin.getDatabase();
        CryptService crypt = plugin.getCryptService();
        if (db == null || !db.isAvailable()) {
            throw new IllegalStateException("DB unavailable");
        }

        String ipPlain = AuditRedaction.maskIp(rec.ipPlain, maskIp);
        String detailsPlain = rec.detailsPlain;

        synchronized (writeLock) {
            String prev = headHash;
            byte[] canonical = AuditChain.canonicalBytes(
                    rec.type.code(), rec.createdAt,
                    rec.actorUuid, rec.actorName,
                    rec.targetUuid, rec.targetName,
                    detailsPlain, ipPlain);
            String chainHash = AuditChain.nextHash(prev, canonical);

            // Insert with placeholder then encrypt using real row id for AAD
            try (Connection c = db.getConnection()) {
                c.setAutoCommit(false);
                try {
                    long id = dao.insert(c, rec.type.code(),
                            rec.actorUuid, rec.actorName,
                            rec.targetUuid, rec.targetName,
                            null, null, // fill after we know id
                            crypt != null && crypt.isEnabled() ? crypt.getKeyVersion() : 1,
                            chainHash, rec.createdAt);

                    String ipEnc = null;
                    String detailsEnc = null;
                    int keyVer = 1;
                    if (crypt != null && crypt.isEnabled()) {
                        keyVer = crypt.getKeyVersion();
                        String rowId = Long.toString(id);
                        if (ipPlain != null && !ipPlain.isBlank()) {
                            ipEnc = crypt.encrypt(ipPlain, "sg_audit_log", rowId, "ip_enc");
                        }
                        if (detailsPlain != null) {
                            detailsEnc = crypt.encrypt(detailsPlain, "sg_audit_log", rowId, "details_enc");
                        }
                        try (var ps = c.prepareStatement(
                                "UPDATE sg_audit_log SET ip_enc = ?, details_enc = ?, key_version = ? WHERE id = ?")) {
                            if (ipEnc != null) ps.setString(1, ipEnc); else ps.setNull(1, java.sql.Types.VARCHAR);
                            if (detailsEnc != null) ps.setString(2, detailsEnc); else ps.setNull(2, java.sql.Types.VARCHAR);
                            ps.setInt(3, keyVer);
                            ps.setLong(4, id);
                            ps.executeUpdate();
                        }
                    } else {
                        // Store plaintext when encryption disabled (still hash-chained)
                        try (var ps = c.prepareStatement(
                                "UPDATE sg_audit_log SET ip_enc = ?, details_enc = ? WHERE id = ?")) {
                            if (ipPlain != null) ps.setString(1, ipPlain); else ps.setNull(1, java.sql.Types.VARCHAR);
                            if (detailsPlain != null) ps.setString(2, detailsPlain); else ps.setNull(2, java.sql.Types.VARCHAR);
                            ps.setLong(3, id);
                            ps.executeUpdate();
                        }
                    }
                    c.commit();
                    headHash = chainHash;
                    headId = id;
                } catch (Exception e) {
                    c.rollback();
                    throw e;
                } finally {
                    c.setAutoCommit(true);
                }
            }
        }
    }

    private void flushAndStop() {
        long deadline = System.currentTimeMillis() + FLUSH_TIMEOUT_MS;
        if (writerThread != null) {
            writerThread.interrupt();
            try {
                long wait = Math.max(100, deadline - System.currentTimeMillis());
                writerThread.join(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            // Drain remaining on this thread within leftover budget
            while (!queue.isEmpty() && System.currentTimeMillis() < deadline) {
                AuditRecord rec = queue.poll();
                if (rec == null) break;
                try {
                    persist(rec);
                } catch (Exception e) {
                    log.warning("[Audit] Flush gagal: " + e.getMessage());
                    break;
                }
            }
            writerThread = null;
        }
        if (!queue.isEmpty()) {
            log.warning("[Audit] Shutdown flush timeout – sisa antrian: " + queue.size());
            queue.clear();
        }
    }

    // -------------------------------------------------------------------------
    // Checkpoint / retention
    // -------------------------------------------------------------------------

    private void loadHeadFromDb(SaryuGuardDatabase db) throws Exception {
        try (Connection c = db.getConnection()) {
            String hash = dao.latestChainHash(c);
            if (hash != null && AuditChain.isValidHashHex(hash)) {
                headHash = hash;
                headId = dao.latestId(c);
            } else {
                headHash = AuditChain.GENESIS_HASH;
                headId = 0L;
            }
        }
    }

    private Path checkpointPath() {
        return plugin.getDataFolder().toPath().resolve(CHECKPOINT_FILE);
    }

    private void loadOrCreateCheckpoint() {
        Path path = checkpointPath();
        try {
            if (Files.exists(path)) {
                for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                    if (line.startsWith("head_hash=")) {
                        String h = line.substring("head_hash=".length()).trim();
                        if (AuditChain.isValidHashHex(h) && headId == 0L) {
                            // Only use checkpoint hash if DB was empty
                            headHash = h;
                        }
                    }
                }
            }
            writeCheckpointSafe();
        } catch (IOException e) {
            log.warning("[Audit] Gagal membaca checkpoint: " + e.getMessage());
        }
    }

    private void writeCheckpointSafe() {
        try {
            Path path = checkpointPath();
            Files.createDirectories(path.getParent());
            String body = "head_hash=" + headHash + "\n"
                    + "last_id=" + headId + "\n"
                    + "timestamp=" + System.currentTimeMillis() + "\n";
            Path tmp = path.resolveSibling(CHECKPOINT_FILE + ".tmp");
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, path,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.log(Level.FINE, "[Audit] Checkpoint write failed: " + e.getMessage());
        }
    }

    private void runRetention() {
        if (retentionDays <= 0) return;
        SaryuGuardDatabase db = plugin.getDatabase();
        if (db == null || !db.isAvailable()) return;
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(retentionDays);
        synchronized (writeLock) {
            try (Connection c = db.getConnection()) {
                String lastDeletedHash = dao.latestChainHashBefore(c, cutoff);
                int deleted = dao.deleteOlderThan(c, cutoff);
                if (deleted > 0) {
                    if (lastDeletedHash != null) {
                        db.setRetentionAnchorHash(c, lastDeletedHash);
                    }
                    log.info("[Audit] Retensi: menghapus " + deleted + " baris lebih tua dari "
                            + retentionDays + " hari.");
                    // Re-anchor head from DB
                    String hash = dao.latestChainHash(c);
                    if (hash != null) {
                        headHash = hash;
                        headId = dao.latestId(c);
                    } else {
                        headHash = lastDeletedHash != null ? lastDeletedHash : AuditChain.GENESIS_HASH;
                        headId = 0L;
                    }
                    writeCheckpointSafe();
                }
            } catch (Exception e) {
                log.warning("[Audit] Retensi gagal: " + e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void loadConfig() {
        var cfg = plugin.getConfigManager().getConfig();
        retentionDays = cfg.getInt("database.retention-days", 30);
        onWriteFailure = cfg.getString("database.on-write-failure", "alert");
        maskIp = cfg.getBoolean("database.encryption.mask-ip", true);
    }

    private static @Nullable String decryptField(@Nullable CryptService crypt,
                                                 @Nullable String enc,
                                                 String table, String rowId, String col,
                                                 int keyVer) throws Exception {
        if (enc == null || enc.isBlank()) return enc;
        if (crypt == null || !crypt.isEnabled()) return enc; // stored plaintext
        // Ciphertext is Base64 – if it doesn't look encrypted, return as-is
        try {
            return crypt.decrypt(enc, table, rowId, col, keyVer);
        } catch (Exception e) {
            // Fallback: treat as plaintext legacy
            return enc;
        }
    }

    private static String shortHash(String h) {
        if (h == null || h.length() < 8) return String.valueOf(h);
        return h.substring(0, 8) + "…";
    }

    private static String esc(@Nullable String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
