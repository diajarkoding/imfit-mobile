# Sesi aktif, dialog, dan ringkasan sesi

18 Oktober 2026 · branch `redisgn` · working tree, tanpa commit/push.

## Hasil dan batas scope

Implementasi yang sudah ada disempurnakan, bukan menimpa perubahan pengguna di layar lain.
Acuan visual dibaca melalui MCP Pencil dari `imfit.pen`; dokumen desain tidak diubah.
Keluarga komponen: Training palette, Inter/Material typography, routine task controls,
surface ber-outline, footer task, dan backdrop dialog native 60% tanpa blur.

- Sesi aktif: overview set/volume, pause/all-complete banner, kartu gerakan, input set,
  empty/loading, footer, feedback operasi existing.
- Input set memakai nomor set tersimpan, key stabil, label aksesibel per gerakan/set,
  target 48 dp, serta format beban desimal ID/EN tanpa pemotongan ke integer.
- Pada 320 dp/font besar, input dan aksi set disusun dua tingkat; metrik/footer/dialog
  ditumpuk agar teks tidak diperkecil dan aksi tetap dapat dijangkau melalui scroll.
- Aksi header, input, completion, tambah/hapus, dan pengaturan dinonaktifkan saat operasi
  konflik. Label menyimpan tetap menampilkan konteks sesi.
- Dialog batal menjelaskan dampak untuk 0, 1, sebagian, dan semua set selesai.
  Judul, pesan, dan kedua tombol mengambil resource locale yang sama.
- Draft durasi/suara/getaran hanya diterapkan saat Simpan. Batal tidak menerapkan draft.
- Countdown angka tetap dari state ViewModel/coordinator; arc bukan dekorasi statis.
  Denominator memakai durasi committed dan extension generation kontrak coordinator saat ini.
- Ringkasan menampilkan outcome hanya bila log tersedia, metrik existing, rincian set
  selesai, legitimate zero-completed exercise, loading, dan unavailable dengan aksi Beranda.

## Referensi Pencil yang diperiksa

Beberapa ID merupakan section heading atau subtree, bukan layar mandiri.

| Area | Node ID |
| --- | --- |
| Sesi kosong/dimulai/berlangsung/jeda/semua selesai | `IGQii`, `WCkwt`, `gLWRF`, `WwE9Y`, `FwgYk` |
| Heading dan empat variasi batal | `J7D1Fw`, `BtniG`, `U6LNX`, `y9wO5Y`, `S8UATo` |
| Heading dan pengaturan istirahat light/dark/compact | `cU48K`, `Jvno8`, `SwWzj`, `VTlE0` |
| Countdown committed/light/dark | `pcxwm`, `CzJHY` |
| Heading ringkasan, loading, unavailable | `pmjYU`, `m3UAk`, `pvgcI` |
| Ringkasan semua set/selesai lebih awal/dark EN | `Ehc3Y`, `MPzJt`, `hSZjh` |
| Status bar dan heading state pendukung | `JqkjO`, `Y4BFM9` |
| Subtree app bar/content/footer | `FjyJL`, `ANXVy`, `N3FQqi` |
| Loading, dark EN, saving, gagal finish, compact | `k5VwUZ`, `Zab8H`, `KlwME`, `wGrE0`, `aPLh0` |

Deviasi disengaja: tombol −30 detik tidak ditambahkan karena coordinator hanya mempunyai
kontrak extend +30 dan skip. Tidak dibuat timer/deadline baru untuk menyamai mockup.
Loading tidak diperlakukan sebagai sesi kosong; unavailable bukan sukses atau error retry rekaan.

## Kontrak state dan navigasi

`NavGraph.ActiveWorkout` → `ActiveWorkoutScreen` → intent ViewModel → mutasi repository lokal.
Hasil picker tetap terikat session ID dan dikonsumsi sekali. Finish menavigasi hanya dari
`workoutLogId`, bukan dari tombol; ringkasan memuat log tersebut. Cancel tetap melalui
`requestCancelSession`; tidak ada save langsung dari composable.

Guard paused/finishing/cancelling, completed-set protection, prefill, volume semua set berisi
data, durasi yang mengecualikan pause, Room/repository, service/notification permission,
timer engine/coordinator, schema dan sync tidak diubah dalam penyempurnaan ini.

