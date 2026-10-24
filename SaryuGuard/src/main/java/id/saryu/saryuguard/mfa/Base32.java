package id.saryu.saryuguard.mfa;

import java.security.SecureRandom;

/**
 * Base-32 encoder/decoder (RFC 4648) – no external deps.
 *
 * <p>Used to encode TOTP secrets into the standard Base32 alphabet expected
 * by authenticator apps (Google Authenticator, Aegis, etc.).</p>
 */
public final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final int[] DECODE_TABLE = new int[128];

    static {
        java.util.Arrays.fill(DECODE_TABLE, -1);
        for (int i = 0; i < ALPHABET.length(); i++) {
            DECODE_TABLE[ALPHABET.charAt(i)] = i;
        }
        // Accept lowercase
        for (int i = 0; i < 26; i++) {
            DECODE_TABLE['a' + i] = i;
        }
    }

    private Base32() {}

    /** Encode raw bytes to Base-32 string (no padding). */
    public static String encode(byte[] data) {
        if (data.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                bitsLeft -= 5;
                sb.append(ALPHABET.charAt((buffer >>> bitsLeft) & 0x1F));
            }
        }
        if (bitsLeft > 0) {
            sb.append(ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return sb.toString();
    }

    /** Decode a Base-32 string (with or without padding) to raw bytes. */
    public static byte[] decode(String input) {
        if (input == null || input.isEmpty()) return new byte[0];
        // Strip padding
        input = input.replaceAll("=+$", "").toUpperCase();
        int outputLen = input.length() * 5 / 8;
        byte[] out = new byte[outputLen];
        int buffer = 0, bitsLeft = 0, idx = 0;
        for (char c : input.toCharArray()) {
            if (c > 127 || DECODE_TABLE[c] == -1) {
                throw new IllegalArgumentException("Karakter Base-32 tidak valid: " + c);
            }
            buffer = (buffer << 5) | DECODE_TABLE[c];
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                bitsLeft -= 8;
                out[idx++] = (byte) ((buffer >>> bitsLeft) & 0xFF);
            }
        }
        return out;
    }

    /**
     * Generate a random 20-byte (160-bit) TOTP secret and return it as Base-32.
     * 160 bits is the recommended TOTP secret length for HmacSHA1.
     */
    public static String generateSecretBase32() {
        byte[] secret = new byte[20];
        new SecureRandom().nextBytes(secret);
        return encode(secret);
    }
}
