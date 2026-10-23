# AntiChatManager + SaryuLibCore

Advanced chat and command management for PaperMC (1.21 – 26.3), built on top of the
shared **SaryuLibCore** library. Features command blacklisting with alias/namespace
resolution and nested-command scanning, customizable alert chat templates, auto-scanning
of installed plugins and LuckPerms groups, progressive punishment escalation, and
multi-database support.

## Repository structure

```
AntiChatManager/                    # Root Gradle project
├── settings.gradle.kts             # Includes SaryuLibCore + AntiChatManager
├── build.gradle.kts                # Shared repos + pluginManagement
├── gradle/libs.versions.toml       # Version catalog
├── gradle.properties               # Gradle settings
├── gradlew / gradlew.bat           # Gradle wrapper
├── SaryuLibCore/                   # Shared library (id.saryu.libcore)
│   ├── build.gradle.kts            # Fat-jar build, bundles HikariCP + JDBC drivers
│   ├── src/main/java/id/saryu/libcore/
│   │   ├── SaryuLib.java           # Core plugin + service registry
│   │   ├── config/                 # Versioned YAML config + migration
│   │   ├── command/                # Lightweight subcommand framework
│   │   ├── database/               # HikariCP MySQL/H2/SQLite + async executor
│   │   ├── integration/            # PlaceholderAPI, Floodgate, Discord webhook
│   │   ├── language/               # i18n (eng_US default, id_ID), per-player locale
│   │   ├── message/                # MiniMessage + legacy hex, gradient, escaping
│   │   ├── packet/                 # PacketEvents abstraction (reflection-based)
│   │   ├── permission/             # Plugin scan, LuckPerms weight/track helpers
│   │   ├── punishment/             # Ban provider auto-detect, duration parser
│   │   ├── scheduler/              # Paper/Folia sync/async/region tasks
│   │   ├── service/                # ServiceRegistry (SaryuLib.getService)
│   │   ├── time/                   # WIB/WITA/WIT timestamps
│   │   └── util/                   # TtlCache, StringSanitizer
│   │   └── example/                # ExamplePlugin template (< 100 lines)
│   ├── src/main/resources/
│   │   ├── plugin.yml
│   │   ├── config.yml
│   │   └── languages/ (eng_US.yml, id_ID.yml)
│   └── build/libs/SaryuLibCore-3.0.2-dev+<commit>.jar
├── AntiChatManager/                # Plugin (id.saryu.antichatmanager)
│   ├── build.gradle.kts            # Depends on SaryuLibCore (compileOnly Paper/Adventure)
│   ├── src/main/java/id/saryu/antichatmanager/
│   │   ├── AntiChatManager.java    # Main plugin class
│   │   ├── alert/AlertManager.java # Templates, broadcast targets, sounds
│   │   ├── blacklist/BlacklistManager.java # Match engine, namespace/alias/nested scan
│   │   ├── command/                # ACMCommand + CommandHandler
│   │   ├── listener/PlayerListener.java
│   │   ├── packet/PacketInterceptor.java
│   │   ├── punishment/PunishmentTracker.java
│   │   ├── scan/AutoScanner.java   # Plugin + LuckPerms auto-scan
│   │   └── storage/Storage.java    # DAO layer (async)
│   ├── src/main/resources/
│   │   ├── plugin.yml              # depend: SaryuLibCore
│   │   ├── config.yml              # Full commented config
│   │   └── languages/ (eng_US.yml, id_ID.yml)
│   └── build/libs/AntiChatManager-3.0.2-dev+<commit>.jar
└── README.md                       # This file
```

## Requirements

- **Minecraft**: PaperMC 1.21 – 26.3 (Purpur compatible)
- **Java**: 21 runtime (compiled to Java 21 bytecode, runs on Java 25)
- **Dependencies**: SaryuLibCore must be installed in the server's `plugins/` folder
- **Optional**: LuckPerms, PlaceholderAPI, Floodgate/Geyser, PacketEvents, LiteBans,
  LibertyBans, AdvancedBan, BanManager, EssentialsX

