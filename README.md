# Saryu Security Ecosystem: AntiChatManager Lite & SaryuGuard

> High-performance, multi-layered security suite for PaperMC servers (Paper 1.21.x – 26.x).  
> Built with Gradle Kotlin DSL, targeting Java 21 bytecode, running smoothly on Java 21 and Java 25. Bilingual Indonesian (`id`) & English (`en`).

---

## 1. Modules Overview

```text
GuardPlugin-Project/
├── SaryuLibCore/        # Shared core library: HikariCP pool, database drivers, Adventure UI, Paginator
├── AntiChatManager/     # AntiChatManager Lite 4.0.0: Ultra-focused, high-throughput command blacklist
└── SaryuGuard/          # SaryuGuard: Comprehensive server security, staff 2FA, OpGuard, audit & defenses
```

### AntiChatManager Lite (4.0.0)
Focused strictly on label-based command blacklisting with zero runtime database queries:
- **Immutable Snapshots**: Blackboard policy swapped atomically via `AtomicReference`.
- **Policy States**: `READY` (operational), `DEGRADED` (DB down, using cache), `UNAVAILABLE` (fallback to `emergency-blacklist`).
- **Matching & Normalization**: Strips leading `/`, whitespace, handles namespaces (`essentials:op`) and CommandMap aliases.
- **Automated 3.x Migration**: Safely imports legacy rules to SQLite/MySQL without data loss.

### SaryuGuard
Modular security engine with real-time operational status reporting (`ACTIVE`, `DEGRADED`, `DISABLED`, `ERROR`):
- **Detect & Plugin Verify**: Jar SHA-256 calculation, Modrinth hash verification, local approval baseline (`/sg plugins approve <plugin>`), anti-cheat detector (GrimAC, Vulcan, Sonar, etc.).
- **OpGuard**: UUID-based operator baseline with `enforce` (automatic de-op on main thread) and `warn` modes.
- **Staff 2FA**: RFC 6238 TOTP, AES-256-GCM encrypted secrets, Argon2id recovery codes, LuckPerms context calculator (`saryuguard:2fa=true`) and progressive lockouts.
- **Chat Filter**: AsyncChatEvent inspection, Unicode NFKC, diacritic stripping, homoglyph mapping, leet speak decoder, Aho-Corasick automaton, censor trie, and pure-Java RE2J admin regex.
- **Audit & Forensics**: Single-writer asynchronous queue, SHA-256 cryptographic hash chain, checkpoint file, retention cleaner, and `/sg audit verify`.
- **Connection Guard**: IP pre-login throttle, ping flood limiter, attack mode, username regex, CGNAT account limit, and dynamic Sonar/AuthMe delegation.
- **Malware Scanner**: External MCAntiMalware wrapper using `ProcessBuilder` (no shell scripts, pinned SHA-256 verification, strict memory limits).
- **Encrypted Database**: Column-level AES-256-GCM encryption with 12-byte IV, AAD binding, and console-only key rotation (`/sg crypt rotate`).

---

## 2. Tested Compatibility Matrix

| Component | Tested Versions | Notes |
|---|---|---|
| **PaperMC** | `1.21.4`, `26.2` (build 134) | Bytecode compiled with Java 21 target (`--release 21`). |
| **Java Runtime** | Java 21 LTS, Java 25 | Paper 1.21.x requires Java 21+; Paper 26.x requires Java 25. |
| **Databases** | SQLite 3 (bundled), H2 (bundled), MySQL | HikariCP connection pooling. |
| **Permissions** | LuckPerms 5.4+ | Context-aware 2FA gating (`saryuguard:2fa=true`). |
| **Auth** | AuthMe Reloaded | Auth commands/arguments never logged; 2FA activates after login. |
| **Anti-Cheats** | Sonar, GrimAC, Vulcan, Themis, NovaAC | Dynamic delegation for connection and ping throttles. |

### JVM Flags & Java 25 SQLite Notice

