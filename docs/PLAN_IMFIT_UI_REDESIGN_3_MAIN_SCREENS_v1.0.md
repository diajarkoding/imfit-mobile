# PLAN IMPLEMENTASI UI/UX — 3 HALAMAN UTAMA IMFIT

**Versi:** 1.0\
**Tanggal:** 2026-10-13\
**Mode:** PLAN / READ-ONLY — belum mengubah repository\
**Status:** READY FOR REPOSITORY PRE-CHECK; implementasi menunggu Build Mode\
**Target:** Home, Exercise, dan Progress (*final edit*). **Bukan pembuatan desain baru.**

## 0. Baseline dan sumber kebenaran

1. **Referensi visual final, harus dipertahankan:**
   - `home_redesign.png` — Home.
   - `exercise_redesign.png` — Exercise.
   - `progress_redesign_final.png` — Progress, ilustrasi **dumbbell + shaker + tanda selesai**, bukan grafik batang.
2. **Kontrak produk:** IMFIT Mobile App PRD v1.2.2, baseline `ff545a8`, status draft/pending verification. Gunakan source repository saat eksekusi untuk mengonfirmasi nama komponen dan signature aktual.
3. **Kontrak tema yang sudah ada (dari cuplikan pengguna):** `com.diajarkoding.imfit.theme` — `Primary` `#3179FF`, `PrimaryDark`, `PrimaryLight`, `PrimaryContainer`, `IMFITColors`, `IMFITCustomColors`, `IMFITSpacing`, `IMFITCornerRadius`, `IMFITSizes`, `IMFITElevation`, `IMFITBottomNavDimensions`, `LocalIsDarkTheme`, serta tema terang/gelap.
4. **Asset Foundation Phase 1 (berdasarkan laporan build):** sebelas WebP sudah tersalin ke `app/src/main/res/drawable-nodpi/` dan adapter baru berada di `presentation/components/common/ImfitArtwork.kt`. Kedua flavor dilaporkan berhasil dibangun. **Verifikasi keberadaan pada working tree saat mulai Build Mode; jangan menyalin ulang aset dan jangan menimpa adapter.**
5. **Status bukti:** screenshot dan cuplikan theme telah tersedia, tetapi source code repository Android aktual belum dibuka pada sesi penyusunan plan ini. Referensi file berikut bersumber dari PRD dan laporan eksekusi sebelumnya; verifikasi ulang saat Build Mode.

## 1. Scope dan guardrail (terkunci)

### In scope
- Mengganti komposisi **UI Compose saja** pada tiga root tab sehingga susunan, hirarki, ilustrasi, card, navigasi, dan informasi mengikuti tiga mockup final.
- Memakai **11 WebP lokal existing**; memakai `ImfitHeroIllustration`/`categoryArtRes` existing.
- Menyusun *shared presentation components* seperlunya; menggunakan **token tema yang sudah ada**. Tidak membuat tema kedua atau mengubah identitas brand.
- Mengikat konten kepada ViewModel/state/repository existing (tanpa data contoh dari mockup).
- Menjaga tiga tab bottom navigation existing, integrasi profiling, riwayat, search, initial sync, serta fallback offline.
- Menambahkan UI state/presentation yang diperlukan untuk hasil kosong, loading, error yang sudah dapat dideteksi oleh state existing. Perubahan target behavior harus ditandai di QA.

### Out of scope / dilarang
- Tidak membuat ulang mockup, generate ilustrasi baru, atau membuat aset 81 gerakan.
- Tidak menambah/mengganti/menyalin 4 `imfit_ex_*.webp`, SVG master, mockup PNG, atau CSV ke `res`.
- Tidak mengubah `Color.kt`/tema global tanpa bukti kebutuhan & persetujuan; **utamakan reuse** token yang diberikan.
- Tidak mengubah domain model, DAO, Room migration, repository, endpoint, Supabase, sync manager, autentikasi, timer, log workout, kalkulasi volume/durasi.
- Tidak membuat fitur popularity ranking, streak algorithm, workout coaching, atau screen navigasi baru secara diam-diam.
- Tidak mengubah aturan create template kosong di Home, maupun jalur start/cancel/finish workout.
- Tidak menambahkan bottom navigation kedua pada tiap screen (host tetap `MainScreen`).

