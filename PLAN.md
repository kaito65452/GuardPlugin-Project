# DEVELOPMENT PLAN: ANTICHATMANAGER LITE (4.0.0) + SARYUGUARD

Paper 1.21.x–26.x · Gradle Kotlin DSL · Pesan en/id

---

## 0. General Rules & Architectural Principles

1. **Process & Quality Gates**:
   - Every phase must have its code written and automated tests passing before proceeding to the next phase.
   - Each phase ends with a comprehensive report: modified/added files, executed tests with results, and aspects that could not be tested on a live server.
2. **Build & Compatibility**:
   - Multi-module Gradle build: `SaryuLibCore`, `AntiChatManager`, `SaryuGuard`.
   - Compile target: JDK 21 with `--release 21` against oldest supported Paper API (`1.21-R0.1-SNAPSHOT`).
   - Bytecode runs on Java 21 and Java 25.
   - Documentation must state: Paper 1.21.x requires Java 21, Paper 26.1+ requires Java 25.
   - Only genuinely tested versions in README (Paper matrix, Java, SaryuLibCore, AuthMe, Sonar, databases).
   - Standard `plugin.yml` for all plugins (`paper-plugin.yml` only if bootstrapper needed).
   - Reproducible builds: `preserveFileTimestamps = false`, `reproducibleFileOrder = true`.
   - Shading & relocation for small pure-Java libraries: RE2J, Argon2id (BouncyCastle lightweight Argon2id), TOML parser (`toml4j`).
   - SaryuLibCore dependency check: if missing, log a clean error message and disable without throwing stacktraces.
   - CI: GitHub Actions use full version tags pinned to SHA hashes (maintained by Dependabot).
3. **Security Standards**:
   - Zero `eval()`, zero shell scripts in JVM execution, prevent path traversal, strict config file limits.
   - All I/O and network requests are async with timeout, rate limit, and opt-in HTTPS.
   - All shared data structures accessed from async events are bounded and thread-safe.
   - All administrative permissions default to `op` (never `true`).
   - Threat Model documented in README:
     - IN SCOPE: rogue/corrupt plugins, modified jars, unauthorized OP escalation, brute-force attacks, inappropriate chat.
     - OUT OF SCOPE: attackers with root/OS compromise, full filesystem/JVM access, volumetric DDoS.
   - Security component statuses: `ACTIVE`, `DEGRADED`, `DISABLED`, `ERROR`. Never report "enabled" if malfunctioning.
   - Documentation clearly distinguishes DENY protections vs ALERT-only detections.

---

## Phase A: SaryuGuard Core Framework & Shared UI Paginator

### Objectives
- Set up `SaryuGuard` build configuration in Gradle (Kotlin DSL), shading TOML parser, reproducible build attributes.
- Create SaryuGuard core module lifecycle: `SaryuGuardModule` interface with `enable()`, `disable()`, and real status reporting (`ACTIVE`, `DEGRADED`, `DISABLED`, `ERROR`).
- Implement safe configuration management (`config.yml`, `locales/id.yml`, `locales/en.yml`, `credentials.toml`, `secret.key`, `secret.toml`) with size limits and path traversal protection.
- Implement shared interactive clickable Paginator in `SaryuLibCore`:
  - Format: `[« Sebelumnya] Halaman 2/5 [Berikutnya »]`.
  - Runs `/<cmd> ... page <n>` with click event and fallback `suggest_command`.
  - Re-checks permissions on each execution.
  - Page number validation.
- Implement `/sg` root command with `/sg help`, `/sg status`, `/sg reload`, `/sg version` and tab completion.
- Unit tests for Paginator (0 items, 1 item, exact 1 page, page out of bounds), config loading, module lifecycle.

### Deliverables
- `SaryuLibCore/src/main/java/id/saryu/libcore/ui/Paginator.java` & tests.
- `SaryuGuard/build.gradle.kts`, `SaryuGuard/src/main/resources/plugin.yml`.
- `SaryuGuard/src/main/java/id/saryu/saryuguard/` core classes (`SaryuGuardPlugin`, `Module`, `Status`, `ConfigManager`, `LocaleManager`).
- Commands: `/sg help`, `/sg status`, `/sg reload`, `/sg version`.

---

## Phase B: SaryuGuard Detect + Plugin Verify (Feature Transfer)

### Objectives
- Move plugin identity, jar hash verification, anti-cheat detection, and security audit from ACM to SaryuGuard.
- **3.1 Anti-Cheat & Plugin Detect**:
  - Detect GrimAC, Vulcan, Themis, NovaAC, Sonar, AuthMe, and other AC plugins via `PluginManager`.
  - Validate plugin names from their respective `plugin.yml` and check `isEnabled()`.
  - Emit warning when two heavy anti-cheats are active concurrently.
  - Share state across modules via `CompatService`.
  - When AuthMe is detected: auth commands are never blocked, auth arguments never logged/saved; 2FA hooks after successful AuthMe login event.
