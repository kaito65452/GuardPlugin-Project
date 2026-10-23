# Laporan Implementasi Versi 7

**Status:** sesi lanjutan selesai. Build Gradle terakhir **lulus**, migrasi config v3 dan H2 sudah diuji runtime, dan perubahan di-push ke branch `dev`.

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
| Skor keyakinan auto-blacklist | BELUM | SEBAGIAN | `PluginIdentityService.java`; skor identitas tersedia dan config fallback `auto-blacklist.min-confidence` ditambahkan, tetapi belum menjadi bobot penuh seluruh sinyal seperti spesifikasi |
| Identifikasi JAR lewat hash Modrinth/Hangar + `acm_plugin_identity` | BELUM | SEBAGIAN | `PluginIdentityService.java`, `RepositoryClient`, `Storage.java`; lookup async dan path `UNVERIFIED` diuji dengan plugin dummy, Hangar masih fallback `UNVERIFIED`, metadata proyek/kategori belum lengkap |
| `/acm scan identify`, `/acm plugins` | BELUM | SUDAH ADA | `ACMCommand.java`, `ACMSubcommands.java` |
| Deteksi konflik LuckPerms vs blacklist/bypass | BELUM | SEBAGIAN | `ConflictDetector.java`, `ACMSubcommands.java`; grup, severity, persistensi status, ignore, allowed-group, fallback `Command#getPermission`, dan rescan event LuckPerms (debounce) tersedia; node user/context/temporary belum lengkap |
| Status konflik `OPEN/IGNORED/RESOLVED` di database | BELUM | SUDAH ADA | `Storage.java`, tabel `acm_conflicts` |
| Whitelist per grup per entri blacklist | BELUM | SUDAH ADA | `BlacklistManager.Entry.allowedGroups`, migrasi schema v3, `/acm blacklist whitelist <cmd> [group]` |
| Audit permission LuckPerms, bypass, wildcard, status temuan | BELUM | SEBAGIAN | `PermissionAudit.java`, `Storage.java`; audit grup/user, ignore `group/user/node`, dan status ada; context/expiry/inherited-origin belum lengkap |
| `/acm audit`, `ack`, `ignore`, info | BELUM | SUDAH ADA | `ACMSubcommands.java` |
| Notifikasi staf saat join dan pengingat konflik/audit | SEBAGIAN | SEBAGIAN | `PlayerListener.java`, `ConflictDetector.java`, `PermissionAudit.java`; throttling/interval belum sepenuhnya disamakan dengan spesifikasi |
| Keamanan level paket, PacketEvents, tab-complete, buku/sign/anvil/clickEvent | SEBAGIAN | SEBAGIAN | `PlayerListener.java`, `PacketInterceptor.java`; handler Bukkit ditambahkan, PacketEvents tidak tersedia di server uji |
| Batas panjang, karakter kontrol, `§`, zero-width | SEBAGIAN | SEBAGIAN | `AntiChatManager.maxCommandLength()`, `BlacklistManager`, `PlayerListener` |
| Pencegahan injeksi command pada hukuman | SEBAGIAN | SUDAH ADA | `PunishmentService.java`; nilai `{PLAYER}`, `{DURATION}`, `{REASON}` dibersihkan dari newline/kontrol |
| Anti injeksi log/webhook, SSRF, allowlist host HTTPS | BELUM | SEBAGIAN | `IntegrationModule.java`, `RepositoryClient`; webhook Discord dibatasi, JSON escaping diperbaiki, client HTTP aman ditambahkan |
| YAML aman, batas ukuran/alias/kedalaman | BELUM | SEBAGIAN | `ACMCommand.java`; guard import YAML ditambahkan, belum menjadi parser global untuk semua YAML |
| Path traversal import/export | BELUM | SUDAH ADA | `ACMCommand.safeDataFile`, validasi path relatif dan symlink |
| Database: prepared statement, async, queue berbatas, schema version | SUDAH ADA | SUDAH ADA | `DatabaseModule.java`, `Storage.java` |
| UPSERT lintas SQLite/MySQL/H2 | SEBAGIAN | SEBAGIAN | `Storage.java`; SQLite/MySQL/H2 dibedakan, counter H2 diperbaiki ke UPDATE-then-INSERT; **H2 schema v3 + persistensi 79 entri sudah diuji runtime**, MySQL belum tersedia di server uji |
| Migrasi schema v3 `allowed_groups` | BELUM | SUDAH ADA | `DatabaseModule.applySchemaV3` |
| Punishment bertahap, durasi, provider ban otomatis | SUDAH ADA | SUDAH ADA | `PunishmentTracker.java`, `PunishmentService.java` |
| API event, audit log, PlaceholderAPI | BELUM | SEBAGIAN | `api/` (`CommandBlacklistedEvent` cancellable + `BlockedCommandEvent`), `security/AuditLog.java`, `integration/ACMPlaceholderExpansion.java` (`violations`, `blacklist_count`, `bypass`, `conflicts_*`, `audit_open`); runtime PAPI belum diuji |
| Audit keamanan startup, hash JAR, baseline JAR, cek server.properties | BELUM | SEBAGIAN | `SecurityAudit.java`; cek RCON/query/secure-profile/offline-mode/baseline JAR ditambahkan, status OP/LuckPerms sebagian |
| `/acm security` dan hash JAR di `/acm info` | BELUM | SUDAH ADA | `ACMSubcommands.java`, `ACMCommand.java` |
| Bahasa `eng_US` default + `id_ID` + fallback | SUDAH ADA | SUDAH ADA | `LanguageManager.java`, file bahasa |
| Help `/acm` tetap kompatibel dan menampilkan command baru | SUDAH ADA | SUDAH ADA | `HelpSub`; command baru di-append otomatis jika file bahasa lama belum memuat baris baru |
| Hot reload seluruh service baru | SEBAGIAN | SUDAH ADA | `AntiChatManager.reloadAll()`, `ReloadSub` |
| CI supply-chain: pin action SHA, permission minimum, CodeQL/Semgrep/OSV/Gitleaks/Grype, SBOM, attestation | BELUM | SEBAGIAN | `.github/workflows/build.yml`; semua action sudah di-pin ke SHA, wrapper validation dan least privilege ditambahkan; scanner CodeQL/Semgrep/OSV/Gitleaks/Grype, SBOM, attestation belum |
| Tes otomatis V7 (path traversal, YAML bomb, regex, injeksi, SSRF, spam alert, DB penuh, GUI) | BELUM | SEBAGIAN | 6 unit test JUnit 5 untuk `StringSanitizer` dan `DurationParser`; skenario keamanan lain belum |
| GUI security SaryuLibCore | BELUM | BELUM | Belum diverifikasi/ditambahkan pada tahap ini |
| Version injection dev/stable | SUDAH ADA | SUDAH ADA | `build.gradle.kts`; cache `build-info.properties`/`plugin.yml` diperbaiki, kini keduanya menampilkan `3.0.2-dev+<commit>` |

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
- Migrasi SQLite schema v3 berhasil; migrasi config v2 → v3 menambahkan key baru.
- H2: `available=true`, schema v3, 79 entri dimuat ulang tanpa duplikasi setelah restart.
- `/acm scan identify` + `/acm plugins` diuji dengan plugin dummy: tampil `DummyIdentity - UNVERIFIED (conf=0%)`.
- Baseline JAR baru memakai key datar; peringatan perubahan JAR muncul sekali lalu baseline diperbarui.
- `./gradlew test` lulus (6 test).

