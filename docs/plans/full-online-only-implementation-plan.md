# IMFIT Full Online-Only — Plan Implementasi

## 0. Metadata dan batas audit

| Item | Nilai |
| --- | --- |
| Status | Checkpoint pengembangan untuk handoff perangkat pada `online-only`; commit/push diminta pengguna, bukan rilis/deployment. Gate host dan source fingerprint ada di laporan §7.11, scope checkpoint serta clean-SHA verification di §7.12; backend/device tetap belum teruji |
| Tanggal dibuat / diperbarui | 26 / 29 Oktober 2026 (tanggal eksekusi diperoleh dari system clock) |
| Versi dokumen | 2.1 |
| Baseline commit | `fb96784d3164a700250e7032d6565779d288029d` |
| Baseline eksekusi F1a/F1b | `online-only@c0be6b823c6e8350a4a0f1c157d239dcdfd28e57`; tidak mengubah baseline audit historis |
| Scope | Modul `:app`, source bersama `main`, binding `production`, pembanding `demo`, migration SQL, test terkait, dan metadata Supabase live |
| Pasangan dokumen | [Feature spec](full-online-only-feature-spec.md) · [Laporan implementasi berkelanjutan](full-online-only-implementation-report.md) |
| Metode | Audit baseline source/live metadata 27 Oktober + perbandingan git ref; F1b menambahkan verifikasi runtime SQL/Auth/HTTP hanya pada Docker lokal 28 Oktober (laporan §7.3) |

**Tidak dilakukan pada audit ini:** build/Gradle test, instalasi perangkat, DDL/DML, pemanggilan RPC aplikasi, pembacaan row bisnis, deployment, commit, atau push. Audit live memakai role `supabase_read_only_user` dengan `transaction_read_only=on`. Keberadaan SQL di repository tidak membuktikan deployment backend; sebaliknya, ketiadaan RPC pada schema live adalah bukti deployment untuk snapshot 27 Oktober 2026, bukan bukti perilaku runtime. Hasil test historis dalam dokumen lama tidak dianggap hasil pengujian audit ini.

**Target eksekusi 29 Oktober:** internal online-only release-ready dengan recovery data lama, proof lokal/perangkat dan production release minified; **tanpa deployment/cutoff Supabase utama**, publish signing, commit atau push. Build internal memakai signing debug existing, bukan publish-ready. Pembaruan kontrak/roadmap tidak menutup gate. Semua kontrak baru di bawah berstatus **Proposed**, dan seluruh gate implementasi/proof baru **NOT_TESTED** sampai benar-benar dijalankan pada source final. Audit/baseline dan PASS historis tetap evidence terpisah.

