# Security Policy: SaryuGuard & AntiChatManager Lite

Paper 1.21.x – 26.x · SaryuLibCore

---

## 1. Threat Model

### In Scope
The following threat vectors are actively guarded and mitigated by this ecosystem:
- **Rogue / Corrupt Plugins**: Plugins with modified hashes, unknown signatures, unverified origins, or altered jars at runtime.
- **Unauthorized OP Escalation**: Non-whitelisted players receiving operator privileges (de-opped immediately via OpGuard in `enforce` mode).
- **Staff Account Takeover**: Hijacking of staff credentials (mitigated via RFC 6238 TOTP 2FA with Argon2id recovery codes and LuckPerms context gating).
- **Inappropriate / Malicious Chat & Commands**: Chat bypass attempts using Unicode homoglyphs, zero-width characters, leet speak, censored trie evasion, and ReDoS attacks.
- **Connection Flooding & Ping Attacks**: High-frequency pre-login IP floods, server list ping spam, invalid username exploits, and multi-account bot waves.
- **Command Injection & Blacklist Evasion**: Command aliases, namespaced variations, trailing spaces, and delimiter tricks.
- **Audit Tampering**: Forensic records are cryptographically anchored using SHA-256 hash chains and periodic checkpoints.

### Out of Scope
The following threats cannot be mitigated by Minecraft plugins and require server/OS-level security:
- **Root / Host OS Compromise**: An adversary possessing root shell access to the host machine.
- **Direct Filesystem / JVM Access**: Direct manipulation of JVM memory or files bypassing Paper security boundaries.
- **Volumetric Network DDoS**: Layer 3/4 network saturation attacks exceeding physical network link capacity (requires upstream firewall/proxy protection).

---

## 2. Component Operational Statuses

All components in SaryuGuard report their true operational state:

| Status | Meaning |
|---|---|
| `ACTIVE` | Component is fully functional and running with primary dependencies healthy. |
| `DEGRADED` | Component is operating in fallback mode (e.g., database query failed but last valid in-memory cache remains active). Throttled administrator alerts are emitted. |
| `DISABLED` | Component was explicitly turned off via configuration or administrator command. |
| `ERROR` | Component encountered a critical failure and halted execution. Malfunctioning components are **never** reported as active or clean. |

---

## 3. Deny Protections vs. Alert Detections

- **DENY Protections**: Immediate, blocking intervention:
  - Command Blacklist (`AntiChatManager Lite`): Intercepts command preprocessing at `LOWEST` priority, cancels execution, and notifies sender.
  - OpGuard (`enforce` mode): Safely de-ops unauthorized players on the server main thread.
  - Staff 2FA: Restricts unauthenticated staff actions (movement, commands, chat, inventory, and LuckPerms privileges).
  - Connection Guard: Disallows invalid usernames, rejoins under cooldown, and IP connection floods before login completes.
- **ALERT-Only Detections**: Passive observation and logging:
  - OpGuard (`warn` mode): Alerts online staff and console when an unauthorized OP is present.
  - Jar Verify (`UNVERIFIED` / `MISMATCH`): Highlights unknown plugin jars in `/sg plugins` and startup logs for admin review (`/sg plugins approve <plugin>`).
  - Audit Forensics: Records security events to tamper-evident hash-chained logs without obstructing gameplay.

---

## 4. Permission Design & Defaults

- **Strict Defaults**: All administrative permissions default strictly to `op`. No administrative permission ever defaults to `true`.
- **Bypass Permissions**: Bypass permissions (`antichatmanager.bypass`, `saryuguard.words.bypass`) default to `false`.

---

## 5. Reporting a Security Vulnerability

If you discover a security vulnerability in SaryuLibCore, SaryuGuard, or AntiChatManager Lite:
1. Please **do NOT** open a public issue on GitHub.
2. Send a detailed report describing the vulnerability, proof of concept, and affected versions to the repository maintainers.
3. You will receive an initial response within 48 hours. Valid vulnerabilities will receive a security advisory and patched release before disclosure.
