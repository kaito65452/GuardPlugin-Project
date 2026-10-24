# PANDUAN PENGUJIAN SERVER & DAFTAR PERIKSA (FASE 4)

**Ekosistem**: `SaryuLibCore`, `AntiChatManager Lite (4.0.0)`, `SaryuGuard (4.0.0)`  
**Platform Target**: PaperMC `1.21.x` (Java 21+) & PaperMC `26.x` (Java 25)  
**Terakhir Diperbarui**: 24 Oktober 2026  

---

## 1. Skrip Peluncuran Server & Persiapan

### A. Persyaratan Runtime
- **Paper 1.21.x**: Memerlukan minimal Java 21 LTS (`--release 21`).
- **Paper 26.x (26.2 build 134)**: Memerlukan Java 25.
- **Flag JVM Native Access (Penting untuk Java 25)**:
  SQLite JDBC driver memanggil `System.load()` untuk library native C/JNI. Pada Java 25, restricted method ini akan menampilkan peringatan keamanan JVM kecuali diizinkan secara eksplisit via:
  ```bash
  --enable-native-access=ALL-UNNAMED
  ```

### B. Menjalankan Server Pengujian
Skrip siap pakai telah disediakan di `testing-server/run.sh`:

```bash
# 1. Pastikan seluruh plugin telah di-compile
./gradlew build --no-daemon

# 2. Masuk ke folder testing server dan jalankan
cd testing-server
./run.sh
```

Isi dari `testing-server/run.sh`:
```bash
#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

exec java \
    -Xms2G -Xmx2G \
    --enable-native-access=ALL-UNNAMED \
    -XX:+UseG1GC \
    -Dfile.encoding=UTF-8 \
    -jar paper-26.2-134.jar \
    --nogui
```

---

## 2. Matriks Kompatibilitas Versi yang Diuji

| Komponen | Versi Diuji | Lingkungan Java | Status Kompatibilitas |
|---|---|---|---|
| **PaperMC 26.x** | `26.2-134` | OpenJDK 25.0.4.1 (Java 25) | [X] LULUS (Semua plugin aktif tanpa error) |
| **PaperMC 1.21.x** | `1.21.4` | OpenJDK 21 LTS / Java 25 | [X] LULUS (Bytecode compiled `--release 21`) |
| **SQLite (JDBC)** | `3.53.4.0` | Java 21 / Java 25 | [X] LULUS (Bebas peringatan dengan native-access) |
| **H2 Database** | `2.5.252` | Java 21 / Java 25 | [X] LULUS (In-memory & file persist) |
| **LuckPerms** | `5.5.71` | Paper 26.x & 1.21.x | [X] LULUS (Context calculator `saryuguard:2fa=true`) |
| **AuthMe Reloaded** | `6.0.1` | Paper 26.x & 1.21.x | [X] LULUS (AuthMe login hook & auth cmd ignore) |
| **Sonar Anti-Cheat** | `Paper` | Paper 26.x & 1.21.x | [X] LULUS (Delegasi proteksi koneksi & ping) |
| **GrimAC** | `2.3.74` | Paper 26.x & 1.21.x | [X] LULUS (Deteksi anti-cheat aktif di CompatService) |
| **EssentialsX** | `2.22.0` | Paper 26.x & 1.21.x | [X] LULUS (NameResolver OpGuard lookup) |

---

## 3. Daftar Periksa Uji Fungsional & Keamanan (Checklist)

Tandai `[X]` untuk Lulus, `[ ]` untuk Belum/Gagal.

### Bagian A: SaryuGuard Core Framework & UI Paginator
- [X] **Core Lifecycle**: SaryuGuard enabled dengan status `ACTIVE` pada server startup (`/sg status`).
- [X] **Help & Version**: `/sg help` menampilkan menu interaktif bilingual, `/sg version` menampilkan commit hash dan channel (`dev`/`stable`).
- [X] **Paginator Navigation**: UI Paginator menampilkan `[« Sebelumnya] Halaman x/y [Berikutnya »]` yang dapat diklik dan menjalankan suggestion command secara aman.
- [X] **Reload Configuration**: `/sg reload` memuat ulang `config.yml`, `words.yml`, dan `locales/{id,en}.yml` tanpa restart server.

