# IMFIT Mobile App PRD v1.2.2 — Existing Implementation Baseline

> Bahasa: Indonesia. Scope: aplikasi mobile existing saja. Bukan PRD backend baru.
> File backend terpisah tetap berlaku: `docs/prd/imfit-backend-api-service.md` (status Proposed).
> Klaim teknis merujuk ke `path:line` pada commit baseline.

**Metadata dokumen**

| Field | Nilai |
|---|---|
| Versi | v1.2.2 |
| Tanggal revisi | 2026-10-13 |
| Commit baseline | `ff545a8` |
| Status review | Draft v1.2.2 — Pending Verification (revisi audit v1.2.1; belum freeze) |
| Owner | Product + Engineering |

**Riwayat revisi:** v1.0 baseline deskriptif → v1.1 correctness → v1.2 kontrak acceptance → v1.2.1 koreksi klasifikasi → v1.2.2 traceability (mapping FR→AC per perilaku, FR-SEC khusus, AC finish/rest-override/template-sync, tiga kategori uji).

## 1. Ringkasan Dokumen

### 1.1 Tujuan
Baseline produk IMFIT mobile. Dokumen ini **tidak menyatakan production readiness** — readiness butuh bukti pengujian (§6 hasil verifikasi, §9).

### 1.2 Scope sumber
Perilaku user-facing di `app/src/main/java/com/diajarkoding/imfit`, termasuk source-set `demo`/`production`, `AndroidManifest.xml`, resources, dan `supabase/migrations/*.sql` yang relevan.

Out-of-scope (lihat §5.2): AI coach, social, Wear OS, web dashboard, export, manual backup, nutrisi.

### 1.3 Sumber kebenaran
Kode pada commit baseline. Dokumen lama hanya konteks.

### 1.4 Audiens
Product, Design, QA, Engineering.

### 1.5 Konvensi membaca (wajib — hasil audit v1.1)
Setiap requirement memakai tiga label terpisah agar QA tidak menganggap gap yang sudah diketahui sebagai hasil uji baru:

| Label | Arti |
|---|---|
| `CURRENT_BEHAVIOR` | Perilaku yang ditemukan di kode baseline |
| `TARGET_REQUIREMENT` | Perilaku yang seharusnya setelah improvement |
| `VERIFICATION_CASE` | Pengujian + expected result; punya hasil sendiri |

Setiap AC diberi tag:
- `[CURRENT]` — menguji perilaku existing (lulus = sesuai kode saat ini, termasuk bila kode menyimpan gap).
- `[TARGET_ONLY]` — menguji perilaku yang **belum** ada; gagal pada baseline adalah ekspektasi, bukan bug baru.
- `[DIVERGES]` — current dan target berbeda; dicatat dua-duanya.

Jenis pengujian (tiga kategori terpisah — penyesuaian audit v1.2.1):
- `CHARACTERIZATION_TEST` — mendokumentasikan perilaku existing, termasuk gap/risiko. Hasil: `BASELINE_REPRODUCED` (gap tereproduksi = lulus sebagai dokumentasi) — **tidak boleh** dihitung sebagai release acceptance.
- `BASELINE_VERIFICATION` — menguji perilaku existing yang **tidak** masuk release gate. Default untuk AC `[CURRENT]` kecuali ditandai lain. Hasil: `TARGET_PASSED` / `FAILED` / `NOT_TESTED`.
- `RELEASE_ACCEPTANCE` — hanya untuk AC yang secara eksplisit masuk release scope. Hasil: `TARGET_PASSED` / `FAILED`. Gap yang masih ada = `KNOWN_GAP` dan memblokir klaim readiness sampai ditutup.

Catatan: tag `[CURRENT]` berarti perilaku ada di kode baseline — **bukan** berarti sudah lolos pengujian. Hasil `NOT_TESTED` tetap berarti belum ada evidence runtime.

Status tiga dimensi (tidak dicampur):

| Dimensi | Nilai |
|---|---|
| Implementasi | `IMPLEMENTED` / `WITH_LIMITATION` / `PARTIAL` / `NOT_IMPLEMENTED` |
| Level verifikasi wajib | `E2E_REQUIRED` / `CODE_REVIEWED_OK` (butuh uji vs cukup review) |
| Hasil verifikasi | `NOT_TESTED` / `CODE_REVIEWED` / `UNIT_TESTED` / `INTEGRATION_TESTED` / `E2E_PASSED` / `FAILED` / `BLOCKED` |

Contoh: `FR-SYNC-02 → WITH_LIMITATION / E2E_REQUIRED / NOT_TESTED`.

---

## 2. Gambaran Produk

**IMFIT** adalah pencatat workout personal. Fokus MVP: pencatatan cepat untuk progressive overload, bukan social/gamification.

Platform: Android native, 100% Jetpack Compose, single-activity (`app/src/main/java/com/diajarkoding/imfit/MainActivity.kt:29`).

Flavor: `demo` (`app/build.gradle.kts:35-39`, fake in-memory, volatil) vs `production` (`app/build.gradle.kts:40-42`, Room + Supabase). Acuan utama = production bila tak disebut lain.

Kontrak desain: IMFIT UI/UX Design Guideline v1.0 sebagai acuan desain (semantic color, typography, spacing, komponen, loading/empty/error, feedback sync, aksesibilitas). Identitas rujukan: **eksternal, di luar repo ini** (terverifikasi: `docs/` hanya berisi `backend/`, `features/`, `prd/`, file `.md` arsitektur — tidak ada file guideline). Sebelum documentation freeze, tetapkan salah satu: (a) impor guideline sebagai dokumen versioned di repo, atau (b) rujuk lokasi + versi + revisi stabil. Tanpa itu design contract bisa berubah tanpa terdeteksi baseline commit.