**Batas v2.1:** audit source-current, kontrak Proposed dan roadmap langkah 1–6 di bawah adalah snapshot step 0 yang dipertahankan, bukan inventaris ulang working tree setelah implementasi. Review/persiapan commit terbaru tercatat pada [laporan §7.11](full-online-only-implementation-report.md#711-persiapan-commit-host-only--29-oktober-2026). Pengguna mengizinkan perbaikan terarah dan check host-only; fixture Docker/backend, emulator/device, staging, commit, worktree verifikasi dan push belum diizinkan. Gate rilis §7 tidak dikurangi oleh batas izin ini.

**Scope handoff terbaru:** permintaan commit/push setelah diskusi pindah perangkat mengotorisasi checkpoint source, bukan penyelesaian semua acceptance release-ready. Satu commit atomik menjaga backend/client/caller/DI/tests/dependency closure; proposal dua commit sebelumnya digantikan. Ikuti [laporan §7.12](full-online-only-implementation-report.md#712-checkpoint-handoff-perangkat): tidak bypass hook/gate commit, tidak mengubah gate rilis §7 menjadi PASS, dan tidak memperluas izin ke fixture backend/device.

Path pendek **M** = `app/src/main/java/com/diajarkoding/imfit`, **P** = `app/src/production/java/com/diajarkoding/imfit`, **D** = `app/src/demo/java/com/diajarkoding/imfit`; path Kotlin tanpa prefix relatif terhadap M. Test mengikuti package source set-nya. Nama **baru/usulan** adalah kontrak Proposed, bukan kemampuan existing.

## 1. Kesimpulan audit

**Baseline historis:** online-first hybrid dengan proyeksi Room, sesi lokal, dan recovery manual. **Source current 29 Oktober:** kedua flavor sudah memakai `OnlineWorkoutRepository`, `OnlineSessionStore` dan guarded Start (laporan §7.9); Room masih reachable melalui auth, exercise/coordinator dan template guard production. Release-ready memerlukan penyelesaian gap di langkah 1–6, bukan pemasangan facade/session writer dari nol.

Keputusan utama yang direkomendasikan:

1. Supabase menjadi satu-satunya penyimpanan permanen data bisnis baru.
2. Repository remote mengembalikan data langsung; UI memakai StateFlow dan PagingData dalam memori.
3. Paging 3 network-only untuk daftar panjang; tidak memakai RemoteMediator.
4. Active workout dipindah ke sesi server dengan version, command idempotent, serta finish atomik.
5. Ticker timer tetap lokal; pembacaan setiap detik tidak boleh berubah menjadi network polling.
6. Recovery legacy adalah jalur transisi terisolasi, bukan antrean offline baru.
7. Room baru boleh dilepas dari runtime umum setelah semua konsumennya dialihkan. Database lama tidak boleh dibuang selama ada data belum terselesaikan, termasuk milik akun lain.

**Status blocker durability:** keputusan G0 D01 menerima kehilangan input yang belum dikonfirmasi server saat process death. Target tetap Full Online-Only tanpa durable local draft atau antrean offline baru. Release masih terblokir oleh backend final, migrasi legacy, pengamanan write server, dan recovery/idempotency (B02–B05), bukan oleh durability input.

### 1.1 Status current dan pemisahan snapshot historis

**Snapshot 27 Oktober:** G0 dan audit live G1 selesai; backend hosted belum menyediakan kontrak target. **Evidence 28 Oktober:** backend lokal, readers G3, foundation recovery §7.8 dan cutover F4/timer peek §7.9 sudah tercatat PASS dalam scope masing-masing. Tidak ada rerun runtime pada pembaruan dokumen 29 Oktober. Keputusan D01–D06 dan seluruh AC01–AC34 tetap berlaku.

| Area | Source/evidence existing | Remaining target — NOT_TESTED |
| --- | --- | --- |
| Runtime sesi | Kedua flavor bind facade remote; store application-scoped, guarded Start, CAS/replay, ticker peek memori (§7.9) | Dirty-vs-confirmed, flush 400 ms AC13/15, typed UI/service, expiry/cold/reconnect verification |
| Remote readers | Paging/state/query readers dan G3 lokal (§7.7), bukan full-pull baseline lagi | Scoped mutation invalidation, final HTTP/UI/accessibility dan >500/race regression |
| Backend lokal | 17 RPC existing (16 awal + `get_home_summary`), lockdown lokal dan smoke REST historis | Empat RPC recovery Proposed, additive replay/security/import proof |
| Recovery | Inventory read-only, historical archive, route `LegacyRecovery`/Profile entry dan guard tersedia | Selected receipt-backed import, local-active/intent/server-legacy resolution, bounded readiness dan installed-data proof |
| Profil/dependency | Facade remote tidak membuat seluruh graph Room-free | Authoritative PATCH, auth tanpa Room, exercise/template guard cutover, obsolete API/writer removal |
| Release | Unit 387 production/395 demo dan smoke §7.9 adalah PASS historis | G4/G5 final, G6a/G6b, backup/upgrade, actual production HTTP/UI dan normal/instrumented minified proof |

**Bukti live 27 Oktober 2026:** applied migrations hanya `20260828130114_imfit_schema_baseline`, `20260828130132_imfit_catalog_seed`, dan `20260828130154_imfit_backend_hardening`. `save_template_revision`, `template_write_receipts`, kolom `revision`, serta 16 nama RPC target untuk read/session/receipt tidak ditemukan. Public schema memiliki 9 tabel dengan RLS dan 29 policy; `anon` tanpa table grant, tetapi `authenticated` memiliki direct DML pada 7 tabel. Ini menggantikan status `reported / TODO/VERIFY` sebelumnya untuk snapshot live ini; source migration tetap ada dan tidak boleh dianggap otomatis deployed.

### 1.2 Lima blocker wajib dan kriteria penutupan

| ID | Blocker | Status 27 Oktober 2026 | Persiapan wajib | Bukti/kriteria siap | Gate |
| --- | --- | --- | --- | --- | --- |
| B01 | Risiko kehilangan input unconfirmed | **Ditutup oleh D01** — kehilangan diterima, target tetap online-only | Pastikan UI tidak menampilkan dirty input sebagai tersimpan; recovery hanya snapshot confirmed | Keputusan produk tertulis dan AC15/AC16/AC21 konsisten; tidak ada klaim zero-loss | G0 |
| B02 | Backend belum mendukung kontrak final | **Terbuka — dikonfirmasi live**: 16 RPC target, revision, dan receipt absen | Finalisasi page/detail/statistik/prefill/session/discovery; migration additive; compatibility | API terdokumentasi dan tersedia pada environment uji terisolasi, DTO cocok, integration/concurrency/security tests lulus | G1 |
| B03 | Migrasi data pengguna lama | **Terbuka — scope versi ditetapkan** D04/D05; Room 9/6/7 | Inventory versi/entity/owner; klasifikasi SYNCED/PENDING/REVIEW/problem; checkpoint dan pemeriksaan konflik **sebelum create sesi remote baru** | Jalur upgrade/crash/skip bridge/multi-account diuji; konflik sesi/hasil ambigu terselesaikan; data lain tetap recoverable | Preflight bagian 6, G6a–G6b |
| B04 | Write dapat melewati version/transaction protocol | **Terbuka — dikonfirmasi live**: authenticated direct DML pada 7 tabel | Compatibility cutoff dan aturan RPC-only; tutup direct CRUD/legacy RPC pada final target; perbaiki konsistensi child workout_sets | Sebelum lockdown: residual risk tercatat; final target: authenticated bypass tests ditolak server | G1/G7 |
| B05 | Recovery/transaksi workout belum lengkap | **Terbuka — belum ada schema/RPC** | CAS, request ID/payload immutable, receipt, unique session→workout, resolve outcome unknown | Commit-response-lost, replay, restart, update/update, finish/finish, finish/cancel tidak menghasilkan duplikasi/lost update | G1/G4/G5 |

Empat blocker tersisa adalah **B02 backend final**, **B03 migrasi legacy**, **B04 pengamanan write server**, dan **B05 recovery/idempotency**. Backend/active/legacy adalah pengelompokan pekerjaan, bukan pengurangan blocker. B01 hanya boleh dibuka kembali bila keputusan D01 direvisi.

### 1.3 Batas eksekusi current

- **Sudah ada:** G0 D01–D06, audit live historis, backend/readers lokal, inventory/preflight, facade remote dan session writer/guarded Start (§7.9).
- **Kerjakan berikutnya:** langkah 1–6 di §4 sesudah konsistensi ketiga dokumen; langkah 0 bukan akhir implementasi.
- **Tidak diotorisasi:** hosted link/db push/reset/purge/cutoff, commit/push, penghapusan data pengguna atau perubahan `.idea/misc.xml` milik pengguna.
- **Tidak boleh diklaim:** Ready dari source-only/fake/PASS historis; Saved sebelum receipt; unknown sebagai absence; Room-free hanya karena facade remote.
- **Fail-closed:** error transport/malformed/checkpoint/schema/dependency tetap Unknown/Blocked dan owner-isolated, bukan discard atau fallback data kosong.

## 2. Hasil deepscan dan analisis gap

Prioritas: **P0** integritas/keamanan/release blocker; **P1** fungsi utama dan skalabilitas; **P2** optimasi setelah kontrak benar. Nomor E merujuk evidence map bagian 10.

Tabel berikut adalah **deepscan baseline historis 27 Oktober**, bukan gambaran wiring current. Perubahan source current dan remaining gap ada di §1.1 dan §4; evidence baseline E/L tetap dipertahankan.

| ID | Area | Kondisi source baseline | Gap ke target pada baseline | Prioritas / bukti |
| --- | --- | --- | --- | --- |
| G01 | Runtime wiring | Production mengikat `WorkoutRepositoryImpl`, `ExerciseRepositoryImpl`, dan coordinator | Seluruh binding dan dependensi transitif Room harus dipisah; facade bukan repository remote-only | P0, E01–E03 |
| G02 | Reads | Template/history refresh remote lalu dibaca dari Room; observer berasal dari local store | Return remote result langsung, error eksplisit, state memori owner-scoped | P1, E03–E05 |
| G03 | Pagination | Coordinator mengambil semua offset page berukuran 500, lalu child aggregate per parent | Ini pagination transport full-pull, bukan infinite scroll UI; perlu summary keyset dan detail on-demand | P1, E05 |
| G04 | Template writes | RPC `save_template_revision`, receipt validation, dan durable Room intent ada di source | **Live absent 27 Okt 2026**; pertahankan desain revision/idempotency, deploy melalui migration additive, lalu ganti recovery process death dengan server operation discovery | P0, E06–E07, L03 |
| G05 | Backend template | Source migration revision dan fixture SQL tersedia; live hanya memiliki `upsert_template_aggregate` | **NEEDS CHANGE**: kolom `revision`, `template_write_receipts`, dan `save_template_revision` tidak ditemukan live; jangan menganggap source sudah terdeploy | P0, E07, E17, L03 |
| G06 | Active session | `WorkoutSessionManager` mendelegasikan start/update/recovery ke `WorkoutLocalStore` | Server harus mengambil alih lifecycle, durability, ownership, conflict, dan terminal state | P0, E03, E08 |
| G07 | Finish | Sesi difinalisasi di Room, lalu satu percobaan upload; failure tetap pending | Sukses UI harus bergantung pada receipt transaksi remote, bukan keberhasilan simpan lokal | P0, E08–E09 |
| G08 | Server session schema | Live `active_sessions` memiliki JSONB, expiry, RLS, dan `UNIQUE(user_id)`, tetapi belum `version`/`status`/receipt; FK template masih cascade | Perlu schema lifecycle, CAS, stable identity, recovery, relasi log, dan pemisahan unique active/terminal | P0, E10, L04–L06 |
| G09 | Aggregate RPC | `upsert_workout_aggregate` atomik pada satu invocation dan upsert berdasarkan ID | Bukan protokol finish sesi: tidak melakukan CAS sesi, terminal transition, atau receipt replay payload-bound | P0, E11 |
| G10 | Timer/service | UI dan service membaca sesi dalam loop sekitar satu detik; validator memakai repository | Pisahkan snapshot sesi memori dari remote reload; cold recovery dan unknown harus eksplisit | P0, E12 |
| G11 | Refresh global | Route, foreground, dan tab memicu refresh coordinator dengan coalescing 30 detik | Hapus full-pull global; initial load per query, refresh eksplisit, invalidasi terarah sesudah mutasi | P1, E13 |
| G12 | Catalog/picker | Seluruh katalog di Room/memori, search/filter lokal, selection ditautkan ke daftar lengkap | Search server debounce, pagination, dan selection lintas halaman/filter berbasis ID | P1, E04, E14 |
| G13 | Progress/calendar | `ProgressViewModel` mengelompokkan seluruh history; metrik dihitung lokal | Jangan hitung statistik dari halaman yang termuat; perlu aggregation dan date markers berbatas periode | P1, E15 |
| G14 | Profile/auth | Profil di-cache Room, `observeCurrentUser()` mengamati DAO; Profile memakai ProgressViewModel | Pisahkan auth identity dari remote profile state; Profile tidak perlu memuat history | P1, E16 |
| G15 | Account isolation | Owner guard dan credential fence sudah ada | Pertahankan prinsipnya; tambah auth epoch untuk kasus A→B→A dan hasil request lama | P0, E05, E18 |
| G16 | Worker | Scheduler enqueue no-op; SyncWorker mengembalikan success tanpa network | Jangan merencanakan penghapusan autosync yang sudah nonaktif; retire compatibility stub secara aman | P1, E09 |
| G17 | Legacy upgrade | Branch: `online-only` Room 9 dengan `OnlineRecoveryMigration` 7→9; `origin/development` Room 7; `development` lokal Room 6; `main`/`offline-first` Room 4; semua `versionCode=1` | D04 membatasi scope ke Room 9/6/7; deteksi dari schema aktual, bukan versi aplikasi; schema 4/8/tidak dikenal masuk review/blocked | P0, E19, L07 |
| G18 | Prefill | Target provenance `input_defaults` disimpan lokal dan dibutuhkan prefill | Baseline SQL/RPC upload tidak membawa field ini; pindahkan provenance ke server, jangan menebak dari hasil latihan | P1, E08, E11, E20 |
| G19 | Libraries/UI | Tidak ditemukan PagingSource/Pager/PullToRefreshBox di source yang dicari; dependency Paging belum ada | Tambah library dan pola load/error terpisah; Material3 sudah tersedia | P1, E02 |
| G20 | Security bypass | Live: authenticated direct DML pada 7 tabel; RLS owner-based; `workout_sets` hanya memeriksa ownership via `exercise_log_id` | **NEEDS CHANGE**: RLS ownership tidak memaksa CAS/finish RPC; child denormalisasi dan final direct-write lockdown belum aman | P0, E11, L05–L06 |
| G21 | Backup/privacy | Manifest mengizinkan backup; aturan backup/extraction masih template | Audit restore legacy, auth token, timer anchor, dan recovery metadata; jangan menghidupkan kembali stale state | P1, E21 |
| G22 | Tests | Ada fixture account isolation, template replay, timer validator, dan manual scheduling | Test tersebut bukan bukti pagination/session RPC/E2E online-only; tambah coverage baru dan pertahankan invariants | P0, E17, E22 |

### 2.1 Matriks backend live — AVAILABLE / MISSING / NEEDS CHANGE

Snapshot 27 Oktober 2026, role `supabase_read_only_user`, `transaction_read_only=on`, tanpa membaca row bisnis. **AVAILABLE** berarti objek ada, bukan berarti kontrak target sudah siap. Evidence L01–L09.

| Kapabilitas | Status | Bukti live | Gap / tindakan |
| --- | --- | --- | --- |
| Auth/profile dasar | AVAILABLE | `profiles` RLS own; trigger `handle_new_user`; insert/update/select policy | Pertahankan; putuskan apakah update profil tetap direct atau RPC |
| Katalog read | AVAILABLE | `muscle_categories`/`exercises` SELECT authenticated; index kategori/aktif | Tidak perlu perubahan struktur dasar |
| Read pagination/search | MISSING | Tidak ada RPC page/search; hanya table/REST | Implementasikan cursor page untuk history, template, katalog |
| Template read | AVAILABLE | Tabel/policy/index; `upsert_template_aggregate` | Tambah summary/detail RPC dan revision yang konsisten |
| Template revision/receipt | MISSING | Tidak ada kolom `revision`, `template_write_receipts`, `save_template_revision` | Deploy migration revision/receipt secara additive |
| Template write protection | NEEDS CHANGE | `authenticated` direct DML pada template/child; aggregate lama tanpa revision | RPC-only pada cutoff; receipt payload-bound |
| History/log read | AVAILABLE | `workout_logs`/`exercise_logs`/`workout_sets`; index `(user_id,date DESC)` dan `(user_id,updated_at,id)` | Verifikasi EXPLAIN untuk keyset/filter target |
| History/detail/progress/markers | MISSING | Tidak ada RPC aggregate/detail/date markers | Implementasikan summary, detail snapshot, period aggregate |
| Prefill provenance | MISSING | Tidak ada field provenance atau RPC prefill | Simpan target defaults saat session dibuat/ditambah |
| Active session storage | AVAILABLE | Tabel, RLS own, `UNIQUE(user_id)`, `expires_at`, `session_data` JSONB | Pertahankan sebagai basis; validasi JSONB perlu ditambahkan |
| Active session lifecycle | MISSING | Tidak ada `version`, `status`, CAS, terminal receipt; expiry tidak dijadwalkan | Tambah version/status/receipt dan RPC lifecycle |
| Atomic finish/receipt | MISSING | `upsert_workout_aggregate` ada, tetapi bukan finish session; tidak ada unique session→workout | Implementasikan finish transaksional + canonical receipt |
| Operation discovery | MISSING | Tidak ada receipt store/RPC | Tambah owner-scoped operation status/list |
| Retention/cascade | NEEDS CHANGE | FK template `ON DELETE CASCADE`; unique owner menghalangi terminal history; tidak ada `pg_cron` | Ubah FK/unique, expiry review 30 hari, receipt 90 hari |
| Direct-write lockdown | NEEDS CHANGE | 7 tabel memiliki minimal satu direct write; `profiles` tanpa DELETE, enam tabel lain INSERT/UPDATE/DELETE | Cabut direct DML final; uji bypass |
| Child integrity | NEEDS CHANGE | `workout_sets` punya `workout_log_id`/`exercise_id` denormalisasi; RLS hanya memeriksa `exercise_log_id` | Tambah composite constraint/trigger dan policy konsisten |
| Applied migrations | NEEDS CHANGE | Hanya 3 migration Agustus | Deploy revision + session/receipt migration setelah approval |

### Koreksi terhadap asumsi usulan awal

- `save_template_revision` **sudah ada di source**, berikut revision trigger dan receipt table, tetapi **tidak ditemukan pada schema live 27 Oktober 2026**.
- `active_sessions` sekarang **terverifikasi live** sebagai tabel dengan RLS/unique/expiry, bukan hanya dibuktikan dari baseline SQL.
- `SyncManager` lama bukan runtime aktif yang perlu dibuat lalu dihapus lagi; coordinator sekarang menyediakan remote reads dan retry workout eksplisit.
- `WorkoutHistory(date)` sekarang membuka rincian sesi **per tanggal**, bukan feed global yang sudah paginated. `WorkoutDetail(workoutId)` adalah detail rutinitas/template, bukan detail log selesai.
- Template list saat ini berada di Home. Dedicated template-list route dan feed history global merupakan perluasan navigasi bila dipilih, bukan layar existing.
- Coroutines, suspend, StateFlow, Compose, Hilt, Supabase, dan DataStore sudah digunakan. Migrasi fokus pada ownership data dan request, bukan mengganti bahasa concurrency.

## 3. Target architecture dan keputusan desain

```text
Compose screen: refresh / append / loading / error / empty
    → ViewModel: StateFlow + Flow<PagingData>, query key, auth epoch
    → Domain repositories: suspend read/write + page/error contracts
    → Supabase remote data sources: PostgREST / RPC / Auth / Storage
    → PostgreSQL: authoritative records, RLS, CAS, transaction, receipts

Active workout UI + service + notification
    → remote session manager: confirmed snapshot in memory + command serializer
    → versioned session RPCs

Transisi saja: legacy recovery adapter → database lama → explicit remote recovery
```

### 3.1 Batas penyimpanan

| Data | Target |
| --- | --- |
| Template, history, profile, active draft confirmed | Supabase |
| Loaded pages, last-successful display, input belum acknowledged | Memori, terikat akun/query |
| Auth session SDK | Boleh lokal sesuai SDK/security policy |
| Tema, bahasa, preferensi timer | Penyimpanan preferensi lokal |
| Alarm deadline/identity/generation | Boleh lokal sebagai state operasional timer; bukan backup draft/set |
| Pending lama | Dipertahankan hanya di jalur recovery transisi sampai diselesaikan |

Jangan memindahkan draft workout ke DataStore, file JSON, SavedStateHandle, atau WorkManager input sebagai pengganti Room tersembunyi. Route/filter/ID kecil boleh dipulihkan; isi bisnis belum tersimpan tidak dijanjikan bertahan setelah proses mati.

### 3.2 Pagination decision gate

Pilihan utama: **Paging 3 network-only** dengan `PagingSource<String, Summary>`, `Pager`, `flatMapLatest`, dan `cachedIn(viewModelScope)`. Tanpa RemoteMediator. Tambahkan dependency yang kompatibel melalui version catalog; versi final harus diverifikasi saat implementasi, bukan dianggap sudah tersedia di baseline.

- History: page 20, initial load 20, prefetch distance 5, placeholders off.
- Catalog/picker: page awal 25; templates bila dipaginasi: 15. Backend memberlakukan batas maksimum.
- Forward-only: `prevKey = null`; refresh harus kembali ke cursor awal, bukan anchor tengah.
- Snapshot display saat refresh gagal wajib query-scoped dan read-only; jangan membuat cursor lama tampak milik generation baru.
- Paging dapat melakukan prefetch dan beberapa load. Tidak ada janji bahwa sesudah refresh hanya tepat 20 item/request yang pernah muncul.

**Spike wajib sebelum migrasi layar:** buktikan stale display, refresh awal, append gate, dedup, dan query switch pada versi Paging terpilih. Bila produk mensyaratkan penggantian tepat satu halaman tanpa prefetch lanjutan, pilih **satu shared custom controller** sebagai pengganti Paging untuk daftar terkait. Catat keputusan; jangan membangun dua paging framework paralel tanpa kebutuhan terukur. Kontrak perilaku rinci ada di feature spec.

### 3.3 Active session dan integrity

- `OnlineSessionStore` existing adalah owner confirmed snapshot application-scoped dan serializer remote; jangan membangun manager/domain command hierarchy kedua atau menghidupkan kembali writer `WorkoutSessionManager`.
- Satu command in-flight per sesi; debounce edit angka sekitar 400 ms, flush sebelum complete/pause/finish. Dirty input tidak boleh ditimpa acknowledgement command sebelumnya.
- Semua mutasi memakai `request_id`, `session_id`, `expected_version`, payload immutable, dan identitas dari token server.
- Idempotency lookup dilakukan sebelum menolak version stale untuk replay yang sama. ID sama + payload berbeda harus gagal.
- Finish dalam satu transaksi: lock sesi → validasi → hitung aggregate completed sets → tulis parent/child → terminal state → receipt.
- Unique link session→workout mencegah log kedua meskipun retry finish memakai request ID berbeda setelah proses mati.
- Template create ambigu membutuhkan operation discovery/recent operations di server; request ID dalam ViewModel saja tidak menyelesaikan process-death recovery.

### 3.4 Lifecycle server yang perlu diubah

Baseline memiliki `UNIQUE(user_id)` pada `active_sessions`, FK template `NOT NULL ... ON DELETE CASCADE`, dan expiry wajib. Target menyimpan terminal session untuk recovery, sehingga:

1. Ubah unique global menjadi **partial unique untuk state aktif/paused** agar sesi baru dapat dibuat tanpa menghapus receipt lama.
2. Simpan template/exercise snapshot; hard delete template tidak boleh menghapus sesi atau bukti finish. Putuskan nullable FK/SET NULL atau retention policy dengan migration kompatibel.
3. Tambahkan status/version/schema version dan referensi workout terminal; spesifikasi field target di dokumen pasangan.
4. Expiry bukan perintah silent delete draft. Tawarkan review/recovery/discard; TTL dan retention disetujui sebelum release.
5. Audit bypass REST serta client lama. Finalisasi RPC-only tidak boleh dilakukan diam-diam saat versi lama masih didukung.

## 4. Urutan implementasi, deliverable, dan exit gate

F0–F3 di bawah mempertahankan hasil fase lama sebagai **evidence historis**. F4/F5 cutover existing dirangkum sebelum roadmap langkah 1–6; nomor fase lama bukan alasan mengulang wiring yang sudah ada. Gate baru tetap **NOT_TESTED**.

Urutan current: selesaikan dokumentasi langkah 0; lalu **1 → 2**. Langkah 3 independen backend recovery, langkah 4 independen 1–2. Langkah 5 menunggu 2–4 dan fixture remote langkah 6; siapkan fixture 6 sebelum Room dihapus, lalu proof final setelah 5. Migrasikan seluruh caller dan hapus kontrak lama atomik agar tree dapat dibangun. Jangan menjalankan build/lint/tests/formatter di tengah irisan paralel; verifikasi sekali pada source final oleh integrator.

### F0 — Product decision dan kontrak awal

PIC: Product + Android + Backend + QA.

- [x] Setujui batas kehilangan input unconfirmed, offline mutation lock, timer cold-start, dan logout semantics (D01/D02/D06).
- [x] Inventaris versi aplikasi/database yang pernah ada dan tetapkan scope legacy (D04): Room 9 baseline, Room 6/7 bridge/review, schema lain blocked.
- [x] Tetapkan TTL sesi 30 hari dan receipt lengkap 90 hari (D03).
- [x] Konfirmasi definisi tanggal/timezone, durasi history, dan precision volume sebelum UI/statistik terkait (K01/K02, 28 Oktober).
- [x] Putuskan scope feed history global/dedicated templates; kebutuhan existing per-tanggal tetap wajib berjalan (K03: tidak menambah route, 28 Oktober).
- [ ] Rancang protokol legacy import dan discovery operasi; tetapkan kontrak error serta API version/capabilities.
- [x] Catat keputusan G0 di feature spec dan laporan implementasi berkelanjutan.
- [x] Audit metadata backend read-only dan bandingkan dengan kontrak target.

**Exit G0 (27 Oktober 2026):** keputusan blocker D01–D06 tercatat dan acceptance criteria diperbarui; tidak ada klaim zero-loss offline. Item scope feed/timezone yang tersisa bersifat P1 dan tidak menghalangi audit backend, tetapi harus ditutup sebelum fitur terkait dirilis.

### F1 — Backend ready, additive terlebih dahulu

PIC: Backend + QA. Tergantung G0. **Status 28 Oktober 2026: F1a desain dan F1b implementasi/verification lokal selesai. Tidak ada deployment utama atau perubahan source Android.**

#### F1a — Finalisasi kontrak 16 RPC

- [x] Verifikasi branch/HEAD `online-only@c0be6b8` dan working tree awal bersih tanpa fetch.
- [x] Perinci signature, DTO wire, validation, CAS/idempotency/discovery, error mapping, dan expiry review di Feature Spec §5.4.F (bukan dokumen baru).
- [x] Konfirmasi K01–K05 melalui pertanyaan keputusan pengguna 28 Oktober: timezone/durasi, numeric 2 desimal/string wire, scope UI, profile, dan tahapan compatibility.
- [x] Kunci bounds payload/jumlah child, aturan prefill per set, expiry review nondestruktif, serta mapping SQLSTATE/HTTP target yang akan diuji.
- [x] Review compatibility signature/receipt `save_template_revision` terhadap source/DTO existing; semua koreksi memakai follow-up migration setelah `20261025000100`, bukan duplikasi migration revision.
- [x] Tetapkan kontrak desain dan contoh payload untuk 16 RPC sebagai acuan implementasi lokal; tidak mengklaim DTO Kotlin/backend sudah tersedia.

**Exit F1a desain: TERCAPAI 28 Oktober 2026.** Keputusan K01–K05 dikonfirmasi, 16 request/response/validation/protocol dan contoh payload terdokumentasi untuk implementasi lokal. Ini bukan bukti runtime/SQLSTATE/HTTP atau persetujuan rollout; perubahan yang ditemukan oleh F1b harus merevisi kontrak di tempat. Product rollout cutoff dan release compatibility tetap terpisah dari exit kontrak lokal.

#### F1b — Implementasi dan pengujian Supabase lokal

Environment dipilih pengguna: **Supabase lokal dengan fixture sintetis**. Tidak menggunakan project bersama sebagai pengganti ketika Docker/MCP bermasalah. Urutan sesudah exit F1a:

1. Verifikasi Node dan Supabase CLI **2.116.0** dari manifest/lockfile existing (`npm ci`, tanpa upgrade dependency), Docker CLI/daemon Linux sehat, port lokal tidak konflik. Pengguna mengizinkan agent menyiapkan Docker; prefer per-user WSL2 tanpa admin. Kebutuhan elevasi, perubahan Windows features/reboot, dan penerimaan lisensi harus dikonfirmasi sebelum tindakan terkait; tidak bypass verifikasi paket/TLS atau mengubah resolver/proxy sistem.
2. Config seed sudah diselaraskan pada 28 Oktober: `enabled=false`, `sql_paths=[]`; catalog seed tetap migration existing, bukan seed.sql baru. Fixture akun/data sintetis hanya di database uji lokal. Pin versi CLI/image yang dipakai dan catat PostgreSQL major; kecocokan backend live tidak diasumsikan.
3. Verifikasi target CLI eksplisit lokal, tanpa `link`, `db push`, remote DB URL, atau credential production. Bootstrap database baru tanpa reset volume lama. Reset/purge tetap memerlukan persetujuan eksplisit untuk target yang jelas; backup/restore diuji sebelum migration berisiko pada data lokal yang sudah ada.
4. Replay tiga migration Agustus, kemudian migration revision existing. Jalankan fixture template existing untuk membuktikan kondisi awal; semua kegagalan baseline harus dicatat sebelum menambah migration.
5. Tambahkan follow-up additive untuk receipt metadata/registry, session lifecycle/CAS, read RPC/index/provenance, constraint/RLS. Perubahan FK/unique memerlukan expand–validate–cutover; deteksi data inkonsisten sebelum constraint divalidasi, tanpa silent delete. Pisahkan compatibility dan final-lockdown gate; additive tidak berarti tidak dapat memblokir writer lama.
6. Jalankan integration, dua koneksi concurrency, transaction rollback, HTTP/PostgREST error mapping, JWT owner/anon/security bypass, pagination/precision/timezone, expiry dan retention. Test tidak boleh hanya menjalankan SECURITY DEFINER sebagai postgres lalu dianggap bukti authenticated/RLS.
7. Catat SHA/dirty snapshot, migration versions, toolchain, target lokal, command, jumlah assertion, PASS/FAIL/BLOCKED/NOT_TESTED di Implementation Report existing. Jangan membuat laporan atau dokumen kontrak baru.

**Exit F1b lokal: TERCAPAI 28 Oktober 2026.** Empat migration baseline dan delapan follow-up diterapkan lokal; 16 RPC/wire/error, CAS/idempotency/atomic finish, owner isolation dan final write protection teruji. Final transport runner lulus 57 pemeriksaan nyata melalui Auth/PostgREST, terpisah dari 6 unit test target guard/mock. SQL mencakup 602 workout paginated, DST/precision/provenance, 10k-row history index plan, expiry, snapshot/pause invariant, serta rollback aggregate. Backup/restore dan replay schema app kosong/upgrade dump baseline lulus; bukan cluster/CI/device/hosted recovery proof. Detail command, fingerprint dan hasil ada di laporan §7.3.

Migration `20261028000400_online_only_final_write_lockdown.sql` adalah **cutoff eksplisit**, bukan perubahan compatibility netral. Delapan follow-up tidak boleh di-push/deploy ke project utama tanpa review dan izin terpisah. Additive schema tidak menghapus draft lama: version/schema legacy dibiarkan null dan memblokir start sampai review. Receipt lama dipertahankan/replayed; tidak ada scheduler/purge otomatis.

Perintah dan prerequisite berikut adalah **historis F1b**; command final current ada di §7.2 dan mengganti dependency dump historis:

```powershell
$env:PYTHONDONTWRITEBYTECODE='1'
python -m unittest discover -s supabase/tests -p test_verify_local_baseline.py -v
python supabase/tests/verify_local_backend.py --phase final
python supabase/tests/verify_local_transport.py --phase final
python supabase/tests/verify_local_migration_paths.py
```

Runner historis memakai `/tmp/imfit-f1b-baseline-c0be6b8.dump`; ini bukan prerequisite yang boleh dipertahankan untuk proof final. Langkah 6/§7.2 wajib menggantinya dengan platform-only bootstrap lokal reproducible; jangan memakai dump/data/credential hosted. Runner baseline/compatibility hanya untuk snapshot migration yang sesuai, bukan tip final. Tidak ada reset/drop database existing.

**Diagnosis MCP terpisah:** pada 28 Oktober `opencode mcp list` melaporkan `supabase` gagal `getaddrinfo ENOTFOUND mcp.supabase.com`; Windows/Node dapat resolve hostname saat dicek kemudian. Node HTTPS timeout, sedangkan curl IPv4 ke endpoint tanpa credential memperoleh HTTP 401 (expected untuk request tanpa autentikasi). Masalah proses/resolver/transport service belum terbukti akar penyebabnya; ini bukan bukti OAuth expired atau bukti MCP sehat. Jangan mengubah endpoint read-only, meminta service_role, atau restart service bersama tanpa izin. Kegagalan MCP sendiri tidak memblokir CLI lokal, tetapi Docker tetap prasyarat.

- [x] Cocokkan schema target, grants, policies, functions, dan applied migrations dengan repository secara read-only. Hasil: 3 migration, 5 routine, 9 tabel RLS, 29 policy; 16 RPC target absen.
- [x] Verifikasi migration revision template existing dan fixture SQL di lokal; tidak membuat revision migration duplikat.
- [x] Tambahkan migration sesi versioned, status terminal, operation receipts, unique session→log, dan partial unique owner aktif di lokal.
- [x] Perbaiki FK template cascade dan composite child consistency; expiry/review 30 hari, receipt minimal 90 hari, tanpa silent purge.
- [x] Implementasikan create/get/update/pause/resume/cancel/finish dan discovery; typed payload, numeric bounds, stable identity, snapshot/pause invariants diuji lokal.
- [x] Sediakan read summary cursor, consistent detail, calendar markers, statistics, dan prefill provenance di lokal.
- [x] Tambahkan indeks history `(user_id, end_time DESC, id DESC)` visible; query plan teruji pada 10k row sintetis (bukan benchmark production).
- [x] Sediakan keyset catalog/template dan literal normalized substring search; tidak mengklaim B-tree mempercepat substring search.
- [x] Uji dua koneksi: template/update/create CAS, finish/finish, finish/cancel, same-request replay, commit-response-loss, dan cross-account lokal.
- [x] Uji compatibility dan final RPC-only lokal secara terpisah, termasuk column grants profil dan penolakan legacy RPC.
- [ ] Review target patch PostgreSQL/API/TLS, backup/rollback hosted, minimum-client cutoff dan deployment utama; menunggu persetujuan terpisah.

**Exit G1 release tetap terpisah:** F1b membuktikan backend lokal, bukan DTO/repository Kotlin, distribusi client lama, versi hosted, atau backup/rollback production. B02/B04/B05 memiliki bukti lokal tetapi belum ditutup untuk aktivasi production; B03 Room/device belum diuji. F2/G2 dapat dimulai dengan fake/local API tanpa mengaktifkan sesi remote untuk instalasi lama.

### F2 — Android remote foundation dan paging spike

PIC: Android + QA. Contract fake dapat dikerjakan sebelum G1; integrasi menunggu G1.

- [x] Definisikan `RemotePage`, `QueryKey`, `RemoteError`, receipt, session version Long, typed DTO/request untuk 16 RPC, dan auth epoch.
- [x] Identity session terpisah dari profile; captured credential + epoch; cancellation dilempar ulang, bukan empty/network.
- [x] Tambahkan `OnlineRepository` RPC-only tanpa DAO atau fabricated fallback; runtime repository existing tidak diganti.
- [x] Tambah Paging common 3.3.6 + adapter PagingDataPresenter; tanpa RemoteMediator/Paging Compose/custom paginator paralel (Spec §5.4.H).
- [x] Implementasikan normalisasi query, search debounce 300 ms, refresh generation, append error latch, cursor-chain validation dan stable-ID dedup.
- [x] Tambahkan fake transport yang menahan respons, reverse completion, response-loss sesudah modeled commit dan auth A→B→A.
- [x] Foundation tidak memakai connectivity boolean sebagai bukti request berhasil; authoritative empty hanya dari response valid.
- [ ] Hubungkan identity lifecycle SDK, DI dan consumer layar di F3/F4 setelah gate terkait; belum diaktifkan pada F2.

**Exit G2:** kontrak read/state dan race tests lulus; refresh tidak memanggil importer/Room; tidak ada respons akun lama yang masuk state baru.

**Status G2: PASS foundation/spike lokal.** 37 test baru per flavor, keseluruhan 275 production/281 demo, dan assemble kedua APK debug lulus pada source dibekukan. Hasil/identitas input serta batas bukti ada di laporan §7.4; integrasi SDK auth/DI/layar tetap gate F3/F4, bukan sudah diaktifkan karena unit test lulus.

**Bukti/keputusan F2:** source terisolasi `data/remote/online`, test di package yang sama, laporan §7.4. Mutasi memakai original JSON/ID/CAS, conservative repository mutex, owner-wide unknown gate dan discovery sebelum menerima write berbeda (juga mencegah retry create dengan resource ID baru). Tidak ada auto-generated retry request atau durable queue; ini bukan refactor manager F4. SDK 3.0.3 menghilangkan `code` error sehingga adapter RPC memakai Ktor raw-envelope terpisah, bukan mengubah exception handler existing. Exit G2 hanya untuk foundation/spike yang diuji, bukan acceptance perangkat/layar atau izin production.

### F3 — Remote read migration per irisan fitur

PIC: Android + Backend + QA. Tergantung G1–G2.

Urutan disarankan:

1. Catalog dan picker: risiko write kecil; selection harus tetap utuh lintas halaman/filter, exclusion tetap berdasarkan ID.
2. History summary + detail: hilangkan full-pull child logs pada daftar; detail hanya saat diminta.
3. Progress/calendar: aggregate periode terpisah; jangan mengganti seluruh history dengan halaman pertama lalu mempertahankan rumus lokal.
4. Template/Home: bounded summary, detail lengkap dan revision; pagination template hanya pada permukaan list yang benar.
5. Profile dan summary: dedicated remote state; tidak lagi berbagi kebutuhan history lewat ProgressViewModel.

**Pelaksanaan G3:** reader runtime/DI production+demo dan SDK auth epoch bridge sudah ada di working tree. Home last-workout all-time mendapat satu additive bounded RPC lokal `get_home_summary()` (17 RPC total), bukan pembatasan 366 hari atau full-pull. Minimum template CAS bridge memakai snapshot remote yang diedit dan guard legacy fail-closed; bukan migrasi session writer G4. Catalog/picker → history/detail → aggregates/markers → Home/template → Profile dihubungkan tanpa route baru. Bukti final/race/UI belum diklaim sampai laporan §7.5 ditutup; ponsel fisik tidak digunakan.

**Kelanjutan G3 pada baseline `8abf100`:** mapping viewport diperbaiki menjadi stable item key → ID pada keempat consumer; header/footer tidak lagi diperlakukan sebagai posisi data. Hint ditahan saat refreshing/stale dan dikirim ulang setelah refresh dengan ID yang sama. **PASS** 297 production + 303 demo unit tests, kedua APK debug, APK instrumentation demo, dan 7 acceptance demo terarah pada emulator API 34 macOS. Matriks lengkap tetap pending; bukti/batas di laporan §7.6. Tidak mengaktifkan G4/G5 atau deployment utama.

- [x] Pada reader yang dimigrasikan sediakan loading awal, legitimate empty, no-results, initial error, refresh error dengan data lama, dan append error bila relevan (matriks/N/A §7.7 laporan; bukan semua release combinations).
- [x] Refresh tersedia di empty/error melalui scroll container full-size dan CTA; bukan hanya saat list berisi (picker compact/font150 memakai explicit refresh CTA; gesture teruji pada font100).
- [x] Hapus global full refresh di `NavGraph`, `MainScreen`, dan `RemoteRefreshViewModel` setelah semua consumer terkait memiliki load/invalidation sendiri.
- [x] Simpan state memori saat kembali ke route yang ViewModel-nya masih hidup; recomposition tidak memicu fetch (host retained-query dan runtime no-fetch tests §7.7).
- [x] Pertahankan desain IMFIT dan navigasi existing; route baru harus diberi nama/entry/caller/test secara eksplisit (tidak ada route baru; regresi navigasi terarah §7.6 PASS).

**Exit G3:** layar membaca remote langsung, statistik benar untuk dataset > satu halaman, dan tidak ada hidden full snapshot pull pada route/tab/foreground.

**Status exit G3: PASS historis lokal** pada source final §7.7: 310 production + 316 demo unit, kedua APK debug/instrumentation, 17 UI demo. Perbaikan meliputi picker context/owner retry, publication reset→identity sebelum collector Main.immediate, source/saveable key editor dan stale detail/year calendar. SDK/HTTP production, manual accessibility penuh, installed-data upgrade dan release-minified belum dibuktikan. Iterasi berikutnya §7.8 lalu §7.9 sudah memasang inventory/preflight dan cutover sesi; remaining work current mengikuti langkah 1–6.

### F4/F5/F6 — Source current dan evidence historis

**Source-verified 29 Oktober:** `AppModule` kedua flavor bind `OnlineWorkoutRepository`; `OnlineSessionStore.start()` memakai `guardedStart.create`, pending immutable/CAS dan `peek()` memori sudah ada. `LegacyStartPreflight.inspect()` masih memeriksa receipt 90 hari satu page limit 100 dan fail-closed pada hasMore, sehingga >100 receipt terminal dapat memblokir Start. Production `RemoteReadModule.legacyGuard` dan `AuthRepositoryImpl` masih membawa database/sync; facade finish masih fetch detail setelah receipt; service STOP masih stop setelah cancel gagal.

**PASS historis §7.8:** inventory read-only exact schema 6/7/9, owner/epoch preflight, checkpoint evidence/fsync dan Android file/WAL/corrupt/restart; bukan receipt-backed import/G6a/G6b. **PASS historis §7.9 (28 Oktober):** facade remote kedua flavor, versioned writer/adapter satu intent, guarded Start, cancel await-receipt dan ticker peek tanpa I/O; production 387/demo 395 unit, debug builds, smoke REST `SMOKE_OK` serta connected rerun dengan batas di laporan. Tidak diulang menjadi bukti 29 Oktober.

**G4/G5 final, G6a/G6b dan release belum selesai.** AC13/AC15 tetap mewajibkan dirty input memori, debounce **400 ms**, flush dan status konfirmasi. D01 menerima kehilangan unconfirmed input saat process death, bukan menghapus kebutuhan ini. Langkah 1–6 mengganti sisa roadmap F4–F7: kontrak baru **Proposed**, seluruh execution gate baru **NOT_TESTED**.

### Langkah 1 — Backend recovery additive setelah lockdown (Proposed; NOT_TESTED)

Tambahkan **baru** `supabase/migrations/20261029000100_online_only_legacy_recovery.sql`; jangan rewrite 13 migration existing atau membuka `upsert_workout_aggregate`. Reuse private `require_owner`, `request_replay`, `record_operation`, `fail`, `cursor_encode`, `cursor_decode` pada `20261028000100_online_only_operation_receipts.sql`. SECURITY DEFINER `search_path=''`, owner token, revoke public/anon EXECUTE; authenticated hanya RPC publik berikut.

#### Empat signature recovery — Proposed

```sql
public.get_start_recovery_status(p_contract_version integer default 1) returns jsonb
public.get_legacy_recovery_page(p_cursor text default null,p_limit integer default 20) returns jsonb
public.resolve_legacy_session(p_request_id uuid,p_session_id uuid,p_expected_source_fingerprint text,p_resolution text) returns jsonb
public.import_legacy_workout(p_request_id uuid,p_source jsonb,p_workout jsonb,p_exercises jsonb,p_sets jsonb) returns jsonb
```

**Readiness:** response `{contract_version:1,checked_at:timestamptz,can_create:boolean,blockers:[{kind,resource_id,version,source_fingerprint,reason}],has_more_blockers:boolean}`; version/fingerprint nullable. kind `LEGACY_SESSION`, `ACTIVE_SESSION`, `EXPIRED_SESSION`; reason `LEGACY_REVIEW_REQUIRED` atau `SESSION_REVIEW_REQUIRED`. Satu snapshot query, maksimal **20** preview urut resource ID; can_create dari EXISTS seluruh blocker, bukan jumlah preview. Effective ACTIVE/PAUSED, expiry unresolved dan null-version/schema legacy belum diakui memblokir; EXPIRED resolved tidak. Receipt terminal historis tidak diperiksa. Contract selain 1 → `PT400/VALIDATION`.

**Page/fingerprint:** envelope RemotePage, limit **1–100**, keyset id/cursor owner-query-bound; item `{session_id,source_fingerprint,resolution,raw_source}` mencakup unresolved/archived; resolution null atau `KEEP_ARCHIVED`. Raw row tetap null-version/schema, bukan SessionSnapshot palsu; paging hanya explicit UI. Satu helper page/readiness/resolution/create: lowercase hex SHA-256 UTF-8 JSONB text field asli `id,user_id,template_id,template_name,start_time,current_exercise_index,session_data,last_activity_at,expires_at,created_at`. Exclude status/review/updated_at agar timestamp trigger tidak mengubah hash archive; simpan original updated_at pada raw_source dan jangan nonaktifkan `update_active_sessions_updated_at`.

**Archive server tanpa delete:** hanya `KEEP_ARCHIVED`; **baru** private `imfit_private.legacy_session_reviews(user_id uuid,session_id uuid,source_fingerprint text,raw_source jsonb,request_id uuid,reviewed_at timestamptz)`, PK `(user_id,session_id)`, RLS/no direct grant. Replay sebelum source/CAS; owner+row lock. Foreign/missing → `PT404/RESOURCE_NOT_FOUND`, fingerprint berubah → `PT409/LEGACY_SOURCE_CHANGED`. Review raw asli+receipt atomik; status EXPIRED/review_required=false tanpa fabricate version/schema/delete. Receipt `{request_id,operation:"resolve_legacy_session",session_id,source_fingerprint,resolution:"KEEP_ARCHIVED",committed_at}`, resource SESSION. Latest create/get_active_session mengecualikan legacy resolved **hanya bila review fingerprint cocok**; current lookup mengabaikannya, page tetap raw. Unresolved conflict; expiry versioned memakai `AcknowledgeExpiry`, bukan RPC legacy.

**Import completed insert-or-exact-match:** p_source `{schema_version:6|7|9,kind:"WORKOUT",id:<original UUID>,fingerprint:<aggregate SHA256>,mapping_version:1}` wajib. Preserve seluruh parent/exercise-log/set UUID; non-UUID blocked review/archive tanpa replacement. Owner token, payload user_id harus cocok. Strict allowlist:

| Payload | Kolom bisnis |
| --- | --- |
| Parent | `id,user_id,template_id,template_name,date,start_time,end_time,total_volume,total_sets,total_reps,deleted_at,created_at,updated_at` |
| Exercise | `id,workout_log_id,exercise_id,exercise_name,muscle_category,order_index,total_volume,total_sets,total_reps,input_defaults,created_at,updated_at` |
| Set | `id,exercise_log_id,workout_log_id,exercise_id,set_number,weight,reps,is_completed,created_at,updated_at` |

Uncompleted sets ikut; sync/pending bukan kolom bisnis. Epoch-ms → ISO UTC tanpa fallback sekarang, date dari epoch tersimpan UTC. REAL sebagai text → BigDecimal → exact decimal string tanpa Float/Double domain/rounding 2 desimal. Schema tanpa input_defaults → null bukan inferred provenance; raw metadata tak representable tetap di archive. Canonical UUID/timestamp/numeric tanpa rounding, exercise `(order_index,id)`, set `(exercise_log_id,set_number,id)`; validasi parentage, identity/order/set uniqueness, dependency owner, bounds DB existing, totals completed sets. Totals mismatch `PT409/LEGACY_REVIEW_REQUIRED`, malformed `PT400/VALIDATION`; template missing/foreign review bukan silently null; source_session_id null.

Extend resource constraint WORKOUT; **baru** private registry `imfit_private.legacy_workout_imports`, PK `(user_id,source_schema_version,source_id,mapping_version)`, fields `source_fingerprint,canonical_payload,payload_digest,workout_id,id_mapping,result`, RLS/no grant. SHA-256 canonical JSONB server; mapping identity UUID asli; key sama/payload atau fingerprint beda conflict. Replay/request owner lock; parent+children+registry+receipt atomik. Existing owner aggregate compare seluruh projection termasuk child IDs/timestamps/flags/provenance; target metadata tak dikirim wajib default null/false, bukan dihapus. Sama → MATCHED tanpa rewrite, beda → `PT409/LEGACY_IMPORT_CONFLICT`. Foreign UUID collision owner-hiding NotFound; partial child collision rollback seluruh transaksi.

Receipt `{request_id,operation:"import_legacy_workout",workout_id,source_fingerprint,payload_digest,outcome:"IMPORTED"|"MATCHED",id_mapping,exercise_count,set_count,committed_at}`. Same request/different payload → IDEMPOTENCY_MISMATCH; new request/same source → workout/mapping sama. Receipt ≥90 hari, dedup linkage tetap tanpa purge. Extend `M/data/remote/online/{OnlineDtos,OnlineRpc}.kt` typed calls/validators/resource type dan demo parity; exact audit **17 existing + empat RPC baru**, grants/column grants/private RLS. **Baru** `supabase/tests/online_only_legacy_recovery.sql`; extend final backend/transport/replay runners + exact migration inventory, jangan prefix-only guard.

### Langkah 2 — Selected-item recovery pada UI existing (Proposed; NOT_TESTED)

Reuse reader/inventory/checkpoint/archive, LegacyRecoveryViewModel/Screen dan route LegacyRecovery, tanpa upload-all/autosync/UI paralel.

- **Baru** `M/data/legacy/LegacyAggregateReader.kt`: parent+children+dependencies satu read-only stable snapshot dengan fence existing; reuse schema detection/SQL inventory, bukan record() scan berbeda. Cap **10.000 owned rows**; overflow/corrupt/unknown/layout/read error blocked bukan truncated success. Exact **6/7/9**; 4/8/alternate unknown tidak ditebak/dimigrasi.
- **Baru** `LegacyImportMapper.kt`: raw maps → payload langkah1, bukan Room entities/WorkoutUploadSnapshot/Double DTO/uploader unversioned.
- **Baru** `LegacyRecoveryCheckpoint.kt`, `FileLegacyRecoveryCheckpointStore.kt`, `LegacyRecoveryCoordinator.kt`: `noBackupFilesDir/legacy_recovery`, AtomicFile+fsync, owner-hashed, max **4 MiB/owner**, **10.000 entries**; foreign/corrupt/unsupported/overflow blocked, tidak overwrite kosong.
- Durable action: `formatVersion=1,ownerId,sourceSchemaVersion,source kind/id/fingerprint,aggregate/dependency fingerprint,actionId,operation,requestId,immutable request JSON,body digest,source→remote mapping,review decision,state,validated receipt`. States `AWAITING_REVIEW,PREPARED,SUBMITTED,CONFIRMED,CONFLICT,BLOCKED`, tanpa auto-clean/delete.

Coordinator API **Proposed** (semua suspend):

```kotlin
suspend fun review(): LegacyRecoveryReview
suspend fun prepare(identity: AuthIdentity, selection: LegacySelection): LegacyRecoveryAction
suspend fun submit(identity: AuthIdentity, actionId: String): LegacyRecoveryResult
suspend fun resolve(identity: AuthIdentity, actionId: String): LegacyRecoveryResult
```

Model displayed identity/evidence, selection dan typed outcome, bukan boolean synced. prepare revalidasi owner/epoch/displayed evidence lalu durable PREPARED; submit durable SUBMITTED sebelum network. Crash exact body/request/IDs sama; CONFIRMED hanya receipt identity/operation/resource/source/mapping/counts valid. Server payload_digest disimpan dan konsisten saat replay, bukan hash serialisasi Android. Source berubah sebelum submit blocked; sesudah dispatch resolve original sebelum review baru. Account switch tidak publish success ke akun baru; owner asal resolve nanti, epoch lama bukan durable authority.

- Pending CREATE workout → explicit selected aggregate import. UPDATE/DELETE tanpa CAS evidence atau invalid/unrepresentable/mismatch → review+keep-history, bukan disguised upsert/create. Profile/template historis keepHistory; apply template jika dipilih via CAS editor existing, tanpa janji child UUID preservation RPC template.
- Room9 intent: full TemplateWriteRequest, targeted get_operation_status; COMMITTED harus exact original save_template_revision replay karena lookup tanpa original payload. NOT_FOUND hanya explicit retry original, tanpa request/template ID baru. Durable resolution sidecar, tidak delete/mark synced source; reuse `OnlineRepository.execute(original,expectedIdentity)`, ledger runtime tidak dilemahkan.
- Local active: explicit abandonment **“Simpan sebagai arsip lama, tidak dapat dilanjutkan”**; sumber tidak dihapus, tidak import draft versioned/provenance/child ID tebakan. Checkpoint fingerprint-bound; keepHistory generik tidak mengakui active/intent.
- Remote ACTIVE/PAUSED resume/cancel await receipt; expired versioned review+AcknowledgeExpiry; server legacy page+KEEP_ARCHIVED; tidak merge/replace otomatis.
- Layar existing menampilkan inventory/dependencies, local active/intent, remote session/outcome, historical aggregate, blocked reason; contextual inspect/keep/resolve/retry-original/import-selected/resume/cancel/acknowledge dengan confirmation. Epoch berubah cancel job/dialog/selected raw.
- Preflight readiness RPC + targeted checkpoint/intent request IDs; **hapus all-receipt readiness loop**, bukan naik cap100. has_more_blockers → RecoveryRequired; transport/malformed/checkpoint error → Unknown. Resolution hanya source+aggregate+dependency fingerprint cocok, restored/changed source review ulang. Second inventory+expected-epoch guard tetap.
- Production RemoteReadModule.legacyGuard database→standalone inventory+validated resolutions; resolved intent tidak block template selamanya, unknown fail-closed. Kedua LegacyRecoveryModule bind reader/store/coordinator; demo Absent bukan upgrade proof.
- Home/error Start callback route LegacyRecovery di NavGraph; kembali tidak auto-create, Retry preflight baru. A resolved boleh Start meski B pending; data B tidak terlihat/diunggah/dihapus.

### Langkah 3 — Confirmed/dirty session, typed commands, authoritative timer (Proposed; NOT_TESTED)

Reuse application-scoped OnlineSessionStore/OnlineRepository, SessionCommand, OnlineAuth reset; tanpa session manager/domain command hierarchy baru.

- Tambahkan `SessionSetKey(exerciseLogId:String,setId:String)`, `SessionSetDraft(weightText:String,repsText:String,editRevision:Long)`, `SessionSavePhase { CONFIRMED,DIRTY,SAVING,OFFLINE_BLOCKED,UNKNOWN,RESOLVING,CONFLICT,RELOADING }`; state drafts/savePhase/connectivity hint/requiresVerification, snapshot confirmed-only. Debounce application-scope DI; synchronous reset/cancel delayed jobs pada epoch change.
- API `editSet(sessionId:String,key:SessionSetKey,weightText:String,repsText:String)`; suspend `flushEdits(sessionId):SessionSnapshot`, `resolvePending():SessionMutationReceipt?`, `reload(sessionId:String?=null):SessionSnapshot?`; command/finish/cancel/retry existing serializer.
- `suspend fun acknowledgeExpiry(sessionId:String):SessionSnapshot`: mutex/pending/epoch guard, targeted reload+explicit confirmation; hanya effective EXPIRED/review kirim AcknowledgeExpiry latest version. Tidak melewati editable ACTIVE/PAUSED/tidak flush draft expired; receipt mengakhiri review bukan revive, raw dibuang setelah consent.
- Debounce **400ms**, urut child order/set number; koma→titik, BigDecimal/reuse validateInputWeight **0–1000kg ≤2 desimal**, reps **0–1000**. Invalid/incomplete tetap draft, flush validation failure bukan zero; field legacy tak diedit tak diround.
- Raw text memori, hoist dari ActiveSetRow rememberSaveable; bukan SavedStateHandle/Room/DataStore/file. Receipt A clear revision A saja; B saat saving tetap. Saved hanya tanpa drafts/pending/error/review/verification.
- Dependent action satu mutex: freeze staging→private flushLocked()→RPC latest version. Debounce bukan recursive public command(EditSet); cancel delay worker bukan dispatched mutation. Gagal pertama stop, reconnect tidak auto-write.
- `OnlineRepository.execute(call,expectedIdentity=null,onRegistered:()->Unit={})`: callback setelah immutable ledger/owner gate sebelum dispatch. Store preserve/guardedStart callback di boundary ini; UnresolvedMutation sebelum registration tidak membuat pending session palsu.
- Resolve original call/epoch: COMMITTED acknowledge; NOT_FOUND tetap pending+explicit retry original; read error locked. Replay tua acknowledge tanpa downgrade. Conflict block sampai authoritative reload/review; reload tidak acknowledge unknown. Definitive conflict tawarkan server/buang raw atau explicit reapply raw snapshot baru; unknown tidak request baru.
- Complete flush→positive confirmed→CompleteSet receipt→rest. Finish flush→atomic receipt→summary ID; `WorkoutRepository.finishWorkout():String?` dan facade return receipt.workoutId saja; summary fetch detail, read failure bukan retry finish. Migrasi seluruh `\bfinishWorkout\(`.
- Cancel tidak flush dirty yang dibuang/tidak bypass pending unknown; clear/navigate hanya terminal receipt. Add/remove/select/pause/resume/rest typed SessionCommand remote IDs; server prefill/pause anchors. AddExercise sequential skip confirmed IDs saat explicit retry/stop failure; rest **0–600 detik**, partial failure confirmed partial bukan common override palsu.
- VM collect store confirmation/raw, ticker clock/peek saja. Screen/components/row `onSetInput(SessionSetKey,String,String)`, `onSetComplete(SessionSetKey)`, `onSetRemove(SessionSetKey)`, `onExerciseRemove(String)`, `onSetAdd(String)`; keys epoch/session/child/set, bukan index lookup sesudah suspend; hapus unused ExerciseRepository injection.
- Cold coalesced remote reload sekali; Loading/Blocked/Unknown/Confirmed bukan started=true prematur. LegacyStartBlocked review/Retry. Notification known session target tidak create via template ID; terminal known targeted reload→receipt/summary; cold tanpa target explicit paginated recent-operation review bukan auto-create/full-pull.
- Offline/error/unknown/conflict lock aksi, raw tetap; NetworkMonitor validated capabilities hint saja. Reconnect coalesced read verification tanpa retry mutation; demo deterministic availability. ID/EN status saved/dirty/saving/offline/unknown/conflict/Retry/Reload spec §4.4, bukan warna saja; Back/logout warning unconfirmed/unknown; logout tidak cancel server.
- WorkoutService inject store; typed pause/resume flush+caught error. STOP stopWorkout hanya cancel receipt/authoritative absence; failure retain locked/error notification.
- peek/timer valid current identity, verified loaded snapshot, effective ACTIVE/PAUSED, !reviewRequired; cold Unknown bukan null/false absence. Cold service verify sekali entry/recovery bukan ticker/validator; preserve monotonic/generation/boot/late-window. Cold-offline anchor tanpa expiry commit/completion/audio/business RPC; online verify→deadline asli bukan reset duration.
- DemoSessionApi parity revision conflict/SESSION_REVIEW_REQUIRED/effective expiry/retained numbering/canonical paused terminal; fake bukan SQL/RLS proof.

### Langkah 4 — Profil authoritative, auth tanpa Room, scoped invalidation (Proposed; NOT_TESTED)

- Ganti AuthRepository.updateProfile(user) → `suspend fun updateProfile(patch:ProfilePatch):Result<User>`. **Baru** `M/domain/model/ProfilePatch.kt`: sealed `ProfileField<out T>` dengan `Omitted:ProfileField<Nothing>`, `Value<T>(value:T):ProfileField<T>`; `ProfilePatch(name:ProfileField<String>=Omitted,birthDate:ProfileField<String?>=Omitted,avatarPath:ProfileField<String?>=Omitted)`. Omitted tidak dikirim; Value(null) JSON null. Tidak menambah editor profil (tidak ada application caller existing).
- KtorReadTables owner/epoch-bound PATCH, `Prefer return=representation`, select `id,name,email,birth_date,avatar_url`, writable hanya `name,birth_date,avatar_url`. Empty patch authoritative GET tanpa PATCH; tepat satu row owner cocok, zero/wrong/malformed gagal. Cache/return row bukan input; cancellation rethrow.
- `RemoteReadGateway.updateProfile(patch):RemoteProfile` production/demo. AuthRepositoryImpl lazy Provider<RemoteReadGateway> cegah demo cycle; getCurrentUser/observeCurrentUser remote+memory per epoch, local identity SDK-only tanpa profile network. Profile error bukan logout/auth absence/profil owner lama.
- Upload helper throwing path-returning; upload foto gagal bukan avatar-null metadata success; upload sukses/PATCH gagal tidak cache committed path. Registration auth sukses/enrichment gagal return failure pesan akun mungkin sudah dibuat, bukan profile success/auto signUp ulang/rollback/delete akun/storage. Canonical storage path, signed URL presentation-only; satukan helper/signing Home/Profile/Progress, failure placeholder tanpa menyembunyikan data lain.
- Lepas database/sync/preferences/cache DAO/logout prune AuthRepositoryImpl dan scheduler/lifecycle SupabaseAuthDeepLinkHandler. Logout clear memory/PII/timer bukan legacy/pending baru; jangan log email/token/payload/signed URL. DemoAuthRepository/FakeUserDataSource/stubs migrasi exact `\bupdateProfile\(` app/src, tanpa alias.
- TemplateEditBridge.changes hanya template list/detail terkait, bukan summary/progress/history. **Baru** `M/data/remote/online/OnlineMutationChanges.kt`: application-scoped typed flow `WorkoutCommitted(identity,requestId,workoutId,startedAt)`, `ProfileChanged(identity)`, request dedup per login; publish setelah receipt/returned-row. Finish resolve/retry emit sekali, import aggregate confirmed emit; bukan durable event queue.
- Home workout→homeSummary/session indication, profile→profile; Progress workout→aggregate/markers/selected day hanya DateWindow cocok timezone, profile→profile; RemoteHistoryViewModel feed/totals hanya tanggal cocok bukan detail lain; ProfileViewModel profile saja. Retained collectors epoch-fenced/new VM initial load; bukan global route/tab/foreground full-pull.

### Langkah 5 — Clean cutover writer/dependency/API lama (Proposed; NOT_TESTED)

Menunggu 2–4 dan fixture remote6. Hapus source/interface/binding/tests obsolete atomik, **bukan database pengguna**.

- CreateTemplateViewModel lookup→RemoteReadGateway.exercises(ids); seluruh ExerciseRepository consumers→bounded gateway; hapus interface/Room impl/binding setelah exact references ExerciseRepository app/src bersih. Fake catalog demo tetap.
- Home pending-upload/manual retry/HomeSyncStatusCard→owner recovery status/entry bukan upload; lepas SyncRunner/SyncStateProvider; standalone template guard dan deep-link tidak enqueue/unblock sync.
- Hapus `M/data/repository/{WorkoutRepositoryImpl,WorkoutTemplateRepository,WorkoutLogRepository,WorkoutSessionManager,WorkoutLocalStore,UserLocalMapping}.kt`, `D/data/repository/DemoWorkoutRepository.kt`, `M/data/sync/{RemoteWorkoutCoordinator,RemoteSnapshotImporter,WorkoutUploadSnapshot,TemplateSnapshotOrder,SyncPreferences}.kt`, obsolete SyncRunner/SyncStateProvider/SyncLifecycleController/SyncScheduler implementations/bindings/demo provider. Hapus ImfitAggregateRemoteDataSource, preserve TemplateWriteRequest/TemplateWriteExercise/TemplateWriteReceipt replay.
- Hapus production DatabaseModule dan Room-only `M/data/local/{dao,entity,database}` setelah tests pindah; preserve standalone legacy reader/stores dan `app/schemas/com.diajarkoding.imfit.data.local.database.IMFITDatabase/{6,7,9}.json`. imfit_database+committed WAL utuh, bukan janji byte SHM operasional tetap.
- SyncWorker tetap terminal retired-work handler tanpa network/DAO/coordinator, bukan queue baru; WorkManager timer/KSP Hilt tetap.
- WorkoutRepository hapus observeTemplates/observeTemplate/getTemplates/observeWorkoutLogs/observeLastWorkoutLog/observeWorkoutLog/getWorkoutLogs/getLastExerciseLog/getLastWeightsForExercise/getLastPerformanceForExercise/updateActiveSession/requestCancelSession/mutateActiveSession + defaults. Timer/prefill abstract bukan I/O/empty fallback. Hapus facade error traps, OnlineSessionStore.mutate dan SessionSnapshot.commandFor; SessionDomainAdapter hanya toDomain.
- Exact callsite search `\b(observeTemplates|observeTemplate|getTemplates|observeWorkoutLogs|observeLastWorkoutLog|observeWorkoutLog|getWorkoutLogs|getLastExerciseLog|getLastWeightsForExercise|getLastPerformanceForExercise|updateActiveSession|requestCancelSession|mutateActiveSession|commandFor)\(` app/src; bedakan fake/DAO. VM/service typed store; DemoSessionLifecycleTest/ActiveSessionEditingTest/RedesignNavigationTest setup typed IDs; RestNotificationInteractionTest template bounded gateway. In-process RestTimerEntryPoint.sessions(), bukan exported/debug intent.
- Hapus wiring/forwarding/error-trap/lambda-translation tests; pindah consumer owner/CAS/terminal invariants ke remote receipt/recovery tests, bukan repin expected-empty. Retire WorkoutRepositoryRoomFirstTest/local writer/facade tests. Ganti IMFITDatabaseMigrationTest/OnlineRecoveryMigration tests dengan reader/installed-upgrade proof, bukan retain Room writer demi tests.
- Gradle lepas Room runtime/ktx/compiler/testing + room.schemaLocation; preserve KSP Hilt/WorkManager/host sqlite-jdbc. Lepas Room ProGuard/keep dan LocalSessionEntryPoint.database(); ports hanya auth/gateway/session/timer existing process.

### Langkah 6 — Backup, device fixtures, minified proof (Proposed; NOT_TESTED)

- backup_rules.xml serta cloud-backup/device-transfer data_extraction_rules.xml allowlist database `.` dan file `datastore/imfit_theme.preferences_pb`, `datastore/imfit_locale.preferences_pb`, `datastore/imfit_workout_preferences.preferences_pb`; DataStore bukan sharedpref. Tidak include root/file/sharedpref keseluruhan; token/timer/effects/sync/recovery acknowledgements tidak direstore authority. Legacy restore re-inventory/review+server dedup.
- RestTimerProductionReleaseTest fixture real local Auth+template/session RPC+confirmed store; warm-offline sesudah confirmation, bukan invalid JWT/DAO seed. Loopback host dan synthetic accounts only.
- tools/run_rest_timer_suite.py/rest_timer_regression.py pisah warm/warm-offline/cold-offline/cold-online; cold SIGKILL exact PID bukan force-stop; PID berubah+deadline lewat tanpa expiry commit/notification/audio, anchor recoverable. Cold-online verify→deadline asli; preserve emulator/non-owner guard, finally restore network/appops/doze; hapus analyzer cold oracle lama.
- **Baru** `app/src/androidTestProduction/java/com/diajarkoding/imfit/OnlineOnlyReadyTest.kt`: actual SDK/HTTP+UI login→routine→Start→input→complete→finish→summary→history/progress/profile, restart, response-loss resolve, account switch. Ports setup/observe saja; edit/complete/finish actual UI.
- **Baru** `app/src/androidTestProduction/java/com/diajarkoding/imfit/LegacyUpgradeReadyTest.kt`: same package/signature baseline6/7/9 dari schema exports dengan platform SQLite/disposable emulator; writer tutup lalu adb install -r target tanpa clear-data/uninstall. Unknown/WAL/restore/A-B fixture terpisah; bukan Room writer/perangkat berdata nyata.
- **Baru** `tools/run_online_only_ready.py`: adapter fixture APK/install/reverse/instrumentation/evidence, reuse guards/exact-PID/network restoration timer. CLI `--serial <emulator> --user <nonzero> --output <new-dir>`; refuse nonemulator, owner-user, existing nonfixture account/data, nonloopback, missing APK; timeout/error failure bukan skip-success.
- Temporary fixture Android app dari schema assets, plugin Android sama version catalog, compileSdk37/minSdk24, applicationId/signing internal sama, versionCode=1; target existing1 adb install -r. Synthetic rows platform SQLite bukan production copy/Room generator.
- Hilangkan dependency `/tmp/imfit-f1b-baseline-c0be6b8.dump`: platform-only schema dump lokal temporary, filter app-dependent auth trigger/storage policy TOC seperti runner existing, restore verification DB baru, replay empat baseline lalu synthetic fixture. Upgrade dari empat baseline; fresh seluruh source. Tidak drop/reset DB existing/publish secrets. Platform TOC/roles beda fail prerequisite konkret, bukan hosted dump atau guard longgar.
- Synthetic Auth API lokal mengikuti verify_local_transport, secrets temporary, cleanup exact fixture UUID/email saja. Native command nonzero gagal; tidak klaim suite tak dijalankan. Proof final sesudah clean cutover5; commands dan gate §7.

**Exit lokal:** semua gate §7 lulus pada source final; AC01–AC34 dan T01–T16 dipetakan evidence/N/A scope sah. Archive owner-accessible setelah explicit consent bukan synced/alasan delete. G4/G5/G6/release tidak selesai dari roadmap. Hosted deferred, audit live27 Oktober tetap historis; runtime prerequisite/gate blocked berarti **bukan Ready**.

## 5. File impact dan urutan pelepasan

| Existing file/area | Tindakan target | Syarat |
| --- | --- | --- |
| `domain/repository/WorkoutRepository.kt` | Hapus whole-snapshot/default API; finish return workout ID, timer/prefill abstract | Seluruh caller langkah3/5 dimigrasi |
| `data/repository/WorkoutRepositoryImpl.kt` | Hapus impl legacy; facade current adalah OnlineWorkoutRepository | Langkah5, bukan facade baru |
| `WorkoutTemplateRepository.kt`, `WorkoutLogRepository.kt` dalam `data/repository/` | Hapus legacy writer/DAO; recovery raw tidak reuse uploader | Langkah2/5 |
| `data/repository/ExerciseRepositoryImpl.kt` | Hapus interface/impl/binding; semua consumer bounded gateway | Exact reference audit langkah5 |
| `data/repository/WorkoutSessionManager.kt` | Hapus legacy manager, extend OnlineSessionStore existing | Langkah3/5 |
| `data/repository/AuthRepositoryImpl.kt` | Auth lifecycle + remote profile, tanpa user DAO/sync coordinator | Session fence dan logout diuji |
| `data/repository/AccountDataAccess.kt` | Adaptasi menjadi guard session-aware; jangan hapus ownership principle | Auth epoch + RLS |
| `data/remote/ImfitAggregateRemoteDataSource.kt`, `TemplateWriteRequest.kt` | Hapus datasource obsolete; preserve template request/exercise/receipt untuk exact replay | Langkah2/5 |
| `data/sync/RemoteWorkoutCoordinator.kt`, `RemoteSnapshotImporter.kt`, `WorkoutUploadSnapshot.kt`, `SyncPreferences.kt` | Lepas dari runtime baru, pindahkan recovery minimum bila masih perlu | Tidak ada consumer baru; G6 |
| `data/sync/SyncWorker.kt`, production `WorkManagerSyncScheduler.kt` | SyncWorker terminal retired-work handler tanpa I/O; hapus scheduler obsolete | Langkah5, WorkManager timer tetap |
| `data/repository/WorkoutLocalStore.kt`, `data/local/dao`, `entity`, `database` | Keluarkan dari business runtime; simpan reader recovery selama diperlukan | G6b sebelum menghapus dependency |
| `presentation/navigation/NavGraph.kt`, `RemoteRefreshViewModel.kt`, `ui/main/MainScreen.kt` | Hapus global full pull; initial load/query invalidation | G3 |
| `presentation/ui/home`, `exercise`, `progress`, `profile`, `workout` | Remote state, refresh/error/paging, confirmed mutation | Per-screen tests |
| `core/service/WorkoutService.kt`, `core/timer/RestTimerAndroidRuntime.kt`, `RestTimerSessionValidator.kt` | Snapshot hot path; cold remote recovery | G5 |
| `app/src/production/java/com/diajarkoding/imfit/di/DatabaseModule.kt`, `LocalSessionEntryPoint.kt`, `AppModule.kt` | Hapus DatabaseModule/database entrypoint; facade binding current tetap | Fixture remote langkah6 sebelum cutover5 |
| `app/src/demo/...` repository/DI dan test source sets | Fake remote semantics untuk preview/UI deterministic | Jangan menyamakan demo dengan E2E |
| `app/build.gradle.kts`, `gradle/libs.versions.toml` | Paging existing tetap; lepas seluruh dependency/config Room, preserve KSP Hilt/WorkManager/sqlite-jdbc host | Langkah5 |
| `app/src/main/res/values/strings.xml`, `values-in/strings.xml` | Copy online-only baru; copy upload lama hanya recovery | ID/EN parity |
| `app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml` | Aturan auth/timer/legacy restore eksplisit | Privacy dan recovery tests |
| `supabase/migrations`, `supabase/tests` | Migration additive dan fixture kontrak baru | Deployment terpisah dan diotorisasi |

RemotePage/RemoteError/QueryKey dan discovery **sudah existing** pada foundation F2, bukan usulan baru. Kontrak recovery baru ditandai Proposed di §4. Copy upload lama hanya boleh tetap pada recovery yang memang menjelaskan data historis.

## 6. Recovery protocol operasional

### 6.1 Preflight sebelum membuat sesi remote baru

Preflight guard **sudah dipasang** (§7.9); target berikut menyelesaikan readiness/resolution-nya (Proposed; NOT_TESTED), bukan memasang Start seam dari nol:

1. Ambil owner dan auth epoch current; cek database legacy yang ada tanpa membuat database workout baru. Fresh install harus benar-benar teridentifikasi, bukan disimpulkan dari read failure.
2. Inventory versi/schema dan data owner tersebut, termasuk local active session, pending completion, write intent outcome unknown, serta dependensi. Record milik owner lain tidak boleh dibaca sebagai data current owner atau diunggah dengan tokennya.
3. Readiness RPC authoritative bounded + targeted original request IDs checkpoint/intent, bukan full-pull receipt historis. Timeout/error/malformed/checkpoint error → Unknown; has_more_blockers → RecoveryRequired.
4. Local active → consent non-resumable archive; remote ACTIVE/PAUSED resume/cancel await receipt; expired versioned AcknowledgeExpiry; server legacy KEEP_ARCHIVED. Konflik/completion ambigu/provenance unknown menahan create; jangan membuat ID baru atau silently discard.
5. Data pending historis lain hanya boleh dibiarkan terpisah setelah diklasifikasi tidak berkonflik dan jalur recovery/checkpoint-nya tetap tersedia. Ini bukan pengakuan synced. Tidak perlu memaksa penyelesaian data akun lain untuk memakai akun current.
6. Simpan checkpoint inventory/resolution transisi secara durable. Setelah recovery atau owner berubah, validasi ulang preflight sebelum create. Server tetap menegakkan unique active session dan idempotency; checkpoint client bukan pengganti constraint server.

Hasil guard existing `Ready`, `RecoveryRequired`, `Unknown`; hanya Ready owner/epoch current dapat create sesudah second inventory. Resolution baru berlaku pada source+aggregate+dependency fingerprint cocok; checkpoint bukan durable authority. Restore/changed source harus review ulang; ini tidak membuktikan device lain tidak punya draft lokal.

### 6.2 Penanganan kategori dan checkpoint

| Kategori lama | Aksi aman | Yang dilarang |
| --- | --- | --- |
| Synced cache terbukti | Pertahankan sumber; klasifikasi dependency/evidence tanpa writer runtime | Menganggap seluruh row tanpa metadata synced atau delete otomatis |
| Pending CREATE workout | Import idempotent aggregate lengkap sesudah intent manual | Membuat UUID baru pada setiap retry |
| Pending UPDATE/DELETE | Review terhadap versi remote dan operasi asli | Upsert snapshot lama menimpa data baru |
| Template intent response hilang | Resolve receipt ID lama lalu validasi payload/owner | Menyimpulkan timeout berarti belum tersimpan |
| REVIEW template/profile | Review terpisah, pertahankan provenance | Retry workout dianggap menyelesaikan semua legacy |
| Active local JSON | Consent “Simpan sebagai arsip lama, tidak dapat dilanjutkan”; fingerprint-bound non-resumable archive, sumber tetap | Import draft/provenance/IDs tebakan atau keepHistory generik mengakui active |
| Corrupt/unknown schema | Blokir migrasi destruktif, berikan jalur support/recovery | Destructive fallback atau menebak schema8 |

Checkpoint baru memakai `AWAITING_REVIEW,PREPARED,SUBMITTED,CONFIRMED,CONFLICT,BLOCKED`, **tanpa cleaned/auto-delete**. CONFIRMED hanya receipt tervalidasi atau explicit fingerprint-bound archive decision sesuai operasi. Crash melanjutkan immutable original request; unknown diselesaikan sebelum review baru. Checkpoint transisi bukan durable draft/queue runtime.

## 7. Matriks verifikasi dan acceptance release

| ID uji | Lapisan | Skenario wajib / hasil yang diharapkan |
| --- | --- | --- |
| T01 | Unit/controller | Refresh menginvalidasi append lama; stale response tidak mengubah generation baru |
| T02 | Unit/Paging | Query/filter/sort/akun berubah: items, cursor, errors, snapshot lama terisolasi |
| T03 | Unit/auth | A→B→A, logout saat request, token refresh: tidak ada data bocor atau false success |
| T04 | Unit/UI | Empty/initial error tetap dapat pull refresh; append gagal tidak retry loop |
| T05 | SQL/page | Timestamp sama, insert baru, delete, cursor tamper/query mismatch, duplicate IDs, halaman kosong/terakhir |
| T06 | SQL/aggregate | Statistik/date markers benar untuk dataset >500 record dan timezone/periode lintas hari |
| T07 | SQL/session | Dua update expected version sama: satu sukses, satu conflict tanpa lost update |
| T08 | SQL/finish | Dua finish paralel/request berbeda tetap satu workout; finish/cancel memiliki satu terminal winner |
| T09 | Transport/E2E | Server commit lalu response putus: replay/resolve mengembalikan receipt yang sama |
| T10 | Process death | Restart memuat server-confirmed draft; unconfirmed input tidak dijanjikan pulih; no duplicate create |
| T11 | UI/picker | Selection tetap ada di luar page/filter aktif; exclusion/return result session-scoped |
| T12 | Timer/device | Nol request per detik; background/deep sleep/permission/reboot/clock shift/notification actions benar |
| T13 | Upgrade/device | Versi historis yang didukung, REVIEW, JSON draft, akun A/B, crash checkpoint, skip bridge, backup restore; preflight unknown/conflict memblokir create RPC, owner resolved dapat lanjut tanpa membuang pending owner lain |
| T14 | SQL/security | Cross-owner, forged receipt, direct REST bypass, old RPC bypass, unauthenticated access ditolak sesuai fase |
| T15 | UI | ID/EN, light/dark, font besar, narrow width, TalkBack, retry accessible |
| T16 | Build/integration | Demo + production compile/unit, production release minified, instrumentation dan E2E pada environment terisolasi |

### 7.1 Matriks final wajib — seluruh gate NOT_TESTED

T01–T16 tetap wajib. Matriks berikut menambah hasil observable, bukan mengganti/mengurangi AC01–AC34. Semua PASS lama hanya berlaku pada source/scope historisnya.

| Gate | Input/scenario | Hasil wajib |
| --- | --- | --- |
| Sesi AC12–19 | Receipt edit A ditahan, ketik B; complete <400ms | B tetap; HTTP edit receipt→complete latest version→rest; Dirty/Saving/Saved acknowledgement, tanpa navigasi prematur |
| Unknown/CAS | Commit lalu proxy drop response; resolve/retry; dua writer CAS sama | Receipt/workout sama, satu terminal winner; NOT_FOUND tak membuat request baru; conflict locked sampai review; stale replay clear pending tanpa downgrade |
| Registration gate | Template unknown lalu session command belum dispatch | Tidak pending session palsu; original intent tetap target resolution |
| Recovery AC23–24/28–33 | 6/7/9, A/B, local-active+remote-active, intent response loss | Explicit review, zero create unknown/conflict; A resolved lanjut, B tak berubah; raw owner-accessible |
| Import | Same source/request; new request same source; payload berubah/child collision | Original UUID/aggregate, satu workout/mapping; no overwrite; rollback parent/children/receipt/registry; >2 decimal exact/null provenance |
| Bounded Start | 101 dan1000 receipt terminal; blocker di luar preview20 | Tanpa blocker can_create true; hidden blocker false; tidak scan receipt; acknowledged expiry tidak block |
| Crash/restore | Kill setelah PREPARED/SUBMITTED/commit-before-response/response-before-checkpoint; restore DB checkpoint hilang | IDs/body replay sama, no duplicate; changed source review; unknown original resolve dahulu |
| Upgrade/read-only | Same-package6/7/9, skip bridge, committed WAL, corrupt/4/8/unknown | Sumber tidak migrate/delete; fresh tak membuat imfit_database; unknown bukan fresh; logout tidak prune |
| Timer AC20–21 | Warm/offline, cold offline/online, sleep/doze/reboot/clock shift, permission denied/revoked, notification failure | Zero transport 10k peek/ticks +60s running observation; cold offline no completion/audio; online verify+deadline asli; failed cancel tak stop-as-success |
| Profil | Omitted/null, wrong-owner/zero-row, upload/PATCH gagal, cancellation, A→B→A | Success hanya returned row; null clear; gagal/cancel tak cache/publish akun baru; canonical path/consistent placeholder |
| Reads AC01–11/22/26–27 | >500 history, search/filter/append race, retained routes, finish/profile/template save | Stats/markers bukan loaded-page; scoped refresh, no global full-pull; stale account/response tak bocor |
| Surface AC15/T15 | ID/EN, light/dark, 320dp, font100/150/200%, IME, TalkBack | Status/retry/review terbaca, ≥48dp touch, CTA/input tidak terpotong; screenshots+interaction actual bukan source-only |
| Security AC25/34 | Authenticated direct/old RPC/private/foreign child/forged receipt; anon | Denied, profile tiga writable columns saja; database/API lokal only |

### 7.2 Prasyarat dan command final — planned, NOT_TESTED

Root repository; daemon JDK sesuai `gradle/gradle-daemon-jvm.properties`, compile37/min24, Node≥20, CLI pinned **2.116.0**, Python≥3.11, Docker project IMFIT-MOBILE/container supabase_db_IMFIT-MOBILE. Tidak install/upgrade global tanpa kebutuhan konkret. Disposable emulator **API30/34/37**: UI utama34, timer/permissions/backup boundary30/37. Physical vendor-specific di luar emulator proof dicatat, bukan tested.

Pertahankan unrelated local.properties: salin original aman lalu set SUPABASE_URL=`http://127.0.0.1:54321`, SUPABASE_ANON_KEY dari status lokal tanpa print token/key lain; restore original setelah build/evidence. Tambahkan hanya loopback127.0.0.1 ke network_security_config.xml; Ktor HTTPS/loopback validation tetap (10.0.2.2 tidak dilonggarkan). `adb -s $env:ANDROID_SERIAL reverse tcp:54321 tcp:54321`.

Langkah6 harus mengganti dump historis dengan platform-only local bootstrap sebelum migration paths proof. Final runner wajib legacy SQL+lifecycle fixture+security; runner existing final belum menjalankan lifecycle fixture, jadi staged replay tetap wajib. Jangan baseline/compatibility runner pada tip final.

```powershell
$ErrorActionPreference = 'Stop'
$env:PYTHONDONTWRITEBYTECODE = '1'
if ($env:DOCKER_HOST) { throw 'Refusing Docker override' }
python -B -c "import sys; sys.path.insert(0,'supabase/tests'); from verify_local_baseline import local_sql; local_sql()"
.\node_modules\.bin\supabase.cmd migration up --local
python -B -m unittest discover -s supabase/tests -p test_verify_local_baseline.py -v
python -B supabase/tests/verify_local_backend.py --phase final
python -B supabase/tests/verify_local_transport.py --phase final
python -B supabase/tests/verify_local_migration_paths.py
.\gradlew.bat :app:testDemoDebugUnitTest :app:testProductionDebugUnitTest --console=plain
.\gradlew.bat :app:assembleDemoDebug :app:assembleProductionDebug :app:assembleDemoDebugAndroidTest --console=plain
.\gradlew.bat :app:assembleProductionRelease --console=plain
# Salin normal-release APK ke evidence sebelum test keep-delta build menimpanya.
.\gradlew.bat -PrestTestBuildType=release :app:assembleProductionRelease :app:assembleProductionReleaseAndroidTest --console=plain
python -B -m unittest discover -s tools -p test_rest_timer_regression.py -v
```

**Sesudah setiap native command**, cek `$LASTEXITCODE -ne 0` lalu throw sebelum berikutnya; `$ErrorActionPreference` saja tidak cukup. Pilih serial disposable aktual dari adb devices, set ANDROID_SERIAL; `adb -s <serial> shell pm create-user imfit-online-ready`, gunakan ID nonzero hasil aktual bukan hard-code. Install app/test hanya user fixture, harness mengurus reset terisolasi. Full demo connected pada emulator disposable terpisah.

```powershell
.\gradlew.bat :app:connectedDemoDebugAndroidTest --console=plain
python -B tools/run_online_only_ready.py --serial $env:ANDROID_SERIAL --user $fixtureUser --output "$env:TEMP\imfit-online-ready-$runId"
python -B tools/run_rest_timer_suite.py --serial $env:ANDROID_SERIAL --user $fixtureUser --output "$env:TEMP\imfit-rest-ready-$runId"
```

$fixtureUser adalah ID yang dibuat, $runId UUID baru; cek exit tiap command. Harness memasang production minified app/test sebelum timer runner. Normal release minified **tanpa test keep-delta** juga install user disposable dan visual Start/edit/finish/restart; jangan sebut instrumentation-tested bila hanya build.

Evidence pada direktori temp baru: SHA256 manifest source inputs, exact migration inventory/signatures, commands/variant/device/API, counts/failures/skips, screenshots, sanitized request trace tanpa token/PII, fixture registry/receipts. Tests permanen behavior/boundary/invariant nyata, bukan source-text/wiring tests; smoke sementara dibuang setelah evidence.

**Exit internal ready:** semua gate final lulus, demo+production unit/build, actual production HTTP/UI, installed upgrade/restore, timer cold/warm, normal+instrumented minified. Petakan semua34 AC dan T01–T16 ke evidence/N/A scope sah, bukan fake sebagai production proof. Docker/SDK/emulator unavailable: siapkan prerequisite aman, laporkan blocker tepat, jangan hosted/fake substitute. Publish signing/hosted policy/backup/cutoff gate terpisah.

Langkah0: integrator menjalankan script read-only pasangan fence, jumlah kolom tabel, link/anchor lokal, parity empat RPC, AC01–AC34 utuh dan label Proposed/source-verified/historical runtime/NOT_TESTED pada ketiga dokumen. Ini validasi dokumen, bukan exit implementasi atau proof gate runtime.

## 8. Rollout dan rollback

**Eksekusi current hanya lokal/internal**, tanpa rollout/deployment/cutoff project bersama `ltcxltahkrjnsjaxndsa`. Review hosted PostgreSQL/API/TLS, backup/rollback, minimum-client policy dan izin cutoff adalah gate release tersendiri/deferred, bukan pekerjaan yang diotorisasi sesi ini.

Untuk keputusan hosted di masa depan, additive → recovery-compatible client → scoped cohort → runtime clean cutover → lockdown hanya setelah compatibility policy server-enforced. Tidak dual-write/berpindah write authority di tengah sesi. Feature flag/capability negotiation tetap Proposed, bukan kontrak existing terverifikasi; jangan mengandalkan header client yang dapat diabaikan.

Rollback policy historis tetap: hentikan rollout/pembuatan sesi baru bila perlu tetapi izinkan recovery/finish sesi remote existing. Downgrade APK/Room tidak dijanjikan memulihkan state server. Forward-compatible migration, bukan destructive down migration; pending legacy tidak dihapus saat rollback.

## 9. Risiko dan keputusan terbuka

| Risiko/keputusan | Mitigasi / gate | Owner usulan |
| --- | --- | --- |
| Input unconfirmed hilang ketika proses mati | **Ditutup D01**: diterima; UI/error copy dan AC15/AC16/AC21 konsisten | Product, G0 |
| Deployment RPC template: live absent 27 Oktober 2026 | Deploy additive + SQL proof; capability check; jangan menganggap source sudah live | Backend, G1 |
| Request ID hilang setelah proses mati | Stable resource identity + server operation discovery; jangan mengandalkan VM saja | Backend/Android, G1/G4 |
| Expiry/cascade menghapus draft atau receipt | **D03** 30 hari/90 hari; ubah FK cascade; review sebelum purge | Backend/Product, G1 |
| `workout_sets` denormalisasi tidak konsisten dengan parent | Composite constraint/trigger + policy cross-owner test | Backend, G1 |
| Pengujian berisiko pada project bersama tanpa staging | **D06**: schema terisolasi/local, backup, persetujuan eksplisit | Backend/QA, G1 |
| Legacy A tertinggal saat pengguna memakai B | Inventory semua owner, recovery owner-only, no blanket database delete | Android, G6 |
| Statistik dan prefill mengalami regresi | Aggregation terpisah; provenance disimpan server; parity fixture | Backend/QA, G3/G4 |
| Durasi active vs history berbeda | Baseline history memakai end−start termasuk jeda; jangan diam-diam mengubah metrik saat migrasi | Product/QA, G0 |
| Paging tidak memenuhi exact refresh UX | Spike; pilih satu implementasi sebelum scale-out | Android/Product, G2 |
| Client lama melewati version/RPC | Compatibility window + server-enforced cutoff dan privilege tests | Backend/Release, G7 |
| Cold-start offline vs rest alarm | **D02**: timer menunggu verifikasi; tidak ada alarm completion cold-offline; remote session Unknown, tidak memalsukan absence | Android/Product, G5 |
| Online-only meningkatkan biaya/latensi | Bounded summaries, debounce, indexes, no per-tick request, payload budgets | Backend/Android |
| Auth catch menelan cancellation | Audit generic catch di AuthRepositoryImpl dan jalur terkait; rethrow cancellation | Android, G2 |

## 10. Evidence map dan confidence

Evidence E01–E23/L01–L09 berikut adalah **audit baseline historis**, Verified source/config/live metadata read-only dan bukan runtime. **Partial** berarti bukti dalam scope itu belum lengkap; **NOT_TESTED** hanya untuk lapisan yang disebut. Evidence lokal F1b dicatat terpisah sebagai LOCAL01 di bawah dan laporan §7.3; jangan menganggap semua runtime/device lulus karena SQL lokal lulus.

| Evidence | Claim / sumber | Jenis | Confidence |
| --- | --- | --- | --- |
| E01 | Production binding: `app/src/production/java/com/diajarkoding/imfit/di/AppModule.kt:37–63`; demo memiliki binding terpisah di `app/src/demo/java/com/diajarkoding/imfit/di/AppModule.kt` | code | Verified |
| E02 | Single app dan flavors: `settings.gradle.kts:25–26`, `app/build.gradle.kts:19–43,114–155`; dependency catalog `gradle/libs.versions.toml` | config | Verified |
| E03 | Facade routing `data/repository/WorkoutRepositoryImpl.kt:10–44`; sesi `WorkoutSessionManager.kt:10–24` | code | Verified |
| E04 | `data/repository/ExerciseRepositoryImpl.kt:21–47`; observer/read memakai DAO | code | Verified |
| E05 | `data/sync/RemoteWorkoutCoordinator.kt:129–150,303–479`; page 500, child reads, importer | code | Verified |
| E06 | `data/repository/WorkoutTemplateRepository.kt:86–144`; request persistence/receipt validation | code | Verified |
| E07 | `supabase/migrations/20261025000100_template_revision_writes.sql:3–43,45–168` | code | Verified source; **live absent 27 Okt 2026** |
| E08 | `data/repository/WorkoutLocalStore.kt:355–465,477–585`; local session dan finish transaction | code | Verified |
| E09 | `data/sync/RemoteWorkoutCoordinator.kt:183–213`; `data/sync/SyncWorker.kt:10–17`; production `WorkManagerSyncScheduler.kt:14–20` | code | Verified |
| E10 | `supabase/migrations/20260828130114_imfit_schema_baseline.sql:120–133` | code + live metadata | Verified source; live table confirmed 27 Okt 2026 |
| E11 | `supabase/migrations/20260828130154_imfit_backend_hardening.sql:43–63,263–278,490–675` | code + live metadata | Verified source; live RLS/privileges confirmed 27 Okt 2026 |
| E12 | `presentation/ui/workout/ActiveWorkoutViewModel.kt:164–209`; `core/service/WorkoutService.kt:89–164`; `core/timer/RestTimerAndroidRuntime.kt:80–97`; `RestTimerSessionValidator.kt:21–36` | code | Verified |
| E13 | `presentation/navigation/NavGraph.kt:47–48`; `RemoteRefreshViewModel.kt:24–35`; `presentation/ui/main/MainScreen.kt:94` | code | Verified |
| E14 | `presentation/ui/exercise/ExerciseBrowserViewModel.kt:33–67`; `ExerciseSelectionViewModel.kt:19–36,61–90` | code | Verified |
| E15 | `presentation/ui/progress/ProgressViewModel.kt:83–90`; `ProgressPresentation.kt:17–28` | code | Verified |
| E16 | `data/repository/AuthRepositoryImpl.kt:278–365,379–407`; `presentation/ui/profile/ProfileScreen.kt:36–54` | code | Verified |
| E17 | `supabase/tests/template_revision_writes.sql`; deployment/testing limitations tercatat pada `docs/online_first.md:94–107` | test, existing-doc | Partial; tidak dieksekusi pada audit ini |
| E18 | `data/repository/AccountDataAccess.kt:13–25`; credential fence coordinator `130–140` | code | Verified |
| E19 | `data/local/database/IMFITDatabase.kt:26–55`; `OnlineRecoveryMigration.kt:6–47`; `LogoutRecoveryStore.kt:7–34`; production `di/DatabaseModule.kt:22–33` | code | Verified |
| E20 | `domain/model/ExerciseInputDefaults.kt:5–15`; `WorkoutLocalStore.kt:773–781`; baseline SQL exercise_logs dan RPC tidak membawa input_defaults | code | Verified source gap |
| E21 | `app/src/main/AndroidManifest.xml:25–35`; `app/src/main/res/xml/backup_rules.xml`; `data_extraction_rules.xml` | manifest, config | Verified |
| E22 | `app/src/test/java/com/diajarkoding/imfit/data/repository/TemplateSaveRecoveryTest.kt`, `WorkoutAccountIsolationTest.kt`; `app/src/test/java/com/diajarkoding/imfit/core/timer/RestTimerSessionValidatorTest.kt`; `app/src/testProduction/java/com/diajarkoding/imfit/data/sync/ManualUploadSchedulingTest.kt` | test | Verified fixture source; hasil run NOT_TESTED |
| E23 | `presentation/navigation/Routes.kt:21–50`; `NavGraph.kt:146–265`; `presentation/ui/progress/WorkoutHistoryDetailScreen.kt:43–79` | code | Verified |
| L01 | Supabase MCP read-only audit, project `ltcxltahkrjnsjaxndsa`, 27 Oktober 2026 09:01 UTC; role `supabase_read_only_user`; `transaction_read_only=on`; tanpa row bisnis/RPC aplikasi | live metadata | Verified snapshot |
| L02 | Applied migrations: `20260828130114_imfit_schema_baseline`, `20260828130132_imfit_catalog_seed`, `20260828130154_imfit_backend_hardening` | live metadata | Verified |
| L03 | Public routines: `handle_new_user`, `update_updated_at_column`, `upsert_template_aggregate`, `upsert_workout_aggregate`, `validate_owned_template_reference`; 16 nama RPC target absen | live metadata | Verified |
| L04 | 9 tabel public, seluruhnya RLS-enabled; 29 policy; `anon` tanpa table grant | live metadata | Verified |
| L05 | `authenticated` direct writes: `profiles` INSERT/UPDATE; `workout_templates`, `template_exercises`, `workout_logs`, `exercise_logs`, `workout_sets`, `active_sessions` INSERT/UPDATE/DELETE | live metadata | Verified |
| L06 | `active_sessions` tanpa `version`/`status`; `UNIQUE(user_id)`; FK `template_id ON DELETE CASCADE`; `expires_at` ada; tidak ada `pg_cron` | live metadata | Verified |
| L07 | Branch/Room: `online-only` 9 (fb96784, 7→9 via `OnlineRecoveryMigration`), `origin/development` 7 (5129c53), `development` lokal 6 (a31fbf7), `main`/`offline-first` 4; semua `versionCode=1`, `versionName=1.0`; migration SQL per branch berbeda | git refs | Verified source; device upgrade NOT_TESTED |
| L08 | Constraint/index: `workout_sets` memiliki FK terpisah ke `exercise_logs` dan `workout_logs`, tanpa composite consistency; index history/catalog/template tersedia | live metadata | Verified |
| L09 | `supabase/tests/full_online_only_readiness_audit.sql` — audit metadata read-only, bukan migration/attack test | audit script | Verified source; query setara dijalankan read-only via MCP, belum menjadi gate integration |
| LOCAL01 | Delapan follow-up migration dan runner SQL/Auth/PostgREST/replay/upgrade di `supabase/tests`; command/fingerprint scope di laporan §7.3 | local runtime + source | Verified lokal 28 Oktober; bukan backend utama/Android/CI |
| LOCAL02 | Lima file foundation dan lima host-test di `data/remote/online`, dependency Paging/MockEngine; laporan §7.4 | host unit/spike + debug build | 37 foundation tests per flavor; total 275 production/281 demo dan dua assemble debug PASS; bukan UI/device/SDK lifecycle/production |

Coverage E/L adalah audit baseline, bukan sertifikasi seluruh project. Runtime lokal historis F1b/G3/F6.1/F4 dan instrumentation terarah tercatat pada laporan §7.3–§7.9; tidak boleh ditulis seolah seluruh Android belum pernah diuji. Yang tetap **NOT_TESTED untuk target final**: semua gate langkah1–6 pada source final, full production SDK/HTTP/UI, installed upgrade/restore, accessibility matrix dan normal/instrumented minified proof. Hosted deployment tetap deferred; query plan 10k row sintetis bukan performa production.

## 11. Referensi dan changelog

- [Feature spec dan acceptance criteria](full-online-only-feature-spec.md).
- [Laporan implementasi berkelanjutan](full-online-only-implementation-report.md): status G0/G1, evidence, blocker, dan langkah berikutnya.
- [Riwayat online-first](../online_first.md): bagian integrasi terbaru lebih relevan daripada rencana offline-first historis di bagian bawahnya.
- [Template dokumentasi fitur](../features/template-feature-documentation.md).
- [Paging overview](https://developer.android.com/topic/libraries/architecture/paging/v3-overview), diperiksa 26 Oktober 2026: mendukung network PagingSource, Flow/Pager, cache memori, dan Compose; bukan bukti dependency sudah terpasang.

| Versi | Tanggal | Perubahan |
| --- | --- | --- |
| 1.0 | 2026-10-26 | Audit baseline source, analisis gap, urutan implementasi, recovery/rollout gates, dan evidence map |
| 1.1 | 2026-10-27 | Integrasi review pengguna: matriks kesiapan, lima blocker, prioritas/dependensi, serta legacy preflight wajib sebelum create sesi remote |
| 1.2 | 2026-10-27 | G0 D01–D06, audit metadata Supabase read-only (3 migration, 16 RPC absen, direct-write exposure), matriks backend live, inventaris Room 9/6/7/4, dan koreksi status B01–B05 |
| 1.3 | 2026-10-28 | F1a kontrak desain selesai setelah keputusan K01–K05; F1b backend lokal dipisahkan, F2/G2 tetap Android; baseline c0be6b8, CLI pinned dipulihkan, prasyarat Docker/MCP dan verification gates tanpa deployment |
| 1.4 | 2026-10-28 | Exit F1b lokal: 12 migration, 16 RPC, 57 transport checks, SQL/security/replay/upgrade/restore dan lint/advisors; batas produksi/Android dan gate F2/G2 dipertahankan |
| 1.5 | 2026-10-28 | Implementasi foundation F2 dan gate G2: typed wire/RPC, auth epoch, CAS/receipt/unknown resolution, fake API, Paging common spike terisolasi; runtime activation tetap F3/F4 |
| 1.6 | 2026-10-28 | Runtime readers G3, bounded Home RPC lokal, template CAS bridge dan verification gates (§7.5 laporan) |
| 1.7 | 2026-10-28 | Perbaikan stable-key viewport/refresh re-hint; 297 + 303 unit tests dan 7 acceptance demo terarah PASS pada emulator macOS; matriks lengkap dan G4–G6 tetap terbuka |
| 1.8 | 2026-10-28 | Kelanjutan state/error lintas reader, picker context/owner guard dan editor saveable-key isolation; regression/lifecycle dan acceptance compact di laporan §7.7 |
| 1.9 | 2026-10-28 | Foundation F6.1 inventory/read-only schema, owner/epoch preflight dan evidence checkpoint teruji lokal; review/import/Start integration dan G6a/G6b tetap terbuka (§7.8 laporan) |
| 2.0 | 2026-10-29 | Tanggal system clock; metadata/source current disejajarkan §7.9 tanpa rerun PASS; roadmap F4–F7 diganti langkah1–6 lengkap dengan empat RPC Proposed, selected recovery, dirty/debounce AC13/15, profil, clean cutover, backup/fixtures dan final NOT_TESTED matrix/commands. Target internal ready tanpa hosted deployment/cutoff; baseline/live audit dan fase lama tetap historis |
| 2.1 | 2026-10-29 | Review manifest dan persiapan commit host-only; perbaikan terarah serta evidence dirty snapshot di laporan §7.11. Roadmap step 0 historis dibedakan dari source implementasi yang kini ada; gate backend/device dan izin mutasi Git tetap terbuka |
