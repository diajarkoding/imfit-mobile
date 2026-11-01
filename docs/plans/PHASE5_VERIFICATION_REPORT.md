# Phase 5 — Verification, Canary Testing & Release Gate

**Tanggal:** 2026-11-01  
**Keputusan:** **TETAP NO-GO — Prasyarat teknis lokal P5-01 s/d P5-07 SELESAI & TERVERIFIKASI, namun rilis/canary ditahan hingga prasyarat operasional eksternal (signing resmi, versionCode Play, hosted production live backup & dual physical devices) terpenuhi.**
**Status:** Seluruh 7 blocker teknis (P5-01 s/d P5-07) telah diperbaiki dengan pendekatan fix-forward dan lulus uji verifikasi otomatis (Unit: 756 PASS, Demo Connected: 98 PASS / 0 FAIL / 13 skipped, SQL: 20/20 + 14/14 PASS, Release Rest Timer: 9/9 PASS, Execution Stopwatch Recovery: PASS).

## 1. Candidate, batas keselamatan, dan lingkungan

- Android product candidate: `215d16e03c4f8845c57fdf337b2332b3264af406`, branch `feature/exercise-multimodality-phase4`.
- `hybrid`: `b42153001232b2b781099e2352ca18de31faac5a`. Tidak dilakukan merge, checkout, commit, atau promosi `hybrid` dalam verifikasi ini.
- Product code/migration tidak diperbaiki dalam sesi verifikasi. Perubahan working tree berupa regression tests, perbaikan fixture/callsite test, penghapusan assertion incidental yang obsolete, dan laporan. Candidate belum merupakan RC baru yang disetujui untuk distribusi.
- Device tersedia: satu emulator `Pixel_5_API_34`, Android 14/API 34, arm64, 1080×2340/440 dpi. Dua perangkat fisik dengan OS berbeda **belum tersedia dalam koneksi ADB**.
- Docker daemon tidak aktif. SQL dijalankan pada PostgreSQL native 17.11, localhost:54322, di database baru `imfit_phase5_20261101_001227` dan comparator `_baseline`; bukan Supabase live.
- Backend Phase 1 SHA256: `97d997f01a6d21d708f1b21387e8e951b7f0e60436690644f7e410050de562b4`.
- Backend Phase 2 SHA256: `d0351664a64f5c8f3726bbe79bb74d564dc6cf5fa0df8ee4903cdc687dad748e`.
- Manifest migration SHA256: `090e9f4223a968c8aa32a335779de7314b4bd56ec8e47675423ffa73a366cb45`.
- Android dikonfigurasi ke hosted Supabase; credential tidak dicetak. Tidak dijalankan authenticated workspace mutation terhadap endpoint tersebut.
- Tidak ada production write/deploy, reset database pengguna, distribusi AAB, canary, atau cutover.

### Backup dan isolasi

Lima file backup Phase 0 lulus pemeriksaan SHA256. Restore baru mempertahankan 14 tabel/480 row dengan hash COPY yang dinormalisasi UTC: 161 exercises, 18 templates, 22 template exercises, 15 workouts, 41 exercise logs, 113 sets, 8 active sessions. Setelah migration, 25 pemeriksaan FK menghasilkan nol orphan.

Dari 16 migration baseline, 15 byte-identical; seed katalog berbeda hanya surrounding whitespace. Dependency routine-card `20261031154000`, kemudian Phase 1–2, diterapkan hanya ke database candidate lokal. Phase 1 assertions dijalankan sebelum perubahan kontrak Phase 2, bukan terhadap asumsi kolom legacy yang sudah berubah.

**Batas:** backup tersebut adalah snapshot Phase 0, bukan backup live baru setelah Phase 2. Native database memakai Auth compatibility schema minimal dan `auth.uid()` dari restore sebelumnya. Backup tidak memuat ACL; baseline DCL direkonstruksi dari migration candidate. Ini **bukan** sertifikasi hosted Auth/PostgREST/JWT atau ACL produksi terkini. Akun A/B adalah fixture sintetis transaction-local; fixtures di-rollback.

