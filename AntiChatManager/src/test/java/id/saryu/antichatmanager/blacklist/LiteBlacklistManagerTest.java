package id.saryu.antichatmanager.blacklist;

import id.saryu.antichatmanager.policy.BlacklistSnapshot;
import id.saryu.antichatmanager.policy.PolicyStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LiteBlacklistManagerTest {

    @Test
    @DisplayName("Command validation rules: alphanumeric, _, -, :, length bounds, reject duplicate")
    void testCommandValidation() {
        LiteBlacklistManager mgr = new LiteBlacklistManager(null, null);

        // Valid commands
        assertNull(mgr.validateCommand("op"));
        assertNull(mgr.validateCommand("minecraft:op"));
        assertNull(mgr.validateCommand("essentials:spawn_point-1"));

        // Invalid: empty or whitespace
        assertNotNull(mgr.validateCommand(""));
        assertNotNull(mgr.validateCommand("   "));
        assertNotNull(mgr.validateCommand(null));

        // Invalid: special characters or spaces
        assertNotNull(mgr.validateCommand("op now"));
        assertNotNull(mgr.validateCommand("op!"));
        assertNotNull(mgr.validateCommand("op;rm"));
        assertNotNull(mgr.validateCommand("op*"));

        // Invalid: too long (> 64 chars)
        String tooLong = "a".repeat(65);
        assertNotNull(mgr.validateCommand(tooLong));
    }

    @Test
    @DisplayName("Emergency blacklist fallback contains static defaults without database")
    void testEmergencyBlacklistFallback() {
        List<String> defaults = List.of("op", "deop", "stop", "reload", "restart");
        BlacklistSnapshot emergencySnapshot = BlacklistSnapshot.ofEmergency(defaults);

        assertFalse(emergencySnapshot.isEmpty());
        assertEquals(5, emergencySnapshot.size());

        assertTrue(emergencySnapshot.matches("op"));
        assertTrue(emergencySnapshot.matches("deop"));
        assertTrue(emergencySnapshot.matches("stop"));
        assertTrue(emergencySnapshot.matches("reload"));
        assertTrue(emergencySnapshot.matches("restart"));
        assertTrue(emergencySnapshot.matches("minecraft:op"));

        assertFalse(emergencySnapshot.matches("gamemode"));
        assertEquals("EMERGENCY", emergencySnapshot.getSource("op"));
    }

    @Test
    @DisplayName("Policy status values")
    void testPolicyStatusEnum() {
        assertEquals(PolicyStatus.READY, PolicyStatus.valueOf("READY"));
        assertEquals(PolicyStatus.DEGRADED, PolicyStatus.valueOf("DEGRADED"));
        assertEquals(PolicyStatus.UNAVAILABLE, PolicyStatus.valueOf("UNAVAILABLE"));
    }
}