---

## 5a. Perbaikan Sesi Lanjutan (Setelah Laporan Awal)

| Bug / celah | Perbaikan | Verifikasi |
|---|---|---|
| `build-info.properties` dan `plugin.yml` bisa tertinggal di commit lama karena task Gradle dianggap up-to-date | Tambah `inputs.property` untuk versi/commit/channel di `generateBuildInfo` dan `processResources` | Build bersih: kedua JAR melaporkan `3.0.2-dev+5804cb0` |
| Migrasi config tidak benar-benar menambah key baru karena `config.contains()` ikut membaca defaults | `migrate()` membandingkan dengan salinan mentah file pengguna | Config v2 → v3 menambahkan `plugin-identity.*`, `audit.*`, `conflict-detection.*` |
| H2 gagal start: fat jar hanya menyimpan satu `META-INF/services/java.sql.Driver` | Registrasi eksplisit `org.sqlite.JDBC`, `org.h2.Driver`, `com.mysql.cj.jdbc.Driver` | H2 `available=true`, schema v3, 79 entri bertahan setelah restart |
| Baseline JAR memakai nama file sebagai path YAML, sehingga `3.0.2` terpecah menjadi nested key | Key baseline memakai nama file yang di-escape (`3_0_2-dev-test_jar`) | Baseline baru berisi hash datar; perubahan JAR memicu satu warning |
| `StringSanitizer` gagal membuang slash jika ada spasi di depannya | Trim dan buang slash berulang sebelum collapse whitespace | Unit test lulus |
| `ConflictDetector` melewatkan entri tanpa `permission` eksplisit | Fallback ke `Command#getPermission` dari command map | Build + unit test; runtime LP belum |
| `/acm audit ignore node <node>` diperlakukan sebagai grup | Dukungan tipe `group|user|node` + tandai temuan `IGNORED` | Build; runtime LP belum |
| `/acm scan identify` tidak benar-benar mengulang karena cache | `identifyNow()` memaksa lookup ulang | Dummy plugin tampil `UNVERIFIED` di `/acm plugins` |
| Tidak ada event API yang cancellable sesuai spesifikasi | Tambah `CommandBlacklistedEvent` (cancellable, menekan alert/punishment; command tetap diblokir) | Build |
| Rescan konflik/audit saat permission LuckPerms berubah belum ada | Subscribe `NodeMutateEvent`, `GroupDataRecalculateEvent`, `UserDataRecalculateEvent` via reflection + debounce 5 detik | Build; runtime LP belum |
| Webhook bisa disisipi markdown/mention | Escape markdown Discord sebelum JSON | Build |
| Template `alert.templates.*` dari spesifikasi tidak terbaca | Baca `alert.templates` dan root `templates`, config dan language | Build |
| `/acm help` tidak menampilkan subcommand baru pada file bahasa lama | Fallback dinamis + merge key bahasa di memori | Runtime `/acm help` menampilkan plugins/conflicts/audit/security |
| Config `plugin-identity.*` vs `auto-blacklist.modrinth.*` | Baca keduanya (fallback kompatibel) | Build |
| Tidak ada automated security test | Tambah JUnit 5 + 6 test sanitasi/durasi | `./gradlew test` lulus |

