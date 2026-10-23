# Enam issue data latihan dan notifikasi — fix-bug

20 Oktober 2026. Baseline `37bfdf9` pada branch `fix-bug`, working tree awal bersih.
Perbaikan awal pada baseline dipertahankan; perubahan fase ini belum di-commit/push.

## Kontrak hasil

1. **Refresh data:** katalog/picker, profil/Home/Progres, template/detail/editor yang bersih,
   ringkasan/riwayat/kalender membaca sumber lokal reaktif. Refresh remote saat foreground,
   pergantian route/tab, dan katalog dibuka; entry berulang dikoaleskan (30 detik).
   Koneksi pulih dan antrean upload tetap ditangani SyncManager/WorkManager.
2. **Log selesai:** hanya gerakan dengan >=1 completed set dan hanya completed sets yang
   disimpan. Sesi tanpa completed set tidak dihapus/disubmit. Snapshot finish dan terminal
   guard kini diambil dalam session mutex yang sama agar edit terlambat tidak menyusup.
3. **Volume:** 0 untuk input/prefill yang belum selesai; bertambah menurut weight × reps
   setiap set selesai. Model completed-only dari baseline dipertahankan.
4. **Auto-fill:** target set/reps asal dicatat sebelum input performa diisi. Konfigurasi
   yang sama mengambil performa terakhir per exercise ID dan owner; konfigurasi gerakan
   yang berubah menggunakan default terbaru. Jumlah set mengikuti template. Tambah set
   manual memakai weight 0, reps target, status belum selesai. Rename tidak membatalkan
   auto-fill. Resume tidak mengulang prefill. Data lama/remote tanpa provenance memakai
   default secara konservatif, bukan menebak target dari reps aktual.
5. **Notifikasi:** ketukan SystemUI membuka sesi yang sama sekaligus acknowledge alarm;
   Stop acknowledge dari background tanpa membuka aplikasi. Timer ID/generation guards
   dan engine deadline/recovery tetap. Perilaku dari baseline diuji ulang, bukan diganti.
6. **Tema:** native notification mengikuti sistem. Putih ketika hanya IMFIT dark tetapi
   Android light adalah perilaku yang disepakati, bukan bug. Tidak membuat RemoteViews,
   memaksa background, atau mengubah pengaturan/dialog/RestAlarmActivity.

## Implementasi sinkronisasi

- `ExerciseRepositoryImpl`: Room Flow, bukan cache list. Tidak menyisipkan data demo ketika
  DB/kategori/query production kosong atau query gagal; fixture hanya milik demo flavor.
- Master refresh terpisah dari upload workout. Master download mencakup `image_url`,
  berhalaman dan diterapkan satu transaksi; record yang hilang dinonaktifkan tanpa
  menghapus referensi historis. Kegagalan request tidak membersihkan katalog lokal.
- `SyncManager` melakukan pull owner-scoped dengan pagination, lalu memeriksa ulang versi
  parent setelah mengambil children. `RemoteSnapshotImporter` menerapkan satu aggregate
  lengkap secara transaksional, memperbarui children lama dan menghapus children yang
  sudah dihilangkan dari snapshot. Parent/children lokal pending atau lebih baru dilindungi.
- User/profile dipersistenkan ke tabel `users`, diamati Home/Progres/Profil, dan cache
  identitas dibatasi owner. ID lokal untuk timer tetap tidak memerlukan lookup profil remote.
- Summary sekarang observer; editor menerima pembaruan hanya selama draft masih bersih.
  Query, filter dan selection picker dipertahankan saat Room memancarkan pembaruan.
- Logout menunggu full sync dan master refresh selesai sebelum pembersihan data owner.
- Room **6 → 7** hanya menambah kolom nullable `exercise_logs.input_defaults`. Aktif JSON
  menambahkan field optional dengan default null. Tidak ada destructive migration atau
  perubahan schema/RLS/RPC Supabase.

## Batas penting: jangan menyatakan seluruh integrasi Supabase sudah terbukti

- Pengujian memakai Room in-memory, fixture demo, dan paket `.modaldiagnostic`, bukan akun
  produksi. Perubahan dashboard Supabase → mobile, retry jaringan nyata, RLS dan upload
  pada proyek Supabase yang digunakan pengguna masih perlu uji end-to-end terotorisasi.
- Client template upload lama dipertahankan. Sesuai
  `docs/backend/imfit-supabase-compose-integration.md`, jangan beralih diam-diam ke template
  RPC: identitas remote child dan pruning belum cocok. Template upload lama bukan transaksi
  remote tunggal. Workout upload tetap memakai aggregate RPC yang sudah ada.
- Perubahan children dari backend harus mengikuti kontrak versi parent; client tidak
  menjamin snapshot lintas request jika admin mengubah children tanpa memperbarui parent.
- Soft-delete/tombstone template dan history diproses. Hard-delete parent tanpa tombstone
  tidak ditafsirkan sebagai izin menghapus semua data lokal; perbaikan protokol tersebut
  memerlukan kontrak backend terpisah.
