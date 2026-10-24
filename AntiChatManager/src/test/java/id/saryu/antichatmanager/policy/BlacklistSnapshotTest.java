package id.saryu.antichatmanager.policy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlacklistSnapshotTest {

    @Test
    @DisplayName("Normalization strips slashes, collapses internal whitespace, and lowercases")
    void testNormalization() {
        assertEquals("op", BlacklistSnapshot.normalize("/op"));
        assertEquals("op", BlacklistSnapshot.normalize("  ///OP  "));
        assertEquals("minecraft:op", BlacklistSnapshot.normalize("/MINECRAFT:OP"));
        assertEquals("lp user admin", BlacklistSnapshot.normalize("  /lp   user   admin  "));
        assertEquals("", BlacklistSnapshot.normalize(null));
        assertEquals("", BlacklistSnapshot.normalize("   "));
    }

    @Test
    @DisplayName("Unnamespaced rule matches plain command and all namespaced variants")
    void testUnnamespacedMatching() {
        BlacklistSnapshot.Entry opEntry = new BlacklistSnapshot.Entry("op", "MANUAL", "ADMIN", System.currentTimeMillis());
        BlacklistSnapshot snapshot = new BlacklistSnapshot(List.of(opEntry));

        // Matches bare "op"
        assertTrue(snapshot.matches("op"));
        assertTrue(snapshot.matches("/op"));
        assertTrue(snapshot.matches("OP"));

        // Matches all namespaced variants
        assertTrue(snapshot.matches("minecraft:op"));
        assertTrue(snapshot.matches("bukkit:op"));
        assertTrue(snapshot.matches("essentials:op"));

        // Does not match other commands
        assertFalse(snapshot.matches("deop"));
        assertFalse(snapshot.matches("stop"));
        assertFalse(snapshot.matches("open"));
    }

    @Test
    @DisplayName("Namespaced rule matches ONLY strictly, does not match bare or other namespaces")
    void testNamespacedMatching() {
        BlacklistSnapshot.Entry entry = new BlacklistSnapshot.Entry("essentials:op", "MANUAL", "ADMIN", System.currentTimeMillis());
        BlacklistSnapshot snapshot = new BlacklistSnapshot(List.of(entry));

        // Strict match on exact namespace
        assertTrue(snapshot.matches("essentials:op"));
        assertTrue(snapshot.matches("/ESSENTIALS:OP"));

        // Must NOT match bare command
        assertFalse(snapshot.matches("op"));

        // Must NOT match different namespaces
        assertFalse(snapshot.matches("minecraft:op"));
        assertFalse(snapshot.matches("bukkit:op"));
    }

    @Test
    @DisplayName("Alias matching via CommandMap canonical names and aliases")
    void testAliasMatching() {
        BlacklistSnapshot.Entry plEntry = new BlacklistSnapshot.Entry("plugins", "MANUAL", "ADMIN", System.currentTimeMillis());
        BlacklistSnapshot snapshot = new BlacklistSnapshot(List.of(plEntry));

        // Direct match
        assertTrue(snapshot.matches("plugins"));

        // Alias match: "/pl" resolved to canonical "plugins"
        assertTrue(snapshot.matchesAlias("plugins", List.of("pl")));

        // Alias match: rule is "pl", checking canonical "plugins" with alias "pl"
        BlacklistSnapshot.Entry aliasRule = new BlacklistSnapshot.Entry("pl", "MANUAL", "ADMIN", System.currentTimeMillis());
        BlacklistSnapshot snap2 = new BlacklistSnapshot(List.of(aliasRule));
        assertTrue(snap2.matchesAlias("plugins", List.of("pl")));
    }
}