### Bagian B: AntiChatManager Lite 4.0.0
- [X] **Zero Runtime DB Query**: Blacklist command dicocokkan langsung dari in-memory immutable snapshot via `AtomicReference`.
- [X] **Command Interception**: Intersepsi `PlayerCommandPreprocessEvent` pada prioritas `LOWEST` dengan `ignoreCancelled=false`.
- [X] **Normalisasi Command**: Variasi input `/op `, `/OP`, `/minecraft:op`, `//op` dinormalisasi secara seragam dan diblokir.
- [X] **Manajemen Blacklist**:
  - `/acm blacklist add <cmd>`: Menambah command ke daftar blokir dan menyimpan ke SQLite/MySQL.
  - `/acm blacklist remove <cmd>`: Menghapus command dari daftar blokir.
  - `/acm blacklist info [page]`: Menampilkan command terdaftar dengan pagination.
- [X] **Policy Status Fallback**:
  - `READY`: Snapshot in-memory sinkron dengan database.
  - `DEGRADED`: Saat database offline, cache terakhir tetap aktif melayani pemblokiran dengan throttled alert ke admin.
  - `UNAVAILABLE`: Jika cache kosong & database mati, fallback darurat mengaktifkan `emergency-blacklist` (`op, deop, stop, reload, restart`).
- [X] **Idempotent 3.x Migration**: Migrasi dari database 3.0.2 berjalan otomatis, mencadangkan file lama ke `backup-3.x/`, tidak menghapus data lama, dan melewati migrasi jika marker sudah ada.

### Bagian C: Staff 2FA & Otentikasi Dua-Faktor
- [X] **OOB Setup**: Staf menjalankan `/sg 2fa setup`, menerima 32-karakter Base32 secret dan URI `otpauth://`.
- [X] **Constant-Time Verification**: Verifikasi kode 6-digit TOTP menggunakan `MessageDigest.isEqual` untuk mencegah timing attack.
- [X] **Progressive Lockout & Rate Limit**: Percobaan TOTP salah memicu penundaan progresif (backoff) dan status `LOCKED` setelah batas maksimum percobaan.
- [X] **Replay Code Prevention**: Kode TOTP yang sama tidak dapat digunakan kembali dalam jendela waktu yang sama.
- [X] **Argon2id Recovery Codes**: Pemain dapat login menggunakan salah satu dari 5 recovery codes jika kehilangan authenticator. Kode di-hash dengan Argon2id (`$argon2id$v=19$...`) dan dihapus setelah 1x pemakaian.
- [X] **Privilege Gating**: Pemain dalam status `PENDING_MFA` tidak dapat bergerak, chat, mengetik command, atau mengakses izin staf sebelum memasukkan kode 2FA yang benar.
- [X] **Admin Reset**: Konsol dapat mereset 2FA pemain terkunci via `/sg 2fa reset <player>`.

### Bagian D: OpGuard & Baseline Operator
- [X] **UUID-based Baseline**: Pemain OP diverifikasi berdasarkan UUID, bukan display name.
- [X] **Multi-Source Name Resolution**: Resolusi nama lokal memeriksa LuckPerms, EssentialsX, dan `ops.json` secara konsisten tanpa blocking web request ke Mojang.
- [X] **Enforce Mode**: Pemain non-whitelisted yang menerima OP langsung di-deop di main thread dan alert disiarkan ke admin.
- [X] **Warn Mode**: Pelanggaran OP hanya membroadcast peringatan ke console dan staf tanpa de-op paksa.
- [X] **Manajemen OpGuard**:
  - `/sg opguard add <player|uuid>`: Menambahkan pemain ke baseline OP sah.
  - `/sg opguard remove <player|uuid>`: Menghapus pemain dan otomatis men-deop.
  - `/sg opguard list`: Menampilkan daftar seluruh operator yang sah.
  - `/sg opguard import`: Mengimpor seluruh operator dari `ops.json` server.

