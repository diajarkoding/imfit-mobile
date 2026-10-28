# IMFIT Full Online-Only — Plan Implementasi

## 0. Metadata dan batas audit

| Item | Nilai |
| --- | --- |
| Status | F1a/F1b lokal dan F2/G2 foundation selesai/teruji; runtime layar/DI tidak diganti; deployment/cutoff utama belum diizinkan |
| Tanggal dibuat / diperbarui | 26 / 28 Oktober 2026 |
| Versi dokumen | 1.5 |
| Baseline commit | `fb96784d3164a700250e7032d6565779d288029d` |
| Baseline eksekusi F1a/F1b | `online-only@c0be6b823c6e8350a4a0f1c157d239dcdfd28e57`; tidak mengubah baseline audit historis |
| Scope | Modul `:app`, source bersama `main`, binding `production`, pembanding `demo`, migration SQL, test terkait, dan metadata Supabase live |
| Pasangan dokumen | [Feature spec](full-online-only-feature-spec.md) · [Laporan implementasi berkelanjutan](full-online-only-implementation-report.md) |
| Metode | Audit baseline source/live metadata 27 Oktober + perbandingan git ref; F1b menambahkan verifikasi runtime SQL/Auth/HTTP hanya pada Docker lokal 28 Oktober (laporan §7.3) |

**Tidak dilakukan pada audit ini:** build/Gradle test, instalasi perangkat, DDL/DML, pemanggilan RPC aplikasi, pembacaan row bisnis, deployment, commit, atau push. Audit live memakai role `supabase_read_only_user` dengan `transaction_read_only=on`. Keberadaan SQL di repository tidak membuktikan deployment backend; sebaliknya, ketiadaan RPC pada schema live adalah bukti deployment untuk snapshot 27 Oktober 2026, bukan bukti perilaku runtime. Hasil test historis dalam dokumen lama tidak dianggap hasil pengujian audit ini.

Path Kotlin pendek dalam dokumen ini relatif terhadap `app/src/main/java/com/diajarkoding/imfit/`. Path flavor, konfigurasi, dan SQL ditulis dari root repository. Nama komponen baru selalu ditandai **usulan**, bukan klaim source yang sudah ada.

## 1. Kesimpulan audit

Migrasi layak, tetapi bukan sekadar mengganti DAO dengan HTTP. Baseline adalah **online-first hybrid dengan proyeksi Room, sesi lokal, dan recovery manual**, bukan full online-only dan bukan lagi autosync offline-first lama.

Keputusan utama yang direkomendasikan:

1. Supabase menjadi satu-satunya penyimpanan permanen data bisnis baru.
2. Repository remote mengembalikan data langsung; UI memakai StateFlow dan PagingData dalam memori.
3. Paging 3 network-only untuk daftar panjang; tidak memakai RemoteMediator.
4. Active workout dipindah ke sesi server dengan version, command idempotent, serta finish atomik.
5. Ticker timer tetap lokal; pembacaan setiap detik tidak boleh berubah menjadi network polling.
6. Recovery legacy adalah jalur transisi terisolasi, bukan antrean offline baru.
7. Room baru boleh dilepas dari runtime umum setelah semua konsumennya dialihkan. Database lama tidak boleh dibuang selama ada data belum terselesaikan, termasuk milik akun lain.

**Status blocker durability:** keputusan G0 D01 menerima kehilangan input yang belum dikonfirmasi server saat process death. Target tetap Full Online-Only tanpa durable local draft atau antrean offline baru. Release masih terblokir oleh backend final, migrasi legacy, pengamanan write server, dan recovery/idempotency (B02–B05), bukan oleh durability input.

### 1.1 Status kesiapan setelah review 27 Oktober 2026

**Snapshot 27 Oktober: G0 dan audit live G1 selesai; implementasi penuh/production belum siap.** Saat audit itu runtime/RLS attack/integration belum dijalankan. Pembaruan 28 Oktober: backend lokal F1b terimplementasi/teruji (bagian F1b dan laporan §7.3), tetapi Android dan deployment utama tetap belum siap. Hasil lokal tidak mengubah snapshot live historis berikut.

