package id.saryu.libcore.database;

import id.saryu.libcore.SaryuLib;
import id.saryu.libcore.config.ConfigModule;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.logging.Level;
import javax.sql.DataSource;
import org.jetbrains.annotations.NotNull;

/**
 * Database module: HikariCP-backed MySQL / H2 / SQLite with an async executor,
 * versioned schema migrations, and a graceful file-log fallback.
 *
 * <p>Consuming plugins can call {@link #reconfigure(ConfigModule)} with their own
 * config so their {@code database.*} settings are authoritative while the pool
 * itself stays shared.</p>
 */
public class DatabaseModule {

    public enum Type { MYSQL, H2, SQLITE }

    private final SaryuLib plugin;
    private volatile ConfigModule cfg;
    private volatile Type type;
    private volatile HikariDataSource dataSource;
    private volatile String loadedSignature;
    /*
     * Bounded queue: a misbehaving plugin cannot grow the JVM heap without
     * limit. When full, the oldest queued write is discarded and logged instead
     * of blocking the server thread.
     */
    private final java.util.concurrent.atomic.AtomicLong droppedWrites =
            new java.util.concurrent.atomic.AtomicLong();
    private final ExecutorService asyncDb;
    private volatile int schemaVersion = 1;
    private volatile boolean available = false;

    public DatabaseModule(@NotNull SaryuLib plugin) {
        this.plugin = plugin;
        this.cfg = plugin.getConfigModule();
        this.type = resolveType(cfg);
        this.asyncDb = new java.util.concurrent.ThreadPoolExecutor(
                2, 8, 30, java.util.concurrent.TimeUnit.SECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(5000),
                r -> {
                    Thread t = new Thread(r, "saryulib-db-async");
                    t.setDaemon(true);
                    return t;
                },
                (r, executor) -> {
                    if (executor.isShutdown()) return;
                    Runnable dropped = executor.getQueue().poll();
                    droppedWrites.incrementAndGet();
                    if (dropped != null) {
                        plugin.getLogger().warning("Database write queue full; discarded the oldest pending write.");
                    }
                    try {
                        executor.execute(r);
                    } catch (java.util.concurrent.RejectedExecutionException ignored) {
                    }
                });
        init();
    }

    private static Type resolveType(ConfigModule cfg) {
        String t = cfg.getString("database.type", "SQLITE").toUpperCase(Locale.ROOT);
        try {
            return Type.valueOf(t);
        } catch (IllegalArgumentException e) {
            return Type.SQLITE;
        }
    }

    /**
     * Apply a consumer's config (database.type/host/.../pool-size) to the shared
     * pool. Existing connections are closed and the schema is (re)created.
     */
    public synchronized void reconfigure(@NotNull ConfigModule config) {
        this.cfg = config;
        Type resolved = resolveType(config);
        String signature = signature(config);
        if (resolved == type && signature.equals(loadedSignature) && dataSource != null) {
            // Same settings: keep the live pool (avoids dropping in-flight writes
            // on every /reload).
            return;
        }
        this.type = resolved;
        closePool();
        init();
    }

    private static String signature(@NotNull ConfigModule cfg) {
        return cfg.getDataFolder().getAbsolutePath() + "|"
                + cfg.getString("database.type", "SQLITE") + "|"
                + cfg.getString("database.host", "") + "|"
                + cfg.getInt("database.port", 3306) + "|"
                + cfg.getString("database.database", "") + "|"
                + cfg.getString("database.user", "") + "|"
                + cfg.getString("database.password", "") + "|"
                + cfg.getBoolean("database.ssl", false) + "|"
                + cfg.getInt("database.pool-size", 5);
    }

    private void closePool() {
        HikariDataSource ds = dataSource;
        dataSource = null;
        available = false;
        if (ds != null) {
            try {
                ds.close();
            } catch (Exception ignored) {
            }
        }
    }

