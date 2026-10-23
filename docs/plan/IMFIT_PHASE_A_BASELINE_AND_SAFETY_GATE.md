# IMFIT — Phase A: Baseline & Safety Decision Closure

**Tanggal:** 21 Oktober 2026. **Mode:** READ-ONLY / PRE-BUILD GATE, dengan satu keluaran dokumentasi ini.
**Baseline yang sudah direview:** [Plan v2](IMFIT_REFRESH_PAGING_SHIMMER_SYNC_PLAN_v2.md) dan [Addendum v2.1](IMFIT_REFRESH_PAGING_SHIMMER_SYNC_PLAN_v2.1.md). Tidak membuat plan v2.2 atau mengulang audit enam layar.
**Persetujuan saat ini:** rencana sebagai baseline engineering dan penyusunan laporan Phase A. **Bukan** izin implementasi Phase B, Full Build, migrasi, test source, atau kebijakan logout/retention final.

## 1. Verdict dan status bukti

**Phase A report selesai untuk review; Phase B entry masih BLOCKED.** Keputusan implementasi D2/D3/D6/D8 dipersempit di bawah, tetapi tidak dinyatakan telah disetujui/dibuktikan.

- **VERIFIED:** inspeksi source terbatas dan fingerprint working tree pada sesi ini; bukan keberhasilan runtime.
- **PROPOSED:** batas implementasi, kebijakan, test, atau helper yang belum dibuat.
- **BLOCKED:** memerlukan keputusan/otorisasi/proof sebelum boundary terkait boleh diubah atau dinyatakan aman.
- **NOT_TESTED:** build, unit/concurrency/Room/Compose test, backup/restore perangkat, process-death aktual, SDK sign-out offline, dan Supabase E2E tidak dijalankan. Ketiadaan verifikasi ini tidak diubah menjadi PASS.

Temuan penguat dibanding baseline: backup aplikasi diaktifkan tanpa exclusion aktif pada aturan yang dibaca; sign-out-retain perlu kebijakan privacy/storage/backup, bukan UI hiding saja. Jalur template UPDATE tidak membedakan rename dari child replacement sehingga rename-only tidak otomatis aman untuk dilepas. Cache berisi data dan coverage unknown harus tetap dapat ditampilkan pada owner yang sah.

## 2. Baseline verification dan preservation evidence

### 2.1 Working tree yang harus dipertahankan

- **VERIFIED:** branch `fix-bug`, HEAD `37bfdf9d817d60a8b9fb2e6e2cae1478c5ffca8d`.
- Sebelum report dibuat: **32 tracked files modified**, **12 untracked files**, termasuk perubahan enam issue, schema export7, tests/report existing, serta dua plan. Working tree tidak bersih dan **tidak dinormalisasi ke HEAD**.
- Fingerprint **474 file** dari gabungan `git ls-files --cached --others --exclude-standard -z`, sorted/deduplicated, regular-file contents, mengecualikan hanya report Phase A ini:
  - SHA-256 aggregate: `da851411fd911ad35f789ff6c56874fbf1541835aec1609da6fadfb8e6e0f2ad`.
  - SHA-256 output `git diff --binary HEAD`: `353f8a785b095753302fbda806f56436da3bfe14d0bd45c239cb4036d011fd42`.
- Algoritme aggregate: untuk tiap path sorted, append UTF-8 path + byte NUL + raw SHA-256 digest konten; hash gabungannya. Ini fingerprint **source working tree**, bukan hash installed APK/database, dan tidak mencakup ignored build artifacts/perangkat.
- **VERIFIED saat finalisasi:** kedua fingerprint pre/post identik; 474 file baseline tidak berubah. Perubahan pada tugas ini hanya penambahan file report ini. Baseline v2/v2.1 tidak diubah lagi.

**VERIFIED — metode:** `git status --short --branch`, `git rev-parse HEAD`, daftar tracked/untracked, content reads, grep terbatas, fingerprint Python read-only. Tidak ada Gradle/adb/Supabase tool, install, atau manipulasi git index. Daftar lengkap dirty files ada pada §10 agar implementasi berikutnya tidak mengira perubahan itu berasal dari Phase B.

### 2.2 Source evidence yang diverifikasi ulang

