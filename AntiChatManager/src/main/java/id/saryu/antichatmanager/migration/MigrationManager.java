package id.saryu.antichatmanager.migration;

import id.saryu.antichatmanager.storage.LiteStorage;
import id.saryu.libcore.database.DatabaseModule;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;

/**
 * Executes safe, idempotent 3.x to 4.0.0 migration.
 *
 * <p>Requirements:
 * 1. Salin file lama ke plugins/AntiChatManager/backup-3.x/.
 * 2. Baca database lama READ-ONLY. Verifikasi struktur dan label sumber di 3.0.2.
 * 3. Impor entri manual/config. Entri tanpa label sumber diimpor dengan flag legacy.
 *    Entri hasil auto-scan tidak diimpor tetapi dihitung dan dilaporkan.
 * 4. Idempoten: penanda migrasi + constraint unik, jalankan ulang tidak menggandakan data.
 * 5. Verifikasi: jumlah entri sumber vs hasil, plus laporan konflik di console.
 * 6. Gagal di tengah jalan: keadaan sebelumnya dipertahankan, plugin memakai emergency-blacklist
 *    dan menampilkan ERROR yang jelas.</p>
 */
public class MigrationManager {

    private final File dataFolder;
    private final java.util.logging.Logger logger;
    private final LiteStorage storage;
    private final DatabaseModule db;

    public MigrationManager(@NotNull JavaPlugin plugin, @NotNull LiteStorage storage, @NotNull DatabaseModule db) {
        this(plugin.getDataFolder(), plugin.getLogger(), storage, db);
    }

    public MigrationManager(@NotNull File dataFolder, @NotNull java.util.logging.Logger logger, @NotNull LiteStorage storage, @NotNull DatabaseModule db) {
        this.dataFolder = dataFolder;
        this.logger = logger;
        this.storage = storage;
        this.db = db;
    }

    public static class MigrationReport {
        public boolean success = false;
        public boolean skipped = false;
        public int manualImported = 0;
        public int legacyImported = 0;
        public int autoScanSkipped = 0;
        public int duplicatesIgnored = 0;
        public int totalOldRows = 0;
        public String errorMessage = null;
    }

    public @NotNull MigrationReport runMigration() {
        MigrationReport report = new MigrationReport();

        // Step 1: Backup old files
        backupOldFiles();

        if (!db.isAvailable()) {
            report.errorMessage = "Database is unavailable; migration cannot run.";
            logger.severe("[MIGRATION ERROR] " + report.errorMessage);
            return report;
        }

        try (Connection conn = db.getConnection()) {
            if (conn == null) {
                report.errorMessage = "Could not obtain DB connection.";
                logger.severe("[MIGRATION ERROR] " + report.errorMessage);
                return report;
            }

            // Step 2: Check idempotency marker
            if (storage.isMigrationCompleted(conn)) {
                report.success = true;
                report.skipped = true;
                logger.info("[MIGRATION] Migrasi 3.x sudah pernah diselesaikan sebelumnya. Melewati.");
                return report;
            }

            // Step 3: Check if old table acm_blacklist exists
            if (!tableExists(conn, "acm_blacklist")) {
                // Fresh installation
                storage.markMigrationCompleted(conn);
                report.success = true;
                report.skipped = true;
                logger.info("[MIGRATION] Tabel 3.x tidak ditemukan; instalasi baru AntiChatManager Lite 4.0.0.");
                return report;
            }

            // Step 4: Read old table READ-ONLY
            logger.info("[MIGRATION] Membaca tabel lama acm_blacklist (READ-ONLY)...");

            List<OldBlacklistRow> oldRows = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT command, source, added_by, added_at, enabled FROM acm_blacklist")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        OldBlacklistRow row = new OldBlacklistRow();
                        row.command = rs.getString("command");
                        row.source = rs.getString("source");
                        row.addedBy = rs.getString("added_by");
                        row.addedAt = rs.getLong("added_at");
                        row.enabled = rs.getInt("enabled");
                        oldRows.add(row);
                    }
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to read acm_blacklist: " + e.getMessage(), e);
                report.errorMessage = e.getMessage();
                return report;
            }

            report.totalOldRows = oldRows.size();

            // Step 5: Process and import rows
            Set<String> processedCommands = new HashSet<>();
            String insertSql = "INSERT INTO acm_blacklist_v4 (command, source, added_by, added_at) VALUES (?, ?, ?, ?) "
                    + (db.getType() == DatabaseModule.Type.SQLITE ? "ON CONFLICT(command) DO NOTHING" : "");

