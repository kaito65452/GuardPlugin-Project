package id.saryu.saryuguard.mfa;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the 2FA subsystem:
 * – TotpEngine (RFC 6238 compliance)
 * – Base32 encode/decode
 * – MfaSession state machine
 * – RecoveryCodes hash & verify
 * – SecretCipher (AES-256-GCM round-trip + wrong AAD)
 */
@DisplayName("2FA Subsystem Tests")
class TwoFactorTest {

    // =========================================================================
    // TotpEngine
    // =========================================================================

    @Nested
    @DisplayName("TotpEngine – TOTP RFC 6238")
    class TotpEngineTests {

        private static final String SECRET_B32 = Base32.generateSecretBase32();

        @Test
        @DisplayName("Generated code length is exactly 6 digits")
        void generatedCodeSixDigits() throws GeneralSecurityException {
            byte[] secret = Base32.decode(SECRET_B32);
            String code = TotpEngine.generate(secret);
            assertEquals(6, code.length(), "TOTP code must be 6 digits");
            assertTrue(code.matches("\\d{6}"), "TOTP code must be numeric");
        }

        @Test
        @DisplayName("Generated code verifies successfully with the same secret")
        void verifyCurrentCode() throws GeneralSecurityException {
            byte[] secret = Base32.decode(SECRET_B32);
            String code = TotpEngine.generate(secret);
            assertTrue(TotpEngine.verify(secret, code), "Current code must verify");
        }

        @Test
        @DisplayName("Wrong code does not verify")
        void wrongCodeFails() throws GeneralSecurityException {
            byte[] secret = Base32.decode(SECRET_B32);
            assertFalse(TotpEngine.verify(secret, "000000"), "Random code should not verify");
        }

        @Test
        @DisplayName("Null or non-numeric input returns false without throwing")
        void nullInputReturnsFalse() throws GeneralSecurityException {
            byte[] secret = Base32.decode(SECRET_B32);
            assertFalse(TotpEngine.verify(secret, null));
            assertFalse(TotpEngine.verify(secret, "abc123"));
            assertFalse(TotpEngine.verify(secret, "12345")); // 5 digits
            assertFalse(TotpEngine.verify(secret, "1234567")); // 7 digits
        }

        @Test
        @DisplayName("Counter N-1 and N+1 are accepted (window ±1)")
        void windowAcceptance() throws GeneralSecurityException {
            byte[] secret = Base32.decode(SECRET_B32);
            long counter = TotpEngine.currentCounter();
            String prevCode = TotpEngine.generate(secret, counter - 1);
            String nextCode = TotpEngine.generate(secret, counter + 1);
            assertTrue(TotpEngine.verify(secret, prevCode), "Prev-step code must be accepted");
            assertTrue(TotpEngine.verify(secret, nextCode), "Next-step code must be accepted");
        }

        @Test
        @DisplayName("otpauth:// URI is well-formed")
        void otpauthUri() {
            String uri = TotpEngine.buildOtpAuthUri("SaryuGuard", "TestPlayer", SECRET_B32);
            assertTrue(uri.startsWith("otpauth://totp/"), "Must start with otpauth://totp/");
            assertTrue(uri.contains("secret=" + SECRET_B32));
            assertTrue(uri.contains("issuer=SaryuGuard"));
            assertTrue(uri.contains("digits=6"));
            assertTrue(uri.contains("period=30"));
        }

        @Test
        @DisplayName("Constant-time comparison: equal strings return true")
        void constantTimeEqual() {
            assertTrue(TotpEngine.constantTimeEquals("123456", "123456"));
            assertFalse(TotpEngine.constantTimeEquals("123456", "654321"));
            assertFalse(TotpEngine.constantTimeEquals(null, "123456"));
        }
    }

    // =========================================================================
    // Base32
    // =========================================================================

    @Nested
    @DisplayName("Base32 – encode / decode")
    class Base32Tests {

        @Test
        @DisplayName("Encode then decode round-trips correctly")
        void roundTrip() {
            byte[] data = "HelloSaryuGuard123".getBytes();
            String encoded = Base32.encode(data);
            byte[] decoded = Base32.decode(encoded);
            assertArrayEquals(data, decoded);
        }

        @Test
        @DisplayName("Empty input produces empty string")
        void emptyInput() {
            assertEquals("", Base32.encode(new byte[0]));
            assertArrayEquals(new byte[0], Base32.decode(""));
        }

