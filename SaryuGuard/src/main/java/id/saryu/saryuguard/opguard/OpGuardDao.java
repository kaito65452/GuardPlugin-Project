package id.saryu.saryuguard.opguard;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * DAO for the sg_op_baseline table – the admin-approved OP UUID list.
 * Thread-safe: callers supply a {@link Connection} from the pool.
 */
public class OpGuardDao {

    // Record to hold a row
    public record OpEntry(
            @NotNull String uuid,
            @Nullable String playerName,
            @NotNull String approver,
            long approvedAt,
            @Nullable String notes
    ) {}

    // Insert or update (idempotent: if uuid exists, updates playerName/approver/notes)
    public boolean upsert(@NotNull Connection c, @NotNull OpEntry entry) throws SQLException {
        // Use INSERT OR REPLACE for SQLite / MERGE for H2
        // The table uses uuid as PRIMARY KEY so this is safe
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO sg_op_baseline (uuid, player_name, approver, approved_at, notes)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO UPDATE SET
                    player_name = excluded.player_name,
                    approver = excluded.approver,
                    approved_at = excluded.approved_at,
                    notes = excluded.notes
                """)) {
            ps.setString(1, entry.uuid());
            ps.setString(2, entry.playerName());
            ps.setString(3, entry.approver());
            ps.setLong(4, entry.approvedAt());
            ps.setString(5, entry.notes());
            return ps.executeUpdate() > 0;
        }
    }

    // Check if uuid exists
    public boolean exists(@NotNull Connection c, @NotNull String uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT 1 FROM sg_op_baseline WHERE uuid = ?")) {
            ps.setString(1, uuid);
            return ps.executeQuery().next();
        }
    }

    // Remove by uuid, returns true if row deleted
    public boolean remove(@NotNull Connection c, @NotNull String uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM sg_op_baseline WHERE uuid = ?")) {
            ps.setString(1, uuid);
            return ps.executeUpdate() > 0;
        }
    }

    // Load all entries ordered by approved_at desc
    public @NotNull List<OpEntry> loadAll(@NotNull Connection c) throws SQLException {
        List<OpEntry> list = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT uuid, player_name, approver, approved_at, notes FROM sg_op_baseline ORDER BY approved_at DESC")) {
            while (rs.next()) {
                list.add(new OpEntry(
                        rs.getString("uuid"),
                        rs.getString("player_name"),
                        rs.getString("approver"),
                        rs.getLong("approved_at"),
                        rs.getString("notes")
                ));
            }
        }
        return list;
    }

    // Count entries
    public int count(@NotNull Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sg_op_baseline")) {
            return rs.next() ? rs.getInt(1) : 0;
        }
    }

    // Bulk insert (for import), returns number inserted
    public int bulkInsert(@NotNull Connection c, @NotNull List<OpEntry> entries) throws SQLException {
        int count = 0;
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO sg_op_baseline (uuid, player_name, approver, approved_at, notes)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT(uuid) DO NOTHING
                """)) {
            for (OpEntry entry : entries) {
                ps.setString(1, entry.uuid());
                ps.setString(2, entry.playerName());
                ps.setString(3, entry.approver());
                ps.setLong(4, entry.approvedAt());
                ps.setString(5, entry.notes());
                count += ps.executeUpdate();
            }
        }
        return count;
    }
}
