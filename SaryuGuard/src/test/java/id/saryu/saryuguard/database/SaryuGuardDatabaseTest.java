package id.saryu.saryuguard.database;

import id.saryu.saryuguard.mfa.SecretCipher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phase E1: SQLite schema, encrypted 2FA persistence, and key-rotation round-trip
 * without requiring a live Paper server.
 */
class SaryuGuardDatabaseTest {

    @TempDir Path tempDir;
    private Connection connection;
    private final TwoFactorDao dao = new TwoFactorDao();

    @BeforeEach
    void setUp() throws Exception {
        Class.forName("org.sqlite.JDBC");
        Path dbFile = tempDir.resolve("saryuguard.db");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("""
                CREATE TABLE sg_meta (
                    id INTEGER PRIMARY KEY,
                    schema_version INTEGER NOT NULL,
                    key_version INTEGER NOT NULL DEFAULT 1
                )
                """);
            st.execute("INSERT INTO sg_meta (id, schema_version, key_version) VALUES (1, 1, 1)");
            st.execute("""
                CREATE TABLE sg_2fa_secrets (
                    uuid TEXT PRIMARY KEY,
                    totp_secret_enc TEXT NOT NULL,
                    recovery_hashes TEXT NOT NULL DEFAULT '[]',
                    key_version INTEGER NOT NULL DEFAULT 1,
                    setup_at INTEGER NOT NULL,
                    last_auth_at INTEGER,
                    locked INTEGER NOT NULL DEFAULT 0,
                    failed_attempts INTEGER NOT NULL DEFAULT 0
                )
                """);
            st.execute("""
                CREATE TABLE sg_audit_log (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    event_type TEXT NOT NULL,
                    actor_uuid TEXT,
                    actor_name TEXT,
                    target_uuid TEXT,
                    target_name TEXT,
                    ip_enc TEXT,
                    details_enc TEXT,
                    key_version INTEGER NOT NULL DEFAULT 1,
                    chain_hash TEXT NOT NULL,
                    created_at INTEGER NOT NULL
                )
                """);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) connection.close();
    }

    @Test
    @DisplayName("TwoFactorDao upsert/load round-trip with AES-GCM ciphertext")
    void encryptedSecretPersists() throws Exception {
        SecretCipher cipher = new SecretCipher(SecretCipher.generateKey(), 1);
        UUID uuid = UUID.randomUUID();
        String secret = "JBSWY3DPEHPK3PXP";
        String aad = cipher.aad("sg_2fa_secrets", uuid.toString(), "totp_secret_enc");
        String enc = cipher.encrypt(secret, aad);

        dao.upsert(connection, new TwoFactorDao.TwoFactorRecord(
                uuid.toString(), enc, "[\"$argon2id$test\"]", 1,
                System.currentTimeMillis(), null, false, 0));

        TwoFactorDao.TwoFactorRecord loaded = dao.load(connection, uuid);
        assertNotNull(loaded);
        assertEquals(enc, loaded.totpSecretEnc());
        String plain = cipher.decrypt(loaded.totpSecretEnc(), aad);
        assertEquals(secret, plain);
        cipher.destroy();
    }

    @Test
    @DisplayName("Key rotation re-encrypts 2FA rows; old key cannot decrypt new ciphertext")
    void keyRotationReencrypts() throws Exception {
        byte[] oldKey = SecretCipher.generateKey();
        SecretCipher oldCipher = new SecretCipher(oldKey, 1);
        UUID uuid = UUID.randomUUID();
        String secret = "SECRETBASE32VALUEAA";
        String aadV1 = SecretCipher.buildAad("sg_2fa_secrets", uuid.toString(), "totp_secret_enc", 1);
        String encV1 = oldCipher.encrypt(secret, aadV1);
        dao.upsert(connection, new TwoFactorDao.TwoFactorRecord(
                uuid.toString(), encV1, "[]", 1, 1L, null, false, 0));

        byte[] newKey = SecretCipher.generateKey();
        SecretCipher newCipher = new SecretCipher(newKey, 2);
        String plain = oldCipher.decrypt(encV1, aadV1);
        String aadV2 = SecretCipher.buildAad("sg_2fa_secrets", uuid.toString(), "totp_secret_enc", 2);
        String encV2 = newCipher.encrypt(plain, aadV2);
        dao.upsert(connection, new TwoFactorDao.TwoFactorRecord(
                uuid.toString(), encV2, "[]", 2, 1L, null, false, 0));

        TwoFactorDao.TwoFactorRecord loaded = dao.load(connection, uuid);
        assertNotNull(loaded);
        assertEquals(2, loaded.keyVersion());
        assertEquals(secret, newCipher.decrypt(loaded.totpSecretEnc(), aadV2));
        assertThrows(Exception.class, () -> oldCipher.decrypt(loaded.totpSecretEnc(), aadV1));
        assertThrows(Exception.class, () -> oldCipher.decrypt(loaded.totpSecretEnc(), aadV2));

        oldCipher.destroy();
        newCipher.destroy();
    }

    @Test
    @DisplayName("secret.key backup & restore: rotated key file restores previous bytes")
    void secretKeyBackupRestore() throws Exception {
        Path keyFile = tempDir.resolve("secret.key");
        Path bakFile = tempDir.resolve("secret.key.bak");
        byte[] original = SecretCipher.generateKey();
        byte[] rotated = SecretCipher.generateKey();
        Files.write(keyFile, original);

        // Simulate ConfigManager.rotateSecretKeyFile backup step
        Files.copy(keyFile, bakFile);
        Files.write(keyFile, rotated);

        assertArrayEquals(rotated, Files.readAllBytes(keyFile));
        assertArrayEquals(original, Files.readAllBytes(bakFile));

        // Restore from backup
        Files.copy(bakFile, keyFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        assertArrayEquals(original, Files.readAllBytes(keyFile));
    }

    @Test
    @DisplayName("Wrong AAD / transplanted ciphertext is rejected")
    void aadPreventsTransplant() throws Exception {
        SecretCipher cipher = new SecretCipher(SecretCipher.generateKey(), 1);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        String enc = cipher.encrypt("secret",
                cipher.aad("sg_2fa_secrets", a.toString(), "totp_secret_enc"));
        assertThrows(Exception.class, () -> cipher.decrypt(enc,
                cipher.aad("sg_2fa_secrets", b.toString(), "totp_secret_enc")));
        cipher.destroy();
    }
}
