# Laporan Implementasi Versi 7

**Status:** pekerjaan dihentikan sementara atas permintaan, perubahan sudah dibangun dan disiapkan untuk di-push ke branch `dev`. Build Gradle terakhir **lulus**.

- Branch: `dev`
- Base version: `3.0.2`
- Commit core sebelumnya: `768ad33 feat(core): add safe config secrets, permission snapshots, bounded DB queue, schema v2`
- Build: `./gradlew build --no-daemon -q` → **PASS**
- Target bytecode: Java 21
- Platform uji: Paper 1.21.8, Java runtime 25

> Catatan: laporan ini membedakan fitur yang sudah selesai, yang baru sebagian, dan yang belum diverifikasi. Tidak ada klaim bahwa seluruh spesifikasi V7 sudah selesai 100%.

---

## 1. Audit Sebelum dan Sesudah

Status: `SUDAH ADA` = sudah berfungsi sebelum perubahan, `SEBAGIAN` = baru sebagian, `BELUM` = belum ada.

| Fitur V7 | Sebelum | Sesudah | Bukti / file utama |
|---|---|---|---|
| Blacklist command inti, bypass/OP, normalisasi, alias, namespace, nested command | SUDAH ADA | SUDAH ADA | `BlacklistManager.java`, `CommandHandler.java`, `PacketInterceptor.java` |
| Pencocokan `exact` / `startswith` / `regex` / `wildcard` | SUDAH ADA | SUDAH ADA | `BlacklistManager.java` |
| Validasi regex anti-ReDoS dan batas panjang command | SEBAGIAN | SEBAGIAN | `BlacklistManager.java`, `ACMCommand.java`; validasi kuantifier dan batas input ditambahkan, tetapi belum ada pengujian serangan regex menyeluruh |
| Alert chat, template, hex/gradient/MiniMessage, escape pemain | SUDAH ADA | SUDAH ADA | `AlertManager.java`, file bahasa |
| Anti-amplifikasi alert (cooldown per pemain + batas global per menit) | BELUM | SUDAH ADA | `AlertManager.java`, config `security.alert-cooldown-ms`, `security.alert-per-minute` |
| Auto-scan command plugin dan LuckPerms | SUDAH ADA | SUDAH ADA | `AutoScanner.java`, `PermissionModule.java` |
| Skor keyakinan auto-blacklist | BELUM | SEBAGIAN | `PluginIdentityService.java`; skor identitas tersedia, tetapi belum menjadi bobot penuh seluruh sinyal seperti spesifikasi |
| Identifikasi JAR lewat hash Modrinth/Hangar + `acm_plugin_identity` | BELUM | SEBAGIAN | `PluginIdentityService.java`, `RepositoryClient`, `Storage.java`; Modrinth hash lookup ada, Hangar masih fallback `UNVERIFIED`, metadata proyek/kategori belum lengkap |
| `/acm scan identify`, `/acm plugins` | BELUM | SUDAH ADA | `ACMCommand.java`, `ACMSubcommands.java` |
| Deteksi konflik LuckPerms vs blacklist/bypass | BELUM | SEBAGIAN | `ConflictDetector.java`, `ACMSubcommands.java`; grup, severity, persistensi status, ignore, allowed-group tersedia; node user/context/temporary belum lengkap |
| Status konflik `OPEN/IGNORED/RESOLVED` di database | BELUM | SUDAH ADA | `Storage.java`, tabel `acm_conflicts` |
| Whitelist per grup per entri blacklist | BELUM | SUDAH ADA | `BlacklistManager.Entry.allowedGroups`, migrasi schema v3, `/acm blacklist whitelist <cmd> [group]` |
| Audit permission LuckPerms, bypass, wildcard, status temuan | BELUM | SEBAGIAN | `PermissionAudit.java`, `Storage.java`; audit grup/user dan status ada, context/expiry/inherited-origin belum lengkap |
| `/acm audit`, `ack`, `ignore`, info | BELUM | SUDAH ADA | `ACMSubcommands.java` |
| Notifikasi staf saat join dan pengingat konflik/audit | SEBAGIAN | SEBAGIAN | `PlayerListener.java`, `ConflictDetector.java`, `PermissionAudit.java`; throttling/interval belum sepenuhnya disamakan dengan spesifikasi |
| Keamanan level paket, PacketEvents, tab-complete, buku/sign/anvil/clickEvent | SEBAGIAN | SEBAGIAN | `PlayerListener.java`, `PacketInterceptor.java`; handler Bukkit ditambahkan, PacketEvents tidak tersedia di server uji |
| Batas panjang, karakter kontrol, `§`, zero-width | SEBAGIAN | SEBAGIAN | `AntiChatManager.maxCommandLength()`, `BlacklistManager`, `PlayerListener` |
| Pencegahan injeksi command pada hukuman | SEBAGIAN | SUDAH ADA | `PunishmentService.java`; nilai `{PLAYER}`, `{DURATION}`, `{REASON}` dibersihkan dari newline/kontrol |
| Anti injeksi log/webhook, SSRF, allowlist host HTTPS | BELUM | SEBAGIAN | `IntegrationModule.java`, `RepositoryClient`; webhook Discord dibatasi, JSON escaping diperbaiki, client HTTP aman ditambahkan |
| YAML aman, batas ukuran/alias/kedalaman | BELUM | SEBAGIAN | `ACMCommand.java`; guard import YAML ditambahkan, belum menjadi parser global untuk semua YAML |
| Path traversal import/export | BELUM | SUDAH ADA | `ACMCommand.safeDataFile`, validasi path relatif dan symlink |
| Database: prepared statement, async, queue berbatas, schema version | SUDAH ADA | SUDAH ADA | `DatabaseModule.java`, `Storage.java` |
| UPSERT lintas SQLite/MySQL/H2 | SEBAGIAN | SEBAGIAN | `Storage.java`; SQLite/MySQL/H2 dibedakan, counter H2 diperbaiki ke UPDATE-then-INSERT; MySQL/H2 belum diuji runtime |
| Migrasi schema v3 `allowed_groups` | BELUM | SUDAH ADA | `DatabaseModule.applySchemaV3` |
| Punishment bertahap, durasi, provider ban otomatis | SUDAH ADA | SUDAH ADA | `PunishmentTracker.java`, `PunishmentService.java` |
| API event, audit log, PlaceholderAPI | BELUM | SEBAGIAN | `api/`, `security/AuditLog.java`, `integration/ACMPlaceholderExpansion.java`; runtime PAPI belum diuji |
| Audit keamanan startup, hash JAR, baseline JAR, cek server.properties | BELUM | SEBAGIAN | `SecurityAudit.java`; cek RCON/query/secure-profile/offline-mode/baseline JAR ditambahkan, status OP/LuckPerms sebagian |
| `/acm security` dan hash JAR di `/acm info` | BELUM | SUDAH ADA | `ACMSubcommands.java`, `ACMCommand.java` |
| Bahasa `eng_US` default + `id_ID` + fallback | SUDAH ADA | SUDAH ADA | `LanguageManager.java`, file bahasa |
| Help `/acm` tetap kompatibel dan menampilkan command baru | SUDAH ADA | SUDAH ADA | `HelpSub`; command baru di-append otomatis jika file bahasa lama belum memuat baris baru |
| Hot reload seluruh service baru | SEBAGIAN | SUDAH ADA | `AntiChatManager.reloadAll()`, `ReloadSub` |
| CI supply-chain: pin action SHA, permission minimum, CodeQL/Semgrep/OSV/Gitleaks/Grype, SBOM, attestation | BELUM | BELUM | `.github/workflows/build.yml` belum diubah pada tahap ini |
| Tes otomatis V7 (path traversal, YAML bomb, regex, injeksi, SSRF, spam alert, DB penuh, GUI) | BELUM | BELUM | Belum ada folder `src/test` untuk skenario tersebut |
| GUI security SaryuLibCore | BELUM | BELUM | Belum diverifikasi/ditambahkan pada tahap ini |
| Version injection dev/stable | SUDAH ADA | SUDAH ADA | `build.gradle.kts`; dev menampilkan `3.0.2-dev+<commit>` |

