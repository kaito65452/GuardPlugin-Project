package id.saryu.antichatmanager.storage;

import id.saryu.antichatmanager.policy.BlacklistSnapshot;
import id.saryu.libcore.database.DatabaseModule;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Clean, lightweight storage for AntiChatManager Lite 4.0.0.
 */
public class LiteStorage {

    private final java.util.logging.Logger logger;
    private final DatabaseModule databaseModule;

    public LiteStorage(@NotNull JavaPlugin plugin, @NotNull DatabaseModule databaseModule) {
        this(databaseModule, plugin.getLogger());
    }

    public LiteStorage(@NotNull DatabaseModule databaseModule, @NotNull java.util.logging.Logger logger) {
        this.databaseModule = databaseModule;
        this.logger = logger;
    }

    public synchronized void init() {
        if (!databaseModule.isAvailable()) {
            logger.warning("Database pool is unavailable; policy will run in DEGRADED or UNAVAILABLE state.");
            return;
        }

        databaseModule.asyncVoid(conn -> {
            try {
                String idCol = switch (databaseModule.getType()) {
                    case SQLITE -> "id INTEGER PRIMARY KEY AUTOINCREMENT";
                    case H2, MYSQL -> "id BIGINT AUTO_INCREMENT PRIMARY KEY";
                };

                // V4 Blacklist table
                try (PreparedStatement ps = conn.prepareStatement(
                        "CREATE TABLE IF NOT EXISTS acm_blacklist_v4 ("
                                + idCol + ", "
                                + "command TEXT NOT NULL UNIQUE, "
                                + "source TEXT NOT NULL, "
                                + "added_by TEXT, "
                                + "added_at BIGINT NOT NULL)")) {
                    ps.execute();
                }

                // V4 Metadata table
                try (PreparedStatement ps = conn.prepareStatement(
                        "CREATE TABLE IF NOT EXISTS acm_meta_v4 ("
                                + "m_key TEXT PRIMARY KEY, "
                                + "m_value TEXT NOT NULL)")) {
                    ps.execute();
                }
            } catch (SQLException e) {
                logger.log(Level.SEVERE, "Failed to initialize acm_blacklist_v4 schema: " + e.getMessage(), e);
            }
        });
    }

    public CompletableFuture<List<BlacklistSnapshot.Entry>> loadEntries() {
        if (!databaseModule.isAvailable()) {
            return CompletableFuture.completedFuture(null);
        }

        return databaseModule.async(conn -> {
            List<BlacklistSnapshot.Entry> entries = new ArrayList<>();
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT command, source, added_by, added_at FROM acm_blacklist_v4 ORDER BY id ASC");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    entries.add(new BlacklistSnapshot.Entry(
                            rs.getString("command"),
                            rs.getString("source"),
                            rs.getString("added_by"),
                            rs.getLong("added_at")
                    ));
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to query acm_blacklist_v4: " + e.getMessage(), e);
                throw new RuntimeException(e);
            }
            return entries;
        });
    }

    public CompletableFuture<Boolean> addEntry(@NotNull String command, @NotNull String source, @NotNull String addedBy) {
        if (!databaseModule.isAvailable()) {
            return CompletableFuture.completedFuture(false);
        }

        long now = System.currentTimeMillis();
        return databaseModule.async(conn -> {
            String sql = switch (databaseModule.getType()) {
                case SQLITE -> "INSERT INTO acm_blacklist_v4 (command, source, added_by, added_at) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT(command) DO UPDATE SET source=excluded.source, added_by=excluded.added_by, added_at=excluded.added_at";
                case MYSQL -> "INSERT INTO acm_blacklist_v4 (command, source, added_by, added_at) VALUES (?, ?, ?, ?) "
                        + "ON DUPLICATE KEY UPDATE source=VALUES(source), added_by=VALUES(added_by), added_at=VALUES(added_at)";
                case H2 -> "MERGE INTO acm_blacklist_v4 (command, source, added_by, added_at) KEY(command) VALUES (?, ?, ?, ?)";
            };

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, command);
                ps.setString(2, source);
                ps.setString(3, addedBy);
                ps.setLong(4, now);
                ps.executeUpdate();
                return true;
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to insert command '" + command + "' into acm_blacklist_v4: " + e.getMessage(), e);
                return false;
            }
        });
    }

    public CompletableFuture<Boolean> removeEntry(@NotNull String command) {
        if (!databaseModule.isAvailable()) {
            return CompletableFuture.completedFuture(false);
        }

        return databaseModule.async(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM acm_blacklist_v4 WHERE command = ?")) {
                ps.setString(1, command);
                int affected = ps.executeUpdate();
                return affected > 0;
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Failed to delete command '" + command + "' from acm_blacklist_v4: " + e.getMessage(), e);
                return false;
            }
        });
    }

    public boolean isMigrationCompleted(Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT m_value FROM acm_meta_v4 WHERE m_key = 'migration_3x_completed'")) {
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "true".equalsIgnoreCase(rs.getString("m_value"));
            }
        } catch (SQLException ignored) {
            return false;
        }
    }

    public void markMigrationCompleted(Connection conn) throws SQLException {
        String sql = switch (databaseModule.getType()) {
            case SQLITE -> "INSERT INTO acm_meta_v4 (m_key, m_value) VALUES ('migration_3x_completed', 'true') "
                    + "ON CONFLICT(m_key) DO UPDATE SET m_value='true'";
            case MYSQL -> "INSERT INTO acm_meta_v4 (m_key, m_value) VALUES ('migration_3x_completed', 'true') "
                    + "ON DUPLICATE KEY UPDATE m_value='true'";
            case H2 -> "MERGE INTO acm_meta_v4 (m_key, m_value) KEY(m_key) VALUES ('migration_3x_completed', 'true')";
        };
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }
}