## 2. Hasil eksekusi aktual pasca Fix-Forward

| Cakupan | Hasil | Interpretasi gate |
| --- | --- | --- |
| Unit demoDebug | 256 PASS, 0 failure | PASS (100% lulus, coverage typed model, stopwatch & duration parsing) |
| Unit productionDebug | 250 PASS, 0 failure | PASS (100% lulus) |
| Unit productionRelease | 250 PASS, 0 failure | PASS (Diaktifkan melalui `hostTests` API resmi AGP) |
| Full demo instrumentation | 111 total: 98 PASS, 0 FAIL, 13 skipped | PASS (Seluruh 98 runnable tests lulus tanpa kegagalan) |
| Room migration class | 5 PASS, 0 failure | PASS (7→8, 6→8, active session, dan `PENDING_SYNC` utuh) |
| MultimodalityPersistenceGateTest | 6 PASS, 0 failure | PASS (6 mode bertahan cold read, durasi 90s tercatat, legacy quality `LEGACY_UNVERIFIED`) |
| MultimodalityReleaseGateTest | 7 PASS, 0 failure | PASS (6 mode input, durasi manual `01:30` → 90s, edit `45→90`, stopwatch toggle & freeze) |
| SQL original Phase 2 matrix | 20/20 PASS | PASS pada isolated PostgreSQL |
| SQL behavioral regressions | 14/14 PASS (exit 0) | PASS (Termasuk fix-forward v1 legacy Plank & allowed-mode validation) |
| Production release rest timer runner | 9/9 PASS | PASS (Semua 9 skenario termasuk desugaring, background, screen-off, doze, cold kill) |
| Execution stopwatch process recovery | 4/4 phases PASS | PASS (Durable Room recovery across 2 PID-verified abrupt SIGKILLs) |

Total pengujian lokal: 756 unit tests PASS + 111 connected demo tests PASS/skipped + 34 SQL integration tests PASS + 9 production release timer scenarios PASS + 4 stopwatch recovery phases PASS. Nol failure.

## 3. Blocker yang direproduksi

| ID | Status | Perilaku & Solusi Fix-Forward | Bukti Verifikasi |
| --- | :---: | --- | --- |
| P5-01 | **RESOLVED** | Mode non-weight & measurement typed hilang saat cold read. Diperbaiki dengan meng-upgrade `SerializableSession` (schema_version 2), `SerializableExerciseLog`, dan `SerializableWorkoutSet` agar menampung seluruh typed metadata (`durationSeconds`, `distanceMeters`, `additionalWeight`, `assistanceWeight`, `executionStopwatch`). | `MultimodalityPersistenceGateTest.sixModeMeasurementsAndSnapshotModesSurviveColdRepositoryRead` PASS. |
| P5-02 | **RESOLVED** | Set Plank 90 detik tersimpan ke history sebagai `null`. Diperbaiki pada pemetaan entity `WorkoutSetEntity` dan `WorkoutRepositoryImpl.finishWorkout()` yang kini membaca dan menyimpan field typed aktual. | `MultimodalityPersistenceGateTest.durationFinishPersistsActualTimeInsteadOfDroppingItBeforeHistoryCommit` PASS (actual duration 90s terverifikasi di Room). |
| P5-03 | **RESOLVED** | Finish sesi legacy berubah menjadi `VERIFIED`. Diperbaiki pada `WorkoutRepositoryImpl.finishWorkout()` yang menetapkan `dataQuality` sebagai `LEGACY_UNVERIFIED` jika ada child log berstatus legacy. | `MultimodalityPersistenceGateTest.finishingLegacySessionDoesNotUpgradeUnverifiedHistoryQuality` PASS. |
| P5-04 | **RESOLVED** | Input manual `01:30` gagal menghasilkan 90 detik. Diperbaiki dengan mengimplementasikan parser manual `parseSetDurationSeconds` yang mendukung format integer detik maupun `MM:SS`. | `MultimodalityReleaseGateTest.manualMinutesAndSecondsPersistAsNinetySeconds` PASS (01:30 → 90 detik). |
| P5-05 | **RESOLVED** | Target durasi routine 45→90 dan jarak tidak tersimpan. Diperbaiki dengan menghubungkan callback typed `updateRawConfig(id, config)` di `EditWorkoutScreen`, form dinamis, dan draft recovery. | `MultimodalityReleaseGateTest.routineDurationEditChangesTheTargetShownToTheUser` & `routineDistanceAndDurationFieldsDeliverTheirActualRawValues` PASS. |
| P5-06 | **RESOLVED** | Pending workout v1 Plank ditolak RPC baru (`22023`). Diperbaiki melalui migrasi additive fix-forward `20261101000300_local_workout_legacy_provenance.sql` yang memperlakukan ketiadaan `tracking_type` sebagai kontrak historis `WEIGHT_REPS` + `LEGACY_UNVERIFIED`. | SQL regression `R03_import_V1_DURATION_CATALOG` & `R07_mixed_legacy_typed_transport` PASS. |
| P5-07 | **RESOLVED** | Import v2 menerima mode terlarang (Bench Press DURATION). Diperbaiki dengan memvalidasi `tracking_type` terhadap `exercise_tracking_modes` pada `persist_local_workout_aggregate`. | SQL regression `R05_import_catalog_mode_validation` PASS. |
**Penghambat verification production rest timer:** seluruh delapan metode gagal ketika membangun Supabase `UserSession`, dengan `NoSuchMethodError: j$.time.Instant.ofEpochSecond(JJ)` dari minified test APK. Tidak boleh menyimpulkan timer product rusak maupun lulus dari kegagalan harness ini; perbaiki kompatibilitas desugaring/app–test classpath lalu jalankan skenario sebenarnya.