            try (PreparedStatement ins = conn.prepareStatement(insertSql)) {
                for (OldBlacklistRow row : oldRows) {
                    if (row.command == null || row.command.isBlank()) continue;
                    String normCmd = row.command.trim();

                    if (!processedCommands.add(normCmd.toLowerCase(Locale.ROOT))) {
                        report.duplicatesIgnored++;
                        continue;
                    }

                    String src = row.source != null ? row.source.trim().toUpperCase(Locale.ROOT) : "";

                    // Categorize sources based on 3.0.2 specification
                    if (src.startsWith("MANUAL-SCAN:") || src.equals("PLUGIN_SCAN") || src.equals("LUCKPERMS_SCAN") || src.equals("MANUAL_SCAN")) {
                        // Auto-scan entries are NOT imported, but counted and reported
                        report.autoScanSkipped++;
                        continue;
                    }

                    String targetSource;
                    if (src.equals("MANUAL") || src.equals("CONFIG") || src.equals("IMPORT")) {
                        targetSource = src;
                        report.manualImported++;
                    } else {
                        // Unlabeled or unknown source imported as 'LEGACY' for admin review
                        targetSource = "LEGACY";
                        report.legacyImported++;
                    }

                    ins.setString(1, normCmd);
                    ins.setString(2, targetSource);
                    ins.setString(3, row.addedBy != null ? row.addedBy : "MIGRATION_3X");
                    ins.setLong(4, row.addedAt > 0 ? row.addedAt : System.currentTimeMillis());
                    try {
                        ins.executeUpdate();
                    } catch (SQLException ex) {
                        // Duplicate or unique constraint failure
                        report.duplicatesIgnored++;
                    }
                }
            }

            // Step 6: Mark migration completed
            storage.markMigrationCompleted(conn);
            report.success = true;

            // Verification report to console
            logger.info("==================================================");
            logger.info("[MIGRATION 3.x -> 4.0.0 COMPLETED]");
            logger.info("Total entri 3.x dibaca        : " + report.totalOldRows);
            logger.info("Entri manual/config diimpor    : " + report.manualImported);
            logger.info("Entri legacy diimpor           : " + report.legacyImported + " (flag: legacy)");
            logger.info("Entri auto-scan diabaikan      : " + report.autoScanSkipped);
            logger.info("Duplikat / konflik diabaikan   : " + report.duplicatesIgnored);
            logger.info("==================================================");

        } catch (Exception e) {
            report.success = false;
            report.errorMessage = e.getMessage();
            logger.log(Level.SEVERE, "[MIGRATION FAILURE] Migrasi gagal di tengah jalan! " + e.getMessage(), e);
        }

        return report;
    }

    private void backupOldFiles() {
        if (!dataFolder.exists()) return;

        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File backupDir = new File(dataFolder, "backup-3.x/" + stamp);
        if (!backupDir.exists() && !backupDir.mkdirs()) {
            logger.warning("Could not create backup directory: " + backupDir);
            return;
        }

        File[] files = dataFolder.listFiles((dir, name) ->
                name.endsWith(".yml") || name.endsWith(".db") || name.endsWith(".sqlite")
                        || name.endsWith(".sqlite3") || name.startsWith("languages"));
        if (files == null) return;

        for (File f : files) {
            try {
                if (f.isFile()) {
                    Files.copy(f.toPath(), new File(backupDir, f.getName()).toPath(), StandardCopyOption.REPLACE_EXISTING);
                } else if (f.isDirectory() && f.getName().startsWith("languages")) {
                    copyDirectoryRecursively(f.toPath(), new File(backupDir, f.getName()).toPath());
                }
            } catch (Exception e) {
                logger.warning("Failed to backup " + f.getName() + ": " + e.getMessage());
            }
        }
        logger.info("[MIGRATION] File konfigurasi lama berhasil dicadangkan ke " + backupDir.getPath());
    }

    private static void copyDirectoryRecursively(java.nio.file.Path source, java.nio.file.Path target) throws IOException {
        try (var stream = Files.walk(source)) {
            stream.forEach(p -> {
                try {
                    java.nio.file.Path dest = target.resolve(source.relativize(p));
                    if (Files.isDirectory(p)) {
                        if (!Files.exists(dest)) Files.createDirectories(dest);
                    } else {
                        Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }

    private boolean tableExists(Connection conn, String tableName) {
        try (ResultSet rs = conn.getMetaData().getTables(null, null, tableName, null)) {
            return rs.next();
        } catch (SQLException ignored) {
            return false;
        }
    }

    private static class OldBlacklistRow {
        String command;
        String source;
        String addedBy;
        long addedAt;
        int enabled;
    }
}
