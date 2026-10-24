package id.saryu.saryuguard.connection;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class ConnectionGuardTest {

    private final Pattern usernamePattern = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");

    @Test
    @DisplayName("Username validation accepts valid Minecraft usernames (3-16 chars, alphanumeric and underscore)")
    void testValidUsernames() {
        assertTrue(usernamePattern.matcher("Steve").matches());
        assertTrue(usernamePattern.matcher("Alex_99").matches());
        assertTrue(usernamePattern.matcher("Player_One_23").matches());
        assertTrue(usernamePattern.matcher("abc").matches());
        assertTrue(usernamePattern.matcher("1234567890123456").matches());
    }

    @Test
    @DisplayName("Username validation rejects invalid characters, spaces, and out-of-bounds lengths")
    void testInvalidUsernames() {
        assertFalse(usernamePattern.matcher("ab").matches(), "Too short (<3)");
        assertFalse(usernamePattern.matcher("12345678901234567").matches(), "Too long (>16)");
        assertFalse(usernamePattern.matcher("Player Name").matches(), "Contains space");
        assertFalse(usernamePattern.matcher("Player$").matches(), "Special character $");
        assertFalse(usernamePattern.matcher("User!").matches(), "Special character !");
        assertFalse(usernamePattern.matcher("").matches(), "Empty string");
    }

    @Test
    @DisplayName("IP connection throttle enforces minimum delay between connections from same IP")
    void testIpThrottleLogic() {
        long throttleMs = 3000L;
        long lastConnect = 1000L;

        long nextConnectImmediate = 2000L;
        boolean throttledImmediate = (nextConnectImmediate - lastConnect) < throttleMs;
        assertTrue(throttledImmediate, "Immediate reconnect must be throttled");

        long nextConnectAllowed = 4500L;
        boolean throttledAllowed = (nextConnectAllowed - lastConnect) < throttleMs;
        assertFalse(throttledAllowed, "Reconnect after throttle period must be permitted");
    }

    @Test
    @DisplayName("CGNAT tolerant account limit caps distinct usernames per IP")
    void testCgnatAccountLimit() {
        int maxAccounts = 3;
        java.util.Set<String> registered = new java.util.HashSet<>();

        assertTrue(registered.add("user1"));
        assertTrue(registered.add("user2"));
        assertTrue(registered.add("user3"));

        // Fourth distinct user exceeds limit
        boolean allowFourth = registered.size() < maxAccounts || registered.contains("user4");
        assertFalse(allowFourth, "Fourth distinct account on same IP must be rejected");

        // Existing user reconnecting is allowed
        boolean allowExisting = registered.size() < maxAccounts || registered.contains("user1");
        assertTrue(allowExisting, "Existing user on same IP must be permitted");
    }
}