State unavailable summary tidak mempunyai error/retry terpisah di ViewModel existing.
Filtered no-results N/A: kedua layar ini bukan pencarian. Tidak ada state produk baru.

## Source utama

Path Kotlin relatif terhadap `app/src/main/java/com/diajarkoding/imfit/`:

- `presentation/ui/workout/ActiveWorkoutScreen.kt`, `ActiveWorkoutComponents.kt`,
  `ActiveSetRow.kt`, `ActiveWorkoutPresentation.kt`, `WorkoutDialogActions.kt`.
- `presentation/ui/workout/WorkoutSummaryScreen.kt`, `WorkoutSummaryComponents.kt`,
  `WorkoutSummaryPresentation.kt`.
- `presentation/components/common/IMFITPlatformDialog.kt`: mempertahankan resources,
  configuration, density, dan context parent dalam dialog; lifecycle/backdrop existing tetap.
- Resource pasangan `values`/`values-in`: `active_session_strings.xml`, `workout_summary_strings.xml`.
- Unit: `ActiveWorkoutPresentationTest`; instrumentation: `ActiveWorkoutScreenTest`,
  `WorkoutSummaryScreenTest`, `WorkoutInteractionTest`, `WorkoutTestEnvironment`.

## Verifikasi aktual

| Status | Pemeriksaan |
| --- | --- |
| PASS | `:app:compileDemoDebugKotlin`, `:app:compileProductionDebugKotlin` |
| PASS | `:app:testDemoDebugUnitTest`: 148 tes, 0 failures/errors |
| PASS | `:app:testProductionDebugUnitTest`: 141 tes, 0 failures/errors |
| PASS | `:app:compileDemoDebugAndroidTestKotlin` |
| PASS | `:app:connectedDemoDebugAndroidTest` dengan enam class terpilih: 27 tes, 0 gagal, Pixel_5_API_34 / Android 14 |
| PASS | Review screenshot sistem terpilih dibanding referensi MCP: sesi, compact scrolled content/footer, cancel EN, countdown compact, summary compact |
| PASS | `git diff --check` untuk source/resource target |
| FAIL | `:app:lintDemoDebug`, `:app:lintProductionDebug`: masing-masing 2 error `NonObservableLocale` di `ui/auth/AuthBirthDatePicker.kt:48` dan `ui/auth/RegisterScreen.kt:158`, di luar scope. Tidak ditutupi dengan baseline/suppression |
| BLOCKED | Gate lint keseluruhan belum bersih karena dua file auth tersebut |
| NOT_TESTED | Perangkat fisik/OEM, TalkBack dan IME manual, landscape, process-death durability, failure injection DB, serta regresi timer background/release baru |

Class instrumentation: `ActiveWorkoutScreenTest` (5), `WorkoutSummaryScreenTest` (4),
`WorkoutInteractionTest` (5), `ActiveSessionEditingTest` (1), `ModalDimTest` (7),
`RedesignNavigationTest` (5). Fixture menggunakan constraints 320/412 dp dan resource locale
eksplisit tanpa mengganti preferensi bahasa/density perangkat. Dialog diuji hingga font 1.5×;
sesi/ringkasan compact 1.3×. Tes draft tidak mengubah permission atau akun.
Existing demo flow integration memakai setup permission-nya sendiri; bukan perubahan runtime aplikasi.

Bukti build lokal:

- Unit XML: `app/build/test-results/testDemoDebugUnitTest/`, `testProductionDebugUnitTest/`.
- Instrumentation: `app/build/reports/androidTests/connected/debug/flavors/demo/index.html`.
- Screenshot sistem: `app/build/outputs/connected_android_test_additional_output/demoDebugAndroidTest/connected/Pixel_5_API_34(AVD) - 14/workout-redesign-evidence/`.
- Lint: `app/build/reports/lint-results-demoDebug.html`, `lint-results-productionDebug.html`.

Screenshot adalah fixture demo/sintetik, bukan akun produksi, approval pixel-perfect, atau
bukti seluruh perilaku perangkat. Loading berupa skeleton; tidak diklaim animasi shimmer.
Working tree lain tetap dipertahankan. Tidak ada commit, push, atau perubahan desain Pencil.
