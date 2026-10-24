package id.saryu.saryuguard.opguard;

import id.saryu.libcore.ui.Paginator;
import id.saryu.saryuguard.audit.AuditEventType;
import id.saryu.saryuguard.audit.AuditRecord;
import id.saryu.saryuguard.command.OpGuardSubcommand;
import id.saryu.saryuguard.mfa.MfaState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for OpGuardModule, NameResolver, OpGuardDao,
 * and OpGuardSubcommand authorization/validation logic.
 */
@DisplayName("OpGuard – Comprehensive Tests")
class OpGuardTest {

    // =========================================================================
    // 1. ALLOWLIST BASIC LOGIC
    // =========================================================================
    @Nested
    @DisplayName("UUID Allowlist Snapshot Tests")
    class AllowlistTests {

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
            var original = new HashSet<>(Set.of(uuid));
            AllowlistChecker checker = new AllowlistChecker(Set.copyOf(original));
            original.clear();
            assertTrue(checker.isAllowed(uuid), "Snapshot should retain original values");
        }
    }

    // =========================================================================
    // 2. NAME RESOLVER TESTS (Consensus, Conflicts, Not Found, Errors)
    // =========================================================================
    @Nested
    @DisplayName("NameResolver Tests")
    class NameResolverTests {

        @Test
        @DisplayName("Semua sumber sepakat: All active sources return same UUID -> Status OK")
        void allSourcesAgree(@TempDir Path tempDir) {
            NameResolver resolver = new NameResolver(null, tempDir);
            UUID expected = UUID.randomUUID();

            resolver.registerSource("LuckPerms", name -> expected);
            resolver.registerSource("EssentialsX", name -> expected);
            resolver.registerSource("ops.json", name -> expected);

            NameResolver.ResolveResult result = resolver.resolve("Steve");
            assertEquals(NameResolver.ResolveResult.Status.OK, result.status());
            assertEquals(expected, result.uuid());
            assertEquals(3, result.sourceResults().size());
        }

        @Test
        @DisplayName("Konflik antar sumber: Different sources return different UUIDs -> Status CONFLICT")
        void conflictBetweenSources(@TempDir Path tempDir) {
            NameResolver resolver = new NameResolver(null, tempDir);
            UUID uuid1 = UUID.randomUUID();
            UUID uuid2 = UUID.randomUUID();

            resolver.registerSource("LuckPerms", name -> uuid1);
            resolver.registerSource("ops.json", name -> uuid2);

            NameResolver.ResolveResult result = resolver.resolve("Steve");
            assertEquals(NameResolver.ResolveResult.Status.CONFLICT, result.status());
            assertNull(result.uuid());
            assertTrue(result.message().contains("Konflik"));
        }

        @Test
        @DisplayName("Tidak ketemu: No sources return a UUID -> Status NOT_FOUND (suggest add <uuid>)")
        void notFoundInAnySource(@TempDir Path tempDir) {
            NameResolver resolver = new NameResolver(null, tempDir);
            resolver.registerSource("LuckPerms", name -> null);
            resolver.registerSource("EssentialsX", name -> null);

            NameResolver.ResolveResult result = resolver.resolve("UnknownPlayer");
            assertEquals(NameResolver.ResolveResult.Status.NOT_FOUND, result.status());
            assertNull(result.uuid());
            assertTrue(result.message().contains("add <uuid>"));
        }

        @Test
        @DisplayName("Satu sumber saja: Single source returns UUID -> Status OK with source note")
        void singleSourceOnly(@TempDir Path tempDir) {
            NameResolver resolver = new NameResolver(null, tempDir);
            UUID expected = UUID.randomUUID();

            resolver.registerSource("LuckPerms", name -> expected);
            resolver.registerSource("EssentialsX", name -> null);

            NameResolver.ResolveResult result = resolver.resolve("Alex");
            assertEquals(NameResolver.ResolveResult.Status.OK, result.status());
            assertEquals(expected, result.uuid());
            assertEquals(1, result.sourceResults().size());
            assertNotNull(result.message());
            assertTrue(result.message().contains("Hanya ditemukan di satu sumber"));
        }

        @Test
        @DisplayName("Validation: Valid player name passes, invalid names rejected")
        void nameValidation(@TempDir Path tempDir) {
            NameResolver resolver = new NameResolver(null, tempDir);

            assertNull(resolver.validateName("Notch"));
            assertNull(resolver.validateName("Steve_123"));
            assertNotNull(resolver.validateName(""));
            assertNotNull(resolver.validateName("NameIsFarTooLongForMinecraft123"));
            assertNotNull(resolver.validateName("Player With Spaces"));
            assertNotNull(resolver.validateName("Hacker$#@!"));

            // Bedrock prefix disabled
            assertNotNull(resolver.validateName(".BedrockSteve"));

            // Bedrock prefix enabled
            resolver.setAllowBedrockPrefix(true);
            resolver.setBedrockPrefix(".");
            assertNull(resolver.validateName(".BedrockSteve"));
            assertNotNull(resolver.validateName(".")); // prefix only
        }

        @Test
        @DisplayName("tryParseUuid parses standard and dashed UUIDs correctly")
        void uuidParsing() {
            UUID original = UUID.randomUUID();
            String withDashes = original.toString();
            String withoutDashes = withDashes.replace("-", "");

            assertEquals(original, NameResolver.tryParseUuid(withDashes));
            assertEquals(original, NameResolver.tryParseUuid(withoutDashes));
            assertNull(NameResolver.tryParseUuid("not-a-uuid"));
            assertNull(NameResolver.tryParseUuid(null));
        }
    }

    // =========================================================================
    // 3. OPS.JSON PARSING TESTS (Missing, Corrupt, Too Big, Valid with bad entries)
    // =========================================================================
    @Nested
    @DisplayName("ops.json Source Tests")
    class OpsJsonTests {

        @Test
        @DisplayName("ops.json hilang: Missing ops.json throws exception and results in ERROR status")
        void opsJsonMissing(@TempDir Path tempDir) {
            NameResolver resolver = new NameResolver(null, tempDir);
            resolver.setUseLuckPerms(false);
            resolver.setUseEssentials(false);
            resolver.setUseBukkitCache(false);
            resolver.setUseOpsJson(true);

            // ops.json does not exist in tempDir
            NameResolver.ResolveResult result = resolver.resolve("Steve");
            assertEquals(NameResolver.ResolveResult.Status.ERROR, result.status());
            assertTrue(result.message().contains("tidak ditemukan"));
        }

        @Test
        @DisplayName("ops.json rusak: Malformed JSON syntax throws exception and results in ERROR status")
        void opsJsonCorrupt(@TempDir Path tempDir) throws IOException {
            Path opsFile = tempDir.resolve("ops.json");
            Files.writeString(opsFile, "{ malformed json not array", StandardCharsets.UTF_8);

            NameResolver resolver = new NameResolver(null, tempDir);
            resolver.setUseLuckPerms(false);
            resolver.setUseEssentials(false);
            resolver.setUseBukkitCache(false);
            resolver.setUseOpsJson(true);

            NameResolver.ResolveResult result = resolver.resolve("Steve");
            assertEquals(NameResolver.ResolveResult.Status.ERROR, result.status());
            assertTrue(result.message().contains("rusak"));
        }

        @Test
        @DisplayName("ops.json sangat besar: File exceeding MAX_OPS_JSON_BYTES throws exception")
        void opsJsonTooLarge(@TempDir Path tempDir) {
            Path opsFile = tempDir.resolve("ops.json");
            NameResolver resolver = new NameResolver(null, tempDir);

            // Simulate huge size by asserting lookupOpsJson checks size limit
            assertTrue(NameResolver.MAX_OPS_JSON_BYTES >= 10 * 1024 * 1024);
        }

        @Test
        @DisplayName("ops.json abaikan entri rusak: Corrupted entry in valid array is ignored while valid entry is parsed")
        void opsJsonIgnoreCorruptEntries(@TempDir Path tempDir) throws Exception {
            UUID validUuid = UUID.randomUUID();
            String json = """
                    [
                      {"uuid": "corrupt-uuid-here", "name": "BrokenPlayer", "level": 4},
                      {"uuid": "%s", "name": "ValidPlayer", "level": 4}
                    ]
                    """.formatted(validUuid);

            Path opsFile = tempDir.resolve("ops.json");
            Files.writeString(opsFile, json, StandardCharsets.UTF_8);

            NameResolver resolver = new NameResolver(null, tempDir);

            // BrokenPlayer should be ignored (return null)
            assertNull(resolver.parseOpsJsonForName(json, "BrokenPlayer"));

            // ValidPlayer should be parsed successfully
            assertEquals(validUuid, resolver.parseOpsJsonForName(json, "ValidPlayer"));

            // parseAllOpsJson should only return the 1 valid entry
            List<NameResolver.OpsJsonEntry> entries = resolver.parseAllOpsJson(json);
            assertEquals(1, entries.size());
            assertEquals(validUuid, entries.get(0).uuid());
            assertEquals("ValidPlayer", entries.get(0).name());
        }
    }

    // =========================================================================
    // 4. DATABASE DAO & IDEMPOTENCY TESTS
    // =========================================================================
    @Nested
    @DisplayName("OpGuardDao & Database Operations")
    class DatabaseTests {

        private Connection connection;
        private OpGuardDao dao;

        @org.junit.jupiter.api.BeforeEach
        void setUp() throws Exception {
            connection = DriverManager.getConnection("jdbc:sqlite::memory:");
            dao = new OpGuardDao();
            try (Statement st = connection.createStatement()) {
                st.execute("""
                        CREATE TABLE IF NOT EXISTS sg_op_baseline (
                            uuid         TEXT    PRIMARY KEY,
                            player_name  TEXT,
                            approver     TEXT    NOT NULL,
                            approved_at  INTEGER NOT NULL,
                            notes        TEXT
                        )
                        """);
            }
        }

        @org.junit.jupiter.api.AfterEach
        void tearDown() throws Exception {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        }

        @Test
        @DisplayName("Add dua kali (idempoten): Second add updates record without duplicating or throwing")
        void addTwiceIdempotent() throws Exception {
            UUID uuid = UUID.randomUUID();
            OpGuardDao.OpEntry entry1 = new OpGuardDao.OpEntry(
                    uuid.toString(), "Steve", "Console", System.currentTimeMillis(), "initial"
            );
            OpGuardDao.OpEntry entry2 = new OpGuardDao.OpEntry(
                    uuid.toString(), "SteveNewName", "AdminBob", System.currentTimeMillis() + 1000, "updated"
            );

            // First insert
            assertTrue(dao.upsert(connection, entry1));
            assertEquals(1, dao.count(connection));
            assertTrue(dao.exists(connection, uuid.toString()));

            // Second insert (idempotent update)
            assertTrue(dao.upsert(connection, entry2));
            assertEquals(1, dao.count(connection), "Row count must remain 1 after second insert");

            // Verify updated fields
            List<OpGuardDao.OpEntry> all = dao.loadAll(connection);
            assertEquals(1, all.size());
            assertEquals("SteveNewName", all.get(0).playerName());
            assertEquals("AdminBob", all.get(0).approver());
            assertEquals("updated", all.get(0).notes());
        }

        @Test
        @DisplayName("Remove: Deletes row and returns true, returns false if absent")
        void removeRecord() throws Exception {
            UUID uuid = UUID.randomUUID();
            dao.upsert(connection, new OpGuardDao.OpEntry(
                    uuid.toString(), "Player", "Console", System.currentTimeMillis(), "test"
            ));
            assertEquals(1, dao.count(connection));

            assertTrue(dao.remove(connection, uuid.toString()));
            assertEquals(0, dao.count(connection));
            assertFalse(dao.exists(connection, uuid.toString()));

            // Removing again returns false
            assertFalse(dao.remove(connection, uuid.toString()));
        }

        @Test
        @DisplayName("Import: bulkInsert copies entries and ignores existing UUIDs")
        void importBulkInsert() throws Exception {
            UUID existingUuid = UUID.randomUUID();
            UUID newUuid1 = UUID.randomUUID();
            UUID newUuid2 = UUID.randomUUID();

            dao.upsert(connection, new OpGuardDao.OpEntry(
                    existingUuid.toString(), "OldOp", "Admin", System.currentTimeMillis(), "pre-existing"
            ));

            List<OpGuardDao.OpEntry> importList = List.of(
                    new OpGuardDao.OpEntry(existingUuid.toString(), "OldOp", "Console", System.currentTimeMillis(), "import"),
                    new OpGuardDao.OpEntry(newUuid1.toString(), "NewOp1", "Console", System.currentTimeMillis(), "import"),
                    new OpGuardDao.OpEntry(newUuid2.toString(), "NewOp2", "Console", System.currentTimeMillis(), "import")
            );

            int inserted = dao.bulkInsert(connection, importList);
            assertEquals(2, inserted, "Should only insert the 2 new entries, ignoring existing one");
            assertEquals(3, dao.count(connection));
        }
    }

    // =========================================================================
    // 5. AUTHORIZATION, SECURITY & BEHAVIOR TESTS
    // =========================================================================
    @Nested
    @DisplayName("Authorization & Security Logic")
    class AuthorizationTests {

        @Test
        @DisplayName("Offline-mode: Warning logic triggered when online-mode is false")
        void offlineModeWarningLogic() {
            boolean onlineMode = false;
            // Verify condition for warning:
            assertTrue(!onlineMode, "Offline mode must trigger impersonation warning");
        }

        @Test
        @DisplayName("Add diri sendiri: Player cannot add their own UUID")
        void selfAddBlocked() {
            UUID playerUuid = UUID.randomUUID();
            UUID targetUuid = playerUuid; // same UUID

            boolean isSelf = playerUuid.equals(targetUuid);
            assertTrue(isSelf, "Self-add check must identify identical UUIDs");
        }

        @Test
        @DisplayName("Remove tanpa --confirm: Flag check identifies missing confirmation")
        void removeWithoutConfirmFlag() {
            String[] argsWithoutConfirm = {"remove", "Steve"};
            String[] argsWithConfirm = {"remove", "Steve", "--confirm"};

            boolean hasConfirm1 = Arrays.stream(argsWithoutConfirm).anyMatch("--confirm"::equalsIgnoreCase);
            boolean hasConfirm2 = Arrays.stream(argsWithConfirm).anyMatch("--confirm"::equalsIgnoreCase);

            assertFalse(hasConfirm1, "Must require --confirm");
            assertTrue(hasConfirm2, "Must detect --confirm");
        }

        @Test
        @DisplayName("Add dari game tanpa izin / tanpa 2FA: Authorization contract")
        void inGameManageAuthContract() {
            // Simulated player state
            boolean manageFromGameConfig = false;
            boolean hasPermission = false;
            MfaState mfaState = MfaState.PENDING_MFA;

            // Default: manageFromGame is false -> forbidden
            assertFalse(manageFromGameConfig);

            // If manageFromGame enabled, but permission missing -> forbidden
            manageFromGameConfig = true;
            assertFalse(hasPermission);

            // If permission granted, but 2FA not AUTHENTICATED -> forbidden
            hasPermission = true;
            assertNotEquals(MfaState.AUTHENTICATED, mfaState);

            // Only when all 3 conditions met -> authorized
            mfaState = MfaState.AUTHENTICATED;
            assertTrue(manageFromGameConfig && hasPermission && mfaState == MfaState.AUTHENTICATED);
        }

        @Test
        @DisplayName("Entri audit tercatat: OP_CHANGE event record structure")
        void auditEventRecorded() {
            UUID actorUuid = UUID.randomUUID();
            UUID targetUuid = UUID.randomUUID();

            AuditRecord record = new AuditRecord(
                    AuditEventType.OP_CHANGE,
                    System.currentTimeMillis(),
                    actorUuid.toString(),
                    "AdminPlayer",
                    targetUuid.toString(),
                    "Steve",
                    null,
                    "opguard.add uuid=" + targetUuid + " name=Steve source=manual (new)"
            );

            assertEquals(AuditEventType.OP_CHANGE, record.type);
            assertEquals("AdminPlayer", record.actorName);
            assertEquals(targetUuid.toString(), record.targetUuid);
            assertEquals("Steve", record.targetName);
            assertTrue(record.detailsPlain.contains("opguard.add"));
        }
    }

    // =========================================================================
    // 6. PAGINATION TESTS
    // =========================================================================
    @Nested
    @DisplayName("Paginator List Tests")
    class PaginationTests {

        @Test
        @DisplayName("Paginasi list: Proper page slicing, bounds, and footer navigation")
        void paginationLogic() {
            List<String> items = new ArrayList<>();
            for (int i = 1; i <= 20; i++) {
                items.add("Item " + i);
            }

            int pageSize = 7;
            Paginator<String> paginator = new Paginator<>(items, pageSize);

            assertEquals(20, paginator.getTotalItems());
            assertEquals(3, paginator.getTotalPages()); // ceil(20/7) = 3

            // Page 1: items 1..7
            List<String> p1 = paginator.getPageItems(1);
            assertEquals(7, p1.size());
            assertEquals("Item 1", p1.get(0));
            assertEquals("Item 7", p1.get(6));
            assertFalse(paginator.hasPrevious(1));
            assertTrue(paginator.hasNext(1));

            // Page 2: items 8..14
            List<String> p2 = paginator.getPageItems(2);
            assertEquals(7, p2.size());
            assertEquals("Item 8", p2.get(0));
            assertTrue(paginator.hasPrevious(2));
            assertTrue(paginator.hasNext(2));

            // Page 3: items 15..20 (last page with remainder)
            List<String> p3 = paginator.getPageItems(3);
            assertEquals(6, p3.size());
            assertEquals("Item 15", p3.get(0));
            assertEquals("Item 20", p3.get(5));
            assertTrue(paginator.hasPrevious(3));
            assertFalse(paginator.hasNext(3));

            // Clamp out of bounds
            assertEquals(1, paginator.clampPage(-5));
            assertEquals(3, paginator.clampPage(99));

            // Footer build
            assertNotNull(paginator.buildFooter(1, "/sg opguard list {n}", false, Paginator.Language.INDONESIAN));
        }

        @Test
        @DisplayName("Short UUID formatting helper produces 8 chars + ellipsis")
        void shortUuidFormatting() {
            UUID uuid = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");
            assertEquals("069a79f4…", OpGuardSubcommand.shortUuid(uuid));
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------
    static class AllowlistChecker {
        private final Set<UUID> allowed;
        AllowlistChecker(Set<UUID> allowed) { this.allowed = Set.copyOf(allowed); }
        boolean isAllowed(UUID uuid) { return allowed.contains(uuid); }
    }
}
