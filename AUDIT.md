# LAPORAN AUDIT KEAMANAN DAN TINJAUAN KODE (FASE 1: READ-ONLY)

**Repositori**: GuardPlugin-Project (ACM Lite 4.0.0, SaryuGuard, SaryuLibCore)  
**Lingkungan**: Linux / Java 25 (OpenJDK 64-Bit Server VM temurin-25) / Gradle 9.8.1  
**Tanggal Audit**: 24 Oktober 2026  
**Status Audit**: Read-Only Audit & Verification Complete  

---

## 1. Ringkasan Eksekutif

Audit ini mencakup analisis statis dan review arsitektur secara menyeluruh terhadap seluruh modul dalam ekosistem `GuardPlugin-Project`:
- **SaryuLibCore**: Komponen bersama (HikariCP, Paginator, StringSanitizer).
- **AntiChatManager Lite (4.0.0)**: Intersepsi command blacklist berbasis snapshot in-memory, transisi status kebijakan (`READY`, `DEGRADED`, `UNAVAILABLE`), fail-safe emergency blacklist, dan migrasi 3.x.
- **SaryuGuard**: Kerangka keamanan modular (CryptService AES-GCM, Staff 2FA TOTP/Argon2id, Audit Hash Chain, Chat Filter Aho-Corasick/RE2J, Connection Guard, OpGuard, Plugin Verify, Malware Scanner wrapper).

Dalam Fase 1 ini, **tidak ada kode aplikasi yang diubah**. Fokus utama adalah memetakan kerentanan (P0/P1/P2), memverifikasi bukti baris kode (`file:baris`), memastikan keabsahan versi API/SHA action, serta mengidentifikasi tes unit/integrasi yang hilang.

### Ringkasan Jumlah Temuan

| Tingkat Keparahan | Terverifikasi | Dugaan | Total |
|---|---|---|---|
| **P0 (Kritis)** | 6 | 0 | 6 |
| **P1 (Tinggi)** | 9 | 1 | 10 |
| **P2 (Sedang/Rendah)** | 8 | 1 | 9 |
| **Total** | **23** | **2** | **25** |

---

## 2. Hasil Eksekusi Build & SpotBugs

### A. Gradle Build (`./gradlew build --no-daemon`)
- **Status**: `BUILD SUCCESSFUL` (23 actionable tasks: 1 executed, 22 up-to-date / re-run tests: 16 tasks executed bersih).
- **Hasil Test**: 20 kelas pengujian JUnit lulus tanpa kegagalan (100% pass).
- **Catatan Runtime Java 25**:
  Ditemukan peringatan JVM terkait native SQLite loader:
  ```text
  WARNING: A restricted method in java.lang.System has been called
  WARNING: java.lang.System::load has been called by org.sqlite.SQLiteJDBCLoader in an unnamed module
  WARNING: Use --enable-native-access=ALL-UNNAMED to avoid a warning for callers in this module
  WARNING: Restricted methods will be blocked in a future release unless native access is enabled
  ```
  *Rekomendasi*: Tambahkan flag `--enable-native-access=ALL-UNNAMED` pada dokumentasi Paper 26.x / Java 25 di README.md.

### B. SpotBugs
- **Status**: **TIDAK TERSEDIA** (Plugin `com.github.spotbugs` belum dikonfigurasi dalam `build.gradle.kts`).
- Hanya task kustom `:scanForbiddenApis` yang aktif pada lifecycle `check` (memeriksa `Runtime.exec`, `ProcessBuilder` selain di MalwareScanner, `ObjectInputStream`, `URLClassLoader`, `Unsafe`, dll).

### C. Verifikasi SHA GitHub Actions (`gh api`)
- `actions/checkout@11d5960a326750d5838078e36cf38b85af677262` -> **Terverifikasi** (commit hash valid).
- `actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02` -> **Terverifikasi** (commit hash valid).

---

## 3. Tabel Temuan Lengkap

