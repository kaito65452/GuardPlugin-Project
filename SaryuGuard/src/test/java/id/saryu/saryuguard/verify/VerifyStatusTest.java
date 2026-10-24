package id.saryu.saryuguard.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VerifyStatusTest {

    @Test
    @DisplayName("VerifyStatus state contract: SCAN_ERROR is never clean, only VERIFIED is clean")
    void testVerifyStatusContract() {
        assertTrue(VerifyStatus.VERIFIED.isClean());
        assertFalse(VerifyStatus.UNVERIFIED.isClean());
        assertFalse(VerifyStatus.MISMATCH.isClean());
        assertFalse(VerifyStatus.SCAN_ERROR.isClean());

        assertEquals("VERIFIED", VerifyStatus.VERIFIED.label());
        assertEquals("UNVERIFIED", VerifyStatus.UNVERIFIED.label());
        assertEquals("MISMATCH", VerifyStatus.MISMATCH.label());
        assertEquals("SCAN_ERROR", VerifyStatus.SCAN_ERROR.label());
    }
}
