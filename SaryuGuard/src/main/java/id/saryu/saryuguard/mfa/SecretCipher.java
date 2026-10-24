package id.saryu.saryuguard.mfa;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * AES-256-GCM encryption for TOTP secrets stored in the database.
 *
 * <p>Key source (in priority order):
 * <ol>
 *   <li>Environment variable {@code SARYUGUARD_KEY} (Base-64 encoded 32 bytes)</li>
 *   <li>{@code secret.key} file (binary 32 bytes, managed by ConfigManager)</li>
 *   <li>Passphrase from {@code secret.toml} → Argon2id KDF</li>
 * </ol>
 *
 * <p>Nonce: 12 random bytes per encryption (never reused).
 * AAD: {@code "tableName:rowId:colName:keyVersion"} – prevents ciphertext from
 * being transplanted to a different row/column.</p>
 *
 * <p>Stored format (Base-64): {@code version(1 byte) | nonce(12) | ciphertext+tag}</p>
 */
public final class SecretCipher {

    private static final int GCM_NONCE_LEN = 12;
    private static final int GCM_TAG_BITS = 128;
    /** Wire format version prepended to ciphertext (independent of DB key_version). */
    public static final byte FORMAT_VERSION = 1;
    private static final String ALGORITHM = "AES/GCM/NoPadding";

    private final byte[] keyBytes; // 32 bytes
    private final int keyVersion;
    private final SecureRandom rng = new SecureRandom();

    /**
     * Construct with a raw 32-byte AES key (key version 1).
     *
     * @param keyBytes exactly 32 bytes; defensively copied
     */
    public SecretCipher(byte[] keyBytes) {
        this(keyBytes, 1);
    }

    /**
     * Construct with a raw 32-byte AES key and explicit key version (for rotation).
     *
     * @param keyBytes   exactly 32 bytes; defensively copied
     * @param keyVersion positive integer stored in DB {@code key_version} columns
     */
    public SecretCipher(byte[] keyBytes, int keyVersion) {
        if (keyBytes == null || keyBytes.length != 32) {
            throw new IllegalArgumentException("AES key harus 32 byte (256 bit).");
        }
        if (keyVersion < 1) {
            throw new IllegalArgumentException("keyVersion harus >= 1");
        }
        this.keyBytes = Arrays.copyOf(keyBytes, 32);
        this.keyVersion = keyVersion;
    }

    public int getKeyVersion() {
        return keyVersion;
    }

    /** Defensive copy of the raw key bytes (caller should wipe after use). */
    public byte[] getKeyBytesCopy() {
        return Arrays.copyOf(keyBytes, keyBytes.length);
    }

    /**
     * Derive a key from a passphrase using Argon2id (BouncyCastle).
     *
     * @param passphrase UTF-8 passphrase
     * @param salt       16-byte salt (can be stored alongside encrypted data)
     */
    public static SecretCipher fromPassphrase(char[] passphrase, byte[] salt) {
        return fromPassphrase(passphrase, salt, 1);
    }

    public static SecretCipher fromPassphrase(char[] passphrase, byte[] salt, int keyVersion) {
        byte[] passBytes = new String(passphrase).getBytes(StandardCharsets.UTF_8);
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(3)
                .withMemoryAsKB(65536) // 64 MB
                .withParallelism(1)
                .withSalt(salt)
                .build();
        Argon2BytesGenerator gen = new Argon2BytesGenerator();
        gen.init(params);
        byte[] derived = new byte[32];
        gen.generateBytes(passBytes, derived, 0, 32);
        Arrays.fill(passBytes, (byte) 0);
        return new SecretCipher(derived, keyVersion);
    }

    /**
     * Derive a key from a Base-64 encoded environment variable.
     *
     * @param base64Key Base-64 string representing 32 raw bytes
     */
    public static SecretCipher fromBase64EnvKey(String base64Key) {
        byte[] raw = Base64.getDecoder().decode(base64Key.trim());
        if (raw.length != 32) {
            throw new IllegalArgumentException("Env key harus 32 byte Base-64 (dapat " + raw.length + " byte).");
        }
        return new SecretCipher(raw);
    }

    /**
     * Encrypt plaintext with AES-256-GCM.
     *
     * @param plaintext UTF-8 plaintext
     * @param aad       Additional Authenticated Data string (e.g. "tableName:rowId:colName:keyVersion")
     * @return Base-64 encoded ciphertext with prepended version byte and nonce
     */
    public String encrypt(String plaintext, String aad) throws Exception {
        byte[] nonce = new byte[GCM_NONCE_LEN];
        rng.nextBytes(nonce);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE,
                new SecretKeySpec(keyBytes, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));

        // Format: [1 byte format version][12 byte nonce][ciphertext+tag]
        byte[] result = new byte[1 + GCM_NONCE_LEN + ciphertext.length];
        result[0] = FORMAT_VERSION;
        System.arraycopy(nonce, 0, result, 1, GCM_NONCE_LEN);
        System.arraycopy(ciphertext, 0, result, 1 + GCM_NONCE_LEN, ciphertext.length);

        return Base64.getEncoder().encodeToString(result);
    }

    /**
     * Decrypt a Base-64 encoded ciphertext.
     *
     * @param base64Ciphertext as produced by {@link #encrypt}
     * @param aad             must match the AAD used during encryption
     * @return decrypted UTF-8 string
     * @throws Exception if decryption or authentication fails (wrong key or tampered data)
     */
    public String decrypt(String base64Ciphertext, String aad) throws Exception {
        byte[] raw = Base64.getDecoder().decode(base64Ciphertext);
        if (raw.length < 1 + GCM_NONCE_LEN + 1) {
            throw new IllegalArgumentException("Data terenkripsi terlalu pendek.");
        }
        // byte[0] = version (reserved for key rotation)
        byte[] nonce = Arrays.copyOfRange(raw, 1, 1 + GCM_NONCE_LEN);
        byte[] ciphertext = Arrays.copyOfRange(raw, 1 + GCM_NONCE_LEN, raw.length);

        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.DECRYPT_MODE,
                new SecretKeySpec(keyBytes, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] plaintext = cipher.doFinal(ciphertext);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    /**
     * Generate a random AES-256 key and return as raw bytes (for writing to secret.key).
     */
    public static byte[] generateKey() throws Exception {
        KeyGenerator kg = KeyGenerator.getInstance("AES");
        kg.init(256, new SecureRandom());
        return kg.generateKey().getEncoded();
    }

    /**
     * Build an AAD string for a specific record (key version 1).
     * Prefer {@link #buildAad(String, String, String, int)} when rotating keys.
     */
    public static String buildAad(String table, String rowId, String colName) {
        return buildAad(table, rowId, colName, 1);
    }

    /**
     * Build AAD bound to table, row id, column name, and key version.
     * Prevents transplanting ciphertext across rows/columns/key eras.
     */
    public static String buildAad(String table, String rowId, String colName, int keyVersion) {
        return table + ":" + rowId + ":" + colName + ":" + keyVersion;
    }

    /** Convenience: AAD using this cipher's key version. */
    public String aad(String table, String rowId, String colName) {
        return buildAad(table, rowId, colName, keyVersion);
    }

    /**
     * Wipe the key bytes from memory (best-effort; JVM may still have copies).
     */
    public void destroy() {
        Arrays.fill(keyBytes, (byte) 0);
    }
}
