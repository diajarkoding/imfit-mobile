# PRD: IMFIT Backend API Service

## 1. Ringkasan

Bangun API backend IMFIT dengan TypeScript yang berjalan pada Bun dan memakai Drizzle ORM untuk PostgreSQL. API ini menggantikan akses langsung Android production ke Supabase Auth, PostgREST, dan Storage, tanpa mengubah perilaku fitur mobile yang sudah aktif: autentikasi, profil/avatar, katalog exercise, template workout, sync log workout, serta progress/riwayat yang bersumber dari log lokal.

**Status:** Proposed. Belum ada service Bun/TypeScript dalam repository ini.

## 2. Dasar Implementasi

Sumber utama kebutuhan adalah kode production saat ini, bukan `schema_db.md` yang merupakan rancangan Supabase lama dan memiliki ketidaksesuaian dengan kontrak sync aktif.

| Klaim | Evidence |
| --- | --- |
| Android menggunakan Supabase SDK langsung | `app/src/production/.../di/SupabaseModule.kt` |
| Auth email/password, profil, dan avatar aktif | `app/src/main/.../data/repository/AuthRepositoryImpl.kt` |
| Kontrak sync yang aktif berada pada DTO private | `app/src/main/.../data/sync/SyncManager.kt:859-1034` |
| Template dan log ditulis local-first lalu disinkronkan | `WorkoutRepositoryImpl.kt:132-177,384`; `SyncManager.kt` |
| Sesi aktif hanya Room lokal | `WorkoutRepositoryImpl.kt:276-485` |

## 3. Tujuan dan Batasan

### Tujuan

- Menyediakan API berautentikasi untuk seluruh data cloud yang dibaca/ditulis Android production.
- Menjaga ID yang dibuat client, JSON `snake_case`, serta format tanggal/waktu yang dapat diparse client saat ini.
- Menegakkan ownership pada server, bukan mempercayai `user_id` dalam request.
- Mendukung sync offline-first yang idempoten untuk template dan aggregate workout selesai.
- Menyediakan signed URL avatar private yang bisa langsung dimuat Coil.

### Di luar cakupan

- Sinkronisasi `active_sessions` lintas perangkat. Tidak dilakukan production mobile saat ini.
- Fitur sosial, Wear OS, AI coach, export, dashboard web, analytics, dan realtime.
- Password reset, OAuth, atau PKCE browser flow. Tidak ada flow aktif di client.
- Mengubah kalkulasi progress Android atau menyimpan preference tema/bahasa, karena keduanya lokal DataStore.

## 4. Pengguna dan Alur

| Pengguna | Kebutuhan aktual |
| --- | --- |
| Pengguna Android terautentikasi | Daftar/login, sinkron profil/avatar, katalog, template, dan workout history. |
| Aplikasi Android offline | Menulis Room lalu mengirim perubahan pending ketika jaringan kembali. |
| Administrator konten | TODO/VERIFY. Tidak ada UI/admin contract saat ini; exercise master harus tetap dapat diseed/diatur di database. |

### Alur sinkronisasi yang harus dipertahankan

1. Android membuat atau mengubah template/log di Room dengan ID UUID dari client dan status pending.
2. Saat login, registrasi, mutasi template/log, atau jaringan kembali tersedia, worker mengirim sync untuk user.
3. API menerima push template terlebih dahulu, lalu aggregate workout (`workout_logs` -> `exercise_logs` -> `workout_sets`).
4. Client menarik exercise, template, template exercise, workout log, exercise log, dan set dalam urutan tersebut.
5. API mengembalikan hanya data pemilik token; tidak boleh menyediakan unfiltered child rows seperti asumsi RLS Supabase lama.

## 5. Stack dan Arsitektur Backend

| Area | Keputusan |
| --- | --- |
| Runtime | Bun |
| Bahasa | TypeScript dengan `strict` aktif |
| HTTP | Router TypeScript yang kompatibel Bun: TODO/VERIFY pilihan framework, misalnya Hono atau Elysia |
| ORM/migrasi | Drizzle ORM + `drizzle-kit` |
| Database | PostgreSQL |
| Auth | API menerbitkan dan memverifikasi bearer access token serta refresh token. Implementasi JWT/session store: TODO/VERIFY. |
| File | Object storage private dengan signed read URL. Provider: TODO/VERIFY. |
| Validation | Schema runtime untuk semua body/query. Library: TODO/VERIFY. |
| Background work | Tidak diperlukan untuk kontrak mobile saat ini; cleanup session/token/object adalah TODO/VERIFY. |

Struktur minimum:

```text
backend/
  src/
    app.ts
    routes/{auth,profile,exercises,templates,workouts,sync}.ts
    middleware/auth.ts
    db/{client,schema,migrations}/
    services/{tokens,avatars,sync}.ts
    validators/
  drizzle.config.ts
  package.json
```

## 6. API Konvensi

- Base path: `/v1`.
- Semua endpoint selain register/login/refresh/health memerlukan `Authorization: Bearer <access_token>`.
- JSON menggunakan `snake_case` agar mapper Android dapat diganti minimal.
- Timestamp menggunakan ISO-8601 dengan offset, contoh `2026-08-27T10:30:00Z`; `workout_logs.date` menggunakan `YYYY-MM-DD`.
- ID template/log/child yang disediakan client harus diterima bila UUID valid; server tidak menggantinya.
- Respons error: `{ "error": { "code": "...", "message": "..." } }`; validasi memakai `422`, autentikasi `401`, ownership/not-found `404`, conflict `409`, rate limit `429`.
- List response: `{ "data": [...], "next_cursor": null }`. Cursor/delta filter belum dipakai Android; tetap siapkan sebagai ekstensi backward-compatible.

## 7. Endpoint Kebutuhan

### 7.1 Auth dan profil

| Method / path | Request minimum | Respons / perilaku |
| --- | --- | --- |
| `POST /v1/auth/register` | `email`, `password`, `name`, `birth_date?` | Buat user dan profil, kembalikan user + token. Password minimum 6 mengikuti client. |
| `POST /v1/auth/login` | `email`, `password` | Kembalikan user + access/refresh token. |
| `POST /v1/auth/refresh` | refresh token | Rotasi/terbitkan token baru. Detail rotasi: TODO/VERIFY. |
| `POST /v1/auth/logout` | refresh token atau session context | Cabut session/token server. Android juga tetap membatalkan worker lokal. |
| `GET /v1/me` | - | Profil: `id`, `name`, `email`, `birth_date`, `avatar_url`, `created_at`, `updated_at`. |
| `PATCH /v1/me` | `name?`, `birth_date?`, `avatar_url?` | Ubah profil sendiri; `avatar_url` adalah object key, bukan URL publik. |
| `POST /v1/me/avatar` | binary multipart image | Validasi image, simpan key `{user_id}/{uuid}.jpg`, kembalikan `{ avatar_url: key }`. |
| `GET /v1/me/avatar-url` | `path` atau gunakan avatar profil | Kembalikan signed direct-read URL yang kedaluwarsa 3.600 detik. |

Catatan kompatibilitas: `AuthRepositoryImpl` saat ini mengandalkan session Supabase segera setelah signup. Client harus dimigrasikan ke respons token endpoint ini; tidak ada adapter server yang dapat membuat Supabase SDK memanggil API Bun tanpa perubahan Kotlin.

### 7.2 Katalog exercise

| Method / path | Perilaku |
| --- | --- |
| `GET /v1/exercises` | Kembalikan exercise aktif/nonaktif sesuai kebutuhan sync: `id`, `name`, `muscle_category_id`, `description`, `is_active`, `created_at`, `updated_at`. Saat ini Android menarik seluruh katalog. |
| `GET /v1/muscle-categories` | TODO/VERIFY. DTO ada, tetapi tidak ada operasi remote production aktif. Endpoint aman disediakan untuk kebutuhan masa depan. |

Exercise adalah master data server-authoritative. ID harus string stabil seperti `ex_chest_1`; `muscle_category_id` harus kompatibel dengan pemetaan enum Android.

### 7.3 Template workout

| Method / path | Request / perilaku |
| --- | --- |
| `GET /v1/templates` | Hanya template pemilik; sertakan `id`, `user_id`, `name`, `is_deleted`, `created_at`, `updated_at`. |
| `PUT /v1/templates/:id` | Upsert idempoten parent dengan `name`, `is_deleted?`, `updated_at?`; ownership dari token. |
| `DELETE /v1/templates/:id` | Soft delete (`is_deleted=true`) untuk kompatibilitas sync. |
| `GET /v1/templates/:id/exercises` | Kembalikan `template_id`, `exercise_id`, `order_index`, `sets`, `reps`, `rest_seconds`. |
| `PUT /v1/templates/:id/exercises` | Ganti atomik seluruh daftar exercise pemilik. Tolak duplicate `exercise_id`; validasi `sets >= 1`, `reps >= 1`, `rest_seconds >= 0`. |

Endpoint replace-list diperlukan karena mobile sekarang menghapus semua child remote lalu memasukkan daftar lokal lengkap. Implementasi API boleh lebih atomik, tetapi hasil harus setara.