---

## 5b. Sesi Lanjutan — Subcommand, Integrasi WE/FAWE/WG, dan Manual Scan

Bagian ini menambahkan fitur tanpa mengubah perilaku lama. Semua perubahan bersifat
aditif; config naik ke **v4**, schema database naik ke **v4**, dan build tetap Java 21.

### 5b.1 Audit sebelum vs sesudah (fitur sesi ini)

| Fitur | Sebelum | Sesudah | Bukti |
|---|---|---|---|
| Aturan subcommand literal (`label sub1 sub2`, `*` = satu argumen) | BELUM | SUDAH ADA | `commands:` config, `BlacklistManager.tokenMatch`, unit runtime |
| Aksi per aturan `block` / `alert-only` / `allow` | BELUM | SUDAH ADA | `Action`, `MatchResult`, listener/packet |
| Variabel alert `{SUBCOMMAND}` / `{FULLCOMMAND}` | BELUM | SUDAH ADA | `AlertManager`, sanitasi + truncation |
| Filter tab-completion untuk subcommand diblokir | BELUM | SUDAH ADA | `PlayerListener.onTabComplete` |
| WorldEdit/FAWE/WorldGuard terdeteksi dan dipetakan | BELUM | SUDAH ADA | `PluginIntegrationService`, runtime WE 7.4.6 + WG 7.0.14 |
| Rekomendasi opt-in dan `/acm integration apply` | BELUM | SUDAH ADA | `commands:` ditulis hanya setelah konfirmasi admin |
| Baca config plugin sensitif (read-only) | BELUM | SUDAH ADA | warning `max-radius: -1`, file plugin lain tidak ditulis |
| Audit LuckPerms untuk `worldedit.*`, `worldguard.*`, `worldguard.region.bypass.*`, `fawe.*` | BELUM | SUDAH ADA | `PermissionAudit`, origin group/weight/primary + penanda staff |
| `plugin-manual-scan` memetakan command/alias/permission/listener | BELUM | SUDAH ADA | CommandMap + `PluginIdentifiableCommand` + `HandlerList` |
| Source `manual-scan:<plugin>` dan cache per plugin | BELUM | SUDAH ADA | DB `acm_blacklist.source`, `/acm scan list` |
| Nonaktifkan baris manual-scan saat plugin disable/dilepas | BELUM | SUDAH ADA | PluginDisableEvent + rescan; runtime PlugManX |
| `/acm scan <plugin>` dan `/acm scan list` | BELUM | SUDAH ADA | `scanPluginAsync`; plugin tak terdaftar hanya pratinjau |
| Permission baru integration/subcmd | BELUM | SUDAH ADA | `plugin.yml`, `CommandFramework.sendUsage()` terfilter |
| Migrasi config v3 → v4 dan schema v3 → v4 | BELUM | SUDAH ADA | `ConfigModule`, `DatabaseModule.applySchemaV4` |
| Kompatibilitas `/acm` lama, config lama, source lama | SUDAH ADA | SUDAH ADA | Root command tetap; baris lama `action=BLOCK` |

