package id.saryu.antichatmanager.storage;

import id.saryu.antichatmanager.AntiChatManager;
import id.saryu.antichatmanager.blacklist.BlacklistManager;
import id.saryu.libcore.database.DatabaseModule;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * Data access layer for AntiChatManager. All DB operations run async via
 * SaryuLibCore's DatabaseModule. Every method degrades to an empty/zero result
 * when the database is unavailable, so the plugin never crashes.
 */
public class Storage {

    public static final class ViolationRow {
        public String playerName;
        public String command;
        public String world;
        public String source;
        public long createdAt;
    }

    private final AntiChatManager plugin;

    public Storage(@NotNull AntiChatManager plugin) {
        this.plugin = plugin;
    }

    private DatabaseModule db() {
        return plugin.getLib().getDatabaseModule();
    }

    public void init() {
        if (!db().isAvailable()) {
            plugin.getLogger().warning("Database unavailable; blacklist/violation data is kept in memory only.");
        }
    }

    public void shutdown() {
        // The shared pool is owned by SaryuLibCore.
    }

    /* ---------- Blacklist entries ---------- */

    public @NotNull CompletableFuture<List<BlacklistManager.Entry>> loadBlacklistEntries() {
        if (!db().isAvailable()) return CompletableFuture.completedFuture(List.of());
        return db().async(c -> {
            try {
                return loadBlacklistEntriesSync(c);
            } catch (SQLException e) {
                throw new RuntimeException(e);
            }
        });
    }

