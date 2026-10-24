package id.saryu.saryuguard.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import id.saryu.saryuguard.SaryuGuardPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SaryuGuard's own database – separate from AntiChatManager's DB.
 *
 * <p>Supports SQLite (default, file-based) and H2 (in-process, optional).
 * HikariCP pool is sized conservatively for a lightweight security plugin.</p>
 *
 * <p><b>Schema</b> (versioned via {@code sg_meta}):
 * <ul>
 *   <li>{@code sg_plugin_baseline} – approved jar hashes (from Phase B)</li>
 *   <li>{@code sg_2fa_secrets}     – AES-GCM encrypted TOTP secrets + recovery code hashes</li>
 *   <li>{@code sg_op_baseline}     – allowed-op UUID list with approver + timestamp</li>
 *   <li>{@code sg_audit_log}       – tamper-evident hash-chained audit events</li>
 * </ul>
 *
 * <p><b>Thread safety:</b> HikariCP is thread-safe; all write operations go through
 * {@link #asyncVoid} to keep I/O off the main/event threads.</p>
 *
 * <p><b>Encryption:</b> columns that store sensitive data (IP, audit details, TOTP
 * secret) are encrypted by callers using {@link id.saryu.saryuguard.mfa.SecretCipher}
 * before passing to {@code INSERT}/{@code UPDATE} and decrypted after {@code SELECT}.
 * The database layer itself is encryption-agnostic.</p>
 */
public class SaryuGuardDatabase {

    static {
        // Drivers are shaded into SaryuLibCore; register explicitly (META-INF/services
        // can be collapsed when multiple JDBC jars share one fat jar).
        for (String driver : new String[]{"org.sqlite.JDBC", "org.h2.Driver"}) {
            try {
                Class.forName(driver);
            } catch (Throwable ignored) {
                // Driver may be absent in unit tests that only exercise SQLite.
            }
        }
    }

    public enum DbType { SQLITE, H2 }

    private static final int CURRENT_SCHEMA = 1;
    private static final int POOL_MIN = 1;
    private static final int POOL_MAX = 4;
    private static final long CONN_TIMEOUT_MS = 5_000L;
    private static final long IDLE_TIMEOUT_MS = 300_000L; // 5 min

    private final SaryuGuardPlugin plugin;
    private final Logger log;

    private volatile HikariDataSource dataSource;
    private volatile DbType dbType;
    private volatile boolean available = false;
    private volatile int keyVersion = 1;

    private final ExecutorService asyncExec;

    public SaryuGuardDatabase(@NotNull SaryuGuardPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.asyncExec = Executors.newFixedThreadPool(2, new DaemonThreadFactory("sg-db"));
    }

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Initialise (or reinitialise) the connection pool and apply schema migrations.
     * Safe to call on the main thread; fast-fails cleanly if DB is unreachable.
     *
     * @throws SQLException if the pool cannot open even one connection
     */
    public synchronized void init() throws SQLException {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }

        var cfg = plugin.getConfigManager().getConfig();
        String typeStr = cfg.getString("database.type", "SQLITE").toUpperCase();
        try {
            dbType = DbType.valueOf(typeStr);
        } catch (IllegalArgumentException e) {
            log.warning("[SaryuGuardDB] Jenis database tidak dikenal '" + typeStr + "'. Menggunakan SQLITE.");
            dbType = DbType.SQLITE;
        }

        HikariConfig hc = buildHikariConfig();
        dataSource = new HikariDataSource(hc);

        // Verify + migrate
        try (Connection c = dataSource.getConnection()) {
            applySchema(c);
            this.keyVersion = Math.max(1, readKeyVersion(c));
        }
        available = true;
        log.info("[SaryuGuardDB] Database siap: " + dbType
                + " (schema v" + CURRENT_SCHEMA + ", key_version=" + keyVersion + ")");
    }

    /** Gracefully close the pool and flush pending async operations (best-effort). */
    public synchronized void close() {
        available = false;
        asyncExec.shutdown();
        try {
            if (!asyncExec.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                asyncExec.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        log.info("[SaryuGuardDB] Database ditutup.");
    }

    // -------------------------------------------------------------------------
    // Connection access
    // -------------------------------------------------------------------------

    /**
     * Borrow a connection from the pool. Caller must close it in a try-with-resources.
     */
    public @NotNull Connection getConnection() throws SQLException {
        if (dataSource == null || dataSource.isClosed()) {
            throw new SQLException("SaryuGuard database tidak tersedia.");
        }
        return dataSource.getConnection();
    }

    public boolean isAvailable() { return available; }
    public DbType getDbType() { return dbType; }
    public int getKeyVersion() { return keyVersion; }

    /** Persist encryption key version (called inside an open transaction by CryptService). */
    public void setKeyVersion(@NotNull Connection c, int version) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE sg_meta SET key_version = ? WHERE id = 1")) {
            ps.setInt(1, version);
            ps.executeUpdate();
        }
        this.keyVersion = version;
    }

    // -------------------------------------------------------------------------
    // Async helpers
    // -------------------------------------------------------------------------

    public interface SqlConsumer {
        void accept(Connection c) throws SQLException;
    }

    public interface SqlFunction<T> {
        T apply(Connection c) throws SQLException;
    }

    /**
     * Run a write operation asynchronously. On-write-failure alerts (never blocks gameplay).
     */
    public @NotNull CompletableFuture<Void> asyncVoid(@NotNull SqlConsumer op) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        asyncExec.submit(() -> {
            try (Connection c = getConnection()) {
                op.accept(c);
                future.complete(null);
            } catch (Exception e) {
                log.log(Level.WARNING, "[SaryuGuardDB] Operasi async gagal: " + e.getMessage(), e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /**
     * Run a read operation asynchronously.
     */
    public <T> @NotNull CompletableFuture<T> async(@NotNull SqlFunction<T> op) {
        CompletableFuture<T> future = new CompletableFuture<>();
        asyncExec.submit(() -> {
            try (Connection c = getConnection()) {
                future.complete(op.apply(c));
            } catch (Exception e) {
                log.log(Level.WARNING, "[SaryuGuardDB] Query async gagal: " + e.getMessage(), e);
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    // -------------------------------------------------------------------------
    // Schema
    // -------------------------------------------------------------------------

    private void applySchema(Connection c) throws SQLException {
        ensureMetaTable(c);
        int current = getSchemaVersion(c);
        if (current < 1) {
            applySchemaV1(c);
            setSchemaVersion(c, 1);
            log.info("[SaryuGuardDB] Schema v1 diterapkan.");
        }
        // Future: if (current < 2) { applySchemaV2(c); setSchemaVersion(c, 2); }
    }

    private void ensureMetaTable(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS sg_meta (
                    id             INTEGER PRIMARY KEY,
                    schema_version INTEGER NOT NULL,
                    key_version    INTEGER NOT NULL DEFAULT 1
                )
                """);
        }
        ensureMetaColumn(c, "key_version", "INTEGER NOT NULL DEFAULT 1");
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(*) FROM sg_meta WHERE id = 1")) {
            ResultSet rs = ps.executeQuery();
            if (rs.next() && rs.getInt(1) == 0) {
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO sg_meta (id, schema_version, key_version) VALUES (1, 0, 1)")) {
                    ins.execute();
                }
            }
        }
    }

    private void ensureMetaColumn(Connection c, String column, String definition) {
        try (Statement st = c.createStatement()) {
            st.execute("ALTER TABLE sg_meta ADD COLUMN " + column + " " + definition);
        } catch (SQLException ignored) {
            // Column already exists
        }
    }

    private int readKeyVersion(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT key_version FROM sg_meta WHERE id = 1")) {
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 1;
        } catch (SQLException e) {
            return 1;
        }
    }

    private int getSchemaVersion(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT schema_version FROM sg_meta WHERE id = 1")) {
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    private void setSchemaVersion(Connection c, int v) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE sg_meta SET schema_version = ? WHERE id = 1")) {
            ps.setInt(1, v);
            ps.execute();
        }
    }

    private void applySchemaV1(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            // ----------------------------------------------------------------
            // Plugin baseline: approved jar SHA-256 hashes
            // ----------------------------------------------------------------
            st.execute("""
                CREATE TABLE IF NOT EXISTS sg_plugin_baseline (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    plugin_name  TEXT    NOT NULL,
                    jar_hash     TEXT    NOT NULL,
                    hash_algo    TEXT    NOT NULL DEFAULT 'SHA-256',
                    status       TEXT    NOT NULL DEFAULT 'UNVERIFIED',
                    approver     TEXT,
                    approved_at  INTEGER,
                    created_at   INTEGER NOT NULL,
                    UNIQUE (plugin_name, jar_hash)
                )
                """);

            // ----------------------------------------------------------------
            // 2FA secrets: AES-GCM encrypted TOTP secret + recovery code hashes
            // totp_secret_enc: SecretCipher.encrypt(base32Secret, aad)
            // recovery_hashes: JSON array of Argon2id hashes (one per code)
            // ----------------------------------------------------------------
            st.execute("""
                CREATE TABLE IF NOT EXISTS sg_2fa_secrets (
                    uuid              TEXT    PRIMARY KEY,
                    totp_secret_enc   TEXT    NOT NULL,
                    recovery_hashes   TEXT    NOT NULL DEFAULT '[]',
                    key_version       INTEGER NOT NULL DEFAULT 1,
                    setup_at          INTEGER NOT NULL,
                    last_auth_at      INTEGER,
                    locked            INTEGER NOT NULL DEFAULT 0,
                    failed_attempts   INTEGER NOT NULL DEFAULT 0
                )
                """);

            // ----------------------------------------------------------------
            // OP baseline: UUID-based approved OP list with full audit trail
            // ----------------------------------------------------------------
            st.execute("""
                CREATE TABLE IF NOT EXISTS sg_op_baseline (
                    uuid         TEXT    PRIMARY KEY,
                    player_name  TEXT,
                    approver     TEXT    NOT NULL,
                    approved_at  INTEGER NOT NULL,
                    notes        TEXT
                )
                """);

            // ----------------------------------------------------------------
            // Audit log: tamper-evident hash-chained events
            // details_enc: AES-GCM encrypted JSON of event details
            // ip_enc:      AES-GCM encrypted (or masked) IP address
            // chain_hash:  SHA-256(prev_hash || canonical_event_bytes)
            // ----------------------------------------------------------------
            st.execute("""
                CREATE TABLE IF NOT EXISTS sg_audit_log (
                    id           INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_type   TEXT    NOT NULL,
                    actor_uuid   TEXT,
                    actor_name   TEXT,
                    target_uuid  TEXT,
                    target_name  TEXT,
                    ip_enc       TEXT,
                    details_enc  TEXT,
                    key_version  INTEGER NOT NULL DEFAULT 1,
                    chain_hash   TEXT    NOT NULL,
                    created_at   INTEGER NOT NULL
                )
                """);
            st.execute("CREATE INDEX IF NOT EXISTS idx_audit_created ON sg_audit_log(created_at)");
            st.execute("CREATE INDEX IF NOT EXISTS idx_audit_type ON sg_audit_log(event_type)");
        }
    }

    // -------------------------------------------------------------------------
    // HikariCP configuration
    // -------------------------------------------------------------------------

    private HikariConfig buildHikariConfig() {
        HikariConfig hc = new HikariConfig();
        hc.setMinimumIdle(POOL_MIN);
        hc.setMaximumPoolSize(POOL_MAX);
        hc.setConnectionTimeout(CONN_TIMEOUT_MS);
        hc.setIdleTimeout(IDLE_TIMEOUT_MS);
        hc.setPoolName("SaryuGuard-DB");

        switch (dbType) {
            case SQLITE -> {
                File dbDir = new File(plugin.getDataFolder(), "database");
                dbDir.mkdirs();
                File dbFile = new File(dbDir, "saryuguard.db");
                hc.setDriverClassName("org.sqlite.JDBC");
                hc.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
                hc.setMaximumPoolSize(1); // SQLite is single-writer
                hc.addDataSourceProperty("journal_mode", "WAL");
                hc.addDataSourceProperty("synchronous", "NORMAL");
                hc.addDataSourceProperty("foreign_keys", "ON");
            }
            case H2 -> {
                File dbDir = new File(plugin.getDataFolder(), "database");
                dbDir.mkdirs();
                File dbFile = new File(dbDir, "saryuguard");
                hc.setDriverClassName("org.h2.Driver");
                hc.setJdbcUrl("jdbc:h2:file:" + dbFile.getAbsolutePath()
                        + ";MODE=MySQL;AUTO_SERVER=FALSE");
                hc.setUsername("sa");
                hc.setPassword("");
            }
        }
        return hc;
    }

    // -------------------------------------------------------------------------
    // Thread factory
    // -------------------------------------------------------------------------

    private static class DaemonThreadFactory implements ThreadFactory {
        private final String name;
        private final AtomicInteger counter = new AtomicInteger(0);

        DaemonThreadFactory(String name) { this.name = name; }

        @Override
        public Thread newThread(@NotNull Runnable r) {
            Thread t = new Thread(r, name + "-" + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    }
}