---

## 2. File Baru

### AntiChatManager

| File | Tujuan |
|---|---|
| `api/AntiChatManagerAPI.java` | API publik untuk plugin lain |
| `api/BlockedCommandEvent.java` | Event custom yang bisa dibatalkan |
| `audit/PermissionAudit.java` | Audit permission/bypass LuckPerms, status temuan, notifikasi |
| `command/ACMSubcommands.java` | Subcommand baru `plugins`, `conflicts`, `audit`, `security` |
| `conflict/ConflictDetector.java` | Deteksi konflik grup LuckPerms vs blacklist/bypass |
| `identity/PluginIdentityService.java` | Hash JAR async dan lookup identitas Modrinth |
| `integration/ACMPlaceholderExpansion.java` | Placeholder `%acm_violations%`, `%acm_blacklist_count%` |
| `security/AuditLog.java` | Audit log untuk aksi admin |
| `security/SecurityAudit.java` | Audit keamanan server, hash JAR, baseline integritas JAR |

### SaryuLibCore
`RepositoryClient` dan migrasi schema v2 sudah masuk pada commit core `768ad33`. Pada pekerjaan ini tidak ada file baru di SaryuLibCore; hanya perubahan kecil pada file yang ada.

---

## 3. File Lama yang Diubah dan Alasannya