## 2. Inventaris resource dan cara penggunaan

| Resource existing | Komponen tujuan | Cara render | Semantics |
|---|---|---|---|
| `imfit_hero_home.webp` | Hero *Latihan Terakhir* di Home | `ImfitHeroIllustration(HOME)`, `ContentScale.Fit`, ukuran terkendali, boleh di-clip di batas card | `contentDescription = null` karena dekoratif |
| `imfit_hero_progress.webp` | Hero *Ringkasan Bulan Ini* di Progress final | `ImfitHeroIllustration(PROGRESS)`, jangan pakai ilustrasi grafik bisnis | `contentDescription = null` |
| `imfit_muscle_dada.webp` | CHEST | `categoryArtRes(MuscleCategory.CHEST)` | Kategori dijelaskan oleh teks |
| `imfit_muscle_punggung.webp` | BACK | typed adapter | Teks yang dapat diakses |
| `imfit_muscle_bahu.webp` | SHOULDERS | typed adapter | Teks yang dapat diakses |
| `imfit_muscle_biceps.webp` | BICEPS | typed adapter | Teks yang dapat diakses |
| `imfit_muscle_triceps.webp` | TRICEPS | typed adapter | Teks yang dapat diakses |
| `imfit_muscle_kaki.webp` | LEGS | typed adapter | Teks yang dapat diakses |
| `imfit_muscle_inti.webp` | CORE | typed adapter | Teks yang dapat diakses |
| `imfit_muscle_kardio.webp` | CARDIO | typed adapter | Teks yang dapat diakses |
| `imfit_muscle_generic.webp` | `null` / fallback presentasi | typed adapter | Teks nama exercise tetap terlihat |

**Aset yang BELUM tersedia:** empat gambar figur latihan pada mockup Exercise (Bench Press, Incline Press, Cable Flyes, Deadlift) sengaja ditunda; **pertahankan susunan kartu mockup tetapi isi slot thumbnail dengan ikon kategori**. Tidak memakai crop screenshot sebagai gambar edukasi. WebP lokal dirender dengan `painterResource`, **tanpa Coil untuk local resource**. Ikon aksi/navigasi gunakan vector/icon set existing. Simpan teks, angka, dan satuan sebagai Text Compose, bukan gambar.

**Known limitation:** pada repository existing, kategori remote tidak dikenal dikonversi menjadi `CHEST`. Karena itu generic icon hanya pasti dipakai untuk nilai `null` yang diteruskan ke adapter; *unknown remote* yang sudah dinormalisasi CHEST tetap tampak sebagai Dada. Jangan mengklaim fallback remote unknown telah selesai.

## 3. Token layout dan pola implementasi (menggunakan theme existing)

| Kebutuhan | Token existing / ketentuan |
|---|---|
| Brand/action | `Primary`, `PrimaryLight`, `PrimaryContainer` / `MaterialTheme.colorScheme.primary` |
| Latar terang/gelap | `IMFITColors.backgroundPrimary()` atau `MaterialTheme.colorScheme.background` dengan `LocalIsDarkTheme` existing |
| Cards | `IMFITColors.cardBackground()`, `IMFITColors.cardBorder()`, `IMFITElevation.card` |
| Teks | `IMFITColors.textPrimary()`, `.textSecondary()`, `.textTertiary()` / MaterialTheme typography existing |
| Padding halaman | `IMFITSpacing.screenHorizontal` (20dp), `screenVertical` (16dp) |
| Jarak kartu/section | `IMFITSpacing.itemSpacing` (12dp), `.sectionSpacing` (24dp) |
| Sudut rounded | `IMFITCornerRadius.large` (18dp) atau `.xlarge` (24dp); jangan hardcode radius per-card |
| CTA utama | `IMFITSizes.buttonHeightLarge` (60dp), primary gradient existing hanya untuk tombol bila cocok dengan theme |
| Nav bawah | `IMFITBottomNavDimensions` pada `MainScreen`, satu owner |
| Target sentuh | target efektif >=48dp untuk ikon yang dapat ditekan, gunakan insets yang aman |

