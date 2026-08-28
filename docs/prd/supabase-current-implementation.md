# PRD Teknis: Implementasi Supabase Saat Ini

## Metadata Dokumen

| Atribut | Nilai |
|---|---|
| Produk | IMFIT Mobile |
| Platform | Android native, Kotlin, Jetpack Compose |
| Package | `com.diajarkoding.imfit` |
| Status | As-is, terverifikasi dari source code |
| Tanggal verifikasi | 28 Agustus 2026 |
| Backend | Supabase Auth, PostgREST, dan Storage |
| Arsitektur data | Room-first dengan sinkronisasi dua arah |

## 1. Tujuan

Dokumen ini menjelaskan implementasi Supabase yang benar-benar digunakan oleh aplikasi IMFIT saat ini. Dokumen ini menjadi acuan untuk:

- memahami hubungan aplikasi Android, Room, WorkManager, dan Supabase;
- menyiapkan konfigurasi project Supabase untuk build `production`;
- memverifikasi tabel, storage bucket, dan kebijakan RLS yang dibutuhkan aplikasi;
- membedakan fitur yang sudah aktif dari model atau rencana yang belum digunakan;
- mengidentifikasi risiko teknis sebelum deployment atau pengembangan lanjutan.

Dokumen ini tidak memuat nilai `SUPABASE_URL`, anon key, access token, atau secret lain.

## 2. Ruang Lingkup

### Termasuk

- konfigurasi Supabase Kotlin client;
- autentikasi email dan password;
- profile pengguna;
- upload dan pembacaan avatar privat;
- sinkronisasi exercises, workout templates, dan workout history;
- penyimpanan lokal Room;
- penjadwalan sinkronisasi dengan WorkManager;
- pemisahan flavor `demo` dan `production`;
- error handling dan batasan implementasi saat ini.

### Tidak Termasuk

- perubahan schema Supabase;
- SQL migration yang dapat dieksekusi;
- Supabase Realtime;
- Edge Functions;
- backup manual;
- dashboard admin;
- implementasi web atau backend terpisah.

## 3. Ringkasan Implementasi

Implementasi production menggunakan pola **Room-first** untuk data workout. UI tidak menunggu Supabase ketika pengguna membuat template atau menyelesaikan workout. Perubahan disimpan terlebih dahulu ke Room, ditandai sebagai pending, lalu dikirim ke Supabase ketika jaringan tersedia.

Auth, profile, dan avatar berbeda dari data workout. Ketiga area tersebut mengakses Supabase secara langsung dan tidak menggunakan Room sebagai sumber data utama.

```text
UI / ViewModel
    |
    +-- Auth, profile, avatar ------------------------> Supabase
    |
    +-- Templates, exercises, workout history
            |
            v
        Repository
            |
            v
          Room <---- sumber data utama untuk UI
            |
            +-- pending operation
                    |
                    v
        NetworkMonitor / WorkManager
                    |
                    v
               SyncManager
                    |
                    v
                 Supabase
```

## 4. Status Kapabilitas

| Kapabilitas | Status | Implementasi saat ini |
|---|---|---|
| Email registration | Aktif | Supabase Auth `signUpWith(Email)` |
| Email login | Aktif | Supabase Auth `signInWith(Email)` |
| Logout | Aktif | Membatalkan sync user dan memanggil `signOut()` |
| Session restore | Aktif terbatas | Mengandalkan persistensi default Supabase Auth SDK |
| Profile | Aktif | Direct select/update pada tabel `profiles` |
| Avatar privat | Aktif | Bucket `avatars` dan signed URL 1 jam |
| Exercise catalog | Aktif | Pull dari Supabase ke Room, server-authoritative |
| Workout templates | Aktif | Room-first, push dan pull |
| Workout history | Aktif | Room-first, push dan pull |
| Active workout session | Lokal saja | Disimpan di Room, tidak dikirim ke Supabase |
| Background sync | Aktif | One-time WorkManager dengan network constraint |
| Initial sync | Aktif | Full pull setelah login baru, install baru, atau database kosong |
| Delta sync | Belum penuh | Timestamp disimpan tetapi pull masih mengambil seluruh result set |
| Realtime | Tidak ada | Dependency dan subscription Realtime tidak dipasang |
| Profile offline | Tidak ada | `UserDao` tersedia tetapi belum digunakan repository production |
| Executable DB migration | Tidak ada | Schema masih berupa dokumen Markdown |

