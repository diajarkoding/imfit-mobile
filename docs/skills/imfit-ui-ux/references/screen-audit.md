# Screen dan route audit

Baseline: `redisgn` pada `3ec72efbf43dc8b749aa3e4ae413a4a0373d8fa7`.
Path pendek relatif terhadap `app/src/main/java/com/diajarkoding/imfit/`.
Audit ini source-only: status desain berarti pola terimplementasi, bukan visual approval baru.

## 1. Peta flow aktual

Owner graph: `presentation/navigation/NavGraph.kt` dan `Routes.kt`.
Owner root tabs: `presentation/ui/main/MainScreen.kt`.

```text
Splash → Login ↔ Register → Main atau RegisterConfirmation → Login
Main
  Home → create-name dialog → WorkoutDetail(routine kosong)
       → WorkoutDetail(routine existing) → EditWorkout → ExerciseSelection(forEdit)
       → ActiveWorkout bila kartu adalah sesi aktif
  Gerakan → ExerciseList(category) → search katalog dalam kategori
  Progres → WorkoutHistory(date) / YearlyCalendar(year, month) / Profile
WorkoutDetail → ExerciseSelection → kembali, simpan penambahan ke routine
EditWorkout → ExerciseSelection → kembali, tambah ke draft, Save Changes terpisah
ActiveWorkout → ActiveExerciseSelection → kembali, mutasi sesi aktif (bukan template)
```

`CreateTemplateScreen.kt` masih ada, tetapi tidak memiliki entry/import pada NavGraph aktif.
Jangan menyatakan itu jalur “Tambah rutinitas” utama atau menyambungkannya sebagai bagian
refactor visual tanpa keputusan scope. Presence file/preview bukan reachability.

## 2. Login

Source: `presentation/ui/auth/LoginScreen.kt`, `LoginViewModel.kt`, `AuthComponents.kt`.

- Komposisi: preferensi ID/EN + theme di atas, brand mark biru, welcome/supporting copy,
  email/password, inline operation error, CTA login, redirect register, versi app saat IME tutup.
- State: `LoginState` memisahkan field errors, `isLoading`, `errorMessage`, `loginSuccess`.
  Input bertahan saat request gagal; field error dibersihkan saat field diedit.
- Validation saat audit: email nonblank/valid, password nonblank dan minimum 6 karakter.
  Ini **bukan** aturan strength register; jangan menyamakan keduanya dalam redesign UI.
- ViewModel menolak submit ulang ketika loading/success. Success effect memanggil callback;
  graph reset ke Main. Error auth dipetakan ke string resource; tidak mengungkap keberadaan akun.
- Form scrollable dengan status/navigation insets dan IME; password visibility memakai
  state presentasi saveable. Jangan mengganti operation error menjadi halaman kosong.

## 3. Register dan overlay pendukung

Source: `presentation/ui/auth/RegisterScreen.kt`, `RegisterViewModel.kt`,
`AuthBirthDatePicker.kt`, `AuthProfilePhotoField.kt`, `RegisterConfirmationScreen.kt`.

- Komposisi: back/header, nama/email/tanggal lahir/password/konfirmasi, foto opsional,
  error operasi/foto terpisah, submit dan redirect.
- Validation: nama/email/tanggal lahir wajib; password >= 8 dengan uppercase/lowercase/digit;
  konfirmasi harus cocok. Foto tidak menjadi persyaratan sukses.
- Satu overlay `RegisterOverlay`: None/PhotoSource/BirthDate; pengembalian fokus ke trigger.
  Picker tanggal menyimpan ISO date, menampilkan locale, melarang tanggal masa depan;
  tahun awal visual bukan data tanggal lahir pengguna.
- Source sheet kamera/galeri memakai Activity Result, permission kamera dan FileProvider.
  Denial/cancel/picker failure tidak boleh menghapus form atau dianggap registration failure.