- Ilustrasi hero diposisikan di ujung kanan kartu **di atas latar menggunakan warna semantic/light container**, tidak menggunakan screenshot sebagai background; tekstur/tint hanya dibangun dari token yang telah ada.
- Mockup adalah baseline *visual hierarchy*, bukan ukuran pixel absolut; layout harus menyesuaikan lebar 320–430dp, font scaling, locale panjang, dan sistem inset.
- Untuk navbar gunakan komponen existing; perubahan hanya styling bila diperlukan untuk mencocokkan selected pill/tab dari mockup tanpa mengubah navigasi.
- `strings.xml` (EN) dan `values-in/strings.xml` (ID) untuk semua label baru. Jangan hardcode `Demo User`, tanggal Oktober, volume, atau kategori lokal di UI.
- Jangan menggunakan warna biru utama untuk teks di atas light-blue container tanpa memeriksa kontras. Dark theme wajib memakai semantic surface/text; kartu bukan bitmap latar dengan label tidak terbaca.

## 4. Susunan dan urutan implementasi per halaman

### Phase 2A — Shared Presentation Foundation (sebelum screen)

**Audit terlebih dahulu:** `MainScreen.kt`, komponen shared existing, `HomeScreen.kt` / `HomeViewModel.kt`, root Exercise screen (temukan nama file), root Progress screen / `ProgressViewModel`, `NavGraph.kt`, `ImfitArtwork.kt`, `Theme.kt`/`Color.kt`/`Type.kt` aktual, serta file string bahasa.

**Komponen reusable hanya jika belum ada padanannya:**
- `ImfitSectionHeader` — judul section + optional `Lihat Semua` yang benar-benar memiliki tujuan.
- `ImfitHeroSummaryCard` — frame/card hero dengan slot illustration dan konten teks/stats.
- `ImfitStatTile` — icon + angka/satuan + label dan semantics.
- `ImfitWorkoutRow` — list row untuk template atau histori melalui parameter yang jelas, tanpa menggabungkan domain yang berbeda.
- `ImfitCategoryTile` — menggunakan `categoryArtRes(enum)`.
- `ImfitExerciseRow` — slot thumbnail dengan category fallback (tanpa URL atau Coil dahulu).
- `ImfitMonthCalendar` — kalender dari data tanggal aktual (reuse composable existing jika ada).

**Dilarang** membuat kedua komponen yang hampir sama hanya demi tiap tab. Pertahankan API callback `onClick`/`onNavigate` dari screen ke host. Composable presentational tidak memanggil repository.

**Exit:** komponen preview/compile menggunakan typed placeholder data **hanya untuk Compose Preview/test fixtures**, bukan runtime. Unit test mapping 8 enum + `null` menegaskan **resource ID yang benar**, bukan hanya exhaustive compile.

### Phase 3 — Home (`home_redesign.png`)

