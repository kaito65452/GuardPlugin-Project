# Manual Testing Guide — AntiChatManager

Prerequisites: PaperMC 1.21+, SaryuLibCore.jar + AntiChatManager.jar in `plugins/`.
Start the server once, then edit `plugins/AntiChatManager/config.yml` as needed and restart.

## 1. Baseline: regular player vs OP vs bypass

1. Join as a regular player (no OP). Try `/op` → should be blocked, alert broadcast.
2. Join as OP. Try `/op` → allowed if `blacklist.bypass.allow-op: true`.
3. Grant `saryuantichatmanager.bypass` to a non-OP player. Try `/op` → allowed.

## 2. Aliases, namespaces, nested commands

1. `/minecraft:op` → blocked (namespace stripped).
2. `/bukkit:pl` → blocked if `pl` is blacklisted.
3. `/execute run op` → blocked (nested `/execute ... run` scanned).
4. `/return run gamemode creative` → blocked if `gamemode` blacklisted.
5. `/pl` (alias for `/plugins`) → blocked if alias is blacklisted.
6. Type `/op  ` (trailing spaces) / `/OP` (uppercase) / `/o p` (zero-width) → blocked (sanitized).

## 3. Unicode / control characters / packet spam

1. Send `/op\u200B` (zero-width space) → blocked.
2. Send very long command (> 256 chars) → rejected by `security.max-command-length`.
3. Rapid-fire tab-complete → rate-limited per `security.rate-limit`.
4. Open a TCP connection to the server port and close it before login (or connect
   with an incompatible client) → `latest.log` must **not** show a Netty
   `NullPointerException` / "PacketEvents caught an unhandled exception".

## 4. Auto-scan (plugin + LuckPerms)

1. Install a new plugin with admin commands (e.g., a utility plugin).
2. Wait for `/acm scan` or the periodic interval → new admin commands auto-blacklisted.
3. Check `/acm blacklist list PLUGIN_SCAN` → shows source = PLUGIN_SCAN.
4. Filter by plugin: `/acm blacklist list <PluginName> 2`.
5. With LuckPerms installed: create a staff group (`/lp creategroup admin`,
   `/lp group admin setweight 100`), give it `minecraft.command.me`, then
   `/acm scan` → `me` is added with source `LUCKPERMS_SCAN` (requires
   `auto-blacklist.luckperms.staff-min-weight` ≤ group weight).
6. `/acm scan report` → last scan summary (or "no new commands" when fully caught up).
7. `/acm scan sources` → how many entries each plugin/LuckPerms contributed.
8. `/acm blacklist check <command>` → shows plugin name, permission, detection method, time.
9. Disable vanilla scanning with `auto-blacklist.vanilla-enabled: false` and verify no
   `Minecraft`-sourced entries are added on a fresh database.

## 5. blacklist check shows source

1. `/acm blacklist check op` → displays:
   - Command, match mode, source (MANUAL/PLUGIN_SCAN/LUCKPERMS_SCAN/IMPORT)
   - Plugin name, permission, reason, added by, added at

## 6. Language switching

1. `/acm language` → numbered list with the active language and a set hint.
2. `/acm language set id_ID` and `/acm language id_ID` → Indonesian, confirmation is
   sent in the new language; `language: id_ID` is persisted in `config.yml`.
3. `/acm language set 1` / `set 2` → switch by list number.
4. `/acm language set ENG_us` and `/acm language ID` → case-insensitive and aliases work.
5. `/acm language zz_ZZ` → unknown-language error plus the available language list.
6. `/acm language reload` or `/acm reload` → re-read edited language files.
7. Missing keys in a non-default language fall back to English automatically. Delete a
   key from the on-disk `id_ID.yml`, reload, and verify the English value is used.
8. `/acm help` under `id_ID` shows translated command descriptions in the same order.
9. With `language-per-player: true`, a client using `id_id` gets Indonesian.
10. Unknown subcommands show a prefix, parsed colours, and a "Did you mean ...?" line;
    `/acm set language id_ID` suggests `/acm language set id_ID`.

## 7. Alert templates

1. `/acm template list` → shows boxed, minimal, banner, modern, compact and the active one.
2. `/acm template preview boxed` → sample values (`Steve`, `gamemode`, current time).
3. `/acm template set banner` → change template (persisted to config).
4. Edit `languages/eng_US.yml` under `templates:` → `/acm reload` re-reads it.
5. Trigger a blacklist command → alert appears in chosen style.
6. Try `broadcast-target: staff-only` → only staff (`saryuantichatmanager.alerts`) see it.

## 8. Database types

Set `database.type: SQLITE` (default), `H2`, or `MYSQL` in config, restart, verify
`/acm info` shows the correct type and "available=true". `database.retention-days`
(default 30) purges old violation/scan/punishment rows on startup. SQLite writes are
drained on shutdown so entries added right before `/stop` are not lost.

Already verified:

| Check | Result |
|-------|--------|
| SQLite schema migration to v3 (`acm_blacklist.allowed_groups`) | ✅ |
| Config v2 → v3 adds `plugin-identity.*`, `audit.*`, `conflict-detection.*` | ✅ |
| Config v3 → v4 adds `commands`, `plugin-manual-scan`, `integration`, audit risk nodes | ✅ |
| H2 starts (JDBC driver explicitly registered), schema v3/v4 | ✅ |
| H2 persists 79 blacklist entries across restart, 0 duplicates | ✅ |
| MySQL | ⚠️ not available in the current test environment |

## 8a. V7 identity, conflicts, audit, security

1. `/acm scan identify` → starts background JAR hash identification. The reply says
   results are available via `/acm plugins` (lookup is async).
2. `/acm plugins` → each third-party plugin shows `VERIFIED`/`UNVERIFIED`, confidence,
   source, and project metadata when Modrinth recognizes the hash.
3. `/acm conflicts` → lists LuckPerms groups/commands that can bypass ACM; test
   `/acm conflicts ignore <cmd> [group]` and `/acm conflicts allow <cmd> <group>`.
4. `/acm audit` → permission findings from LuckPerms; `/acm audit info <id>`,
   `/acm audit ack <id>`, `/acm audit ignore group|user|node <name>`.
5. `/acm security` → read-only findings (offline mode, missing LuckPerms/PacketEvents,
   unsafe JAR permissions, server.properties checks, baseline hash changes).
6. Delete `plugins/AntiChatManager/security-baseline.yml`, restart, and verify a new
   baseline is written with flat file-name keys such as `3_0_2-dev-test_jar`.
7. With LuckPerms installed, change a group permission and confirm conflicts/audit are
   rescanned automatically after the 5-second debounce.
8. PlaceholderAPI: verify `%acm_violations%`, `%acm_blacklist_count%`, `%acm_bypass%`,
   `%acm_conflicts_open%`, `%acm_conflicts_critical%`, and `%acm_audit_open%`.

## 8b. Command rules and alert actions (config v4)

1. Start a fresh server (or back up and delete `config.yml`), verify `config.version: 4`
   and the default `commands: "lp info": mode: allow` rule.
2. `/acm subcmd add block worldguard reload` then `/acm blacklist check WG  reload`
   → matched by `worldguard reload`, action `block`.
3. `/acm subcmd add alert-only lp user * permission set`; as EXACT token test:
   `/acm blacklist check lp user Steve permission set` → action `alert-only`; the
   player receives an alert but the command still executes.
4. `/acm subcmd add allow test safe`; `/acm blacklist check test safe` → explicit allow;
   `/acm blacklist check test safe extra` still matches the allow prefix.
5. `/acm subcmd remove test safe`, `/acm subcmd list`, `/acm reload` → rules persist.
6. Verify `{SUBCOMMAND}` and `{FULLCOMMAND}` in alerts are sanitized (no `§`,
   zero-width, newlines) and truncated; `/lp info` remains allowed.
7. Check `/acm blacklist list` shows `[MODE/ACTION]` and `/acm blacklist info` has `action`.

## 8c. Sensitive plugin manual scan and WE/FAWE/WG

1. Install WorldEdit + WorldGuard (FAWE optionally, not together with WE) and add the
   plugin names under `plugin-manual-scan:` in `config.yml`.
2. `/acm scan WorldEdit` → command/alias/permission/listener counts. If it is in
   `plugin-manual-scan`, `/acm blacklist check //SET stone` → `set` from
   `manual-scan:WorldEdit`; `/acm scan <not-configured>` is preview-only.
3. `/acm scan list` → mapping source is `manual-scan:<plugin>` or `integration`.
4. `/acm integration status` → WE/FAWE/WG version, command counts, warnings, recommendations.
5. `/acm integration apply worldguard` → writes `wg reload`, `rg flag`, etc. to
   `commands:`; `/acm integration apply worldedit` → `set`, `schem load`, ...
   Rules already present are skipped; nothing is applied without this command.
6. `/acm blacklist check worldguard:wg reload` → blocked via `wg reload`.
7. `/acm blacklist check rg flag spawn pvp allow` → blocked via `rg flag`.
8. `/acm blacklist check lp info` → allowed (no match or explicit allow).
9. With PlugManX: `/plugman disable WorldEdit`, wait for the rescan → manual-scan rows
   for WorldEdit become disabled; `/acm scan list` shows 0 commands. Re-enable it →
   PluginEnableEvent rescans and the rows become enabled again.
10. Remove a plugin from `plugin-manual-scan`, `/acm reload`, wait for the scan → its
    manual-scan rows are disabled (not deleted); `/acm scan list` no longer lists it.
11. Missing plugin names warn once and retry when that plugin is enabled.
12. Read-only audit: set a dangerous WE/WG limit (`max-radius: -1`) and verify
    `/acm integration status` warns without writing the other plugin's config.
13. LuckPerms: give a non-staff group `worldedit.*` or `worldguard.region.bypass.*`,
    run `/acm audit`; expect HIGH/CRITICAL findings, while a configured staff group is
    marked `[staff-group]`. Confirm no `worldedit.*` command is executed by ACM.