| File | Alasan perubahan |
|---|---|
| `AntiChatManager/AntiChatManager.java` | Wiring service baru, `reloadAll()`, getter API/audit/security |
| `AntiChatManager/alert/AlertManager.java` | Anti-amplifikasi alert, pembacaan config baru, validasi template |
| `AntiChatManager/blacklist/BlacklistManager.java` | Validasi regex/command, allowed-group per entri, akses `findEntry` |
| `AntiChatManager/command/ACMCommand.java` | Registrasi subcommand baru, help dinamis, whitelist per grup, load/save aman, hash JAR di info |
| `AntiChatManager/command/CommandHandler.java` | Firing `BlockedCommandEvent`, audit log |
| `AntiChatManager/listener/PlayerListener.java` | Handler `ServerCommandEvent`, buku, sign, clickEvent, notifikasi join |
| `AntiChatManager/packet/PacketInterceptor.java` | Integrasi allowed-group pada filter paket |
| `AntiChatManager/scan/AutoScanner.java` | Rescan konflik/audit setelah auto-scan |
| `AntiChatManager/storage/Storage.java` | Persistensi identity/conflict/audit, UPSERT lintas DB, ID temuan, allowed-groups, counter H2 |
| `AntiChatManager/config.yml` | Config v2 dan key baru dengan default aman; nilai user tidak ditimpa |
| `languages/eng_US.yml`, `id_ID.yml` | Key baru: plugin, konflik, audit, keamanan, help, allowed-group |
| `SaryuLibCore/database/DatabaseModule.java` | Migrasi schema v3 dan validasi tipe database yang sudah ada |
| `SaryuLibCore/integration/IntegrationModule.java` | Webhook hanya HTTPS Discord, JSON escaping, `allowed_mentions` kosong |
| `SaryuLibCore/permission/PermissionModule.java` | Snapshot grup dan daftar grup untuk tab-complete/konflik |
| `SaryuLibCore/punishment/PunishmentService.java` | Sanitasi nilai command console pada hukuman |
| `build.gradle.kts`, `AntiChatManager/build.gradle.kts` | Repository dan dependency compileOnly PlaceholderAPI |

---

## 4. Kompatibilitas ke Belakang

- `/acm` dan `/acm help` tetap memakai root command dan format baris lama.
- Semua key config lama dipertahankan. Key baru hanya ditambahkan; config pengguna tidak ditimpa.
- Semua key bahasa lama dipertahankan; key baru ditambahkan. Fallback ke `eng_US` tetap berlaku.
- Tabel database lama tidak dihapus. Perubahan hanya `CREATE TABLE IF NOT EXISTS` dan `ALTER TABLE ... ADD COLUMN allowed_groups`.
- Nama command, permission, dan format pesan lama tidak diubah.
- Event handler baru bersifat aditif; filter lama tetap berjalan.
- Perubahan perilaku yang disengaja hanya perbaikan bug/keamanan:
  1. Webhook sekarang menolak URL non-HTTPS/non-Discord.
  2. Nilai `{REASON}` pada command hukuman dibersihkan dari newline/kontrol.
  3. Counter pelanggaran H2 memakai UPDATE-then-INSERT agar tidak mereset hitungan.
  4. Hash JAR dan baseline keamanan sekarang dihitung/diperiksa saat startup.