## 3. Tujuan Produk

1. Catat sesi gym (template → eksekusi → ringkasan) tanpa kertas/spreadsheet.
2. Volume otomatis (`weight × reps`, total sesi; hanya set completed).
3. Tidak hilang saat offline/layar mati — **kecuali logout** (lihat §9.2).
4. Riwayat/progres mudah dilihat.

## 4. Persona

Pemula (butuh panduan 8 kategori otot), Intermediate/Advanced (prefill, edit template, riwayat volume), pengguna offline-first.

## 5. Scope PRD

### 5.1 In scope
Auth, dashboard, template, library, active workout, summary, progress/history, profile & preferensi (view), offline-first & sync, demo mode.

### 5.2 Out of scope
AI coach, social, Wear OS, web dashboard, export, manual backup, nutrisi, marketplace, reset-password, OAuth.

---

## 6. Fitur Utama

### 6.1 Authentication — `IMPLEMENTED` / `E2E_REQUIRED` / `NOT_TESTED`

| Subfitur | Impl / Hasil |
|---|---|
| Login email/password | IMPLEMENTED / NOT_TESTED |
| Register + validasi | IMPLEMENTED / CODE_REVIEWED |
| PKCE deep-link `imfit://auth` | IMPLEMENTED / NOT_TESTED |
| Session restore | IMPLEMENTED / NOT_TESTED |
| Logout cleanup | IMPLEMENTED / NOT_TESTED |

`CURRENT_BEHAVIOR` (terverifikasi):
- Register: nama/email/password/birthDate opsional/foto opsional (`domain/repository/AuthRepository.kt:7-13`); validasi email (`core/utils/Validator.kt:14-16`), password 8+upper+lower+digit+simbol (`Validator.kt:18-22`, ditegakkan ulang `AuthRepositoryImpl.kt:56-64`).
- Bercabang `Main` vs `RegisterConfirmation` (`NavGraph.kt:99-105`); login → `Main` (`NavGraph.kt:88-97`); deep-link → `MainActivity.kt:105-107` → `NavGraph.kt:62-67`; splash → `Main`/`Login` (`NavGraph.kt:81-86`); logout → `Login` (`NavGraph.kt:214-223`).
- Logout: `timerCleanup.stop("LOGOUT")` → cancel WM → block/drain → `deleteUserData` 1 transaksi → clear DataStore → `signOut` (`AuthRepositoryImpl.kt:269-278`). **Koreksi v1.2:** `TimerSessionCleanup.stop` **memang** menghentikan timer + `stopService(WorkoutService)` (`core/timer/TimerSessionCleanup.kt:14-18`) — klaim v1.1 "tidak stop eksplisit" salah untuk service. Yang belum terverifikasi: dismissal notifikasi + stale broadcast action (STOP/PAUSE/RESUME/SKIP_REST) pasca-logout.

`VERIFICATION_CASE`:
- [ ] AC-AUTH-01 `[CURRENT]`: email invalid ditolak pre-network.
- [ ] AC-AUTH-02 `[CURRENT]`: `Abc123!x` diterima, `abc12345` ditolak.
- [ ] AC-AUTH-03 `[CURRENT]`: konfirmasi ON → `RegisterConfirmation`.
- [ ] AC-AUTH-04 `[CURRENT]`: login valid → `Main`.
- [ ] AC-AUTH-05 `[CURRENT]`: logout hapus data lokal 1 transaksi + ke `Login`.
- [ ] AC-AUTH-06 `[CURRENT]`: deep-link valid cold start → session valid + ke `Main`. Hasil: `NOT_TESTED`.
- [ ] AC-AUTH-07 `[CURRENT]`: callback invalid/expired → tidak membuka authenticated. Hasil: `NOT_TESTED`.
- [ ] AC-SEC-01A `[CURRENT]` (dipetakan ke FR-AUTH-03 dan FR-SEC-01 — menguji logout sekaligus isolasi lokal): logout A → login B di perangkat sama: data Room + active session A tidak terekspos. Hasil: `NOT_TESTED`.
- [ ] AC-SEC-01B `[CURRENT]` (hanya FR-SEC-02; RLS/RPC sudah bagian backend existing, `20260828130154_imfit_backend_hardening.sql`): B tidak bisa baca/ubah data Supabase A via API tanpa otorisasi. Hasil: `NOT_TESTED`.
- [ ] AC-SEC-01C `[CURRENT]` (hanya FR-SEC-03; storage policy avatars sudah existing): B tidak bisa membaca objek privat atau membuat signed URL atas avatar A via API tak berwenang. Catatan batas: akses memakai signed URL valid yang sudah dibagikan adalah skenario berbeda. Hasil: `NOT_TESTED`.

### 6.2 Dashboard — `IMPLEMENTED` / `E2E_REQUIRED` / `NOT_TESTED`
`CURRENT_BEHAVIOR`: tab Home/Exercise/Progress (`MainScreen.kt:71-75`); `syncState` + overlay `SyncProgressDialog` saat initial sync (`MainScreen.kt:79-80,167-168`). Dialog **hanya** tampil bila `isInitialSync` dan **tanpa** tombol retry/dismiss/failure UI (`SyncProgressDialog.kt:44`, seluruh file tanpa error branch) — recovery UI belum ada (gap, bukan asumsi).
- [ ] AC-DASH-01 `[CURRENT]`: tanpa login tak bisa buka Home.
- [ ] AC-DASH-02 `[CURRENT]`: initial sync → overlay + blokir nav bawah.
- [ ] AC-DASH-03 `[TARGET_ONLY]`: initial sync gagal/offline: ada retry/jalur keluar aman, UI tak terjebak.
- [ ] AC-DASH-04 `[CURRENT]`: tap template → detail; tap start → active.

