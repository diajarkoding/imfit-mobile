---
name: imfit-ui-ux
description: Designs, implements, and reviews IMFIT Android Jetpack Compose UI/UX using the existing redisgn design system and state contracts. Use for redesigning unfinished screens, new screens, login/register, beranda/Home, gerakan/Exercises, Progres, rutinitas creation/editing, exercise pickers, shared components, themes, accessibility, and ID/EN UI copy. Do not use for backend-only, database migrations, sync algorithms, authentication policy, or rest-timer engine work.
compatibility: Requires access to the IMFIT Kotlin/Jetpack Compose repository. Android SDK, Gradle, and ADB are needed only for applicable build/device verification. Tool permissions and skill discovery depend on the host agent.
metadata:
  version: "1.0.5"
  source-branch: "redisgn"
  source-commit: "3ec72efbf43dc8b749aa3e4ae413a4a0373d8fa7"
  source-overlay: "Profile/scrim and UI polish; all page headers use an 8 dp content gap, Exercises avatar removed, 2026-10-19; see reference addenda"
---

# IMFIT UI/UX

## Objective

Kembangkan halaman yang belum dire-design dan UI berikutnya agar konsisten dengan IMFIT,
tanpa mengubah perilaku bisnis, ownership state, atau kontrak navigasi secara tidak sengaja.
Konsistensi berarti memakai sistem yang sama, bukan menyalin seluruh komposisi Home ke setiap layar.

## Input dan batas penggunaan

Sebelum mengubah kode, identifikasi:

- Halaman/flow target, tujuan pengguna, scope yang diizinkan, dan acceptance criteria.
- Desain/referensi yang disetujui, jika ada; tanpa mockup, gunakan pola source yang relevan.
- Route/caller aktual, ViewModel/state, komponen, aset, resource ID/EN, dan test terkait.
- Perilaku yang wajib tetap: draft, back, selection, validasi, save/delete, sesi aktif, dan sync.

Jika keputusan mengubah aturan produk atau arsitektur, minta persetujuan spesifik sebelum
mengimplementasikannya. Kekurangan mockup saja tidak mengharuskan pekerjaan berhenti:
nyatakan asumsi visual yang konservatif dan terapkan scope minimum.

**Bukan izin tambahan:** skill ini tidak memberi akses akun produksi, mengizinkan install
tool/dependency, menjalankan operasi backend, atau mengizinkan commit/push.

## Progressive disclosure

Baca hanya referensi yang diperlukan, lalu source file target dan caller-nya:

| Kebutuhan | Referensi |
| --- | --- |
| Warna, typography, spacing, component families, artwork | [Design system](references/design-system.md) |
| Halaman acuan dan route aktif; perbedaan dengan kode legacy | [Screen audit](references/screen-audit.md) |
| State ownership, operasi async, navigation/result, local-first | [State contracts](references/state-contracts.md) |
| Perintah verifikasi, matriks UI dan evaluasi skill | [Validation and evaluation](references/validation-and-evaluation.md) |

Referensi adalah snapshot source pada commit metadata, bukan API yang dijamin tetap.
Periksa `git status`, branch, dan source saat digunakan. Jika source sudah berubah,
catat perbedaannya; jangan menimpa source baru agar cocok dengan snapshot ini.
Dokumen rencana lama adalah konteks, bukan bukti bahwa suatu komponen masih terhubung ke UI.

## Workflow

### 1. Audit dan kontrak sebelum edit

1. Telusuri route dari `NavGraph`/`MainScreen` sampai screen, state, dan aksi repository.
2. Bedakan komponen aktif, helper legacy, preview-only, dan halaman tanpa route.
3. Buat kontrak singkat: **state → representasi UI → aksi → hasil → kondisi navigasi**.
4. Pilih keluarga visual terdekat: root-tab, auth form, atau routine/task flow.
5. Daftarkan file yang akan diubah serta perilaku yang tidak boleh berubah.

Minimal state yang dipertimbangkan: initial/loading, content, legitimate empty, filtered
no-results, load failure/retry, validation error, operation failure, submitting/disabled,
success dan back/cancel. Tandai N/A dengan alasan; jangan mengarang state yang tidak tersedia.

### 2. Rancang dengan bahasa visual IMFIT

- Gunakan `IMFITTheme`, `MaterialTheme.typography`, `IMFITTypography`, dan token bersama.
- Untuk Training Hub/auth/routine yang memakai palette custom, gunakan
  `IMFITColors.training` dan pasangan semantic foreground/container yang sesuai.
- Background netral, surface bersih, biru sebagai aksi/fokus, judul kuat, supporting text
  lebih tenang, dan artwork anatomi/fitness sebagai pendukung, bukan pembawa informasi wajib.
- Root screen memakai shell `MainScreen`/`ImfitMainAppBar`; jangan membuat header/nav ganda.
- Form/routine memakai control solid, outlined secondary, footer task yang jelas, dan error
  dekat konteksnya. Jangan menjadikan semua tombol gradient atau semua halaman hero banner.