**Urutan tampilan yang harus diikuti (atas → bawah):**
1. Header IMFIT: app icon/brand (reuse asset/logo aplikasi existing) di kiri; ikon Profile dan navigasi ke Profile existing di kanan.
2. Greeting dinamis `Halo, {displayName}!` + subtitle `Siap untuk latihan hari ini?` (string resource).
3. Kartu **LATIHAN TERAKHIR**: nama workout log selesai terbaru, tanggal + jumlah exercise aktual, hero `HOME` di kanan; dua stat panel di bawah: **Durasi** dan **Total Volume**.
4. Section **Latihan Saya** dengan `Lihat Semua` hanya bila ada destination existing yang tepat. Daftar template dari state existing: Push/Pull/Leg Day hanya contoh data, bukan tiga baris hardcoded. Tiap row menampilkan ikon dumbbell existing/category art yang sesuai; nama, durasi estimasi **bila ada dasar data**, dan jumlah exercise; klik → `WorkoutDetail`.
5. CTA biru **Tambah Latihan** tetap membuka *Buat Latihan* existing, yang membuat template dari nama lalu masuk detail sesuai flow yang sudah ada.
6. Bottom navigation **Home aktif** dari `MainScreen` existing.

**State:**
- Tidak ada log → kosong untuk hero dengan copy netral; jangan menampilkan statistik Push Day fiktif.
- Tidak ada template → empty state dengan CTA existing, hindari list tiga contoh.
- Nama kosong → greeting umum, bukan string `Demo User`.
- Estimasi durasi tidak tersedia → sembunyikan field tersebut atau tampilkan `—` (bukan nilai ~40 menit default).
- Initial sync overlay tetap host `MainScreen`, tidak diubah/digandakan.

**Files likely touched (konfirmasi saat audit):** `HomeScreen.kt`, presentation shared components, `strings.xml`, `values-in/strings.xml`. Perubahan `HomeViewModel.kt` **hanya** pemetaan `UI state` dari data yang memang sudah tersedia; jangan ubah create/use-case.

**Exit:** tap row → detail, CTA → dialog sama, template kosong dari jalur Home tetap diperbolehkan, hero menampilkan completed log terbaru, tidak ada mockup number hardcoded.

### Phase 4 — Exercise (`exercise_redesign.png`)

**Urutan tampilan yang harus diikuti:**
1. Header IMFIT dan Profile (sama gaya Home), judul **Latihan**, subtitle eksplorasi gerakan.
2. Search field **Cari latihan** di atas kategori, keyboard IME search/back sesuai komponen existing.
3. Section **Kategori Otot** grid **8 kategori** urut: Dada, Punggung, Bahu, Biceps, Triceps, Kaki, Inti, Kardio. Gunakan 8 kategori English enum untuk routing/data, label locale Indonesian; ikon `imfit_muscle_*.webp`.
4. Section daftar exercise tepat di bawah grid, dengan card kecil berisi slot thumbnail, nama exercise, kategori, deskripsi ringkas **hanya jika record menyediakannya**, dan chevron klik.
5. Nav **Exercise aktif**, satu owner di host.

**Kontrak visual responsif:** Grid **4 kolom × 2 baris** bila lebar & font scale mencukupi; turun menjadi 2 kolom bila compact/large-font supaya tulisan tidak terpotong. Search dan list scroll dalam satu `LazyColumn` / kontainer scroll konsisten; navbar tidak ikut scroll.

**Search semantics & data:**
- Sumber: exercise repository/search existing; jangan memperkenalkan API/BE baru.
- Search kosong → category + section list; query non-kosong → hasil search deterministik, state loading/no result/error sesuai sinyal existing. Search *tidak* masuk exercise selection flow secara otomatis.
- Tap kategori → screen kategori existing (`NavGraph`); tap exercise → detail existing **jika route tersedia**. Bila belum ada standalone detail, jangan buat clickable affordance palsu atau route baru; tandai sebagai blocker screen parity untuk product decision.
- `Latihan Populer` pada mockup **tidak didukung metric popularity di PRD**. Pertahankan **posisi dan layout section**, tetapi label/ranking harus diputuskan sebelum menampilkan produk: (A) Product menyetujui daftar editorial & penggunaan label `Latihan Populer`, atau (B) hanya koreksi copy menjadi `Jelajahi Latihan` tanpa mengubah UI layout. Tidak mengarang popularity score.
- Tidak memakai empat thumbnail `imfit_ex_*` yang belum disalin. **Slot gambar seluruh exercise memakai kategori;** tidak ada aturan mapping ID yang diasumsikan.