## 5. Konfigurasi Project

### 5.1 Dependency

Supabase Kotlin menggunakan BOM versi `3.0.3`. Plugin yang dipasang:

- Auth;
- PostgREST;
- Storage.

Dependency dideklarasikan pada:

- `gradle/libs.versions.toml`;
- `app/build.gradle.kts`.

Tidak terdapat dependency Supabase Realtime.

### 5.2 Local configuration

Root `local.properties` harus memiliki:

```properties
SUPABASE_URL=https://<project-ref>.supabase.co
SUPABASE_ANON_KEY=<public-anon-key>
```

`app/build.gradle.kts` membaca kedua properti tersebut dan membuat:

```kotlin
BuildConfig.SUPABASE_URL
BuildConfig.SUPABASE_ANON_KEY
```

Catatan keamanan:

- `local.properties` diabaikan oleh Git;
- anon key akan tertanam di APK dan bukan server-side secret;
- keamanan data wajib mengandalkan RLS dan Storage policies;
- service role key tidak boleh digunakan di aplikasi Android;
- jika properti tidak tersedia, build saat ini menggunakan string kosong dan tidak gagal lebih awal.

### 5.3 Supabase client

Client production dibuat sebagai singleton Hilt di:

`app/src/production/java/com/diajarkoding/imfit/di/SupabaseModule.kt`

Konfigurasi Auth saat ini:

```text
Flow type : PKCE
Scheme    : imfit
Host      : login-callback
```

Plugin yang di-install:

```text
Auth + Postgrest + Storage
```

Manifest saat ini belum memiliki deep-link intent filter `VIEW` dan `BROWSABLE` untuk callback PKCE. Hal ini tidak menghambat email/password biasa, tetapi perlu diselesaikan sebelum memakai OAuth atau callback eksternal.

## 6. Product Flavor

### Production

Flavor `production` menggunakan:

- `AuthRepositoryImpl`;
- `ExerciseRepositoryImpl`;
- `WorkoutRepositoryImpl`;
- `SyncManager`;
- `WorkManagerSyncScheduler`;
- Room database `imfit_database`;
- singleton `SupabaseClient`.

Binding tersedia di:

- `app/src/production/java/com/diajarkoding/imfit/di/AppModule.kt`;
- `app/src/production/java/com/diajarkoding/imfit/di/DatabaseModule.kt`;
- `app/src/production/java/com/diajarkoding/imfit/di/SupabaseModule.kt`.

### Demo

Flavor `demo` tidak mengakses Supabase. Flavor ini menggunakan fake repository in-memory, user demo otomatis, dan no-op sync provider.

Konsekuensi:

- build demo tidak menguji Auth, RLS, Storage, atau network sync;
- test demo tidak membuktikan integrasi Supabase production;
- verifikasi backend harus menggunakan varian production dan project Supabase yang terisolasi.

## 7. Resource Supabase yang Digunakan

| Resource | Arah data | Perilaku aplikasi |
|---|---|---|
| `auth.users` | Dua arah melalui Auth SDK | Register, login, logout, current user |
| `profiles` | Direct remote read/write | Profile dibaca setelah login dan dapat di-update |
| `exercises` | Supabase ke Room | Catalog server-authoritative |
| `workout_templates` | Dua arah | Parent aggregate template |
| `template_exercises` | Dua arah | Child template, diganti sebagai satu aggregate |
| `workout_logs` | Dua arah | Parent aggregate workout selesai |
| `exercise_logs` | Dua arah terbatas | Push upsert, pull hanya insert data yang belum ada |
| `workout_sets` | Dua arah terbatas | Push upsert, pull hanya insert data yang belum ada |
| Storage `avatars` | Upload dan signed read | File privat per folder user |