### 6.3 Workout Template — `IMPLEMENTED` / `E2E_REQUIRED` / `NOT_TESTED`
Operasi: 7 fungsi CRUD (`WorkoutRepository.kt:11-17`). Alur `Main → WorkoutDetail → ExerciseSelection/EditWorkout` (`NavGraph.kt:136-177`).

`CURRENT_BEHAVIOR` — dua entry point, aturan berbeda (temuan audit P1 terkonfirmasi):
- Jalur A (Home "Add day" dialog, nama saja): memanggil `HomeViewModel.createWorkout(name)` → `createTemplate(userId, name, emptyList())` (`HomeViewModel.kt:111-132`, dialog `HomeScreen.kt:120-158`, navigasi ke detail `HomeScreen.kt:113-118`). **Template kosong DIIZINKAN di jalur ini.**
- Jalur B (`CreateTemplateScreen`): tombol disabled bila nama kosong atau exercise kosong (`CreateTemplateScreen.kt:184`); VM early-return (`CreateTemplateViewModel.kt:78-85`). **Template kosong DIBLOKIR di jalur ini.**
- Repository mengizinkan kosong: hanya `requireDistinctExercises`, insert template tanpa syarat jumlah (`WorkoutRepositoryImpl.kt:134-170`, guard `isNotEmpty` hanya untuk child `170`).
- Soft-delete via flag lokal.

`TARGET_REQUIREMENT`: **OPEN PRODUCT DECISION** (belum diputuskan — QA menguji per-jalur sampai keputusan dicatat di sini):
- Opsi 1: izinkan template kosong sebagai draft di semua jalur.
- Opsi 2: blokir di semua jalur + validasi domain di repository.
Keputusan, tanggal, dan pemilik keputusan wajib dicatat di baris ini saat ditetapkan.

`VERIFICATION_CASE`:
- [ ] AC-TMPL-01 `[CURRENT]`: Jalur A dengan nama valid → template kosong tersimpan + terbuka di detail.
- [ ] AC-TMPL-02 `[CURRENT]`: Jalur B tanpa nama/tanpa exercise → tidak tersimpan, tanpa crash.
- [ ] AC-TMPL-03 `[CURRENT]`: edit nama/sets/reps/rest tersimpan.
- [ ] AC-TMPL-04 `[CURRENT]`: hapus template tak hapus histori.
- [ ] AC-TMPL-05 `[CURRENT]`: multi-select masuk detail tanpa duplikasi; batal tak mengubah detail.

### 6.4 Exercise Library — `IMPLEMENTED` / `CODE_REVIEWED_OK` / `CODE_REVIEWED`
8 kategori (`MuscleCategory.kt:6-15`); operasi get/search (`ExerciseRepository.kt:7-10`); 3 layar (`NavGraph.kt:162-177`).
Katalog seed: **tepat 81 ID exercise** (`'ex_*'` di `20260828130132_imfit_catalog_seed.sql`; 2 statement INSERT: categories + exercises). Jumlah runtime dapat berbeda — seed acuan, runtime diverifikasi terpisah.
- [ ] AC-LIB-01 `[CURRENT]`: 8 kategori tampil.
- [ ] AC-LIB-02 `[CURRENT]`: pilih kategori → daftarnya.
- [ ] AC-LIB-03 `[CURRENT]`: search mengandung query.
- [ ] AC-LIB-04 `[TARGET_ONLY]` (FR-LIB-03): query tanpa hasil → empty state (bukan blank/error).

### 6.5 Active Workout — `WITH_LIMITATION` / `E2E_REQUIRED` / `NOT_TESTED`

`CURRENT_BEHAVIOR`:
- Operasi sesi (`WorkoutRepository.kt:20-26`); persist `active_sessions` + kolom pause/rest (`IMFITDatabase.kt:62-76`).
- Service/alarm/receiver (`AndroidManifest.xml:67-94`); tap notifikasi kembali (`MainActivity.kt:110-122`, `NavGraph.kt:47-60`); prefill (`WorkoutRepository.kt:35`); finish → `trimToMain` + Summary (`NavGraph.kt:180-189`).
- Cancel: dialog konfirmasi DESTRUCTIVE **ada** (`ActiveWorkoutScreen.kt:323-338`, trigger `365`); confirm → `cancelWorkout()` + back (`332-335`); repo hapus sesi + reset (`WorkoutRepositoryImpl.kt:484-490`) + `timerCleanup.stop("WORKOUT_CANCELLED")` (`485`); service stop action juga (`WorkoutService.kt:79`). **Tidak** menghasilkan log.
- Durasi (KNOWN_GAP): timer pakai `actualElapsedMs` tanpa pause (`WorkoutSession.kt:32-41` → `ActiveWorkoutViewModel.kt:180-181`, `WorkoutService.kt:110`); log tersimpan pakai `startTime/endTime` mentah (`WorkoutRepositoryImpl.kt:385-415`) dengan `durationMinutes=(end-start)/60000` (`WorkoutLog.kt:13-14`). Jadi pause dikecualikan di timer tapi termasuk di log/summary/history.

`TARGET_REQUIREMENT` (kontrak durasi tunggal):
- `active_duration = end − start − total_paused` konsisten di timer, persistence, summary, history, progress.
- Contoh: elapsed 60 mnt, pause 15 mnt → semua tampil 45 mnt.