When running on Java 25 (mandatory for Paper 26.x), the SQLite JDBC driver invokes native JNI loading (`System.load`) which triggers a restricted method access warning in unnamed modules. To silence this warning and enable high-performance native SQLite access on Java 25, add `--enable-native-access=ALL-UNNAMED` to your server startup command:

```bash
java --enable-native-access=ALL-UNNAMED -Xms2G -Xmx4G -jar paper-26.2-134.jar --nogui
```

---

## 3. Command & Permission Reference

### AntiChatManager Lite (`/acm`)
| Command | Permission | Description |
|---|---|---|
| `/acm version` | `antichatmanager.admin` | Displays plugin version and current policy status. |
| `/acm blacklist info [page]` | `antichatmanager.admin` | Lists currently blacklisted commands with pagination. |
| `/acm blacklist add <cmd>` | `antichatmanager.admin` | Adds a command to the blacklist. |
| `/acm blacklist remove <cmd>`| `antichatmanager.admin` | Removes a command from the blacklist. |
| `/acm reload` | `antichatmanager.admin` | Reloads configurations and swaps blacklist cache. |

### SaryuGuard (`/sg`)
| Command | Permission | Description |
|---|---|---|
| `/sg help` | `saryuguard.help` | Displays interactive help menu. |
| `/sg status` | `saryuguard.status` | Shows operational status of all active modules. |
| `/sg reload` | `saryuguard.reload` | Reloads all configurations and modules. |
| `/sg version` | `saryuguard.version` | Displays version and commit information. |
| `/sg plugins [page]` | `saryuguard.plugins` | Lists installed plugins and their verification status. |
| `/sg plugins approve <plugin>`| `saryuguard.plugins.approve` | Approves a new jar hash to local baseline. |
| `/sg scan verify` | `saryuguard.scan` | Checks plugin jar hashes and server security posture. |
| `/sg scan malware` | `saryuguard.scan` | Launches external MCAntiMalware scan. |
| `/sg scan malware generate-script` | `saryuguard.scan` | Generates offline pre-startup scan script. |
| `/sg words list [page]` | `saryuguard.words` | Displays filtered word list with pagination. |
| `/sg words add <word>` | `saryuguard.words` | Adds a word to the chat filter. |
| `/sg words remove <word>` | `saryuguard.words` | Removes a word from the chat filter. |
| `/sg opguard add <player>` | `saryuguard.opguard` | Adds player UUID to authorized OP baseline. |
| `/sg opguard remove <player>` | `saryuguard.opguard` | Removes player UUID from authorized OP baseline. |
| `/sg opguard list` | `saryuguard.opguard` | Lists authorized OPs. |
| `/sg 2fa setup` | Player only | Generates TOTP secret and recovery codes. |
| `/sg 2fa reset <player>` | `saryuguard.2fa.admin` | Resets 2FA credentials for a locked player account. |
| `/sg audit [page]` | `saryuguard.audit` | Displays forensic audit records. |
| `/sg audit verify` | `saryuguard.audit` | Verifies cryptographic integrity of the audit hash chain. |
| `/sg connection` | `saryuguard.connection` | Shows connection guard status and compatibility matrix. |
| `/sg crypt rotate` | Console only | Rotates AES-256 database encryption key. |

*Note: All administrative permissions default to `op`.*

---

## 4. Building from Source

Requirements: JDK 21+ (Java 21 or Java 25).

```bash
# Clone the repository
git clone https://github.com/saryu/GuardPlugin-Project.git
cd GuardPlugin-Project

# Build all modules and run unit tests
./gradlew build --no-daemon

# Output JARs:
# - AntiChatManager/build/libs/AntiChatManager-<version>.jar
# - SaryuGuard/build/libs/SaryuGuard-<version>.jar
# - SaryuLibCore/build/libs/SaryuLibCore-<version>.jar
```

All builds are reproducible (`preserveFileTimestamps = false`, `reproducibleFileOrder = true`).