- Hasil `RegisterResult.Authenticated` → Main; `CheckEmail` → confirmation, **bukan** login
  sukses palsu. Result dikonsumsi setelah routing. Back/input/redirect dibatasi saat submit.
- File preview menunjukkan variasi visual; itu bukan bukti runtime registration/permission lulus.

## 4. Beranda dan tambah rutinitas

Source: `presentation/ui/home/HomeScreen.kt`, `HomeViewModel.kt`, `HomeComponents.kt`.

- Header dan bottom navigation dimiliki MainScreen. Konten: greeting aktual, banner strength,
  ringkasan sesi terakhir (tanggal, durasi, volume), status sync kondisional, daftar rutinitas.
- Home mengambil template dan last workout melalui observer repository, bukan statistik
  gambar/mockup. Last workout dipasok `observeLastWorkoutLog`; jangan mengganti makna latest.
- Loading memakai shimmer per bagian; legitimate empty rutinitas berbeda dari last-workout
  kosong. Kartu active punya border/status resume dan menuju ActiveWorkout.
- Tambah rutinitas adalah `RoutineDialog` + `RoutineNameField`: nama wajib, submit guard,
  error validasi terpisah dari error operasi, nama dipertahankan saat gagal.
- `createWorkout` memakai nama trim dan `exercises = emptyList()`. Sukses lokal memberi ID,
  dialog ditutup, result di-clear, lalu navigate ke WorkoutDetail untuk menambah gerakan.
- HomeState belum punya generic load-error flag; load exception hanya menghentikan loading.
  Catat gap ini bila task menyentuh load-error UX; jangan mendokumentasikan retry state
  full-screen yang belum diimplementasikan sebagai fitur existing.
- `HomeSyncStatusCard` ditampilkan caller ketika pending > 0, SYNCING, atau FAILED;
  dukungan OFFLINE di helper tidak berarti card offline selalu tampil saat pending = 0.

## 5. Gerakan/latihan: root kategori vs daftar vs picker

Source: `presentation/ui/exercise/ExerciseBrowserScreen.kt`, `ExerciseCategory.kt`,
`ExerciseListScreen.kt`, `ExerciseBrowserViewModel.kt`.

- Root adalah **12 kategori presentasi**, termasuk Semua; grid 3/2 kolom dengan artwork SVG.
  Tidak ada query field, remote load, atau daftar “popular” pada root saat baseline ini.
- Tap kategori membuka `ExerciseList(category.name)`. Daftar memiliki query repository dengan
  debounce 300 ms, cancel request sebelumnya, distinct ID dan sort ID; filter kategori di screen.
- Loading shimmer, error/retry, katalog kosong, query no-results, dan subgroup-empty punya
  cabang tampilan berbeda. Card exercise pada daftar ini bukan route detail exercise baru.
- `ExerciseCategory` bukan enum persisten `MuscleCategory`. Forearms/Glutes/Calves memakai
  verified ID map, bukan tebakan nama; subgroup remote kosong bisa valid. Jangan mengubah
  ordinal/ID domain untuk membuat 12 tile tersedia.
- Picker routine memiliki ViewModel/filter terpisah: jangan menyalin debounce/network
  milik daftar ke picker yang memfilter `allExercises` lokal.

## 6. Progres

Source: `presentation/ui/progress/ProgressScreen.kt`, `ProgressViewModel.kt`,
`ProgressComponents.kt`, `ProgressPresentation.kt`.

- Root shell meniadakan avatar action header untuk Gerakan dan Progres; avatar Home dan
  profile row di konten Progres tetap membuka Profile.
- Tampilan aktif: profile row, **dua metric cards** (volume dan durasi), kalender bulan berjalan,
  summary tanggal terpilih. Tidak ada hero/streak/four-stat overview yang aktif pada source ini.
- Kedua metrik memakai **bulan kalender yang sama**, window start bulan inclusive → bulan
  berikutnya exclusive, zona lokal; dedup log ID, exclude future start. Nama resource
  `progress_overview_weekly_time` tidak berarti perhitungan mingguan: text-nya “Durasi latihan”.
