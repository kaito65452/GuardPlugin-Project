package id.saryu.saryuguard.crypt;

import id.saryu.saryuguard.SaryuGuardPlugin;
import id.saryu.saryuguard.database.SaryuGuardDatabase;
import id.saryu.saryuguard.mfa.SecretCipher;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Column-level AES-256-GCM encryption and console-only key rotation.
 *
 * <p>Sensitive fields (TOTP secrets, audit details, IPs) are encrypted by callers
 * via {@link #encrypt}/{@link #decrypt} with AAD bound to table/row/column/keyVersion.
 * {@link #rotateKeys()} re-encrypts known sensitive tables in batches and atomically
 * replaces {@code secret.key}.</p>
 */
public class CryptService {

    private static final int BATCH_SIZE = 64;

    private final SaryuGuardPlugin plugin;
    private final Logger log;

    private volatile SecretCipher cipher;
    private volatile int keyVersion = 1;
    private volatile boolean encryptionEnabled = true;

    public CryptService(@NotNull SaryuGuardPlugin plugin) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
    }

    /**
     * Initialise cipher from ConfigManager key material and DB key_version meta.
     */
    public synchronized void init(@Nullable SaryuGuardDatabase db) throws Exception {
        var cfg = plugin.getConfigManager().getConfig();
        encryptionEnabled = cfg.getBoolean("database.encryption.enabled", true);

        byte[] key = plugin.getConfigManager().getSecretKey();
        int storedVersion = 1;
        if (db != null && db.isAvailable()) {
            storedVersion = Math.max(1, db.getKeyVersion());
        }
        this.keyVersion = storedVersion;
        this.cipher = new SecretCipher(key, storedVersion);
        log.info("[Crypt] AES-256-GCM siap. key_version=" + keyVersion
                + " enabled=" + encryptionEnabled);
    }

    public synchronized void destroy() {
        if (cipher != null) {
            cipher.destroy();
            cipher = null;
        }
    }

    public boolean isEnabled() {
        return encryptionEnabled && cipher != null;
    }

    public int getKeyVersion() {
        return keyVersion;
    }

    public @NotNull SecretCipher requireCipher() {
        SecretCipher c = cipher;
        if (c == null) {
            throw new IllegalStateException("CryptService belum diinisialisasi.");
        }
        return c;
    }

    public @NotNull String encrypt(@NotNull String plaintext,
                                   @NotNull String table,
                                   @NotNull String rowId,
                                   @NotNull String column) throws Exception {
        SecretCipher c = requireCipher();
        return c.encrypt(plaintext, c.aad(table, rowId, column));
    }

    public @NotNull String decrypt(@NotNull String ciphertext,
                                   @NotNull String table,
                                   @NotNull String rowId,
                                   @NotNull String column,
                                   int recordKeyVersion) throws Exception {
        SecretCipher c = requireCipher();
        // Decrypt with AAD for the version the row was written with.
        // Current cipher key must still match that era (rotation updates all rows).
        String aad = SecretCipher.buildAad(table, rowId, column, recordKeyVersion);
        return c.decrypt(ciphertext, aad);
    }

    /**
     * Rotate encryption key: generate new key, re-encrypt sensitive columns in batches,
     * persist new key_version, atomically replace {@code secret.key}.
     *
     * @return number of rows re-encrypted
     */
    public synchronized int rotateKeys(@NotNull SaryuGuardDatabase db) throws Exception {
        if (!db.isAvailable()) {
            throw new IllegalStateException("Database tidak tersedia; rotasi dibatalkan.");
        }
        if (!encryptionEnabled) {
            throw new IllegalStateException("database.encryption.enabled=false; rotasi dibatalkan.");
        }

        SecretCipher oldCipher = requireCipher();
        int oldVersion = keyVersion;
        int newVersion = oldVersion + 1;
        byte[] newKeyBytes = SecretCipher.generateKey();
        SecretCipher newCipher = new SecretCipher(newKeyBytes, newVersion);

        // Pre-stage key file to .tmp before touching database
        plugin.getConfigManager().prepareRotateSecretKeyFile(newKeyBytes);

        int total = 0;
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                total += reencryptTwoFactor(c, oldCipher, newCipher, oldVersion, newVersion);
                total += reencryptAudit(c, oldCipher, newCipher, oldVersion, newVersion);
                db.setKeyVersion(c, newVersion);
                c.commit();
            } catch (Exception e) {
                c.rollback();
                newCipher.destroy();
                plugin.getConfigManager().abortRotateSecretKeyFile();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }

        // Commit atomic move of key file after DB commit
        plugin.getConfigManager().commitRotateSecretKeyFile(newKeyBytes);
        oldCipher.destroy();
        this.cipher = newCipher;
        this.keyVersion = newVersion;
        java.util.Arrays.fill(newKeyBytes, (byte) 0);

        log.info("[Crypt] Rotasi kunci selesai. key_version " + oldVersion + " → " + newVersion
                + " | baris di-reencrypt: " + total);
        return total;
    }

    private int reencryptTwoFactor(Connection c, SecretCipher oldC, SecretCipher newC,
                                   int oldVer, int newVer) throws Exception {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT uuid, totp_secret_enc, key_version FROM sg_2fa_secrets")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                rows.add(new Row(
                        rs.getString("uuid"),
                        rs.getString("totp_secret_enc"),
                        rs.getInt("key_version")));
            }
        }
        int count = 0;
        try (PreparedStatement upd = c.prepareStatement(
                "UPDATE sg_2fa_secrets SET totp_secret_enc = ?, key_version = ? WHERE uuid = ?")) {
            for (Row row : rows) {
                int ver = row.keyVersion > 0 ? row.keyVersion : oldVer;
                String aadOld = SecretCipher.buildAad("sg_2fa_secrets", row.id, "totp_secret_enc", ver);
                String plain = oldC.decrypt(row.ciphertext, aadOld);
                String aadNew = SecretCipher.buildAad("sg_2fa_secrets", row.id, "totp_secret_enc", newVer);
                String enc = newC.encrypt(plain, aadNew);
                upd.setString(1, enc);
                upd.setInt(2, newVer);
                upd.setString(3, row.id);
                upd.addBatch();
                count++;
                if (count % BATCH_SIZE == 0) {
                    upd.executeBatch();
                }
            }
            if (count % BATCH_SIZE != 0) {
                upd.executeBatch();
            }
        }
        return count;
    }

    private int reencryptAudit(Connection c, SecretCipher oldC, SecretCipher newC,
                               int oldVer, int newVer) throws Exception {
        // Audit rows may be empty until Phase E2; still rotate any present ciphertext.
        List<AuditRow> rows = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT id, ip_enc, details_enc, key_version FROM sg_audit_log")) {
            while (rs.next()) {
                rows.add(new AuditRow(
                        rs.getLong("id"),
                        rs.getString("ip_enc"),
                        rs.getString("details_enc"),
                        rs.getInt("key_version")));
            }
        }
        int count = 0;
        try (PreparedStatement upd = c.prepareStatement(
                "UPDATE sg_audit_log SET ip_enc = ?, details_enc = ?, key_version = ? WHERE id = ?")) {
            for (AuditRow row : rows) {
                int ver = row.keyVersion > 0 ? row.keyVersion : oldVer;
                String rowId = Long.toString(row.id);
                String newIp = reencryptNullable(oldC, newC, row.ipEnc,
                        "sg_audit_log", rowId, "ip_enc", ver, newVer);
                String newDetails = reencryptNullable(oldC, newC, row.detailsEnc,
                        "sg_audit_log", rowId, "details_enc", ver, newVer);
                upd.setString(1, newIp);
                upd.setString(2, newDetails);
                upd.setInt(3, newVer);
                upd.setLong(4, row.id);
                upd.addBatch();
                count++;
                if (count % BATCH_SIZE == 0) {
                    upd.executeBatch();
                }
            }
            if (count % BATCH_SIZE != 0) {
                upd.executeBatch();
            }
        }
        return count;
    }

    private static @Nullable String reencryptNullable(SecretCipher oldC, SecretCipher newC,
                                                      @Nullable String ciphertext,
                                                      String table, String rowId, String col,
                                                      int oldVer, int newVer) throws Exception {
        if (ciphertext == null || ciphertext.isBlank()) {
            return ciphertext;
        }
        String plain = oldC.decrypt(ciphertext, SecretCipher.buildAad(table, rowId, col, oldVer));
        return newC.encrypt(plain, SecretCipher.buildAad(table, rowId, col, newVer));
    }

    private record Row(String id, String ciphertext, int keyVersion) {}
    private record AuditRow(long id, String ipEnc, String detailsEnc, int keyVersion) {}
}