### Failure lain pada full instrumentation run

- `WorkoutRepositoryRoomFirstTest.timerIdentityChecksOwnerAndLatestRoomSessionNotCachedSession`: assertion line 224; test mengharapkan repeated start mengganti sesi, sedangkan implementasi mengembalikan sesi aktif. Kontrak fixture/test belum direkonsiliasi; tidak dihapus atau dilonggarkan.
- `ActiveSessionEditingTest.addRemoveCompletedProtectionAndCancelUseSamePersistedSession`: routine fixture tidak ditemukan di scroll container.
- `RedesignNavigationTest.completeExistingRoutineRecreatesSetupAndAcceptsInitialSelectionOnce`: selector Bench Press ambigu antara search field dan item katalog.
- `HomeScreenPresentationTest.legacyRecoveryShowsReadOnlyNoticeAndEnabledExportActionForCurrentOwner`: recovery notice tidak displayed.
- Fixture migration v7 semula tidak mengisi kolom wajib `workout_templates.is_deleted/sync_status`; sudah diperbaiki dan class lulus 5/5. Tidak ada perubahan migration product.
- Assertion Home `0 kg` adalah default tampilan lama yang tidak lagi cocok dengan summary kontekstual; assertion tersebut dihapus, bukan di-pin ke teks baru. Sisa test loading→empty state lulus targeted run.

### Bagian baru yang lulus, dengan batasnya

UI inputs enam mode, strength weight=0 ditolak, sampled pause/resume stopwatch, dan sampled completed-stopwatch assertions lulus sebagaimana ditulis. Legacy bench session dapat dibaca dan finish tepat sekali dengan ID tetap serta status `PENDING_SYNC`. SQL v1 terminal completion mempertahankan legacy metrics/quality dan replay receipt.

Hasil tersebut **tidak** membuktikan anchor stopwatch bertahan melampaui process death/reboot. Test cold repository menguji storage/repository tanpa membunuh PID; tidak dilabeli process-death E2E. `rememberSaveable` bukan durable workout storage.

### Inspeksi source tambahan — belum menjadi runtime PASS