**Files likely touched:** root Exercise screen (cek nama aktual di `NavGraph.kt`), ViewModel root jika perlu untuk state presentasi dari API existing, presentation shared components, string resource. **Tidak** mengubah `ExerciseRepositoryImpl.kt`.

**Exit:** 8 tiles + link kategori valid, search menemukan query, no-results jelas (target AC-LIB-04), semua baris gambar fallback lokal, klik navigasi valid, dua flavor offline bila cache tersedia.

### Phase 5 — Progress FINAL (`progress_redesign_final.png`)

**Urutan tampilan yang harus diikuti:**
1. Header judul **Progress** + avatar/profile link kanan.
2. Kartu hero **RINGKASAN BULAN INI** dengan illustration `ImfitHeroIllustration(PROGRESS)` (dumbbell + shaker + check). Headline **Kamu konsisten!** hanya untuk kondisi yang benar-benar memenuhi aturan copy dari jumlah workout selesai; untuk nol latihan pilih pesan netral (copy-state, bukan redesign layout).
3. Area **empat slot statistik** mengikuti mockup: total volume, waktu mingguan, jumlah latihan, metrik keempat. Isi angka dari log/aggregate sebenarnya, **tidak** menggunakan `7.700 kg`, `1h 0m`, `3`, atau `5` sebagai default.
4. Kalender bulanan dalam satu card: judul bulan/tahun, sebelumnya/berikutnya, `Hari Ini`; hari bertanda workout, tanggal terpilih, dan hari ini dibedakan secara visual/semantics.
5. Section **Latihan Terakhir** berisi riwayat completed workouts (judul, tanggal, durasi, volume, jumlah latihan). Row klik → History/date/detail existing; `Lihat Semua` → History existing.
6. Nav **Progress aktif** dari host.

**Kontrak metrik:**
- Label harus menentukan periode: total volume existing *all-time* vs total volume bulan, waktu mingguan vs bulan. **Jangan mengubah cakupan aggregate hanya agar angka cocok dengan screenshot.** Definisikan di presentation mapping memakai data existing; jika data belum tersedia, tandai sebagai blocker data/UX, bukan membuat repository baru.
- `Streak Bulan Ini` di screenshot **belum punya kontrak di PRD**. Slot keempat tetap dalam layout, tetapi **tidak boleh menampilkan nilai streak fiktif**. Gate product sebelum release: setujui aturan streak terukur dari data existing (periode/timezone/hari latihan, definisi consecutive), atau gunakan label metrik existing yang disetujui (perubahan copy saja, bukan layout baru). Jika tidak disetujui, tandai fungsi slot sebagai BLOCKED dan jangan nyatakan screen parity selesai.
- Bulan/tanggal dihitung dari calendar library/system locale, bukan ditulis dari mockup. Untuk Okt 2026, `2026-10-01` adalah Kamis. Zona waktu, rollover midnight, multi-log/day, Januari↔Desember harus benar. Urutan hari awal minggu mengikuti kontrak produk/existing; jangan meniru grid foto yang secara visual mungkin tidak konsisten.
- Waktu/durasi memakai hasil log existing sambil mencatat known gap pause duration `AC-WORK-04T`, `AC-SUM-03T`; bug durasi adalah scope terpisah.
- Card empat metrik boleh responsif 2×2 pada compact/large-font tetapi urutan, warna, label, dan ikon tetap mengikuti desain. Title/angka tidak boleh ellipsize tanpa aksesibilitas.
- Jika belum ada log, kalender tetap render, hero/metric/list punya empty state yang jelas.

