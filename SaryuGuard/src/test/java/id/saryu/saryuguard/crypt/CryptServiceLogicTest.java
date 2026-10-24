package id.saryu.saryuguard.crypt;

import id.saryu.saryuguard.mfa.SecretCipher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Crypt helpers used by CryptService (AAD binding + versioned keys) without Bukkit.
 */
class CryptServiceLogicTest {

    @Test
    @DisplayName("AAD includes table, row, column, and key version")
    void aadFormat() {
        assertEquals("sg_2fa_secrets:uuid-1:totp_secret_enc:3",
                SecretCipher.buildAad("sg_2fa_secrets", "uuid-1", "totp_secret_enc", 3));
    }

    @Test
    @DisplayName("Passphrase Argon2id derivation is deterministic for same salt")
    void passphraseDerivationStable() throws Exception {
        byte[] salt = new byte[16];
        for (int i = 0; i < salt.length; i++) salt[i] = (byte) i;
        char[] pass = "correct-horse-battery".toCharArray();
        SecretCipher a = SecretCipher.fromPassphrase(pass, salt, 1);
        SecretCipher b = SecretCipher.fromPassphrase(pass.clone(), salt, 1);
        String aad = a.aad("t", "r", "c");
        String enc = a.encrypt("payload", aad);
        assertEquals("payload", b.decrypt(enc, aad));
        a.destroy();
        b.destroy();
    }

    @Test
    @DisplayName("Different passphrase salts produce incompatible keys")
    void differentSaltDifferentKey() throws Exception {
        byte[] salt1 = new byte[16];
        byte[] salt2 = new byte[16];
        salt2[0] = 1;
        char[] pass = "same-passphrase".toCharArray();
        SecretCipher a = SecretCipher.fromPassphrase(pass, salt1);
        SecretCipher b = SecretCipher.fromPassphrase(pass.clone(), salt2);
        String aad = SecretCipher.buildAad("t", "r", "c", 1);
        String enc = a.encrypt("payload", aad);
        assertThrows(Exception.class, () -> b.decrypt(enc, aad));
        a.destroy();
        b.destroy();
    }
}