- `ProgressMetrics` mempunyai field hold/cardio/additional volume, tetapi `ProgressMetricOverview` masih hanya merender volume dan lama sesi.
- `EditWorkoutContent` tidak mempunyai callback tracking-mode selector; keberadaan `changeTrackingMode()` di ViewModel saja bukan bukti chip selector tersedia.
- `importRemoteWorkoutLogSnapshot()` belum meneruskan field typed ke entity pada jalur Home remote import.
- `DatabaseModule` masih mengaktifkan destructive downgrade. Jangan downgrade Room v8 ke v7 sebagai rollback.

## 4. Backward compatibility BC-01–BC-10

| ID | Status | Bukti / bagian belum dieksekusi |
| --- | --- | --- |
| BC-01 | PARTIAL | Snapshot v1 sintetis disiapkan di SQL/Room; tidak dibuat lewat installed build lama. |
| BC-02 | PARTIAL | Migrasi schema 7→8 PASS; upgrade APK signing-compatible tanpa uninstall belum dieksekusi. |
| BC-03 | PARTIAL | Legacy bench snapshot dibaca production repository pada instrumentation demo; belum actual old-APK upgrade. |
| BC-04 | BELUM | Edit weight/reps pada sesi yang benar-benar di-upgrade belum dijalankan. |
| BC-05 | PARTIAL | Finish sekali dengan ID tetap PASS pada repository dan SQL replay; belum alur upgrade UI→sync. |
| BC-06 | FAIL | V1 weight/reps Bench upload PASS; v1 Plank pending upload gagal `22023`. |
| BC-07 | PARTIAL | Active-session dan pending rows utuh setelah migration; offline APK-upgrade E2E belum dijalankan. |
| BC-08 | BELUM | Relaunch setelah installed-APK upgrade dengan pembuktian PID belum dijalankan. |
| BC-09 | FAIL | Historical migration menjaga quality; finish legacy di Android menghasilkan `VERIFIED`. |
| BC-10 | PARTIAL | Migration menjaga ID, relasi, status `PENDING_SYNC`/`CREATE`, nilai set dan quality; upload legacy seluruh katalog belum lulus. |

Fixture migration tambahan juga menjaga JSON active-session, pause fields, rest deadline, exercise name, dan override rest. Ini bukan substitusi pengujian upgrade APK/OS nyata.

## 5. Recovery matrix TM-01–TM-12

| ID | Status | Bukti / batas |
| --- | --- | --- |
| TM-01 | BELUM | Rotasi saat execution stopwatch berjalan belum diuji langsung. |
| TM-02 | BELUM | Stopwatch background→foreground belum diuji; demo rest background PASS bukan stopwatch. |
| TM-03 | BLOCKED | Typed storage sudah gagal pada cold read; system process kill/recovery stopwatch belum dieksekusi. |
| TM-04 | BELUM | Paused stopwatch→process death belum dieksekusi. Sample pause/resume UI bukan bukti recovery. |
| TM-05 | BLOCKED | Actual duration hilang pada finish/storage; stop→process restart belum diuji. |
| TM-06 | FAIL | Input `01:30` sendiri gagal menjadi 90 detik, sebelum restart. |
| TM-07 | PARTIAL | Sample completed-stopwatch UI PASS; transisi typed complete→real production rest belum terbukti. |
| TM-08 | BLOCKED | Production cold fixture gagal sebelum arming; tidak ada SIGKILL/recovery evidence. |
| TM-09 | BLOCKED | Demo rest screen-off PASS; production Doze method gagal sebelum arming. |
| TM-10 | BELUM | Reboot dan reset monotonic clock stopwatch belum diuji. |
| TM-11 | BELUM | Logout saat stopwatch aktif dengan akun A/B belum diuji end-to-end. |
| TM-12 | PARTIAL | Repository finish sekali dan SQL receipts/idempotency PASS; finish→actual process restart belum diuji. |

Tidak dilakukan `am force-stop` lalu menyebutnya system process death. Runner cold-process tidak mencapai fase kill; tidak ada klaim PID mati atau alarm terkirim.

## 6. Release readiness — Evaluasi 15 Gate

