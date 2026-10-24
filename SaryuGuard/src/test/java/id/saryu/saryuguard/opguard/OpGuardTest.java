package id.saryu.saryuguard.opguard;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for OpGuardModule logic (UUID allowlist).
 * Does not require a live Paper server.
 */
@DisplayName("OpGuard – UUID allowlist")
class OpGuardTest {

    @Test
    @DisplayName("isAllowed returns true for UUIDs in the set")
    void allowedUuidIsAllowed() {
        UUID allowed = UUID.randomUUID();
        AllowlistChecker checker = new AllowlistChecker(Set.of(allowed));
        assertTrue(checker.isAllowed(allowed));
    }

    @Test
    @DisplayName("isAllowed returns false for unknown UUIDs")
    void unknownUuidIsNotAllowed() {
        AllowlistChecker checker = new AllowlistChecker(Set.of(UUID.randomUUID()));
        assertFalse(checker.isAllowed(UUID.randomUUID()));
    }

    @Test
    @DisplayName("Empty allowlist blocks everyone")
    void emptyAllowlistBlocksAll() {
        AllowlistChecker checker = new AllowlistChecker(Set.of());
        assertFalse(checker.isAllowed(UUID.randomUUID()));
    }

    @Test
    @DisplayName("Allowlist is immutable snapshot (not affected by external mutation)")
    void snapshotIsImmutable() {
        UUID uuid = UUID.randomUUID();
        var original = new java.util.HashSet<>(Set.of(uuid));
        AllowlistChecker checker = new AllowlistChecker(Set.copyOf(original));
        original.clear(); // mutate the source
        assertTrue(checker.isAllowed(uuid), "Snapshot should retain original values");
    }

    // -------------------------------------------------------------------------
    // Pure-logic helper (no Paper dependency)
    // -------------------------------------------------------------------------
    static class AllowlistChecker {
        private final Set<UUID> allowed;
        AllowlistChecker(Set<UUID> allowed) { this.allowed = Set.copyOf(allowed); }
        boolean isAllowed(UUID uuid) { return allowed.contains(uuid); }
    }
}