| Area | Kesiapan konsep/persiapan | Kesiapan implementasi target |
| --- | --- | --- |
| Full Online-Only | Arah arsitektur layak; D01 menerima kehilangan input unconfirmed | Runtime hybrid belum dimigrasikan |
| Coroutines dan StateFlow | Fondasi sudah digunakan; dapat dipakai untuk remote contracts | Request lifecycle/error/isolation target perlu implementasi dan test |
| Pull-to-Refresh | Pola UX dapat dikerjakan terhadap fake | Belum diimplementasikan/diverifikasi sebagai target |
| Paging 3 + cursor | Spike dan contract tests dapat dimulai | Belum tersedia pada baseline; wajib verifikasi race/dedup/retry |
| Kontrak backend final | Audit live selesai; 3 migration deployed; 16 RPC target absen | **Belum siap**: kontrak sesi/read/recovery belum ada dan write protection belum final |
| Active Workout remote | D01/D02 ditetapkan; desain versioned session dapat dikembangkan terisolasi | **Belum siap**: runtime masih bergantung pada Room dan `active_sessions` belum versioned |
| Legacy recovery | D04/D05 menetapkan scope Room 9/6/7 dan review sebelum Start | **Belum siap**: upgrade perangkat dan checkpoint belum diuji |
| Implementasi penuh | Pekerjaan modular nonblocking dapat berjalan | **Terblokir** oleh B02–B05 sampai release gates ditutup |

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

### 1.3 Yang boleh dimulai dan yang belum boleh diaktifkan

- **Sudah selesai:** keputusan G0 D01–D06, audit metadata Supabase read-only, inventaris branch/Room, dan script audit readiness.
- **Dapat dimulai:** rancangan migration additive dan kontrak RPC/receipt, fixture SQL terisolasi, inventory/protokol legacy, repository contracts/fake transport, Paging spike, refresh UI, cancellation/account-switch tests.
- **Menunggu kontrak fitur terkait:** integrasi remote read/write pada environment terisolasi; fitur lain tidak harus menunggu seluruh endpoint jika kontrak dan dependensi fiturnya sudah terbukti.
- **Belum boleh:** menerapkan migration ke project bersama tanpa backup/isolasi dan review; mengganti sesi pengguna menjadi remote-only tanpa G1 dan recovery preflight; mengklaim input tersimpan sebelum receipt; menghapus Room/pending; atau mengaktifkan final rollout tanpa verification gates.
- **Bukan blocker pemilihan arsitektur:** detail Paging, format error/cursor/receipt, validasi lintas Android/backend, statistik/timezone, timer cold recovery, dan empty/error UX dapat disempurnakan selama implementasi. Namun, cacat integritas/keamanan pada detail tersebut tetap blocker rilis fitur terkait, bukan alasan menunda pengujiannya sesudah release.

## 2. Hasil deepscan dan analisis gap

Prioritas: **P0** integritas/keamanan/release blocker; **P1** fungsi utama dan skalabilitas; **P2** optimasi setelah kontrak benar. Nomor E merujuk evidence map bagian 10.

| ID | Area | Kondisi source sekarang | Gap ke target | Prioritas / bukti |
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

- Manager sesi (refactor `WorkoutSessionManager`, bukan nama class yang sudah remote) menyimpan confirmed snapshot di memori untuk UI dan service.
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

Status mengikuti checkbox dan bukti per fase (F1 lokal selesai; F2/G2 lihat laporan §7.4); checklist yang belum ditandai tetap roadmap. Peran PIC adalah usulan, bukan penugasan orang. Estimasi kalender menunggu environment uji dan kapasitas tim.

Label **F0–F7 adalah fase**, sedangkan **P0/P1/P2 adalah prioritas**. Urutan tabel berikut adalah fokus pengerjaan; dependensi, bukan nomor urut atau prioritas saja, menentukan kapan pekerjaan dapat berjalan.