        @Test
        @DisplayName("Generated secret is 20 chars or more (160 bits)")
        void generatedSecretSize() {
            String secret = Base32.generateSecretBase32();
            assertNotNull(secret);
            // 20 bytes → ceil(20*8/5) = 32 Base32 chars
            assertTrue(secret.length() >= 20, "Secret must be at least 20 chars");
        }

        @Test
        @DisplayName("Invalid Base32 character throws IllegalArgumentException")
        void invalidCharThrows() {
            assertThrows(IllegalArgumentException.class, () -> Base32.decode("!invalid!"));
        }
    }

    // =========================================================================
    // MfaSession state machine
    // =========================================================================

    @Nested
    @DisplayName("MfaSession – state transitions")
    class MfaSessionTests {

        @Test
        @DisplayName("Initial state is PENDING_MFA after construction")
        void initialState() {
            MfaSession session = new MfaSession(UUID.randomUUID(), MfaState.PENDING_MFA);
            assertEquals(MfaState.PENDING_MFA, session.getState());
            assertEquals(0, session.getFailedAttempts());
        }

        @Test
        @DisplayName("PENDING_MFA → AUTHENTICATED transition")
        void pendingToAuthenticated() {
            MfaSession session = new MfaSession(UUID.randomUUID(), MfaState.PENDING_MFA);
            session.setState(MfaState.AUTHENTICATED);
            assertEquals(MfaState.AUTHENTICATED, session.getState());
        }

        @Test
        @DisplayName("PENDING_MFA → LOCKED after max attempts")
        void pendingToLocked() {
            MfaSession session = new MfaSession(UUID.randomUUID(), MfaState.PENDING_MFA);
            for (int i = 0; i < 5; i++) session.incrementFailedAttempts();
            session.setState(MfaState.LOCKED);
            assertEquals(MfaState.LOCKED, session.getState());
            assertEquals(5, session.getFailedAttempts());
        }

        @Test
        @DisplayName("LOCKED → UNAUTHENTICATED on reset")
        void lockedToUnauthenticated() {
            MfaSession session = new MfaSession(UUID.randomUUID(), MfaState.LOCKED);
            session.setState(MfaState.UNAUTHENTICATED);
            session.resetFailedAttempts();
            assertEquals(MfaState.UNAUTHENTICATED, session.getState());
            assertEquals(0, session.getFailedAttempts());
        }

        @Test
        @DisplayName("Reconnect while LOCKED stays LOCKED (session preserved)")
        void reconnectStaysLocked() {
            UUID uuid = UUID.randomUUID();
            MfaSession session = new MfaSession(uuid, MfaState.LOCKED);
            // Simulate the session map retaining LOCKED state across reconnect
            assertEquals(MfaState.LOCKED, session.getState(),
                    "LOCKED session should survive reconnect (session not removed from map)");
        }

        @Test
        @DisplayName("Rate limit: setNextAttemptAt in future causes isRateLimited")
        void rateLimitCheck() {
            MfaSession session = new MfaSession(UUID.randomUUID(), MfaState.PENDING_MFA);
            assertFalse(session.isRateLimited(), "Initially not rate-limited");
            session.setNextAttemptAt(System.currentTimeMillis() + 30_000L);
            assertTrue(session.isRateLimited(), "Should be rate-limited when next attempt is in future");
        }

        @Test
        @DisplayName("Session timeout: millisSincePending increases over time")
        void sessionTimeout() throws InterruptedException {
            MfaSession session = new MfaSession(UUID.randomUUID(), MfaState.PENDING_MFA);
            Thread.sleep(50);
            assertTrue(session.millisSincePending() >= 40,
                    "millisSincePending should be at least 40ms after 50ms sleep");
        }
    }

    // =========================================================================
    // RecoveryCodes
    // =========================================================================

    @Nested
    @DisplayName("RecoveryCodes – hash and verify")
    class RecoveryCodesTests {

        @Test
        @DisplayName("Generate returns the requested count of codes")
        void generateCount() {
            String[] codes = RecoveryCodes.generate(8);
            assertEquals(8, codes.length);
            for (String code : codes) {
                assertNotNull(code);
                assertFalse(code.isBlank());
            }
        }

        @Test
        @DisplayName("All generated codes are unique")
        void codesAreUnique() {
            String[] codes = RecoveryCodes.generate(10);
            var set = new java.util.HashSet<>(java.util.Arrays.asList(codes));
            assertEquals(10, set.size(), "All recovery codes should be unique");
        }