- **3.2 Plugin Verify**:
  - Jar SHA-256 calculation.
  - Modrinth hash lookup (`/v2/version_file/<hash>`), verified endpoint and algorithms -> `VERIFIED`.
  - Hangar & Spigot name/author fallback -> low-confidence `UNVERIFIED` (never `VERIFIED` without verified doc endpoint).
  - Statuses: `VERIFIED`, `UNVERIFIED`, `MISMATCH`, `SCAN_ERROR` (`SCAN_ERROR` never treated as clean).
  - Local baseline stored in database with timestamp and approver. New/changed jar hash marked `MISMATCH`/`UNKNOWN` until `/sg plugins approve <plugin>`.
  - Monitoring via `WatchService` + periodic fallback polling with hash deduplication.
  - Opt-in network, cached responses, rate-limited.
  - Interactive clickable pagination for `/sg plugins [page]`.
- Commands: `/sg plugins [page]`, `/sg plugins approve <plugin>`, `/sg scan verify`.

---

## Phase C1: AntiChatManager Lite 4.0.0 (Core Blacklist, Policy Status, 3.x Migration)

### Objectives
- Focus AntiChatManager purely on label-based command blacklisting.
- **Commands**:
  - `/acm version`
  - `/acm blacklist info [page] | add <command> | remove <command>`
  - `/acm reload` (permission `antichatmanager.admin`, tab completion for all).
- **Files**:
  - `languages/` (templates `eng_US`, `id_ID`), `database/`, `config.yml`, `messages.yml`, `database.yml` (`sqlite`|`mysql`).
- **Policy States**:
  - `READY`: Valid cache loaded and active.
  - `DEGRADED`: Database query failed, but previous valid cache exists. Active with throttled admin alerts (console + online admins).
  - `UNAVAILABLE`: No valid policy ever loaded (e.g. DB down at startup, empty cache). Fallback to `emergency-blacklist` (`op, deop, stop, reload, restart`). Massive ERROR log. `/acm reload` must fail and report error.
  - Empty cache is never treated as empty blacklist.
  - Zero DB access during command execution; cache is an immutable snapshot swapped via `AtomicReference`.
- **Command Interception**:
  - `PlayerCommandPreprocessEvent` at `LOWEST`, `ignoreCancelled=false`.
  - Manual check of `isCancelled()`.
  - Normalization: trim, collapse multiple spaces, lowercase, strip leading `/`.
  - Unnamespaced entry matches bare name and namespaced variants. Namespaced entry (`essentials:op`) matches strictly. Alias resolution via `CommandMap`.
  - Strict string literal matching (no regex). Input validation for `add`: letters, digits, `_`, `-`, `:`, bounded length, reject duplicates.
- **3.x Migration**:
  - Safe backup of old files to `plugins/AntiChatManager/backup-3.x/`.
  - Read-only inspection of 3.x database.
  - Import `MANUAL`, `CONFIG`, `IMPORT` entries. Unlabeled entries imported as `legacy` flag for admin review. Auto-scan entries counted & reported, NOT imported.
  - Idempotent migration marker + unique constraints.
  - Verification: source vs imported counts + conflict logging.

---

## Phase C2: AntiChatManager Lite Cleanup (Feature Pruning)

### Objectives
- Remove discontinued features from AntiChatManager:
  - Compat mode, subcmd rules, plugin-manual-scan, WorldEdit/FAWE/WorldGuard integration, conflicts, LuckPerms audit, custom alert templates, history/violations, export/import, auto-scan.
- When removed subcommands are invoked, show informative redirect message pointing to SaryuGuard ("dipindah ke SaryuGuard").
- Verify clean compilation, reduced jar size, and zero leftover deprecated background tasks.

---

## Phase D1: SaryuGuard Chat Filter

### Objectives
- Intercept chat via `AsyncChatEvent` (`chat-filter.enabled`).
- Text Normalization Pipeline:
  - Lowercase, Unicode NFKC normalization, diacritic stripping, zero-width & RTL override removal.
  - Basic homoglyph map (Cyrillic/Greek lookalikes to Latin).
  - Leet substitution map (`0`->`o`, `1`->`i`, `3`->`e`, `4`->`a`, `5`->`s`, `7`->`t`, `@`->`a`).
  - Collapse repeated character sequences.
- Layer 1: Aho-Corasick automaton over normalized text with word boundary checks and whitelist (protect Indonesian false positives).
- Censored character detection (`*`): Trie search without regex, maximum 2 asterisks per token, minimum token length, whitelisting.
- Layer 2: Admin regular expressions exclusively via pure-Java `RE2J`. Pre-compiled, complexity/length limits. Atomic ruleset replacement.
- Fail-safe error handling (`on-error: allow|block`, default `allow` with audit log).
- Offender isolation: modify `event.viewers()` so only sender sees offending message, send warning; do NOT cancel and resend manually.
- Management: `/sg words add|remove|list [page]`.
- Performance benchmark tests: < 2 ms per message under 100 simulated players; dictionary rebuild < 500 ms.