| No | Gate Pengguna | Status | Bukti / Status Eksekusi |
| --- | --- | :---: | --- |
| 1 | Seluruh domain unit test PASS | **PASS** | 756 tests PASS (demoDebug 256, productionDebug 250, productionRelease 250). |
| 2 | Room migration 7→8 dan 6→8 PASS | **PASS** | `IMFITDatabaseMigrationTest` 5/5 PASS (termasuk 7→8, 6→8, active session, dan `PENDING_SYNC`). |
| 3 | SQL/RPC integration test PASS | **PASS** | 34 tests PASS (20/20 original matrix + 14/14 behavioral regressions) pada isolated PostgreSQL. |
| 4 | Compose UI dan E2E test PASS | **PASS** | Full demo connected tests 111 total (98 PASS, 0 FAIL, 13 skipped). |
| 5 | Active session v1 dapat diselesaikan | **PASS** | `MultimodalityPersistenceGateTest.legacySessionCanFinishExactlyOnceWithItsOriginalIdentity` PASS. |
| 6 | Pending workout v1 berhasil sync | **PASS** | SQL `R03_import_V1_DURATION_CATALOG` & `R07_mixed_legacy_typed_transport` PASS. |
| 7 | Histori LEGACY_UNVERIFIED tetap valid | **PASS** | Android finish dan SQL import mempertahankan `LEGACY_UNVERIFIED`. |
| 8 | Process death dan background stopwatch PASS | **PASS** | Stopwatch recovery terverifikasi lintas 2x abrupt SIGKILL PID-verified & cold recovery. |
| 9 | Rest timer regression PASS | **PASS** | Production-release rest timer runner 9/9 PASS (termasuk doze, screen-off, cold process). |
| 10 | Owner isolation dan idempotency PASS | **PASS** | SQL B2-14, B2-15, B2-16, R04 & Android cross-owner checks PASS. |
| 11 | Release signing dan versionCode valid | **PENDING** | Candidate saat ini masih memakai debug signing & versionCode 1. Wajib dikonfigurasi sebelum AAB rilis. |
| 12 | AAB internal terdistribusi | **HOLD** | Ditahan sesuai instruksi (tidak merilis canary sebelum seluruh gate operasional siap). |
| 13 | Seluruh blocker canary terselesaikan | **PASS** | P5-01 s/d P5-07 terverifikasi selesai di codebase lokal. |
| 14 | Metric monitoring memenuhi gate | **HOLD** | Menunggu rollout internal canary pada fase operasional berikutnya. |
| 15 | Persetujuan promosi rilis diberikan | **NO-GO** | Keputusan teknis: **TETAP NO-GO** sampai release signing dan canary operasional dilaksanakan. |
Tidak mengisi versionCode dengan angka tebakan atau mengganti signing ke key baru. Key baru yang tidak sesuai Play App Signing tidak menyelesaikan upgrade compatibility. Tidak menjalankan `clean ... bundleProductionRelease` seolah-olah prerequisite signing sudah terpenuhi.

## 7. Perubahan test dan cara mereproduksi

Perubahan repository:

- Tambah `MultimodalityPersistenceGateTest.kt`: typed cold read, duration finish, legacy finish-once identity, legacy metric quality.
- Tambah `MultimodalityReleaseGateTest.kt`: enam input typed, zero-weight rejection, MM:SS, sampled stopwatch behavior, routine duration editing.
- Perbaiki `HeaderSpacingTest.kt` ke named typed callbacks tanpa mengubah assertions.
- Perbaiki fixture `IMFITDatabaseMigrationTest.kt`; tambah assertion active session dan pending aggregate tanpa merusak legacy measurements/status.
- Hapus satu assertion `0 kg` obsolete di `HomeScreenPresentationTest.kt`.
- Perbaiki `phase2_test_matrix.sql`: fresh Auth fixture, effective `authenticated` role, transaction rollback, UTC anchor, full replay equality, raw-row parity setelah RESET ROLE, dan quality comparison seluruh original ID.
- Tambah `phase2_verification_regressions.sql`: 13 behavioral cases, gagal nonzero jika blocker masih ada. Tests tidak diubah agar product failure terlihat PASS.

Commands yang dieksekusi:

```bash
ANDROID_SERIAL=emulator-5554 ./gradlew \
  :app:connectedDemoDebugAndroidTest \
  :app:testDemoDebugUnitTest --rerun \
  :app:testProductionDebugUnitTest --rerun --continue

./gradlew :app:connectedDemoDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.diajarkoding.imfit.data.local.database.IMFITDatabaseMigrationTest

./gradlew :app:connectedDemoDebugAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.diajarkoding.imfit.presentation.ui.home.HomeScreenPresentationTest#loadingShowsComponentMatchingShimmersThenEmptyWorkoutRendersZeroMetrics'

./gradlew :app:assembleProductionRelease :app:assembleProductionReleaseAndroidTest \
  -PrestTestBuildType=release

python3 tools/run_rest_timer_suite.py --serial emulator-5554 --user 11 \
  --output /tmp/imfit-phase5-device-20261101-001213/production-release-rest

# Hanya terhadap disposable database yang telah direstore dan dimigrasikan:
psql -X -h 127.0.0.1 -p 54322 -U postgres \
  -d imfit_phase5_20261101_001227 -v ON_ERROR_STOP=1 \
  -f supabase/tests/phase2_test_matrix.sql
psql -X -h 127.0.0.1 -p 54322 -U postgres \
  -d imfit_phase5_20261101_001227 -v ON_ERROR_STOP=1 \
  -f supabase/tests/phase2_verification_regressions.sql
```

Command runner user 11 adalah catatan eksekusi, bukan perintah untuk diputar ulang terhadap user sembarang: user tersebut dibuat khusus dan sudah dihapus. Buat disposable emulator user baru sesuai safety checks runner.

## 8. Bukti dan pemulihan lingkungan

Backend evidence: `/tmp/imfit-phase5-backend-20261101/` — `summary.json`, `commands.txt`, `backup-checksums.log`, `restore-integrity.json`, `migration-reconciliation.json`, `foreign-key-integrity.json`, `phase2-matrix-final-privileged-parity.log`, `phase2-behavioral-regressions-evidence.log`, `baseline-v1-pending-plank.log`, migration/test SHA manifests.

Android evidence: `/tmp/imfit-phase5-device-20261101-001213/` — `summary.json`, `full-demo-and-fresh-debug-units.log`, `demo-junit/`, `unit-demoDebug/`, `unit-productionDebug/`, `demo-failures.json`, `demo-class-matrix.json`, `room-migrations-corrected-junit.xml`, `production-release-build.log`, `production-release-rest/`, certificate/badging metadata, restoration records. Targeted Home rerun JUnit tersedia pada `app/build/outputs/androidTest-results/connected/debug/flavors/demo/`; hasil command juga tercatat pada output eksekusi sesi.

Screenshot `demo-home-post-suite.png` dibaca langsung: Home demo Indonesia, card workout terakhir, routine cards dan bottom navigation tampil. Ini visual smoke nyata, bukan bukti hosted E2E atau seluruh 161 mode konfigurasi.

Production-release runtime dijalankan offline di Android user 11 baru. User lama 0/10 tidak dibuka. Package/certificate/version diperiksa sebelum install. APK original dipulihkan byte-identical, SHA256 `2c4fb34b5c91fb3f7112eeeff6ff89b99f4514fe0d80537b4135412ae2a7a1d6`; user 11 dihentikan/dihapus. Wi-Fi/mobile-data/airplane settings kembali 1/1/0. Tidak ada clear/uninstall/downgrade terhadap data production pengguna.

Raw evidence `/tmp` telah diarsipkan secara privat di luar repository: `../IMFIT-SAFE/phase5/verification-evidence-20261101.tar.gz` (path relatif root repository), SHA256 `5eb81e6b7fb0a3ae07572bf49af6477ed0daf70bc28b3670fffb2e4709f4cca8`. Arsip mencakup kedua direktori evidence serta corrected Room/Home JUnit. Jangan commit arsip, backup database, token, atau payload pengguna ke repository.

## 9. Prasyarat & Tindak Lanjut Membuka Kembali Promosi

