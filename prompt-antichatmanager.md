# PROMPT AGENT AI: Plugin AntiChatManager + SaryuLibCore (PaperMC) — Versi 3

Kamu adalah developer plugin Minecraft senior (Java). Buatkan dua proyek yang saling terhubung, lengkap, siap build, dan siap dipakai di server produksi: library inti **SaryuLibCore** (dipakai bersama oleh plugin ini dan plugin-plugin berikutnya) dan plugin **AntiChatManager** yang memakai library tersebut. Jelaskan hasil kerja dalam bahasa Indonesia; **bahasa bawaan plugin (default) adalah bahasa Inggris**.

---

## 1. Target & Platform

- Platform: **PaperMC** (+ Purpur). Folia opsional (`folia-supported: true` hanya jika semua scheduler sudah diabstraksi).
- Versi Minecraft: **1.21 sampai 26.3**. Dilarang memakai NMS/CraftBukkit internal; hanya Paper API + library paket.
- Java: kompilasi bytecode **Java 21** (tetap berjalan di Java 25).
- Build: **Gradle Kotlin DSL**; shade + relocate semua dependency (HikariCP, driver DB, PacketEvents bila di-shade, dll.).
- `api-version: '1.21'`. `softdepend`: LuckPerms, PlaceholderAPI, Floodgate, PacketEvents (jika tidak di-shade).
- Package: `id.saryu.antichatmanager`.

---

## 2. Blacklist Command (Inti Plugin)

1. Command terlarang hanya boleh dijalankan oleh:
   - pemegang permission **`saryuantichatmanager.bypass`**, atau
   - **operator (OP)** (`bypass.allow-op: true`, bisa dimatikan).
2. Pemain lain yang mengetik command terlarang: command **dibatalkan total** + **alert global chat** (lihat bagian 3) + aksi tambahan (log, kick, dll.).
3. Pencocokan harus **kebal bypass**:
   - Abaikan case, spasi depan, dan `/` berlebih; bersihkan unicode, zero-width, dan karakter kontrol.
   - Tangani namespace (`/minecraft:op`, `/bukkit:pl`, `/plugin:cmd`).
   - Resolusi **alias** lewat `CommandMap` (alias ikut terblokir).
   - Mode pencocokan per entri: `exact`, `startswith`, `regex`, `wildcard`, dan pencocokan argumen (mis. `/gamemode creative`).
   - **Scan command bersarang**: `/execute ... run <cmd>`, `/minecraft:execute`, `/return run`, `/schedule`, `/function`, `/tellraw` dengan `clickEvent run_command`. Command di dalamnya juga dicek terhadap blacklist.
4. Pengecualian sumber: **console selalu diizinkan**; command block dan RCON bisa diatur (`sources.command-block`, `sources.rcon`).

---

## 3. Alert Chat yang Dapat Dikustomisasi

### Variabel
| Variabel | Isi |
|---|---|
| `{PLAYER}` | Nama pemain pelaku |
| `{CMDBLACKLIST}` | Entri blacklist yang cocok |
| `{NAMESERVER}` | Nama label kustom yang diatur admin (mis. `Server Security`, `Server Guard`) |
| `{INPUT}` | Teks asli yang diketik (sudah disanitasi) |
| `{TIME}` | Waktu format `dd/MM/yy HH:mm` + zona |
| `{WORLD}`, `{SOURCE}` | World dan sumber blacklist (manual/plugin/luckperms) |

- `alert.server-name: "Server Security"` (bebas diubah). `{NAMESERVER}` mengambil nilai ini.
- Zona waktu: `timezone: WIB` → `WIB=Asia/Jakarta`, `WITA=Asia/Makassar`, `WIT=Asia/Jayapura`. Label zona ikut pilihan.

### Warna
Dukung **hex color** dalam tiga gaya: `&#RRGGBB`, `<#RRGGBB>`, dan `<gradient:#RRGGBB:#RRGGBB>`; serta kode legacy `&` dan MiniMessage lengkap. Semua teks dari pemain **wajib di-escape** (`Placeholder.unparsed`) agar tidak bisa menyuntik tag MiniMessage.

### Template (minimal 4, sediakan 5)
Disimpan di file bahasa (`alert.templates.<nama>`), dipilih lewat `alert.template: boxed`. Contoh default (Inggris):