Singkatan path: **M/** = `app/src/main/java/com/diajarkoding/imfit/`; **P/** = `app/src/production/java/com/diajarkoding/imfit/`; **D/** = `app/src/demo/java/com/diajarkoding/imfit/`; **UT/** = `app/src/test/java/com/diajarkoding/imfit/`; **IT/** = `app/src/androidTestDemo/java/com/diajarkoding/imfit/`. Nomor baris snapshot saat inspeksi, bukan API permanen.

| Bukti | VERIFIED source/function | Existing behavior dan batas yang belum terbukti |
| --- | --- | --- |
| E01 | `M/data/repository/AuthRepositoryImpl.kt:198–282`, `register:123–125` | Login/register cache user, unblock, enqueue; logout stop timer → cancel worker → drain sync → deleteUserData → clear preferences/cache → SDK signOut. Purge terjadi **sebelum** signOut, tidak ada pemeriksaan pending/persetujuan retention |
| E02 | `M/data/local/database/IMFITDatabase.kt:26–58` | Room7; deleteUserData transaksi menghapus set/log/template/active-session/user milik akun, tidak mengecualikan unsynced rows |
| E03 | `M/data/repository/AuthRepositoryImpl.kt:285–347`; `M/data/local/dao/UserDao.kt:9–18` | getLocalUserId menunggu SDK init bila ID belum loaded; observeCurrentUser menangkap satu userId lalu observe Room, bukan switch identity flow; cacheUser check terpisah dari insert. UserDao juga mempunyai getAllUsers, bukan hanya query owner |
| E04 | `P/data/auth/SupabaseAuthDeepLinkHandler.kt:18–25`; `P/di/SupabaseModule.kt:27–38` | SDK deep-link callback dapat autentikasi lalu unblock/enqueue; Auth PKCE dan lifecycle callbacks=false untuk kontrak timer. Tidak ada session epoch fence bersama yang tampak di jalur ini. Storage/token persistence internal SDK dan ordering event belum diverifikasi runtime |
| E05 | `app/src/main/AndroidManifest.xml:25–35`, `app/src/main/res/xml/backup_rules.xml:8–13`, `data_extraction_rules.xml:6–19`, production manifest:1–20 | allowBackup=true; tidak ada include/exclude aktif pada rules, device-transfer rules hanya komentar; production manifest tidak override backup. Ini konfigurasi source, **bukan** bukti konten backup/transfer aktual atau hasil merged-manifest/runtime |
| E06 | `P/di/DatabaseModule.kt:19–32`; `app/src/main/res/xml/file_paths.xml:2–11`; main manifest:46–54 | Room builder biasa, tidak memasang encryption factory di module ini. FileProvider non-exported dengan URI grants, paths files/cache/external-files luas; database directory tidak secara eksplisit dibagikan di file_paths. Bukan bukti database terenkripsi atau kebocoran aktual; grant caller/SDK storage perlu verifikasi bila retention disetujui |
| E07 | `M/data/sync/SyncManager.kt:69–97,121–166` | Reconnect collectLatest di application scope; syncMutex dan masterMutex terpisah; explicit logout blockedUsers+drain. Mutex bukan shared-flight result; full sync bisa bersamaan catalog lane |
| E08 | `SyncManager.kt:181–221,245–304` | Catalog/profile read independent, kemudian upload templates/workouts; jika salah satu pending gagal, template/history pull tidak berlanjut. Initial sync mengulang catalog/profile; cancellation tidak punya satu finalization contract universal |
| E09 | `SyncManager.kt:321–363,618–660` | CREATE: upsert parent → delete-all children → inserts; UPDATE: update name/timestamp → delete-all children → inserts; DELETE: update is_deleted=true saja. Ack timestamp/op transaction; retry failure mempertahankan operation, status bisa SYNC_FAILED |
| E10 | `SyncManager.kt:392–420`; `M/data/remote/ImfitAggregateRemoteDataSource.kt:24–32,53–79` | Workout parent berasal scan, children dibaca terpisah sebelum RPC; ack memakai parent timestamp/op lalu clear child flags. RPC result serverUpdatedAt tersedia tetapi tidak dipakai sebagai stored revision pada jalur ack ini |
| E11 | `M/data/local/dao/WorkoutTemplateDao.kt:46–66`, `WorkoutLogDao.kt:61–83`; `M/data/repository/WorkoutRepositoryImpl.kt:227–320` | Queue dipilih pending_operation non-null; CAS timestamp+op; mutations routine memberi wall-clock updatedAt dan parent dirty dalam transaksi. Same-ms/ABA dan seluruh child-only mutator invariant belum dibuktikan |
| E12 | `SyncManager.kt:429–488,493–592,610–614` | Pages offset500, short page dianggap EOF; catalog missing IDs dinonaktifkan dalam transaksi sesudah downloads. Parent template/history di-recheck setelah children; identity dicek sebelum importer/Room transaction, bukan fenced sampai commit |
| E13 | `M/data/sync/RemoteSnapshotImporter.kt:10–48` | Child graph/duplicate validation, pending/newer-local parent guard, child replacement per aggregate atomic. Tidak menerima epoch/session fence; completeness/coherence server tidak dapat dibuktikan oleh transaction lokal |
| E14 | `M/data/sync/SyncRunner.kt:3–13`, `SyncWorker.kt:17–23`; `P/data/sync/WorkManagerSyncScheduler.kt:21–45` | Tiga outcome; Worker retries RETRYABLE_FAILURE, success untuk no-auth; CONNECTED, exponential10s, unique per user APPEND_OR_REPLACE. Tidak ada reason blocked-protocol terpisah; mengganti ke KEEP tanpa handshake berisiko lost wakeup |
| E15 | `M/presentation/navigation/RemoteRefreshViewModel.kt:24–35`; `M/data/repository/ExerciseRepositoryImpl.kt:25–39` | Cooldown VM per user attempt dan catalog repository success-only terpisah; force/failure/jalur lain bukan satu registry result |
| E16 | `M/presentation/components/common/SyncProgressDialog.kt:43–54`; `M/presentation/ui/main/MainScreen.kt:202–215`; `M/data/sync/SyncState.kt:7–23` | Overlay berdasarkan isInitialSync global; progress load key memakai lastSyncTime. SyncState tidak membawa owner/epoch, cache availability, completeness, atau auth initialization |
| E17 | `M/presentation/navigation/NavGraph.kt:90–112`; `M/presentation/ui/splash/SplashViewModel.kt:13–22`, `SplashScreen.kt:144–152` | Success/splash callbacks menuju Main; Splash membaca isLoggedIn sesudah delay. Bukan per-dataset content gate; tidak membuktikan auth restored/stale navigation aman untuk retained accounts |
| E18 | `M/presentation/ui/home/HomeViewModel.kt:55–98`; `M/presentation/ui/progress/ProgressViewModel.kt:47–90` | Loader restart set isLoading=true; signed avatar sebelum workout observer; profile/history readiness belum independen. Cache data mungkin masih di StateFlow tetapi renderer dapat menyembunyikannya |
| E19 | `M/data/sync/SyncPreferences.kt:21–24,26–59,66–83` | DataStore imfit_sync, legacy SharedPreferences migration, per-user timestamps/initial-completed; terpisah dari Room transaction. Bukan atomic coverage record |

**BLOCKED claims:** semua full-safe-sync/account-fence/backup-privacy claims tetap membutuhkan proof. Komentar source “atomic sync”/“complete snapshot” tidak mengubah multi-request server behavior menjadi transaction server.

## 3. Decision records — D2 / D3 / D6 / D8

Setiap record berikut **PROPOSED / awaiting approval**. Rencana engineering direview/disetujui tidak sama dengan menyetujui retention, degradation sinkronisasi, atau patch aplikasi.

### DDR-D2 — Pending-safe logout, authentication termination, dan privacy

**Existing / VERIFIED:** E01–E06. Logout menghapus pending dan active session sebelum SDK signOut; signature `AuthRepository.logout(): Unit` tidak mengembalikan pilihan/outcome. `ProgressViewModel.logout()` langsung memanggil callback setelah return. Lifecycle auth juga masuk lewat register/login/deep-link/restore, bukan logout saja.

**Risiko P0:** pending offline hilang; kegagalan/cancellation SDK signOut setelah purge dapat meninggalkan user masih authenticated dengan cache hilang. Retain-without-purge sebaliknya meningkatkan risiko privasi storage/backup dan stale Room/session restoration; UI hiding bukan encryption. Tidak ada klaim failure path ini sudah terjadi pada perangkat.

**Proposed boundary:** pecah operasi menjadi prepareLogout, resolved user choice, terminate authentication, dan optional purge; nama ini kontrak konseptual, belum API existing. Transition menutup penerimaan local writes akun lama, invalidate epoch/publication, dan membuat pending inventory snapshot sebelum destructive step. Jangan memakai pendingCount UI sebagai otoritas. Preserve completed records/pending flags; active-session dan timer cleanup tetap kontrak existing, tidak diubah untuk menyelesaikan retention.

| Kondisi | Rancangan user-visible | Guard / batas |
| --- | --- | --- |
| Online, pending | Informasi data belum tersinkron + **Coba sinkronkan**, **Batal**; opsi **Keluar tanpa menghapus data** hanya jika policy retention disetujui | Sync retry bounded/cancellable; partial/blocked-protocol bukan permission purge; jangan endless spinner |
| Offline, pending / protokol template diblokir | Informasi “data masih di perangkat, belum di server”; Batal + jalur offline sign-out-retain yang perlu approval | Jangan mensyaratkan online selamanya. Tanpa policy privacy final, jalur ini belum boleh diimplementasikan atau dinyatakan aman |
| Tidak ada pending durable rows | Auth termination dan owner cleanup sesuai policy yang disetujui | Pending check versus concurrent local write harus linearizable. Active workout/draft bukan otomatis “tidak ada data” karena pending0 |
| Sign-out-retain disetujui | Sesi autentikasi berakhir, data retained tak dapat dibuka dari UI/API akun lain; login ulang owner yang sama dapat melanjutkan sync | Drain/fence owner lama, clear per-user display/pager/selection/signed URLs, no worker authA usingB. Tidak auto-store credential tambahan untuk retry |
| SDK signOut gagal/timeout/offline behavior belum diketahui | Outcome jujur; jangan klaim signed-out sukses atau purge pending sebagai kompensasi | Periksa SDK local-vs-global sign-out behavior dan SDK persistence terlebih dahulu; jangan menebak revocation berhasil offline |

**Privacy policy yang harus diputuskan:**

1. Retained pending disimpan hanya dengan pilihan pengguna yang jelas, owner-scoped, tidak auto-purge/auto-retain tanpa policy. Tentukan trusted restore/re-login dan perangkat bersama.
2. Usulkan backup exclusions untuk Room DB (termasuk companion files), coverage/blocked metadata, serta auth/session storage cloud **dan** transfer sebelum mengklaim sign-out-retain private. Lokasi storage SDK harus ditemukan saat implementasi; jangan menebak nama file. Perubahan manifest/rules memerlukan approval scope privacy tersendiri, bukan sekadar overlay patch.
3. Room sandbox bukan bukti encryption. Jika threat model menuntut encryption/key-per-account, STOP untuk desain dependency/key storage/migration; tidak menambahkan SQLCipher/Keystore diam-diam.
4. Retention duration, akun terhapus/tidak dapat login, user-visible data removal/export, dan handling backup lama belum disepakati. Menghapus pending karena TTL tanpa informed policy melanggar no-silent-deletion.
5. FileProvider grant surface perlu dicek jika retained sensitive files berada di files/cache; non-exported tidak berarti granted URI aman untuk semua path. Tidak ada bukti database dibagikan sekarang.

**Alternatif/trade-off:** upload-before-logout saja sederhana tetapi memenjarakan pengguna offline atau pending-template-blocked; purge+discard confirmation adalah kehilangan data eksplisit dan bukan zero-loss solution; sign-out-retain memberi jalan offline tetapi memerlukan privacy controls di atas. Rekomendasi tetap **explicit retained logout dengan privacy gate**, bukan keputusan final.

**Schema/backend dependency:** pemisahan auth/purge dapat memakai owner fields existing, tetapi owner validation pada by-ID repository wajib. Tidak mengubah RLS. Encryption/revision metadata yang butuh schema harus diajukan terpisah. Android backup-policy patch bukan migrasi Room tetapi tetap perubahan behavior yang perlu approval.

**Rollback:** jangan mengembalikan automatic purge sesudah user memilih retention; simpan pending dan fail-safe block destructive cleanup. Data yang sudah terhapus tidak dapat dipulihkan hanya dengan revert kode. Revert UI dapat mempertahankan typed safety outcome.

**Test evidence:** B03/B04/B06/B11/B12 (§5), termasuk A→B→A, signOut exception/cancel, owner-fenced by-ID access, archive XML assertions dan device backup/restore yang kelak berizin. XML source tests tidak membuktikan cloud backup aktual aman.

**Approval outstanding:** offline retained-sign-out UX; retention/remove policy; backup exclusions/old-backup consequences; active-session exit behavior unchanged atau review lifecycle terpisah; SDK local-auth termination boundary.

### DDR-D3 — Auth, cache availability, dan data initialization dipisahkan

**Existing / VERIFIED:** E16–E18. Overlay isInitialSync tidak mempunyai owner/epoch; cache dan initialized state tidak ikut predicate. Auth callbacks mengatur graph, bukan data completeness.

**Risiko P1/P0:** menonaktifkan overlay tanpa account gate bisa memperlihatkan state akun lama, atau menganggap Room empty sebagai hasil final saat hydrate belum selesai. Menunggu global initial-completed walau cache valid membuat offline UX macet/flicker. Overlay bukan mekanisme otorisasi.

**Proposed boundary:** jangan ubah Navigation3 route/result graph. Owner-bound readiness diperiksa pada repository/ViewModel publication dan target content; gating overlay hanya presentation setelah auth context sah. Tetap enam target, jangan mengubah global dialog/scrim/navigation. Pisahkan tiga dimensi berikut:

| Auth context | Cache/query owner | Init/coverage | Penyajian yang diusulkan |
| --- | --- | --- | --- |
| Initializing / switching / signed-out | Ada cacheA | Apa pun | Tidak publikasikan data sensitifA. Tampilkan shell/auth-resolution sesuai alur existing; jangan fallback Guest dengan daftarA |
| AuthenticatedA dari SDK lokal yang sudah resolved | Ada cacheA valid | Unknown atau in-progress | **Tampilkan cacheA langsung**, status “belum diverifikasi lengkap/belum tersinkron” bila relevan; jangan skeleton ulang |
| AuthenticatedA | Cache hanyaB | Apa pun | Anggap no usable cacheA; B tidak boleh muncul sesaat pun |
| AuthenticatedA | QueryA belum selesai | Apa pun | Section loading setelah delay; statics tetap, unknown bukan0/empty |
| AuthenticatedA | QueryA kosong | Belum coverage valid + online hydrate | Unknown/in-progress, bukan empty sah; akhiri dengan unavailable/error bila hydrate gagal |
| AuthenticatedA | QueryA kosong | Verified complete matching owner/context | Empty/0 yang sah; remote refresh tidak mengulang skeleton |
| AuthenticatedA | No usable cacheA | Offline / failed / snapshot-unverified | Offline/data-unavailable + retry; tidak endless skeleton atau fake records |

**Alternatif/trade-off:** retain full-shell overlay lebih sederhana tetapi menutup cache; unconditional disable lebih ringan tetapi tidak mengatasi race. Rekomendasi owner-aware content gate + scoped presentation eligibility, bukan network auth requirement: offline SDK-restored identity yang sah tidak harus memanggil server dulu untuk membaca cache miliknya.

**Dependencies:** session epoch/commit+publication fence Phase B; typed outcomes dan D8 coverage. Jika helper memerlukan graph remap atau perubahan timer auth lifecycle callbacks, STOP; `enableLifecycleCallbacks=false` dilindungi acceptance timer existing.

**Rollback:** renderer eligibility boleh dikembalikan secara terarah tanpa melepas owner gate. Jangan rollback account isolation demi mengembalikan overlay lama.

**Test evidence:** B03/B04/B07/B10/B13 serta Compose tests fase C/F: signed-out restoration, valid-cache unknown-coverage, wrong-owner cache, failed hydrate, header8/nav/footer dan dialogs unchanged. Runtime seluruhnya NOT_TESTED.

**Approval outstanding:** minimal gating change saja setelah safety gate, no-cache copy, dan kontrak auth-initializing presentation tanpa redesign navigation.

### DDR-D6 — Exact template-write denylist dan degraded synchronization

**Existing / VERIFIED:** E08–E13; `docs/backend/imfit-supabase-compose-integration.md:105–107` memperingatkan template RPC belum dapat menggantikan replacement karena child UUID/pruning. Room child key `(template_id, exercise_id)`; enum pending hanya CREATE/UPDATE/DELETE.

**Risiko P0:** CREATE/UPDATE dapat menghapus semua remote children lalu gagal insert; reader lain bisa mengimpor graph parsial dengan parent yang terlihat stabil. Rename-only tetap menuju replacement existing. Request retry tidak menjadikan operasi multi-request atomic. Parent scan terpisah dari child read juga dapat menghasilkan upload mixed-revision.

**Proposed exact dispatch policy, sebelum request pertama:**

| Operation / path existing | Baseline endpoint sequence | Rekomendasi default Phase B | Jalur non-destructive / batas |
| --- | --- | --- | --- |
| Pending CREATE, children nonempty | parent UPSERT → children DELETE → N INSERT | **Tahan seluruh aggregate**; parent request count0 | Tidak melepas parent-only creation lalu menandai rutin lengkap. Insert-only baru bukan existing path; collision/retry/pruning protocol belum disetujui |
| Pending CREATE, children empty | parent UPSERT → children DELETE (tetap dipanggil) | **Tahan seluruh aggregate** | Empty array tidak membuktikan remote children kosong; retry/stable ID collision membuat delete tetap berisiko |
| Pending UPDATE, reorder/add/remove/config | parent UPDATE → children DELETE → N INSERT | **Tahan seluruh aggregate** | Jangan update name/timestamp dahulu lalu menahan child stage |
| Pending UPDATE, rename-only | Jalur sama dengan UPDATE lainnya | **Tahan default** | No persisted trusted last-synced child fingerprint/operation subtype existing. Parent-only rename dapat menjadi extension hanya setelah proof classifier dan approval, tidak diasumsikan aman dari label UPDATE |
| Pending DELETE | Single parent UPDATE is_deleted=true; tidak physical child DELETE | **Tunda default sampai approval khusus tombstone propagation** | Berbeda dari non-atomic child replacement, tetapi secara semantik destructive. Boleh dipertimbangkan allowlist terpisah sesudah owner/intent/snapshot/ack proof; tidak membuka CREATE/UPDATE |
| Unknown/null operation inconsistent | Tidak punya dispatch contract yang lengkap | Jangan kirim atau clear flags; validation/blocked reason | Bukan implicit success |
| `upsert_template_aggregate` | Wrapper tersedia, jalur pending existing tidak memakainya | **Tidak digunakan sebagai shortcut** | Child identity/removal protocol perlu backend/schema design terpisah |

**Kemampuan yang sementara terblokir:** dengan default ini **upload rutinitas tidak berfungsi untuk operasi yang ditahan**, bukan “template sync sudah diperbaiki”. Local create/edit/soft-delete dan pending persistence tetap berfungsi; perangkat lain tidak langsung menerima perubahan. User-visible status harus menyebut “rutinitas tersimpan di perangkat; sinkronisasi rutinitas ditunda karena proteksi data”. Copy final memerlukan review ID/EN saat UI diimplementasikan.

**Yang masih dapat berjalan secara independen, setelah safety gate:**

- Local reads/mutations, cached History/Progress, dan durable pending storage.
- Catalog/profile remote reads yang owner/epoch/record validation sah; no missing-record deactivation atau profile dirty overwrite dari unverified snapshot.
- Existing workout aggregate RPC dengan snapshot capture/ack/ownership yang diperkuat, tanpa mengubah payload completed-set/volume. Jika remote template reference belum tersedia karena rutinitas ditunda, outcome dependency-blocked harus akurat; jangan retry storm atau menghapus workout pending.
- Read remote template/history hanya jika verification boundary dapat dipenuhi; read-only network request bukan izin melakukan Room replacement. Jika coherence/completeness tidak terbukti, cache dipertahankan dan remote hydration dilaporkan unavailable/unverified, termasuk kemungkinan perangkat baru belum bisa menampilkan data remote lengkap.

**Remote snapshot policy:** failure page3/short-page-cap/duplicate/parent-version ambiguity tidak boleh menjadi full replacement. Atomic Room boundary per aggregate dipertahankan; aggregate terdahulu yang valid boleh committed tetapi outcome seluruh dataset partial. Absence parent tanpa tombstone bukan alasan hard-delete. Backend count atau dua scan sama bukan proof isolated snapshot.

**Durability dan anti-loop:**

1. Jangan clear pending_operation atau mark SYNCED ketika blocked. Pertahankan PENDING_SYNC/operation existing; blocked reason/capability disajikan terpisah dan dapat **direkonstruksi dari durable row + policy** setelah process restart, tanpa tabel baru otomatis.
2. Pisahkan result blocked-protocol dari retryable transport. Worker untuk blocked-only demand berhenti tanpa Result.retry loop; penghentian Worker **tidak** berarti semua tersinkron. Kalau dataset lain transient-failed, retry hanya eligible work, bukan menjalankan template denylist lagi.
3. Automatic reconnect/foreground/manual refresh boleh melaporkan blocked tetapi template endpoint counter harus tetap0. Tidak membuat infinite chain workers karena blocked count>0. User edit baru dapat menjadwalkan worker, namun dispatch guard tetap skip safely.
4. Resume demand saat capability/protocol diperbaiki lewat change set yang disetujui atau user retry eligible work. Login owner yang sama melakukan pending re-scan. Penundaan blocked tidak boleh menghilangkan durable wakeup untuk operation lain.
5. Current full-sync all-or-nothing Boolean barrier (E08) perlu outcomes per dataset agar template blocked tidak menahan catalog/history/workout yang eligible; tetap jangan menyatakan all-synced selama pending ada.

**Alternatif/trade-off:** keep replacement + retry tetap berisiko; direct existing RPC melanggar warning protocol; pause all sync terlalu luas; narrow guarded staging mempertahankan fitur yang aman tetapi menurunkan freshness/multidevice functionality. Rekomendasi default denial di atas perlu persetujuan eksplisit.

**Dependencies schema/backend:** guard dan typed blocked outcome dapat dirancang tanpa schema bump; true atomic template replacement memerlukan desain remote child identity/pruning dan server concurrency protocol, terpisah. Jangan menciptakan metadata revision terselubung melalui enum/status yang tidak kompatibel.

**Rollback:** guard tidak boleh dicabut otomatis ketika gagal benchmark/UI regression. Selama protocol unsafe, fail closed dengan pending utuh. Jangan “rollback” dengan mengirim ulang child-delete legacy yang ditahan atau clear pending agar dashboard hijau.

**Test evidence:** B01/B05/B07–B10 (§5), counters per exact endpoint, failpoints sebelum first request, parent success/child delete/interrupted insert characterization pada fake saja, blocked Worker termination/restart, workload mixed blocked+eligible. Real server transaction/RLS tetap membutuhkan disposable E2E, bukan test produksi.

**Approval outstanding:** menerima degraded routine sync; default tunda DELETE juga atau allowlist tombstone terpisah; permission per-dataset continuation; outcome/dependency message; protokol backend phase lain bila multidevice freshness wajib.

### DDR-D8 — Coverage marker fail-safe, cache visibility dan crash ordering

**Existing / VERIFIED:** E12/E13/E19. Timestamp/initial-completed tersimpan di DataStore sesudah sebagian atau seluruh Room writes; tidak ada transaction lintas kedua storage. Initial-completed satu akun bukan bukti coverage setiap section/query. Restore Room dan metadata bisa berasal state berbeda.

**Risiko:** crash setelah Room commit sebelum marker -> valid cache + unknown; menyembunyikan cache tersebut dengan skeleton/unavailable salah. Sebaliknya stale marker complete dengan cache salah owner/berbeda generation tidak boleh mengesahkan false empty atau destructive reconciliation.

**Proposed boundary:** availability dan completeness dua sumbu berbeda. Marker bukan izin delete atau source data UI.

| Room owner result | Coverage marker | Perilaku wajib |
| --- | --- | --- |
| Cached rowsA valid, authA | Unknown/missing/corrupt/mismatched | **Tampilkan rowsA**, jangan skeleton ulang; status completeness unknown, jangan klaim “seluruh riwayat sudah lengkap” |
| Empty queryA pada catalog known yang tidak match filter | Full-dataset catalog coverage diketahui | Filter no-results sah; bukan global empty remote |
| Empty datasetA | Unknown | No authoritative empty/0; local query completed saja belum cukup; hydrate atau offline/unverified unavailable |
| Empty datasetA | Verified matching dataset/owner/content state | Empty sah, termasuk saat offline; refresh tidak menghapus state ini |
| RowsA tetapi authB atau belum resolved | Apa pun | Jangan tampilkanA; tidak boleh memakai markerA untukB |

**Crash-safe sequencing yang diusulkan, tanpa jaminan atomik palsu:** invalidate relevant completeness marker **sebelum** apply dataset yang akan mengubah coverage; kalau invalidation gagal, jangan melakukan langkah yang membutuhkan trusted coverage. Apply verified Room transaction(s); sesudah success semua boundary, publish marker yang diikat owner/dataset/validated content state. Crash sebelum finalize marker -> unknown; committed rows tetap usable. Crash sesudah marker tetapi sebelum UI publish -> baca Room pada restart, bukan UI transient state.

**Stale-marker prevention belum otomatis terpecahkan:** sebelum menerima marker complete pada startup, cocokkan owner/dataset/context dan content evidence dari consistent Room read. Pilihan no-schema: fingerprint dataset/aggregate yang relevan dan integrity validation, dengan biaya yang harus diukur; bukan timestamp DataStore saja. Jika tidak dapat membuktikan linkage, downgrade completeness ke unknown sambil menampilkan cache berisi data. Valid-empty marker harus diuji khusus restore/reset/skew. Hash linkage bukan encryption dan bukan proof remote snapshot coherence.

**Alternatif/trade-off:** DataStore unknown-first sederhana tetapi dapat kehilangan informasi completeness setelah crash; integrity fingerprint memberi linkage dengan overhead scan; Room metadata/revision table memberi atomicity lokal lebih kuat tetapi membutuhkan migration plan. Rekomendasi default unknown-first + typed availability, tidak menjanjikan complete setelah every crash.

**Dependencies schema/backend:** existing SyncPreferences dapat diperluas setelah approval tanpa migrasi Room; nama marker baru/helper masih usulan. Jika strict atomic revision/coverage dibutuhkan, STOP untuk migration/key compatibility plan; tidak menulis ALTER TABLE pada Phase A/B default. Backend snapshot predicate tetap D6, bukan diselesaikan marker.

**Rollback:** abaikan marker format baru yang tidak dikenal dan turun ke unknown; jangan clear cache/data. Jangan menghidupkan kembali initial-completed lama sebagai sumber completeness authoritative. Backup exclusions D2 juga harus mencakup coverage metadata agar cross-device restore tidak memberikan klaim palsu.

**Test evidence:** B07/B10/B13; inject crash setelah invalidate, sebelum/dalam/after Room transaction, sebelum/after marker, marker read/write failure/corruption, DB-only/metadata-only restore. Assert data berisi tetap tampil pada owner sah dan verified-empty tidak berasal marker mismatched.

**Approval outstanding:** granularity marker/linkage no-schema dan performance budget, truthful unknown-completeness copy, serta STOP bila revision metadata atomik dibutuhkan.

## 4. Phase B implementation boundaries

Semua perubahan berikut **PROPOSED**, belum dibuat.

1. **B1 — Test seams dan safety outcomes:** injectable remote transport, clock, auth/session event port, explicit dataset result. Network tetap produksi melalui adapter PostgREST/RPC existing, bukan fake di release.
2. **B2 — Single-flight + bounded scheduling:** satu registry lintas SyncRunner/master/reconnect/Worker/foreground, shared Deferred result, application-owned read flights; waiter cancellation tidak membatalkan upload durable. Registry lock tidak ditahan sepanjang network; shared catalog tidak duplicate initial read.
3. **B3 — Auth epoch + commit/publication fence:** seluruh app-controlled login/register/logout/deep-link dan SDK transitions harus melewati linearization boundary yang dapat diuji. Owner fence sampai Room commit; invalidate observers/context/results akun lama. Metadata owner lama tak boleh dibuka melalui by-ID repository. Tidak mengubah timer engine atau Supabase lifecycleCallbacks yang dilindungi tanpa review.
4. **B4 — Atomic local capture/ack:** capture parent/children/operation/revision dalam consistent transaction, network di luar Room. Ack hanya exact state yang dikirim. Evaluasi full-state comparison/fingerprint under transaction + write guards versus persisted revision; timestamp millisecond saja tidak dianggap proof. Jika durable revision baru mutlak perlu, STOP untuk migration approval.
5. **B5 — Conservative dispatch/import:** DDR-D6 denylist sebelum request pertama, verified graph+completeness+identity/pending guard sebelum apply; pending/degraded status bukan all-synced. Allow independent eligible datasets dengan bounded retry.
6. **B6 — Logout privacy dan coverage contract:** DDR-D2/D8 setelah kebijakan disetujui; typed outcomes sampai caller tidak boleh dianggap success pada failure/pending. Availability contract disiapkan tanpa renderer/shimmer baru.

**Di luar Phase B:** renderer/skeleton baru, gesture remote refresh di enam layar, Paging dependency/DAO API/pager, perubahan calculation Progress/auto-fill/completed sets, protocol template RPC/RLS migration, style/nav redesign. Minimal VM clearing/publication guards adalah safety change, bukan alasan memulai Phase C renderer.

**B entry versus B exit:** entry membutuhkan keputusan/izin dan test design yang jelas, bukan tests patch yang belum ada harus sudah PASS. Exit membutuhkan safety implementation beserta tests nyata lulus. Phase A tidak dapat membuktikan exit B tanpa menulis/menjalankan kode, dan tidak akan menyebutnya closed.

## 5. Phase B automated test contract S01–S12

### 5.1 Fake backend, barriers dan counters

**PROPOSED test harness, belum file/class existing:**

- `ScriptedSyncRemote`: deterministic catalog/profile/template/history/aggregate endpoint scripts. Routes keyed method/table/RPC/owner/range/aggregateId; supports exact pages, truncation cap, duplicate graph, parent/child version change, HTTP/auth/transport error. No real credentials or network.
- `ControlledSession`: explicit owner+monotonic epoch, initializing/signed-out/authenticated/transition events dan barriers untuk restore/deep-link/expiry. Fake port tests membuktikan logic; production SDK adapter memerlukan integration proof tersendiri, tidak diwarisi otomatis dari fake.
- `ControlledClock`: monotonic cooldown/debounce/retry scheduling, wall-clock fixed untuk same-ms cases. Gunakan virtual scheduler bila dependency testing disetujui; jangan menambah dependency saat Phase A.
- `CommitProbe` / `UploadProbe`: `CompletableDeferred`/Channel barrier pada after-capture, before-send, before-transaction, inside-transaction, before-ack dan after-last-queue-scan. Test menunggu reached signal lalu melepaskan release signal; bounded test timeout untuk mendeteksi hang, **tanpa sleep acak**.
- `RequestLedger`: atomic started/completed/cancelled/active/maxActive per `(owner, epoch, dataset)` dan ordered endpoint invocations. Counter started berarti actual transport execution, bukan jumlah UI intents. `flightId` sama dan shared outcome dibuktikan untuk semua waiter.
- Room in-memory untuk transaction/rollback/owner assertions; persisted temporary test DB reopen + new scheduler/coordinator instance untuk recovery. Reopen synthetic bukan bukti OS process-death/backups aktual; device verification terpisah.
- Record queue rows, child flags, content fingerprints dan marker state sebelum/sesudah failpoints; jangan log PII/token/payload. Dependencies existing menyediakan JUnit, WorkManager testing, Room/Compose instrumentation; coroutine-test/HTTP mock engine tidak dinyatakan tersedia. Bila perlu, ajukan dependency test-only khusus saat Build diizinkan.

Transport seam diperlukan karena SyncManager saat ini memanggil SupabaseClient langsung dan `ImfitAggregateRemoteDataSource` konkret (E07/E10). Jangan membuat test hanya terhadap duplikasi algoritme fake yang tidak dipakai production coordinator/adapter.

### 5.2 Test cases yang akan dibuat

Semua Bxx **PROPOSED / NOT_TESTED**; rujukan Txx berasal matriks v2. Jenis: U=unit, I=Room/adapter integration, X=concurrency, W=WorkManager, C=Compose fase lanjut, M=device berizin, S=authorized disposable Supabase.

| Test | Script/barrier | Assertion minimum | Jenis / prioritas |
| --- | --- | --- | --- |
| B01 — SharedFlightsAllOrigins | Tahan HISTORY transport; start10 manual callers + Home/Progress/History + Worker/reconnect/full/master catalog demands; release bersama | Satu started flight per identical key, shared result/outcome, maxActive<=bound; tidak ada10 queued runs setelah release; catalog/profile shared sesuai overlap, no duplicate initial reads | U/X/I, P0; T05/T06/T22 |
| B02 — CooldownAndCancellationOwnership | Clock virtual, repeat immediate completion bursts/failed-auto retry; cancel satu waiter, lalu owner service | Manual bypass auto cooldown tetapi join active; burst bounded; satu waiter cancel tidak membatalkan shared flight/eligible upload; service cancellation terminal bersih | U/X, P0/P1; T05/T22/T26 |
| B03 — EpochFenceABA | Capture A/epoch1; stop before-transaction, request transitionB lalu A/epoch3. Ulangi ketika old transaction sudah memegang fence | Jika transition linearizes dulu, old commit ditolak; jika commit linearizes dulu, transition menunggu dan hasil itu tetap partisiA/epoch lama. Tidak ada publication lama setelah transition, no deadlock, ID sama tidak menyamakan epoch | U/I/X, P0; T09 |
| B04 — OwnerReadsAndResultDelivery | A/B rows + stale user observer/by-ID requests, saved nav result/selection callbacks; SDK restore/deep-link event | Observer/pager context invalidated sebelumB publish, akses by-ID old owner gagal, stale picker result tidak dikirim keB/active session baru. Real picker UI integration di E/G tetap diperlukan | U/I/X lalu C, P0; T09/T15/T21 |
| B05 — ConsistentCaptureAndAck | Freeze after parent capture, concurrent child/config mutation; freeze after RPC before ack; fixed same-ms wall clock dan operation ABA | Payload salah satu consistent revision, bukan mixed parent/children; ack tak clear newer parent/child flags; retry idempotent IDs, failed-send tetap pending; revision-proof absent -> fail/gate, bukan dilonggarkan | I/X, P0; T11 |
| B06 — PendingLogoutMatrix | Online/offline pending/no-pending, sync-blocked template, concurrent write before final logout check, SDK signOut fail/cancel | Tidak silent purge/auto-retain; choice sesuai approved policy; epoch invalidate/drain bounded; B tak view/uploadA; loginA baru reschedules; failure tidak mengaku signOut/purge sukses; no infinite offline lock | U/I/X lalu M/S, P0; T10 |
| B07 — UnverifiedRemoteRead | Remote1001+ items, page3 error, cap<500, empty unverified, duplicateIDs/invalid graph, mutate child tanpa parent bump | Catalog tidak deactivate missing; template/history cached graph tidak replace; flags/pending unchanged; per-aggregate valid prior success dilaporkan partial; no complete marker pada unverified | U/I/X lalu S, P0; T07/T23/T24 |
| B08 — TemplateDenylistBeforeFirstRequest | Parameter CREATE/UPDATE nonempty/empty/rename/reorder + DELETEdefault/optional-approved; direct calls/worker/reconnect/manual | Untuk blocked op **semua** parent UPSERT/UPDATE, child DELETE/INSERT counts0; pending_operation utuh, tidak SYNCED. Optional DELETE allowlist test single tombstone only terpisah bila disetujui | U/I/X, P0; T29 |
| B09 — BlockedVsRetryAndLostWakeup | Mixed blocked template+eligible workout, write after final scan, cancellation just-before-enqueue completion, persisted DB reopen | Blocked-only ends finite/no auto retry loop, eligible transient backoff bounded; no lost pending/wakeup; auth-mismatch worker tak uploadA asB; capability change/login resumes durable re-scan | U/I/W/X lalu M/S, P0; T25 |
| B10 — TerminalOutcomesAndLocks | Inject cancellation/error pada register/join/network/transaction/marker/ack, stale flight finalization | Typed success/partial/offline/network/server/auth/cancel, skip reasons jujur; registry/locks released; old flight tak menutup spinner baru; unauth bukan all-synced; same request dapat retry eligible | U/I/X, P0/P1; T08/T26 |
| B11 — BackupPrivacyPolicy | Static XML/merged manifest assertions dan later disposable backup/restore fixtures | Source policy cocok keputusan privacy; retained DB/session/coverage tidak ikut surface yang dilarang. Static test tidak diberi label device/cloud PASS; existing backup gap harus ditutup atau retention boundary BLOCKED | U/I lalu M, P0; D2 |
| B12 — SDKAdapterTransitions | Adapter injected auth events di init/login/register/deep-link/expiry/signOut boundaries; session storage restore vs Room owner mismatch | Production adapter obeys same epoch protocol; local signOut outcome sesuai SDK; no retained-owner exposure pada restore. Timing SDK yang tak dapat difence -> gate tetap blocked, bukan green fake test saja | I/X lalu M/S, P0; T09/T10/T21 |
| B13 — CoverageCrashCacheVisibility | Crash before/after marker invalidate, before/inside/after Room commit, before/after final marker; mismatched/corrupt/DB-only/marker-only restore | OwnerA valid cached data tetap Available dengan completenessUnknown; no skeleton/false0/verified-empty palsu; wrong-owner never visible; failed marker write tidak clear Room | U/I/X lalu C/M, P1/P0; T18/T27 |
| B14 — NoNetworkFromLocalLoading | Local read/invalidations dan later actual pager append/retry/search | Request ledger remote delta0 untuk local-only operation. Di B hanya local boundary proof; **actual Paging append/retry test ditunda D/E**, tidak diberi PASS sebelum pager ada | U/I lalu C/X, P2; T13/T16 |

### 5.3 Traceability per invariant

| Invariant | Automated test contract | Gate dan batas |
| --- | --- | --- |
| S01 — single-flight all entry points/shared result | B01, B02, B09 | Phase B; endpoint counters, bukan serial mutex assumption |
| S02 — manual cooldown/burst control | B01, B02 | Phase B; test repeated pulls saat overlapping dan immediate terminal |
| S03 — epoch atomic commit fence | B03, B06, B12 | Phase B; production SDK transition proof diperlukan |
| S04 — owner observer/pager/selection/result | B04, B06, B12 | B repository/publication, E/G actual pager/picker/screen integration |
| S05 — consistent parent/children upload snapshot | B05 | Phase B; capture di transaksi, network tidak menahan Room |
| S06 — pending/ack revision protection | B05, B06, B07 | Phase B; same-ms/ABA/child flags, STOP bila butuh schema baru |
| S07 — remote graph/completeness/coherence | B07, B12 | B validator/degraded mode; actual server guarantees S-E2E terpisah |
| S08 — no destructive unverified apply | B07, B08, B13 | Phase B; retain cache/pending, blocked bukan synced |
| S09 — durable scheduling/no lost wakeup | B09, B05 | B WorkManager/reopen; OS process-death M/G belum terbukti |
| S10 — typed terminal outcomes/finalization | B02, B10, B13 | Phase B; lock/registry/pending/indicator lifecycle assertions |
| S11 — no remote network from append/retry | B14 | B boundary; D/E actual Paging endpoint-count test wajib |
| S12 — pending-safe logout/account privacy | B03, B04, B06, B11, B12 | D2 approval + Phase B safety; device backup/runtime signOut belum boleh dianggap lulus |

**Execution nanti:** unit targets demo/production `testDemoDebugUnitTest` / `testProductionDebugUnitTest` dan isolated diagnostic instrumentation mengikuti build option existing setelah approval. Seleksi kelas baru melalui test filter. Matrix bukan log keberhasilan; tidak menjalankan command tersebut di Phase A. Tidak install build ke aplikasi normal atau memilih SDK/akun produksi sebagai fixture.

## 6. Shimmer contract freeze — tanpa renderer implementation

**Baseline visual tetap v2.1 §5.2**; tidak mengubah ukuran, layout, referensi, atau empat mockup yang belum dikonfirmasi. Freeze ini adalah kontrak target, bukan klaim semua aturan sudah dipenuhi oleh source sekarang.

| Kontrak | Verifikasi terbatas source / status | Acceptance freeze |
| --- | --- | --- |
| Cached content tidak skeleton ulang | VERIFIED gap E18; Home/Progress restart loading observer. Profile/History cache precedence sudah dicatat v2 | PROPOSED: jangan restart observer untuk remote refresh; section known tidak pernah diganti skeleton |
| Per-section readiness | VERIFIED satu isLoading Home/Progress; avatar signing mendahului history | PROPOSED: identity, routine, last-log, metrics/calendar/history readiness independen; image failure fallback saja |
| Shape enam target | VERIFIED baseline mapping v2.1 dari source; D9 mockup sign-off belum lengkap | Home greeting/last-workout/routine56; list artwork48+description; picker checkbox48/footer; Progress dua cards min116/calendar; Profile avatar72/fields; History totals/session/summary, tanpa set-detail baru |
| Unknown vs cache incomplete | VERIFIED D8 storage split; marker belum tersedia sebagai contract baru | PROPOSED: data berisi dengan unknown completeness tetap tampil, tidak zero/false empty; auth owner gate tetap wajib |
| Local append no Supabase | VERIFIED belum ada Paging3 pada baseline | PROPOSED: B14 + D/E actual pager tests; jangan klaim append safety sudah teruji sekarang |
| Selected IDs independen paging | VERIFIED `ExerciseSelectionViewModel.kt:19–36,73–78`: selection terpisah filter tetapi dipangkas terhadap catalog emission | PROPOSED: ID set dan full DAO revalidation, tidak mapNotNull loaded window; no silent prune, footer count terjaga |
| Progress/prefill full dataset | VERIFIED `ProgressScreen.kt:55–56` memakai seluruh workoutLogsByDate; repository observeWorkoutLogs:86–90; getPrefill:727–733 dan WorkoutSetDao:65–81 owner/completed DB query | Pertahankan calculation/trusted provenance; tidak bergantung UI pages. Weekly Time/monthlyMinutes dan empat kartu tetap backlog terpisah, bukan closed |
| Header/footer | VERIFIED `DesignSystem.kt:220` header8; picker Scaffold bottomBar:62–70, search top8:74 | Pertahankan8dp/Scaffold sticky footer/IME; indicator overlay tidak menambah spacing; no layout/nav redesign |
| Delay/reduced motion | VERIFIED shimmerBrush:40–73 animasi1200ms; disabled brush transparent, belum visible static fallback | PROPOSED delay150ms (evaluasi120–200), hasil siap langsung; neutral light/dark shared phase; motion-off static **terlihat**, bukan transparent |

**NOT_TESTED:** no-flicker, per-section rendering, viewport gesture, anchor, screen-level TalkBack/motion-off, final mockup comparison. Freeze tidak mengizinkan pembuatan renderer Phase C atau remote gesture sebelum B exit gate.

## 7. Exact file impact untuk Phase B dan batas scope

**Actual changes Phase A:** hanya `docs/plan/IMFIT_PHASE_A_BASELINE_AND_SAFETY_GATE.md` (file ini). Semua path lainnya di tabel adalah calon impact saat kelak diizinkan, bukan perubahan sekarang.

| Path exact / status | Proposed boundary | Risk existing behavior change |
| --- | --- | --- |
| `M/data/sync/SyncManager.kt`, `SyncRunner.kt`, `SyncState.kt`, `SyncStateProvider.kt`, `SyncLifecycleController.kt`, `SyncScheduler.kt`, `SyncWorker.kt` — existing | Central registry facade/dataset outcomes, fenced apply, denylist, cancel/finalize, durable scheduling | HIGH: all callers/Worker enum mapping, lock order, auth/no-auth outcomes; jangan silently mark blocked success |
| `P/data/sync/WorkManagerSyncScheduler.kt` — existing | Re-scan/drain/wakeup and eligible blocked handling | HIGH: queue policy change bisa lost wakeup; jangan ubah APPEND_OR_REPLACE sebelum contract proof |
| `M/data/sync/RemoteSnapshotImporter.kt`, `SyncPreferences.kt` — existing | Fenced verification boundary, atomic pending guards, non-destructive unknown, coverage marker linkage | HIGH: stale remote removal/freshness intentionally berkurang; marker unknown tidak menyembunyikan cache |
| `M/data/repository/AuthRepositoryImpl.kt`, `M/domain/repository/AuthRepository.kt` — existing | Typed prepare/logout/auth transition, live owner identity, safe purge/retention | HIGH: UI callers Unit logout sekarang menganggap return sukses, signOut failure/active-session semantics |
| `P/data/auth/SupabaseAuthDeepLinkHandler.kt`, `P/di/SupabaseModule.kt` — existing | Route auth transition through session adapter; dependency binding saja pada SupabaseModule bila perlu | HIGH: PKCE/deep-link/SKD ordering; **jangan ubah** lifecycleCallbacks=false atau timer contract sebagai shortcut |
| `M/data/repository/WorkoutRepositoryImpl.kt`, `M/data/local/dao/WorkoutTemplateDao.kt`, `WorkoutLogDao.kt`, `TemplateExerciseDao.kt`, `ExerciseLogDao.kt`, `WorkoutSetDao.kt`, `UserDao.kt`, `ActiveSessionDao.kt` — existing | Consistent capture/revision check + owner scoped reads/mutations needed untuk retention | HIGH: by-ID access/child flags; jangan ubah completed-set volume, inputDefaults, session snapshot/timer engine |
| `M/data/repository/ExerciseRepositoryImpl.kt`, `M/presentation/navigation/RemoteRefreshViewModel.kt` — existing | Join same catalog/full registry; remove duplicate cooldown ownership safely | MEDIUM: preserving offline cache/manual cooldown result, no new gesture/UI integration |
| `P/di/AppModule.kt`, `D/di/AppModule.kt`, `D/data/sync/DemoSyncStateProvider.kt`, `D/data/repository/DemoAuthRepository.kt` — existing | DI/demo parity untuk kontrak yang berubah | MEDIUM: demo no-op sync tidak menjadi bukti production safety |
| `M/presentation/ui/home/HomeViewModel.kt`, `M/presentation/ui/progress/ProgressViewModel.kt`, `M/presentation/ui/exercise/ExerciseSelectionViewModel.kt` — existing, conditional safety-only | Owner-context invalidation/publication + typed logout caller changes, bukan skeleton/pager baru | HIGH bila stale content tetap tampil saat account switch; perubahan loading renderer ditunda C |
| `M/presentation/ui/profile/ProfileScreen.kt`, `M/presentation/ui/profile/ProfileComponents.kt`, `M/presentation/ui/main/MainScreen.kt`, `M/presentation/components/common/SyncProgressDialog.kt` — existing, deferred C/F kecuali approval UX D2 membutuhkan caller | Logout retry/batal/retention UI dan D3 overlay eligibility setelah owner contracts stabil | MEDIUM/HIGH: perubahan scope UI minimal harus direview, tidak menjalankan fase visual di B diam-diam |
| `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml` — existing, conditional D2 approval | Privacy backup/transfer policy untuk retained storage setelah SDK paths diketahui | HIGH privacy/data portability; belum diizinkan berubah; bukan bukti encryption |
| `M/data/sync/RefreshCoordinator.kt`, `RefreshOutcome.kt`, `SessionCommitGate.kt`, `SyncRemoteGateway.kt` — **NEW PROPOSED, belum ada** | Explicit responsibilities testable, dapat digabung jika source style menuntut | Nama usulan, bukan bukti class exist; hindari second independent sync engine |
| `UT/data/sync/RefreshCoordinatorTest.kt`, `SessionCommitGateTest.kt`, `TemplateWritePolicyTest.kt`, `SyncWorkerRecoveryTest.kt` — **NEW PROPOSED** | B01/B02/B03/B08/B09/B10 pure/worker harness | Tests belum ditulis; harness harus memanggil production boundary, bukan clone logic |
| `IT/data/sync/SyncSafetyRoomTest.kt`, `CoverageCrashRecoveryTest.kt`, `IT/data/repository/AuthLogoutSafetyTest.kt` — **NEW PROPOSED** | B04/B05/B06/B07/B13 DB/adapter integration | No real production account; device/SDK/backup verification tetap terpisah |
| `IT/data/sync/RemoteSnapshotImporterTest.kt`, `IT/data/repository/ExerciseReactiveRepositoryTest.kt`, `WorkoutRepositoryRoomFirstTest.kt` — existing | Extend characterization/guards tanpa mengubah baseline behavior assertions agar hijau | Existing test success report lama bukan hasil run Phase A |

**Tidak dalam default Phase B:** `IMFITDatabase.kt`/Room entity/schema changes, Supabase migrations/RPC/RLS, Gradle dependency upgrades, Navigation3 graph remap, design assets/Pencil, timer/notification engine. Jika invariant membutuhkan salah satu, STOP dan ajukan scope/migration/protocol plan khusus. `DatabaseModule.kt` baseline tetap menginstal migrations existing; audit ini tidak mengeksekusinya.

## 8. Risk of behavior change dan rollback guardrails

- **Pending retention:** privacy/data portability berubah; tidak boleh auto-enable tanpa D2. Rollback tidak boleh purge retained pending atau mengembalikan unsafe cleanup.
- **Template denylist:** sinkronisasi rutinitas sementara terblokir dan workout dengan missing remote template bisa tertahan dependency. Ini regression functionality yang harus disetujui dan dijelaskan, bukan disembunyikan sebagai fix selesai.
- **Snapshot fail-closed:** cache bisa stale, remote removals belum muncul, fresh install bisa unavailable untuk dataset yang coherence-nya belum terbukti. Jangan membuka destructive reconciliation supaya UI tampak sukses.
- **Coordinator job ownership:** perubahan `viewModelScope`/worker waiters ke shared service memengaruhi cancellation/retry/auth; jangan mempertahankan old network job tanpa fencing atau menghapus durable queue saat screen keluar.
- **Coverage:** downgrade unknown mengurangi klaim completeness tetapi tidak boleh menghilangkan cached rows. Revert marker format dengan ignore+unknown, bukan clear Room.
- **By-ID owner guards:** caller legacy yang salah konteks dapat mulai mendapat denied/null/error; error harus eksplisit, bukan memuat akun lain sebagai fallback. Uji semua caller terkait sebelum retention rollout.
- **Rollback mekanis:** perubahan B safety/high-risk dipisah dari visual/dependency/paging; revert hanya change set sendiri yang disetujui. Tidak reset/checkout perubahan enam issue, clear DB/installed app, downgrade schema, atau destructive migration. Semantik data yang sudah dihapus tidak dapat dibatalkan lewat revert, sehingga destructive step ditahan di depan.

## 9. Unresolved approval requirements dan Phase B entry criteria

### 9.1 Approval register — belum closed

| ID | Keputusan yang masih diperlukan | Status |
| --- | --- | --- |
| A-D2 | Offline retained-sign-out, retention/removal/privacy/backup policy, active-session/timer exit boundary, local-vs-server SDK signOut semantics | BLOCKED untuk implementasi jalur logout baru |
| A-D3 | Auth-context first + scoped overlay eligibility; typed caller/status copy tanpa graph redesign | PROPOSED, menunggu approval patch boundary |
| A-D6 | Terima create/update/rename/empty template blocked; default DELETE deferred atau tombstone allowlist terpisah; per-dataset continuation dan blocked Worker behavior | BLOCKED untuk perubahan dispatch/reconciliation |
| A-D8 | No-schema unknown-first marker/linkage approach dan crash/restore validation; izin berhenti bila revision atomik butuh migrasi | PROPOSED, belum algorithm/runtime proof |
| A-REV | Local capture/ack revision strategy cukup tanpa schema baru atau perlu proposal khusus | BLOCKED sebelum pending-write safety dapat dinyatakan lulus |
| A-AUTH | Production SDK event linearization/fence yang mencakup restore/deep-link/expiry | BLOCKED sebelum account-isolation exit proof; fake saja tidak cukup |
| A-E2E | Disposable Supabase project + akunA/B dan izin skenario remote/destructive fixtures | BLOCKED E2E; tidak menghalangi penulisan unit test setelah izin B, tetapi membatasi remote readiness verdict |

### 9.2 Entry criteria untuk mulai Phase B

1. **Persetujuan baru eksplisit** untuk implementasi Phase B, bukan Full Build/Phase C–G otomatis.
2. Approval atau narrow implementation scope yang jelas untuk A-D2/A-D3/A-D6/A-D8; jika privacy belum diputuskan, boundary logout-retention tetap tidak disentuh dan Phase B overall tidak boleh dinyatakan selesai.
3. Sepakati denylist/degraded features, no-silent-deletion, blocked-only no-retry-loop, serta independent eligible datasets. Jangan melanjutkan dengan policy implisit.
4. S01–S12 dan B01–B14 menjadi acceptance contracts; tetapkan test seams/clock/barriers/counters. S11 actual Paging deferred D/E harus tetap ditandai deferred, bukan PASS palsu.
5. Sepakati candidate file impact §7, preservation fingerprint §2/§10, no schema/backend/timer/renderer changes. Scope expansion -> STOP.
6. Strategi epoch/commit fence dan capture/ack revision mempunyai design yang bisa dibuktikan; bila SDK ordering atau durable revision belum dapat ditutup, jalankan hanya spike/test-design yang secara khusus disetujui, bukan unsafe production patch.

**Phase B exit nanti:** implementation + automated safety evidence, production adapter integration, no pending loss/account leakage/unsafe template calls, reliable worker finalization. Semua P0 yang relevan harus lulus; remote protocol limitations dilaporkan sebagai limitations/blocked capabilities. E2E nyata tetap separate release gate G.

## 10. Preservation inventory dan hasil aktual Phase A

### 10.1 Dirty tracked baseline (32 paths, sebelum report)

```text
app/build.gradle.kts
app/src/androidTestDemo/java/com/diajarkoding/imfit/core/timer/RestTimerBackgroundTest.kt
app/src/androidTestDemo/java/com/diajarkoding/imfit/data/local/database/IMFITDatabaseMigrationTest.kt
app/src/androidTestDemo/java/com/diajarkoding/imfit/data/repository/WorkoutRepositoryRoomFirstTest.kt
app/src/demo/java/com/diajarkoding/imfit/data/repository/DemoWorkoutRepository.kt
app/src/main/java/com/diajarkoding/imfit/data/local/dao/UserDao.kt
app/src/main/java/com/diajarkoding/imfit/data/local/dao/WorkoutSetDao.kt
app/src/main/java/com/diajarkoding/imfit/data/local/database/IMFITDatabase.kt
app/src/main/java/com/diajarkoding/imfit/data/local/entity/ExerciseLogEntity.kt
app/src/main/java/com/diajarkoding/imfit/data/repository/AuthRepositoryImpl.kt
app/src/main/java/com/diajarkoding/imfit/data/repository/ExerciseRepositoryImpl.kt
app/src/main/java/com/diajarkoding/imfit/data/repository/WorkoutRepositoryImpl.kt
app/src/main/java/com/diajarkoding/imfit/data/sync/SyncManager.kt
app/src/main/java/com/diajarkoding/imfit/data/sync/SyncRunner.kt
app/src/main/java/com/diajarkoding/imfit/domain/model/ExerciseLog.kt
app/src/main/java/com/diajarkoding/imfit/domain/repository/AuthRepository.kt
app/src/main/java/com/diajarkoding/imfit/domain/repository/ExerciseRepository.kt
app/src/main/java/com/diajarkoding/imfit/domain/repository/WorkoutRepository.kt
app/src/main/java/com/diajarkoding/imfit/presentation/navigation/NavGraph.kt
app/src/main/java/com/diajarkoding/imfit/presentation/ui/exercise/ExerciseBrowserViewModel.kt
app/src/main/java/com/diajarkoding/imfit/presentation/ui/exercise/ExerciseListScreen.kt
app/src/main/java/com/diajarkoding/imfit/presentation/ui/exercise/ExerciseSelectionViewModel.kt
app/src/main/java/com/diajarkoding/imfit/presentation/ui/home/HomeViewModel.kt
app/src/main/java/com/diajarkoding/imfit/presentation/ui/main/MainScreen.kt
app/src/main/java/com/diajarkoding/imfit/presentation/ui/progress/ProgressViewModel.kt
app/src/main/java/com/diajarkoding/imfit/presentation/ui/workout/ActiveWorkoutViewModel.kt
app/src/main/java/com/diajarkoding/imfit/presentation/ui/workout/EditWorkoutScreen.kt
app/src/main/java/com/diajarkoding/imfit/presentation/ui/workout/EditWorkoutViewModel.kt
app/src/main/java/com/diajarkoding/imfit/presentation/ui/workout/WorkoutSummaryViewModel.kt
app/src/production/java/com/diajarkoding/imfit/di/DatabaseModule.kt
docs/skills/imfit-ui-ux/SKILL.md
docs/skills/imfit-ui-ux/references/state-contracts.md
```

### 10.2 Untracked baseline (12 files, sebelum report)

```text
app/schemas/com.diajarkoding.imfit.data.local.database.IMFITDatabase/7.json
app/src/androidTestDemo/java/com/diajarkoding/imfit/core/timer/RestNotificationInteractionTest.kt
app/src/androidTestDemo/java/com/diajarkoding/imfit/core/timer/RestNotificationThemeTest.kt
app/src/androidTestDemo/java/com/diajarkoding/imfit/data/repository/ExerciseReactiveRepositoryTest.kt
app/src/androidTestDemo/java/com/diajarkoding/imfit/data/sync/RemoteSnapshotImporterTest.kt
app/src/main/java/com/diajarkoding/imfit/data/repository/UserLocalMapping.kt
app/src/main/java/com/diajarkoding/imfit/data/sync/RemoteSnapshotImporter.kt
app/src/main/java/com/diajarkoding/imfit/domain/model/ExerciseInputDefaults.kt
app/src/test/java/com/diajarkoding/imfit/domain/model/CompletedVolumeTest.kt
docs/IMFIT_FIX_BUG_SIX_ISSUES_REPORT.md
docs/plan/IMFIT_REFRESH_PAGING_SHIMMER_SYNC_PLAN_v2.1.md
docs/plan/IMFIT_REFRESH_PAGING_SHIMMER_SYNC_PLAN_v2.md
```

### 10.3 Verification ledger

| Aktivitas | Status aktual | Batas klaim |
| --- | --- | --- |
| Source verification Task1 + pending/template/backup/coverage boundaries | VERIFIED melalui reads/grep pada sesi ini | Tidak membuktikan frekuensi insiden atau server behavior |
| DDR-D2/D3/D6/D8, test contract S01–S12, file impact dan shimmer freeze | PROPOSED lengkap untuk review | Tidak ada code/test source dibuat atau decision privacy final dipaksakan |
| Fingerprint pre/post 474 files excluding report + tracked diff | VERIFIED: jumlah dan kedua SHA-256 identik | Seluruh file baseline tetap, hanya report baru ditambahkan |
| Markdown structure, links, inventory paths dan whitespace report | VERIFIED: pemeriksaan struktur/tabel/link, inventory32+12, S01–S12 dan B01–B14 lulus | Validasi dokumen, bukan runtime test aplikasi |
| Unit/DAO/concurrency/Compose/build/benchmark | NOT_TESTED | Tidak dijalankan di Phase A read-only |
| SDK local signOut/device backup/restore/process death/Supabase E2E | NOT_TESTED / BLOCKED sesuai izin dan policy | Tidak menghubungi backend atau perangkat |

**STOP — hanya Phase A report. Phase B dan Full Build tidak dimulai; tunggu persetujuan baru.**
