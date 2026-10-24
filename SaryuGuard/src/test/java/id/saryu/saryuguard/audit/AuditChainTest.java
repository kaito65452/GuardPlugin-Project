package id.saryu.saryuguard.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AuditChainTest {

    @Test
    @DisplayName("Hash chain calculation is deterministic and tamper-evident")
    void testHashChainProgression() throws Exception {
        String genesis = AuditChain.GENESIS_HASH;
        assertNotNull(genesis);
        assertEquals(64, genesis.length());

        byte[] canonical1 = AuditChain.canonicalBytes(
                "PLAYER_JOIN", 1700000000000L,
                "00000000-0000-0000-0000-000000000001", "Player1",
                null, null,
                "Join server", "192.168.1.1"
        );
        String hash1 = AuditChain.nextHash(genesis, canonical1);
        assertNotEquals(genesis, hash1);
        assertEquals(64, hash1.length());

        byte[] canonical2 = AuditChain.canonicalBytes(
                "SENSITIVE_COMMAND", 1700000005000L,
                "00000000-0000-0000-0000-000000000001", "Player1",
                null, null,
                "/op player2", "192.168.1.1"
        );
        String hash2 = AuditChain.nextHash(hash1, canonical2);
        assertNotEquals(hash1, hash2);
        assertEquals(64, hash2.length());

        // Tampering with canonical1 should completely alter hash1 and fail validation against hash2
        byte[] tamperedCanonical1 = AuditChain.canonicalBytes(
                "PLAYER_JOIN", 1700000000000L,
                "00000000-0000-0000-0000-000000000001", "Player1",
                null, null,
                "Join server with altered details", "192.168.1.1"
        );
        String recomputedHash1 = AuditChain.nextHash(genesis, tamperedCanonical1);
        assertNotEquals(hash1, recomputedHash1);

        String invalidNextHash = AuditChain.nextHash(recomputedHash1, canonical2);
        assertNotEquals(hash2, invalidNextHash, "Altered previous hash must break subsequent chain hash");
    }

    @Test
    @DisplayName("Redaction cleanses sensitive authentication arguments and masks IP")
    void testAuthRedactionAndIpMasking() {
        String loginCmd = "/login mySecretPassword123";
        String redactedLogin = AuditRedaction.redactCommand(loginCmd);
        assertFalse(redactedLogin.contains("mySecretPassword123"));
        assertTrue(redactedLogin.contains("<redacted>"));

        String regCmd = "/register myPass myPass";
        String redactedReg = AuditRedaction.redactCommand(regCmd);
        assertFalse(redactedReg.contains("myPass"));
        assertTrue(redactedReg.contains("<redacted>"));

        String safeCmd = "/spawn";
        String redactedSafe = AuditRedaction.redactCommand(safeCmd);
        assertEquals("/spawn", redactedSafe);

        String ip = "192.168.1.42";
        String masked = AuditRedaction.maskIp(ip, true);
        assertEquals("192.168.1.xxx", masked);
    }
}