---

## Phase D2: SaryuGuard OpGuard & Staff 2FA

### Objectives
- **OpGuard**:
  - UUID-based authorized OP baseline.
  - Periodic and on-join checks.
  - Modes: `enforce` (safely de-op on main server thread) vs `warn` (alert staff/console). Does NOT block commands.
- **Staff 2FA**:
  - Applies to high-privilege accounts (configurable permission/groups/OP).
  - TOTP RFC 6238 using JDK built-in `HmacSHA1`. Secrets encrypted with AES-GCM (never stored as hash).
  - One-time recovery codes hashed using pure-Java Argon2id (BouncyCastle lightweight generator; fallback BCrypt; algorithm name prefixed).
  - State machine: `UNAUTHENTICATED` -> `PENDING_MFA` -> `AUTHENTICATED` | `LOCKED`.
  - Pending TTL: 5 minutes, then kick. Reconnecting resets to `PENDING_MFA`.
  - Privilege gating: LuckPerms `ContextCalculator` (`saryuguard:2fa=true`) so staff permissions apply only when authenticated. Fallback without LuckPerms: freeze movement, commands, chat, inventory.
  - Intercept code entry via `AsyncChatEvent` at `LOWEST`, cancelled. Bypass chat filter/logging for pending players.
  - Progressive backoff delay on failed attempts, then `LOCKED`.
  - Recovery console command: `sg 2fa reset <player>`.
  - One-time OTP setup secret & `otpauth://` URI display.

---

## Phase E1: SaryuGuard Encrypted Database & Schema Migration

### Objectives
- Storage backends: SQLite and H2.
- Column-level encryption with AES-256-GCM for sensitive fields (IP address, audit details, TOTP secrets).
- Random 12-byte IV per encryption operation (never reused). AAD bound to table name, row ID, column name, and key version.
- Key sources: `secret.key` (auto-generated 32-byte AES key), environment variable `SARYUGUARD_KEY`, or passphrase-derived key in `secret.toml`.
- Key rotation: `/sg crypt rotate` (console only) executing incremental batch re-encryption.
- Backup & restore verification tests.

---

## Phase E2: SaryuGuard Audit & Forensics

### Objectives
- Single-writer asynchronous queue processing log records.
- Cryptographic hash chain: `hash = SHA-256(prev_hash || canonical_bytes)`.
- Periodic and shutdown checkpoint file for hash chain head.
- Command `/sg audit verify` to validate chain integrity.
- Events logged: player join/quit, sensitive commands, OP changes, jar changes, scan results, violations.
- Strict redaction: auth command arguments never logged/saved. IP masking options.
- Graceful shutdown flush with timeout < 5 seconds.
- Retention cleanup and export functionality. Commands: `/sg audit [page]`, `/sg audit verify`.

---

## Phase E3: SaryuGuard Connection Guard & Compatibility Matrix

### Objectives
- Event-level connection defenses:
  - Pre-login IP throttle (`AsyncPlayerPreLoginEvent`).
  - Ping rate limit (`ServerListPingEvent`).
  - Attack mode (prioritize known returning players, throttle unknown newcomers).
  - Username regex validation.
  - Relaxed per-IP connection limit (CGNAT tolerant).
  - Optional rejoin throttle.
  - Proxy configuration warning when forwarding is misconfigured.
- Dynamic Compatibility Matrix:
  - Integrations with AuthMe and Sonar:
    - No AC: all checks active.
    - With AuthMe: delegate username/account limits to AuthMe (passive).
    - With Sonar: delegate connection throttles, attack mode, ping limits (passive).
    - If both present: use most passive configuration.
  - Failover (`compat.on-missing: full|keep`): if third-party plugin is disabled/uninstalled at runtime, immediately revert to full internal protection and log.
- Command: `/sg connection`.

---

## Phase F: SaryuGuard Malware Scanner (MCAntiMalware Wrapper)

### Objectives
- External scanner wrapper using `ProcessBuilder` (no shell execution).
- Admin-provided MCAntiMalware jar with pinned SHA-256 hash in `config.yml`. No code/signature bundling (GPL-3.0 compliance).
- Execution constraints: explicit `-Xmx`, timeout enforcement, single concurrent scan, isolated scratch working directory, bounded stdout/stderr buffers.
- Graceful degradation if external process execution is blocked by hosting environment.
- Statuses: `OK`, `DETECTED`, `SCAN_ERROR`.
- Startup script generator (`/sg scan malware generate-script`) for pre-startup scans.
- Command: `/sg scan malware`.

---

## Phase G: Integration, Paper 1.21 & 26 Matrix Verification, CI & Documentation

### Objectives
- Multi-version Paper verification (Paper 1.21.x and Paper 26.x).
- CI workflow pinning every action to full version tag + commit SHA.
- Final documentation in `README.md` and `SECURITY.md` (tested matrix, threat model, permission defaults, deny vs alert).