| Urutan | Pekerjaan | Prioritas | Fase / dependensi |
| --- | --- | --- | --- |
| 1 | Finalisasi keputusan produk dan kontrak backend | P0 | F0, keputusan G0 |
| 2 | Implementasi dan verifikasi backend Supabase | P0 | F1; deployment terpisah dan diotorisasi |
| 3 | Persiapan legacy recovery dan session-start preflight | P0 | Mulai pada F0; implementasi awal F6 sebelum aktivasi F4 |
| 4 | Remote Data Layer, Paging, Refresh | P1 | F2; fake/spike boleh sebelum backend siap |
| 5 | Active Workout remote + timer adapter | P0 | F4–F5; integrasi membutuhkan kontrak sesi dan preflight |
| 6 | Migrasi fitur baca/tulis dan data lama | P0 | F3/F4/F6 per irisan; aktivasi mengikuti gate masing-masing |
| 7 | Cleanup Room dan komponen sync lama | P1 | Sesudah G6 dan consumer runtime dipindahkan |
| 8 | Pengujian final dan staged rollout | P0 | F7; testing juga berjalan pada setiap fase |

F6 bukan pekerjaan yang baru dimulai setelah F5. **Desain, inventory, dan preflight legacy harus siap sebelum sesi remote pengguna diaktifkan**; transfer/cleanup lengkap dapat dilanjutkan bertahap dengan data tetap terlindungi.

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

Perintah verifikasi ulang pada database lokal yang sudah mencapai seluruh 12 migration:

```powershell
$env:PYTHONDONTWRITEBYTECODE='1'
python -m unittest discover -s supabase/tests -p test_verify_local_baseline.py -v
python supabase/tests/verify_local_backend.py --phase final
python supabase/tests/verify_local_transport.py --phase final
python supabase/tests/verify_local_migration_paths.py
```

Runner migration paths membutuhkan backup lokal pra-additive `/tmp/imfit-f1b-baseline-c0be6b8.dump`; jangan menggantinya dengan dump/data/credential production. Runner baseline/phase compatibility hanya untuk snapshot migration yang sesuai, bukan dijalankan sembarang pada tip final. Semua runner menolak target Docker non-local; verification databases baru dipertahankan tanpa reset/drop. Tidak ada jaminan reproducible CI sampai environment/backup/toolchain CI disiapkan.

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

- [ ] Pada setiap layar sediakan loading awal, legitimate empty, no-results, initial error, refresh error dengan data lama, dan append error bila relevan.
- [ ] Refresh tersedia di empty/error melalui scroll container full-size; bukan hanya saat list berisi.
- [ ] Hapus global full refresh di `NavGraph`, `MainScreen`, dan `RemoteRefreshViewModel` setelah semua consumer terkait memiliki load/invalidation sendiri.
- [ ] Simpan state memori saat kembali ke route yang ViewModel-nya masih hidup; recomposition tidak memicu fetch.
- [ ] Pertahankan desain IMFIT dan navigasi existing; route baru harus diberi nama/entry/caller/test secara eksplisit.

**Exit G3:** layar membaca remote langsung, statistik benar untuk dataset > satu halaman, dan tidak ada hidden full snapshot pull pada route/tab/foreground.

### F4 — Remote write dan sesi aktif

PIC: Android + Backend + QA. Tergantung G1–G2; sebelum menonaktifkan local session. Aktivasi start sesi remote juga mensyaratkan preflight legacy bagian 6; implementasi dengan fixture dapat dikerjakan lebih awal.

