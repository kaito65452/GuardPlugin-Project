package id.saryu.antichatmanager.migration;

import id.saryu.antichatmanager.storage.LiteStorage;
import id.saryu.libcore.database.DatabaseModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class MigrationManagerTest {

    @Test
    @DisplayName("Test 3.x to 4.0.0 migration: real SQLite fixture, source filtering, and idempotency (run twice)")
    void testMigrationIdempotencyAndFiltering(@TempDir Path tempDir) throws Exception {
        File dataDir = tempDir.resolve("plugins/AntiChatManager").toFile();
        assertTrue(dataDir.mkdirs());

        File dbFile = new File(dataDir, "database.db");
        String jdbcUrl = "jdbc:sqlite:" + dbFile.getAbsolutePath();

        // 1. Create real 3.0.2 schema fixture
        try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE acm_meta (id INTEGER PRIMARY KEY, schema_version INTEGER NOT NULL)");
                st.execute("INSERT INTO acm_meta (id, schema_version) VALUES (1, 3)");

                st.execute("CREATE TABLE acm_blacklist ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "command TEXT NOT NULL, "
                        + "match_mode TEXT NOT NULL DEFAULT 'exact', "
                        + "source TEXT NOT NULL DEFAULT 'MANUAL', "
                        + "plugin_name TEXT, "
                        + "permission TEXT, "
                        + "reason TEXT, "
                        + "added_by TEXT, "
                        + "added_at BIGINT NOT NULL, "
                        + "world TEXT, "
                        + "group_name TEXT, "
                        + "allowed_groups TEXT, "
                        + "enabled INTEGER NOT NULL DEFAULT 1, "
                        + "action TEXT NOT NULL DEFAULT 'BLOCK')");

                // Populate with representative 3.0.2 data:
                // - MANUAL: op
                // - CONFIG: stop
                // - IMPORT: deop
                // - LEGACY (empty / unknown source): gamemode
                // - AUTO-SCAN: wand (manual-scan:WorldEdit) -> must be skipped
                // - AUTO-SCAN: lp (LUCKPERMS_SCAN) -> must be skipped
                // - AUTO-SCAN: fly (PLUGIN_SCAN) -> must be skipped
                st.execute("INSERT INTO acm_blacklist (command, source, added_by, added_at, enabled) VALUES ('op', 'MANUAL', 'Admin', 1000, 1)");
                st.execute("INSERT INTO acm_blacklist (command, source, added_by, added_at, enabled) VALUES ('stop', 'CONFIG', 'System', 1001, 1)");
                st.execute("INSERT INTO acm_blacklist (command, source, added_by, added_at, enabled) VALUES ('deop', 'IMPORT', 'Importer', 1002, 1)");
                st.execute("INSERT INTO acm_blacklist (command, source, added_by, added_at, enabled) VALUES ('gamemode', '', 'Unknown', 1003, 1)");
                st.execute("INSERT INTO acm_blacklist (command, source, added_by, added_at, enabled) VALUES ('wand', 'manual-scan:WorldEdit', 'Scanner', 1004, 1)");
                st.execute("INSERT INTO acm_blacklist (command, source, added_by, added_at, enabled) VALUES ('lp', 'LUCKPERMS_SCAN', 'Scanner', 1005, 1)");
                st.execute("INSERT INTO acm_blacklist (command, source, added_by, added_at, enabled) VALUES ('fly', 'PLUGIN_SCAN', 'Scanner', 1006, 1)");
            }
        }

        Logger logger = Logger.getLogger("TestACM");
        TestDbModule testDb = new TestDbModule(jdbcUrl);
        LiteStorage storage = new LiteStorage(testDb, logger);
        storage.init();

        MigrationManager migrator = new MigrationManager(dataDir, logger, storage, testDb);

        // --- FIRST RUN ---
        MigrationManager.MigrationReport report1 = migrator.runMigration();
        assertTrue(report1.success, "Migration 1 should succeed: " + report1.errorMessage);
        assertFalse(report1.skipped, "Migration 1 should not be skipped");
        assertEquals(7, report1.totalOldRows, "Should read 7 rows in total from 3.x table");
        assertEquals(3, report1.manualImported, "Should import op, stop, deop (MANUAL, CONFIG, IMPORT)");
        assertEquals(1, report1.legacyImported, "Should import gamemode as LEGACY");
        assertEquals(3, report1.autoScanSkipped, "Should skip 3 auto-scan entries (wand, lp, fly)");

        // Verify acm_blacklist_v4 database rows
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM acm_blacklist_v4")) {
            assertTrue(rs.next());
            assertEquals(4, rs.getInt(1), "Exactly 4 commands should be imported in v4 table");
        }

        // Verify source labels in v4 table
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT command, source FROM acm_blacklist_v4 ORDER BY command ASC")) {
            assertTrue(rs.next());
            assertEquals("deop", rs.getString("command"));
            assertEquals("IMPORT", rs.getString("source"));

            assertTrue(rs.next());
            assertEquals("gamemode", rs.getString("command"));
            assertEquals("LEGACY", rs.getString("source"));

            assertTrue(rs.next());
            assertEquals("op", rs.getString("command"));
            assertEquals("MANUAL", rs.getString("source"));

            assertTrue(rs.next());
            assertEquals("stop", rs.getString("command"));
            assertEquals("CONFIG", rs.getString("source"));
        }

        // --- SECOND RUN (IDEMPOTENCY TEST) ---
        MigrationManager.MigrationReport report2 = migrator.runMigration();
        assertTrue(report2.success, "Migration 2 should succeed");
        assertTrue(report2.skipped, "Migration 2 should be skipped via idempotency marker");

        // Verify row count is unchanged (no duplicate data)
        try (Connection conn = DriverManager.getConnection(jdbcUrl);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM acm_blacklist_v4")) {
            assertTrue(rs.next());
            assertEquals(4, rs.getInt(1), "Row count must remain exactly 4 after second run");
        }
    }

    @Test
    @DisplayName("Test migration against real server database file from testing-server")
    void testMigrationWithRealServerDb(@TempDir Path tempDir) throws Exception {
        File realDb = new File("../testing-server/plugins/AntiChatManager/data.db");
        if (!realDb.exists()) {
            realDb = new File("testing-server/plugins/AntiChatManager/data.db");
        }
        if (!realDb.exists()) {
            return; // Skip if run in environment without testing-server dir
        }

        File dataDir = tempDir.resolve("AntiChatManager").toFile();
        assertTrue(dataDir.mkdirs());
        File copiedDb = new File(dataDir, "data.db");
        java.nio.file.Files.copy(realDb.toPath(), copiedDb.toPath());

        // Create a dummy config.yml and language dir to test backup
        File cfgFile = new File(dataDir, "config.yml");
        java.nio.file.Files.writeString(cfgFile.toPath(), "test: true");
        File langDir = new File(dataDir, "languages");
        langDir.mkdirs();
        java.nio.file.Files.writeString(new File(langDir, "messages.yml").toPath(), "test-msg: ok");

        String jdbcUrl = "jdbc:sqlite:" + copiedDb.getAbsolutePath();
        Logger logger = Logger.getLogger("RealDbTest");
        TestDbModule testDb = new TestDbModule(jdbcUrl);
        LiteStorage storage = new LiteStorage(testDb, logger);
        storage.init();

        MigrationManager migrator = new MigrationManager(dataDir, logger, storage, testDb);
        MigrationManager.MigrationReport report = migrator.runMigration();

        assertTrue(report.success, "Migration on real server database should succeed: " + report.errorMessage);

        // Verify backup-3.x directory was created and contains backup files
        File backupDir = new File(dataDir, "backup-3.x");
        assertTrue(backupDir.exists() && backupDir.isDirectory(), "backup-3.x should exist");
        File[] backups = backupDir.listFiles();
        assertNotNull(backups);
        assertTrue(backups.length >= 1, "There should be at least one backup folder");

        // Verify idempotency on second run
        MigrationManager.MigrationReport report2 = migrator.runMigration();
        assertTrue(report2.success, "Second migration run should succeed");
        assertTrue(report2.skipped, "Second migration run should be skipped via idempotency");
    }

    private static class TestDbModule extends DatabaseModule {
        private final String jdbcUrl;

        public TestDbModule(String jdbcUrl) {
            super();
            this.jdbcUrl = jdbcUrl;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public DatabaseModule.Type getType() {
            return DatabaseModule.Type.SQLITE;
        }

        @Override
        public Connection getConnection() throws java.sql.SQLException {
            return DriverManager.getConnection(jdbcUrl);
        }

        @Override
        public java.util.concurrent.CompletableFuture<Void> asyncVoid(DatabaseModule.SqlConsumer task) {
            try (Connection conn = getConnection()) {
                task.accept(conn);
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            } catch (Exception e) {
                return java.util.concurrent.CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public <T> java.util.concurrent.CompletableFuture<T> async(DatabaseModule.SqlFunction<T> task) {
            try (Connection conn = getConnection()) {
                return java.util.concurrent.CompletableFuture.completedFuture(task.apply(conn));
            } catch (Exception e) {
                return java.util.concurrent.CompletableFuture.failedFuture(e);
            }
        }
    }
}