1. **boxed**
```
<#FF5555>=================================
<#FFFFFF>{PLAYER} <#AAAAAA>was detected trying to use
<#FFAA00>{CMDBLACKLIST} <#AAAAAA>on <#FFFFFF>{TIME}
<#5555FF>[{NAMESERVER}]
<#FF5555>=================================
```
2. **minimal**
```
<#5555FF>[{NAMESERVER}] <#FFFFFF>{PLAYER} <#AAAAAA>tried <#FFAA00>{CMDBLACKLIST}
```
3. **banner**
```
<gradient:#FF0000:#FFAA00>━━━━━ {NAMESERVER} ━━━━━</gradient>
<#FFFFFF>{PLAYER} <#AAAAAA>blocked from <#FFAA00>{CMDBLACKLIST}
<#AAAAAA>{TIME} • {WORLD}
```
4. **modern**
```
<gradient:#00C6FF:#0072FF>▎{NAMESERVER}</gradient>
<#FFFFFF>▎ {PLAYER} <#AAAAAA>attempted <#FFAA00>{CMDBLACKLIST}
<#555555>▎ {TIME}
```
5. **compact**
```
<#FF5555>⚠ <#FFFFFF>{PLAYER} <#AAAAAA>→ <#FFAA00>{CMDBLACKLIST} <#555555>({TIME})
```
- Admin boleh menambah template sendiri; ada `/acm template list|preview <nama>|set <nama>`.
- Opsi per-template: `broadcast-target` (`global` / `staff-only` / `player-only`), suara (`sound`), dan hover/klik (opsional).

---

## 4. Auto-Scan Permission Seluruh Plugin → Auto-Blacklist

Tujuan: otomatis menemukan command yang berada **di atas level default (member)** lalu memblacklist-nya, dengan jejak sumber yang jelas.

### Cara menentukan "admin / di atas member"
1. **Scan plugin**: untuk tiap plugin terpasang (termasuk vanilla/`minecraft.command.*`), baca `permissions` di `plugin.yml` + permission pada tiap `Command#getPermission`.
   - `default: true` → level **member** (tidak diblacklist).
   - `default: op` / `default: not op` yang bermakna terbatas → **admin** (diblacklist).
   - Command tanpa permission = terbuka untuk semua → tidak diblacklist (tapi dilaporkan di `/acm scan report`).
   - Heuristik tambahan (bisa dimatikan): nama permission mengandung `admin`, `staff`, `mod`, `manage`, `op`, `*`, `reload`, `give`, `gamemode`, dll.
2. **Scan LuckPerms (otomatis, seperti scan database)**: lewat **LuckPerms API** (bekerja untuk semua backend storage: MySQL, MariaDB, H2, SQLite, dsb.):
   - Tentukan grup default (member) dan grup di atasnya berdasarkan **weight** dan **track**.
   - Permission yang dimiliki grup default = level member (aman).
   - Permission yang **hanya** dimiliki grup di atas default = **staff/admin** → command terkait diblacklist otomatis.
   - Dengarkan event LuckPerms (`NodeMutateEvent`, `GroupDataRecalculateEvent`) dan **scan ulang otomatis** (debounce) saat permission berubah.
   - Opsi: `luckperms.scan-interval-minutes`, `luckperms.default-group: default`, `luckperms.staff-min-weight`.
3. Scan ulang juga saat plugin di-enable/disable dan lewat `/acm scan`.

### Keamanan agar tidak salah blacklist
- `auto-blacklist.enabled: true` (default **aktif**, sesuai kebutuhan), `auto-blacklist.dry-run: false`. Mode `dry-run` hanya melaporkan tanpa menerapkan.
- `auto-blacklist.exclude-plugins`, `exclude-commands`, `exclude-permissions` (mis. `/help`, `/spawn`, `/msg`, `/tpa`, `/home`).
- Command yang dipakai member di grup default (menurut LuckPerms) tidak pernah diblacklist.
- Perubahan hasil scan dicatat dan ditampilkan ke staf (`saryuantichatmanager.alerts`).

### Cek blacklist & sumbernya (wajib ada)
Setiap entri menyimpan: command, **sumber** (`MANUAL` / `PLUGIN_SCAN` / `LUCKPERMS_SCAN` / `IMPORT`), nama plugin, permission pemicu, alasan deteksi, waktu ditambahkan, dan siapa yang menambahkan. Disimpan di database (+ cache memori).

| Command | Fungsi |
|---|---|
| `/acm blacklist check <command>` | Cek apakah command diblacklist **dan tampilkan sumbernya** (plugin, permission, metode deteksi, waktu). Mendukung alias & namespace. |
| `/acm blacklist list [source/plugin] [page]` | Daftar entri, bisa difilter per sumber atau plugin |
| `/acm blacklist info <entry>` | Detail satu entri |
| `/acm blacklist add/remove <command>` | Kelola entri manual |
| `/acm blacklist whitelist <command>` | Kecualikan command hasil auto-scan |
| `/acm scan [report]` | Jalankan scan, tampilkan laporan |
| `/acm scan sources` | Ringkasan: berapa entri per sumber/plugin |