### Bagian E: Chat Filter & Normalisasi Teks
- [X] **Unicode Normalization**: Diacritic stripping dan Unicode NFKC mencegah bypass dengan karakter modifikasi.
- [X] **Homoglyph & Leet Substitution**: Karakter Cyrillic/Greek lookalikes dan angka leet (`0`->`o`, `1`->`i`, `3`->`e`, `@`->`a`) dinormalisasi sebelum dicocokkan.
- [X] **Aho-Corasick Engine**: Pencarian kata terlarang berjalan dalam kompleksitas linear O(n) terhadap panjang pesan.
- [X] **ReDoS Immunity (RE2J Only)**: Pola regex admin dieksekusi secara eksklusif menggunakan pure-Java `RE2J` tanpa risiko catastrophic backtracking.
- [X] **Offender Isolation**: Pesan yang melanggar diisolasi (`event.viewers().removeIf(...)`) sehingga hanya pengirim yang melihat pesannya sendiri (shadow-block) tanpa membatalkan dan mengirim ulang.
- [X] **Whitelisting**: Kata aman (seperti bahasa Indonesia yang mengandung substring mirip kata terlarang) dilindungi oleh kamus whitelist.

### Bagian F: Plugin Verify & Integritas Jar
- [X] **SHA-256 Hashing**: Menghitung hash SHA-256 seluruh jar di direktori `plugins/`.
- [X] **Local Baseline Approval**: Jar yang belum disetujui berstatus `UNVERIFIED`/`UNKNOWN` sampai admin menjalankan `/sg plugins approve <plugin>`.
- [X] **Hash Mismatch Detection**: Modifikasi pada jar yang sudah disetujui langsung terdeteksi sebagai `MISMATCH` dan memicu alert keamanan tinggi.
- [X] **Modrinth API Lookup (Opt-in)**: Jika diaktifkan, hash jar dicocokkan dengan Modrinth API untuk menetapkan status `VERIFIED`.

### Bagian G: Audit Forensik & Hash Chain
- [X] **Single-Writer Asynchronous Queue**: Event audit dicatat tanpa membebani tick server utama.
- [X] **Cryptographic Hash Chain**: Tiap log entry diikat dengan formula `hash = SHA-256(prev_hash || canonical_bytes)`.
- [X] **Chain Verification**: Perintah `/sg audit verify` memvalidasi integritas matematika seluruh rantai log dari genesis hingga head.
- [X] **Data Redaction**: Argumen command otentikasi (`/login`, `/register`, `/2fa`) otomatis disamarkan (`[REDACTED]`).
- [X] **Graceful Shutdown**: Draining antrian audit selesai dalam waktu kurang dari 5 detik saat server dimatikan.

### Bagian H: Connection Guard & Proteksi Jaringan
- [X] **Pre-Login IP Throttle**: Menolak koneksi beruntun dari IP yang sama dalam interval agresif.
- [X] **Ping Rate Limit**: Membatasi flooding `ServerListPingEvent` dari bot ping.
- [X] **CGNAT Tolerant Account Limit**: Batas akun per IP fleksibel (`max-accounts-per-ip`) agar pemain di jaringan CGNAT/warnet tidak terblokir.
- [X] **Dynamic Compat Delegation**: Jika Sonar terdeteksi, proteksi koneksi didelegasikan ke Sonar; jika AuthMe terdeteksi, proteksi username/akun didelegasikan ke AuthMe.

### Bagian I: Malware Scanner Wrapper
- [X] **ProcessBuilder Execution**: Menjalankan MCAntiMalware jar eksternal secara mandiri tanpa shell script (`sh -c` / `cmd /c`).
- [X] **Pinned SHA-256 Validation**: Eksekusi dibatalkan jika hash jar scanner tidak cocok dengan `pinned-sha256` di `config.yml`.
- [X] **Execution Constraints**: Parameter `-Xmx512M` eksplisit, timeout 120 detik, dan buffer output dibatasi 512 KB untuk mencegah memory exhaustion.