### Resource yang dimodelkan tetapi belum aktif

| Resource | Status |
|---|---|
| `muscle_categories` | Ada pada DTO/schema, tetapi tidak ada PostgREST call aktif |
| `active_sessions` remote | Ada pada DTO/schema, tetapi active session runtime hanya di Room |
| Storage `exercises` | Ada pada perencanaan, tetapi aplikasi tidak mengakses bucket ini |
| Room `users` | Entity dan DAO ada, tetapi production auth tidak menggunakannya |

## 8. Alur Autentikasi

### 8.1 Registration

1. UI memvalidasi nama, email, password, konfirmasi password, dan tanggal lahir.
2. Repository memanggil `signUpWith(Email)`.
3. Nama dan tanggal lahir opsional dikirim sebagai user metadata.
4. Repository meminta current user ID dari session Supabase.
5. Foto profile opsional di-upload ke bucket `avatars`.
6. Aplikasi mengasumsikan trigger database sudah membuat row dasar `profiles`.
7. `birth_date` dan `avatar_url` di-update pada row profile jika tersedia.
8. User disimpan pada cache memory.
9. Initial/background sync dijadwalkan.
10. Navigation mengganti root destination menjadi halaman utama.

Keterbatasan penting: jika email confirmation diaktifkan dan signup tidak langsung menghasilkan session, registration akan gagal karena current user ID tidak tersedia. Aplikasi belum memiliki halaman "check your email" atau alur konfirmasi email.

### 8.2 Login

1. Repository memanggil `signInWith(Email)`.
2. Current user ID dibaca dari Supabase Auth.
3. Profile dibaca dari tabel `profiles`.
4. Jika profile tidak ditemukan, aplikasi mencoba membuat profile dari auth metadata menggunakan `upsert`.
5. Jika profile tetap tidak tersedia, aplikasi membuat domain user minimal dari auth metadata.
6. User disimpan pada cache memory.
7. Sinkronisasi dijadwalkan.
8. Root navigation diganti menjadi halaman utama.

Fallback user membuat login tetap berjalan ketika profile gagal dibaca, tetapi dapat menyembunyikan masalah RLS atau schema.

### 8.3 Session restore

Saat aplikasi dimulai, Splash memanggil `isLoggedIn()`. Status login ditentukan dari `currentUserOrNull()` milik Supabase Auth SDK.

Implementasi saat ini:

- tidak memiliki `SessionManager` aplikasi;
- tidak menyimpan token sendiri di DataStore;
- tidak mengobservasi session expiry secara reaktif;
- tidak memiliki guard session pada setiap destination;
- mengandalkan mekanisme persistensi dan refresh default SDK.

### 8.4 Logout

1. Ambil current user ID.
2. Batalkan unique sync work untuk user tersebut.
3. Panggil Supabase Auth `signOut()`.
4. Hapus cache user jika sign-out berhasil.
5. Navigation kembali ke Login.

Error logout saat ini hanya dicatat ke log dan tidak diteruskan ke UI. Room dan sync preferences juga tidak dibersihkan ketika logout.

## 9. Profile dan Avatar

### 9.1 Profile

Kontrak profile yang digunakan aplikasi:

| Field | Kegunaan |
|---|---|
| `id` | Sama dengan `auth.users.id` |
| `name` | Nama pengguna |
| `email` | Email profile |
| `birth_date` | Tanggal lahir opsional |
| `avatar_url` | Storage path, bukan public URL |
| `created_at` | Waktu pembuatan |
| `updated_at` | Waktu perubahan |

DTO dan mapping berada di:

`app/src/main/java/com/diajarkoding/imfit/data/remote/dto/ProfileDto.kt`

Profile saat ini tidak offline-first. `AuthRepositoryImpl` membaca tabel `profiles` secara langsung dan hanya menyimpan hasil terakhir di memory.