`VERIFICATION_CASE`:
- [ ] AC-WORK-01 `[CURRENT]`: start → sesi sesuai template.
- [ ] AC-WORK-02 `[CURRENT]`: complete set simpan weight/reps + picu rest.
- [ ] AC-WORK-03 `[CURRENT]`: pause menahan `actualElapsedMs`; resume akumulasi (`ActiveWorkoutViewModel.kt:383-400`).
- [ ] AC-WORK-04C `[CURRENT]` `CHARACTERIZATION_TEST`: 60/15 → timer 45; summary/history = 60 (elapsed). Hasil lulus = `BASELINE_REPRODUCED` (gap terdokumentasi) — **bukan** release acceptance.
- [ ] AC-WORK-04T `[TARGET_ONLY]` `RELEASE_ACCEPTANCE`: 60/15 → timer = summary = history = 45. Gagal di baseline adalah ekspektasi (`KNOWN_GAP` sampai kode diperbaiki).
- [ ] AC-WORK-05A `[CURRENT]`: screen off/on — state set, durasi, rest sesuai kontrak. Jaminan service tetap berjalan **tidak** disamakan dengan pemulihan sesi dari DB.
- [ ] AC-WORK-05B `[CURRENT]`: background→foreground — sesi dapat dilanjutkan.
- [ ] AC-WORK-05C `[CURRENT]`: system process recreation — state tersimpan dipulihkan benar.
- [ ] AC-WORK-05D `[CURRENT]`: force-stop lalu buka ulang — recovery sesuai data persisten + batas lifecycle Android.
- [ ] AC-WORK-06 `[CURRENT]`: skip rest hentikan countdown.
- [ ] AC-WORK-07 `[CURRENT]`: cancel → konfirmasi → hapus sesi, tanpa log, timer/notifikasi berhenti.
- [ ] AC-WORK-08A `[CURRENT]`: aksi notifikasi PAUSE/RESUME/STOP/SKIP_REST hanya berlaku untuk sesi user aktif; aksi basi tidak memengaruhi sesi baru.
- [ ] AC-WORK-08B `[CURRENT]`: logout saat service aktif → timer + service berhenti via `TimerSessionCleanup`, tak ada stale session/aksi user lama (dismissal notifikasi + stale broadcast: `NOT_TESTED`).
- [ ] AC-WORK-09 `[CURRENT]` (FR-WORK-01): finish saat offline → aggregate lokal tersimpan (log + exercise + sets dalam 1 transaksi `WorkoutRepositoryImpl.kt:391-468`), active session ditutup, navigasi membuka Summary dengan `workoutLogId` yang benar. Navigasi saja **bukan** bukti penyimpanan — aggregate Room wajib diverifikasi. Cloud acknowledgement diuji terpisah via AC-SYNC-02/05/06.
- [ ] AC-WORK-10 `[CURRENT]` (FR-WORK-03; session-scoped terverifikasi di kode, hasil `NOT_TESTED`): ubah rest sesi → rest berikutnya memakai override (`ActiveWorkoutViewModel.kt:287-289`), tanpa mengubah template permanen (tidak ada tulis ke tabel template di jalur override). Bukti scope: kolom `session_rest_override` di baris `active_sessions` (`ActiveSessionEntity.kt:41-42`, migrasi `IMFITDatabase.kt:74`); tulis via `updateSessionRestOverride` (`WorkoutRepositoryImpl.kt:492-508`); sesi baru = UUID baru + hapus sesi lama + default null (`WorkoutRepositoryImpl.kt:295-321`), jadi override tidak terbawa; `updateActiveSession` mempertahankan kolom (`352-379`); dimuat ulang saat init (`ActiveWorkoutViewModel.kt:100-106`). Kontrak pemulihan + non-carryover memerlukan verifikasi runtime.

### 6.6 Workout Summary — `IMPLEMENTED` / `E2E_REQUIRED` / `NOT_TESTED`
`CURRENT_BEHAVIOR`: volume per set `weight×reps`, total Σ, hanya completed (`WorkoutRepositoryImpl.kt:396-410,424-426`); `formattedDuration` dari elapsed (`WorkoutLog.kt:13-21`); volume `%,.0f kg` (`WorkoutLog.kt:23-24`); dibuka via `workoutLogId` (`Routes.kt:37`, `NavGraph.kt:191-196`).
`TARGET_REQUIREMENT`: durasi mengikuti kontrak §6.5.
- [ ] AC-SUM-01 `[CURRENT]`: angka = agregat tersimpan (completed-only).
- [ ] AC-SUM-02 `[CURRENT]`: satuan konsisten; selesai → `Main` tanpa stack ganda.
- [ ] AC-SUM-03T `[TARGET_ONLY]`: durasi = active_duration. Gagal di baseline adalah ekspektasi.

### 6.7 Progress & History — `IMPLEMENTED` / `CODE_REVIEWED_OK` / `CODE_REVIEWED`
Operasi log (`WorkoutRepository.kt:29-32`); alur kalender/histori (`NavGraph.kt:198-212`).
- [ ] AC-PROG-01 `[CURRENT]`: detail per tanggal.
- [ ] AC-PROG-02 `[CURRENT]`: kalender → tanggal → detail.
- [ ] AC-PROG-03 `[CURRENT]`: prefill berat terakhir.
- [ ] AC-PROG-04 `[TARGET_ONLY]`: volume/durasi mingguan memakai definisi §6.5/§6.6.

### 6.8 Profile & Preferensi — `PARTIAL` (view-only) / `CODE_REVIEWED_OK` / `CODE_REVIEWED`
`CURRENT_BEHAVIOR`: UI view-only (nama/email/tanggal/foto + logout, via `ProgressViewModel`, tanpa dialog edit/panggilan `updateProfile`: `ProfileScreen.kt:81-83,107-138,194-226`). Repository mampu `updateProfile/getSignedAvatarUrl` (`AuthRepository.kt:20-27`). Tema/bahasa persisten (`MainActivity.kt:39-59`, `NavGraph.kt:214-223`); izin kamera/galeri + FileProvider (`AndroidManifest.xml:15-54`).
- [ ] AC-PROF-01 `[CURRENT]`: tema langsung + persisten.
- [ ] AC-PROF-02 `[CURRENT]`: bahasa langsung + persisten.
- [ ] AC-PROF-03 `[CURRENT]`: tampil data + logout bekerja. Edit profil: belum AC aktif.