    public void init() {
        try {
            switch (type) {
                case MYSQL -> initMySQL();
                case H2 -> initH2();
                case SQLITE -> initSQLite();
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Could not start " + type + " database", t);
            dataSource = null;
        }
        if (dataSource != null) {
            available = testConnection();
            if (available) {
                runMigrations();
            }
        }
        if (!available) {
            plugin.getLogger().warning("Database unavailable (" + type + "); falling back to file logging. Plugin will keep running.");
        } else {
            loadedSignature = signature(cfg);
            plugin.getLogger().info("Database ready: " + type + " (schema v" + schemaVersion + ")");
        }
    }

    private void initMySQL() {
        String host = cfg.getString("database.host", "127.0.0.1");
        int port = cfg.getInt("database.port", 3306);
        String database = cfg.getString("database.database", "antichatmanager");
        String user = cfg.getString("database.user", "root");
        String password = cfg.getString("database.password", "");
        boolean ssl = cfg.getBoolean("database.ssl", false);
        int pool = cfg.getInt("database.pool-size", 10);

        HikariConfig hc = new HikariConfig();
        // Never accept a raw JDBC URL from config. The driver/host/port/db are
        // assembled here, and dangerous connector options stay disabled.
        hc.setJdbcUrl("jdbc:mysql://" + host + ":" + port + "/" + database
                + "?useSSL=" + ssl
                + (ssl ? "&sslMode=REQUIRED" : "&sslMode=DISABLED")
                + "&allowPublicKeyRetrieval=false"
                + "&allowMultiQueries=false"
                + "&allowLoadLocalInfile=false"
                + "&allowUrlInLocalInfile=false"
                + "&autoDeserialize=false"
                + "&serverTimezone=UTC");
        hc.setUsername(user);
        hc.setPassword(password);
        hc.addDataSourceProperty("allowMultiQueries", "false");
        hc.addDataSourceProperty("allowLoadLocalInfile", "false");
        hc.addDataSourceProperty("allowUrlInLocalInfile", "false");
        hc.addDataSourceProperty("autoDeserialize", "false");
        hc.setMaximumPoolSize(pool);
        hc.setConnectionTimeout(5000);
        hc.setIdleTimeout(300000);
        dataSource = new HikariDataSource(hc);
        plugin.getLogger().info("MySQL datasource configured: " + host + ":" + port + "/" + database);
    }

    private void initH2() {
        File dbFile = new File(cfg.getDataFolder(), "data");
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:h2:" + dbFile.getAbsolutePath());
        hc.setUsername("sa");
        hc.setPassword("");
        hc.setMaximumPoolSize(cfg.getInt("database.pool-size", 5));
        dataSource = new HikariDataSource(hc);
        plugin.getLogger().info("H2 database at " + dbFile.getAbsolutePath());
    }

    private void initSQLite() {
        File dbFile = new File(cfg.getDataFolder(), "data.db");
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
        hc.setMaximumPoolSize(cfg.getInt("database.pool-size", 5));
        hc.addDataSourceProperty("busy_timeout", "5000");
        dataSource = new HikariDataSource(hc);
        plugin.getLogger().info("SQLite database at " + dbFile.getAbsolutePath());
    }

    private boolean testConnection() {
        try (Connection c = dataSource.getConnection()) {
            return c.isValid(3);
        } catch (SQLException e) {
            plugin.getLogger().warning("DB connection test failed: " + e.getMessage());
            return false;
        }
    }

    private void runMigrations() {
        try (Connection c = dataSource.getConnection()) {
            ensureMetaTable(c);
            int current = getSchemaVersion(c);
            schemaVersion = current;
            if (current < 1) {
                applySchemaV1(c);
                setSchemaVersion(c, 1);
                schemaVersion = 1;
                current = 1;
            }
            if (current < 2) {
                applySchemaV2(c);
                setSchemaVersion(c, 2);
                schemaVersion = 2;
            }
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Migration failed", e);
        }
    }

    private void ensureMetaTable(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "CREATE TABLE IF NOT EXISTS acm_meta (id INTEGER PRIMARY KEY, schema_version INTEGER NOT NULL)")) {
            ps.execute();
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT schema_version FROM acm_meta WHERE id = 1")) {
            var rs = ps.executeQuery();
            if (rs.next()) schemaVersion = rs.getInt("schema_version");
            else {
                try (PreparedStatement ins = c.prepareStatement("INSERT INTO acm_meta (id, schema_version) VALUES (1, 0)")) {
                    ins.execute();
                }
            }
        }
    }

    public int getSchemaVersion() {
        return schemaVersion;
    }

    private int getSchemaVersion(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT schema_version FROM acm_meta WHERE id = 1")) {
            var rs = ps.executeQuery();
            return rs.next() ? rs.getInt("schema_version") : 0;
        }
    }

    private void setSchemaVersion(Connection c, int v) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE acm_meta SET schema_version = ? WHERE id = 1")) {
            ps.setInt(1, v);
            ps.execute();
        }
    }

    /** {@code id} column definition differs between engines. */
    private String idColumn() {
        return switch (type) {
            case SQLITE -> "id INTEGER PRIMARY KEY AUTOINCREMENT";
            case H2 -> "id BIGINT AUTO_INCREMENT PRIMARY KEY";
            case MYSQL -> "id BIGINT AUTO_INCREMENT PRIMARY KEY";
        };
    }

    private String indexKeyword() {
        // MySQL (< 8.0.29) does not support CREATE INDEX IF NOT EXISTS.
        return type == Type.MYSQL ? "" : "IF NOT EXISTS ";
    }

    private void applySchemaV1(Connection c) {
        String id = idColumn();
        String idx = indexKeyword();
        String[] stmts = {
            // Blacklist entries with source tracking.
            "CREATE TABLE IF NOT EXISTS acm_blacklist ("
            + id + ","
            + "command TEXT NOT NULL,"
            + "match_mode TEXT NOT NULL DEFAULT 'exact',"
            + "source TEXT NOT NULL DEFAULT 'MANUAL',"
            + "plugin_name TEXT,"
            + "permission TEXT,"
            + "reason TEXT,"
            + "added_by TEXT,"
            + "added_at BIGINT NOT NULL,"
            + "world TEXT,"
            + "group_name TEXT,"
            + "enabled INTEGER NOT NULL DEFAULT 1)",
            "CREATE INDEX " + idx + "idx_acm_blacklist_command ON acm_blacklist(command)",

            // Violations log.
            "CREATE TABLE IF NOT EXISTS acm_violations ("
            + id + ","
            + "player_uuid TEXT NOT NULL,"
            + "player_name TEXT NOT NULL,"
            + "command TEXT NOT NULL,"
            + "server TEXT,"
            + "world TEXT,"
            + "source TEXT,"
            + "created_at BIGINT NOT NULL)",
            "CREATE INDEX " + idx + "idx_acm_violations_uuid ON acm_violations(player_uuid)",

            // Violation counts per player (for punishment escalation).
            "CREATE TABLE IF NOT EXISTS acm_violation_counts ("
            + "player_uuid TEXT PRIMARY KEY,"
            + "player_name TEXT NOT NULL,"
            + "count INTEGER NOT NULL DEFAULT 0,"
            + "window_start BIGINT NOT NULL,"
            + "updated_at BIGINT NOT NULL)",
            "CREATE INDEX " + idx + "idx_acm_violation_counts_name ON acm_violation_counts(player_name)",

            // Punishments issued.
            "CREATE TABLE IF NOT EXISTS acm_punishments ("
            + id + ","
            + "player_uuid TEXT NOT NULL,"
            + "player_name TEXT NOT NULL,"
            + "action TEXT NOT NULL,"
            + "duration TEXT,"
            + "reason TEXT,"
            + "issued_by TEXT,"
            + "issued_at BIGINT NOT NULL,"
            + "expires_at BIGINT)",
            "CREATE INDEX " + idx + "idx_acm_punishments_uuid ON acm_punishments(player_uuid)",

            // Scan history.
            "CREATE TABLE IF NOT EXISTS acm_scan_history ("
            + id + ","
            + "plugin_name TEXT,"
            + "command TEXT,"
            + "permission TEXT,"
            + "source TEXT,"
            + "added INTEGER NOT NULL DEFAULT 0,"
            + "scanned_at BIGINT NOT NULL)",
            "CREATE INDEX " + idx + "idx_acm_scan_history_time ON acm_scan_history(scanned_at)"
        };
        for (String sql : stmts) {
            try (Statement stmt = c.createStatement()) {
                stmt.execute(sql);
            } catch (SQLException e) {
                // Indexes may already exist on engines without IF NOT EXISTS.
                plugin.getLogger().log(Level.FINE, "Schema statement skipped: " + sql + " (" + e.getMessage() + ")");
            }
        }
    }

    /**
     * v2: v7 plugin identity cache, LuckPerms conflicts, and security audit
     * findings. All statements are additive; existing v1 data is untouched.
     */
    private void applySchemaV2(Connection c) {
        String id = idColumn();
        String idx = indexKeyword();
        String[] stmts = {
            "CREATE TABLE IF NOT EXISTS acm_plugin_identity ("
            + "plugin_name TEXT PRIMARY KEY,"
            + "jar_hash TEXT,"
            + "jar_hash_sha512 TEXT,"
            + "source TEXT,"
            + "project_slug TEXT,"
            + "project_title TEXT,"
            + "categories TEXT,"
            + "project_version TEXT,"
            + "verified INTEGER NOT NULL DEFAULT 0,"
            + "confidence INTEGER NOT NULL DEFAULT 0,"
            + "fetched_at BIGINT NOT NULL DEFAULT 0,"
            + "expires_at BIGINT NOT NULL DEFAULT 0)",

            "CREATE TABLE IF NOT EXISTS acm_conflicts ("
            + id + ","
            + "command TEXT NOT NULL,"
            + "group_name TEXT NOT NULL,"
            + "permission TEXT,"
            + "severity TEXT NOT NULL DEFAULT 'INFO',"
            + "source TEXT,"
            + "first_seen BIGINT NOT NULL,"
            + "last_seen BIGINT NOT NULL,"
            + "status TEXT NOT NULL DEFAULT 'OPEN',"
            + "notes TEXT,"
            + "UNIQUE(command, group_name, permission))",
            "CREATE INDEX " + idx + "idx_acm_conflicts_status ON acm_conflicts(status)",

            "CREATE TABLE IF NOT EXISTS acm_audit_findings ("
            + id + ","
            + "subject_type TEXT NOT NULL,"
            + "subject_name TEXT NOT NULL,"
            + "node TEXT NOT NULL,"
            + "origin TEXT,"
            + "severity TEXT NOT NULL DEFAULT 'INFO',"
            + "status TEXT NOT NULL DEFAULT 'OPEN',"
            + "first_seen BIGINT NOT NULL,"
            + "last_seen BIGINT NOT NULL,"
            + "note TEXT,"
            + "expires_at BIGINT,"
            + "UNIQUE(subject_type, subject_name, node))",
            "CREATE INDEX " + idx + "idx_acm_audit_findings_status ON acm_audit_findings(status)"
        };
        for (String sql : stmts) {
            try (Statement stmt = c.createStatement()) {
                stmt.execute(sql);
            } catch (SQLException e) {
                plugin.getLogger().log(Level.FINE, "Schema v2 statement skipped: " + sql + " (" + e.getMessage() + ")");
            }
        }
    }

    public long getDroppedWriteCount() {
        return droppedWrites.get();
    }

    public int getPendingWriteCount() {
        return asyncDb instanceof java.util.concurrent.ThreadPoolExecutor pool ? pool.getQueue().size() : 0;
    }

    public @NotNull DataSource getDataSource() {
        if (dataSource == null) throw new IllegalStateException("Database not initialized.");
        return dataSource;
    }

    public boolean isAvailable() {
        return available && dataSource != null;
    }

    public @NotNull Type getType() {
        return type;
    }

    /**
     * Run a blocking DB operation on the async executor.
     */
    @FunctionalInterface
    public interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface SqlConsumer {
        void accept(Connection connection) throws SQLException;
    }

    public <T> @NotNull CompletableFuture<T> async(@NotNull SqlFunction<T> op) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection c = getDataSource().getConnection()) {
                return op.apply(c);
            } catch (SQLException e) {
                throw new CompletionExceptionWrapper(e);
            }
        }, asyncDb);
    }

    public @NotNull CompletableFuture<Void> asyncVoid(@NotNull SqlConsumer op) {
        return CompletableFuture.runAsync(() -> {
            try (Connection c = getDataSource().getConnection()) {
                op.accept(c);
            } catch (SQLException e) {
                throw new CompletionExceptionWrapper(e);
            }
        }, asyncDb);
    }

    public void shutdown() {
        // Drain pending writes before closing the pool, otherwise queued
        // blacklist/violation inserts are lost on server stop.
        asyncDb.shutdown();
        try {
            if (!asyncDb.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Timed out waiting for pending database writes.");
                asyncDb.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            asyncDb.shutdownNow();
        } finally {
            closePool();
        }
    }

    private static final class CompletionExceptionWrapper extends RuntimeException {
        CompletionExceptionWrapper(SQLException cause) { super(cause); }
    }
}