## Build

```bash
# Development build: 3.0.2-dev+<short-commit>, channel "dev"
./gradlew build

# Stable build: 3.0.2, channel "stable"
./gradlew build -Prelease
```

This produces:
- `SaryuLibCore/build/libs/SaryuLibCore-<version>.jar` (fat jar with bundled DB drivers)
- `AntiChatManager/build/libs/AntiChatManager-<version>.jar`

The version and channel are derived from `build.gradle.kts` and the current Git commit,
then injected into both `plugin.yml` files and `build-info.properties`. `/acm info` and
the startup log display the full build string, for example
`3.0.2-dev+abc1234 (dev, commit abc1234)`.

Install both JARs in your server's `plugins/` folder. Start the server once to generate
configs, then restart.

## Verified runtime

End-to-end tested on **Paper 26.2 build 134** (Java 25 runtime, Java 21 bytecode) with:

- **LuckPerms 5.5.71** — detected, staff groups scanned by weight, permissions added
  with source `LUCKPERMS_SCAN`
- **PacketEvents 2.14.0** — hooked; `/acm info` reports `Packet layer: PacketEvents (active=true)`
- **No integrations** — clean no-op packet layer, no errors

On first start the plugin creates `plugins/AntiChatManager/config.yml`,
`languages/eng_US.yml`, `languages/id_ID.yml`, and `data.db`, then auto-blacklists
79 admin commands on a vanilla Paper server (visible via `/acm blacklist list`).

## Configuration

Edit `plugins/AntiChatManager/config.yml` (documented inline). Key sections:

- `language: eng_US` — default language (fallback to English)
- `language-per-player: true` — use each client's locale when a matching file exists
- `timezone: WIB` — `WIB`, `WITA`, `WIT`, `UTC` for `{TIME}`
- `blacklist.enabled`, `blacklist.bypass.allow-op`, `blacklist.max-command-length`
- Alert templates live in `plugins/AntiChatManager/languages/<code>.yml` under
  `templates:` (`boxed`, `minimal`, `banner`, `modern`, `compact`); an optional
  `templates:` section in `config.yml` overrides them.
- `alert.template`, `alert.broadcast-target` (`global`/`staff-only`/`player-only`),
  `alert.sound`, `alert.server-name`
- `auto-blacklist.enabled`, `auto-blacklist.vanilla-enabled` (admin vanilla commands)
- `auto-blacklist.luckperms.staff-min-weight` — groups at/above this weight are staff
- `punishment.steps` — progressive warn/kick/ban ladder
- `ban.provider` — `AUTO`, `LITEBANS`, `VANILLA`, `CUSTOM`, etc.
- `database.type` — `SQLITE`, `H2`, `MYSQL`; `database.retention-days` purges old rows
- `security.max-command-length`, `security.rate-limit.*`, control-character filters
- `security.*`, `webhook.*` — SSRF allowlist, alert cooldown, Discord event hooks
- `plugin-identity.*` — Modrinth hash lookup, cache TTL, minimum confidence
- `conflict-detection.*` — LuckPerms command conflict review, ignore/allow lists
- `audit.*` — permission audit, ignore lists, notification interval
- `scan.*` — auto-scan interval and enable/disable switches

Config files use a versioned schema (`config.version: 3`). On upgrade, SaryuLibCore
adds new keys without overwriting existing values; old on-disk language files also
receive missing help rows in memory, so `/acm help` stays complete.

### API and placeholders

`AntiChatManager` exposes `CommandBlacklistedEvent` (cancellable; suppresses alerts and
punishments while the command remains blocked) and the legacy `BlockedCommandEvent`.
PlaceholderAPI placeholders include `%acm_version%`, `%acm_language%`,
`%acm_violations%`, `%acm_blacklist_count%`, `%acm_bypass%`, `%acm_conflicts_open%`,
`%acm_conflicts_critical%`, and `%acm_audit_open%`.

## Commands & Permissions

All commands under `/acm` (alias `/antichatmanager`):