### 6.9 Offline-first & Sync — `WITH_LIMITATION` / `E2E_REQUIRED` / `NOT_TESTED`
`CURRENT_BEHAVIOR`:
- State `IDLE/SYNCING/SYNCED/FAILED/OFFLINE` + pending/progress/initial (`SyncState.kt:7-24`).
- Workout push = 1 RPC `upsert_workout_aggregate` (`ImfitAggregateRemoteDataSource.kt:56-67` ← `SyncManager.kt:440`), ack `markAsSyncedIfUnchanged` (`SyncManager.kt:446-456`).
- Template push = CRUD baris (`SyncManager.kt:363-404`); wrapper `upsertTemplate` (`ImfitAggregateRemoteDataSource.kt:69-79`) nol call-site — belum jalur aktif.
- Pull exercises per template SYNCED (`SyncManager.kt:303-358`). Trigger online/login/mutasi/foreground/manual. UI indikator (`MainScreen.kt:79-80,167-168`).
- Batasan: full pull (bukan delta), child edit-remote belum rekonsiliasi penuh, konflik partial LWW tanpa prompt.
- [ ] AC-SYNC-01 `[CURRENT]`: offline write → lokal + pending>0.
- [ ] AC-SYNC-02 `[CURRENT]`: online → pending 0, `SYNCED`, tanpa duplikat.
- [ ] AC-SYNC-03 `[TARGET_ONLY]`: sync gagal/offline → retry/jalur keluar (dialog saat ini tanpa itu).
- [ ] AC-SYNC-04 `[CURRENT]`: dibedakan per kondisi — (a) instalasi baru + belum login: katalog kosong offline; (b) login tapi initial sync belum selesai: katalog kosong/parsial + overlay; (c) pernah initial sync: baca cache offline. Syarat auth + initial sync dicatat per kondisi.
- [ ] AC-SYNC-05 `[CURRENT]`: RPC sukses + ack lokal gagal → retry aman tanpa duplikat. Hasil: `NOT_TESTED`.
- [ ] AC-SYNC-06 `[CURRENT]`: RPC gagal tengah transaksi → lokal tetap pending, bisa retry; tidak ada aggregate parsial ter-commit di remote. Hasil: `NOT_TESTED`.
- [ ] AC-SYNC-07 `[CURRENT]` `CHARACTERIZATION_TEST`: offline workout lalu logout → data hilang (perilaku saat ini, §9.2). Hasil lulus = `BASELINE_REPRODUCED` — **bukan** release acceptance; kebijakan warning/guard = target terpisah.
- [ ] AC-SYNC-08 `[CURRENT]` (koreksi v1.2.1 — guard `markAsSyncedIfUnchanged` aktif di `SyncManager.kt:446-456`): edit lokal saat RPC in-flight → respons payload lama tidak meng-ack perubahan baru. Hasil: `NOT_TESTED`.
- [ ] AC-SYNC-09 `[CURRENT]` (FR-SYNC-03): push template pending (CREATE/UPDATE/DELETE) → baris remote + exercises tersinkron per `SyncManager.kt:363-404`, guard `markAsSyncedIfUnchanged`. Hasil: `NOT_TESTED`.
- [ ] AC-SYNC-10 `[CURRENT]` (FR-SYNC-03): pull exercises untuk template SYNCED → ganti isi lokal per template (`SyncManager.kt:303-358`). Hasil: `NOT_TESTED`.

### 6.10 Demo Mode — `IMPLEMENTED` / `CODE_REVIEWED_OK` / `CODE_REVIEWED`
Suffix `.demo` (`app/build.gradle.kts:35-39`), fake in-memory, volatil by design.
- [ ] AC-DEMO-01 `[CURRENT]`: tanpa Supabase bisa masuk; restart reset.

---

## 7. Alur Pengguna

Auth: `Splash → Login/Main`; `Register → Main/RegisterConfirmation → Login`; deep-link → `Main`; `Profile → Logout → Login` (`NavGraph.kt:81-111,214-223`). Template: `Home → WorkoutDetail → ExerciseSelection → detail → EditWorkout` + jalur cepat Home-dialog (kosong) (`NavGraph.kt:136-177`, `HomeScreen.kt:113-158`). Workout: `→ ActiveWorkout → [Finish → Summary → Main | Cancel → kembali]` (`NavGraph.kt:180-189`). History: `Progress → History/YearlyCalendar → History` (`NavGraph.kt:198-212`).

## 8. Functional Requirements (satu sumber ID)