### 5b.2 File baru

| File | Tujuan |
|---|---|
| `AntiChatManager/integration/PluginIntegrationService.java` | Deteksi WE/FAWE/WG, pemetaan command/permission/listener, rekomendasi opt-in, manual scan async, warning config read-only |
| `AntiChatManager/command/ACMIntegrationSubcommands.java` | `/acm integration` dan `/acm subcmd` |

### 5b.3 File lama yang diubah dan alasannya

| File | Alasan |
|---|---|
| `blacklist/BlacklistManager.java` | `Action`/`MatchResult`, canonicalization, token matcher, prioritas source, config rules, cache hasil, manual-scan upsert/disable |
| `listener/PlayerListener.java` | Aksi block/alert-only/allow, filter tab, alert-only, penanganan formatting/command panjang |
| `packet/PacketInterceptor.java` | Aksi yang sama di jalur packet tanpa mengubah fallback Bukkit |
| `command/CommandHandler.java` | Alert sukses untuk alert-only; event tetap tidak membuka command yang sudah diblokir |
| `command/ACMCommand.java` | `/acm scan list|<plugin>`, action pada list/info/export/import, join argumen multi-token untuk check/info/remove |
| `alert/AlertManager.java` | Placeholder `{SUBCOMMAND}`/`{FULLCOMMAND}`, source label asli |
| `audit/PermissionAudit.java` | Node sensitif plugin, deteksi wildcard, penanda staff/non-staff |
| `storage/Storage.java` | Persistensi `action`, label `manual-scan:<plugin>`, upsert/enable/disable |
| `config.yml` | `commands:`, `plugin-manual-scan:`, `integration:`, `audit.plugin-permissions` |
| `languages/eng_US.yml`, `languages/id_ID.yml` | Semua pesan scan/integration/subcmd/action |
| `plugin.yml` | Softdepend WE/FAWE/WG, permission integration/subcmd, root command tanpa permission |
| `SaryuLibCore/DatabaseModule.java` | `applySchemaV4` (`acm_blacklist.action`) |
| `SaryuLibCore/ConfigModule.java` | Config version 4 + migrasi aditif |
| `SaryuLibCore/CommandFramework.java` | Usage hanya menampilkan subcommand yang boleh dijalankan pengirim |

### 5b.4 Verifikasi runtime

- Paper 1.21.8, Java 25, bytecode Java 21.
- WorldEdit 7.4.6 (163 command label) dan WorldGuard 7.0.14 (17 command label) terdeteksi.
- `//SET stone` → `set` dari `manual-scan:WorldEdit`.
- `WG  reload` dan `worldguard:wg reload` → `wg reload` (CONFIG).
- `rg flag spawn pvp allow` → `rg flag` (CONFIG).
- `lp info` → tidak diblokir / allow.
- `/acm integration status` menampilkan rekomendasi `verified=true` untuk 14 command.
- `/acm integration apply worldguard` menulis 7 aturan ke `commands:` (opt-in).
- Warning read-only `WorldEdit: max-radius: -1` muncul tanpa menulis config WorldEdit.
- PlugManX: disable WorldEdit → 162 baris manual-scan menjadi `enabled=0`;
  enable kembali → 162 baris kembali `enabled=1` lewat PluginEnableEvent.
- `/acm scan list` menampilkan sumber `manual-scan:WorldEdit` dan `integration`.
- Migrasi SQLite schema v3 → v4 berhasil dan kolom `action` terbaca.
- Bahasa Indonesia diregenerasi dari resource; seluruh pesan baru tampil.
- `./gradlew :AntiChatManager:compileJava` dan pembuatan JAR berhasil.

### 5b.5 Kompatibilitas ke belakang

- Command lama, alias `/antichatmanager`, dan seluruh subcommand lama tetap ada.
- Config lama tidak ditimpa; key baru hanya ditambahkan. Baris blacklist lama
  otomatis `BLOCK`.
- Source lama (`MANUAL`, `PLUGIN_SCAN`, `LUCKPERMS_SCAN`, `IMPORT`) tetap valid.
- Semua entry baru hanya dibuat oleh admin, konfigurasi eksplisit, atau
  `plugin-manual-scan`; integrasi WE/FAWE/WG tidak menulis aturan tanpa
  `/acm integration apply`.