- Reuse komponen yang ada; helper `internal`/`private` bukan API global. Ekstrak shared
  component hanya jika benar-benar dipakai lintas layar dan scope mengizinkannya.

### 3. Implementasi tanpa mengubah kontrak

- Screen mengoleksi state dengan `collectAsStateWithLifecycle()` dan mengirim intent ke
  ViewModel; jangan menambah query Room/network atau save langsung dari body composable.
- Gunakan `rememberSaveable` untuk state presentasi kecil yang perlu bertahan, seperti
  overlay/filter draft. Ini bukan jaminan persistensi seluruh draft saat process death.
- Loading awal boleh shimmer. Submit/error operasi tetap di konteks form; pertahankan input.
- Nonaktifkan aksi konflik saat proses; jangan memicu double-submit atau navigasi sebelum
  state sukses. Retry tidak boleh menghapus draft/selection tanpa persetujuan.
- Pisahkan pilihan sementara dari apply/confirm; simpan pilihan berdasarkan ID, bukan label.
- Gunakan resource teks `values` dan `values-in`, plural, serta format locale untuk tanggal/angka.
  Gunakan terminologi **rutinitas = template**, **gerakan = exercise**, **sesi/riwayat = workout**.
- Pertahankan route/result consumption, active-session guard, dan refresh lokal sesudah save.
- Jangan mengubah perhitungan metrik, default set/reps/rest, enum persisten, timer deadline,
  permission, schema, sync atau auth policy untuk mencapai tampilan mockup.

### 4. Adaptif dan aksesibel

- Target interaktif minimum 48 dp; glyph tidak harus 48 dp. Pakai `heightIn` untuk kontrol
  yang mengandung teks agar label terjemahan dan font besar dapat tumbuh.
- Uji narrow width dan font besar; stack/wrap alih-alih mengecilkan teks atau memotong aksi.
- Tetapkan satu owner inset per area; periksa status bar, navigation bar, Scaffold padding,
  dan IME. CTA tidak boleh menutupi input/list terakhir.
- Label aksi ikon dengan resource; dekorasi `contentDescription = null`. Gunakan role,
  selected/toggle semantics, error semantics, dan live region yang relevan.
- Jangan menyandikan status hanya dengan warna. Pasangan status harus terbaca di light/dark.
- Jangan menyalin ukuran/font lokal yang incidental menjadi aturan global tanpa audit.

### 5. Verifikasi dan review diff

Ikuti reference validation berdasarkan area dampak. Pisahkan compile, unit, instrumentation,
visual comparison, dan manual device checks: satu jenis PASS tidak membuktikan yang lain.
Gunakan fixture demo/sintetik; jangan akun produksi atau modifikasi konfigurasi perangkat
tanpa scope yang jelas. Sinkronisasi test memakai state/semantics, bukan sleep tetap.

Untuk perubahan dokumen skill saja, cukup validasi paket/source references; jangan mengklaim
UI, build Android, atau kualitas perilaku agent sudah diuji.

## Output contract

Laporkan secara ringkas:

1. Hasil UI dan file yang berubah, beserta source/pola acuan.
2. State/aksi penting yang dipertahankan dan keputusan visual yang diambil.
3. Verifikasi aktual: command, variant/device bila relevan, hasil dan lokasi bukti.
4. Status terpisah **PASS**, **FAIL**, **BLOCKED**, **NOT_TESTED**; N/A disertai alasan.
5. Gap, asumsi, dan pekerjaan di luar scope; jangan menyebut visual approved tanpa review.

## Completion criteria

- Scope dan route yang benar telah disentuh; tidak ada perubahan fungsi yang tidak diizinkan.
- Komponen/token/asset/resource yang dirujuk benar-benar ada; tidak ada data statistik palsu.
- State penting dan jalur error/retry/back tidak hilang demi happy-path screenshot.
- Verifikasi sesuai dampak dilakukan; yang belum dijalankan dicatat, bukan dianggap lulus.
- Diff ditinjau. Jangan discard pekerjaan pengguna; rollback perubahan sendiri secara
  terarah atau lewat commit revert yang diizinkan, bukan reset/checkout paksa seluruh file.

## Maintenance dan penggunaan lintas host

Folder `docs/skills` **tidak otomatis ditemukan oleh semua host agent**. Muat `SKILL.md`
secara eksplisit atau pasang/configure folder ini sesuai host, hanya bila diminta pengguna.
Jangan menambahkan konfigurasi host atau mengubah permission secara diam-diam.

Saat pola UI/state berubah, perbarui referensi, metadata source/version, dan skenario evaluasi
bersama bukti baru. Jalankan [package check](scripts/check_skill.py); bila tersedia jalankan
juga `skills-ref validate docs/skills/imfit-ui-ux`. Package check bukan pengganti validator
resmi atau evaluasi perilaku model.