| ID | Kebutuhan | Impl / Level / Hasil |
|---|---|---|
| FR-AUTH-01 | Register + validasi | IMPLEMENTED / CODE_REVIEWED_OK / CODE_REVIEWED |
| FR-AUTH-02 | Login + restore + deep-link | IMPLEMENTED / E2E_REQUIRED / NOT_TESTED |
| FR-AUTH-03 | Logout hapus lokal 1 transaksi | IMPLEMENTED / E2E_REQUIRED / NOT_TESTED |
| FR-DASH-01 | Home + sync state | IMPLEMENTED / E2E_REQUIRED / NOT_TESTED |
| FR-TMPL-01 | CRUD template | IMPLEMENTED / E2E_REQUIRED / NOT_TESTED |
| FR-TMPL-02 | Tambah/hapus exercise; aturan kosong per-jalur | IMPLEMENTED / E2E_REQUIRED / NOT_TESTED |
| FR-LIB-01 | Browse 8 kategori + detail | IMPLEMENTED / CODE_REVIEWED_OK / CODE_REVIEWED |
| FR-LIB-02 | Search exercise | IMPLEMENTED / E2E_REQUIRED / NOT_TESTED |
| FR-LIB-03 | Empty state hasil kosong | NOT_IMPLEMENTED / E2E_REQUIRED / NOT_TESTED |
| FR-WORK-01 | Start/cancel/finish | IMPLEMENTED / E2E_REQUIRED / NOT_TESTED |
| FR-WORK-02 | Catat set (prefill di FR-PROG-01 via AC-PROG-03) | IMPLEMENTED / E2E_REQUIRED / NOT_TESTED |
| FR-WORK-03 | Rest + override + skip | WITH_LIMITATION / E2E_REQUIRED / NOT_TESTED |
| FR-WORK-04 | Pause/resume durasi bersih | WITH_LIMITATION / E2E_REQUIRED / NOT_TESTED |
| FR-WORK-05 | Pulih per skenario lifecycle (05A–05D) | WITH_LIMITATION / E2E_REQUIRED / NOT_TESTED |
| FR-WORK-06 | Aksi notifikasi + logout saat service aktif (08A/08B) | IMPLEMENTED / E2E_REQUIRED / NOT_TESTED |
| FR-SUM-01 | Ringkasan akurat (volume IMPLEMENTED; durasi WITH_LIMITATION, kontrak §6.5) | IMPLEMENTED / E2E_REQUIRED / NOT_TESTED |
| FR-PROG-01 | Histori + kalender + prefill berat terakhir | IMPLEMENTED / CODE_REVIEWED_OK / CODE_REVIEWED |
| FR-PROF-01 | Lihat profil + avatar | PARTIAL / CODE_REVIEWED_OK / CODE_REVIEWED |
| FR-PREF-01 | Tema persisten | IMPLEMENTED / CODE_REVIEWED_OK / CODE_REVIEWED |
| FR-PREF-02 | Bahasa persisten | IMPLEMENTED / CODE_REVIEWED_OK / CODE_REVIEWED |
| FR-SYNC-01 | Tulis lokal + pending | IMPLEMENTED / E2E_REQUIRED / NOT_TESTED |
| FR-SYNC-02 | Push workout RPC idempoten + retry | WITH_LIMITATION / E2E_REQUIRED / NOT_TESTED |
| FR-SYNC-03 | Push template baris + pull + UI | WITH_LIMITATION / E2E_REQUIRED / NOT_TESTED |
| FR-SEC-01 | Isolasi data lokal antar-user | IMPLEMENTED / E2E_REQUIRED / NOT_TESTED |
| FR-SEC-02 | Ownership enforcement via RLS/RPC | IMPLEMENTED / E2E_REQUIRED / NOT_TESTED |
| FR-SEC-03 | Akses avatar private via Storage policy | IMPLEMENTED / E2E_REQUIRED / NOT_TESTED |
| FR-DEMO-01 | Demo volatil | IMPLEMENTED / CODE_REVIEWED_OK / CODE_REVIEWED |

## 9. Non-Functional + Batasan + Known Limitations

### 9.1 NFR
Offline tulis-lokal sukses; baca cache bila pernah sync. Finish atomik via transaksi Room `database.withTransaction` (`WorkoutRepositoryImpl.kt:391-468`: insert log + exercise + sets + hapus sesi dalam satu transaksi; regression test: kill saat finish → tanpa setengah state). Retry WM; kill saat sync → tetap pending + retry. Anon key via BuildConfig (`app/build.gradle.kts:45-68`), tanpa service-role; RLS per-user; avatar private. Compose M3, dark/light, ID/EN, aksi notifikasi; rotasi/font besar/talkback dasar. `minSdk 24`, `target/compile 37`. Splash/deep-link cold-warm/foreground-background timer.

### 9.2 Known limitations & risiko
1. Template RPC tersedia tapi belum jalur aktif; aktif = CRUD baris.
2. Durasi: timer bersih-pause vs log elapsed — target kontrak tunggal; AC-WORK-04C = characterization (`BASELINE_REPRODUCED`), AC-WORK-04T = release acceptance (`KNOWN_GAP` di baseline).
3. Logout menghapus unsynced by current behavior (`AuthRepositoryImpl.kt:269-278`); klaim offline tak berlaku untuk logout. Warning/guard = proposed.
4. Notifikasi: aksi PAUSE/RESUME/STOP/SKIP ada dan terdaftar (`AndroidManifest.xml:84-94`) — IMPLEMENTED. Jaminan isolasi aksi basi terhadap user/sesi lain belum diuji (AC-WORK-08A/08B, `NOT_TESTED`).
5. Auth E2E, sync E2E (idempotency/rollback/konkurensi), lifecycle E2E belum dijalankan.
6. Full pull; child edit-remote belum penuh; konflik partial LWW.
7. Sebagian production partial runtime-tested. Password UI > `config.toml`; SMTP custom belum ada.

## 10. Metrics Keberhasilan (usulan — belum instrumentasi)

| Metric | Formula (proposed) | Event source (proposed) | Status |
|---|---|---|---|
| Workout completion | finish/start per user-minggu | `finishWorkout` vs `startWorkout` repo call | PROPOSED |
| WAU + template aktif | user unik (definisi operasional saat instrumentasi: workout started/completed dalam minggu, bukan sekadar login — pengguna long-login tanpa aktivitas tidak dihitung) + template dipakai/minggu | workout start/complete event + kohor eksplisit | PROPOSED |
| Sync success + backlog | sukses/total run; median pending age | `SyncManager` result + DAO pending | PROPOSED |
| Crash-free sesi aktif | sesi tanpa crash / total sesi | crash reporter + session start | PROPOSED |
| Retensi 7/30 hari | kohor aktif (definisi sama dengan WAU: workout started/completed, bukan login event) | workout event + kohor eksplisit | PROPOSED |