### 7.4 Workout logs dan sync aggregate

| Method / path | Request / perilaku |
| --- | --- |
| `GET /v1/workout-logs` | Hanya log pemilik, termasuk `deleted_at`, `created_at`, `updated_at`. |
| `PUT /v1/workout-logs/:id` | Idempotent upsert log client ID. Body: `template_id?`, `template_name`, `date`, `start_time`, `end_time`, `total_volume`, `total_sets`, `total_reps`, `deleted_at?`. |
| `GET /v1/workout-logs/:id/exercises` | Hanya children log milik user. |
| `PUT /v1/workout-logs/:id/exercises` | Upsert child `id`, `exercise_id`, `exercise_name`, `muscle_category`, `order_index`, `total_volume`, `total_sets`, `total_reps`, serta sets bersarang atau endpoint bulk. |
| `PUT /v1/workout-logs/:id/sets` | Upsert child `id`, `exercise_log_id`, `workout_log_id`, `exercise_id`, `set_number`, `weight`, `reps`, `is_completed`. |
| `POST /v1/sync` | Endpoint rekomendasi untuk menggantikan banyak request: menerima perubahan ordered template/log dan mengembalikan seluruh dataset user dalam urutan pull. Kontrak final: TODO/VERIFY sebelum perubahan client. |

`POST /v1/sync` adalah target integrasi yang lebih aman, tetapi endpoint resource di atas harus tersedia atau Kotlin `SyncManager` harus ditulis ulang. Jangan menerapkan hard-delete log pada API sampai bug client pending DELETE diselesaikan; client aktif selalu melakukan upsert log.

## 8. Model Data Drizzle

Skema harus mencerminkan wire contract aktif, bukan schema Supabase lama saja.

| Tabel | Kolom penting |
| --- | --- |
| `users` | `id` UUID, `email` unique, `password_hash`, timestamps |
| `sessions` | ID, `user_id`, refresh token hash, expiry/revocation timestamps |
| `profiles` | `id` FK users, `name`, `email`, `birth_date?`, `avatar_url?`, timestamps |
| `muscle_categories` | integer ID, name/display name/sort order |
| `exercises` | string ID, `muscle_category_id`, name, description, `is_active`, timestamps |
| `workout_templates` | UUID ID, `user_id`, name, `is_deleted`, timestamps |
| `template_exercises` | composite unique `(template_id, exercise_id)`, order/set/reps/rest |
| `workout_logs` | UUID ID, `user_id`, template snapshot, date/start/end/totals, `deleted_at?`, `updated_at` |
| `exercise_logs` | UUID ID, `workout_log_id`, exercise snapshots, order, `total_volume`, `total_sets`, `total_reps` |
| `workout_sets` | UUID ID, `exercise_log_id`, **`workout_log_id`**, **`exercise_id`**, set/weight/reps/completed |

Wajib: foreign key dan ownership checks pada seluruh child write; indexes `(user_id, updated_at)` untuk template/log, `(workout_log_id)` untuk child, dan unique `(exercise_log_id, set_number)`. `workout_sets.workout_log_id` dan `exercise_id`, serta totals exercise log, wajib ada karena dikirim/dibaca `SyncManager` aktif.

## 9. Aturan Bisnis dan Konsistensi

- User hanya bisa membaca/memodifikasi profil, template, dan log miliknya sendiri. `user_id` pada body divalidasi sama dengan token atau diabaikan/diderivasi server.
- Nama template harus non-empty. Batas panjang dan keunikan tidak boleh ditambahkan tanpa perubahan client yang disengaja; Home mobile saat ini mengizinkan template kosong.
- Template tidak boleh memiliki `exercise_id` duplikat.
- Set selesai mobile hanya valid dengan weight dan reps > 0; backend menerima histori sinkron dan harus minimal menolak nilai negatif serta timestamp akhir sebelum awal.
- Perubahan aggregate workout dan seluruh child write harus transactionally consistent per request.
- Timestamps server harus authoritative untuk `created_at` dan `updated_at`; simpan client `updated_at` bila diperlukan untuk merge, tetapi jangan mempercayainya sebagai ownership proof.

## 10. Konflik dan Migrasi Sync

Perilaku client sekarang adalah partial last-write-wins: local pending menang terhadap pull; remote yang lebih baru menimpa local yang sudah `SYNCED`; tidak ada precondition saat push.

Backend MVP harus menerima upsert idempoten dan memastikan ownership. Untuk mengurangi overwrite diam-diam, API baru sebaiknya menerima `base_updated_at` atau versi pada setiap mutasi dan mengembalikan `409` saat stale. Ini **memerlukan perubahan `SyncManager` Android** dan tidak boleh diaktifkan sebagai syarat request lama tanpa rollout client.