### 9.2 Avatar

Alur upload avatar:

1. UI memilih gambar dari gallery atau camera.
2. Repository membaca seluruh file menjadi `ByteArray`.
3. Nama file dibuat sebagai `{userId}/{uuid}.jpg`.
4. File di-upload ke private bucket `avatars` dengan `upsert = true`.
5. Storage path disimpan di `profiles.avatar_url`.
6. UI meminta signed URL dengan masa berlaku 3.600 detik.
7. Coil menggunakan URL untuk menampilkan dan meng-cache gambar.

Keterbatasan:

- tidak ada resize atau compression;
- MIME type tidak diverifikasi;
- semua file dinamai `.jpg` meskipun sumber dapat memiliki format lain;
- seluruh file dimuat ke memory;
- avatar lama tidak dihapus;
- signed URL hanya diperbarui ketika ViewModel memuat ulang data;
- kegagalan upload avatar tidak menggagalkan registration.

## 10. Arsitektur Room-First

Room version 6 memiliki tabel:

1. `users`;
2. `exercises`;
3. `workout_templates`;
4. `template_exercises`;
5. `workout_logs`;
6. `exercise_logs`;
7. `workout_sets`;
8. `active_sessions`.

Data workout dibaca oleh UI dari Room. Supabase berperan sebagai remote backup dan media pertukaran data antar install/device.

### Status sinkronisasi lokal

```text
SYNCED       : data lokal sesuai dengan remote
PENDING_SYNC : terdapat perubahan lokal yang belum dikirim
SYNC_FAILED  : percobaan sync gagal dan harus dicoba kembali
```

### Pending operation

```text
CREATE
UPDATE
DELETE
```

Template dan workout log menjadi parent aggregate. Child records disinkronkan bersama parent.

## 11. Sinkronisasi

### 11.1 Trigger

Sinkronisasi dapat dimulai oleh:

- jaringan kembali tersedia;
- registration berhasil;
- login berhasil;
- template dibuat, diubah, atau dihapus;
- workout selesai;
- WorkManager menjalankan pending work.

`Mutex` pada `SyncManager` mencegah dua proses sinkronisasi berjalan bersamaan di process yang sama.

### 11.2 Urutan normal

```text
1. Pastikan user terautentikasi
2. Pastikan jaringan tersedia
3. Push pending workout templates
4. Push pending workout logs beserta child records
5. Tentukan apakah initial sync diperlukan
6. Pull exercises
7. Pull workout templates
8. Pull template exercises
9. Pull workout logs
10. Pull exercise logs
11. Pull workout sets
12. Simpan timestamp dan update UI sync state
```

Perubahan lokal selalu di-push sebelum remote data di-pull agar pending edit lokal tidak langsung tertimpa.

### 11.3 Initial sync

Initial sync dijalankan jika:

- user belum pernah menyelesaikan initial sync; atau
- data template lokal kosong dan last-sync timestamp masih nol.

Initial sync mengambil seluruh data berikut:

- exercises;
- templates;
- template exercises;
- workout logs;
- exercise logs;
- workout sets.

Progress initial sync diekspos melalui `SyncState` dan dapat ditampilkan oleh `SyncProgressDialog`.

### 11.4 WorkManager

Production scheduler membuat one-time work dengan konfigurasi:

| Properti | Nilai |
|---|---|
| Constraint | `NetworkType.CONNECTED` |
| Backoff | Exponential |
| Backoff awal | 10 detik |
| Unique work | `imfit-sync-{userId}` |
| Existing work policy | `APPEND_OR_REPLACE` |

Tidak terdapat periodic sync. Sinkronisasi mengandalkan event aplikasi, perubahan jaringan, dan one-time work.

### 11.5 Push behavior

#### Workout template

- Create memakai `upsert` dengan conflict key `id`.
- Update mengubah nama dan `updated_at`.
- Delete memakai soft-delete `is_deleted = true`.
- Child `template_exercises` remote dihapus lalu current local list dimasukkan kembali.