---

## 5. Sistem Bahasa

- Folder `plugins/AntiChatManager/languages/` berisi **`eng_US.yml`** (English, **default**) dan **`id_ID.yml`** (Indonesia).
- `config.yml`: `language: eng_US`. Fallback otomatis ke `eng_US` jika key hilang di bahasa lain.
- Seluruh teks tampil (pesan, bantuan, error, template alert) berada di file bahasa, bukan di kode.
- Mudah menambah bahasa baru (salin file, ganti nama). Perintah `/acm language <kode>` untuk ganti dan reload.
- Opsional: `language.per-player: true` memakai locale klien pemain bila file tersedia.

---

## 6. Keamanan Level Paket

Gunakan **PacketEvents** (utama). Abstraksikan lewat `PacketLayer` bila ingin fallback ProtocolLib.

- Intersep paket command & chat (`CHAT_COMMAND`, `CHAT_COMMAND_SIGNED`, `CHAT_MESSAGE`, legacy) dan **blokir sebelum diproses Bukkit**.
- Tab-complete: sembunyikan command terlarang dari pemain tanpa bypass (`PlayerCommandSendEvent` + filter paket) dan batasi spam tab-complete.
- Proteksi exploit: batas panjang (`security.max-command-length`, default 256); tolak karakter kontrol, `\u0000`, `§` mentah, payload malformed.
- **Rate limit** command/chat/tab-complete per pemain; aksi `cancel` / `kick` / `log-only`.
- Validasi `CUSTOM_PAYLOAD`, edit buku, edit sign, rename anvil (panjang & karakter ilegal). **Scan `clickEvent` command** pada buku/sign/chat component yang memicu command terlarang.
- Tolak command sebelum state PLAY / sebelum login selesai.
- Pemain Bedrock (Floodgate): identitas berbasis UUID, tanpa error.

---

## 7. Database

`database.type: MYSQL | H2 | SQLITE` (default `SQLITE`)

- Diimplementasikan lewat modul database **SaryuLibCore**; semua operasi **async**.
- Tabel: `acm_violations`, `acm_blacklist` (entri + sumber), `acm_scan_history`, `acm_violation_counts` (hitungan per pemain), `acm_punishments`, `acm_meta` (versi skema).
- MySQL: host, port, database, user, password, ssl, pool-size, properties. H2/SQLite: file di folder plugin.
- Migrasi skema otomatis, retensi data (`database.retention-days`).
- Fallback ke file log jika database gagal; plugin tidak boleh crash.

---

## 8. Batas Pelanggaran → Kick / Ban (dengan durasi)

Hitung berapa kali pemain melanggar, lalu beri hukuman bertahap jika melewati batas. Semua nilai ada di `config.yml`:

```yaml
punishment:
  enabled: true
  count-mode: global          # global = semua command digabung, per-command = dihitung per entri blacklist
  count-window: 60m           # pelanggaran kedaluwarsa setelah 60 menit; "0" = dihitung seumur hidup
  persist: true               # hitungan disimpan di database (tidak hilang saat restart)
  steps:
    - violations: 3
      action: WARN
      message: "Warning {COUNT}/{LIMIT}: do not use blacklisted commands."
    - violations: 5
      action: KICK
      reason: "Repeated use of blacklisted commands ({COUNT}x)"
    - violations: 8
      action: BAN
      duration: 1d            # contoh: 30m, 12h, 1d, 7d, 1w, 1mo, perm
      reason: "Abusing blacklisted commands ({COUNT}x)"
    - violations: 15
      action: BAN
      duration: perm
      reason: "Persistent abuse of blacklisted commands"
  per-command-overrides:      # opsional: batas khusus untuk command berbahaya
    "op": { violations: 1, action: BAN, duration: 7d }
```

- Format durasi: `s, m, h, d, w, mo, y, perm` (boleh digabung, mis. `1d12h`), divalidasi saat load config.
- Placeholder hukuman: `{PLAYER}`, `{COUNT}`, `{LIMIT}`, `{DURATION}`, `{REASON}`, `{CMDBLACKLIST}`, `{NAMESERVER}`.
- Hitungan dibersihkan lewat `/acm violations <player>`, `/acm violations reset <player>`; reset otomatis setelah ban dijalankan (`reset-after-ban: true`).
- Pemegang bypass/OP tidak dihitung. Semua hukuman dicatat di database dan audit log, serta dikirim ke staf dan webhook.
- Hukuman dijalankan di thread yang aman (dispatch ke main/region thread), tidak memblok.