    private List<BlacklistManager.Entry> loadBlacklistEntriesSync(Connection c) throws SQLException {
        List<BlacklistManager.Entry> list = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, command, match_mode, source, plugin_name, permission, reason, "
                     + "added_by, added_at, world, group_name, enabled FROM acm_blacklist WHERE enabled = 1")) {
            while (rs.next()) {
                BlacklistManager.Entry e = new BlacklistManager.Entry();
                e.id = rs.getInt("id");
                e.command = rs.getString("command");
                e.mode = safeMode(rs.getString("match_mode"));
                e.source = safeSource(rs.getString("source"));
                e.pluginName = rs.getString("plugin_name");
                e.permission = rs.getString("permission");
                e.reason = rs.getString("reason");
                e.addedBy = rs.getString("added_by");
                e.addedAt = rs.getLong("added_at");
                e.world = rs.getString("world");
                e.groupName = rs.getString("group_name");
                e.enabled = rs.getBoolean("enabled");
                e.normalized = id.saryu.libcore.util.StringSanitizer.normalize(e.command);
                e.compileRegex();
                list.add(e);
            }
        }
        return list;
    }

    private BlacklistManager.MatchMode safeMode(String value) {
        try {
            return BlacklistManager.MatchMode.valueOf(value.toUpperCase());
        } catch (Exception e) {
            return BlacklistManager.MatchMode.EXACT;
        }
    }

    private BlacklistManager.Source safeSource(String value) {
        try {
            return BlacklistManager.Source.valueOf(value.toUpperCase());
        } catch (Exception e) {
            return BlacklistManager.Source.MANUAL;
        }
    }

    public @NotNull CompletableFuture<Integer> saveBlacklistEntry(@NotNull BlacklistManager.Entry entry) {
        if (!db().isAvailable()) return CompletableFuture.completedFuture(-1);
        return db().async(c -> saveBlacklistEntrySync(c, entry))
                .exceptionally(ex -> {
                    plugin.getLogger().log(Level.FINE, "Blacklist persist failed", ex);
                    return -1;
                });
    }

    private int saveBlacklistEntrySync(Connection c, BlacklistManager.Entry entry) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO acm_blacklist (command, match_mode, source, plugin_name, permission, reason, "
                + "added_by, added_at, world, group_name, enabled) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, entry.command);
            ps.setString(2, entry.mode.name());
            ps.setString(3, entry.source.name());
            ps.setString(4, entry.pluginName);
            ps.setString(5, entry.permission);
            ps.setString(6, entry.reason);
            ps.setString(7, entry.addedBy);
            ps.setLong(8, entry.addedAt);
            ps.setString(9, entry.world);
            ps.setString(10, entry.groupName);
            ps.setBoolean(11, entry.enabled);
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return -1;
    }

    public @NotNull CompletableFuture<Void> deleteBlacklistEntry(int id) {
        if (!db().isAvailable() || id <= 0) return CompletableFuture.completedFuture(null);
        return db().asyncVoid(c -> deleteBlacklistEntrySync(c, id))
                .exceptionally(ex -> null);
    }

    private void deleteBlacklistEntrySync(Connection c, int id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM acm_blacklist WHERE id = ?")) {
            ps.setInt(1, id);
            ps.executeUpdate();
        }
    }

    /* ---------- Violations ---------- */

    public @NotNull CompletableFuture<Void> recordViolation(@NotNull String uuid, @NotNull String name,
                                                            @NotNull String command, @NotNull String server,
                                                            @Nullable String world, @Nullable String source) {
        if (!db().isAvailable()) return CompletableFuture.completedFuture(null);
        return db().asyncVoid(c -> recordViolationSync(c, uuid, name, command, server, world, source))
                .exceptionally(ex -> {
                    plugin.getLogger().log(Level.FINE, "Failed to record violation", ex);
                    return null;
                });
    }

    private void recordViolationSync(Connection c, String uuid, String name, String command,
                                     String server, String world, String source) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO acm_violations (player_uuid, player_name, command, server, world, source, created_at) "
                + "VALUES (?,?,?,?,?,?,?)")) {
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.setString(3, command);
            ps.setString(4, server);
            ps.setString(5, world);
            ps.setString(6, source);
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    public @NotNull CompletableFuture<List<ViolationRow>> loadViolations(@NotNull String playerKey,
                                                                         int limit, int offset) {
        if (!db().isAvailable()) return CompletableFuture.completedFuture(List.of());
        return db().async(c -> loadViolationsSync(c, playerKey, limit, offset))
                .exceptionally(ex -> {
                    plugin.getLogger().log(Level.FINE, "Failed to load violations", ex);
                    return List.of();
                });
    }

    private List<ViolationRow> loadViolationsSync(Connection c, String playerKey, int limit, int offset)
            throws SQLException {
        List<ViolationRow> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT player_name, command, world, source, created_at FROM acm_violations "
                + "WHERE LOWER(player_name) = LOWER(?) OR player_uuid = ? "
                + "ORDER BY created_at DESC LIMIT ? OFFSET ?")) {
            ps.setString(1, playerKey);
            ps.setString(2, playerKey);
            ps.setInt(3, limit);
            ps.setInt(4, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ViolationRow row = new ViolationRow();
                    row.playerName = rs.getString("player_name");
                    row.command = rs.getString("command");
                    row.world = rs.getString("world");
                    row.source = rs.getString("source");
                    row.createdAt = rs.getLong("created_at");
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /**
     * Increment a violation counter and return the new count, or 0 when the
     * database is unavailable (caller should fall back to memory).
     */
    public @NotNull CompletableFuture<Integer> incrementViolation(@NotNull String uuid, @NotNull String name) {
        if (!db().isAvailable()) return CompletableFuture.completedFuture(0);
        return db().async(c -> incrementViolationSync(c, uuid, name))
                .exceptionally(ex -> {
                    plugin.getLogger().log(Level.FINE, "Violation increment failed", ex);
                    return 0;
                });
    }

    private int incrementViolationSync(Connection c, String uuid, String name) throws SQLException {
        long now = System.currentTimeMillis();
        long windowStart = 0L;
        String window = plugin.getConfigModule().getString("punishment.count-window", "60m");
        if (!"0".equals(window)) {
            windowStart = now - parseDurationMs(window);
        }
        String sql = switch (db().getType()) {
            case MYSQL -> "INSERT INTO acm_violation_counts (player_uuid, player_name, count, window_start, updated_at) "
                    + "VALUES (?,?,1,?,?) ON DUPLICATE KEY UPDATE count=count+1, "
                    + "player_name=VALUES(player_name), updated_at=VALUES(updated_at)";
            default -> "INSERT INTO acm_violation_counts (player_uuid, player_name, count, window_start, updated_at) "
                    + "VALUES (?,?,1,?,?) ON CONFLICT(player_uuid) DO UPDATE SET count=count+1, "
                    + "player_name=excluded.player_name, updated_at=excluded.updated_at";
        };
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.setLong(3, windowStart);
            ps.setLong(4, now);
            ps.executeUpdate();
        }
        // Reset the counter when the stored window has expired.
        if (windowStart > 0) {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count, window_start FROM acm_violation_counts WHERE player_uuid = ?")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getLong("window_start") < windowStart) {
                        try (PreparedStatement upd = c.prepareStatement(
                                "UPDATE acm_violation_counts SET count = 1, window_start = ? WHERE player_uuid = ?")) {
                            upd.setLong(1, now);
                            upd.setString(2, uuid);
                            upd.executeUpdate();
                        }
                        return 1;
                    }
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT count FROM acm_violation_counts WHERE player_uuid = ?")) {
            ps.setString(1, uuid);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt("count");
            }
        }
        return 0;
    }

    public @NotNull CompletableFuture<Integer> getViolationCount(@NotNull String uuid) {
        if (!db().isAvailable()) return CompletableFuture.completedFuture(0);
        return db().async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT count FROM acm_violation_counts WHERE player_uuid = ?")) {
                ps.setString(1, uuid);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt("count") : 0;
                }
            }
        }).exceptionally(ex -> 0);
    }

    public @NotNull CompletableFuture<Void> resetViolationCount(@NotNull String uuid) {
        if (!db().isAvailable()) return CompletableFuture.completedFuture(null);
        return db().asyncVoid(c -> resetViolationCountSync(c, uuid)).exceptionally(ex -> null);
    }

    private void resetViolationCountSync(Connection c, String uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM acm_violation_counts WHERE player_uuid = ?")) {
            ps.setString(1, uuid);
            ps.executeUpdate();
        }
    }

    /* ---------- Punishments ---------- */

    public @NotNull CompletableFuture<Void> recordPunishment(@NotNull String uuid, @NotNull String name,
                                                             @NotNull String action, @Nullable String duration,
                                                             @NotNull String reason, @NotNull String issuedBy,
                                                             long expiresAt) {
        if (!db().isAvailable()) return CompletableFuture.completedFuture(null);
        return db().asyncVoid(c -> recordPunishmentSync(c, uuid, name, action, duration, reason, issuedBy, expiresAt))
                .exceptionally(ex -> null);
    }

    private void recordPunishmentSync(Connection c, String uuid, String name, String action,
                                      String duration, String reason, String issuedBy, long expiresAt)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO acm_punishments (player_uuid, player_name, action, duration, reason, issued_by, "
                + "issued_at, expires_at) VALUES (?,?,?,?,?,?,?,?)")) {
            ps.setString(1, uuid);
            ps.setString(2, name);
            ps.setString(3, action);
            ps.setString(4, duration);
            ps.setString(5, reason);
            ps.setString(6, issuedBy);
            ps.setLong(7, System.currentTimeMillis());
            ps.setLong(8, expiresAt);
            ps.executeUpdate();
        }
    }

    /* ---------- Scan history ---------- */

    public @NotNull CompletableFuture<Void> recordScan(String pluginName, String command, String permission,
                                                       String source, int added) {
        if (!db().isAvailable()) return CompletableFuture.completedFuture(null);
        return db().asyncVoid(c -> recordScanSync(c, pluginName, command, permission, source, added))
                .exceptionally(ex -> null);
    }

    private void recordScanSync(Connection c, String pluginName, String command, String permission,
                                String source, int added) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO acm_scan_history (plugin_name, command, permission, source, added, scanned_at) "
                + "VALUES (?,?,?,?,?,?)")) {
            ps.setString(1, pluginName);
            ps.setString(2, command);
            ps.setString(3, permission);
            ps.setString(4, source);
            ps.setInt(5, added);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /* ---------- Retention ---------- */

    /**
     * Delete violation/scan/punishment rows older than {@code retentionDays}.
     * A value of 0 or less keeps everything.
     */
    public @NotNull CompletableFuture<Integer> purgeOldData(int retentionDays) {
        if (retentionDays <= 0 || !db().isAvailable()) {
            return CompletableFuture.completedFuture(0);
        }
        long cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L;
        return db().async(c -> {
            int removed = 0;
            removed += deleteBefore(c, "acm_violations", "created_at", cutoff);
            removed += deleteBefore(c, "acm_scan_history", "scanned_at", cutoff);
            removed += deleteBefore(c, "acm_punishments", "issued_at", cutoff);
            return removed;
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.FINE, "Retention purge failed", ex);
            return 0;
        });
    }

    private int deleteBefore(Connection c, String table, String column, long cutoff) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM " + table + " WHERE " + column + " < ?")) {
            ps.setLong(1, cutoff);
            return ps.executeUpdate();
        }
    }

    /* ---------- Helpers ---------- */

    private long parseDurationMs(@NotNull String duration) {
        try {
            return new id.saryu.libcore.punishment.DurationParser().parse(duration).millis;
        } catch (Exception e) {
            return 3600000L;
        }
    }
}