---

## Appendix A — Baseline Teknis

Single-module `:app`, `com.diajarkoding.imfit` (`app/build.gradle.kts:20`). MVVM + Clean parsial tanpa `domain/usecase/`. Navigation3 (`NavGraph.kt:42,69-80`, helper `228-240`). Hilt; single-activity + nested 3 tab (`MainScreen.kt:61-170`). Coil3, DataStore, WM + service + alarm + receiver (`AndroidManifest.xml`). Room v6 8 entities/DAO + export (`IMFITDatabase.kt:26-39`), migrasi pause/rest (`62-76`). Supabase 3 migrasi; 9 tabel; RPC workout aktif + template tersedia; RLS; bucket avatars/exercises. Beda: tanpa tabel lokal kategori (enum), PK komposit vs UUID, epoch vs timestamptz, denormalisasi sets. 14 NavKey (`Routes.kt:6-46`) + tab internal (`MainScreen.kt:52-59`). Stack: AGP 9.1.1, Kotlin 2.2.10, Compose BOM 2026.08.00, Navigation3 1.1.6, Hilt 2.60.1, Room 2.8.4, DataStore 1.0.0, Coil 3.3.0, Supabase 3.0.3, Ktor 3.0.2, WM 2.11.2, Java 17.

## Appendix B — Traceability FR→AC→Kode→Bukti (per ID individual)

Kolom Jenis: `CHAR` = CHARACTERIZATION_TEST, `BASE` = BASELINE_VERIFICATION, `REL` = RELEASE_ACCEPTANCE, `TGT` = TARGET_ONLY.

