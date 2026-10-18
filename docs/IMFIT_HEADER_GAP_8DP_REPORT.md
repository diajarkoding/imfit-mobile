# Header gap 8 dp dan avatar Gerakan

Revisi pengguna 18–19 Oktober 2026, branch `redisgn`, baseline
`3ec72efbf43dc8b749aa3e4ae413a4a0373d8fa7` dengan perubahan lokal yang sudah ada.
Aturan ini **menggantikan** gap main-tabs 12 dp dan revisi 0 dp dalam laporan polish lama;
bukti lama tetap historis, bukan hasil runtime untuk revisi ini.

## Implementasi

- Satu token `IMFITSpacing.headerContentGap = 8.dp`: Home, Gerakan, Progres, Profil,
  Register, katalog/pemilih gerakan, Detail/Edit rutinitas, sesi aktif, ringkasan,
  riwayat per tanggal, kalender tahunan, dan legacy CreateTemplate (tanpa route baru).
- Padding konten atas sebelumnya diganti, bukan ditambah. Padding atas identity Profil,
  shimmer/loading sesi/ringkasan, outcome ringkasan, dan spacer awal legacy disesuaikan
  agar konten pertama tidak mendapat gap ganda. Jarak antarkonten dan padding bawah tetap.
- Appbar internal padding, status/navigation insets, IME, footer, draft, callback,
  repository, navigasi, permission policy dan timer engine tidak diubah oleh revisi gap ini.
- `MainScreen.showProfileAction` hanya true pada Home: avatar serta target klik Gerakan
  hilang; avatar Home dan baris profil Progres tetap.
- Dialog/bottom sheet dan file Pencil tidak diubah oleh revisi ini.

## Verifikasi

- **PASS:** compile demo/production, unit test demo/production (164 + 157 = 321),
  compile instrumentation dan assemble diagnostic APK.
- UI/layout, lint dan pemeriksaan preservasi dicatat setelah eksekusi selesai.

Evidence lokal (bukan file yang di-commit):
`/private/var/folders/yv/171k2lgd0jdg4y0jclvxjtzw0000gn/T/opencode/imfit-header-gap8-20261018/`.
Baseline source hashes/diff dan APK/settings emulator dicatat sebelum edit. Hanya paket
diagnostic `.modaldiagnostic` digunakan untuk runtime; aplikasi biasa tidak di-upgrade.

## Batas bukti

Pengukuran Compose/instrumentation bukan persetujuan visual pengguna atau bukti OEM.
Realme fisik, API lain dan investigasi provenance screenshot lama tidak diverifikasi ulang.
Legacy CreateTemplate tidak diaktifkan hanya untuk test. Laporan modal/native-dim lama
tetap historis; revisi spacing tidak menyatakan akar masalah backdrop lama terselesaikan.
