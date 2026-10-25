# Design system source audit

Snapshot: `redisgn`, commit `3ec72efbf43dc8b749aa3e4ae413a4a0373d8fa7`, 17 Oktober 2026.
Metode: inspeksi source, route/caller, resource dan test; **bukan screenshot/device audit baru**.
Semua path Kotlin pendek di referensi ini relatif terhadap
`app/src/main/java/com/diajarkoding/imfit/`.

## 1. Source of truth

| Owner | Source | Tanggung jawab |
| --- | --- | --- |
| Raw color dan semantic palettes | `theme/Color.kt` | Brand, light/dark surfaces, status pairs, Training palette |
| Public design tokens | `theme/DesignSystem.kt` | `IMFITColors`, typography, spacing, sizes, radius, gradients, motion |
| Theme installation | `theme/Theme.kt` | Material scheme, `LocalTrainingPalette`, `LocalIsDarkTheme`, transisi 300 ms |
| Material adapters | `theme/Type.kt`, `theme/Shape.kt` | Material typography/shapes memakai token bersama |
| Theme/locale ownership | `theme/ThemeViewModel.kt`, `theme/LocaleManager.kt`, `MainActivity.kt` | Preferensi, edge-to-edge, pembaruan bahasa tanpa reset graph |
| Existing guideline | `docs/DESIGN_SYSTEM.md` | Ringkasan ownership; verifikasi terhadap kode saat digunakan |

Tidak ada dynamic-color palette yang dipilih pada `IMFITTheme` saat audit. Jangan mengganti
identitas brand dengan dynamic/system palette tanpa keputusan desain yang diizinkan.

## 2. Dua jalur warna, bukan dua design system terpisah

| Role | Source value saat audit | Cara penggunaan |
| --- | --- | --- |
| Global brand primary | `Primary` = `#3179FF` | `MaterialTheme.colorScheme.primary` untuk komponen Material/general |
| Training primary light/dark | `PrimaryDark` = `#2563EB` / `PrimaryLight` = `#5C9AFF` | `IMFITColors.training.primary` pada redesigned Training Hub/auth/routine |
| Training onPrimary light/dark | putih / `#101D34` | Pasangkan dengan training primary, jangan selalu pakai putih |
| Background light/dark | `#F7F9FC` / `#121212` | `training.background` atau Material background sesuai keluarga layar |
| Surface light/dark | putih / `#1F1F1F` | Container netral, border tipis pada banyak card redesign |
| Raised light/dark | `#F3F4F8` / `#2A2A2A` | Icon well, chip/field surface sekunder |
| Muted Training light/dark | `#5F6878` / `#B0B7C3` | Supporting copy, bukan disabled state otomatis |
| Border light/dark | `#E5E7EB` / `#374151` | Card/control outline |

Angka di tabel adalah evidence, **bukan instruksi menyalin literal Color ke screen**.
Gunakan palette pemilik layar dan pasangan `on*` miliknya. Jangan mencampur Training primary
dark dengan `OnPrimary` putih secara otomatis. Untuk status gunakan `training.success`,
`warning`, `error` bersama `*Container`; warna status raw tidak selalu pasangan text yang tepat.
`SemanticPaletteTest` mengecek rasio 4.5:1 untuk tiga pasangan status, bukan seluruh UI.

## 3. Typography, spacing, bentuk

- Font Inter lokal: `app/src/main/res/font/inter.ttf`; `IMFITFonts.Inter` adalah owner.
- Material styles adalah adapter `IMFITTypography`, bukan font family baru.
- Hierarki produk: `brand`, `greeting`, `screenTitle`, `sectionTitle`, `templateTitle`,
  `supporting`, `caption`, `statusTitle/statusBody`, dan `progressMetricValue`.
- Contoh token: `headlineSmall` 24/32 sp, `bodyLarge` 16/24, `bodyMedium` 14/20,
  `caption` 12/16.8, `brand` 22/30.8. Ukuran lokal di auth bukan baseline font baru.