#### Workout history

- Parent `workout_logs` di-upsert berdasarkan ID.
- `exercise_logs` di-upsert berdasarkan ID.
- `workout_sets` di-upsert berdasarkan ID.
- Row lokal ditandai `SYNCED` setelah seluruh request aggregate berhasil.

Remote aggregate bukan transaksi database tunggal. Jika salah satu request gagal, sebagian row dapat sudah tersimpan di Supabase. Stable UUID dan upsert membuat retry relatif idempotent.

### 11.6 Pull dan conflict handling

Aturan template dan workout log:

- remote row yang belum ada akan dimasukkan ke Room;
- remote row yang lebih baru diterapkan hanya jika local row sudah `SYNCED`;
- local row dengan pending operation dipertahankan;
- push dijalankan sebelum pull;
- perubahan dibandingkan menggunakan timestamp untuk last-write-wins sederhana.

Exercises selalu dianggap server-authoritative.

Keterbatasan conflict handling:

- clock skew antar perangkat dapat memengaruhi hasil;
- kegagalan parsing timestamp menggunakan waktu perangkat saat ini;
- child exercise log dan set yang sudah ada tidak diperbarui saat pull;
- row remote yang hilang dari result set tidak otomatis dihapus lokal;
- belum ada UI untuk menyelesaikan konflik manual.

### 11.7 Delta sync

`SyncPreferences` menyimpan timestamp global dan beberapa timestamp per resource. Namun query PostgREST saat ini belum menerapkan filter seperti:

```text
updated_at > last_sync_timestamp
```

Dengan demikian, sinkronisasi setelah initial sync masih melakukan full pull terhadap result set yang diizinkan RLS. Istilah "delta sync" pada komentar source belum menggambarkan perilaku aktual.

## 12. UI Sync State

`SyncManager` mengekspos `StateFlow<SyncState>` dengan status:

```text
IDLE
SYNCING
SYNCED
FAILED
OFFLINE
```

State juga membawa:

- jumlah aggregate pending;
- last sync time;
- error message;
- initial sync flag;
- progress `0.0..1.0`;
- progress message.

`MainScreen` menampilkan `SyncProgressDialog` sebagai overlay penuh ketika initial sync berlangsung. Komponen indikator sinkronisasi juga tersedia pada presentation components.

## 13. Error Handling

### Auth

Exception Supabase dipetakan menjadi domain exception seperti:

- invalid credentials;
- email sudah terdaftar;
- weak password;
- invalid email;
- session expired;
- account not verified;
- rate limited;
- network error;
- unknown error.

Mapping saat ini menggunakan pencocokan teks pesan exception. Pendekatan ini sensitif terhadap perubahan pesan dari SDK/backend.

### Sync

- Kegagalan aggregate menandai parent sebagai `SYNC_FAILED`.
- Pending operation tetap tersimpan agar dapat di-retry.
- WorkManager mengembalikan retryable failure ketika sync belum berhasil.
- Error message mentah disimpan pada `SyncState`.
- Cancellation tetap dilempar agar coroutine dapat dihentikan dengan benar.

### Fallback

- Exercise repository memakai bundled fake exercises jika Room kosong atau gagal dibaca.
- Profile fetch failure dapat menghasilkan basic user dari auth metadata.
- Avatar upload atau signed URL failure menghasilkan placeholder.

## 14. Persyaratan Supabase Project

Supabase project yang digunakan production harus memenuhi minimum berikut:

### Auth

- Email/password provider aktif.
- Keputusan email confirmation diselaraskan dengan UI registration.
- Jika confirmation aktif, aplikasi harus dikembangkan agar mendukung pending confirmation.

### Database

- Tabel yang dipakai runtime tersedia.
- Primary key dan foreign key sesuai stable ID dari aplikasi.
- Kolom wire DTO `SyncManager` tersedia.
- Trigger pembuatan `profiles` dari `auth.users` aktif atau profile insert policy disediakan.
- Trigger `updated_at` konsisten pada resource yang dibandingkan berdasarkan waktu.