---

## 5. Verifikasi yang Sudah Dilakukan

- `./gradlew build --no-daemon -q` lulus setelah seluruh perubahan terakhir.
- Server Paper 1.21.8 dijalankan dengan JAR hasil build.
- Perintah yang berhasil dieksekusi tanpa exception:
  - `/acm help`
  - `/acm info`
  - `/acm security`
  - `/acm conflicts`
  - `/acm audit`
  - `/acm plugins`
- Migrasi SQLite sampai schema v2 berhasil pada uji sebelumnya.
- `/acm scan identify` dan `/acm plugins` sempat menampilkan data kosong sebelum perbaikan reflection file plugin; perbaikan sudah dibangun, tetapi belum diuji ulang di server.
- Schema v3 `allowed_groups` sudah dikompilasi, tetapi belum diuji runtime setelah build terakhir.

---

## 6. Yang Belum Diverifikasi / Perlu Dilanjutkan di RDP

1. **Uji ulang runtime setelah build terakhir** untuk `/acm help`, `/acm reload`, `/acm scan identify`, `/acm plugins`, `/acm conflicts`, `/acm audit`, `/acm security`.
2. **LuckPerms**: uji konflik grup default/bypass, `*`, inheritance, `ack`, `ignore`, `RESOLVED`, notifikasi join. Server uji saat ini tidak memasang LuckPerms.
3. **MySQL dan H2**: uji UPSERT, schema v3, counter H2, dan persistensi status.
4. **PlaceholderAPI**: uji ekspansi `%acm_violations%` dan `%acm_blacklist_count%` dengan PlaceholderAPI terpasang.
5. **PacketEvents/Floodgate**: uji pemblokiran sebelum Bukkit dan identitas Bedrock.
6. **Baseline JAR**: verifikasi `security-baseline.yml`, peringatan JAR berubah, dan hash di `/acm info`.
7. **CI supply-chain**: pin semua GitHub Action ke commit SHA, pisahkan job rilis dengan permission `contents: write`, tambahkan CodeQL/Semgrep/OSV/Gitleaks/Grype, SBOM, dan attestation.
8. **Tes otomatis**: path traversal, YAML bomb, regex jahat, injeksi log/command, SSRF, spam alert, DB queue penuh, klik GUI tidak valid, config rusak.
9. **`SaryuLibCore` version display**: runtime masih melaporkan `3.0.2` saat JAR dev dipakai; perlu dicek ulang proses `processResources`/caching build-info.
10. **File bahasa lama di server uji** belum memuat baris help baru; fallback dinamis sudah ditambahkan di kode, tetapi perlu restart untuk membuktikan tampilan help terbaru.

---

## 7. Perbedaan Spesifikasi vs Kode Lama yang Ditemukan

Sesuai aturan “kode lama menang”, perbedaan berikut tidak diubah diam-diam:

| Spesifikasi | Kode yang ada | Tindakan |
|---|---|---|
| Template alert di `alert.templates.<nama>` | Template berada di root `templates:` pada file bahasa | Dipertahankan; fitur baru memakai path yang sudah ada |
| `language.per-player` | Key lama `language-per-player` | Dipertahankan |
| `auto-blacklist.modrinth.*` | Config baru memakai `plugin-identity.modrinth.*` | Perlu disatukan atau dibuat fallback pada tahap lanjutan |
| `auto-blacklist.min-confidence` | Config baru memakai `plugin-identity.min-confidence` | Perlu disatukan atau dibuat fallback |
| Hitungan pelanggaran H2 | Kode lama memakai `ON CONFLICT` yang tidak cocok untuk H2 | Diperbaiki menjadi UPDATE-then-INSERT |
| Audit/konflik detail (context, expiry, inherited origin) | Snapshot LuckPerms belum mengekspos semua metadata | Ditandai `SEBAGIAN`, perlu dilanjutkan |

---

## 8. Perintah Lanjutan

```bash
cd /home/saryu/project/AntiChatManager
./gradlew build --no-daemon
git status
git push origin dev
```

Untuk server uji, JAR dev berada di:

```text
AntiChatManager/build/libs/AntiChatManager-3.0.2-dev+<commit>.jar
SaryuLibCore/build/libs/SaryuLibCore-3.0.2-dev+<commit>.jar
```
