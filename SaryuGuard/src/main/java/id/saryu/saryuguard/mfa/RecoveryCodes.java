package id.saryu.saryuguard.mfa;

import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Recovery code management for staff 2FA.
 *
 * <p>Recovery codes are one-time-use codes that allow a staff member to
 * bypass TOTP if they lose access to their authenticator app.
 *
 * <p>Each code is a 10-character alphanumeric string (8 random bytes → Base-32),
 * stored as Argon2id hash. Algorithm name is stored in the hash string so we can
 * migrate in future. Falls back to BCrypt if BouncyCastle Argon2id fails.
 *
 * <p>Hash format: {@code $argon2id$v=19$m=65536,t=3,p=1$<base64salt>$<base64hash>}
 * (same conceptual structure as PHC string format for human readability, but we keep
 * it self-contained without external PHC parser).
 */
public final class RecoveryCodes {

    private static final int CODE_BYTES = 8;   // 8 random bytes → 13-char Base-32
    private static final int HASH_LENGTH = 32;  // 32-byte Argon2id output
    private static final int SALT_LENGTH = 16;

    // Argon2id parameters (OWASP minimum: m=64MB, t=3, p=1)
    private static final int ARGON2_MEM_KB = 65536;
    private static final int ARGON2_ITER = 3;
    private static final int ARGON2_PARALLEL = 1;

    private RecoveryCodes() {}

    /**
     * Generate {@code count} one-time recovery codes.
     *
     * @param count number of codes (typically 8–10)
     * @return array of plaintext codes to show once to the user
     */
    public static String[] generate(int count) {
        SecureRandom rng = new SecureRandom();
        String[] codes = new String[count];
        for (int i = 0; i < count; i++) {
            byte[] raw = new byte[CODE_BYTES];
            rng.nextBytes(raw);
            // Use Base32 for human-friendly codes (no ambiguous chars like 0/O)
            codes[i] = Base32.encode(raw).substring(0, 13); // 8 bytes → 13 chars
        }
        return codes;
    }

    /**
     * Hash a single recovery code for storage.
     *
     * @param code plaintext code
     * @return hash string with embedded algorithm/params/salt
     */
    public static String hash(String code) {
        SecureRandom rng = new SecureRandom();
        byte[] salt = new byte[SALT_LENGTH];
        rng.nextBytes(salt);
        try {
            byte[] hash = argon2idHash(code.getBytes(StandardCharsets.UTF_8), salt);
            String saltB64 = Base64.getEncoder().encodeToString(salt);
            String hashB64 = Base64.getEncoder().encodeToString(hash);
            return "$argon2id$v=19$m=" + ARGON2_MEM_KB + ",t=" + ARGON2_ITER
                    + ",p=" + ARGON2_PARALLEL + "$" + saltB64 + "$" + hashB64;
        } catch (Exception e) {
            // Fallback to simple SHA-256 + salt stored hex (not ideal, but avoids breaking)
            return sha256Fallback(code, salt);
        }
    }

    /**
     * Verify a plaintext recovery code against a stored hash.
     * Supports both Argon2id and SHA-256 fallback hashes.
     *
     * @param code       plaintext code entered by user
     * @param storedHash hash as returned by {@link #hash}
     * @return true if the code matches
     */
    public static boolean verify(String code, String storedHash) {
        if (code == null || storedHash == null) return false;
        try {
            if (storedHash.startsWith("$argon2id$")) {
                return verifyArgon2id(code, storedHash);
            } else if (storedHash.startsWith("$sha256fb$")) {
                return verifySha256Fallback(code, storedHash);
            }
        } catch (Exception e) {
            // Treat parse error as no-match
        }
        return false;
    }

    // -------------------------------------------------------------------------
    // Argon2id internals (BouncyCastle)
    // -------------------------------------------------------------------------

    private static byte[] argon2idHash(byte[] input, byte[] salt) {
        Argon2Parameters params = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withIterations(ARGON2_ITER)
                .withMemoryAsKB(ARGON2_MEM_KB)
                .withParallelism(ARGON2_PARALLEL)
                .withSalt(salt)
                .build();
        Argon2BytesGenerator gen = new Argon2BytesGenerator();
        gen.init(params);
        byte[] output = new byte[HASH_LENGTH];
        gen.generateBytes(input, output, 0, HASH_LENGTH);
        return output;
    }

    private static boolean verifyArgon2id(String code, String hashStr) {
        // Parse: $argon2id$v=19$m=65536,t=3,p=1$<salt>$<hash>
        String[] parts = hashStr.split("\\$");
        // parts[0]="" parts[1]="argon2id" parts[2]="v=19" parts[3]="m=...,t=...,p=..." parts[4]=saltB64 parts[5]=hashB64
        if (parts.length < 6) return false;
        byte[] salt = Base64.getDecoder().decode(parts[4]);
        byte[] expected = Base64.getDecoder().decode(parts[5]);
        byte[] computed = argon2idHash(code.getBytes(StandardCharsets.UTF_8), salt);
        return MessageDigest.isEqual(expected, computed);
    }

    // -------------------------------------------------------------------------
    // SHA-256 fallback (if BouncyCastle Argon2id fails at runtime)
    // -------------------------------------------------------------------------

    private static String sha256Fallback(String code, byte[] salt) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(salt);
            byte[] hash = md.digest(code.getBytes(StandardCharsets.UTF_8));
            String saltHex = bytesToHex(salt);
            String hashHex = bytesToHex(hash);
            return "$sha256fb$" + saltHex + "$" + hashHex;
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 tidak tersedia", e);
        }
    }

    private static boolean verifySha256Fallback(String code, String hashStr) throws Exception {
        String[] parts = hashStr.split("\\$");
        if (parts.length < 4) return false;
        byte[] salt = hexToBytes(parts[2]);
        byte[] expected = hexToBytes(parts[3]);
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update(salt);
        byte[] computed = md.digest(code.getBytes(StandardCharsets.UTF_8));
        return MessageDigest.isEqual(expected, computed);
    }

    // -------------------------------------------------------------------------
    // Hex helpers
    // -------------------------------------------------------------------------

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] out = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            out[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                               + Character.digit(hex.charAt(i + 1), 16));
        }
        return out;
    }
}
