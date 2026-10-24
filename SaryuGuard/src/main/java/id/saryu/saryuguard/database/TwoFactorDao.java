package id.saryu.saryuguard.database;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Data-access object for {@code sg_2fa_secrets}.
 *
 * <p>All sensitive columns ({@code totp_secret_enc}, {@code recovery_hashes})
 * are stored pre-encrypted by the caller using
 * {@link id.saryu.saryuguard.mfa.SecretCipher}. This DAO is encryption-agnostic.</p>
 */
public class TwoFactorDao {

    // -------------------------------------------------------------------------
    // Record type
    // -------------------------------------------------------------------------

    public record TwoFactorRecord(
            @NotNull String uuid,
            @NotNull String totpSecretEnc,
            @NotNull String recoveryHashesJson,
            int keyVersion,
            long setupAt,
            @Nullable Long lastAuthAt,
            boolean locked,
            int failedAttempts
    ) {}

    // -------------------------------------------------------------------------
    // Read
    // -------------------------------------------------------------------------

    /**
     * Load a player's 2FA record.
     *
     * @param c    open connection (caller closes)
     * @param uuid player UUID
     * @return record or {@code null} if not registered
     */
    public @Nullable TwoFactorRecord load(@NotNull Connection c, @NotNull UUID uuid)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT uuid, totp_secret_enc, recovery_hashes, key_version, " +
                "setup_at, last_auth_at, locked, failed_attempts " +
                "FROM sg_2fa_secrets WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (!rs.next()) return null;
            Long lastAuth = rs.getLong("last_auth_at");
            if (rs.wasNull()) lastAuth = null;
            return new TwoFactorRecord(
                    rs.getString("uuid"),
                    rs.getString("totp_secret_enc"),
                    rs.getString("recovery_hashes"),
                    rs.getInt("key_version"),
                    rs.getLong("setup_at"),
                    lastAuth,
                    rs.getInt("locked") != 0,
                    rs.getInt("failed_attempts")
            );
        }
    }

    // -------------------------------------------------------------------------
    // Write
    // -------------------------------------------------------------------------

    /**
     * Insert or replace a 2FA record (upsert).
     *
     * <p>SQLite supports {@code INSERT OR REPLACE}; H2 supports {@code MERGE INTO}.</p>
     */
    public void upsert(@NotNull Connection c, @NotNull TwoFactorRecord rec)
            throws SQLException {
        // REPLACE INTO works in both SQLite and H2 (H2 supports it as an extension)
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT OR REPLACE INTO sg_2fa_secrets " +
                "(uuid, totp_secret_enc, recovery_hashes, key_version, setup_at, last_auth_at, locked, failed_attempts) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, rec.uuid());
            ps.setString(2, rec.totpSecretEnc());
            ps.setString(3, rec.recoveryHashesJson());
            ps.setInt(4, rec.keyVersion());
            ps.setLong(5, rec.setupAt());
            if (rec.lastAuthAt() != null) ps.setLong(6, rec.lastAuthAt());
            else ps.setNull(6, java.sql.Types.INTEGER);
            ps.setInt(7, rec.locked() ? 1 : 0);
            ps.setInt(8, rec.failedAttempts());
            ps.executeUpdate();
        }
    }

    /** Update only the lock + attempts columns (on failed/locked). */
    public void updateLockState(@NotNull Connection c, @NotNull UUID uuid,
                                boolean locked, int failedAttempts) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE sg_2fa_secrets SET locked = ?, failed_attempts = ? WHERE uuid = ?")) {
            ps.setInt(1, locked ? 1 : 0);
            ps.setInt(2, failedAttempts);
            ps.setString(3, uuid.toString());
            ps.executeUpdate();
        }
    }

    /** Record a successful authentication timestamp. */
    public void recordAuth(@NotNull Connection c, @NotNull UUID uuid, long epochMs)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE sg_2fa_secrets SET last_auth_at = ?, failed_attempts = 0, locked = 0 WHERE uuid = ?")) {
            ps.setLong(1, epochMs);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }

    /** Remove a player's 2FA record (for console reset). */
    public boolean delete(@NotNull Connection c, @NotNull UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "DELETE FROM sg_2fa_secrets WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            return ps.executeUpdate() > 0;
        }
    }

    /** Reset the lock and failed-attempts counter only (admin reset). */
    public boolean resetLock(@NotNull Connection c, @NotNull UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE sg_2fa_secrets SET locked = 0, failed_attempts = 0 WHERE uuid = ? AND locked = 1")) {
            ps.setString(1, uuid.toString());
            return ps.executeUpdate() > 0;
        }
    }

    // -------------------------------------------------------------------------
    // Recovery codes
    // -------------------------------------------------------------------------

    /** Update the stored recovery code hashes JSON array. */
    public void updateRecoveryHashes(@NotNull Connection c, @NotNull UUID uuid,
                                     @NotNull String recoveryHashesJson) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE sg_2fa_secrets SET recovery_hashes = ? WHERE uuid = ?")) {
            ps.setString(1, recoveryHashesJson);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        }
    }
}