| Token | Value | Intent |
| --- | --- | --- |
| `IMFITSpacing.screenHorizontal` | 20 dp | Gutter halaman |
| `IMFITSpacing.headerContentGap` | 8 dp | Header halaman → konten pertama; bukan padding internal appbar/inset |
| `screenVertical` / `cardPadding` | 16 dp | Padding halaman/card umum |
| `cardPaddingLarge` / `sectionSpacing` | 20 / 24 dp | Container besar dan pemisahan section |
| `xs/sm/md/lg/xl/xxl/xxxl` | 4/8/12/16/20/24/32 dp | Ritme shared; ada token tambahan 2/5/6/10/14/48 |
| `IMFITSizes.minimumInteractiveTarget` | 48 dp | Batas target sentuh, bukan ukuran glyph |
| `buttonHeight` / `textFieldHeight` | 48 / 56 dp | Minimum shared control |
| `IMFITCornerRadius.small/medium/listItem/card/dialog` | 10/14/16/20/28 dp | Pilih berdasarkan keluarga komponen |

`IMFITShapes.Card` = 20 dp; `CardSmall` = 14 dp. Banyak routine rows memakai radius lokal
12 dp, editor 14 dp, dan dialog 24 dp. Jangan menyeragamkan semua radius menjadi 20 dp
hanya karena nama `Card` shared. Promosi ukuran lokal menjadi token perlu alasan reuse.

## 4. Komponen acuan dan batas reuse

Path di bawah `presentation/components/common/` kecuali dinyatakan lain.

| Keluarga | Acuan aktif | Aturan pemilihan |
| --- | --- | --- |
| Root tabs | `ImfitMainAppBar.kt`, `ui/main/MainScreen.kt` | Brand tile, title, profile action; shell mengurus header/nav/inset |
| Auth | `ui/auth/AuthComponents.kt` | `AuthTextField`, `AuthPrimaryButton`, error strip, redirect, preferences; helper internal |
| Routine task | `ImfitRoutineComponents.kt` | `RoutineAppBar`, `RoutineButton`, `RoutineFooter`, `RoutineNameField`, `RoutineFailure`, `RoutineLoading`, `RoutineDialog` |
| Home | `ui/home/HomeComponents.kt` | Greeting, strength banner, local-history summary, template card, sync status |
| Progress | `ui/progress/ProgressComponents.kt` | Profile row, two metric cards, month calendar, selected-day summary |
| Profile (working-tree addendum 18 Oktober 2026) | `ui/profile/ProfileComponents.kt`, `ProfileScreen.kt` | Task app bar, solid semantic avatar fallback, outlined read-only cards, switch/radio preferences; see screen-audit addendum |
| General/legacy | `IMFITButton.kt`, `IMFITTextField.kt`, `IMFITShimmer.kt` | Reuse untuk area yang memang memakai keluarga ini; jangan global-replace saat redesign parsial |
| Artwork | `ImfitArtwork.kt` | `MuscleCategoryArtwork`, `categoryArtAsset`; hero helper juga ada tetapi bukan bukti hero masih dipakai di semua root tabs |

Auth/routine memakai minimum CTA 54 dp dan kontrol solid; shared `IMFITButton` minimum 48 dp
dengan pressed scale. `RoutineButton.loading` menampilkan spinner dan label; shared
`IMFITButton.isLoading` mengganti isi dengan spinner. Signature tidak bisa ditukar begitu saja.
Auth control/routine control radius 10 dp; error dibatasi konteks, bukan modal full-screen.

`ImfitRedesignComponents.kt` masih menyediakan generic hero/stat/header helper. **Jangan
memilihnya hanya dari nama file**: root aktual memakai komponen khusus di tabel atas.

## 5. Artwork dan platform bridge

- `app/src/main/assets/muscle-categories` berisi 12 SVG: chest, back, shoulders, biceps,
  triceps, forearms, core, upper-legs, glutes, calves, cardio, all-muscles.
