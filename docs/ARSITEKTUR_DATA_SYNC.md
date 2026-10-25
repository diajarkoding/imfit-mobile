# Arsitektur Data IMFIT — Pengelolaan Lokal dan Remote (Online-First)

> Dokumen arsitektur aktif. Istilah teknis universal ditulis dalam Bahasa Inggris.
> Status verifikasi: working tree 25 Oktober 2026 (sumber: source code `app/src/main`, `app/src/production`, `app/src/demo`, dan migration Supabase).
> Dokumen ini melengkapi dan memperbarui deskripsi *offline-first* lama pada `docs/features/16-local-storage-cloud-sync-personal-light.md` (versi 2026-08-27 sudah usang).

---

## Daftar Isi

1. [Definisi Istilah](#1-definisi-istilah)
2. [Ringkasan Arsitektur](#2-ringkasan-arsitektur)
3. [Perbandingan Flavor: Production vs Demo](#3-perbandingan-flavor)
4. [Komponen Data Layer](#4-komponen-data-layer)
5. [Basis Data Lokal (Room)](#5-basis-data-lokal-room)
6. [Metadata Sinkronisasi pada Setiap Entitas](#6-metadata-sinkronisasi)
7. [Arus Read: Pull Remote ke Lokal](#7-arus-read-pull)
8. [Arus Write Lokal](#8-arus-write-lokal)
9. [Arus Upload: Push Lokal ke Remote](#9-arus-upload-push)
10. [Template Write dengan Revision dan Write Intent](#10-template-write)
11. [Primitif Koordinasi (Mutex, Fence, Snapshot)](#11-primitif-koordinasi)
12. [Logout, Ganti Akun, dan Pemangkasan Cache](#12-logout-dan-pemangkasan-cache)
13. [Migrasi Database dan Pemulihan](#13-migrasi-database-dan-pemulihan)
14. [Sesi Aktif (Active Session)](#14-sesi-aktif)
15. [Jaringan dan State UI Sinkronisasi](#15-jaringan-dan-state-ui)
16. [Kontrak Backend (Supabase)](#16-kontrak-backend-supabase)
17. [Jaminan, Batasan, dan Gap](#17-jaminan-batasan-dan-gap)
18. [Peta Sumber (File Reference)](#18-peta-sumber)

---

## 1. Definisi Istilah

| Istilah | Definisi dalam konteks IMFIT |
| --- | --- |
| **Online-first** | Pola di mana lalu lintas data cenderung melewati jaringan: baca selalu dimulai dengan *refresh* remote, tulis menuntut jaringan aktif. Data lokal (Room) berperan sebagai *cache* reaktif, bukan satu-satunya *source of truth*. |
| **Offline-first** | Pola lama (sebelum arsitektur saat ini): semua perubahan lokal ditandai *pending* lalu diunggah otomatis oleh *background job* saat jaringan tersedia. Pada production, pola ini sudah ditinggalkan; jejaknya hanya tersisa sebagai kontrak antarmuka yang berperilaku *no-op*. |
| **Pending upload** | Baris lokal yang memiliki `pending_operation` (CREATE/UPDATE/DELETE) dan `sync_status` ≠ `SYNCED`. Baris ini **tidak pernah** diunggah secara otomatis; hanya melalui niat eksplisit pengguna (tombol Retry) atau tepat setelah workout selesai. |
| **Aggregate** | Satu unit data yang terdiri dari parent beserta seluruh anaknya yang utuh: `workout_logs` + `exercise_logs` + `workout_sets`, atau `workout_templates` + `template_exercises`. |
| **Snapshot** | Rekaman konten yang identik dan *immutable* dari sekumpulan data pada satu waktu. Dipakai untuk dua tujuan: memverifikasi konten belum berubah saat upload, dan menulis hasil *pull* remote secara atomik. |
| **Revision** | Bilangan bulat bertambah satu (`bigint`) milik server untuk setiap baris `workout_templates`. Menjadi acuan *optimistic concurrency*: tulis menuntut `expected_revision` yang cocok. |
| **Write intent** | Catatan tahan-lama (*durable*) pada tabel `template_write_intents` yang menyimpan identitas dan payload satu permintaan tulis template. Menjamin hasil permintaan yang tidak pasti (*ambiguous outcome*) tidak pernah diulang sebagai pembuatan data baru. |
| **Coalesced refresh** | Penyatuan banyak pemicu refresh menjadi maksimal satu operasi jaringan dalam jendela waktu tertentu (di IMFIT: 30 detik per alur). |
| **Fence (pagar logout)** | Mekanisme `blockAndAwaitIdle`/`unblock` yang menahan semua operasi jaringan milik satu akun selama proses logout berlangsung. |
| **Receipt** | Bukti konfirmasi dari server untuk satu permintaan tulis. Tanpa receipt yang cocok, status `pending` lokal **dipertahankan**. |
| **Input defaults (provenance)** | Metadata milik lokal **saja** (`exercise_logs.input_defaults`) yang merekam sumber prefill berat/reps. Tidak dikirim ke Supabase. |
| **Soft delete** | Penghapusan logis melalui kolom `deleted_at` (workout_logs) atau `is_deleted` (workout_templates); baris tetap ada untuk keperluan sinkronisasi. |
| **Owner scoping** | Seluruh kueri digembok dengan kunci `user_id`. Data akun lain tidak pernah keluar dari repository, meskipun pemanggil mengirim ID yang salah. |

---

## 2. Ringkasan Arsitektur

Production flavor IMFIT menggunakan **online-first dengan Room sebagai cache reaktif**, dengan kebijakan yang tegas per jenis data:

| Jenis data | Baca (remote → lokal) | Tulis (lokal → remote) |
| --- | --- | --- |
| Katalog exercise (`exercises`) | *Server-authoritative*: selalu menimpa cache lokal | Tidak pernah ditulis dari sisi aplikasi (server-only) |
| Profil (`users`/`profiles`) | Menimpa hanya baris yang sudah `SYNCED` | Tulis profil selalu menulis remote terlebih dahulu |
| Template rutinitas (`workout_templates`) | *Pull* snapshot atomik milik owner; baris `pending` lokal menang | **Menuntut jaringan** (network-required) dengan RPC revision |
| Riwayat workout (`workout_logs` dan anaknya) | *Pull* snapshot atomik; baris `pending` lokal menang | Tersimpan lokal lebih dahulu (`pending`), diunggah **satu kali secara eksplisit** |
| Sesi aktif (`active_sessions`) | Local-only, tidak pernah disinkronkan | Local-only (draft sesi) |

Perbedaan penting dari pola *offline-first* klasik:

1. **Tidak ada antrean upload otomatis.** Perubahan konektivitas, masuk foreground, dan `WorkManager` **tidak pernah** mengunggah baris pending. Antarmuka lama (`SyncScheduler.enqueue`, `SyncWorker`) tetap ada agar kode pemanggil tidak berubah, tetapi berperilaku *no-op*.
2. **Percobaan upload hanya melalui dua pintu:** (a) tepat setelah workout selesai — `attemptFinishedWorkout`, tepat satu kali; (b) tombol **Retry** manual di Home — `retryPendingUploads`. Ketika gagal, status `pending` tetap dijaga tahan-lama di Room.
3. **Setiap layar yang dibuka** mengalami refresh remote terlebih dahulu (bila online dan memiliki sesi), lalu data selalu dibaca dari Room. UI tidak pernah menunggu jaringan — refresh bersifat non-blocking, dan kegagalannya ditelan sambil memunculkan state.

Diagram alur tingkat tinggi:

```
                        ┌──────────────────────────────────────────────┐
                        │                  UI (Compose)                │
                        │  ViewModel mengamati Flow dari Room saja     │
                        └───────────────┬──────────────────────────────┘
                                        │ peristiwa pengguna
                                        ▼
                        ┌──────────────────────────────────────────────┐
                        │    WorkoutRepositoryImpl (facade domain)     │
                        │    + AccountDataAccess (pagar owner)         │
                        └───┬──────────────┬──────────────┬────────────┘
                            │              │              │
            ┌───────────────▼──┐  ┌────────▼─────────┐  ┌▼─────────────────────┐
            │ WorkoutTemplate  │  │ WorkoutLog       │  │ WorkoutSessionManager│
            │ Repository       │  │ Repository       │  │ (local-only draft)   │
            │ network-required │  │ refresh + cache  │  └─────────┬────────────┘
            └───────┬──────────┘  └────────┬─────────┘            │
                    │                      ▼                      ▼
                    │      ┌─────────────────────────┐   ┌────────────────┐
                    │      │ WorkoutLocalStore       │   │ active_sessions│
                    │      │ (penulis lokal bersama) │   │ (Room)         │
                    │      └───────────┬─────────────┘   └────────────────┘
                    │                  │
                    ▼                  ▼
        ┌────────────────────────────────────────┐      ┌─────────────────────────┐
        │            Room (imfit_database)       │◄─────│ RemoteWorkoutCoordinator│
        │  cache reaktif + pending tahan-lama    │      │ pull/push + state UI    │
        └────────────────────┬───────────────────┘      └───────────┬─────────────┘
                             │ invalidationTracker                  │ RPC / PostgREST
                             ▼                                      ▼
                    UI memuat ulang otomatis            ┌──────────────────────────┐
                                                        │  Supabase (PostgREST,    │
                                                        │  Storage, Auth PKCE)     │
                                                        └──────────────────────────┘
```

---

## 3. Perbandingan Flavor

Kedua flavor berbagi `presentation` dan `domain` yang sama; yang berbeda hanya binding Hilt di lapisan `data`.

| Komponen | `production` | `demo` |
| --- | --- | --- |
| `AuthRepository` | `AuthRepositoryImpl` (Supabase Auth PKCE) | `DemoAuthRepository` (akun demo otomatis) |
| `WorkoutRepository` | `WorkoutRepositoryImpl` (facade lengkap) | `DemoWorkoutRepository` (in-memory `Fake*DataSource`) |
| `ExerciseRepository` | `ExerciseRepositoryImpl` (cache Room + refresh) | `DemoExerciseRepository` (in-memory) |
| `SyncStateProvider` | `RemoteWorkoutCoordinator` | `DemoSyncStateProvider` (state statis `IDLE`) |
| `SyncScheduler` | `WorkManagerSyncScheduler` (`enqueue` = no-op) | `DemoSyncStateProvider` (no-op) |
| `SyncRunner` | `RemoteWorkoutCoordinator` | `DemoSyncStateProvider` (`SUCCESS` selalu) |
| `SyncLifecycleController` | `RemoteWorkoutCoordinator` (`blockAndAwaitIdle` nyata) | `DemoSyncStateProvider` (no-op) |
| `AuthDeepLinkHandler` | `SupabaseAuthDeepLinkHandler` (scheme `imfit://auth`) | `DemoAuthDeepLinkHandler` |
| Database Room | `DatabaseModule` — dibangun dengan daftar migrasi penuh | Tidak dipakai untuk data workout |

Flavor `demo` meniru kontrak *offline-first* sepenuhnya dalam memori: alur aplikasi dapat dicoba tanpa backend, dan tidak ada data demo yang bocor ke cache production (komentar proteksi pada `ExerciseRepositoryImpl`: *"Demo records never enter production"*).

---

## 4. Komponen Data Layer

Struktur paket dan perannya:

```
app/src/main/java/com/diajarkoding/imfit/
├── core/
│   ├── data/WorkoutPreferences.kt      DataStore preferensi pengguna (default rest timer)
│   └── network/
│       ├── NetworkMonitor.kt           Observasi konektivitas (Flow + callback)
│       ├── NetworkException.kt         NoNetworkException, NetworkOperationException
│       └── RetryUtils.kt               Utilitas backoff (hanya dipakai unit test saat ini)
├── data/
│   ├── auth/
│   │   ├── AuthDeepLinkHandler.kt      Kontrak deep-link auth (binding per flavor)
│   │   └── exception/AuthExceptions.kt Pemetaan error Supabase → AuthException
│   ├── repository/
│   │   ├── AuthRepositoryImpl.kt       Login/register/logout/profil/avatar (Supabase)
│   │   ├── AccountDataAccess.kt        Pagar kepemilikan di batas repository
│   │   ├── AuthMetadata.kt             Pemetaan metadata auth → User
│   │   ├── UserLocalMapping.kt         Entity ↔ domain User
│   │   ├── WorkoutRepositoryImpl.kt    Facade publik WorkoutRepository
│   │   ├── WorkoutTemplateRepository.kt Tulis template network-required + revision
│   │   ├── WorkoutLogRepository.kt     Read riwayat + refresh remote
│   │   ├── WorkoutSessionManager.kt    Kontrak sesi aktif dan timer
│   │   └── WorkoutLocalStore.kt        Penulis lokal bersama (Room): sesi, prefill, dan log workout
│   ├── local/
│   │   ├── dao/                        9 DAO Room
│   │   ├── database/                   IMFITDatabase, Converters, LogoutRecoveryStore,
│   │   │                               OnlineRecoveryMigration
│   │   ├── entity/                     9 entitas Room
│   │   └── sync/SyncStatus.kt          Enum SyncStatus + PendingOperation
│   ├── remote/
│   │   ├── ImfitAggregateRemoteDataSource.kt  Klien RPC aggregate
│   │   ├── TemplateWriteRequest.kt     DTO permintaan/receipt tulis template
│   │   └── dto/                        DTO REST (profil, katalog, dll.)
│   └── sync/                           Mesin koordinasi (lihat §11)
│       ├── RemoteWorkoutCoordinator.kt Inti: pull, push, state UI, fence
│       ├── RemoteSnapshotImporter.kt   Penulis snapshot transaksional
│       ├── DTO pull/push (Remote*Dto, Upsert*Dto) + parser timestamp ISO8601
│       ├── WorkoutUploadSnapshot.kt    Konten immutable satu percobaan upload
│       ├── TemplateSnapshotOrder.kt    Aturan prioritas baris lokal vs remote
│       ├── SyncPreferences.kt          DataStore per-user (stempel waktu sync)
│       └── SyncWorker, SyncScheduler, SyncRunner, SyncState*, SyncLifecycleController
├── domain/                             Model + antarmuka repository (murni Kotlin)
└── presentation/                       Screen/ViewModel (sumber kebenaran: Flow Room)
```

Poin arsitektural yang mudah terlewat:

- **`WorkoutRepositoryImpl` adalah binding tunggal `WorkoutRepository` di production.** Komentar source: *"Local storage alone is never bound as the app repository"* — `WorkoutLocalStore` (penulis pending) bukan repository mandiri; ia merupakan *collaborator* tulis lokal di dalam tiga komponen facade.
- **Kepemilikan tidak boleh bertumpu pada parameter.** `AccountDataAccess` memaksa setiap read/observe membaca `auth.getLocalUserId()` sendiri; parameter `userId` dari pemanggil hanya menjadi pemeriksaan silang (`expectedOwner`). Setiap hasil diperiksa ulang: bila identitas tidak lagi cocok, observer mengeluarkan nilai kosong.
- **`domain` tidak tahu tentang jaringan.** Antarmuka repository di `domain/repository` tidak memiliki kata "sync" atau "offline"; semua kebijakan ada di `data`.

---

## 5. Basis Data Lokal (Room)

**Database:** `imfit_database` — `@Database(version = 9, exportSchema = true)`; skema diekspor untuk pengujian migrasi.

| Tabel | Peran | Kunci utama | Keterangan sync |
| --- | --- | --- | --- |
| `users` | Cache profil pengguna aktif | `id` | `sync_status` default `SYNCED` |
| `exercises` | **Katalog** server-authoritative | `id` | Tanpa kolom pending (murni cache server) |
| `workout_templates` | Template rutinitas | `id` | + `is_deleted`, `remote_revision` |
| `template_exercises` | Junction template ↔ exercise | `(template_id, exercise_id)` | + status pending per baris |
| `workout_logs` | Log workout | `id` | + `deleted_at` (soft delete) |
| `exercise_logs` | Baris gerakan pada log | `id` | + `input_defaults` (provenance lokal) |
| `workout_sets` | Baris set | `id` | + status pending per baris |
| `active_sessions` | Draft sesi aktif (maksimal satu per user) | `id` | Tanpa kolom sync — local-only |
| `template_write_intents` | Jurnal permintaan tulis template | `key` | Payload JSON tahan-lama |

Indeks tambahan: `exercise_logs(workout_log_id)` dan `exercise_logs(exercise_id)` (dibuat oleh `OnlineRecoveryMigration`).

**Migrasi terdaftar pada `DatabaseModule` (production):**

| Migrasi | Perubahan yang diperkenalkan |
| --- | --- |
| `MIGRATION_4_5` | `active_sessions`: kolom pause (`is_paused`, `total_paused_time_ms`, `last_pause_time`) |
| `MIGRATION_5_6` | `active_sessions`: kolom rest timer (`rest_timer_end_time`, `rest_timer_exercise_name`, `session_rest_override`) |
| `MIGRATION_6_7` | `exercise_logs.input_defaults` (prefill provenance) |
| `OnlineRecoveryMigration` | 7 → 9 (lihat §13) |

**Observasi reaktif:** alih-alih Flow `@Query` biasa, seluruh Flow live pada `WorkoutLocalStore` digerakkan oleh `database.invalidationTracker.createFlow(...)` lalu membaca ulang **di dalam transaksi Room** setiap kali tabel terkait berubah. Keuntungannya: pembacaan selalu *snapshot-consistent* — parent dan anak tidak pernah terbaca secara parsial.

---

## 6. Metadata Sinkronisasi

Berkas `data/local/sync/SyncStatus.kt` mendeklarasikan dua enum:

```kotlin
enum class SyncStatus { SYNCED, PENDING_SYNC, SYNC_FAILED }
enum class PendingOperation { CREATE, UPDATE, DELETE }
```

Kolom pada baris yang dapat disinkronkan (semua tabel kecuali `exercises` dan `active_sessions`):

| Kolom | Arti |
| --- | --- |
| `sync_status` | Status per baris terhadap server (nama enum sebagai string; default `PENDING_SYNC` untuk baris baru). |
| `pending_operation` | Niat tulis yang belum dikonfirmasi server; `NULL` bila telah sinkron. |
| `updated_at` (lokal) | Stempel waktu edit terakhir; menjadi syarat pengakuan (`markAsSyncedIfUnchanged`). |
| `is_deleted` (template) / `deleted_at` (log) | Soft delete. |
| `remote_revision` (hanya template) | Salinan revision milik server saat terakhir sinkron. |

Aturan penting tentang metadata:

1. **Pending tidak pernah ditimpa oleh pull.** Baik `RemoteSnapshotImporter` maupun `pullProfile` hanya menulis baris dengan `syncStatus == SYNCED` dan `pendingOperation == null`. Data yang belum terkonfirmasi ke server tidak pernah hilang karena data remote.
2. **Pengakuan sync memiliki satu pintu yang terkunci.** Perubahan menjadi `SYNCED` hanya boleh melalui `markAsSyncedIfUnchanged(id, updated_at, pending_operation)` yang mensyaratkan konten sama seperti saat snapshot diambil; perubahan lokal apa pun (misalnya pengguna membuka detail) membuat pengakuan gagal dan baris tetap pending.
3. **Metadata yang hilang tidak dianggap keberhasilan.** Pada `retryPendingUploads`, hanya baris ber-`pendingOperation` CREATE/UPDATE/DELETE yang diunggah; baris tanpa asal-usul (provenance) yang jelas (artefak historis) dilaporkan sebagai sesuatu yang membutuhkan review, bukan dianggap telah tersinkron.
4. **Data demo tidak pernah masuk cache production**, dan sebaliknya (binding terpisah per flavor).

---

## 7. Arus Read: Pull

### 7.1 Pemicu pull

| Pemicu | Lintasan | Koalescing |
| --- | --- | --- |
| Masuk route/foreground | `NavGraph` → `RemoteRefreshViewModel.refresh()` → `SyncRunner.run(userId)` | 30 detik per user (`SystemClock.elapsedRealtime`); mutex + timestamp |
| Membaca daftar template | `WorkoutTemplateRepository.getTemplates/getTemplateById` → `refreshTemplates` | — (di dalam `withRemoteSession`, menuntut online) |
| Membaca riwayat | `WorkoutLogRepository.getWorkoutLogs/getWorkoutLogById/getLastWorkoutLog` → `refreshHistory` | — |
| Membuka layar katalog | `ExerciseBrowserViewModel`/`ExerciseSelectionViewModel` → `refreshExercises` | mutex + timestamp 30 detik |
| Login/registrasi/deep-link auth | `syncScheduler.enqueue(userId)` | **No-op** pada production (pull pertama terjadi melalui refresh route atau saat pembacaan) |
| Perubahan konektivitas | `RemoteWorkoutCoordinator.init` | **Tidak memicu pull** — hanya memperbarui label state UI `IDLE`/`OFFLINE` |
| `SyncWorker` (legacy) | `doWork()` | **No-op** — mengembalikan `Result.success()` agar antrean lama selesai tanpa jaringan |

Seluruh pemuatan UI berjalan *offline-tolerant*: ketika refresh gagal atau perangkat offline, `SyncRunResult.RETRYABLE_FAILURE` diterima tanpa melempar pengecualian ke UI; tampilan tetap memuat data Room (Flow tetap hidup).

### 7.2 `run()` — pull gabungan sekali eksekusi

`RemoteWorkoutCoordinator.run(expectedUserId)`:
1. Mengunci `syncMutex` (menserialisasi seluruh operasi pull/push sync).
2. Memeriksa syarat: `currentUserOrNull().id == expectedUserId` (bila diisi), user tidak ada dalam `blockedUsers`, dan `networkMonitor.isOnline`.
3. Menjalankan empat read berurutan — `pullExercises`, `pullProfile`, `pullTemplates`, `pullWorkoutLogs` — masing-masing dibungkus `captureRead` (error ditangkap); bila satu saja gagal, `run()` gagal dengan `RETRYABLE_FAILURE` (refresh yang parsial tidak pernah dianggap sukses).
4. Sukses → status kembali `IDLE` dan `lastSyncTime` diperbarui; gagal → status `FAILED`.

### 7.3 Detail per jenis pull

**Exercise (server-authoritative):** menggunakan `pages()` (500 baris per halaman, `order(id asc)`, token akses pada header). Perhatikan: **halaman offset tidak menjadi bukti bahwa snapshot server lengkap** — karena itu importer hanya `insertExercises` (menimpa yang ada), dan baris cache yang tidak dikirim server **tidak dihapus**. Stempel `last_exercises_sync` disimpan per user pada `SyncPreferences`.

**Profile:** membaca `profiles` milik akun sendiri; hanya menulis cache `UserEntity` bila barisnya saat ini `SYNCED` dan tanpa pending.

**Template (owner-scoped):** tiga langkah per template:
1. Baca parent (`workout_templates`) — catat `remoteRevision`.
2. Baca anak (`template_exercises`).
3. **Baca ulang parent** (`singleOrNull`), lalu `check(latest == remote)` — bila parent berubah di tengah pembacaan anak, seluruh `run()` gagal dengan pesan *"Remote template changed while reading its children; retry required"* (tidak pernah menulis sebagian).

**Workout log (owner-scoped):** pola yang sama (baca log → baca `exercise_logs` → baca `workout_sets` → verifikasi parent tidak berubah).

**Impor snapshot (`RemoteSnapshotImporter`):** semuanya dilakukan dalam **satu `database.withTransaction`**; aturan penerimaannya:

| Kondisi | Hasil |
| --- | --- |
| Ada anak lokal `pendingOperation != null` atau `syncStatus != SYNCED` | Snapshot **ditolak** — baris pending menang |
| Template lokal milik user lain | Ditolak |
| Template lokal memiliki `remoteRevision != null` | Diterima hanya bila `remoteRevision` remote ≥ lokal |
| Template lokal tanpa revision | Diterima bila `updatedAt` remote ≥ `updatedAt` lokal; selain itu ditolak |
| Workout log lokal `updatedAt > remote.updatedAt` | Ditolak (lokal lebih baru) |
| Semua kondisi aman | Timpa parent, delete+insert anak, tandai `SYNCED` |

Catatan khusus: **provenance `inputDefaults` dipertahankan** selama proses pull workout — bila konten performa set (setNumber, weight, reps, isCompleted) pada versi remote identik dengan lokal, kolom `input_defaults` lama tetap dipertahankan supaya perilaku prefill tidak berubah hanya karena sinkronisasi.

---

## 8. Arus Write Lokal

Semua penulisan yang murni lokal dilakukan melalui `WorkoutLocalStore` dan selalu di dalam transaksi Room:

### 8.1 Fasilitas tulis yang tetap offline-capable

| Aksi | Perilaku |
| --- | --- |
| `startWorkout` | Draft `active_sessions` lama milik user dihapus, draft baru ditulis (JSON `session_data_json` + kolom indeks pendukung). Prefill diambil melalui `getPrefillForExercise`. |
| `mutateActiveSession` | Deserialisasi JSON → transformasi → `validateActiveSessionEdit` → tulis ulang; semua di bawah satu kunci (`sessionMutex`) dan dalam satu transaksi. |
| `requestCancelSession` | Menandai `terminalSessions` (set memori) lalu menjalankan penghapusan hingga 3 kali dengan backoff pada `cleanupScope`. |
| `updateSessionRestOverride` | Menulis kolom `session_rest_override`. |
| `finishWorkout` | Lihat §9.1 — persist lokal seluruh aggregate, lalu satu kali percobaan upload. |

### 8.2 Catatan tentang jalur tulis template lama

Jalur tulis template yang offline-capable pada `WorkoutLocalStore` (`createTemplate` menandai `PENDING_SYNC` lalu `syncScheduler.enqueue`) **tidak lagi dapat dijangkau dari facade production** — `WorkoutRepositoryImpl` memakai `WorkoutTemplateRepository` yang menuntut jaringan (lihat §10). Jalur tersebut tetap dipertahankan karena mengimplementasikan kontrak `WorkoutRepository` yang sama, tetapi tidak dipanggil oleh alur production.

---

## 9. Arus Upload: Push

### 9.1 Setelah workout selesai (satu percobaan otomatis)

`WorkoutSessionManager.finishWorkout()`:

1. `WorkoutLocalStore.finishLocally()` dalam transaksi Room:
   - Menyaring hanya set yang `isCompleted` — set yang belum selesai maupun log tanpa completed set tidak pernah menghasilkan baris log (volume hanya menghitung completed sets).
   - Menulis `WorkoutLogEntity` + `ExerciseLogEntity` + `WorkoutSetEntity`, masing-masing dengan `sync_status = PENDING_SYNC`, `pending_operation = CREATE`.
   - Identitas deterministik: `stableUuid("exercise-log", sessionId, index, exerciseId)` dan `stableUuid("workout-set", exerciseLogId, setIndex, setNumber)` — memungkinkan perbandingan snapshot yang stabil meskipun proses mati dan hidup kembali.
   - `check(activeSessionDao.deleteSessionById(session.id) == 1)` — draft harus terhapus tepat satu baris.
2. Segera setelahnya `attemptFinishedWorkout(userId, id)` pada `RemoteWorkoutCoordinator`:
   - Syarat: `pendingOperation == CREATE`, milik user aktif, tidak diblokir logout, dan online.
   - `syncWorkoutAggregate`: mengambil `WorkoutUploadSnapshot` **dalam transaksi** dan memastikan baris tidak berubah (`getWorkoutLogById(log.id) == log`).
   - Mengirim RPC `upsert_workout_aggregate` (parent + exercises + sets).
   - Validasi receipt: `workout_id == log.id`, `exercise_count` dan `set_count` cocok — ketidaksesuaian berarti *"Unexpected aggregate receipt; pending preserved"*.
   - Transaksi kedua: memastikan sesi belum berganti (`currentSessionOrNull() === credential`), snapshot masih identik (`matches`), lalu `markAsSyncedIfUnchanged`; bila pengakuan parent berhasil (`marked == 1`), barulah anak-anak ditandai `SYNCED`.
   - Ketika gagal: pengecualian dilempar dari `syncWorkoutAggregate`, lalu ditelan oleh `attemptFinishedWorkout` — **pending tetap tahan-lama**, tidak ada retry pada loop background.

### 9.2 Retry manual (satu-satunya jalan ulang)

`HomeViewModel.retryPendingUploads()` → `syncRunner.retryPendingUploads(owner)`:
- Menelusuri semua `getPendingLogs(userId)` yang `pendingOperation`-nya valid, mengunggah satu per satu dengan pemeriksaan sesi per baris.
- Keberhasilan dinilai dari hasil akhir `pendingCount == 0` (agregat template + intent + log — `countRecoveryAggregates`).
- Tombol Retry tampil di `HomeScreen` ketika `pendingCount > 0`; kegagalan memunculkan flag `uploadRetryFailed`.

### 9.3 Aturan anti-upload-otomatis yang wajib dipahami kontributor

| Pemicu | Perilaku wajib |
| --- | --- |
| Koneksi kembali (`NetworkMonitor`) | Hanya mengembalikan status UI ke `IDLE`. **Tidak** mengunggah apa pun. |
| Masuk foreground / pindah route | Read refresh saja. **Tidak** mengunggah. |
| `WorkManager` (`enqueue`) | No-op pada production (`WorkManagerSyncScheduler.enqueue` kosong). |
| Background service/alarm rest timer | Tidak berhubungan dengan upload; hanya membaca identitas sesi. |

Kebijakan ini tertulis eksplisit di KDoc `RemoteWorkoutCoordinator`: *"there is no automatic upload queue. Reconnect, foreground refresh and legacy Worker requests never upload pending rows."*

---

## 10. Template Write

Menulis template adalah jalur dengan tuntutan integritas tertinggi, karena harus aman terhadap: proses mati di tengah request, response yang tidak pasti (permintaan tersimpan di server tetapi klien tidak menerima response), duplikasi permintaan, dan konflik revision.

### 10.1 Komponen

| Komponen | Peran |
| --- | --- |
| `TemplateWriteRequest` (DTO) | `p_request_id`, `p_template_id`, `p_expected_revision`, `p_name`, `p_exercises[]`, `p_removed_exercise_ids[]`, `p_is_deleted`, `p_owner_id` |
| `template_write_intents` (Room) | Kunci deterministik: `UUID(owner\u001Foperation\u001Ftarget)` — intent sama → payload sama. |
| `save_template_revision` (RPC) | Optimistic concurrency: memverifikasi `p_expected_revision` cocok dengan `workout_templates.revision`. |
| `template_write_receipts` (Postgres) | Tabel bukti server: `(user_id, request_id)` → `payload`, `receipt`. Menjadi jaminan idempotence antar-perangkat. |

### 10.2 Urutan langkah (`write` pada `WorkoutTemplateRepository`)

```
[Transaksi 1 (prepare)]
  - baca intent lama dengan key yang sama
      → ada?  payload harus identik (sameIntent); selain itu error
              "Resolve the previous save before changing its draft"
  - untuk edit/delete: template harus SYNCED dan punya remote_revision
      ("Refresh/review this template before editing; backend revision
        support is required")
      anak-anak juga SYNCED, dan katalog exercise harus lengkap
      → snapshot(parent+children) = baseline
  - insert TemplateWriteIntentEntity

[RPC jaringan]
  - save_template_revision → receipt
  - validasi receipt: request_id, template_id, exercise_count, revision > expected

[Transaksi 2 (commit)]
  - pastikan sesi belum berganti dan snapshot lokal masih identik dengan baseline
      ("Local template changed while saving; recovery request retained")
  - bila revision lokal ≤ receipt.revision:
      tulis template+children dengan SYNCED + remote_revision baru
  - intentDao.acknowledge(key, owner, payload) harus tepat 1 baris

[Gagal mana pun]
  - CancellationException diteruskan
  - sisanya dibungkus NetworkOperationException:
      "Template save failed. Your draft/request is retained; retry the same
       save or review the conflict."
  → intent TIDAK dihapus; percobaan ulang yang identik memakai request_id yang
    sama sehingga server mengembalikan receipt yang sama (idempotent).
```

### 10.3 Sisi server (`save_template_revision`)

Ringkasan perilaku (berkas `supabase/migrations/20261025000100_template_revision_writes.sql`):

1. `p_owner_id` harus sama dengan `auth.uid()`.
2. `pg_advisory_xact_lock` pada `(owner, request_id)` lalu pada `template_id` — menserialisasi pembuatan ganda dan menghindari race.
3. Bila `template_write_receipts` sudah memiliki `request_id` tersebut → payload harus identik; kembalikan receipt lama (idempotent).
4. Pembuatan baru ditolak bila `p_is_deleted = true` atau `expected_revision` terisi; update ditolak bila revision tidak cocok (`40001`).
5. Anak yang dihapus harus **tepat sama** dengan yang hilang dari payload (`removed_exercise_ids` eksplisit) — tidak ada delete-then-reinsert diam-diam.
6. Baris anak digeser `order_index`-nya menggunakan offset tinggi sebelum reindex agar swap tidak melanggar unique index; `order_index` hasil akhir harus kontinu 0..N-1.
7. `revision` bertambah melalui trigger `bump_template_revision` (update parent) dan `bump_template_from_child` (DML pada `template_exercises`).

---

## 11. Primitif Koordinasi

Seluruh koordinasi terpusat pada satu kelas singleton `RemoteWorkoutCoordinator`:

| Primitif | Tujuan |
| --- | --- |
| `syncMutex` | Menserialisasi semua operasi pull/push sync; `syncMutex` ini juga diambil oleh `blockAndAwaitIdle` (logout fence) sehingga logout menunggu operasi yang sedang berjalan selesai. |
| `masterMutex` | Kunci tersendiri untuk pull katalog (`refreshMasterData`); `blockAndAwaitIdle` mengunci keduanya sebelum mengizinkan pemangkasan data. |
| `blockedUsers` | Diisi oleh `blockAndAwaitIdle`: kumpulan user yang sedang berada dalam fence. Semua `run`, `retryPendingUploads`, `refreshMasterData`, `withRemoteSession`, `requireCurrentUser`, dan `updatePendingCount` menolak user yang diblokir. |
| Validasi sesi | `requireCurrentSession(credential)` dan `requireCurrentUser(userId)` — di setiap batas transaksi identitas diverifikasi; bila sesi berganti, operasi terhenti (`check`) dan status pending tidak pernah ditandai atas nama akun yang salah. |
| `WorkoutUploadSnapshot.matches` | Perbandingan konten **satu per satu** (bukan hanya timestamp): parent + anak yang diurutkan berdasarkan ID. Menjamin *"no timestamp-only child acknowledgement"* — pengakuan sync hanya bila kontennya benar-benar identik. |
| `invalidationTracker.createFlow` | Flow reaktif Room (lihat §5); juga menjaga `pendingCount` tetap mutakhir. |
| `CoroutineScope(Dispatchers.IO + SupervisorJob)` | Scope mandiri untuk listener lifecycle (network state, perubahan sesi auth, invalidationTracker) agar kegagalan tidak menjatuhkan dependensi lain. |

Empat kontrak sync yang masih dikonsumsi lapisan presentasi (`SyncStateProvider`, `SyncRunner`, `SyncLifecycleController`, `SyncScheduler`) tetap diikat ke satu implementasi — KDoc menegaskan hal ini: *"Sync interfaces are presentation/lifecycle compatibility contracts, not offline scheduling."*

---

## 12. Logout dan Pemangkasan Cache

`AuthRepositoryImpl.logout()`:

```
1. timerCleanup.stop("LOGOUT")                    — hentikan rest timer/FGS
2. syncScheduler.cancelForUser(userId)            — batalkan unique work legacy
3. blockAndAwaitIdle(userId)                      — fence + kunci syncMutex+masterMutex
4. check(peekLocalUserId() == userId)             — identitas masih cocok
5. pruneSyncedCacheForLogout(userId)              — transaksi Room (§12.1)
6. syncPreferences.clearForUser(userId)           — hapus stempel waktu per user
7. cachedUser = null
8. check(peekLocalUserId() == userId)             — masih sama/konsisten
9. supabaseClient.auth.signOut()
```

Kegagalan di tengah langkah 4–9 → `unblock(userId)` dipanggil terlebih dahulu (`if (peekLocalUserId() == userId) unblock`) agar tidak ada operasi yang tertahan di fence, lalu error diteruskan. Login, registrasi, dan deep-link auth memanggil `unblock` + `enqueue` (enqueue tetap no-op).

### 12.1 `LogoutRecoveryStore.pruneSyncedCache`

Prinsipnya: **logout mempertahankan kebutuhan pemulihan dan memangkas cache yang sudah aman.**

| Dihapus | Dipertahankan |
| --- | --- |
| `workout_logs` yang `SYNCED`, tanpa pending, dan tidak memiliki anak pending (beserta `workout_sets`, `exercise_logs`-nya) | **Semua aggregate pending**, apa pun statusnya |
| `workout_templates` yang `SYNCED`, tanpa anak pending, dan tidak dipakai oleh `workout_logs`, `active_sessions`, maupun `template_write_intents` | Template yang menjadi dependensi pemulihan (dipakai log pending, draft sesi aktif, atau intent) |
| Baris `users` (bila `SYNCED` dan tanpa pending) | Baris user bila masih ada pending |

Artinya: setelah logout yang sehat, hanya data yang **belum** terkonfirmasi server (dan ketergantungan minimumnya) yang tersisa di perangkat — cukup untuk memulihkan proses unggah ulang ketika akun yang sama masuk kembali; data profil yang dapat diunduh ulang dari server ikut dibersihkan selama masih `SYNCED`.

### 12.2 Identitas berubah di tengah proses

Logout mensyaratkan dua kali pengecekan `peekLocalUserId() == userId` — sebelum pemangkasan dan sebelum `signOut()`; perubahan identitas apa pun menggagalkan pemangkasan (fail-closed).

---

## 13. Migrasi Database dan Pemulihan

`OnlineRecoveryMigration(7, 9)` menyatukan dua varian layout historis versi 7 (perangkat yang pernah memakai layout berbeda) tanpa kehilangan data. Versi 8 berasal dari branch pembuktian terpisah dan sengaja tidak diterka serta tidak dimigrasikan.

1. Untuk delapan tabel inti, schema target dibuat sebagai tabel sementara `online_recovery_<tabel>` dengan definisi v9 (termasuk `remote_revision`, `input_defaults`, `deleted_at`, dan kolom rest timer).
2. Schema lama dibaca melalui `PRAGMA table_info`; kolom yang dikenal dipetakan; kolom `created_at`/`updated_at` yang wajib dilindungi `COALESCE(..., 0)`.
3. Kolom metadata yang tidak ada pada layout lama diisi default: `sync_status = 'SYNC_FAILED'`, `pending_operation = 'REVIEW'` — baris semacam itu tidak otomatis diunggah dan wajib direview manusia terlebih dahulu.
4. Validasi ketat: jumlah baris sumber harus sama dengan jumlah baris tujuan; kolom tak dikenal memicu `check` *"preserve database for review"* — tidak ada fallback destruktif.
5. Tabel `template_write_intents` dibuat bila belum ada.
6. Seluruh migrasi berjalan dalam satu transaksi Room, sehingga satu kegagalan mengembalikan semuanya.

`SyncWorker.doWork()` sengaja menjadi `Result.success()`: request sync lama yang pernah terjadwal ditandai selesai tanpa menyentuh jaringan dan tanpa memberi bendera pending apa pun.

---

## 14. Sesi Aktif

`active_sessions` menyimpan paling banyak satu draft sesi per user (setiap penulis menghapus draft lama terlebih dahulu sebelum insert). Isi kolomnya:

- `session_data_json` — snapshot penuh `WorkoutSession` (gerakan, set, `restSeconds`, `inputDefaults`, pause state) yang diencode melalui `SerializableSession`.
- Kolom pendukung kueri alarm: `currentExerciseIndex`, `is_paused`, `total_paused_time_ms`, `last_pause_time`, `rest_timer_end_time`, `rest_timer_exercise_name`, `session_rest_override`.

Kebijakan:

1. **Draft tidak pernah disinkronkan** — template yang dipakai draft tetap tersinkron seperti template biasa.
2. `startWorkout` memanggil `timerCleanup?.stop("SESSION_REPLACED")` terlebih dahulu supaya timer lama tidak tersisa.
3. Proses mati dan hidup kembali: `getActiveSession()` mendeserialisasi JSON kembali ke model domain; `terminalSessions` (memori) menahan sesi yang sudah selesai/dibatalkan agar tidak muncul kembali ketika Room cleanup belum selesai.
4. `isActiveSessionForTimer` membaca ID sesi saja (`getActiveSessionId`) supaya jalur alarm tidak perlu melakukan deserialisasi JSON penuh.

---

## 15. Jaringan dan State UI

### 15.1 `NetworkMonitor`

- `isOnline`: menuntut `NET_CAPABILITY_INTERNET` **dan** `NET_CAPABILITY_VALIDATED` — jaringan tanpa internet (misalnya hotspot kosong) dianggap offline.
- `networkStatus: Flow<Boolean>` (callbackFlow, `distinctUntilChanged`) diobservasi koordinator untuk memperbarui label state UI.

### 15.2 `SyncState`

```kotlin
data class SyncState(
    val status: SyncStatus,      // IDLE, SYNCING, SYNCED, FAILED, OFFLINE
    val pendingCount: Int,       // template+intent+log ("recovery aggregates")
    val lastSyncTime: Long?,
    val errorMessage: String?,
    val progress: Float,
    val progressMessage: String?,
    val isInitialSync: Boolean
)
```

Detail yang mudah terlewat:

- `pendingCount` mencakup **baris anak** pada perhitungan (`countRecoveryAggregates` menyertakan EXISTS pada tabel anak), bukan hanya parent.
- Status `OFFLINE` tidak pernah menjadi pemicu upload — murni label status untuk UI.
- `isInitialSync` saat ini **tidak pernah bernilai true** oleh koordinator (komponen `SyncProgressDialog` masih ada dan dipakai `MainScreen`, tetapi dialog itu tidak pernah tampil karena field `isInitialSync` selalu false; `SyncPreferences.isInitialSyncCompleted` tetap dipertahankan sebagai kontrak lama).
- `SyncState` direset ketika sesi auth berubah (`sessionStatus`), sehingga data status akun tidak menumpuk antar-akun.

### 15.3 Permukaan UI

| UI | Sumber | Perilaku |
| --- | --- | --- |
| `HomeSyncStatusCard` | `HomeScreen` membaca `viewModel.syncState` | Menampilkan ketika `pendingCount > 0`, `SYNCING`, atau `FAILED` |
| Tombol Retry | `HomeScreen.onRetryUploads` → `HomeViewModel.retryPendingUploads` | Menandai `retryingUploads`/`uploadRetryFailed` |
| `SyncIndicator`/`SyncCard` | `presentation/components/common` | Menampilkan status dan pending count sesuai state (`when`) |

---

## 16. Kontrak Backend (Supabase)

Sesi auth **tersimpan lokal oleh SDK Auth** (offline start tetap dianggap terautentikasi); `SplashViewModel.isLoggedIn` hanya membaca `auth.currentUserOrNull()` — tidak menyentuh jaringan. `AuthRepositoryImpl.getLocalUserId()` baru memanggil `awaitInitialization()` bila sesi lokal belum selesai dimuat.

| Kapabilitas | Mekanisme | Rujukan |
| --- | --- | --- |
| Auth | Supabase Auth `Email` + PKCE; deep-link `imfit://auth` ditangani `SupabaseAuthDeepLinkHandler` | `SupabaseModule` — `enableLifecycleCallbacks = false` agar background rest timer melihat sesi stabil (komentar penjelasan tertulis di `SupabaseModule`) |
| Storage | Bucket privat `avatars`; upload path `userId/UUID.jpg`; UI meminta **signed URL 1 jam** | `AuthRepositoryImpl.uploadProfilePhoto/getSignedAvatarUrl` |
| Read REST | `postgrest.from(...)` dengan header `Authorization: Bearer <token>`; paging manual 500 baris | `RemoteWorkoutCoordinator` |
| Read refresh template/history | Owner-scoped, order by id, verifikasi parent tidak berubah | lihat §7.3 |
| Write workout | `upsert_workout_aggregate(jsonb, jsonb, jsonb)` — SECURITY INVOKER | `ImfitAggregateRemoteDataSource.upsertWorkout` |
| Write template | `save_template_revision(...)` — SECURITY DEFINER + tabel receipt | `saveTemplate` |
| RLS dan hardening | Semua tabel memiliki RLS; fungsi di-`revoke` dari `public`/`anon`/`authenticated`, lalu di-`grant` spesifik | `20260828130154_imfit_backend_hardening.sql` |

Perilaku `upsert_workout_aggregate` yang penting bagi klien:

1. `auth.uid()` wajib ada; `p_workout.user_id` bila terisi harus cocok.
2. `on conflict (id) do update ... where user_id = v_uid` — upsert atas id milik user lain ditolak (`42501`).
3. `set.exercise_log_id` divalidasi kepemilikan (`23503`).
4. Timestamp server diambil dari `statement_timestamp()` dan dikembalikan sebagai `server_updated_at`; klien tidak menyuntikkan `updated_at` sendiri — timestamp server-lah yang dipakai saat commit lokal.
5. RPC **tidak menghapus** baris anak; klien selalu mengirim seluruh snapshot (parent + exercises + sets) sehingga data server dan lokal selalu identik pasca-upload — set yang tidak dikirim akan tetap ada di server dan lalu ditarik saat pull berikutnya.

---

## 17. Jaminan, Batasan, dan Gap

### 17.1 Jaminan (berlaku pada production saat ini)

| # | Jaminan | Tempat ditegakkan |
| --- | --- | --- |
| G1 | Data pending tidak pernah hilang karena pull remote | `RemoteSnapshotImporter`, `pullProfile` |
| G2 | Upload salah akun tidak mungkin — setiap lapisan (coordinator, transaksi, pemeriksaan sesi) memverifikasi `user_id` | `requireCurrentSession`, `requireCurrentUser`, `snapshot()` |
| G3 | Pengakuan sync hanya bila konten yang diterima server identik dengan konten yang diunggah | `WorkoutUploadSnapshot.matches` + `markAsSyncedIfUnchanged` |
| G4 | Upload tidak pernah sebagian: parent baru menjadi `SYNCED` bila pengakuan parent berhasil (`marked == 1`); anak menyusul di transaksi yang sama | `syncWorkoutAggregate` |
| G5 | Tidak ada upload otomatis (reconnect/foreground/worker) | KDoc + `WorkManagerSyncScheduler.enqueue` no-op + `SyncWorker` no-op |
| G6 | Logout tidak menggagalkan jaringan yang sedang berjalan; operasi tidak merebut identitas baru | `blockAndAwaitIdle` + `blockedUsers` |
| G7 | `request_id` sama dengan payload berbeda ditolak; payload sama menghasilkan receipt sama (idempotent) | `save_template_revision` |
| G8 | Migration tidak pernah destruktif | `OnlineRecoveryMigration` (row-count check + transaksi) |

### 17.2 Batasan yang disengaja (bukan bug)

| # | Batasan | Alasannya |
| --- | --- | --- |
| B1 | Upload workout yang gagal tidak diulang berantai otomatis; pengguna harus menekan tombol Retry (`retryPendingUploads`) | Loop background otomatis sengaja tidak ada; tertulis tegas di berbagai KDoc |
| B2 | Template wajib memiliki `remote_revision` sebelum dapat diedit | Revision server adalah syarat keandalan; baris legacy tanpa revision direview dulu |
| B3 | Tidak ada delta sync (filter `updated_at`) — pull selalu snapshot penuh | Menekan kompleksitas; delta-sync direncanakan sebagai pekerjaan terpisah (`docs/online_first.md`) |
| B4 | Update anak belum memiliki protokol tombstone sendiri (tombstone hanya pada level parent) | Reconciliasi update anak belum diterapkan (`docs/backend/imfit-supabase-compose-integration.md`) |
| B5 | `RetryUtils` (backoff) sudah dibangun lengkap dengan unit test, tetapi belum dipakai di jalur production | Menunggu kebutuhan retry yang terstruktur |
| B6 | Tidak ada resolusi konflik interaktif; konflik selalu diartikan: baris pending menang atau masuk review | Sesuai PRD (tanpa dialog manual merge) |

### 17.3 Gap yang diketahui (dari pindai source saat ini, bukan dokumen lama)

| # | Gap | Bukti |
| --- | --- | --- |
| K1 | Template tidak dapat dibuat ketika offline (`createTemplate` → `withRemoteSession` menuntut jaringan), sementara hasil workout tetap dapat dipersist lokal | `WorkoutTemplateRepository.createTemplate` |
| K2 | `SyncProgressDialog`/`isInitialSync` bertahan sebagai kompatibilitas kontrak lama, tetapi tidak pernah aktif pada arsitektur koordinator saat ini | `SyncState.isInitialSync` tidak pernah true |
| K3 | Baris pending dengan metadata tidak lengkap (`REVIEW`) wajib direview manusia; belum ada UI review | `retryPendingUploads` hanya mengunggah baris ber-`pendingOperation` CREATE/UPDATE/DELETE |
| K4 | History masih di-pull penuh (tanpa paging berbasis cursor atau filter `updated_at`) | `pages()` hanya offset paging |
| K5 | `template_write_intents` hanya terhapus melalui `acknowledge` yang sukses; intent yang selamanya gagal tetap tersimpan dan juga menahan pemangkasan template terkait saat logout | `disposableTemplates ... NOT EXISTS template_write_intents` |

---

## 18. Peta Sumber

| Topik | Berkas |
| --- | --- |
| Inti koordinator | `app/src/main/java/com/diajarkoding/imfit/data/sync/RemoteWorkoutCoordinator.kt` |
| Snapshot importer | `data/sync/RemoteSnapshotImporter.kt` |
| Aturan prioritas snapshot template | `data/sync/TemplateSnapshotOrder.kt` |
| Snapshot upload workout | `data/sync/WorkoutUploadSnapshot.kt` |
| Stempel waktu per-user | `data/sync/SyncPreferences.kt` |
| Enum status | `data/local/sync/SyncStatus.kt` |
| No-op scheduler/worker | `app/src/production/.../sync/WorkManagerSyncScheduler.kt`, `data/sync/SyncWorker.kt` |
| Repository facade & komponen | `data/repository/{WorkoutRepositoryImpl, WorkoutTemplateRepository, WorkoutLogRepository, WorkoutSessionManager, WorkoutLocalStore, ExerciseRepositoryImpl}.kt` |
| Pagar owner | `data/repository/AccountDataAccess.kt` |
| Auth & logout | `data/repository/AuthRepositoryImpl.kt` |
| RPC klien | `data/remote/ImfitAggregateRemoteDataSource.kt`, `data/remote/TemplateWriteRequest.kt` |
| Database & migrasi | `data/local/database/{IMFITDatabase, OnlineRecoveryMigration, LogoutRecoveryStore, Converters}.kt` |
| Entities & DAO | `data/local/entity/`, `data/local/dao/` |
| Binding DI | `app/src/production/java/com/diajarkoding/imfit/di/{AppModule, DatabaseModule, SupabaseModule}.kt`, `app/src/demo/.../di/AppModule.kt` |
| Jaringan | `core/network/{NetworkMonitor, NetworkException, RetryUtils}.kt` |
| UI sync state | `presentation/components/common/{SyncIndicator, SyncProgressDialog}.kt`, `presentation/ui/home/HomeScreen.kt`, `presentation/navigation/RemoteRefreshViewModel.kt` |
| Backend SQL | `supabase/migrations/20260828130114_imfit_schema_baseline.sql`, `...imfit_backend_hardening.sql`, `20261025000100_template_revision_writes.sql` |
| Dokumen terkait | `docs/online_first.md` (konteks migrasi ke online-first), `docs/backend/imfit-supabase-compose-integration.md` (status integrasi), `docs/prd/imfit-mobile-app-prd.md` (PRD aktif), `docs/features/16-local-storage-cloud-sync-personal-light.md` dan `docs/features/17-demo-mode-personal-light.md` (dokumentasi fitur; bagian sync usang) |