- [ ] Refactor `WorkoutSessionManager` menjadi owner confirmed snapshot application-scoped dan serializer command remote.
- [ ] Ganti kontrak lambda `mutateActiveSession(transform)` dengan typed commands atau adapter sementara yang menghasilkan command versioned; jangan mengirim lambda/whole stale snapshot tanpa CAS.
- [ ] Start hanya menampilkan sesi berhasil setelah server acknowledgement; existing session mengarah ke recovery, bukan overwrite diam-diam.
- [ ] Sebelum mengirim create sesi baru, jalankan preflight legacy untuk owner current; blokir start jika inventory/conflict/outcome ambigu belum diselesaikan. Akun lain tetap terisolasi dan datanya tidak dibuang.
- [ ] Implementasikan dirty-vs-confirmed state, debounce/flush, request replay, conflict reload, dan offline lock.
- [ ] Finish/cancel tidak bernavigasi atau menghapus sesi berdasarkan local success. Timeout hasil ambigu memicu resolve/retry, bukan membuat workout/sesi baru.
- [ ] Template write tetap menggunakan revision dan removed IDs eksplisit; pindahkan durable intent recovery ke server discovery sebelum Room intent dilepas.
- [ ] Profile update harus memeriksa hasil server dan owner; bedakan field omitted dari explicit null. Avatar storage gagal tidak boleh memalsukan keberhasilan update metadata.
- [ ] Invalidate hanya query terkait setelah receipt; invalidation dibedakan dari data mutation/retry upload.

**Exit G4:** sesi dapat dipulihkan pada device lain/proses baru dari server; repeat finish menghasilkan workout yang sama; draft belum acknowledged tidak dilabeli Saved.

### F5 — Timer, service, notification decoupling

PIC: Android + QA. Wajib selesai bersama F4, bukan cleanup opsional.

- [ ] Ticker `ActiveWorkoutViewModel` dan `WorkoutService` hanya membaca confirmed snapshot memori/clock lokal; **nol request periodik per detik**.
- [ ] Pisahkan remote verification dari `getActiveSessionForTimer()`/validator hot path; ubah contract timeout/null agar network failure menjadi Unknown, bukan sesi tidak ada.
- [ ] Remote reload dilakukan pada cold recovery, explicit reload, dan resume/reconnect sesuai kebijakan terbatas; bukan setiap reconcile alarm.
- [ ] Pertahankan alarm generation, monotonic deadline, boot identity, skip/extend/reduce, sound/vibration, dan permission handling.
- [ ] Pause/resume/cancel dari notification melewati serializer yang sama dengan UI, dengan error teramati; tidak boleh optimistic success yang tidak confirmed.
- [ ] Cold process tanpa network tidak merekonstruksi workout dari timer store; timer-only behavior harus sesuai feature spec.
- [ ] Jangan menghapus WorkManager secara keseluruhan: recovery timer masih membutuhkannya.

**Exit G5:** regression timer lintas API/background/process death lulus; tidak ada Room atau network per-tick dependency; action notification tidak dapat melewati session version guard.

### F6 — Legacy recovery dan cleanup bertahap

PIC: Android + Backend + QA + Product. Desain dimulai pada F0; inventory/preflight tersedia sebelum aktivasi start sesi F4; transfer/cleanup mengikuti checkpoint upgrade.

- [ ] Pisahkan adapter recovery yang hanya membaca format lama dan mencatat checkpoint transisi; jangan menulis workout baru ke database legacy.
- [ ] Jalankan inventory owner-scoped untuk pending logs/children, template/profile REVIEW, template intents, active JSON, dan dependensi katalog/template.
- [ ] Sediakan start preflight yang fail-closed bila pembacaan legacy gagal/unknown atau konflik belum terselesaikan; uji bahwa create RPC belum dipanggil dalam kondisi tersebut.
- [ ] Tampilkan review dan persetujuan manual. Kebijakan retry legacy yang sudah ada tidak berubah menjadi reconnect/autoupload.
- [ ] Persist ID mapping/checkpoint recovery transisi secara durable agar crash tidak mengulang import sebagai data baru; hanya receipt valid yang mengakui item.
- [ ] Tangani akun A/B di satu instalasi: akun B tidak membaca/mengunggah milik A; pending A tetap ada sesudah logout dan login B.
- [ ] Jika local active draft bertabrakan dengan remote active session, lakukan review eksplisit; jangan merge JSON atau replace otomatis.
- [ ] Import workout lama menjaga ID dan aggregate lengkap. Jika remote ID sama tetapi payload berbeda, masuk review/conflict; jangan overwrite via upsert lama tanpa pembuktian.
- [ ] Intent template ambigu diperiksa melalui receipt/discovery; tidak membuat template baru dengan ID baru secara otomatis.
- [ ] REVIEW/provenance unknown tidak otomatis dianggap synced, tidak otomatis diunggah, dan tidak dihapus hanya karena tidak tampak di remote page.
- [ ] Hapus cache/checkpoint/data lama hanya setelah verifikasi seluruh owner/aggregate atau explicit discard yang disetujui pengguna untuk data tersebut.
- [ ] Audit backup/restore agar database lama yang kembali tidak memicu import ganda.