### Bagian J: Enkripsi Kolom Database & Rotasi Kunci
- [X] **AES-256-GCM**: Kolom sensitif (secret 2FA, data audit) dienkripsi dengan AES-256-GCM.
- [X] **Unique Nonce & AAD**: 12-byte random IV per enkripsi dan AAD mengikat `table:rowId:colName:keyVersion`.
- [X] **Key Permissions**: File `secret.key` otomatis dilindungi izin akses POSIX `0600`.
- [X] **Console-Only Key Rotation**: Perintah `/sg crypt rotate` (hanya via konsol) mengenkripsi ulang seluruh database secara transaksional dengan era kunci baru.

---

## 4. Langkah Pengujian Manual Langkah demi Langkah (Smoke Test)

### Uji 1: AntiChatManager Blacklist & OP Bypass
1. Masuk ke server dengan akun non-OP.
2. Ketik `/op`.
   - **Hasil Diharapkan**: Perintah diblokir, pesan terlarang dikirim ke pemain, dan peringatan muncul di konsol.
3. Berikan izin OP ke akun tersebut di konsol (`op <player>`).
4. Ulangi `/op`.
   - **Hasil Diharapkan**: Perintah diizinkan jika `blacklist.bypass.allow-op: true`, atau diblokir jika disetel `false`.
5. Ketik `/minecraft:op`, `//op`, `/OP`.
   - **Hasil Diharapkan**: Semua variasi nama dan namespace dinormalisasi dan diblokir.

### Uji 2: SaryuGuard 2FA Setup & Login Lock
1. Masuk sebagai pemain yang memiliki izin staf (misal memiliki permission `saryuguard.2fa.enforce` atau OP).
2. Jalankan `/sg 2fa setup`.
3. Simpan 5 recovery codes yang diberikan dan masukkan kode 6-digit dari Google Authenticator / Aegis.
4. Keluar dari server (`quit`), lalu masuk kembali (`join`).
5. Coba bergerak, mengetik pesan chat bebas, atau command lain.
   - **Hasil Diharapkan**: Gerakan terkunci, chat dan command diblokir dengan pesan meminta input 2FA.
6. Ketik kode TOTP yang benar di chat.
   - **Hasil Diharapkan**: Otentikasi berhasil, status beralih ke `AUTHENTICATED`, dan kontrol karakter pulih.

### Uji 3: OpGuard Enforce Mode
1. Pastikan `opguard.mode: enforce` di `plugins/SaryuGuard/config.yml`.
2. Di konsol server, ketik `op <unwhitelisted_player>`.
3. Dalam interval pengecekan (default 60 detik) atau saat pemain tersebut login:
   - **Hasil Diharapkan**: OpGuard mendeteksi pemain tidak terdaftar di baseline sah, otomatis men-deop pemain di main thread, dan menyiarkan peringatan ke console & admin.
4. Ketik `/sg opguard add <unwhitelisted_player>`.
5. Ketik kembali `op <player>`.
   - **Hasil Diharapkan**: Pemain sekarang sah dan status OP dipertahankan.

### Uji 4: Chat Filter Homoglyph & Leet Evasion
1. Sebagai pemain reguler, ketik kata terlarang normal: `anjing`.
   - **Hasil Diharapkan**: Pesan diblokir, hanya terlihat oleh pengirim (shadow-block), dan peringatan dikirim.
2. Coba bypass dengan leet speak: `@nj1ng`, `4nj1ng`.
   - **Hasil Diharapkan**: Filter mendeteksi substitusi leet dan memblokir pesan.
3. Coba bypass dengan karakter berulang: `aannnjiinggg`.
   - **Hasil Diharapkan**: Collapse repeat karakter mendeteksi kata dasar dan memblokir pesan.

### Uji 5: Audit Forensics Chain Verification
1. Jalankan perintah `/sg audit 1` untuk melihat log forensik terkini.
2. Jalankan `/sg audit verify`.
   - **Hasil Diharapkan**: Sistem memverifikasi SHA-256 hash chain dari record pertama sampai head, mengembalikan pesan hijau bahwa seluruh rantai log kriptografis utuh tanpa modifikasi.