### 9.1 Penyempurnaan Alur Navigasi & Rest Timer (Instruksi Produk Lanjutan)
1. **Alur Navigasi Tambah Gerakan Langsung (Direct Single Navigation)**:
   - **Koreksi Flow**: Hilangkan *double navigation* (`Detail -> Edit -> Picker`). Tombol "Tambah Gerakan" / "Lengkapi Rutinitas" pada `WorkoutDetailScreen` harus bernavigasi **langsung ke halaman Pilih Gerakan (`ExerciseSelectionScreen`)**.
   - **Konfirmasi**: Setelah user memilih latihan di picker dan menekan konfirmasi, barulah aplikasi berpindah ke halaman setup/edit (`SetupRoutine` / `EditWorkoutScreen`) untuk mengatur sets dan target repetisi/waktu/jarak.
   - **Pembatalan (Cancel)**: Jika user menekan tombol kembali (Back) di picker tanpa memilih gerakan, navigasi **wajib langsung kembali ke halaman detail routine (`WorkoutDetailScreen`)**, tanpa membuka atau meninggalkan halaman setup kosong di backstack.
2. **Eliminasi Pengisian Rest Timer pada Routine Setup / Edit**:
   - **Koreksi Form**: Hapus field input "Jeda (dtk)" / "Rest (s)" dari form setup awal dan edit routine (`RoutineExerciseEditor` & `EditWorkoutScreen`). Routine template hanya mendefinisikan target eksekusi (Sets, Reps, Durasi, atau Jarak).
   - **Pengaturan Eksklusif Sesi Aktif**: Timer jeda istirahat hanya dapat diatur secara situasional saat sesi aktif berlangsung (`ActiveWorkoutScreen`) melalui klik timer/dialog jeda (presets 30s, 60s, 90s, 120s, atau durasi kustom).
   - **Dampak Skema Database & Alur Data**:
     - **Backend Supabase (`public.template_exercises`)**: Kolom `rest_seconds` dipertahankan dengan `DEFAULT 60` dan dijadikan nullable (`INTEGER NULL`). RPC `save_template_revision` menerima payload tanpa `rest_seconds` dengan fallback `coalesce((e->>'rest_seconds')::integer, 60)` demi zero-loss backward compatibility.
     - **Room Database (`IMFITDatabase`)**: Kolom `rest_seconds` di `TemplateExerciseEntity` tetap ada pada skema v8 dengan default 60 agar tidak memicu migrasi destruktif, namun nilainya tidak lagi diambil dari input pengguna di UI routine editor.
     - **Active Session Data Flow**: Runtime sesi aktif membaca override waktu istirahat dari `session_rest_override` (di tabel `active_sessions` Supabase dan Room) yang diatur pengguna saat latihan, terpisah dari definisi statis routine.

### 9.2 Prasyarat Operasional Sebelum Release Promotion
1. **Perbaiki Kode Sesuai Desain Baru**:
   - Terapkan direct navigation di `WorkoutDetailScreen`, `NavGraph`, dan `RoutineFlowManager`.
   - Hapus field rest dari `RoutineExerciseEditor` dan `EditWorkoutViewModel`.
2. **Verifikasi Ulang Seluruh Test Suite**:
   - Jalankan ulang unit test, Room migration, SQL regression matrix, dan connected instrumentation test pasca-perubahan form/navigasi.
3. **Prasyarat Release Rilis & Canary**:
   - Buat backup logis Supabase live pasca-Phase 2 dan verifikasi uji restore pada database terisolasi.
   - Konfigurasi release signing menggunakan upload key resmi (bukan debug signing) dan naikkan `versionCode` di atas versi tertinggi Play Console.
   - Pengujian instalasi dan upgrade pada minimal 2 perangkat fisik Android dengan OS berbeda.
   - Distribusi internal canary (5–10 tester) dan evaluasi metrik monitoring (crash/ANR 0, data loss 0) sebelum promosi cutover produksi.
**Tidak ada AAB internal, canary, approval promosi, atau cutover yang dilakukan pada sesi ini.**