- Render SVG lewat `MuscleCategoryArtwork` (Coil `file:///android_asset/...`), bukan
  `painterResource` yang menganggap asset SVG sebagai Android drawable.
- Home aktif memakai `app/src/main/res/drawable-nodpi/imfit_strength_3d.webp`.
  Background gradient/glow dibuat `HomeStrengthBanner`; jangan membakar text/metric ke gambar.
- Artwork anatomi mempertahankan warna ilustrasi; jangan global tint. `categoryArtAsset(null)`
  memberi all-muscles. Subgroup library memakai `ExerciseCategory.artwork` tersendiri.
- `Symbols`/`Symbols.AutoMirrored` dari `IMFITSymbols.kt` adalah wrapper icon existing.
- `app/src/main/res/values/colors.xml` dan `app/src/main/res/values-night/colors.xml` adalah
  bridge window/launch/launcher. Update hanya bila token platform yang terkait berubah.

## 6. Adaptasi yang benar-benar ada dan gap

- Grid root Gerakan: 3 kolom bila lebar >= 360 dp dan fontScale <= 1.3; selain itu 2.
  Addendum 18 Oktober: tinggi baris/artwork mengikuti viewport tersisa dan pengukuran
  label, gap 8 dp; semua 12 muat bila ruang cukup, scroll bila font/tinggi membutuhkan.
   Tidak lagi memaksakan minimum baris 154 dp. Revisi terbaru pengguna: **semua halaman
   berheader** memakai `headerContentGap` 8 dp (menggantikan main-tab-only 12/0 dp).
   Ganti padding atas lama dan periksa padding komponen pertama agar tidak bertumpuk.
   Padding internal appbar/status bar tetap; dialog/bottom sheet tidak ikut aturan gap ini.
   Avatar/action appbar hanya di Home; Gerakan dan Progres tidak memilikinya, sedangkan
   profile row Progres tetap membuka Profil.
- Login/Profil memakai `IMFITPreferenceSizes`: track bahasa/tema 72×36 dp dengan target
  sentuh minimal 48 dp. Button Add exercise saja mengaktifkan `matchOutlineToContent`.
- Home section CTA stack bila lebar < 320 dp atau fontScale > 1.3.
- Dua progress metric cards stack pada kondisi yang sama; form config routine stack bila
  fontScale > 1.4. Angka ini spesifik komponen, bukan breakpoint universal setiap screen.
- Birth-date calendar mengizinkan horizontal scroll pada area 336 dp agar target 48 dp utuh.
- Progress calendar saat ini membagi lebar menjadi tujuh cell; minimum tinggi 48 dp tidak
  membuktikan lebarnya 48 dp pada perangkat sempit. Perlakukan sebagai gap review, bukan
  contoh aksesibilitas sempurna atau izin mengganti kalender tanpa scope.
- Ada literal ukuran dan copy legacy pada source. Jangan mengangkat semua yang sudah ada
  menjadi best practice; bedakan **observed implementation** dan **quality requirement**.

## 7. Modal backdrop — addendum working tree 18 Oktober 2026

- Keputusan terbaru: satu `IMFITOpacity.scrim = 0.50f` untuk dialog wrapper, kedua
  bottom sheet dan fullscreen sync. Backdrop digambar Compose; tidak ada blur.
- `IMFITPlatformDialog` adalah host fullscreen/edge-to-edge dengan native dim off/0.
  Content saja menerima safe/IME insets; batas lebar caller, guard dismiss dan draft tetap.
  Jangan mengaktifkan native dim di atas scrim Compose atau memaksa card dark putih.
- Back/secure/window-token tetap milik native Dialog. Outside tap hanya memanggil dismiss
  jika diizinkan; cleanup memulihkan atribut native. Jangan pindahkan logika timer/sync.
- Actual Register dan Home API 34 diuji; calendar/sheet Register memiliki RGB backdrop
  identik. Root cause lama dan direct visual approval tidak disimpulkan dari screenshot.
- Catatan ini bukan jaminan perilaku OEM/lintas-API.