**Files likely touched:** root Progress screen (verifikasi path), `ProgressViewModel` **UI state mapping saja** bila perlu, shared components, string resource. `ProfileScreen.kt`, `YearlyCalendar` dan `History` **tetap**. Jangan ubah calculation repository.

**Exit:** ilustrasi **final edit** tampil, stats dari data konsisten dengan existing history, pindah bulan/tanggal benar, klik history/profile bekerja, tidak ada label/angka streak palsu.

## 5. File-change matrix (minimal diff)

| Berkas/area | Perubahan yang diizinkan | Wajib tetap |
|---|---|---|
| `HomeScreen.kt` | Layout Compose mengikuti Home final + adapter state | Dialog pembuatan template, nav ke detail |
| Root Exercise screen | Header, search, kategori, list preview, empty states | Enum, repository search, category navigation |
| Root Progress screen | Hero, stat tiles, calendar, history cards | Data existing, date navigation, profile |
| `presentation/components/common/ImfitArtwork.kt` | **Prefer no edit**; pemanggilan existing | Mapping 8 kategori + null, contentDescription hero null |
| New/reused `presentation/components/common/*` | Hanya reusable UI primitives bila existing tidak memadai | Tidak ada repository calls/domain mutations |
| `MainScreen.kt` | Hanya bila perlu styling active pill/insets untuk mockup | 3 tab dan initial sync overlay, no duplicate nav |
| `HomeViewModel`/Exercise VM/`ProgressViewModel` | Presentation mapping *only*, bila state tidak expose yang ada | Flow lifecycle, sync, domain behavior |
| `values/strings.xml`, `values-in/strings.xml` | Label UI/pesan state | Fallback locale, tidak hardcode Indonesia |
| Theme files | Default **NO CHANGE** | `Primary #3179FF`, semua semantic token existing |
| `drawable-nodpi/*.webp` | **NO CHANGE** | 11 resource Phase 1, jangan copy ulang |
| Repository/domain/DB/backend/timer | **NO CHANGE** | Existing behavior dan AC |

## 6. Implementation sequence / PR checkpoints

1. **Checkpoint 0 — Repository confirmation (read-only):** temukan root composable, callback, state class, routes, token & komponen existing. Beri hasil *actual path/signature* sebelum edit. Cek working tree tidak menimpa untracked file Phase 1.
2. **Checkpoint 1 — Shared UI:** reuse `ImfitArtwork` dan token, buat shared components minimal, preview + unit tests kategori. Build `assembleDemoDebug`, `assembleProductionDebug`.
3. **Checkpoint 2 — Home:** implement hanya Home; compare dengan `home_redesign.png`, uji CTA/dialog/template detail serta offline.
4. **Checkpoint 3 — Exercise:** implement hanya Exercise; compare dengan `exercise_redesign.png`, uji 8 kategori, search/filter, fallback; selesaikan wording `Latihan Populer` dan route detail sebelum menyatakan done.
5. **Checkpoint 4 — Progress:** implement Progress final; compare dengan `progress_redesign_final.png`, uji 4 stat slots, calendar, history, gambar hero final; selesaikan definisi metrik keempat atau laporkan BLOCKED.
6. **Checkpoint 5 — Regression:** kedua flavor build, screenshot tests, UI semantics, feature tests dan audit PRD. Simpan before/after per tab. **Jangan merge jika gating functional acceptance gagal.**

**Rollback:** per-screen commit/cherry-pick atau per-feature flag lokal bila tersedia, tanpa rollback aset Phase 1; tidak perlu migrasi data atau rollback backend.

## 7. Acceptance criteria / verification evidence