- `monthlyWorkouts` dan `monthlyCompletedSets` masih dihitung model, tetapi tidak ditampilkan
  sebagai dua tile tambahan. Jangan membuat klaim streak/popularity/konsistensi dari sana.
- State: loading, hasError/retry, content termasuk zero-history. Observer Room log memperbarui
  tampilan; fetch avatar yang gagal tidak boleh menyembunyikan histori lokal.
- Kalender Monday-first, padding null, selection saveable. Tap tanggal dengan log langsung
  membuka histori; tanggal kosong hanya dipilih. Heading bulan membuka YearlyCalendar dengan
  year/month yang sama. Screen overview tidak mengimplementasikan arrow next/previous month.
- Today masuk deskripsi aksesibilitas; selected dan has-workout punya treatment visual.
  Jangan mengklaim semua indikator visual/touch width sudah tervalidasi di perangkat.

## 7. Detail/edit rutinitas

Source: `presentation/ui/workout/WorkoutDetailScreen.kt`, `WorkoutDetailViewModel.kt`,
`EditWorkoutScreen.kt`, `EditWorkoutViewModel.kt`; shared `ImfitRoutineComponents.kt`.

- Detail: app bar back/edit/delete, routine name/category, jumlah gerakan/set dan estimasi,
  exercise rows, empty prompt/add action, fixed footer Start/Resume.
- Estimasi saat ini `exerciseCount * 8` menit; ditandai ±. Jangan mengubahnya ke perhitungan
  baru atau mengklaim durasi aktual dalam task visual.
- Empty routine sah disimpan, tetapi Start disabled tanpa gerakan. Adanya sesi aktif lain
  membatasi edit/delete/add/start; resume hanya untuk routine yang sedang aktif.
- Delete memakai dialog destructive dan guard proses, error tetap dalam dialog; tidak
  navigate back sebelum `isDeleted`. Detail di-observe sehingga rename/add lokal terlihat.
- Edit adalah draft name + exercises + config set/reps/rest; save eksplisit. State terpisah
  `hasLoaded/isLoading/isSaving/isAdding/invalidConfigIds/errorRes/nameErrorRes/isSaved`.
- Input config mempertahankan raw string lokal, lalu publish hanya angka valid:
  sets/reps > 0, rest >= 0. Nilai kosong/invalid memblok Save, tidak diam-diam di-clamp.
- Daftar key berdasarkan ID; remove/add menjaga uniqueness dan invalid-config IDs yang relevan.
  Jangan memakai index sebagai identitas draft input yang survive penghapusan/reorder.
- Error load/retry berbeda dari gagal menambah ID dan gagal save. Retry add menyimpan pending
  IDs; save memvalidasi data lokal sebelum repository update. Perubahan tidak menulis ulang histori.

## 8. Tambah gerakan ke rutinitas

Source: `presentation/ui/exercise/ExerciseSelectionScreen.kt`, `ExerciseSelectionViewModel.kt`,
`presentation/navigation/NavGraph.kt`, `Routes.kt`.

- Task app bar berisi konteks routine; query + clear, quick filters Semua/Dada/Punggung,
  tombol full filter sheet, hasil/count, multi-select rows, footer count/reset/confirm.
- Sheet memakai **draft category**; Apply baru mengubah filter aktif, Cancel tidak menerapkan.
- Selection berdasarkan ID; pilihan tersembunyi karena filter/query tetap terpilih. Reset
  selection berbeda dari clear filters. Row toggle dan checkbox tidak boleh memicu dua toggle.
- Exclusions dari caller edit adalah ID draft saat ini, bukan hanya routine yang tersimpan.
  Tanpa explicit exclusions, picker dapat mengambil ID existing dari repository.
- Loading/retry error, no-results karena filter, katalog kosong, dan semua existing excluded
  perlu ditangani tanpa membuat data exercise palsu.