### RLS

- RLS aktif pada semua tabel user-owned.
- User hanya dapat membaca dan mengubah row miliknya.
- Exercises dapat dibaca authenticated user.
- Child policy memverifikasi kepemilikan melalui parent.
- Profile dapat dibaca dan di-update pemiliknya.
- Jika login fallback boleh membuat profile, policy INSERT profile harus tersedia.

### Storage

- Private bucket `avatars` tersedia.
- User hanya dapat upload/read object dalam folder `{auth.uid()}/`.
- Signed URL dapat dibuat oleh authenticated owner.

## 15. Perbedaan Source Code dan Schema Dokumen

Repository belum memiliki folder migration Supabase atau file `.sql` yang dapat dijalankan otomatis. `schema_db.md` adalah dokumentasi/manual SQL dan bukan bukti schema production yang sudah terpasang.

Perbedaan yang harus diverifikasi terhadap Supabase project aktual:

| Area | Runtime code | `schema_db.md` |
|---|---|---|
| `workout_logs` | Membaca/menulis `updated_at` dan `deleted_at` | Definisi tabel tidak mencantumkan keduanya |
| `exercise_logs` | Mengirim `total_sets` dan `total_reps` | Definisi tabel tidak mencantumkan keduanya |
| `workout_sets` | Mengirim `workout_log_id` dan `exercise_id` | Definisi tabel tidak mencantumkan keduanya |
| `active_sessions` | Hanya menggunakan Room | Didokumentasikan sebagai tabel remote |
| Profile fallback | Dapat melakukan `upsert` | RLS yang didokumentasikan tidak memiliki policy INSERT |

Kontrak DTO private di `SyncManager.kt` harus dianggap sebagai bukti utama payload runtime sampai tersedia migration SQL yang terversi dan tervalidasi.

## 16. Risiko dan Technical Debt

### Prioritas tinggi

1. Schema Supabase aktual tidak dapat direproduksi dari repository karena tidak ada executable migration.
2. Email confirmation dapat membuat registration dianggap gagal setelah auth account berhasil dibuat.
3. Remote workout aggregate ditulis melalui beberapa request dan tidak atomic.
4. RLS/profile trigger yang salah dapat disamarkan oleh basic-user fallback.
5. Missing Supabase config tidak menghasilkan fail-fast yang jelas.

### Prioritas menengah

1. Pull belum benar-benar incremental.
2. Profile belum tersedia secara offline.
3. Tidak ada session-expiry observer atau route guard reaktif.
4. Existing child log/set tidak diperbarui ketika remote berubah.
5. Sync preferences dan data user tidak dibersihkan ketika logout.
6. Signed avatar URL dapat kedaluwarsa ketika screen tetap terbuka lama.
7. Upload avatar tidak melakukan compression, MIME validation, atau cleanup.

### Prioritas rendah

1. DTO remote publik dan DTO private `SyncManager` memiliki overlap.
2. Beberapa log memuat email, user ID, URI lokal, atau storage path.
3. `UserDao` tersedia tetapi belum memiliki consumer production.
4. Deep-link PKCE dikonfigurasi tetapi callback intent filter belum tersedia.

## 17. Acceptance Criteria Implementasi Saat Ini

Implementasi dianggap operasional apabila seluruh kondisi berikut terpenuhi pada environment production test:

- build production memiliki URL dan anon key yang valid;
- register menghasilkan auth user dan row `profiles`;
- login mengembalikan user dan membuka halaman utama;
- restart aplikasi memulihkan session yang masih valid;
- logout menghapus session Supabase dan kembali ke Login;
- avatar dapat di-upload ke folder user dan ditampilkan melalui signed URL;
- initial sync mengisi Room dengan exercises dan data user;
- template dapat dibuat offline dan terkirim setelah jaringan tersedia;
- update dan soft-delete template tersinkron ke Supabase;
- workout selesai tersimpan atomically di Room;
- parent log, exercise logs, dan sets terkirim setelah jaringan tersedia;
- fresh install dengan account yang sama dapat menarik kembali template dan history;
- satu user tidak dapat membaca atau mengubah data user lain;
- retry tidak membuat duplikasi row dengan ID berbeda;
- sync failure tetap mempertahankan pending local data;
- build demo tetap berjalan tanpa Supabase.