**Exit G6a per instalasi:** semua item legacy terselesaikan atau tetap diarsip recovery yang dapat diakses owner; runtime baru tidak membuat pending lokal.

**Exit G6b untuk penghapusan dependency Room:** installer yang melompati bridge release tetap memiliki jalur recovery. Jika inventory versi/data belum dapat dijamin selesai, pertahankan pembaca legacy terisolasi. Target runtime online-only tidak berarti boleh membuang data upgrade demi menghapus dependency.

### F7 — Release verification dan penghentian compatibility

- [ ] Jalankan matriks bagian 7 pada source stabil dan environment uji terisolasi dengan contract version yang dicatat.
- [ ] Verifikasi kedua flavor tetap compile; demo memakai fake remote contracts, bukan bukti network/RLS production.
- [ ] Bangun production release minified dan uji recovery/notification path yang memakai entry point.
- [ ] Rollout bertahap setelah backend additive siap; pantau error, conflict, unknown outcome, duplicate attempt, dan recovery unresolved.
- [ ] Cabut akses unversioned hanya sesudah minimum-client policy berlaku; uji bypass sebagai authenticated user.
- [ ] Retire worker stub/Room binding/dead resource hanya setelah gate dependency/recovery dipenuhi.

**Exit G7:** seluruh release blocker ditutup dengan evidence; persentase rollout dan ambang rollback disepakati pemilik release.

## 5. File impact dan urutan pelepasan

| Existing file/area | Tindakan target | Syarat |
| --- | --- | --- |
| `domain/repository/WorkoutRepository.kt` | Pisah kontrak page/detail/session commands; facade kompatibilitas sementara | Semua caller dipetakan |
| `data/repository/WorkoutRepositoryImpl.kt` | Facade remote atau split binding repository | Tidak lagi mengarah ke local store |
| `WorkoutTemplateRepository.kt`, `WorkoutLogRepository.kt` dalam `data/repository/` | Return remote DTO/domain; hapus DAO/import orchestration | Remote read/write dan recovery tersedia |
| `data/repository/ExerciseRepositoryImpl.kt` | Network paging/search/detail | Picker mendukung selection lintas page |
| `data/repository/WorkoutSessionManager.kt` | Remote session state owner + command serializer | G4–G5 |
| `data/repository/AuthRepositoryImpl.kt` | Auth lifecycle + remote profile, tanpa user DAO/sync coordinator | Session fence dan logout diuji |
| `data/repository/AccountDataAccess.kt` | Adaptasi menjadi guard session-aware; jangan hapus ownership principle | Auth epoch + RLS |
| `data/remote/ImfitAggregateRemoteDataSource.kt`, `TemplateWriteRequest.kt` | Pertahankan kontrak template; tambah datasource/RPC target yang dipisah per concern | Backend capability verified |
| `data/sync/RemoteWorkoutCoordinator.kt`, `RemoteSnapshotImporter.kt`, `WorkoutUploadSnapshot.kt`, `SyncPreferences.kt` | Lepas dari runtime baru, pindahkan recovery minimum bila masih perlu | Tidak ada consumer baru; G6 |
| `data/sync/SyncWorker.kt`, production `WorkManagerSyncScheduler.kt` | Compatibility retirement, bukan autosync baru | Pekerjaan versi lama dibatalkan/di-drain aman |
| `data/repository/WorkoutLocalStore.kt`, `data/local/dao`, `entity`, `database` | Keluarkan dari business runtime; simpan reader recovery selama diperlukan | G6b sebelum menghapus dependency |
| `presentation/navigation/NavGraph.kt`, `RemoteRefreshViewModel.kt`, `ui/main/MainScreen.kt` | Hapus global full pull; initial load/query invalidation | G3 |
| `presentation/ui/home`, `exercise`, `progress`, `profile`, `workout` | Remote state, refresh/error/paging, confirmed mutation | Per-screen tests |
| `core/service/WorkoutService.kt`, `core/timer/RestTimerAndroidRuntime.kt`, `RestTimerSessionValidator.kt` | Snapshot hot path; cold remote recovery | G5 |
| `app/src/production/java/com/diajarkoding/imfit/di/DatabaseModule.kt`, `LocalSessionEntryPoint.kt`, `AppModule.kt` | Ubah DI; entrypoint database hanya legacy/test jika perlu | Instrumentation production disesuaikan |
| `app/src/demo/...` repository/DI dan test source sets | Fake remote semantics untuk preview/UI deterministic | Jangan menyamakan demo dengan E2E |
| `app/build.gradle.kts`, `gradle/libs.versions.toml` | Tambah Paging; Room/KSP Room dilepas terakhir; WorkManager tetap bila timer memakai | Dependency audit |
| `app/src/main/res/values/strings.xml`, `values-in/strings.xml` | Copy online-only baru; copy upload lama hanya recovery | ID/EN parity |
| `app/src/main/res/xml/backup_rules.xml`, `data_extraction_rules.xml` | Aturan auth/timer/legacy restore eksplisit | Privacy dan recovery tests |
| `supabase/migrations`, `supabase/tests` | Migration additive dan fixture kontrak baru | Deployment terpisah dan diotorisasi |

