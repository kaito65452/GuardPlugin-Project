package id.saryu.saryuguard.audit;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Data access for {@code sg_audit_log}. Encryption is applied by callers.
 */
public class AuditDao {

    public record StoredRow(
            long id,
            @NotNull String eventType,
            @Nullable String actorUuid,
            @Nullable String actorName,
            @Nullable String targetUuid,
            @Nullable String targetName,
            @Nullable String ipEnc,
            @Nullable String detailsEnc,
            int keyVersion,
            @NotNull String chainHash,
            long createdAt
    ) {}

    public record ChainLink(
            long id,
            @NotNull String eventType,
            long createdAt,
            @Nullable String actorUuid,
            @Nullable String actorName,
            @Nullable String targetUuid,
            @Nullable String targetName,
            @Nullable String ipEnc,
            @Nullable String detailsEnc,
            int keyVersion,
            @NotNull String chainHash
    ) {}

    public long insert(@NotNull Connection c,
                       @NotNull String eventType,
                       @Nullable String actorUuid,
                       @Nullable String actorName,
                       @Nullable String targetUuid,
                       @Nullable String targetName,
                       @Nullable String ipEnc,
                       @Nullable String detailsEnc,
                       int keyVersion,
                       @NotNull String chainHash,
                       long createdAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO sg_audit_log "
                        + "(event_type, actor_uuid, actor_name, target_uuid, target_name, "
                        + "ip_enc, details_enc, key_version, chain_hash, created_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, eventType);
            setNullable(ps, 2, actorUuid);
            setNullable(ps, 3, actorName);
            setNullable(ps, 4, targetUuid);
            setNullable(ps, 5, targetName);
            setNullable(ps, 6, ipEnc);
            setNullable(ps, 7, detailsEnc);
            ps.setInt(8, keyVersion);
            ps.setString(9, chainHash);
            ps.setLong(10, createdAt);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        // SQLite fallback
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    public @Nullable String latestChainHash(@NotNull Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT chain_hash FROM sg_audit_log ORDER BY id DESC LIMIT 1")) {
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString(1) : null;
        }
    }

    public long latestId(@NotNull Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id FROM sg_audit_log ORDER BY id DESC LIMIT 1")) {
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public long count(@NotNull Connection c) throws SQLException {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM sg_audit_log")) {
            return rs.next() ? rs.getLong(1) : 0L;
        }
    }

    public @NotNull List<StoredRow> listRecent(@NotNull Connection c, int limit) throws SQLException {
        List<StoredRow> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, event_type, actor_uuid, actor_name, target_uuid, target_name, "
                        + "ip_enc, details_enc, key_version, chain_hash, created_at "
                        + "FROM sg_audit_log ORDER BY id DESC LIMIT ?")) {
            ps.setInt(1, Math.max(1, limit));
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                out.add(mapStored(rs));
            }
        }
        return out;
    }

    public @NotNull List<ChainLink> listAscending(@NotNull Connection c) throws SQLException {
        List<ChainLink> out = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, event_type, created_at, actor_uuid, actor_name, target_uuid, target_name, "
                             + "ip_enc, details_enc, key_version, chain_hash "
                             + "FROM sg_audit_log ORDER BY id ASC")) {
            while (rs.next()) {
                out.add(new ChainLink(
                        rs.getLong("id"),
                        rs.getString("event_type"),
                        rs.getLong("created_at"),
                        rs.getString("actor_uuid"),
                        rs.getString("actor_name"),
                        rs.getString("target_uuid"),
                        rs.getString("target_name"),
                        rs.getString("ip_enc"),
                        rs.getString("details_enc"),
                        rs.getInt("key_version"),
                        rs.getString("chain_hash")
                ));
            }
        }
        return out;
    }

    public int deleteOlderThan(@NotNull Connection c, long cutoffEpochMs) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM sg_audit_log WHERE created_at < ?")) {
            ps.setLong(1, cutoffEpochMs);
            return ps.executeUpdate();
        }
    }

    private static StoredRow mapStored(ResultSet rs) throws SQLException {
        return new StoredRow(
                rs.getLong("id"),
                rs.getString("event_type"),
                rs.getString("actor_uuid"),
                rs.getString("actor_name"),
                rs.getString("target_uuid"),
                rs.getString("target_name"),
                rs.getString("ip_enc"),
                rs.getString("details_enc"),
                rs.getInt("key_version"),
                rs.getString("chain_hash"),
                rs.getLong("created_at")
        );
    }

    private static void setNullable(PreparedStatement ps, int idx, @Nullable String v)
            throws SQLException {
        if (v == null) ps.setNull(idx, java.sql.Types.VARCHAR);
        else ps.setString(idx, v);
    }
}