| FR | AC | Jenis/Tag | Code path | Bukti / Hasil |
|---|---|---|---|---|
| FR-AUTH-01 | AC-AUTH-01 | BASE/CURRENT | `Validator.kt:14-16` | CODE_REVIEWED |
| FR-AUTH-01 | AC-AUTH-02 | BASE/CURRENT | `Validator.kt:18-22`, `AuthRepositoryImpl.kt:62-64` | CODE_REVIEWED |
| FR-AUTH-01 | AC-AUTH-03 | BASE/CURRENT | `NavGraph.kt:99-105` | CODE_REVIEWED |
| FR-AUTH-02 | AC-AUTH-04 | BASE/CURRENT | `NavGraph.kt:88-97` | NOT_TESTED |
| FR-AUTH-02 | AC-AUTH-06 | BASE/CURRENT | `MainActivity.kt:103-108`, `NavGraph.kt:62-67` | NOT_TESTED |
| FR-AUTH-02 | AC-AUTH-07 | BASE/CURRENT | `AuthDeepLinkHandler.kt` | NOT_TESTED |
| FR-AUTH-03 | AC-AUTH-05 | BASE/CURRENT | `AuthRepositoryImpl.kt:269-278`, `IMFITDatabase.kt:51-59` | NOT_TESTED |
| FR-AUTH-03 | AC-SEC-01A | BASE/CURRENT | sama + `TimerSessionCleanup.kt:14-18` | NOT_TESTED |
| FR-DASH-01 | AC-DASH-01 | BASE/CURRENT | `NavGraph.kt:81-86` | CODE_REVIEWED |
| FR-DASH-01 | AC-DASH-02 | BASE/CURRENT | `MainScreen.kt:167-168` | CODE_REVIEWED |
| FR-DASH-01 | AC-DASH-03 | TGT | `SyncProgressDialog.kt:44` (tanpa recovery UI) | TARGET_ONLY |
| FR-DASH-01 | AC-DASH-04 | BASE/CURRENT | `NavGraph.kt:113-134` | CODE_REVIEWED |
| FR-TMPL-01 | AC-TMPL-01 | BASE/CURRENT | `HomeScreen.kt:113-158`, `HomeViewModel.kt:111-132` | CODE_REVIEWED |
| FR-TMPL-01 | AC-TMPL-03 | BASE/CURRENT | `WorkoutRepository.kt:11-17` | CODE_REVIEWED |
| FR-TMPL-01 | AC-TMPL-04 | BASE/CURRENT | soft-delete flag | CODE_REVIEWED |
| FR-TMPL-02 | AC-TMPL-02 | BASE/CURRENT | `CreateTemplateScreen.kt:184`, `CreateTemplateViewModel.kt:78-85` | CODE_REVIEWED |
| FR-TMPL-02 | AC-TMPL-05 | BASE/CURRENT | `NavGraph.kt:140-152,169-177` | CODE_REVIEWED |
| FR-LIB-01 | AC-LIB-01 | BASE/CURRENT | `MuscleCategory.kt:6-15` | CODE_REVIEWED |
| FR-LIB-01 | AC-LIB-02 | BASE/CURRENT | `NavGraph.kt:162-177` | CODE_REVIEWED |
| FR-LIB-02 | AC-LIB-03 | BASE/CURRENT | `ExerciseRepository.kt:10` | CODE_REVIEWED |
| FR-LIB-03 | AC-LIB-04 | TGT | — (belum ada UI empty state) | TARGET_ONLY |
| FR-WORK-01 | AC-WORK-01 | BASE/CURRENT | `WorkoutRepository.kt:20-26` | CODE_REVIEWED |
| FR-WORK-01 | AC-WORK-07 | BASE/CURRENT | `ActiveWorkoutScreen.kt:323-338`, `WorkoutRepositoryImpl.kt:484-490` | CODE_REVIEWED |
| FR-WORK-01 | AC-WORK-09 | BASE/CURRENT | `WorkoutRepositoryImpl.kt:391-468`, `NavGraph.kt:180-189` | NOT_TESTED |
| FR-WORK-02 | AC-WORK-02 | BASE/CURRENT | completeSet `ActiveWorkoutViewModel.kt:264-302` | CODE_REVIEWED |
| FR-WORK-03 | AC-WORK-06 | BASE/CURRENT | skip rest path | CODE_REVIEWED |
| FR-WORK-03 | AC-WORK-10 | BASE/CURRENT | `ActiveWorkoutViewModel.kt:210-215,287-289`, `WorkoutRepositoryImpl.kt:492-520`, `ActiveSessionEntity.kt:41-42` | NOT_TESTED |
| FR-WORK-04 | AC-WORK-03 | BASE/CURRENT | `ActiveWorkoutViewModel.kt:180-181,383-400` | CODE_REVIEWED |
| FR-WORK-04 | AC-WORK-04C | CHAR/CURRENT | `WorkoutSession.kt:32-41` vs `WorkoutLog.kt:13-14` | BASELINE_REPRODUCED (target) |
| FR-WORK-04 | AC-WORK-04T | REL/TARGET_ONLY | kontrak §6.5 | KNOWN_GAP |
| FR-WORK-05 | AC-WORK-05A | BASE/CURRENT | screen off/on | NOT_TESTED |
| FR-WORK-05 | AC-WORK-05B | BASE/CURRENT | background/foreground | NOT_TESTED |
| FR-WORK-05 | AC-WORK-05C | BASE/CURRENT | process recreation | NOT_TESTED |
| FR-WORK-05 | AC-WORK-05D | BASE/CURRENT | force-stop | NOT_TESTED |
| FR-WORK-06 | AC-WORK-08A | BASE/CURRENT | `AndroidManifest.xml:84-94` | NOT_TESTED |
| FR-WORK-06 | AC-WORK-08B | BASE/CURRENT | `TimerSessionCleanup.kt:14-18` | NOT_TESTED |
| FR-SUM-01 | AC-SUM-01 | BASE/CURRENT | `WorkoutRepositoryImpl.kt:396-410,424-426` | CODE_REVIEWED |
| FR-SUM-01 | AC-SUM-02 | BASE/CURRENT | `NavGraph.kt:191-196` | CODE_REVIEWED |
| FR-SUM-01 | AC-SUM-03T | REL/TARGET_ONLY | kontrak durasi §6.5 | KNOWN_GAP |
| FR-PROG-01 | AC-PROG-01 | BASE/CURRENT | `NavGraph.kt:198-212` | CODE_REVIEWED |
| FR-PROG-01 | AC-PROG-02 | BASE/CURRENT | YearlyCalendar path | CODE_REVIEWED |
| FR-PROG-01 | AC-PROG-03 | BASE/CURRENT | `WorkoutRepository.kt:35` (dipakai FR-WORK-02) | CODE_REVIEWED |
| FR-PROG-01 | AC-PROG-04 | TGT | definisi §6.5/§6.6 | TARGET_ONLY |
| FR-PROF-01 | AC-PROF-03 | BASE/CURRENT | `ProfileScreen.kt:81-226` view-only | CODE_REVIEWED |
| FR-PREF-01 | AC-PROF-01 | BASE/CURRENT | `MainActivity.kt:39-59` tema | CODE_REVIEWED |
| FR-PREF-02 | AC-PROF-02 | BASE/CURRENT | `LocaleManager`, `NavGraph.kt:214-223` | CODE_REVIEWED |
| FR-SYNC-01 | AC-SYNC-01 | BASE/CURRENT | pending ops Room | CODE_REVIEWED |
| FR-SYNC-01 | AC-SYNC-02 | BASE/CURRENT | `SyncManager.kt` + RPC | NOT_TESTED |
| FR-SYNC-01 | AC-SYNC-04 | BASE/CURRENT | kondisi (a)/(b)/(c) §6.9 | CODE_REVIEWED |
| FR-SYNC-02 | AC-SYNC-05 | BASE/CURRENT | ack path `SyncManager.kt:446-456` | NOT_TESTED |
| FR-SYNC-02 | AC-SYNC-06 | BASE/CURRENT | rollback remote + pending lokal | NOT_TESTED |
| FR-SYNC-02 | AC-SYNC-08 | BASE/CURRENT | guard `markAsSyncedIfUnchanged` | NOT_TESTED |
| FR-SYNC-03 | AC-SYNC-03 | TGT | `SyncProgressDialog.kt:44` | TARGET_ONLY |
| FR-SYNC-03 | AC-SYNC-07 | CHAR/CURRENT | `AuthRepositoryImpl.kt:269-278` | BASELINE_REPRODUCED (target) |
| FR-SYNC-03 | AC-SYNC-09 | BASE/CURRENT | `SyncManager.kt:363-404` | NOT_TESTED |
| FR-SYNC-03 | AC-SYNC-10 | BASE/CURRENT | `SyncManager.kt:303-358` | NOT_TESTED |
| FR-SEC-01 | AC-SEC-01A | BASE/CURRENT | `IMFITDatabase.kt:51-59`, `TimerSessionCleanup.kt:14-18` | NOT_TESTED |
| FR-SEC-02 | AC-SEC-01B | BASE/CURRENT | hardening `...0154...sql` RLS/RPC | NOT_TESTED |
| FR-SEC-03 | AC-SEC-01C | BASE/CURRENT | storage policy avatars | NOT_TESTED |
| FR-DEMO-01 | AC-DEMO-01 | BASE/CURRENT | `app/build.gradle.kts:33-43` | CODE_REVIEWED |
