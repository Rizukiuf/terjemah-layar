# Terjemah Layar

Aplikasi Android untuk menerjemahkan isi layar **dengan satu ketukan**, tanpa tekan-tahan bar navigasi.

- Tombol mengambang **文A** di tepi layar → ketuk → teks di layar langsung tertimpa terjemahannya.
- Membaca teks (OCR) dan menerjemahkan **di dalam HP** (Google ML Kit). Internet hanya dipakai sekali untuk mengunduh paket bahasa (±30 MB per bahasa).
- Ketuk hasil = tutup · tahan = intip teks asli · **Daftar** = semua teks asli + terjemahan · **Salin** · **Lens** = kirim gambar layar ke Google untuk bertanya soal isinya.
- Alternatif pemicu: tile "Terjemah Layar" di Pengaturan Cepat (panel notifikasi), atau tombol aksesibilitas sistem.
- Butuh Android 11 atau lebih baru.

## Cara mendapatkan APK (tanpa install apa pun di komputer) — via GitHub

1. Buat akun di github.com (gratis) lalu buat repository baru, mis. `terjemah-layar`.
2. Unggah seluruh isi folder ini ke repository tersebut (di komputer: **Add file → Upload files**, seret semua file & folder, termasuk folder `.github`).
3. Buka tab **Actions** → tunggu proses "Buat APK" selesai (±5–8 menit, tanda centang hijau).
4. Buka halaman **Releases** di repository → unduh `TerjemahLayar.apk` dari HP.

## Cara lain — Android Studio

Buka folder ini di Android Studio → **Build → Build App Bundle(s)/APK(s) → Build APK(s)**.
APK ada di `app/build/outputs/apk/debug/app-debug.apk`.

## Memasang di HP (Tecno / HiOS)

1. Buka file `TerjemahLayar.apk` → izinkan "Instal aplikasi tidak dikenal" untuk browser/file manager bila diminta → **Instal**.
   Jika Play Protect memberi peringatan, pilih **Detail lainnya → Tetap instal** (aplikasi ini dibuat sendiri, bukan dari Play Store).
2. Buka aplikasi **Terjemah Layar** → **Buka pengaturan Aksesibilitas** → pilih **Terjemah Layar** → nyalakan.
3. Jika tombol tidak bisa dinyalakan / muncul "Setelan terbatas":
   **Info Aplikasi** → ⋮ (kanan atas) → **Izinkan setelan terbatas** → ulangi langkah 2.
4. Pilih bahasa tujuan (default: Indonesia) dan jenis huruf teks sumber (Latin / Mandarin / Jepang / Korea).
5. Agar tombol tidak berhenti sendiri di HiOS: **Info Aplikasi → Baterai → Tanpa batasan**, dan kunci aplikasi di daftar aplikasi terbaru.

## Catatan

- Aplikasi bank/streaming yang melarang tangkapan layar tidak bisa diterjemahkan (layar akan hitam/ditolak) — ini aturan Android.
- Teks yang sudah berbahasa tujuan dibiarkan apa adanya.
- Kunci tanda tangan (`app/debug.keystore`) sengaja disertakan supaya versi baru bisa dipasang menimpa versi lama tanpa menghapus aplikasi.
