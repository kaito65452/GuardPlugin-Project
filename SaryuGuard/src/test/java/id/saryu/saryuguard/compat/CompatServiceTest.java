package id.saryu.saryuguard.compat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CompatServiceTest {

    @Test
    @DisplayName("Auth command detection correctly identifies login, register, and variants")
    void testAuthCommandDetection() {
        CompatService service = new CompatService(null);

        assertTrue(service.isAuthCommand("/login password123"));
        assertTrue(service.isAuthCommand("/l secret"));
        assertTrue(service.isAuthCommand("register pass pass"));
        assertTrue(service.isAuthCommand("/reg pass pass"));
        assertTrue(service.isAuthCommand("/changepassword old new"));
        assertTrue(service.isAuthCommand("/cp old new"));
        assertTrue(service.isAuthCommand("/authme:login secret"));
        assertTrue(service.isAuthCommand("/totp 123456"));

        assertFalse(service.isAuthCommand("/spawn"));
        assertFalse(service.isAuthCommand("/help"));
        assertFalse(service.isAuthCommand("/op Steve"));
        assertFalse(service.isAuthCommand(null));
        assertFalse(service.isAuthCommand(""));
    }

    @Test
    @DisplayName("Redaction strips auth command arguments while keeping root command")
    void testAuthCommandRedaction() {
        CompatService service = new CompatService(null);

        assertEquals("/login [REDACTED]", service.redactAuthCommand("/login mySuperSecretPassword!"));
        assertEquals("/register [REDACTED]", service.redactAuthCommand("/register secret123 secret123"));
        assertEquals("/changepassword [REDACTED]", service.redactAuthCommand("/changepassword oldSecret newSecret"));
        assertEquals("/spawn", service.redactAuthCommand("/spawn"));
        assertEquals("/acm blacklist add op", service.redactAuthCommand("/acm blacklist add op"));
    }
}