| ID | Modul | File : Baris | Keparahan | Status | Ringkasan Temuan |
|---|---|---|---|---|---|
| **SEC-01** | TwoFactor | [TotpEngine.java:91-96](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/mfa/TotpEngine.java#L91-L96) | **P0** | Terverifikasi | `constantTimeEquals` menggunakan `Arrays.equals(ba, bb)` yang bukan constant-time (timing side-channel). |
| **SEC-02** | TwoFactor | [TwoFactorModule.java:257-298](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/mfa/TwoFactorModule.java#L257-L298) | **P0** | Terverifikasi | Verifikasi recovery code sama sekali tidak diimplementasikan di `onChat` (hanya memeriksa TOTP 6-digit regex). |
| **SEC-03** | TwoFactor | [TwoFactorModule.java:186-200](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/mfa/TwoFactorModule.java#L186-L200) | **P0** | Terverifikasi | Race window saat reconnect: player belum terdaftar di `sessions` sebelum async DB load selesai, bypass pembatasan interaksi. |
| **SEC-04** | Audit | [AuditModule.java:218, 520](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/audit/AuditModule.java#L218) | **P0** | Terverifikasi | Eksekusi retensi audit menghancurkan integritas verifikasi hash chain (`verifyChain` selalu berasumsi mulai dari `GENESIS_HASH`). |
| **SEC-05** | AntiChat | [LiteCommandListener.java:38, 65](file:///workspaces/GuardPlugin-Project/AntiChatManager/src/main/java/id/saryu/antichatmanager/listener/LiteCommandListener.java#L38) | **P0** | Terverifikasi | Default `allow-op: true` membiarkan OP mem-bypass emergency blacklist (`/stop`, `/op`, `/reload`) saat kebijakan `UNAVAILABLE`. |
| **SEC-06** | PluginVerify | [PluginVerifyModule.java:171, 191](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/verify/PluginVerifyModule.java#L171) | **P0** | Terverifikasi | Ketidakcocokan skema kritis: `PluginVerifyModule` query kolom `jar_size` dan `approved_by` yang tidak ada di skema `SaryuGuardDatabase`. |
| **SEC-07** | Crypt | [ConfigManager.java:134-136](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/config/ConfigManager.java#L134-L136) | **P1** | Terverifikasi | Penurunan kriptografi: mode passphrase di `ConfigManager` memakai SHA-256 biasa, mengabaikan Argon2id KDF dari PLAN.md. |
| **SEC-08** | Crypt | [ConfigManager.java:140-156](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/config/ConfigManager.java#L140-L156) | **P1** | Terverifikasi | Kunci hilang/corrupt memicu pembuatan kunci acak baru secara diam-diam atau fallback in-memory, merusak data DB terenkripsi permanen. |
| **SEC-09** | Crypt | [ConfigManager.java:143, 165](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/config/ConfigManager.java#L143) | **P1** | Terverifikasi | File `secret.key` ditulis tanpa pembatasan hak akses POSIX (0600), dapat dibaca proses/user lokal lain. |
| **SEC-10** | TwoFactor | [TwoFactorModule.java:380-390](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/mfa/TwoFactorModule.java#L380-L390) | **P1** | Terverifikasi | Status akun `LOCKED` tidak pernah disimpan ke DB (`updateLockState` tidak dipanggil), hilang saat server reboot. |
| **SEC-11** | TwoFactor | [TotpEngine.java:49-62](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/mfa/TotpEngine.java#L49-L62) | **P1** | Terverifikasi | Tidak ada pencegahan replay kode TOTP; kode yang sama dapat digunakan berulang kali dalam jendela toleransi 90 detik. |
| **SEC-12** | Audit | [AuditChain.java:40-49](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/audit/AuditChain.java#L40-L49) | **P1** | Terverifikasi | Format kanonis rentan collision/ambiguitas jika field mengandung delimiter pipe `\|` tanpa escaping. |
| **SEC-13** | Connection | [ConnectionGuardModule.java:110, 187](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/connection/ConnectionGuardModule.java#L187) | **P1** | Terverifikasi | Konfigurasi proxy di `config.yml` tidak dibaca kode; IP proxy 127.0.0.1 memicu blokir massal `max-accounts-per-ip`. |
| **SEC-14** | Connection | [ConnectionGuardModule.java:248-250](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/connection/ConnectionGuardModule.java#L248-L250) | **P1** | Terverifikasi | Pembersihan struktur `accountsPerIp.clear()` saat mencapai batas 5000 entri menghapus semua limitasi akun secara massal. |
| **SEC-15** | Compat | [CompatService.java:70](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/compat/CompatService.java#L70) | **P1** | Terverifikasi | Tidak ada penanganan runtime plugin disable (`PluginDisableEvent`); delegasi ke Sonar/AuthMe tetap aktif meski plugin dimatikan. |
| **SEC-16** | MalwareScanner | [MalwareScannerModule.java:168, 220](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/scanner/MalwareScannerModule.java#L168) | **P1** | Terverifikasi | Blank `pinned-sha256` melewati verifikasi hash; log output "No threats detected" salah dideteksi sebagai ancaman aktif. |
| **SEC-17** | ACM Migration | [MigrationManager.java:204-212](file:///workspaces/GuardPlugin-Project/AntiChatManager/src/main/java/id/saryu/antichatmanager/migration/MigrationManager.java#L204-L212) | **P1** | Terverifikasi | Backup migrasi 3.x melewatkan direktori `languages/` karena hanya memfilter file (`f.isFile()`). |
| **SEC-18** | Locale | [ChatFilterModule.java:212, 224](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/filter/ChatFilterModule.java#L212) | **P1** | Terverifikasi | Kunci bahasa `chat.filtered-notice` dan `chat.error-blocked` dipakai di kode tetapi tidak ada di `id.yml` atau `en.yml`. |
| **SEC-19** | Crypt | [SecretCipher.java:90](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/mfa/SecretCipher.java#L90) | **P2** | Terverifikasi | Zeroing gagal: `new String(passphrase)` membuat objek immutable String di heap JVM yang tidak bisa di-wipe. |
| **SEC-20** | Crypt | [CryptService.java:128-144](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/crypt/CryptService.java#L128-L144) | **P2** | Terverifikasi | Kegagalan rotasi: DB commit duluan sebelum penulisan file `secret.key.tmp`; kegagalan I/O file menyebabkan inkonsistensi era kunci. |
| **SEC-21** | Audit | [AuditModule.java:339-345](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/audit/AuditModule.java#L339-L345) | **P2** | Terverifikasi | Kegagalan penulisan DB audit membuang record secara permanen tanpa mekanisme retry/re-queue. |
| **SEC-22** | OpGuard | [OpGuardModule.java:161-203](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/opguard/OpGuardModule.java#L161-L203) | **P2** | Terverifikasi | Penanganan pelanggaran OP pada `PlayerLoginEvent` (priority MONITOR) menjadwalkan task sinkron tick berikutnya; celah OP sesaat di tick 0. |
| **SEC-23** | Locale | [Locales](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/resources/locales/) | **P2** | Terverifikasi | Duplikat locale usang: `id_ID.yml` dan `en_US.yml` berisi key usang dengan kode warna legacy `&a`/`&7`. |
| **SEC-24** | ChatFilter | [ChatFilterModule.java:321](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/filter/ChatFilterModule.java#L321) | **P2** | Dugaan | Akses konkuren pada `rawAdminRegex` (ArrayList biasa) saat `saveWordsFileAsync` dijalankan di thread terpisah. |
| **SEC-25** | AntiChat | [ACMCommandLite.java:126-129](file:///workspaces/GuardPlugin-Project/AntiChatManager/src/main/java/id/saryu/antichatmanager/command/ACMCommandLite.java#L126-L129) | **P2** | Terverifikasi | Placeholder `{status}` di `messages.yml` tidak diganti karena argumen `status` tidak dimasukkan ke pemanggilan `formatMessage`. |

---

## 4. Tinjauan Terperinci per Modul (Daftar Periksa)

### 4.1 CryptService & SecretCipher
- **Nonce Unik**: [SecretCipher.java:127-128](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/mfa/SecretCipher.java#L127-L128) menggunakan `new byte[12]` yang diisi oleh `SecureRandom.nextBytes`. Nonce unik tiap enkripsi. *(Terverifikasi - Aman)*
- **AAD (Additional Authenticated Data)**: [SecretCipher.java:193-195](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/mfa/SecretCipher.java#L193-L195) mengikat `table:rowId:colName:keyVersion`. Ini mencegah ciphertext dipindahkan antar kolom, baris, atau era kunci. *(Terverifikasi - Aman)*
- **Format Version Check**: [SecretCipher.java:159](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/mfa/SecretCipher.java#L159) mencadangkan `raw[0]` sebagai versi format, namun `decrypt()` tidak memvalidasi apakah `raw[0] == FORMAT_VERSION`. *(Terverifikasi - P2)*
- **Panjang Kunci & Penurunan Kunci**:
  - `SecretCipher(byte[], int)` memastikan 32 byte (256 bit).
  - Namun di [ConfigManager.java:134-136](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/config/ConfigManager.java#L134-L136), mode `passphrase` hanya menggunakan satu kali digest `MessageDigest.getInstance("SHA-256")` dengan salt, bukan Argon2id KDF! Hal ini bertentangan langsung dengan PLAN.md baris 20 dan 168. *(Terverifikasi - P1)*
  - Pada mode `env`, [ConfigManager.java:128-129](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/config/ConfigManager.java#L128-L129) me-hash string environment variable dengan SHA-256, sedangkan `SecretCipher.fromBase64EnvKey` mengharapkan format 32 byte Base-64. *(Terverifikasi - P2)*
- **Hak Akses File `secret.key`**: [ConfigManager.java:143](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/config/ConfigManager.java#L143) dan baris 165 menulis file tanpa mengatur atribut izin POSIX (`0600` / `rw-------`). *(Terverifikasi - P1)*
- **Penanganan Kunci Hilang**: [ConfigManager.java:140](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/config/ConfigManager.java#L140) langsung menghasilkan kunci acak baru bila `secret.key` tidak ada. Jika database sudah berisi tabel terenkripsi (`sg_2fa_secrets`, `sg_audit_log`), pergantian kunci acak ini menyebabkan seluruh rekaman lama tidak dapat didekripsi lagi (data loss permanen). Jika gagal baca, baris 153 memakai `emergencyKey` in-memory yang hilang saat restart. *(Terverifikasi - P1)*
- **Zeroing**: [SecretCipher.java:90](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/mfa/SecretCipher.java#L90) memanggil `new String(passphrase)` yang menduplikasi rahasia ke objek immutable JVM heap, merusak tujuan penerimaan parameter `char[]`. *(Terverifikasi - P2)*

### 4.2 TwoFactorModule, TotpEngine & RecoveryCodes
- **Perbandingan Waktu-Konstan**:
  - [TotpEngine.java:91-96](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/mfa/TotpEngine.java#L91-L96): Metode `constantTimeEquals` menggunakan `Arrays.equals(ba, bb)`. Implementasi `Arrays.equals` di Java JDK melakukan short-circuit loop pada byte pertama yang tidak cocok (`if (a[i] != b[i]) return false;`). Ini rentan timing attack. Wajib diganti dengan `MessageDigest.isEqual(ba, bb)`. *(Terverifikasi - P0)*
  - [TotpEngine.java:55](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/mfa/TotpEngine.java#L55): Ekspresi `|| Integer.parseInt(generated) == provided` memotong perbandingan konstan. *(Terverifikasi - P0)*
- **Batas Percobaan & Persistensi Lockout**:
  - [TwoFactorModule.java:381](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/mfa/TwoFactorModule.java#L381): Ketika percobaan gagal mencapai `maxAttempts`, akun dikunci via `session.setState(MfaState.LOCKED)`. Namun pemanggilan DAO untuk persistensi status kunci ke database (`twoFactorDao.updateLockState(c, uuid, true, attempts)`) **TIDAK PERNAH DILAKUKAN**.
  - Akibatnya: Status kunci hanya bertahan di RAM. Begitu server restart, map in-memory `sessions` kosong, dan penyerang dapat mencoba kembali brute-force tanpa batasan. *(Terverifikasi - P1)*
- **Replay Kode**: [TotpEngine.java:49-62](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/mfa/TotpEngine.java#L49-L62) tidak melacak time-step atau counter terakhir yang berhasil digunakan. Kode TOTP yang sama sah digunakan kembali dalam rentang waktu ±1 time-step (90 detik). *(Terverifikasi - P1)*
- **Status `PENDING_MFA` saat Reconnect**:
  - [TwoFactorModule.java:186-200](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/mfa/TwoFactorModule.java#L186-L200): Pada `onPlayerJoin`, modul menjadwalkan task async `loadSecretFromDb(uuid)` lalu baru menjadwalkan task sync `continueJoinMfa(player)`.
  - Selama jeda (race window) antara event join dan selesainya query DB, session player adalah `null`. Seluruh listener pembatasan ([onCommand baris 306](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/mfa/TwoFactorModule.java#L306), `onMove`, `onInventory`) langsung mengembalikan `return` jika `session == null || state != PENDING_MFA`. Pemain berhak staf dapat mengeksekusi perintah atau memindahkan item sebelum sesi MFA dibuat! *(Terverifikasi - P0)*
- **Verifikasi Recovery Code**:
  - [TwoFactorModule.java:257-298](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/mfa/TwoFactorModule.java#L257-L298): Pada event chat input 2FA, kode hanya memeriksa `TotpEngine.verify(secretBytes, input)`. `TotpEngine.verify` secara kaku menolak input yang bukan 6-digit regex (`\d{6}`). Kode pemulihan (13 karakter alfanumerik) yang disimpan di `recoveryHashes` **sama sekali tidak memiliki alur pengecekan** di `onChat`. Staf yang kehilangan authenticator tidak dapat menggunakan recovery code mereka. *(Terverifikasi - P0)*
- **Pencatatan Secret di Log**: Tidak ditemukan pencatatan plaintext TOTP secret ke console logger. `TwoFactorModule` baris 423 secara eksplisit mencatat bahwa secret tidak dicatat ke log. *(Terverifikasi - Aman)*

### 4.3 AuditChain & AuditModule
- **Format Kanonis & Delimiter Collision**:
  - [AuditChain.java:40-49](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/audit/AuditChain.java#L40-L49): Penggabungan string menggunakan pemisah pipe `|` tanpa escaping atau pemanjangan panjang string. Jika `detailsPlain` atau `actorName` mengandung karakter `|`, representasi kanonis dapat bertabrakan atau bergeser. *(Terverifikasi - P1)*
- **Verifikasi Rantai vs Retensi Data**:
  - [AuditModule.java:218](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/audit/AuditModule.java#L218) pada `verifyChain()` selalu memulai kalkulasi rantai dari `AuditChain.GENESIS_HASH` (`0000...0000`).
  - Ketika [runRetention() baris 520](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/audit/AuditModule.java#L520) berjalan dan menghapus baris-baris terlama berdasarkan `database.retention-days`, baris pertama yang tersisa di tabel merujuk pada `prev_hash` dari baris yang telah dihapus (bukan `GENESIS_HASH`).
  - Dampak: Begitu retensi berjalan dan menghapus 1 baris saja, perintah `/sg audit verify` akan langsung **GAGAL** dan melaporkan "Rantai rusak pada id=..." untuk seluruh sisa log! Diperlukan pencatatan anchor checkpoint pada baris pertama setelah retensi. *(Terverifikasi - P0)*
- **Penanganan Kesalahan Dekripsi pada Verifikasi**:
  - [AuditModule.java:560-566](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/audit/AuditModule.java#L560-L566): Jika dekripsi kolom `details_enc` atau `ip_enc` gagal saat verifikasi, method secara diam-diam mengembalikan string ciphertext mentah sebagai plaintext. Akibatnya, hash kanonis dihitung atas ciphertext, yang pasti gagal mencocokkan rantai. *(Terverifikasi - P2)*
- **Kegagalan Tulis di Queue**:
  - [AuditModule.java:333-345](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/audit/AuditModule.java#L333-L345): Jika `persist(rec)` gagal (misal koneksi DB putus), record telah di-`poll()` keluar dari antrian dan langsung dibuang tanpa retry loop. *(Terverifikasi - P2)*

### 4.4 MigrationManager (AntiChatManager Lite)
- **Idempotensi & Transaksi MySQL**:
  - [MigrationManager.java:126-127](file:///workspaces/GuardPlugin-Project/AntiChatManager/src/main/java/id/saryu/antichatmanager/migration/MigrationManager.java#L126-L127): Kueri SQL menyertakan `ON CONFLICT(command) DO NOTHING` hanya untuk SQLite. Untuk MySQL, klausa tersebut kosong dan koneksi berjalan dalam mode autocommit default.
  - Jika terjadi kegagalan di tengah-tengah import di MySQL, sebagian data masuk tanpa penanda `markMigrationCompleted`. Saat dijalankan ulang, MySQL melempar duplicate key error berulang kali. *(Terverifikasi - P2)*
- **Pencadangan Direktori Bahasa**:
  - [MigrationManager.java:204-212](file:///workspaces/GuardPlugin-Project/AntiChatManager/src/main/java/id/saryu/antichatmanager/migration/MigrationManager.java#L204-L212): Pemeriksaan file backup memeriksa `name.startsWith("languages")`, namun pada baris 210 terdapat pengecekan `if (f.isFile())`. Folder `languages/` diabaikan dan tidak dicadangkan ke `backup-3.x/`. *(Terverifikasi - P1)*
- **Data Preservation**:
  - Tabel lama `acm_blacklist` dibaca murni dengan `SELECT`. Tidak ada perintah `DROP` atau `DELETE`. Data lama utuh. *(Terverifikasi - Aman)*
- **Jalur Gagal**:
  - Bila migrasi gagal, laporan error dicatat dengan jelas di console dan plugin beralih menggunakan emergency-blacklist jika tabel blacklist kosong/gagal dimuat. *(Terverifikasi - Aman)*

### 4.5 LiteCommandListener & PolicyStatus
- **Transisi Status (READY, DEGRADED, UNAVAILABLE)**:
  - Logika di `LiteBlacklistManager.loadPolicy()` mematuhi kontrak: cache kosong tidak pernah dianggap blacklist kosong, database error dengan cache valid menjadi `DEGRADED`, dan database error tanpa cache menjadi `UNAVAILABLE`. *(Terverifikasi - Aman)*
- **Emergency Blacklist Bypass via Default OP**:
  - [LiteCommandListener.java:38](file:///workspaces/GuardPlugin-Project/AntiChatManager/src/main/java/id/saryu/antichatmanager/listener/LiteCommandListener.java#L38) dan [config.yml:7](file:///workspaces/GuardPlugin-Project/AntiChatManager/src/main/resources/config.yml#L7): Variabel `allowOpBypass` bernilai default `true`.
  - [LiteCommandListener.java:65-67](file:///workspaces/GuardPlugin-Project/AntiChatManager/src/main/java/id/saryu/antichatmanager/listener/LiteCommandListener.java#L65-L67): Pengecekan `if (allowOpBypass && player.isOp()) return;` dieksekusi **sebelum** command blacklist atau emergency blacklist diperiksa.
  - Dampak: Jika sistem dalam status `UNAVAILABLE` (darurat), akun OP ilegal/penyerang tetap dapat mengeksekusi `/stop`, `/op`, `/reload`, `/restart` tanpa halangan sama sekali. Aturan keamanan di `SECURITY.md` baris 56 ("All administrative permissions default strictly to op... bypass permissions default to false") dilanggar oleh default `allow-op: true`. *(Terverifikasi - P0)*
- **Placeholder Versi di messages.yml**:
  - [ACMCommandLite.java:126-129](file:///workspaces/GuardPlugin-Project/AntiChatManager/src/main/java/id/saryu/antichatmanager/command/ACMCommandLite.java#L126-L129): Template `version-info` di `messages.yml` memiliki token `{status}`, namun kode Java tidak menyertakan pasangan `"status", statusColor` ke `formatMessage()`. Token `{status}` tampil mentah. *(Terverifikasi - P2)*

### 4.6 ChatFilterModule
- **Ketahanan ReDoS**:
  - [AdminRegexMatcher.java:3, 62](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/filter/AdminRegexMatcher.java#L3): Memakai murni `com.google.re2j.Pattern` yang menjamin waktu linear O(n). Pola regex yang tidak didukung atau melebihi batas jumlah/panjang ditolak sebelum diaplikasikan. *(Terverifikasi - Aman)*
- **Thread-Safety Ruleset**:
  - Kompilasi dan pergantian ruleset menggunakan `AtomicReference<List<Pattern>>` dan `AtomicReference<AhoCorasick>`.
  - Namun, di [ChatFilterModule.java:321](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/filter/ChatFilterModule.java#L321), `saveWordsFileAsync()` mengakses `rawAdminRegex` (instance `ArrayList` yang tidak thread-safe) dari background thread saat config disimpan. *(Dugaan / P2)*
- **Offender Isolation & Kebocoran Pesan**:
  - [ChatFilterModule.java:209](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/filter/ChatFilterModule.java#L209): `event.viewers().removeIf(viewer -> viewer != player);` memastikan hanya pengirim yang melihat pesannya sendiri saat terjadi pelanggaran.
  - Namun jika pengecekan melempar exception tak terduga, jalur catch ([baris 219-228](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/filter/ChatFilterModule.java#L219-L228)) dengan default `chat-filter.on-error: allow` akan membiarkan pesan terlarang lolos ke seluruh pemain di server. *(Terverifikasi - P1)*

### 4.7 ConnectionGuardModule & CompatService
- **Kepercayaan IP di Balik Proxy**:
  - Di `SaryuGuard/src/main/resources/config.yml` terdapat konfigurasi `connection-guard.proxy.enabled` dan `trusted-proxies`.
  - Namun pada [ConnectionGuardModule.java](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/connection/ConnectionGuardModule.java), konfigurasi proxy tersebut **sama sekali tidak dibaca atau digunakan**.
  - Dampak: Jika server dijalankan di belakang BungeeCord/Velocity tanpa proper forwarding, `event.getAddress()` selalu bernilai `127.0.0.1`. Setelah 5 pemain pertama login, `max-accounts-per-ip` (default 5) akan **memblokir seluruh pemain di server** yang mencoba masuk. *(Terverifikasi - P0)*
- **Reset Massal Struktur Pembatas Akun**:
  - [ConnectionGuardModule.java:248-250](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/connection/ConnectionGuardModule.java#L248-L250): Ketika `accountsPerIp.size() > 5000`, kode mengeksekusi `accountsPerIp.clear();`. Tindakan ini mereset seluruh batas pembuatan akun untuk semua IP secara instan. Penyerang dapat melakukan flood koneksi dengan IP palsu untuk memicu `clear()`, lalu mendaftarkan akun tanpa batas. *(Terverifikasi - P1)*
- **Dinamika Failover Kompatibilitas**:
  - [CompatService.java](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/compat/CompatService.java) memindai plugin hanya pada saat startup. Tidak ada listener untuk `PluginDisableEvent`.
  - Jika Sonar atau AuthMe di-disable atau crash di tengah jalan, `ConnectionGuardModule` tetap mengira Sonar/AuthMe aktif (`delegateThrottleToSonar = true`, `delegateAccountToAuthMe = true`), dan perlindungan internal tidak mengambil alih. Ketentuan failover `compat.on-missing: full` tidak berfungsi. *(Terverifikasi - P1)*

### 4.8 OpGuardModule, PluginVerifyModule & MalwareScannerModule
- **Inkonsistensi Skema Database PluginVerifyModule**:
  - Skema tabel `sg_plugin_baseline` di [SaryuGuardDatabase.java:287-298](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/database/SaryuGuardDatabase.java#L287-L298) mendefinisikan kolom: `id, plugin_name, jar_hash, hash_algo, status, approver, approved_at, created_at`.
  - Namun di [PluginVerifyModule.java:171, 191](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/verify/PluginVerifyModule.java#L171), modul melakukan query: `SELECT plugin_name, jar_hash, jar_size, approved_at, approved_by, status FROM sg_plugin_baseline`.
  - Dampak: Kolom `jar_size` dan `approved_by` tidak ada di database `SaryuGuardDatabase`. Eksekusi query melempar `SQLException: no such column: jar_size`. Selain itu, `PluginVerifyModule` memakai pool `lib.getDatabaseModule()` bukan `plugin.getDatabase()`. *(Terverifikasi - P0)*
- **Verifikasi Hash Kosong pada MalwareScannerModule**:
  - [MalwareScannerModule.java:168](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/scanner/MalwareScannerModule.java#L168): Pengecekan integritas berbunyi: `if (!pinnedSha256.isBlank() && !pinnedSha256.equalsIgnoreCase(actualHash))`.
  - Jika admin belum menyetel `pinned-sha256` di `config.yml` (default adalah string kosong `""`), pengecekan hash dilewati, dan proses eksternal tetap menjalankan JAR apa pun yang diletakkan di folder scanner tanpa verifikasi integritas. *(Terverifikasi - P1)*
- **False-Positive Parsing Output Malware Scanner**:
  - [MalwareScannerModule.java:220-223](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/scanner/MalwareScannerModule.java#L220-L223): Baris output yang mengandung substring `"detected"`, `"threat"`, `"malware"` langsung dimasukkan ke `detectedThreats`.
  - Output standar scanner seperti `"No threats detected"` atau `"Malware definitions: 4500"` akan ditangkap sebagai ancaman aktif (`ScanStatus.DETECTED`). *(Terverifikasi - P1)*
- **Jeda De-op OpGuard di Tick 0**:
  - [OpGuardModule.java:165, 192](file:///workspaces/GuardPlugin-Project/SaryuGuard/src/main/java/id/saryu/saryuguard/opguard/OpGuardModule.java#L165): `onPlayerLogin` (priority MONITOR) menjadwalkan task sinkron de-op untuk tick selanjutnya. Pemain tidak sah yang memiliki flag OP berpotensi memiliki hak OP singkat pada tick 0 saat pertama kali join. *(Terverifikasi - P2)*

### 4.9 Config & Locale
- **Kredensial Default**:
  - `credentials.toml` dan `secret.toml` telah diperiksa. Keduanya hanya berisi template string kosong (tidak ada token atau passphrase produksi nyata). *(Terverifikasi - Aman)*
- **Duplikasi & File Usang di Locales**:
  - Folder `SaryuGuard/src/main/resources/locales/` memiliki file `id_ID.yml` dan `en_US.yml` selain `id.yml` dan `en.yml`.
  - `id_ID.yml` dan `en_US.yml` menggunakan struktur hierarki lama (`plugin:`, `commands:`) serta kode warna `&a` / `&7`. Keduanya tidak digunakan oleh `LocaleManager` (yang memuat `id.yml` dan `en.yml`). *(Terverifikasi - P2)*
- **Kunci Bahasa Dipakai Tetapi Hilang di File YAML**:
  - `chat.filtered-notice` dan `chat.error-blocked` dipakai di `ChatFilterModule.java:212, 224`, tetapi tidak didefinisikan di `id.yml` maupun `en.yml`.
  - `core.usage`, `core.cooldown`, dan `core.error` dipanggil di `SaryuGuardCommand.java`, tetapi tidak ada di file konfigurasi bahasa.
  - Subcommand `AuditSubcommand`, `CryptSubcommand`, `ScanSubcommand`, `OpGuardSubcommand` menggunakan string hardcoded berbahasa Indonesia melalui `parseComponent()` alih-alih mengambil dari `LocaleManager`. *(Terverifikasi - P1)*

---

## 5. Rekomendasi Perbaikan Konkret

1. **TwoFactorModule**:
   - Ganti `Arrays.equals` di `TotpEngine.constantTimeEquals` menjadi `MessageDigest.isEqual`.
   - Hapus short-circuit perbandingan integer di `TotpEngine.verify`.
   - Tambahkan verifikasi recovery code di `TwoFactorModule.onChat`: jika panjang input > 6 dan bukan digit, periksa terhadap `recoveryHashes` menggunakan `RecoveryCodes.verify(input, hash)`.
   - Di `TwoFactorModule.onPlayerJoin`, buat session `PENDING_MFA` di thread sinkron secara instan sebelum menjalankan query DB async.
   - Panggil `twoFactorDao.updateLockState(conn, uuid, true, attempts)` saat status akun dikunci.
   - Simpan `lastUsedCounter` per UUID untuk mencegah replay TOTP dalam jendela waktu yang sama.
2. **AntiChatManager Lite**:
   - Ubah default `blacklist.bypass.allow-op` menjadi `false` di `LiteCommandListener.java` dan `config.yml`.
   - Pastikan emergency blacklist tidak pernah mengizinkan bypass OP dalam status kebijakan `UNAVAILABLE`.
   - Kirim token `"status", statusColor` pada pemanggilan `formatMessage("version-info", ...)` di `ACMCommandLite`.
3. **AuditModule**:
   - Jangan mulai verifikasi rantai selalu dari `GENESIS_HASH`. Simpan record checkpoint/anchor tertua saat retensi menghapus baris lama, atau verifikasi integritas mulai dari baris pertama yang masih ada di tabel terhadap checkpoint tersimpan.
   - Lakukan escaping karakter pipe `\|` (misal `\|` menjadi `\\|`) pada `AuditChain.canonicalBytes`.
4. **ConnectionGuardModule**:
   - Implementasikan pembacaan `connection-guard.proxy.enabled` dan `trusted-proxies`. Jika IP pengirim adalah trusted proxy, evaluasi IP asli dari header/Paper proxy forwarding.
   - Tambahkan peringatan jika proxy terdeteksi tetapi IP forwarding tidak aktif.
   - Ganti `accountsPerIp.clear()` dengan pembersihan berbasis TTL (eviction tertua) agar tidak terjadi bypass massal.
   - Tambahkan listener `PluginDisableEvent` untuk menangani failover runtime (`compat.on-missing: full`) jika Sonar atau AuthMe dinonaktifkan.
5. **CryptService & ConfigManager**:
   - Terapkan izin file `0600` (`PosixFilePermissions`) saat membuat `secret.key`.
   - Validasi keberadaan database sebelum menimpa `secret.key` yang hilang: jika tabel dengan data terenkripsi sudah ada tetapi `secret.key` hilang, hentikan startup dengan ERROR kritis alih-alih membuat kunci acak baru yang merusak data.
   - Gunakan Argon2id KDF untuk mode passphrase di `ConfigManager` sesuai dengan spesifikasi di PLAN.md.
6. **PluginVerifyModule & MalwareScanner**:
   - Samakan definisi skema `sg_plugin_baseline` antara `PluginVerifyModule` dan `SaryuGuardDatabase`, dan gunakan pool database milik `SaryuGuardDatabase`.
   - Wajibkan `pinned-sha256` tidak boleh kosong saat menjalankan pemindaian eksternal pada `MalwareScannerModule`.
   - Perbaiki deteksi substring malware agar output informasional/bersih tidak memicu status `DETECTED`.
7. **Pembersihan Bloatware & Locale (Fase 2 Persiapan)**:
   - Hapus file duplikat usang `locales/id_ID.yml` dan `locales/en_US.yml`.
   - Lengkapi kunci bahasa yang hilang di `id.yml` dan `en.yml`.

---

## 6. Daftar Tes yang Hilang (Automated Tests Backlog)

Untuk memastikan keandalan sistem sebelum rilis, pengujian berikut wajib ditambahkan ke test suite:

1. `TwoFactorTest`:
   - `testConstantTimeEqualityNonShortCircuit`: Pengujian perbandingan string untuk mencegah timing side-channel.
   - `testRecoveryCodeChatAuthentication`: Simulasi input recovery code pada `TwoFactorModule` yang mencocokkan hash Argon2id dan menghapus kode yang sudah terpakai.
   - `testTotpReplayPrevention`: Pengujian bahwa kode TOTP yang sama ditolak jika dimasukkan dua kali dalam jendela waktu 30-90 detik.
   - `testImmediatePendingMfaOnJoin`: Verifikasi bahwa pemain berizin staf langsung berada pada status `PENDING_MFA` pada tick join pertama tanpa celah eksekusi perintah.
   - `testLockoutPersistenceAcrossReload`: Pengujian bahwa status `LOCKED` tersimpan ke SQLite/H2 dan tetap aktif setelah modul di-reload.
2. `AuditChainTest`:
   - `testAuditChainVerificationAfterRetention`: Pengujian rantai verifikasi hash setelah sejumlah entri lama dihapus oleh retention cleaner.
   - `testCanonicalBytesDelimiterEscaping`: Pengujian payload audit yang mengandung karakter pipe `|` tidak merusak struktur kanonis.
3. `CryptServiceTest`:
   - `testSecretKeyFilePermissions`: Memverifikasi file `secret.key` dibuat dengan atribut POSIX `0600` pada sistem Unix.
   - `testMissingSecretKeyFailsGracefullyWithExistingDb`: Memverifikasi SaryuGuard menolak membuat kunci acak baru jika DB sudah berisi data terenkripsi.
   - `testArgon2idPassphraseDerivationMatchesSpec`: Memverifikasi kunci yang diturunkan dari passphrase menggunakan Argon2id bukan SHA-256 tunggal.
4. `ConnectionGuardTest`:
   - `testProxyIpForwardingAndAccountLimits`: Pengujian multi-koneksi di balik proxy terpercaya.
   - `testAccountsPerIpBoundedEviction`: Memverifikasi struktur cache tidak melakukan `clear()` massal saat penuh.
   - `testRuntimeFailoverWhenThirdPartyAntiCheatDisabled`: Memverifikasi perlindungan kembali ke internal saat plugin rekanan di-disable.
5. `MalwareScannerTest`:
   - `testBlankPinnedSha256RejectsScan`: Menolak eksekusi jika `pinned-sha256` belum diisi.
   - `testInformationalOutputDoesNotTriggerDetection`: Memverifikasi output seperti "No threats detected" menghasilkan `ScanStatus.OK`.
6. `AntiChatManagerTest`:
   - `testEmergencyBlacklistBlocksOpWhenUnavailable`: Memverifikasi akun OP tetap diblokir dari `/stop` dan `/op` saat status `UNAVAILABLE`.
   - `testMigrationBackupIncludesLanguagesFolder`: Memverifikasi folder `languages/` ikut tersalin ke `backup-3.x/`.

---

## 7. Lampiran: Log Output Gradle Build & Checks

```text
$ ./gradlew build --no-daemon
> Task :SaryuLibCore:compileJava UP-TO-DATE
> Task :AntiChatManager:compileJava UP-TO-DATE
> Task :AntiChatManager:generateBuildInfo UP-TO-DATE
> Task :AntiChatManager:processResources UP-TO-DATE
> Task :AntiChatManager:classes UP-TO-DATE
> Task :AntiChatManager:jar UP-TO-DATE
> Task :AntiChatManager:sourcesJar UP-TO-DATE
> Task :AntiChatManager:assemble UP-TO-DATE
> Task :scanForbiddenApis
> Task :AntiChatManager:compileTestJava UP-TO-DATE
> Task :AntiChatManager:processTestResources NO-SOURCE
> Task :AntiChatManager:testClasses UP-TO-DATE
> Task :SaryuLibCore:generateBuildInfo UP-TO-DATE
> Task :SaryuLibCore:processResources UP-TO-DATE
> Task :SaryuLibCore:classes UP-TO-DATE
> Task :SaryuLibCore:jar UP-TO-DATE
> Task :AntiChatManager:test UP-TO-DATE
> Task :AntiChatManager:check
> Task :AntiChatManager:build
> Task :SaryuGuard:compileJava UP-TO-DATE
> Task :SaryuGuard:generateBuildInfo UP-TO-DATE
> Task :SaryuGuard:processResources UP-TO-DATE
> Task :SaryuGuard:classes UP-TO-DATE
> Task :SaryuGuard:jar UP-TO-DATE
> Task :SaryuGuard:shadowJar UP-TO-DATE
> Task :SaryuGuard:sourcesJar UP-TO-DATE
> Task :SaryuGuard:assemble UP-TO-DATE
> Task :SaryuGuard:compileTestJava UP-TO-DATE
> Task :SaryuGuard:processTestResources NO-SOURCE
> Task :SaryuGuard:testClasses UP-TO-DATE
> Task :SaryuGuard:test UP-TO-DATE
> Task :SaryuGuard:check
> Task :SaryuGuard:build
> Task :SaryuLibCore:sourcesJar UP-TO-DATE
> Task :SaryuLibCore:assemble UP-TO-DATE
> Task :SaryuLibCore:compileTestJava UP-TO-DATE
> Task :SaryuLibCore:processTestResources NO-SOURCE
> Task :SaryuLibCore:testClasses UP-TO-DATE
> Task :SaryuLibCore:test UP-TO-DATE
> Task :SaryuLibCore:check
> Task :SaryuLibCore:build

BUILD SUCCESSFUL in 1s
23 actionable tasks: 1 executed, 22 up-to-date
```
