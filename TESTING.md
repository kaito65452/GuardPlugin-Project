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

## 13. Verified on Paper 26.2 build 134

Automated console runs confirmed:

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
