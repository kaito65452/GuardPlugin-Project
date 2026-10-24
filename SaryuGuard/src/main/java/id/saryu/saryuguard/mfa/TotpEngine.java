package id.saryu.saryuguard.mfa;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/**
 * TOTP (RFC 6238) implementation using JDK-built-in HmacSHA1.
 *
 * <p>Compliant with RFC 6238 / RFC 4226:
 * <ul>
 *   <li>Algorithm: HmacSHA1 (as per RFC 4226 §5.3)</li>
 *   <li>Time step: 30 seconds</li>
 *   <li>Digits: 6</li>
 *   <li>Window: ±1 step (accepts past and future one step for clock skew)</li>
 * </ul>
 *
 * <p>No external dependencies; uses {@code javax.crypto.Mac} bundled with JDK.</p>
 */
public final class TotpEngine {

    private static final int DIGITS = 6;
    private static final int DIGITS_POW = 1_000_000; // 10^6
    private static final long STEP_SECONDS = 30L;
    private static final int WINDOW = 1; // ±1 steps

    private TotpEngine() { /* static utility */ }

    /**
     * Generate a TOTP code for the current time.
     *
     * @param secret raw (decoded) TOTP secret bytes
     * @return 6-digit code as zero-padded string
     * @throws GeneralSecurityException if HMAC-SHA1 is not available
     */
    public static String generate(byte[] secret) throws GeneralSecurityException {
        return generate(secret, currentCounter());
    }

    /**
     * Verify a TOTP code against the current time, with a ±1 step window and replay protection.
     *
     * @param secret          raw TOTP secret bytes
     * @param code            code entered by the user (digits only)
     * @param lastUsedCounter last counter successfully used (or -1 if none)
     * @return counter of the matched window if valid and newer than lastUsedCounter; -1 if invalid or replay
     */
    public static long verifyAndGetCounter(byte[] secret, String code, long lastUsedCounter) throws GeneralSecurityException {
        if (code == null || !code.matches("\\d{6}")) return -1;
        long counter = currentCounter();
        for (int i = -WINDOW; i <= WINDOW; i++) {
            long checkCounter = counter + i;
            if (checkCounter <= lastUsedCounter) continue; // replay prevention
            String generated = generate(secret, checkCounter);
            if (constantTimeEquals(generated, code)) {
                return checkCounter;
            }
        }
        return -1;
    }

    /**
     * Verify a TOTP code against the current time, with a ±1 step window.
     *
     * @param secret raw TOTP secret bytes
     * @param code   code entered by the user (digits only)
     * @return true if code is valid within the window
     */
    public static boolean verify(byte[] secret, String code) throws GeneralSecurityException {
        return verifyAndGetCounter(secret, code, -1) != -1;
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    static long currentCounter() {
        return System.currentTimeMillis() / 1000 / STEP_SECONDS;
    }

    static String generate(byte[] secret, long counter) throws GeneralSecurityException {
        byte[] msg = ByteBuffer.allocate(8).putLong(counter).array();
        Mac hmac = Mac.getInstance("HmacSHA1");
        hmac.init(new SecretKeySpec(secret, "RAW"));
        byte[] hash = hmac.doFinal(msg);

        // Dynamic truncation (RFC 4226 §5.3)
        int offset = hash[hash.length - 1] & 0x0F;
        int binCode = ((hash[offset]     & 0x7F) << 24)
                    | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) <<  8)
                    |  (hash[offset + 3] & 0xFF);
        int otp = binCode % DIGITS_POW;
        return String.format("%0" + DIGITS + "d", otp);
    }

    /**
     * Constant-time string comparison to prevent timing side-channel.
     */
    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ba = a.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ba, bb);
    }

    /**
     * Build an otpauth:// URI for QR code generation.
     *
     * @param issuer     e.g. "SaryuGuard"
     * @param accountName player name
     * @param base32Secret Base-32 encoded secret (for QR code display)
     */
    public static String buildOtpAuthUri(String issuer, String accountName, String base32Secret) {
        String encoded;
        try {
            encoded = java.net.URLEncoder.encode(issuer + ":" + accountName, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            encoded = issuer + ":" + accountName;
        }
        return "otpauth://totp/" + encoded
                + "?secret=" + base32Secret
                + "&issuer=" + issuer
                + "&algorithm=SHA1"
                + "&digits=" + DIGITS
                + "&period=" + STEP_SECONDS;
    }
}