| Command | Permission | Description |
|---------|------------|-------------|
| `/acm help` | `saryuantichatmanager.admin` | List all subcommands |
| `/acm reload` | `saryuantichatmanager.admin` | Reload config, language, blacklist engine |
| `/acm info` | `saryuantichatmanager.admin` | Status: DB, packet layer, LuckPerms, language, last scan |
| `/acm scan [report\|sources\|identify]` | `saryuantichatmanager.admin` | Run auto-scan, inspect the last result, or re-identify plugin JARs |
| `/acm plugins` | `saryuantichatmanager.admin` | Show plugin identity / Modrinth hash verification results |
| `/acm conflicts [page\|ignore\|allow]` | `saryuantichatmanager.admin` | Review LuckPerms/blacklist command conflicts |
| `/acm audit [page\|info\|ack\|ignore]` | `saryuantichatmanager.admin` | Review and manage permission-audit findings |
| `/acm security` | `saryuantichatmanager.admin` | Run read-only security checks and show recommendations |
| `/acm blacklist list [source\|plugin] [page]` | `saryuantichatmanager.admin` | List entries (filter by source or plugin) |
| `/acm blacklist check <cmd>` | `saryuantichatmanager.admin` | Check a command + show source/reason |
| `/acm blacklist info <id\|cmd>` | `saryuantichatmanager.admin` | Show full entry details |
| `/acm blacklist add <cmd> [mode] [reason]` | `saryuantichatmanager.admin` | Add a manual entry |
| `/acm blacklist remove <id\|cmd>` | `saryuantichatmanager.admin` | Remove entry/entries |
| `/acm blacklist whitelist <cmd> [group]` | `saryuantichatmanager.admin` | Remove auto entries and keep the command clean (optionally allow a group) |
| `/acm blacklist purge` | `saryuantichatmanager.admin` | Drop auto entries for commands that no longer exist |
| `/acm history <player> [page]` | `saryuantichatmanager.admin` | Violation history |
| `/acm violations [reset] <player>` | `saryuantichatmanager.admin` | Show or reset violation counts |
| `/acm template list\|preview\|set <name>` | `saryuantichatmanager.admin` | Manage alert templates |
| `/acm language [list\|reload\|set <code\|number>]` | `saryuantichatmanager.admin` | Show, change (code or list number) or reload languages |
| `/acm export [file]` | `saryuantichatmanager.admin` | Export blacklist to YAML |
| `/acm import <file>` | `saryuantichatmanager.admin` | Import blacklist from YAML |

Permissions:
- `saryuantichatmanager.admin` — all `/acm` commands (default: op)
- `saryuantichatmanager.bypass` — use blacklisted commands (default: false)
- `saryuantichatmanager.alerts` — receive staff alerts (default: op)

## SaryuLibCore for new plugins

SaryuLibCore is a shared library. To use it in a new plugin:

1. Add `depend: [SaryuLibCore]` to your `plugin.yml`.
2. Add `implementation(project(":SaryuLibCore"))` to your `build.gradle.kts`.
3. In `onEnable()`, get `SaryuLib.getInstanceSafe()` and use its modules:

```java
SaryuLib lib = SaryuLib.getInstanceSafe();
ConfigModule config = lib.getConfigModule();
LanguageManager lang = lib.getLanguageManager();
DatabaseModule db = lib.getDatabaseModule();
SchedulerModule sched = lib.getSchedulerModule();
```

See `SaryuLibCore/src/main/java/id/saryu/libcore/example/ExamplePlugin.java` for a
minimal working example (< 100 lines).

## Manual Testing Guide

See `TESTING.md` for a step-by-step manual verification plan covering:
- Regular player vs OP vs bypass
- Aliases, namespaces, `/execute run`, unicode, packet spam
- LuckPerms scan (change permission → automatic rescan)
- `blacklist check` showing source
- Language switching, all 5 alert templates
- SQLite / H2 / MySQL
- Punishment ladder: warn → kick → tempban → permban
- Ban provider auto-detection (EssentialsX, LiteBans, none)
# GuardPlugin-Project