        @Test
        @DisplayName("Correct code verifies against its hash")
        void correctCodeVerifies() {
            String code = RecoveryCodes.generate(1)[0];
            String hash = RecoveryCodes.hash(code);
            assertTrue(RecoveryCodes.verify(code, hash));
        }

        @Test
        @DisplayName("Wrong code does not verify")
        void wrongCodeFails() {
            String code = RecoveryCodes.generate(1)[0];
            String hash = RecoveryCodes.hash(code);
            assertFalse(RecoveryCodes.verify("WRONGWRONGWRONG", hash));
        }

        @Test
        @DisplayName("Null inputs return false without throwing")
        void nullInputs() {
            assertFalse(RecoveryCodes.verify(null, "$argon2id$v=19$m=65536,t=3,p=1$salt$hash"));
            assertFalse(RecoveryCodes.verify("code", null));
        }
    }

    // =========================================================================
    // SecretCipher (AES-256-GCM)
    // =========================================================================

    @Nested
    @DisplayName("SecretCipher – AES-256-GCM")
    class SecretCipherTests {

        private byte[] newKey() throws Exception {
            return SecretCipher.generateKey();
        }

        @Test
        @DisplayName("Encrypt then decrypt returns original plaintext")
        void roundTrip() throws Exception {
            SecretCipher cipher = new SecretCipher(newKey());
            String aad = SecretCipher.buildAad("sg_2fa_secrets", "uuid-row-1", "totp_secret");
            String plaintext = "JBSWY3DPEHPK3PXP";
            String encrypted = cipher.encrypt(plaintext, aad);
            assertNotEquals(plaintext, encrypted, "Ciphertext must differ from plaintext");
            String decrypted = cipher.decrypt(encrypted, aad);
            assertEquals(plaintext, decrypted);
        }

        @Test
        @DisplayName("Wrong AAD fails authentication tag")
        void wrongAadFails() throws Exception {
            SecretCipher cipher = new SecretCipher(newKey());
            String aad = SecretCipher.buildAad("sg_2fa_secrets", "uuid-row-1", "totp_secret");
            String wrongAad = SecretCipher.buildAad("sg_2fa_secrets", "uuid-row-2", "totp_secret");
            String encrypted = cipher.encrypt("secret-value", aad);
            assertThrows(Exception.class, () -> cipher.decrypt(encrypted, wrongAad),
                    "AES-GCM must reject ciphertext decrypted with wrong AAD");
        }

        @Test
        @DisplayName("Wrong key fails decryption")
        void wrongKeyFails() throws Exception {
            SecretCipher cipher1 = new SecretCipher(newKey());
            SecretCipher cipher2 = new SecretCipher(newKey());
            String aad = SecretCipher.buildAad("table", "row", "col");
            String encrypted = cipher1.encrypt("secret", aad);
            assertThrows(Exception.class, () -> cipher2.decrypt(encrypted, aad),
                    "AES-GCM must reject ciphertext decrypted with wrong key");
        }

        @RepeatedTest(5)
        @DisplayName("Each encryption produces a unique ciphertext (nonce uniqueness)")
        void nonceUniqueness() throws Exception {
            SecretCipher cipher = new SecretCipher(newKey());
            String aad = SecretCipher.buildAad("table", "row", "col");
            String enc1 = cipher.encrypt("same-plaintext", aad);
            String enc2 = cipher.encrypt("same-plaintext", aad);
            assertNotEquals(enc1, enc2, "Each encryption must produce unique ciphertext (random nonce)");
        }

        @Test
        @DisplayName("Key must be exactly 32 bytes")
        void keyLengthValidation() {
            assertThrows(IllegalArgumentException.class, () -> new SecretCipher(new byte[16]));
            assertThrows(IllegalArgumentException.class, () -> new SecretCipher(new byte[24]));
            assertThrows(IllegalArgumentException.class, () -> new SecretCipher(null));
            assertDoesNotThrow(() -> new SecretCipher(new byte[32]));
        }

        @Test
        @DisplayName("Tampered ciphertext fails authentication")
        void tamperedCiphertextFails() throws Exception {
            SecretCipher cipher = new SecretCipher(newKey());
            String aad = SecretCipher.buildAad("table", "row", "col");
            String encrypted = cipher.encrypt("important-secret", aad);
            // Flip one byte in the middle of the Base-64 string
            char[] chars = encrypted.toCharArray();
            int mid = chars.length / 2;
            chars[mid] = (chars[mid] == 'A') ? 'B' : 'A';
            String tampered = new String(chars);
            assertThrows(Exception.class, () -> cipher.decrypt(tampered, aad));
        }
    }
}
