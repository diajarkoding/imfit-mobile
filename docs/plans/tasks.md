# IMFIT Online-Only — tasks.md

> **Dokumen kerja agent | 29 Oktober 2026 | Fokus: MVP internal preview**
>
> Tujuan: menghasilkan **APK IMFIT online-only yang benar-benar bisa dipakai untuk menyelesaikan workout dan menyimpan hasilnya di Supabase**. Bukan menambah arsitektur, dokumen, RPC, atau jumlah test.

**Status akhir — 29 Oktober 2026: MVP internal preview PASS, T0–T5 selesai.** APK dan konfigurasi uji ada pada hasil T5 di bawah; bukti final di [Implementation Report §7.13](full-online-only-implementation-report.md#713-mvp-internal-preview-t5). Bukan production/upgrade legacy atau distribusi klien eksternal.

## 1. Definisi MVP (Minimum Viable Product)

**Target rilis:** **internal preview untuk fresh install pada emulator/perangkat uji disposable, menggunakan backend Supabase lokal/terisolasi**. MVP ini **bukan** production rollout, tidak mencakup upgrade instalasi lama, dan tidak memberi izin deployment ke project Supabase utama.

**Perjalanan pengguna yang wajib selesai:**

`Login → Katalog gerakan → Buat/pilih rutinitas → Start Workout → Edit/selesaikan set → Finish → Summary → History/Progress → Tutup & buka kembali → Hasil tetap ada`.

**Kondisi berhasil:**

- Data bisnis baru (template, active workout, workout log, progress, profile) dibaca/ditulis melalui Supabase; memori Android hanya menyimpan state sementara. Tidak ada penulisan bisnis baru ke Room, antrean upload offline, atau full-sync tersembunyi.
- Hasil **Saved** hanya tampil setelah server mengonfirmasi. Finish atomik menghasilkan **satu** workout log sekalipun respons hilang lalu dicoba di-resolve/retry. Error jaringan tidak ditampilkan sebagai data kosong atau sukses.
- Login/logout dan ganti akun tidak membocorkan data; auth/RLS pemilik data bekerja. Timer berjalan lokal tanpa request setiap detik; cold-offline tidak mengklaim sesi terverifikasi.
- APK internal dapat dipasang, skenario di atas lulus **melalui UI + HTTP/backend nyata**, dan data tersimpan setelah restart aplikasi.

**Di luar MVP ini (jangan jadikan blocker preview fresh install):** migrasi/restore Room 6/7/9, import legacy massal, matriks lintas perangkat/API lengkap, load test ribuan transaksi, optimasi prematur, audit seluruh skenario crash, publish signing, rollout/cutoff production, dan fitur/navigasi baru. Semua tetap menjadi **pekerjaan sebelum rilis production atau upgrade pengguna lama bila relevan**, bukan dianggap selesai/dihapus. Jika APK ditujukan untuk upgrade instalasi lama, MVP fresh-install **tidak boleh** dipakai sebagai bukti aman.

## 2. Sumber kebenaran dan baseline

**Hierarki pemakaian:** tasks.md = pekerjaan sekarang → Feature Spec = kontrak/kebutuhan → Plan = desain/backlog → Report = bukti/blocker. Prioritas MVP tidak membatalkan kontrak integritas data. Baca/perbarui checklist ini setiap iterasi; setelah penyelarasan satu kali, jangan kembali ke roadmap release penuh sebelum MVP selesai.

- **Kontrak bisnis/keputusan:** `docs/plans/full-online-only-feature-spec.md` (D01–D06, K01–K05, AC relevan).
- **Desain teknis dan kewajiban release penuh:** `docs/plans/full-online-only-implementation-plan.md`.
- **Bukti, blocker dan histori:** `docs/plans/full-online-only-implementation-report.md`.
- **Checklist dan prioritas pekerjaan saat ini:** **`tasks.md` ini**. Baca bagian dokumen lain **hanya yang relevan** dengan task aktif; jangan mengaudit/menyalin ulang semua dokumen di setiap iterasi.

**Status awal dari dokumen yang diunggah (harus dicocokkan dengan checkout aktual, jangan diklaim PASS baru):** backend **17 RPC** inti dan G3 remote reads pernah PASS lokal; facade/session remote untuk dua flavor sudah dipasang; source recovery tambahan dan clean cutover sudah masuk checkpoint; host-only 414 unit production/421 demo serta build debug/release pernah PASS. **Production HTTP/UI end-to-end, backend recovery SQL/HTTP baru, timer perangkat dan upgrade legacy belum terbukti lulus pada source final.** Status commit/push checkpoint terakhir harus diperiksa; jangan menebak SHA.

## 3. Prioritas kerja — kerjakan berurutan

Gunakan hanya status `[ ]` belum, `[x]` selesai, `[!]` blocker. Tandai `[x]` **hanya jika ada bukti saat eksekusi**; source presence atau PASS historis tidak otomatis berarti MVP end-to-end lulus.

### P0 — Mulai dari kondisi nyata (maksimum satu audit singkat)

- [x] **T0.1** Periksa `git status`, branch/HEAD dan commit terakhir; catat modifikasi pengguna tanpa membuangnya. Jangan melakukan refactor atau menjalankan full suite pada langkah ini.
- [x] **T0.2** Cek apa yang **sudah terhubung**: konfigurasi Supabase lokal, 17 RPC inti + 4 recovery bila memang ada, DI production/demo, caller workout, dan sisa Room runtime. Tandai gap konkret yang menghambat MVP.
- [x] **T0.3** Prioritaskan blocker berdasarkan alur pengguna di §1; jangan membuka lagi task F1a/F1b/F2/G3 yang sudah selesai kecuali ada kegagalan reproduksibel pada source terkini.

**Selesai jika:** ada daftar blocker aktual maksimal lima item dengan file/fitur terdampak, tanpa membuat dokumen audit baru.

**Hasil T0 — 29 Oktober 2026 (PASS audit singkat; bukan runtime MVP PASS):**

- Checkout `online-only@5372f210b26b3ec9f846171cbbfd63e81f4fa482`, commit terakhir `feat(online): checkpoint legacy recovery and runtime cutover`. `HEAD...origin/online-only` = 0/0 terhadap ref cache lokal, tanpa fetch/bukti remote terbaru. Perubahan awal: tiga dokumen Spec/Plan/Report modified dan `tasks.md` untracked; semuanya dipertahankan. Tidak ada modifikasi source awal.
- Source migration: **14 file, 17/17 RPC inti + 4/4 recovery** ditemukan, termasuk `20261029000100_online_only_legacy_recovery.sql`; ini inventaris source, bukan bukti applied/SQL/HTTP. `LegacyStartPreflight` sudah memanggil `get_start_recovery_status`, bukan scan seluruh receipt lama.
- Wiring: `di/AppModule.kt` production/demo → `OnlineWorkoutRepository`; `di/RemoteReadModule.kt` → ProductionReadGateway/DemoReadGateway; shared `di/OnlineSessionModule.kt` → guarded Start + OnlineSessionStore. Production gateway memakai BuildConfig/Supabase Auth + transport RPC nyata; demo bukan bukti backend. Caller ActiveWorkoutViewModel sudah memakai typed commands/remote IDs dan Finish → receipt.workoutId → NavGraph/WorkoutSummary; fungsi alur belum diuji runtime.
- Room: pencarian terarah `app/src` tidak menemukan import `androidx.room`, DAO/writer/coordinator lama aktif; Gradle/version catalog tidak memiliki dependency Room. `data/local` hanya tiga fake datasource, sementara schema 6/7/9 dan standalone reader/recovery tetap ada. Tidak membuka ulang cleanup lama tanpa caller nyata yang ditemukan T4.

**Blocker MVP aktual, urut alur/dependensi (3 item):**

| Prioritas / task | Bukti dan fitur terdampak | Perbaikan terkecil berikutnya |
| --- | --- | --- |
| 1 — T1: target aplikasi bukan lokal | `local.properties` berisi URL **nonlokal/hosted**; `app/build.gradle.kts:52–57` memasukkannya ke BuildConfig dan production `di/SupabaseModule.kt:23–25` memakainya. Login/data belum diarahkan ke backend uji MVP; key tidak dicetak | Setelah backend lokal siap, gunakan URL/key lokal dan koneksi perangkat yang sesuai; jangan jalankan fixture pada target hosted |
| 2 — T1: Docker belum dapat diakses | `docker ps --all --filter name=supabase_` exit 1: socket `/var/run/docker.sock` tidak tersedia. Tool docker/node/python3/adb dan binary CLI Supabase lokal tersedia, tetapi kesiapan container/migration belum dapat diperiksa | Pulihkan/pilih daemon/context lokal yang benar, lalu verifikasi stack existing; jangan reset/purge/deploy sebagai shortcut |
| 3 — T2.2: conflict review tidak terjangkau | `app/src/main/java/com/diajarkoding/imfit/data/remote/online/OnlineSessionStore.kt:144–149` mempertahankan requiresVerification saat conflict berdraft sesudah reload; `app/src/main/java/com/diajarkoding/imfit/presentation/ui/workout/ActiveWorkoutScreen.kt:563–565` mensyaratkan flag false untuk Use server/Reapply. Recovery konflik workout terhalang menurut source; belum direproduksi UI | Perbaiki transisi verified-conflict secara terarah dan uji, tanpa membuka aksi sebelum authoritative reload |

**Non-blocker fresh install:** defect `OnlineRpc.kt:161` UUID untuk exercise_id text masih ada, tetapi berada di `importLegacyWorkout`; prefill memakai text dan AddExercise membawa exercise_id text. Pertahankan T2.1 untuk perbaikan terarah, jangan perluas ke seluruh upgrade/import legacy. Runtime UI/HTTP, timer, finish-response-loss dan persistensi restart tetap **NOT_TESTED**, ditangani T1/T3/T5, bukan bukti kegagalan baru. F1a/F1b/F2/G3 tidak dibuka ulang.

**Batas eksekusi T0 (historis saat task itu):** hanya T0.1–T0.3; tidak mengubah config/source, memulai backend, mengakses hosted, menjalankan build/test atau melanjutkan T1. Kelanjutan T1–T5 dicatat pada milestone masing-masing di bawah.

### P0 — Backend nyata dapat digunakan oleh aplikasi

- [x] **T1.1** Jalankan Supabase **lokal/terisolasi** memakai migration yang ada; cek schema dan RPC yang dibutuhkan alur MVP. **Jangan membuat migration/RPC baru** kecuali ada mismatch nyata yang tidak bisa diperbaiki dengan yang sudah ada.
- [x] **T1.2** Smoke lewat Auth/PostgREST nyata: login akun sintetis, baca katalog, buat/baca template, create/update/finish sesi, baca summary/history; pastikan RLS menolak akses akun lain.
- [x] **T1.3** Uji satu skenario finish berulang/response-loss untuk menjamin satu receipt/workout ID. Jika SQL/backend tidak berubah dan bukti existing masih valid, jangan ulang semua concurrency/10k-row/replay suite.

**Selesai jika:** endpoint nyata melayani alur MVP dan perubahan persisten setelah read ulang. **Tidak menggunakan project utama** untuk fixture/destructive test.

**Hasil T1 — 29 Oktober 2026 (PASS backend lokal, bukan UI/APK PASS):**

- Stack existing sehat pada context **`colima-imfit-online-verify`**, project/container `IMFIT-MOBILE`/`supabase_db_IMFIT-MOBILE`, API **`http://127.0.0.1:54321`**, PostgreSQL 17.6, CLI pinned 2.116.0. Perintah memakai `DOCKER_CONTEXT=colima-imfit-online-verify` per proses, tanpa mengubah default context. Blocker socket T0 terselesaikan dengan context lokal existing, bukan reset/start ulang.
- Inspeksi awal: 13 migration applied, empat RPC recovery belum tersedia. Backup lokal dibuat sebelum `DOCKER_CONTEXT=colima-imfit-online-verify node_modules/.bin/supabase migration up --local` menerapkan **hanya** migration existing `20261029000100_online_only_legacy_recovery.sql`. Kini **14/14 migration dan 21/21 signature/grant RPC** cocok; tidak ada migration/RPC/source baru.
- Metadata read-only PASS: RLS public aktif, tidak ada authenticated direct business writer, hanya UPDATE tiga kolom profil; private receipt/recovery terisolasi. `supabase db advisors --local --type all --level warn --fail-on error` sebelum/sesudah migration: exit 0, results kosong pada threshold warn/error; bukan audit security/performance menyeluruh.
- **35 pemeriksaan smoke PASS**: dua akun sintetis signup+password login, katalog/template, readiness fresh/active, create/edit/complete, Finish, Summary/History/Progress/Home/calendar, read ulang persisten, owner isolation/RLS dan direct-write/anon denial. Reuse `tools/online_ready_support.py::LocalFixtureBackend`/`ResponseLossProxy` lewat smoke sementara di direktori evidence; tidak menambah runner repository atau mengulang concurrency/10k-row/replay suite.
- Satu Finish HTTP benar-benar commit (upstream 200), lalu proxy memutus respons. Discovery + exact retry mengembalikan receipt sama; request ID baru juga tetap workout yang sama. History dan query database memastikan **satu** log untuk session tersebut. Hanya dua akun fixture yang dibuat invocation ini beserta dependensinya dibersihkan; data lama tidak dihapus.
- Evidence lokal di `/private/var/folders/yv/171k2lgd0jdg4y0jclvxjtzw0000gn/T/opencode/imfit-t1-30l7k7al/`: `mvp-smoke-evidence.json` (35 PASS + trace response-loss), `readiness-audit.json`, `migration-versions.json`, `backup-evidence.json`. Backup sukses `local-before-recovery-postgres.dump` (mode 0600; jangan commit/bagikan), TOC terbaca; **restore tidak diuji**. Percobaan backup awal sebagai supabase_admin gagal, backup ulang sebagai postgres sukses; arsip parsial tidak digunakan.

**Sisa:** `local.properties` tetap hosted dan tidak dipakai smoke ini; URL/key lokal serta koneksi perangkat perlu diatur saat integrasi T3, tanpa mencetak credential. T1 membuktikan backend siap, **bukan** APK sudah terhubung. Conflict UI T2.2 dan T2.1 tetap terbuka; tidak lanjut T2/T3, build Android, deployment/cutoff utama, reset/purge, atau penghapusan data legacy pada task ini.

### P0 — Perbaiki dua defect integrasi yang sudah diketahui

- [x] **T2.1** `OnlineRpc.kt`: perbaiki validasi `exercise_id` teks katalog (contoh `ex_back_1`) yang keliru diperlakukan sebagai UUID; uji request import/command yang terdampak.
- [x] **T2.2** `OnlineSessionStore.reload` + `ActiveWorkoutScreen`: setelah conflict dan reload authoritative, opsi **Use server/Reapply** harus bisa diakses tanpa melemahkan lock sebelum verifikasi; uji transisi UI.
- [x] **T2.3** Perbaiki defect tambahan **hanya** jika reproduksibel dan menghalangi alur MVP; selain itu catat sebagai non-blocker. Defect P2 fixture timer dua set ID dapat ditunda sampai pengujian timer terkait.

**Selesai jika:** kedua defect P1 punya test terarah PASS dan UI/transport yang terdampak dapat digunakan.

**Hasil T2 — 29 Oktober 2026 (PASS):** `OnlineRpc` menerima exercise_id string katalog nonblank tanpa melemahkan UUID aggregate; regresi import/command memakai `ex_back_1`. `OnlineSessionStore.reload` yang sukses kini melepas requiresVerification, tetapi failure conflict/draft tetap menahan mutasi sampai explicit Use server/Reapply. Sebelum fix: 34 test terarah, dua FAIL yang mereproduksi defect; sesudah fix: 35 PASS, termasuk reload gagal tetap locked, reapply CAS terbaru dan discard tanpa write. Verifikasi gabungan T2/T3: **51 unit production PASS**, dan kedua aksi conflict dibuktikan melalui UI + dua client HTTP nyata pada T3. Tidak ada defect bisnis tambahan yang memerlukan perluasan scope; selector/tag test stale diperbaiki untuk proof T3. P2 fixture timer dua set tetap deferred.

### P0 — Satukan integrasi UI hingga workout berhasil

- [x] **T3.1** Login/Auth, Catalog, Template Home/editor, Profile serta DI production mengakses backend; tidak mengandalkan fake/demo sebagai bukti production.
- [x] **T3.2** Start hanya setelah preflight menyatakan aman; remote session harus confirmed sebelum layar menganggap workout dimulai. Untuk **fresh install** preflight tidak boleh memblokir tanpa alasan; legacy/unknown tetap fail-closed, **jangan bypass guard**.
- [x] **T3.3** Edit weight/reps, complete set, pause/resume bila tersedia, dan Finish memakai command/ID session remote yang benar; raw draft memori, debounce/flush sebelum aksi bergantung, `Saved` hanya setelah receipt. Jangan implementasi session manager kedua.
- [x] **T3.4** Finish → `receipt.workoutId` → Summary. Kegagalan fetch Summary tidak memicu Finish ulang dengan identitas berbeda. Workout tercatat di History/Progress dan muncul lagi setelah restart.
- [x] **T3.5** Network error/unknown/conflict mempunyai pesan dan aksi retry/resolve yang aman; timer tidak memanggil Supabase per detik; logout mengganti state akun, bukan menghapus data legacy.

**Selesai jika:** seluruh jalur §1 dapat dikerjakan dari layar aplikasi production terhadap backend nyata, bukan hanya fake/unit test.

**Hasil T3 — 29 Oktober 2026 (PASS integrasi preview, bukan seluruh MVP/release PASS):**

- Hanya **Pixel_5_API_34 existing**, serial `emulator-5554`, API 34, user fixture 10; productionDebug memakai flag isolasi existing `-PmodalDiagnostic=true` → `com.diajarkoding.imfit.modaldiagnostic`, tanpa mengganti aplikasi/data IMFIT existing. Guard fixture tetap emulator + non-owner user + akun sintetis + loopback + legacy DB absent; debug hanya diterima melalui opt-in `readyInternalPreview=true` pada package terisolasi tersebut.
- **4/4 instrumentation PASS, nol skip** di `OnlineOnlyReadyTest`: `onlineUiJourney` (login UI → buat rutinitas di Home → picker katalog → Start confirmed → pause/resume → input 12.25 kg × 7 → complete/skip rest → Finish → Summary 85.75 → History/Progress/Profile); `verifyFinishedRestart` (proses dihentikan, login SDK pulih, workout yang sama tetap ada lewat backend dan History UI); `conflictReloadExposesExplicitReviewWithoutUnlockingMutations` (client HTTP kedua mengubah version; sebelum reload aksi review tersembunyi, sesudahnya Use server dan Reapply keduanya bekerja); `lostResponseAndAccountSwitch` (Finish respons hilang → explicit Resolve UI → receipt/Summary, logout/A→B→A tanpa state/data bocor).
- Trace nyata mencatat **dua request Finish untuk dua alur**, satu committed response sengaja diputus; host read mengonfirmasi tepat **dua workout unik**, bukan duplikasi akibat resolve/restart. Guard fresh/legacy unknown, draft/flush/400ms/receipt/unknown, account reset dan **10.000 hot timer peek tanpa request** juga tercakup dalam 51 unit terarah. Ini bukan pengujian lengkap timer perangkat/legacy upgrade atau full regression T5.
- Perubahan runtime tambahan hanya memasang tag Summary pada composable yang benar-benar dirutekan (sebelumnya hanya preview memilikinya). Navigasi, desain, repository/session manager existing, UUID/CAS/RLS/idempotency dan data legacy tidak diubah. Test profil memakai label accessibility aktual, test UI menunggu aksi enabled dan menangani rest popup melalui Skip.
- Build hanya memakai URL/key Supabase lokal sementara; `local.properties` asli dipulihkan **byte-for-byte**. User emulator dikembalikan ke 0, reverse sementara dihapus, dua akun sintetis serta package fixture milik run ini dibersihkan. Tidak ada hosted access/deployment/reset/purge, penghapusan data lama, commit/push atau eksekusi T4/T5.
- Evidence: `/private/var/folders/yv/171k2lgd0jdg4y0jclvxjtzw0000gn/T/opencode/imfit-t2-t3-9c0cylud/`, khususnya `device-dkz3uy5f/result.json` + empat log instrumentation, `targeted-unit-evidence.json`, `sources.json`, dan `t3-build.log`. APK pengujian productionDebug terisolasi di `app/build/outputs/apk/production/debug/app-production-debug.apk`; bukan penyerahan artefak MVP T5.

**Kegagalan/non-blocker dipertahankan:** run host offline awal gagal karena artifact Room runtime transitive WorkManager belum cached; dependency existing di-resolve tanpa perubahan versi (bukan writer bisnis Room IMFIT). Instrumentation minified gagal `NoSuchMethodError mutableIntObjectMapOf` di Compose test; deferred ke gate minified, tidak diklaim PASS. Revisi fixture memperbaiki dua method JUnit non-void dan selector Summary/Profile stale sebelum empat test final PASS. P2 timer dan matriks release lengkap tetap backlog. Selanjutnya T4 bila diminta; tidak kembali ke roadmap release penuh.

### P1 — Bersihkan hanya dependensi lama yang benar-benar menghalangi online-only

- [x] **T4.1** Cari pemakaian Room/DAO/Sync/importer di runtime bisnis baru dan pindahkan caller yang masih aktif ke repository remote yang **sudah ada**.
- [x] **T4.2** Hapus wiring/API obsolete **setelah** semua caller diganti dan build PASS. **Pertahankan database/WAL lama, schema 6/7/9, legacy reader dan jalur review**; jangan destructive migration/clear-data untuk membuat test hijau.
- [x] **T4.3** Pastikan Home/Progress/Profile memakai remote summary yang relevan, bukan full history pull; pertahankan navigasi existing, jangan menambah feed atau layar baru.

**Selesai jika:** tidak ada jalur produksi yang membuat atau menyimpan data bisnis baru lewat Room/sync queue; fallback offline tidak dibuat lagi.

**Hasil T4 — 29 Oktober 2026 (PASS verifikasi cutover existing; tidak perlu penghapusan source baru):**

- Pencarian caller/DI terarah tidak menemukan Room/DAO, writer, importer/coordinator/scheduler sync lama atau fake datasource terikat pada production. `AppModule` → `OnlineWorkoutRepository`/`AuthRepositoryImpl`; `RemoteReadModule` → ProductionReadGateway/OnlineRepository. Cutover sudah ada pada checkpoint, sehingga tidak diulang atau direfactor hanya untuk membuat diff.
- `data/sync/SyncWorker.kt` dipertahankan: terminal no-op tanpa network/DAO untuk menutup request lama. `LegacyRecoveryModule` tetap memakai `AndroidLegacyInventoryReader` dengan `SQLiteDatabase.OPEN_READONLY`; reader/review/checkpoint transisi bukan queue workout baru. Schema 6/7/9 terverifikasi tetap utuh; tidak mengakses/mengubah database/WAL pengguna.
- `dependencyInsight` pada `productionDebugRuntimeClasspath` membuktikan **Room 2.7.0 transitif dari WorkManager 2.11.2/Hilt Work**, yang masih dipakai `RestTimerRecoveryWorker`/scheduler. Ini storage operasional library, bukan Room bisnis IMFIT; tidak di-exclude/dihapus agar recovery timer tidak rusak. Dependency langsung Room/writer bisnis memang sudah tidak ada.
- Home memakai templateFeed + `get_home_summary`/profile/session indication; Progress memakai `get_progress_summary` dan date markers per periode/hari; Profile hanya dedicated profile state. Tidak mengambil seluruh history untuk menghitung summary, tidak menambah route atau fallback offline.
- **37 unit production PASS, nol failure/error/skip**: G3RemoteReadTest (13), OnlineMutationInvalidationTest (5), LegacySqlInventoryTest (17), LegacyIndependentProfileTest (2). Command terarah `:app:dependencyInsight --dependency androidx.room --configuration productionDebugRuntimeClasspath :app:testProductionDebugUnitTest --tests <empat kelas tersebut> --offline` exit 0; compile/Hilt production juga PASS. Tidak menjalankan full regression, emulator atau backend lagi.
- Source/dependency/API tidak berubah pada T4; fingerprint dan SHA256 APK T3 masih cocok dengan evidence build/UI PASS sebelumnya, jadi tidak membuat ulang APK hanya untuk pencatatan. Bukti T4 di `/private/var/folders/yv/171k2lgd0jdg4y0jclvxjtzw0000gn/T/opencode/imfit-t4-dxycpxfr/`: `t4-verification.log`, `targeted-tests.json`, `preservation-evidence.json`. Saat milestone T4 hanya tasks.md dan ringkasan Report diperbarui; kelanjutan T5 dicatat di bawah.

### P0 — Buktikan MVP dan hasilkan APK

- [x] **T5.1** Jalankan test **terarah** untuk source yang diubah: unit/controller/DTO/SQL terkait. Jalankan full regression **sekali** di source final yang dibekukan, bukan tiap edit.
- [x] **T5.2** Jalankan **satu smoke test production flavor dengan UI + HTTP lokal nyata**: login → template → start → input → complete → finish → summary → history/progress → restart. Tambahkan cek akun lain dan **satu** skenario offline/unknown yang kritis.
- [x] **T5.3** Build APK internal (minimal `assembleProductionDebug`); demo/normal release/minified/instrumentation tambahan hanya bila perlu untuk defect atau gate distribusi yang sedang diuji. Catat test gagal atau tidak dijalankan secara jujur.
- [x] **T5.4** Laporkan lokasi APK aktual, konfigurasi backend uji yang dibutuhkan, ringkasan PASS/FAIL dan keterbatasan. APK yang mengarah ke Supabase lokal **tidak otomatis dapat digunakan klien dari jaringan luar**.

**Selesai jika:** seluruh checklist MVP §1 lulus, APK tersedia dan dapat dipasang pada lingkungan uji yang disebutkan. Jika tidak, status **BLOCKED** beserta blocker minimal, bukan “release-ready”.

**Hasil T5 — 29 Oktober 2026 (MVP internal preview PASS):**

- Source final dibekukan pada working tree HEAD `5372f21` + perbaikan T2/T3; tidak ada edit source/dependency selama T5. Satu full host regression `:app:testDemoDebugUnitTest :app:testProductionDebugUnitTest --offline`: **417 production + 424 demo PASS**, nol failure/error/skip. SQL/backend tidak berubah sejak T1; tidak mengulang suite concurrency/10k-row/replay atau migration.
- Build `-PmodalDiagnostic=true :app:assembleProductionDebug :app:assembleProductionDebugAndroidTest --offline` PASS. **3/3 smoke final PASS**, nol skip, hanya Pixel_5_API_34 existing (`emulator-5554`, API34, user fixture10): `onlineUiJourney`, `verifyFinishedRestart`, `lostResponseAndAccountSwitch`. UI mencakup login, create routine/catalog picker, guarded Start, pause/resume, input/complete, Finish → Summary/History/Progress/Profile; hasil yang sama tetap ada setelah proses dihentikan. Response-loss setelah commit di-resolve lewat UI, A→B→A terisolasi; tepat dua request Finish dan dua workout unik. Guard/conflict tambahan tetap dibuktikan T2/T3.
- APK yang diserahkan: [imfit-online-only-internal-preview-20261029.apk](../../app/build/outputs/apk/internal-preview/imfit-online-only-internal-preview-20261029.apk), **22.911.365 bytes**, productionDebug, package **`com.diajarkoding.imfit.modaldiagnostic`**, signing debug/internal. Ini APK aplikasi, bukan androidTest; package terisolasi tidak mengganti IMFIT existing. SHA256 **`56fda795603d4edc9f63c52a98e7235b07606cbacea2b4b1611420af3a006af4`**; sidecar `.apk.sha256` tersedia. Signature diverifikasi; byte/hash identik dengan APK yang berhasil dipasang dan diuji pada smoke final.
- Evidence final di `/private/var/folders/yv/171k2lgd0jdg4y0jclvxjtzw0000gn/T/opencode/imfit-t5-xa5ue294/`: `artifact-manifest.json`, `sources-before.json`, `build-results.json`, `full-regression-results.json`, `full-regression.log`, `apk-build.log`, `device/result.json`, tiga log instrumentation dan screenshot Summary. Bukti working tree lokal, **bukan** clean commit/CI atau publish signing.
- **Konfigurasi pemakaian:** backend existing Supabase lokal project `IMFIT-MOBILE`, Docker context `colima-imfit-online-verify`, 14 migration/21 RPC, API baked-in `http://127.0.0.1:54321`. Untuk Pixel 5 tersebut, jalankan `adb -s emulator-5554 reverse tcp:54321 tcp:54321` saat mencoba APK; gunakan/buat akun uji lokal, bukan akun utama. Akun fixture smoke sudah dibersihkan. `local.properties` asli dipulihkan byte-for-byte; **build ulang wajib memakai URL/key lokal**, jangan menganggap konfigurasi hosted existing sebagai config preview.
- **Batas:** hanya fresh install/internal preview pada lingkungan lokal yang disebutkan. Instrumentation minified T3 masih FAIL/deferred (`NoSuchMethodError` Compose test); tidak diulang/diklaim PASS. Upgrade/restore legacy, seluruh matriks timer/perangkat/API/accessibility, publish signing dan deployment/koneksi klien luar tetap deferred. Tidak ada blocker MVP tersisa dalam scope ini. Untuk klien di luar lingkungan uji perlu deployment backend dan verifikasi koneksi terpisah setelah izin eksplisit; Supabase utama/cutoff/penghapusan data lama tidak dilakukan.
- Cleanup hanya dua akun/package fixture baru; user emulator kembali 0 dan reverse sementara dihapus. Data/aplikasi existing, database/WAL dan schema 6/7/9 tidak dihapus. **Berhenti pada penyerahan APK dan backlog deferred; tidak membuka roadmap release penuh atau commit/push otomatis.**

## 4. Aturan kerja agent (anti-scope-creep)

1. **Satu task aktif → ubah kode minimal → test terkait → checklist → lanjut.** Kerjakan perubahan yang bisa dibuktikan menghasilkan kemajuan alur MVP.
2. **Reuse first:** jangan menambah RPC, migration, repository, abstraction, framework paging/session, runner test, atau dokumen bila yang existing cukup. Perubahan schema hanya untuk gap nyata, additive bila mungkin.
3. **Dilarang mengulang audit menyeluruh**, membuat laporan baru, menguji semua varian berkali-kali, atau memperluas acceptance criteria tanpa blocker nyata/izin pengguna.
4. **Test proporsional:** perbaikan UI → test UI/VM terkait; perubahan SQL/RLS → test SQL/Auth/HTTP terkait; perubahan finish → receipt/idempotency. Full regression satu kali pada kandidat final atau setelah perbaikan risiko besar.
5. **Tidak boleh menghapus safety dasar:** RLS, owner isolation, CAS, request ID/idempotency, finish atomik, unknown≠success, timer cold-offline verification, dan pemeliharaan data legacy. Ini bukan fitur opsional.
6. **Tidak ada operasi hosted/destruktif tanpa izin terpisah:** `db push`, migration ke Supabase utama, compatibility cutoff, reset/purge, penghapusan data pengguna, dan release publik. Commit/push hanya sesuai otorisasi eksplisit yang benar-benar berlaku; jangan menganggap izin lama otomatis untuk scope baru.
7. **Bila blocker:** tulis penyebab, dampak MVP, opsi perbaikan terkecil. Minta keputusan pengguna **hanya** jika membutuhkan perubahan scope, risiko data/hosted, atau pilihan produk yang belum diputuskan. Lanjutkan task independen yang aman.
8. **Dokumentasi hemat token:** update checklist `tasks.md` per task; Implementation Report hanya satu ringkasan milestone (bukti, status, blocker). Feature Spec/Plan diubah **hanya** jika kontrak/kebutuhan berubah. Jangan menyalin log panjang atau fingerprint berulang.
9. **Wajib berhenti menambah scope** saat MVP lulus. Serahkan APK dan backlog deferred; jangan membuka fase recovery, seluruh matriks produksi, atau optimasi kecuali pengguna meminta.

## 5. Format laporan agent (wajib singkat)

Setelah satu task selesai, laporkan maksimal lima baris:

```text
Task: Tn.n — PASS / FAIL / BLOCKED
Perubahan: <file/fitur inti>
Bukti: <tes relevan atau skenario UI + hasil>
Blocker: <tidak ada / satu blocker spesifik>
Berikutnya: <ID task berikutnya>
```

**Definition of Done keseluruhan:** **MVP internal preview PASS dan APK diserahkan**. **Production release/upgrade legacy tetap gerbang terpisah** dengan keputusan dan pengujian sendiri; jangan dilabeli selesai oleh MVP.