Target berikutnya, setelah client mendukungnya:

- `updated_since` cursor untuk delta pull, karena client saat ini selalu full pull meski menyimpan timestamp sync.
- Replace children atomik pada workout log agar remote child yang dihapus tidak tertinggal.
- Tombstone/hard-delete contract yang eksplisit untuk log/template.

## 11. Security dan Privacy

- Password harus memakai hash modern yang sesuai library server; plaintext tidak disimpan/log.
- Access token singkat dan refresh token hanya disimpan sebagai hash server-side. TTL/rotasi: TODO/VERIFY.
- Semua query Drizzle harus mendapat `userId` dari auth middleware sebelum query, termasuk nested child route.
- Avatar private: cek MIME berdasarkan bytes, ukuran maksimal, dan authorization sebelum upload/signed URL. Batas ukuran: TODO/VERIFY; Android saat ini tidak membatasi ukuran.
- Batasi rate login/register/avatar dan gunakan pesan error yang tidak membocorkan status akun secara berlebihan.
- Jangan masukkan database URL, secret JWT, object-storage key, atau password ke repository maupun response/log.

## 12. Non-Functional Requirements

- API harus mengembalikan respons deterministik dan JSON yang kompatibel Kotlin serialization.
- Write sync harus aman diulang akibat WorkManager retry.
- Kesalahan transient harus menggunakan status yang membedakan retry (`5xx`/`429`) dari kesalahan validasi (`4xx`).
- Observability minimum: request ID, user ID ter-redaksi/hashed bila perlu, endpoint, status, latency, dan error class; tidak ada password/token/body avatar pada log.
- Health endpoint `GET /health` tanpa auth untuk readiness.
- Backup, retention, SLO, deployment topology, dan monitoring provider: TODO/VERIFY.

## 13. Acceptance Criteria

1. User dapat register, login, refresh, logout, lalu `GET /v1/me` hanya mengembalikan profilnya.
2. Avatar private dapat diupload dan signed URL-nya dapat dimuat oleh Coil selama 3.600 detik; URL/object key pengguna lain ditolak.
3. `GET /v1/exercises` mengembalikan ID string dan category ID yang dapat dipetakan Android.
4. Upsert template berulang dengan ID sama tidak membuat duplicate; replace exercise list tidak menyisakan child lama dan menolak duplicate exercise.
5. Upsert satu aggregate workout beserta exercise log/set dapat diulang tanpa duplicate, dan tidak bisa menulis child milik log user lain.
6. Pull log/template tidak mengembalikan data user lain, termasuk children.
7. Semua timestamp/date respons memenuhi format Kotlin yang digunakan client.
8. Migrasi Drizzle membuat constraint/index pada bagian Model Data dan dapat dijalankan ulang secara terkendali.
9. Test API mencakup auth, authorization cross-user, avatar access, template replacement, idempotent log sync, dan validasi request.
10. Rencana migrasi Android mengganti `AuthRepositoryImpl` dan `SyncManager` dari SDK Supabase ke HTTP bearer API sebelum Supabase SDK/config dihapus.

## 14. Risiko dan TODO/VERIFY

| Item | Dampak | Status |
| --- | --- | --- |
| Client tidak punya HTTP API abstraction | Backend Bun membutuhkan perubahan Kotlin AuthRepository/SyncManager | Open |
| Register mengasumsikan session langsung | Definisi email verification/token delivery perlu diputuskan | TODO/VERIFY |
| Sync delete log client tidak benar | Hard delete dapat berubah menjadi upsert tombstone | Open |
| Full-table pull | Skala dan bandwidth memburuk; perlu delta setelah client support | Open |
| Child log remote tidak di-update/delete pada pull | Riwayat bisa stale; perlu replace/upsert merge jelas | Open |
| Framework HTTP, auth token implementation, object storage | Belum ada evidence project | TODO/VERIFY |
| `schema_db.md` lama | Tidak boleh dijadikan sumber tunggal migrasi Drizzle | Verified risk |

## 15. Referensi

- `docs/features/imfit-features.md`
- `docs/features/01-authentication-personal-light.md`
- `docs/features/09-active-workout-session-personal-light.md`
- `docs/features/16-local-storage-cloud-sync-personal-light.md`
- `app/src/main/java/com/diajarkoding/imfit/data/repository/AuthRepositoryImpl.kt`
- `app/src/main/java/com/diajarkoding/imfit/data/sync/SyncManager.kt`
- `app/src/production/java/com/diajarkoding/imfit/di/SupabaseModule.kt`