Komponen/model baru seperti `RemotePage`, `RemoteError`, `QueryKey`, dan receipt discovery adalah **usulan**. Jangan menganggap nama/path implementasinya sudah final. `SyncIndicator`/`SyncProgressDialog` baru boleh dihapus setelah semua caller dan legacy recovery UX selesai dialihkan.

## 6. Recovery protocol operasional

### 6.1 Preflight sebelum membuat sesi remote baru

Target berikut wajib untuk instalasi upgrade, bukan perilaku baseline yang sudah diimplementasikan:

1. Ambil owner dan auth epoch current; cek database legacy yang ada tanpa membuat database workout baru. Fresh install harus benar-benar teridentifikasi, bukan disimpulkan dari read failure.
2. Inventory versi/schema dan data owner tersebut, termasuk local active session, pending completion, write intent outcome unknown, serta dependensi. Record milik owner lain tidak boleh dibaca sebagai data current owner atau diunggah dengan tokennya.
3. Baca status sesi/operasi remote untuk membandingkan kandidat konflik. Timeout/error berarti **Unknown**, bukan tidak ada sesi.
4. Jika ada sesi lokal/remote bertabrakan, completion ambigu, atau provenance/schema yang belum dapat diklasifikasi aman, tahan create dan arahkan ke recovery/review. Selesaikan konflik dengan receipt/import confirmed atau explicit discard; jangan menyembunyikannya dengan membuat session ID baru.
5. Data pending historis lain hanya boleh dibiarkan terpisah setelah diklasifikasi tidak berkonflik dan jalur recovery/checkpoint-nya tetap tersedia. Ini bukan pengakuan synced. Tidak perlu memaksa penyelesaian data akun lain untuk memakai akun current.
6. Simpan checkpoint inventory/resolution transisi secara durable. Setelah recovery atau owner berubah, validasi ulang preflight sebelum create. Server tetap menegakkan unique active session dan idempotency; checkpoint client bukan pengganti constraint server.

Hasil preflight usulan: `Ready`, `RecoveryRequired`, atau `Unknown/Blocked`. Hanya `Ready` pada owner/epoch current yang dapat melanjutkan create RPC. Status bersih yang pernah dicatat tidak boleh melewati inventory baru bila ada data lama yang kembali melalui restore. Mekanisme preflight ini melindungi upgrade instalasi tersebut; ia tidak membuktikan device lain tidak menyimpan draft lokal yang belum pernah dikirim.