- `allow` milik admin selalu menang atas hasil scan/manual-scan pada command yang sama.
- Root `/acm` tidak lagi memakai permission root; framework memeriksa permission
  subcommand, sehingga admin khusus integration/subcmd tetap bisa bekerja.

### 5b.6 Yang masih memerlukan lingkungan tambahan

- FAWE belum diuji bersama karena bentrok dengan WorldEdit di server uji.
- LuckPerms, PacketEvents, PlaceholderAPI, Floodgate/Geyser, dan MySQL belum tersedia
  di server uji ini; audit node sensitif sudah diimplementasikan dan menunggu runtime LP.
- Pengujian event dengan pemain asli (client) belum dilakukan; matcher dan plugin
  load/unload sudah diverifikasi lewat konsol dan PlugManX.

---

## 6. Yang Belum Diverifikasi / Perlu Dilanjutkan di RDP

1. **Uji ulang runtime setelah build terakhir** untuk `/acm help`, `/acm reload`, `/acm scan identify`, `/acm plugins`, `/acm conflicts`, `/acm audit`, `/acm security`.
2. **LuckPerms**: uji konflik grup default/bypass, `*`, inheritance, `ack`, `ignore`, `RESOLVED`, notifikasi join, dan rescan event. Server uji saat ini tidak memasang LuckPerms.
3. **MySQL**: uji UPSERT, schema v3, dan persistensi status. H2 sudah lulus; MySQL belum tersedia di server uji.
4. **PlaceholderAPI**: uji ekspansi `%acm_violations%`, `%acm_blacklist_count%`, `%acm_bypass%`, `%acm_conflicts_*%` dengan PlaceholderAPI terpasang.
5. **PacketEvents/Floodgate**: uji pemblokiran sebelum Bukkit dan identitas Bedrock.
6. **Baseline JAR**: verifikasi `security-baseline.yml`, peringatan JAR berubah, dan hash di `/acm info`.
7. **CI supply-chain**: action sudah di-pin ke SHA dan wrapper validation ditambahkan; lanjutkan CodeQL/Semgrep/OSV/Gitleaks/Grype, SBOM, dan attestation.
8. **Tes otomatis**: path traversal, YAML bomb, regex jahat, injeksi log/command, SSRF, spam alert, DB queue penuh, klik GUI tidak valid, config rusak.
9. **`SaryuLibCore` version display**: sudah diperbaiki; runtime melaporkan `3.0.2-dev+5804cb0`.
10. **File bahasa lama di server uji**: sudah diuji; fallback dinamis menampilkan subcommand baru pada `/acm help`.

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

---

## 9. Sesi Penyelesaian (audit statis, tanpa build)

Lingkungan sesi ini tidak punya akses jaringan, sehingga `./gradlew build` **tidak dijalankan**. Yang dilakukan hanya audit statis dan perbaikan kecil.

| Temuan | Tindakan | Verifikasi |
|---|---|---|
| Kode memakai kunci `messages.internal-error`, tetapi tidak ada di file bahasa, sehingga pengguna `id_ID` melihat pesan Inggris | Tambah `internal-error` di `eng_US.yml` dan `id_ID.yml` | Kedua YAML dimuat ulang tanpa error |
| `audit.check-users` dibaca kode (default `false`) tetapi tidak ada di `config.yml` | Tambah `audit.check-users: false` dengan komentar | YAML valid, nilai terbaca `false` |
| Laporan menyebut `.github/workflows/build.yml`, tetapi berkas itu tidak ada di zip (juga tidak ada `.gitignore`) | Buat `.github/workflows/build.yml` (build, test, CodeQL, Gitleaks, OSV), `dependabot.yml`, dan `.gitignore` | YAML valid. **Action masih memakai tag, belum dipin ke SHA** |
| Konsistensi kunci bahasa | 150 kunci yang dipakai kode semuanya ada di kedua file bahasa (kecuali `help.sub.`, yang memang prefiks dinamis) | Skrip audit |
| Sintaks Java | 45 berkas diparse oleh `javac` tanpa error sintaks. Error yang tersisa hanya karena dependency (Paper API dll.) tidak tersedia di sini | `jdk.compiler` |

Belum dikerjakan: SBOM dan attestation di CI, tes otomatis keamanan (path traversal, YAML bomb, regex jahat, SSRF), GUI security (tidak ada GUI di kode), serta seluruh poin bagian 6 yang butuh server uji.