---

## 9. Auto-Deteksi Plugin Ban (Ban Provider)

Plugin otomatis memilih cara menjalankan kick/ban sesuai plugin ban yang terpasang:

```yaml
ban:
  provider: AUTO        # AUTO | LITEBANS | LIBERTYBANS | ADVANCEDBAN | BANMANAGER | ESSENTIALS | VANILLA | CUSTOM
  fallback-chain: true  # jika provider gagal, coba berikutnya
```

- Urutan prioritas AUTO: **LiteBans → LibertyBans → AdvancedBan → BanManager → EssentialsX → Vanilla/Paper ban list**.
- Deteksi saat startup dan saat plugin ban di-enable/disable (reload provider otomatis). Tampilkan provider aktif di `/acm info` dan log.
- Tiap provider punya adapter (interface `BanProvider` di SaryuLibCore) dengan **template command console** yang bisa diubah, mis.:
  - LiteBans: `tempban {PLAYER} {DURATION} {REASON}` / `ban {PLAYER} {REASON}` / `kick {PLAYER} {REASON}`
  - EssentialsX: `tempban {PLAYER} {DURATION} {REASON}` / `ban {PLAYER} {REASON}`
  - Provider lain memakai sintaks command masing-masing (verifikasi dokumentasi tiap plugin).
- Konversi format durasi per provider (Essentials, LiteBans, dll. berbeda sedikit) dan tangani ban permanen.
- `VANILLA`: pakai ban list Paper API dengan tanggal kedaluwarsa jika tidak ada plugin ban.
- `CUSTOM`: admin mengisi sendiri template command (`ban.custom.ban`, `ban.custom.tempban`, `ban.custom.kick`).
- Validasi: jika tidak ada provider yang berfungsi, tulis peringatan jelas dan turun ke `VANILLA`, jangan crash.
- Provider ini juga diekspos lewat SaryuLibCore (`PunishmentService`) agar plugin lain bisa memakainya.

---

## 10. SaryuLibCore (Library Bersama)

Library inti yang mempermudah pembuatan plugin Saryu berikutnya, supaya kode tidak ditulis ulang di tiap plugin.

**Bentuk:** plugin mandiri **SaryuLibCore.jar** yang dimuat lewat `depend` (satu instance dipakai bersama semua plugin Saryu, pool database dan packet layer tidak duplikat). Konsumen memakai `compileOnly`. Versi API mengikuti **semantic versioning**, dengan Javadoc dan README. Package: `id.saryu.libcore`. Kompatibel PaperMC 1.21–26.3, Java 21 bytecode, tanpa NMS. Dependency pihak ketiga (HikariCP, driver DB, PacketEvents) di-shade + relocate agar tidak bentrok.

**Modul yang harus ada:**
1. **Config**: YAML berversi, auto-migrasi, komentar terjaga, validasi dengan pesan error jelas, hot reload.
2. **Language/i18n**: folder `languages/` (`eng_US.yml` default, `id_ID.yml`), fallback ke Inggris, opsi locale per pemain.
3. **Message & Component**: hex (`&#RRGGBB`, `<#RRGGBB>`), gradient, MiniMessage + legacy, helper escape (`Placeholder.unparsed`), sistem template dengan variabel.
4. **Command framework**: pendaftaran subcommand, tab-complete otomatis, permission, cooldown, eksekusi async, pesan usage otomatis (tanpa boilerplate `plugin.yml` per command).
5. **Database**: HikariCP untuk **MySQL/H2/SQLite**, executor async, migrasi skema berversi, helper query/DAO sederhana, fallback bila gagal.
6. **Scheduler**: abstraksi Paper/Folia (sync, async, region, delayed, repeating).
7. **Packet layer**: pembungkus PacketEvents (listener, cancel, rate limit, validasi paket umum).
8. **Permission & LuckPerms**: helper deteksi grup default, weight/track, scanner permission plugin (dipakai auto-blacklist), pengecekan "di atas member".
9. **PunishmentService**: auto-detect provider ban (bagian 9), kick/ban/tempban, parser durasi.
10. **Time util**: parser durasi (`1d2h30m`, `perm`), format waktu `dd/MM/yy HH:mm` dengan zona WIB/WITA/WIT.
11. **Integrasi**: PlaceholderAPI, Floodgate/Geyser (cek pemain Bedrock), Vault, webhook Discord (embed), update checker.
12. **Utilitas**: cache LRU/TTL, logger berlevel (`debug`), GUI inventory sederhana (aman untuk Bedrock), ItemBuilder, event custom dasar.
13. **Service registry**: `SaryuLib.getService(...)` agar plugin saling berbagi layanan; tiap plugin terdaftar dengan nama, versi, dan data folder sendiri.