### Visual & responsive (semua screen)
- [ ] Layout dan urutan section cocok dengan masing-masing 3 PNG final; deviasi aset Exercise (fallback kategori) terdokumentasi.
- [ ] Hero HOME = `imfit_hero_home`; PROGRESS = `imfit_hero_progress` final; tidak tertukar.
- [ ] Cards, warna, spacing, radius, typography menggunakan theme existing dan tidak menyisipkan hardcoded screenshot.
- [ ] Light/dark, Indonesian/English, layar kecil, fontScale 1.0/1.3/1.5, orientation bila supported; tidak ada overflow/tab overlap/keyboard occlusion.
- [ ] `contentDescription=null` pada ilustrasi dekoratif; label/angka/card actions terbaca TalkBack; tap target efektif >=48dp.
- [ ] 11 WebP lokal dapat dirender offline dan 9 adapter input-output resource benar, termasuk null.

### Functional / data (QA)
- [ ] **Home:** latest finished log sebenarnya; template list benar; CTA → dialog; row → detail; template kosong Home tetap sesuai baseline (`AC-DASH-04`, `AC-TMPL-01`).
- [ ] **Exercise:** 8 kategori, kategori → daftar, search hasil query, no result state (`AC-LIB-01/02/03`; `AC-LIB-04` adalah TARGET_ONLY baseline, dicatat sebagai target perubahan ini jika diimplementasikan).
- [ ] **Progress:** calendar → date history (`AC-PROG-01/02`), recent log benar, periode stats dinyatakan, tidak ada angka dummy.
- [ ] Bottom nav 3 tab, active indicator, backstack, profile route dan overlay initial sync tidak mengalami regresi (`AC-DASH-01/02`, `AC-SYNC-04`).
- [ ] Demo vs production; offline cached data/empty cache dibedakan; gambar lokal tetap muncul.
- [ ] Durasi elapsed vs active-duration mismatch masih tercatat (`AC-WORK-04C/04T`, `AC-SUM-03T`); jangan mengklaim solved.

**Evidence wajib tiap checkpoint:** file diff, screenshot before/after, build commands + exit, test report dengan status `PASSED/FAILED/NOT_TESTED/BLOCKED`, daftar approved copy/metric decisions. Lulus compile ≠ lulus runtime/UI.

## 8. Blockers/decisions yang harus ditutup tanpa redesign ulang

| Kode | Keputusan | Tindakan minimum | Gate |
|---|---|---|---|
| D-01 | Label section Exercise `Latihan Populer` belum ada ranking | Setujui editorial list atau copy-only `Jelajahi Latihan`; jangan ganti layout | Sebelum Phase 4 selesai |
| D-02 | Klik exercise card harus punya destination real | Konfirmasi route existing; jika tidak ada, eskalasi scope agar tidak jadi CTA palsu | Sebelum Phase 4 selesai |
| D-03 | Metrik keempat Progress `Streak Bulan Ini` belum terdefinisi | Setujui formula dari data existing atau pengganti yang valid; jangan angka palsu | Sebelum Phase 5 selesai |
| D-04 | Scope waktu total volume/latihan di hero dan stat tiles | Kunci periode untuk setiap label sesuai data yang tersedia | Sebelum Phase 5 implement |
| D-05 | Tautan `Lihat Semua` Home | Pastikan rute template list tersedia; bila tidak, hide aksi saja, tidak menambah page | Sebelum Phase 3 selesai |
| D-06 | Source resmi UI/UX Guideline v1.0 disebut eksternal di PRD | Gunakan token theme actual sebagai batas sekarang, catat lokasi/version guideline bila ada | Sebelum code freeze |

### Definisi Done
**Selesai** berarti tiga halaman Compose memakai UI mockup final dan resource Phase 1, seluruh aksi menuju destination nyata, data aktual/empty states benar, build kedua flavor & tes regresi lulus, serta keputusan D-01–D-06 tertutup atau dinyatakan blocker. **Ini tidak menyatakan production readiness IMFIT secara keseluruhan.**

---

**Keputusan scope:** mulai Build Mode nanti dari Checkpoint 0 → shared components → Home → Exercise → Progress; **tidak** membuat UI/UX baru, tidak menambah asset, tidak mengubah logika workout.