## 18. Checklist Verifikasi

### Build

```powershell
.\gradlew.bat compileProductionDebugKotlin
.\gradlew.bat testDemoDebugUnitTest
```

### Auth dan profile

- Register tanpa avatar.
- Register dengan gallery image.
- Register dengan camera image.
- Login dengan profile yang valid.
- Login ketika row profile sengaja tidak tersedia.
- Restart aplikasi dan verifikasi session.
- Logout saat online dan saat jaringan bermasalah.

### Data dan sync

- Login account tanpa data untuk initial sync.
- Login account dengan template dan history remote.
- Buat, edit, dan hapus template saat offline.
- Selesaikan workout saat offline.
- Pulihkan jaringan dan amati pending count.
- Paksa satu request gagal dan pastikan status dapat di-retry.
- Login pada device kedua dan verifikasi hasil pull.

### Security

- Verifikasi RLS dengan dua account berbeda.
- Verifikasi anon user tidak dapat membaca user-owned table.
- Verifikasi user tidak dapat upload avatar ke folder user lain.
- Pastikan service role key tidak berada di APK atau repository.

## 19. Sumber Kebenaran di Repository

| Area | File utama |
|---|---|
| Build config | `app/build.gradle.kts` |
| Version catalog | `gradle/libs.versions.toml` |
| Supabase client | `app/src/production/java/com/diajarkoding/imfit/di/SupabaseModule.kt` |
| Production bindings | `app/src/production/java/com/diajarkoding/imfit/di/AppModule.kt` |
| Auth/profile/storage | `app/src/main/java/com/diajarkoding/imfit/data/repository/AuthRepositoryImpl.kt` |
| Auth contract | `app/src/main/java/com/diajarkoding/imfit/domain/repository/AuthRepository.kt` |
| Profile DTO | `app/src/main/java/com/diajarkoding/imfit/data/remote/dto/ProfileDto.kt` |
| Workout repository | `app/src/main/java/com/diajarkoding/imfit/data/repository/WorkoutRepositoryImpl.kt` |
| Exercise repository | `app/src/main/java/com/diajarkoding/imfit/data/repository/ExerciseRepositoryImpl.kt` |
| Sync orchestration | `app/src/main/java/com/diajarkoding/imfit/data/sync/SyncManager.kt` |
| Work scheduler | `app/src/production/java/com/diajarkoding/imfit/data/sync/WorkManagerSyncScheduler.kt` |
| Worker | `app/src/main/java/com/diajarkoding/imfit/data/sync/SyncWorker.kt` |
| Sync preferences | `app/src/main/java/com/diajarkoding/imfit/data/sync/SyncPreferences.kt` |
| Local sync status | `app/src/main/java/com/diajarkoding/imfit/data/local/sync/SyncStatus.kt` |
| Room database | `app/src/main/java/com/diajarkoding/imfit/data/local/database/IMFITDatabase.kt` |
| Existing schema reference | `schema_db.md` |

## 20. Keputusan Arsitektur Saat Ini

1. Room adalah source of truth untuk fitur workout.
2. Local write tidak boleh menunggu network.
3. Stable UUID digunakan agar retry upsert tetap idempotent.
4. Pending local aggregate di-push sebelum remote pull.
5. Exercise catalog dikendalikan server.
6. Auth, profile, dan avatar tetap direct-to-Supabase.
7. Active workout session tetap lokal agar dapat dipulihkan setelah process restart.
8. Demo flavor harus independen dari Supabase.

Perubahan terhadap keputusan tersebut harus disertai update pada dokumen ini, kontrak schema, migration, dan test integrasi production.