- Provenance auto-fill lokal tidak disinkronkan sebagai kolom baru ke Supabase. Riwayat
  yang baru diunduh pada perangkat lain tetap memakai default sampai tersedia sesi dengan
  provenance yang tepercaya pada perangkat itu. Tidak ada pembersihan massal riwayat lama.

## Verifikasi

- **PASS compile:** demo/production debug Kotlin dan demo instrumentation; assemble APK
  diagnostic terisolasi. `final-compile-unit.log` dan `final-apk-build.log`.
- **PASS unit:** 166 demo + 159 production = **325**, nol failure/error/skipped.
- **BLOCKED full lint:** dua `NonObservableLocale` yang sudah ada sebelumnya di
  `AuthBirthDatePicker.kt:48` dan `RegisterScreen.kt` (pemakaian `Locale.getDefault()`).
  Tidak ada suppression atau perbaikan auth UI yang tidak diminta.
- **PASS source preservation:** 22 source timer/notification/MainActivity/manifest/theme
  yang dilindungi identik terhadap baseline; timer engine dan alarm UI tidak diedit.
- **PASS instrumentation API 34:** **24/24** data/repository/migration/notification interaction
  (`data-final.log`), termasuk snapshot nama/kategori historis tidak tertimpa perubahan master.
- **PASS UI regression API 34:** **17/17** (`ui-final.log`): navigasi, active session editing,
  header 8 dp/12 kategori, summary, dan workout interaction.
- **PASS native theme API 34:** dua eksekusi terisolasi (Android light/dark), native card
  brightness assertions dan crop screenshot terbaca; `native-theme-verified-no.log` /
  `native-theme-verified-yes.log`. Host menghentikan hanya paket diagnostic sebelum setiap
  fixture agar Activity/timer milik test sebelumnya tidak membatalkan notifikasi sintetik.
  Total final **43 eksekusi instrumentation** = 24 + 17 + 2, bukan 43 skenario unik tema.
- **PASS preservasi:** kedua paket diagnostic di-uninstall. APK/path aplikasi biasa,
  font/rotation/animation settings, night mode, ukuran dan density emulator identik baseline;
  `preservation-result.json`. Tidak ada reinstall aplikasi biasa atau modifikasi Supabase.
- **PASS:** `git diff --check` dan checker paket skill lokal. Validator resmi/benchmark
  agent tidak dijalankan. Long-duration background/OEM/minified release suite lama tidak
  diulang pada fase ini; satu pengujian alarm nyata pendek bukan penggantinya.

Log percobaan yang gagal tetap disimpan, tidak dihitung sebagai PASS. Percobaan test
notifikasi membedakan pengiriman activity PendingIntent dari background (diblokir Android
14), ketukan SystemUI nyata, lifecycle ActivityScenario yang berganti instance, dan label
lokalisasi Activity vs Application, serta posting notifikasi asynchronous. Test interaksi
final memakai ketukan nyata dan label locale aktif;
bukan mengubah perilaku aplikasi agar cocok dengan fixture.

APK aplikasi yang diuji (debug demo diagnostic):
`e4c5bd06121fb306dfdc686e25e864ffa2e1f20d196cd09ae89340e5848ff45c` (SHA-256).
Perubahan terakhir setelah suite 41 test tersebut hanya pada fixture tema, bukan APK aplikasi.

## Status penutupan

| Issue | Status yang dapat dibuktikan |
| --- | --- |
| 1 — seluruh jalur refresh/offline-online | Implementasi client dan fixture lokal PASS; **belum ditutup E2E Supabase**. Batas protokol template/hard-delete di atas tetap berlaku. |
| 2 — >=1 completed set per gerakan | PASS repository, aggregate lokal, dan regresi UI; riwayat lama tidak dibersihkan massal. |
| 3 — volume awal 0 / bertambah saat complete | PASS unit dan regresi sesi. |
| 4 — auto-fill/default menurut konfigurasi | PASS provenance lokal, cold repository, rename, perubahan target, owner isolation dan tambahan manual; data lama/remote tanpa provenance memakai default. |
| 5 — body membuka+stop alarm / Stop background | PASS ketukan SystemUI nyata, audio/notification cleanup dan session identity API 34. |
| 6 — native light/dark sistem | PASS API 34; putih ketika hanya IMFIT dark tetap perilaku yang disepakati. |

**Kesimpulan:** jangan mengklaim seluruh enam issue sudah tuntas pada proyek Supabase
pengguna. Penutupan issue 1 membutuhkan proyek/akun uji yang diizinkan untuk round-trip
remote update, offline create/finish, reconnect/retry dan konfirmasi payload server.

Evidence lokal:
`/private/var/folders/yv/171k2lgd0jdg4y0jclvxjtzw0000gn/T/opencode/imfit-fix-six-20261020/`.
APK biasa, data akun produksi, channel notifikasi aplikasi biasa dan file Pencil tidak
ditimpa. Semua instalasi/pengaturan permission test dibatasi paket diagnostic sementara.