### 6.2 Penanganan kategori dan checkpoint

| Kategori lama | Aksi aman | Yang dilarang |
| --- | --- | --- |
| Synced cache terbukti | Verifikasi kebutuhan dependency lalu buang cache | Menganggap seluruh row tanpa metadata pasti synced |
| Pending CREATE workout | Import idempotent aggregate lengkap sesudah intent manual | Membuat UUID baru pada setiap retry |
| Pending UPDATE/DELETE | Review terhadap versi remote dan operasi asli | Upsert snapshot lama menimpa data baru |
| Template intent response hilang | Resolve receipt ID lama lalu validasi payload/owner | Menyimpulkan timeout berarti belum tersimpan |
| REVIEW template/profile | Review terpisah, pertahankan provenance | Retry workout dianggap menyelesaikan semua legacy |
| Active local JSON | Import draft versioned atau explicit discard setelah review | Menghapus draft saat membuka build baru |
| Corrupt/unknown schema | Blokir migrasi destruktif, berikan jalur support/recovery | Destructive fallback atau menebak schema8 |

Checkpoint recovery memiliki state usulan `discovered → awaiting_review → submitted → confirmed → cleaned`, plus `conflict/blocked`. Transisi `confirmed` memerlukan receipt sesuai owner/ID/payload. Crash pada setiap batas harus dapat dilanjutkan tanpa duplikasi. Checkpoint bukan izin menambah antrean workout offline untuk runtime baru.

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

Contoh perintah **untuk dijalankan nanti**, bukan hasil audit ini:

```powershell
.\gradlew.bat :app:testDemoDebugUnitTest :app:testProductionDebugUnitTest
.\gradlew.bat :app:assembleDemoDebug :app:assembleProductionDebug :app:assembleProductionRelease
```

Instrumentation, SQL tests, dan E2E membutuhkan environment terisolasi yang disetujui. Catat command, variant, device/API, schema version, commit, jumlah test, PASS/FAIL/BLOCKED, serta lokasi evidence. Mock/host PASS tidak membuktikan SQL transaction, RLS, Android migration, atau visual approval.

## 8. Rollout dan rollback

1. **Backend additive dahulu.** Deploy kontrak baru tanpa langsung mencabut client lama.
2. **Bridge release recovery.** Data lama tetap terlindungi; sesi baru hanya memakai satu write authority yang dipilih. Tidak ada dual-write Room+server sebagai protokol baru.
3. **Cohort internal/environment terisolasi lalu rollout bertahap.** Mode per sesi dipin saat start; jangan mengganti local/remote di tengah sesi karena flag berubah.
4. **Final online-only runtime.** Nonaktifkan general Room/sync dependencies setelah consumer dialihkan; reader legacy dapat tetap dikemas untuk pengguna yang melewati bridge.
5. **Contract lockdown.** Minimum-client cutoff, direct-write restriction, dan retirement legacy hanya sesudah compatibility gate.

Feature flag/capability negotiation adalah **usulan, belum ada kontrak flag online-only yang diverifikasi**. Server version gate harus benar-benar enforced, bukan hanya mengandalkan header/client yang dapat diabaikan.

Rollback berarti hentikan rollout atau nonaktifkan pembuatan sesi baru, tetapi **tetap izinkan recovery/finish sesi remote yang sudah dibuat**. Jangan menjanjikan downgrade APK/Room dapat memulihkan state yang hanya ada di server. Migration schema bersifat forward-compatible; destructive down migration bukan rollback default. Pending legacy tidak dihapus saat rollout dihentikan.

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

Coverage audit baseline mencakup data/repository/sync, DI, route/ViewModel, timer dan selected tests; bukan sertifikasi seluruh project. F1b kemudian membuktikan SQL/Auth/HTTP/concurrency/security lokal. UI visual, Android/device/Room upgrade, seluruh test individual, distribusi versi terpasang, benchmark production serta deployment utama tetap **Partial/NOT_TESTED**. Query-plan history pada 10k row sintetis bukan performa production.

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