**Template plugin baru:** sertakan contoh minimal (`ExamplePlugin`) yang menunjukkan cara memakai config, bahasa, command, database, dan scheduler dalam < 100 baris, sehingga plugin berikutnya cepat dibuat.

AntiChatManager **wajib memakai SaryuLibCore** untuk config, bahasa, command, database, scheduler, packet, LuckPerms, dan hukuman, bukan menulis ulang fungsi yang sama.

---

## 11. Fitur Tambahan (Rekomendasi)

1. **Eskalasi hukuman** berbasis jumlah pelanggaran: lihat bagian 8.
2. **Alert staf + webhook Discord** (embed berisi pemain, command, sumber blacklist, waktu).
3. **Whitelist pemain tepercaya** (UUID) dan whitelist per grup LuckPerms.
4. **Blacklist per world** dan **per grup** (mis. command tertentu hanya boleh untuk grup tertentu).
5. **Anti-spam chat**: cooldown, pesan duplikat, batas huruf kapital, daftar kata terlarang.
6. **API & event untuk developer**: `CommandBlacklistedEvent` (cancellable) dan `AntiChatManagerAPI`.
7. **PlaceholderAPI**: `%acm_violations%`, `%acm_blacklist_count%`.
8. **Audit log** file `logs/violations.log` + `/acm history <player> [page]`.
9. **Export/import blacklist** (`/acm export`, `/acm import`) dan backup otomatis config.
10. **Hot reload** + validasi config dengan pesan error yang jelas; **auto-migrasi versi config**.
11. **Update checker** (opsional, bisa dimatikan) dan metrics opsional.
12. **Mode debug** (`debug: false`) dan `/acm info` (status, versi, DB, jumlah entri per sumber).
13. **Performa**: cache hasil pencocokan (LRU), pencocokan O(1) untuk entri exact, tanpa operasi blocking di main thread.
14. **Notifikasi saat scan menemukan command baru** (plugin baru dipasang) ke staf.

---

## 12. Command & Permission

Semua di bawah `/acm` (alias `/antichatmanager`):
`reload`, `info`, `scan`, `blacklist ...`, `history`, `violations [reset] <player>`, `template ...`, `language`, `export`, `import`.

- **`saryuantichatmanager.bypass`**: boleh memakai semua command blacklist (default: false). Operator juga bypass jika `bypass.allow-op: true`.
- **`saryuantichatmanager.admin`**: semua command `/acm` (default: op).
- **`saryuantichatmanager.alerts`**: menerima alert staf (default: op).
- Tab-complete `/acm` hanya untuk pemegang `saryuantichatmanager.admin`.

---

## 13. Kualitas Kode & Hasil Akhir

- Struktur: `config`, `lang`, `listener`, `packet`, `scan` (plugin + luckperms), `blacklist`, `storage`, `command`, `alert`, `util`, `api`.
- Tanpa operasi blocking di main thread; bersihkan data pemain saat quit (tanpa memory leak).
- Struktur: **Gradle multi-project** berisi `SaryuLibCore/` dan `AntiChatManager/` (satu `settings.gradle.kts`), tiap proyek menghasilkan JAR sendiri; `SaryuLibCore` juga siap `maven-publish` (mavenLocal/GitHub Packages).
- Sertakan: `build.gradle.kts` tiap proyek, `plugin.yml`, `config.yml` (berkomentar lengkap), `languages/eng_US.yml`, `languages/id_ID.yml`, `README.md` (termasuk panduan memakai SaryuLibCore di plugin baru).
- Sertakan **panduan uji manual**: pemain biasa vs OP vs bypass; alias, namespace, `/execute run`; unicode; spam paket; scan LuckPerms (ubah permission → scan ulang otomatis); `blacklist check` menampilkan sumber; ganti bahasa; tiap template alert; tiap tipe database; batas pelanggaran (warn → kick → ban sementara → ban permanen); auto-deteksi provider ban (EssentialsX, LiteBans, tanpa plugin ban); `SaryuLibCore` dipakai oleh plugin contoh.

**Output yang diharapkan:** seluruh source code kedua proyek + resource + instruksi build (`./gradlew build`) dalam satu repositori utuh.