## 12a. Build and test checks

```bash
./gradlew clean build     # includes JUnit 5 tests and scanForbiddenApis
./gradlew test            # focused unit tests
```

`scanForbiddenApis` fails the build on unsafe JVM APIs (reflection helper exceptions
such as the optional LuckPerms softdepend are explicitly allowed). Current automated
coverage is `StringSanitizerTest` and `DurationParserTest`; manual security cases for
YAML bombs, SSRF, and regex DoS are still on the list.


## 9. Export / import

1. `/acm export backup` → writes `plugins/AntiChatManager/backup.yml`.
2. `/acm import backup` → duplicates are skipped, new commands are imported.
3. `/acm blacklist purge` after uninstalling a plugin → stale auto entries removed.
4. `/acm blacklist list MANUAL` → verify imported/manual entries have source `IMPORT`/`MANUAL`.

## 10. Punishment ladder (warn → kick → tempban → permban)

Default config:
- 3 violations → WARN
- 5 violations → KICK
- 8 violations → BAN 1d
- 15 violations → BAN perm

1. Have a regular player use a blacklisted command 3 times → warning message.
2. 5 times → kicked.
3. Rejoin, 8 more → banned for 1 day.
4. Check `/acm violations <player>` → shows count.
5. `/acm violations reset <player>` → count cleared.
6. Paper 26.2+: trigger a tempban/permban → player is kicked, `banned-players.json`
   has the right future expiry (or no expiry for perm), and the console shows no
   `ClassCastException` from `CraftProfileBanList`.

## 11. Ban provider auto-detection

1. No ban plugin installed → `/acm info` shows `VANILLA`, bans use Paper ban list.
2. Install EssentialsX → provider switches to `ESSENTIALS`, uses `m:tempban`.
3. Install LiteBans → provider switches to `LITEBANS` (higher priority).
4. Uninstall all ban plugins → falls back to VANILLA, no crash.

## 12. SaryuLibCore used by a plugin example

Drop `ExamplePlugin.jar` (built from SaryuLibCore's `example/` module) into `plugins/`
alongside SaryuLibCore → `/example ping` works, proving library sharing.

## 13. Verified on Paper 26.2 build 134 / 1.21.8

Automated console runs confirmed (V7 additions were smoke-tested on Paper 1.21.8):

| Check | Result |
|-------|--------|
| Both plugins enable, `/acm` registered | ✅ |
| `plugins/AntiChatManager/{config.yml,languages/,data.db}` created | ✅ |
| Auto-scan (vanilla fallback) | ✅ 79 entries |
| Restart: DB load then scan (no duplicates) | ✅ 79 loaded, 0 added |
| Graceful shutdown persists queued writes | ✅ 79 rows |
| `/acm info`, `blacklist list/check/info/add/remove`, `history`, `violations` | ✅ |
| `/acm template preview` (sample substitution) | ✅ |
| `/acm language` list + `set <code\|number>` (hot reload, persisted) | ✅ |
| `/acm language id_ID` → Indonesian messages and help rows | ✅ |
| Unknown language lists available languages; unknown subcommand suggests reorder | ✅ |
| `/acm info` shows `3.0.2-dev+<commit> (dev, commit <hash>)` | ✅ |
| `/acm reload` (no DB reconnect with unchanged settings) | ✅ |
| LuckPerms 5.5.71 detection + staff-weight scan | ✅ source `LUCKPERMS_SCAN` |
| PacketEvents 2.14.0 hook (`PacketEvents`, active) | ✅ |
| PacketEvents absent → no-op layer, no errors | ✅ |
| V7 `/acm help`, `info`, `security`, `conflicts`, `audit`, `plugins`, `reload` | ✅ Paper 1.21.8 |
| Config v2 → v3/v4 migration adds new keys without overwriting values | ✅ |
| `/acm scan identify` + `/acm plugins` with a dummy plugin (`UNVERIFIED`) | ✅ |
| JAR baseline uses flat file-name keys; hash change warns once | ✅ |
| H2/SQLite schema v3 → v4 + persistence across restart | ✅ |
| WE 7.4.6 + WG 7.0.14 detection, 163 WE / 17 WG command labels | ✅ Paper 1.21.8 |
| `//SET stone` → `set` manual-scan; `WG  reload`, `worldguard:wg reload`, `rg flag` → block | ✅ matcher |
| `/lp info` allowed; `/acm integration apply worldguard` writes 7 rules | ✅ console |
| PlugManX disable/enable WorldEdit → 162 rows disabled/re-enabled | ✅ runtime |
| Read-only WE config warning (`max-radius: -1`) | ✅ |
| `./gradlew test` (JUnit 5) and `scanForbiddenApis` | ✅ |
| LuckPerms event-triggered rescan | ⚠️ needs LuckPerms installed |
| PlaceholderAPI placeholders | ⚠️ needs PlaceholderAPI installed |
| MySQL | ⚠️ no MySQL server available |