- Confirm sekali → kirim IDs ke graph → pop picker → owner mengonsumsi hasil. Pada detail,
  penambahan disimpan melalui repository. Pada edit, hanya menambah draft sampai Save.
- `ActiveExerciseSelection` terpisah dan membawa sessionId. Jangan menyatukan state hasilnya
  dengan routine template; perubahan sesi aktif bukan edit template/history.

## 8a. Profil — addendum 18 Oktober 2026

Baseline commit di atas tetap berlaku untuk bagian lain. Profil kini memakai perubahan
working tree yang belum di-commit saat addendum ditulis.
Source: `presentation/ui/profile/ProfileScreen.kt`, `ProfileComponents.kt`,
`ProfilePresentation.kt`; state/aksi tetap milik `ProgressViewModel`.

- Task app bar/back, identitas tanpa hero/gradient card, informasi hanya baca, tema,
  bahasa ID/EN, dan logout existing. Tidak ada edit profil/foto atau statistik baru.
- Initial loading tanpa identitas memakai skeleton. Error tanpa identitas menyediakan
  retry `loadData()`. Identitas yang sudah tersedia tetap terlihat saat reload/error:
  `ProgressState.hasError` juga dapat berasal dari observer histori.
- ISO birth date diformat menurut locale untuk tampilan; null/blank menjadi em dash,
  teks legacy non-ISO dipertahankan. Jangan mengubah nilai tersimpan.
- Avatar memakai `IMFITProfilePhoto` dengan override placeholder semantic; default
  komponen shared untuk caller lain tidak berubah.
- Theme row memiliki switch semantics; ID/EN berupa dua opsi radio dengan target minimal
  48 dp. Memilih opsi aktif tidak memanggil toggle. Callback root tetap owner preferensi.
- Header menumpuk pada lebar konten <320 dp atau fontScale >1.3. Konten scroll; teks tidak
  dibatasi maxLines/ellipsis. Gunakan resource `profile_strings.xml` ID/EN.
- Route `Profile`, logout dan ViewModel tidak diubah. Tidak ada state progress/logout-error
  baru; jangan menganggap screenshot membuktikan real-account auth atau process recovery.

## 9. Perbedaan dokumen historis dan batas audit

Addendum working tree 18 Oktober 2026: polish 1–7 mengubah gap main menjadi 0 dp (revisi awal dari 12 dp), grid kategori agar fit viewport, ukuran preference track,
dan border Add exercise. Permintaan notifikasi kini pada Start di Detail **sebelum**
session dibuat, bukan ketika ActiveWorkout dibuka. Denial tidak memblokir latihan;
resume tidak meminta ulang. Snackbar keterbatasan generik otomatis dihapus, bukan engine
capability/fallback atau error penyimpanan. Mapping nama metadata memakai isi JSON string,
bukan serialisasi/strip kutip global.

Revisi 19 Oktober 2026: `IMFITSpacing.headerContentGap = 8.dp` menggantikan 0/12 dp itu
untuk **semua halaman berheader**, bukan hanya main tabs; avatar appbar Gerakan dihapus.
Padding internal header/inset dan jarak antarkonten lain tetap.

Iterasi desain tiga root screen yang lama memakai pola: 8 kategori, 4/2 kolom, search root, hero/four metrics Progres, dan window metrik
berbeda. Source aktif serta test saat baseline di atas lebih baru. `ImfitRedesignComponents`
atau `CreateTemplateScreen` yang masih ada tidak membuktikan route/pattern lama aktif.

Gap yang **tidak diperbaiki oleh skill ini**: error load Home minim, legacy create tanpa
route/error operasi lengkap, literal copy di initial-sync overlay, progress calendar cell
width sempit, dan persistensi draft process-death yang tidak menyeluruh. Wajib audit ulang
jika area itu jadi target; jangan memperluas scope hanya untuk merapikan seluruh repo.
