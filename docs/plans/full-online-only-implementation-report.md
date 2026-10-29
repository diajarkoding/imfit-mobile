# IMFIT Full Online-Only — Laporan Implementasi

## 0. Metadata dan aturan pemakaian

| Item | Nilai |
| --- | --- |
| Status | **MVP internal preview PASS; T0–T5 selesai, APK diserahkan**. Final: 417 production/424 demo unit dan 3 smoke UI/HTTP nyata PASS pada Pixel 5 API34 existing. Production/upgrade legacy/deployment tetap deferred; [bukti §7.13](#713-mvp-internal-preview-t5) |
| Versi laporan | 2.3 |
| Tanggal diperbarui | 29 Oktober 2026 (tanggal eksekusi dari jam sistem) |
| Baseline commit | `fb96784d3164a700250e7032d6565779d288029d` (`online-only`) |
| Baseline eksekusi | `c0be6b823c6e8350a4a0f1c157d239dcdfd28e57` — branch `online-only`, awal iterasi clean; tidak fetch/commit/push |
| Project Supabase | `ltcxltahkrjnsjaxndsa` — database bersama development/testing/production |
| Dokumen acuan | [tasks.md — checklist utama](tasks.md) · [Feature Spec v2.3](full-online-only-feature-spec.md) · [Plan Implementasi v2.3](full-online-only-implementation-plan.md) |
| Metode audit live | Supabase MCP, project-scoped, `read_only=true`, `features=database`; role `supabase_read_only_user`; `transaction_read_only=on` |
| Batas | §7.1–§7.12 mempertahankan histori sesuai scope/izin saat itu; hasil final MVP lokal ada di §7.13. Bukan clean-commit/CI, publish signing, upgrade legacy atau deployment/cutoff utama |
| Identitas snapshot backend teruji | HEAD c0be6b8 + dirty working tree; 27 input backend, fingerprint SHA256 `abc44bea440f906335864a654e1ac588f8553d35fac2f74d6737a6f9bed40994` (§7.3); bukan verified commit/CI |

**Aturan:** file ini adalah **satu-satunya laporan implementasi** dan diperbarui di tempat. Jangan membuat laporan baru setiap iterasi. Klaim harus berlabel **Verified** dengan scope source/live metadata/runtime lokal yang jelas, **Proposed** (desain), atau **NOT_TESTED**. Keberadaan SQL atau PASS lokal bukan bukti deployment, CI, atau perangkat.

**Pemisahan bukti:** **Verified — source** adalah hasil inspeksi pada snapshot yang dicatat, bukan runtime; **PASS historis** hanya berlaku untuk input/lingkungan pada iterasi tersebut; **Proposed** adalah kontrak/desain, bukan bukti kemampuan. Target release-ready internal lokal pada v2.0–v2.1 adalah historis dan digantikan **MVP internal preview fresh install dengan Supabase lokal/terisolasi**. Gate lengkap §7.10 yang belum dibuktikan tetap NOT_TESTED sebagai backlog production/legacy, bukan semuanya blocker preview.

**Histori checkpoint ada di §7.11–§7.12; milestone T0–T5 ada di §1/tasks.md dan bukti final §7.13.** Remaining gaps audit step 0 §7.10 mendahului implementasi tambahan, bukan inventaris checkout terkini. T0–T5 selesai dalam scope internal preview; gate produksi/legacy yang belum dibuktikan tetap backlog, bukan ikut PASS atau dibuka otomatis.

**Hierarki aktif:** tasks.md = pekerjaan sekarang; Feature Spec = kontrak/kebutuhan; Plan = desain/backlog; Report ini = bukti/blocker. Otorisasi handoff §7.12 dan batas host-only §7.11 adalah histori untuk scope saat itu, bukan izin Git/deployment baru atau larangan permanen atas task MVP lokal. Supabase utama, cutoff dan penghapusan data lama tetap memerlukan persetujuan eksplisit.

## 1. Ringkasan status

| Area | Status tercatat | Bukti / pekerjaan tersisa |
| --- | --- | --- |
| Kontrak | **Tetap berlaku** | D01–D06/K01–K05 dan 34 AC utuh; pemisahan acceptance MVP vs production ada di Feature Spec §2 |
| Backend | **T1 PASS lokal pada source checkpoint 5372f21** | 14 migration/21 RPC signature dan grant cocok; 35 pemeriksaan Auth/PostgREST alur MVP/RLS/finish-response-loss PASS. Recovery readiness/page teruji untuk akun fresh; full import/upgrade legacy tetap deferred |
| Android existing | **T2/T3 integrasi preview dan T4 cutover PASS** | Dua defect diperbaiki; facade/store/guard existing dipertahankan. Tidak ditemukan Room writer/queue bisnis produksi tersisa; Room transitif WorkManager untuk timer tetap dipertahankan |
| Pengujian host | **T5 full regression 417 production/424 demo PASS** | Satu run pada source final dibekukan, nol failure/error/skip. T2/T3 51 dan T4 37 adalah test terarah sebelumnya; §7.11 tetap historis |
| Preview end-to-end/APK | **MVP internal preview PASS; APK tersedia** | T5: 3 smoke UI/HTTP final, restart dan critical unknown/account switch; APK signature/hash verified dan identik dengan yang dipasang/diuji. Artefak/config/batas di §7.13 |
| Production/upgrade legacy | **Deferred; bukan selesai** | Installed upgrade/restore/crash, matriks perangkat/minified lengkap dan hosted rollout tetap backlog Plan; bukan blocker fresh-install preview kecuali safety/alur MVP terdampak |

**Milestone T0 — 29 Oktober 2026, PASS audit singkat:** `online-only@5372f210b26b3ec9f846171cbbfd63e81f4fa482`; ref cache origin sama (0/0), tanpa fetch. Perubahan awal tiga dokumen modified + tasks.md untracked dipertahankan. Source berisi 14 migration, 17 RPC inti + empat recovery; facade/store/guard dan caller Finish berbasis receipt sudah terhubung. Pencarian terarah tidak menemukan Room/DAO runtime atau dependency Room aktif; schema 6/7/9 dan legacy reader tetap ada. Ini bukti inspeksi, bukan SQL/HTTP/UI PASS.

**Milestone T1 — 29 Oktober 2026, PASS backend lokal:** stack existing pada `colima-imfit-online-verify` sehat; default context tidak diubah, perintah memakai `DOCKER_CONTEXT=colima-imfit-online-verify`. Setelah backup lokal, `node_modules/.bin/supabase migration up --local` menerapkan hanya migration existing `20261029000100_online_only_legacy_recovery.sql` (13 → 14 applied). Metadata read-only: seluruh 21 RPC signature/grant hadir, RLS public/private aktif, no direct business writers, profil hanya tiga writable columns. Advisors lokal sebelum/sesudah: exit 0, kosong pada threshold warn/error.

**Bukti T1:** smoke sementara memakai helper fixture/proxy existing, API `http://127.0.0.1:54321`, dua akun sintetis, **35 pemeriksaan PASS** melalui password login → katalog/template → create/edit/complete → Finish → Summary/History/Progress/read ulang. RLS/cross-owner mutation/detail/receipt ditolak atau owner-hidden. Satu response-loss nyata setelah Finish upstream 200, discovery/exact retry dan request ID baru tetap menghasilkan satu workout; query linkage database mengonfirmasi satu log. Cleanup hanya akun fixture exact-ID/email yang baru dibuat, bukan data lama. Tidak mengulang full concurrency/10k-row/replay suite.

**Artefak T1:** `/private/var/folders/yv/171k2lgd0jdg4y0jclvxjtzw0000gn/T/opencode/imfit-t1-30l7k7al/` berisi `mvp-smoke-evidence.json`, `readiness-audit.json`, `migration-versions.json`, `backup-evidence.json`; backup `local-before-recovery-postgres.dump` mode 0600 di luar Git, TOC valid, **restore NOT_TESTED**. Backup awal sebagai supabase_admin gagal; ulang sebagai postgres sukses, arsip parsial tidak digunakan. CLI 2.116.0/PostgreSQL 17.6 existing dipertahankan, bukan rekomendasi hosted production.

**Milestone T2/T3 — 29 Oktober 2026, PASS integrasi preview:** `OnlineRpc.kt` menerima exercise_id text katalog dan tetap memvalidasi UUID aggregate. `OnlineSessionStore.reload` sukses melepas verification lock, tetapi conflict/draft tetap memerlukan explicit Use server/Reapply; failed reload tetap locked. Dua defect direproduksi sebagai dua test FAIL sebelum fix, kemudian 35 test terarah PASS; gabungan final **51 unit production PASS**. Runtime tambahan hanya tag Summary pada layar aktif; fixture memperbarui selector/navigasi aktual, bukan bypass guard atau session manager baru.

**Bukti UI T3:** hanya **Pixel_5_API_34 existing (`emulator-5554`, API 34)**, user fixture 10, productionDebug terisolasi `com.diajarkoding.imfit.modaldiagnostic` dengan flag existing `-PmodalDiagnostic=true`. **4/4 instrumentation PASS, nol skip:** login → Home create routine → catalog picker → confirmed Start → pause/resume → edit/complete → Skip rest → Finish/Summary/History/Progress/Profile; completed workout tetap ada setelah proses dihentikan dan dibuka lagi; dua client HTTP memicu CAS conflict lalu kedua aksi review UI bekerja; response-loss Finish diselesaikan melalui Resolve UI dan A→B→A tidak membocorkan state/data. Trace memuat tepat dua Finish untuk dua alur dan satu committed response drop; backend mengonfirmasi dua workout unik.

**Evidence T2/T3:** `/private/var/folders/yv/171k2lgd0jdg4y0jclvxjtzw0000gn/T/opencode/imfit-t2-t3-9c0cylud/`: `device-dkz3uy5f/result.json` + empat log instrumentation, `targeted-unit-evidence.json`, `sources.json`, `t3-build.log`. APK pengujian terisolasi `app/build/outputs/apk/production/debug/app-production-debug.apk`, SHA256 `56fda795603d4edc9f63c52a98e7235b07606cbacea2b4b1611420af3a006af4`; ini bukan penyerahan APK final T5. URL/key lokal hanya sementara saat build; local.properties asli dipulihkan byte-for-byte. User emulator kembali 0, reverse sementara dihapus; akun/package fixture exact milik run dibersihkan tanpa mengganti aplikasi/data existing.

**Failure/batas tetap tercatat:** run offline awal gagal artifact Room transitive WorkManager belum cached, lalu dependency existing di-resolve tanpa ubah versi; bukan writer bisnis Room IMFIT. Run minified instrumentation gagal `NoSuchMethodError mutableIntObjectMapOf` pada Compose test; deferred, bukan PASS dan bukan blocker productionDebug preview. Method JUnit non-void dan selector/tag Summary/Profile stale diperbaiki sebelum final 4 PASS. P2 fixture timer, full cold/warm timer perangkat, upgrade/restore legacy, full regression dan release-minified tetap belum dibuktikan. Hot path 10.000 peek tanpa request dan unknown/identity locks teruji unit, bukan klaim matriks timer perangkat lengkap.

**Milestone T4 — 29 Oktober 2026, PASS tanpa source deletion baru:** pemeriksaan caller/DI terarah mengonfirmasi cutover existing: production auth/workout/read gateway tidak membawa Room/DAO/importer atau sync queue bisnis. SyncWorker terminal no-op tetap diperlukan untuk request lama; legacy reader OPEN_READONLY, review/checkpoint dan schema 6/7/9 tetap utuh. DependencyInsight membuktikan Room 2.7.0 hanya transitif WorkManager 2.11.2/Hilt Work yang dipakai recovery timer, bukan writer bisnis IMFIT; tidak dihapus atau di-exclude. Home memakai bounded template/Home summary, Progress aggregate/markers per periode, Profile dedicated remote state tanpa full history pull.

**Bukti T4:** 37 unit production PASS (G3RemoteReadTest 13, OnlineMutationInvalidationTest 5, LegacySqlInventoryTest 17, LegacyIndependentProfileTest 2), nol failure/error/skip; command dependencyInsight + targeted test exit 0, compile/Hilt production PASS. Source/dependency/API dan schema tidak diubah; fingerprint source/SHA256 APK cocok dengan build dan proof UI T3, tanpa build APK baru atau full regression. Evidence di `/private/var/folders/yv/171k2lgd0jdg4y0jclvxjtzw0000gn/T/opencode/imfit-t4-dxycpxfr/`: `t4-verification.log`, `targeted-tests.json`, `preservation-evidence.json`. Tidak ada backend/device run atau akses database/WAL pengguna pada T4.

**Kesimpulan aktif:** **T5 selesai, MVP internal preview PASS dan APK diserahkan** pada §7.13. Tidak ada blocker preview tersisa dalam scope yang diuji. Berhenti memperluas scope; production/upgrade/minified dan deployment/koneksi klien luar tetap deferred. Tidak ada hosted deployment/cutoff, reset/purge, penghapusan data lama atau commit/push.

## 2. Keputusan G0 yang sudah final

| ID | Keputusan | Dampak |
| --- | --- | --- |
| D01 | Kehilangan input belum confirmed saat process death **diterima**; target tetap Full Online-Only | Tidak ada durable local draft/offline queue baru; recovery hanya snapshot confirmed |
| D02 | Timer cold-offline **menunggu verifikasi** sesi | Tidak ada alarm completion/business action cold-offline yang mengklaim sesi aktif |
| D03 | Active draft expired setelah **30 hari tanpa aktivitas**; receipt lengkap **minimal 90 hari** | Expiry masuk `EXPIRED`/review, bukan silent purge; cleanup butuh policy dan persetujuan |
| D04 | Legacy tidak semua build: Room **9** baseline; Room **6/7** bridge/review; schema lain blocked | Deteksi dari `user_version`/schema Room, bukan `versionName`/`versionCode` |
| D05 | Konflik sesi lokal–remote/hasil ambigu **direview sebelum Start** | Hanya preflight `Ready` owner/epoch current yang boleh create RPC |
| D06 | Project Supabase tunggal untuk development/testing/production; belum ada staging | Uji berisiko memakai schema terisolasi/local; reset/purge butuh persetujuan eksplisit |

## 3. Hasil audit backend live

Snapshot: **27 Oktober 2026 09:01 UTC**, role `supabase_read_only_user`, `transaction_read_only=on`. Tidak ada row bisnis yang dibaca.

### 3.1 Migration yang terpasang

| Version | Name |
| --- | --- |
| `20260828130114` | `imfit_schema_baseline` |
| `20260828130132` | `imfit_catalog_seed` |
| `20260828130154` | `imfit_backend_hardening` |

`20261025000100_template_revision_writes.sql` **ada di source `online-only`, tetapi tidak tercatat deployed**. Ini menjawab status `reported / TODO/VERIFY` sebelumnya untuk snapshot ini.

### 3.2 Objek dan proteksi dasar

| Pemeriksaan | Hasil live |
| --- | --- |
| Tabel `public` | 9 tabel; seluruhnya RLS-enabled |
| Policy `public` | 29 policy (owner-based untuk data pengguna; SELECT katalog untuk authenticated) |
| Routine `public` | 5: `handle_new_user`, `update_updated_at_column`, `upsert_template_aggregate`, `upsert_workout_aggregate`, `validate_owned_template_reference` |
| `anon` table grant | Tidak ada SELECT/INSERT/UPDATE/DELETE |
| `authenticated` direct DML | 7 tabel: `profiles` (INSERT/UPDATE), `workout_templates`, `template_exercises`, `workout_logs`, `exercise_logs`, `workout_sets`, `active_sessions` (INSERT/UPDATE/DELETE) |
| `active_sessions` | Ada `expires_at`, `session_data` JSONB, `UNIQUE(user_id)`; **tidak ada** `version`/`status`; FK template `ON DELETE CASCADE` |
| Template revision | Kolom `workout_templates.revision` **tidak ada**; tabel `template_write_receipts` **tidak ada** |
| Operation receipt | Tidak ada tabel/RPC receipt atau discovery |
| Scheduled cleanup | `pg_cron` tidak terpasang; expiry belum dijalankan otomatis |

### 3.3 RPC target yang absen

Seluruh 16 nama berikut tidak ditemukan pada schema live:

`save_template_revision`, `get_workout_history_page`, `get_exercise_catalog_page`, `get_template_page`, `get_workout_detail`, `get_template_detail`, `get_progress_summary`, `get_workout_date_markers`, `get_exercise_prefill`, `create_active_session`, `get_active_session`, `update_active_session`, `cancel_active_session`, `finish_active_session`, `get_operation_status`, `list_recent_operations`.

`upsert_template_aggregate` dan `upsert_workout_aggregate` tersedia, tetapi bukan kontrak revision/receipt atau finish-active-session yang dibutuhkan target.

### 3.4 Temuan berprioritas

| ID | Temuan | Prioritas | Bukti | Tindakan |
| --- | --- | --- | --- | --- |
| F01 | RPC revision/receipt template dan kolom revision tidak ada live | P0 | L02–L03 | Migration additive + fixture SQL terisolasi |
| F02 | `active_sessions` belum versioned/statused; belum ada CAS/receipt/unique session→workout | P0 | L06 | Migration lifecycle + RPC create/get/update/cancel/finish |
| F03 | `authenticated` masih dapat direct DML pada 7 tabel | P0 | L05 | Compatibility cutoff, lalu RPC-only dan bypass test |
| F04 | `workout_sets.workout_log_id`/`exercise_id` denormalisasi; RLS hanya memeriksa `exercise_log_id` | P0 | L08 | Composite constraint/trigger + policy konsisten + cross-owner test |
| F05 | FK template `ON DELETE CASCADE` dapat menghapus active session; `UNIQUE(user_id)` menghalangi terminal history | P0 | L06 | Ubah FK/unique, pertahankan snapshot/receipt |
| F06 | Read pagination/detail/progress/markers/prefill RPC belum ada | P1 | L03 | Implementasikan cursor/aggregate contracts |
| F07 | Expiry 30 hari/receipt 90 hari belum ditegakkan; tidak ada scheduler | P1 | L06 | Status/expiry review + job/endpoint; tanpa purge diam-diam |
| F08 | `versionName=1.0`/`versionCode=1` tidak membedakan Room 9/7/6/4 | P0 | L07 | Deteksi schema aktual; upgrade Room 9/6/7; schema lain blocked |
| F09 | `session_data` JSONB belum tervalidasi di server | P1 | L06 | Schema validation, ukuran payload, numeric bounds |

## 4. Inventaris branch, Room, dan migration

| Ref | Commit | App | Room | Room migration | Supabase migration di ref |
| --- | --- | --- | --- | --- | --- |
| `online-only` | `fb96784` | 1.0 / 1 | 9 | 4→5, 5→6, 6→7, 7→9 (`OnlineRecoveryMigration`) | 4 file, termasuk revision writes |
| `origin/development` | `5129c53` | 1.0 / 1 | 7 | 4→5, 5→6, 6→7 | 3 file Agustus |
| `development` (lokal) | `a31fbf7` | 1.0 / 1 | 6 | 4→5, 5→6 | Tidak ada |
| `main` | `d01a65f` | 1.0 / 1 | 4 | — | Tidak ada |
| `offline-first` | `046c3c4` | 1.0 / 1 | 4 | — | Tidak ada |

Catatan: schema JSON yang tersedia di `online-only` adalah 6, 7, dan 9; **schema 8 tidak ada**. Jalur 7→9 ditangani `OnlineRecoveryMigration`, yang mempertahankan tabel/JSON dan tidak memakai destructive fallback; jalur ini belum diuji pada perangkat. Room 4 dan schema 8 tidak boleh ditebak atau di-destructive-fallback. Tidak ada bukti runtime bahwa build tertentu pernah terpasang; D04 membatasi scope pada Room 9/6/7 dan mewajibkan review untuk schema lain. Snapshot ref diambil dari repository lokal **tanpa `git fetch` jaringan**, sehingga `origin/development` adalah ref cache terakhir, bukan bukti kondisi remote saat ini.

## 5. Status blocker

**Register release penuh historis, bukan daftar blocker MVP otomatis.** B03/upgrade ditunda untuk fresh-install preview tanpa menghapus guard/data legacy; B02/B04/B05 hanya menjadi blocker MVP sejauh endpoint, safety dan alur aktif terdampak. Blocker MVP aktual hasil T0 diringkas di §1/tasks.md; source presence tidak menutup gate.

| ID | Blocker | Status | Gate penutupan |
| --- | --- | --- | --- |
| B01 | Kehilangan input unconfirmed | **Ditutup D01** | UI/error copy dan AC15/16/21 konsisten |
| B02 | Backend final | **PASS lokal historis; gate final baru NOT_TESTED** — typed session/read DTO existing terintegrasi (§7.9); empat RPC recovery Proposed belum dibuktikan. Hosted tetap deferred | G1/release lokal: kontrak recovery + Android HTTP proof; hosted patch/deployment/cutoff memerlukan review/izin terpisah |
| B03 | Migrasi legacy | **Terbuka** — read-only fixture 9/6/7 historis §7.8 dan archive/UI existing; selected aggregate import, receipt resolution, mapping/checkpoint dan installed-data upgrade belum lengkap | G6a/G6b dan gate recovery/upgrade §7.10 |
| B04 | Write bypass | **Final-lockdown lokal PASS historis; perubahan baru NOT_TESTED** — grant/RLS empat RPC baru harus diuji ulang tanpa membuka RPC lama; main grants/cutoff tidak diubah | Security lokal final §7.10; hosted minimum-client/cutoff terpisah |
| B05 | Recovery/idempotency | **Parsial; terbuka** — backend CAS/replay/race/response-loss PASS historis dan writer sesi Android existing, tetapi registration boundary, stale replay, unknown resolution, dirty input dan timer final belum lengkap | G4/G5 dan gate unknown/CAS/timer §7.10 |

## 6. Checklist progres

**Snapshot progres fase lama/backlog release penuh.** Checklist aktif yang wajib dibaca/diperbarui setiap iterasi hanya [tasks.md](tasks.md). Item terbuka di bawah tidak otomatis menjadi prasyarat MVP dan beberapa mendahului implementasi §7.11–§7.12; pertahankan sebagai histori, jangan mengulang seluruh fase/audit untuk mencentangnya.

### G0 — keputusan dan audit

- [x] D01 durability online-only
- [x] D02 timer cold-offline menunggu verifikasi
- [x] D03 expiry 30 hari + receipt 90 hari
- [x] D04 scope legacy Room 9/6/7
- [x] D05 review konflik sebelum Start
- [x] D06 lingkungan project tunggal dan aturan uji terisolasi
- [x] Audit metadata Supabase read-only
- [x] Inventaris branch/Room/migration
- [x] Script audit readiness `supabase/tests/full_online_only_readiness_audit.sql`
- [x] Konfirmasi timezone/durasi history/precision volume (K01/K02, 28 Oktober)
- [x] Konfirmasi scope feed history/template list (K03, 28 Oktober)

### G1 — backend

- [x] Audit read-only schema/RPC/RLS/grants/migration
- [x] Pilih Supabase lokal + fixture sintetis tanpa deployment utama (instruksi pengguna 28 Oktober)
- [x] Perinci kontrak desain F1a untuk 16 RPC di Feature Spec existing
- [x] Konfirmasi K01–K05 dan detail kontrak; exit F1a desain, bukan proof runtime
- [x] Verifikasi CLI 2.116.0 runnable dan Docker Linux daemon 29.9.2 sebelum F1b (kelanjutan 28 Oktober)
- [x] Implementasi lokal additive/cutover bertahap sesuai scope pengguna; revision migration existing tidak diduplikasi
- [x] Migration revision existing + wrapper/global receipt/discovery lokal
- [x] Migration session version/status/receipt/unique link lokal
- [x] Perbaikan FK template, unique owner, dan composite consistency `workout_sets` lokal
- [x] Delapan RPC read/pagination/detail/progress/prefill lokal
- [x] RPC create/get/update/cancel/finish + operation discovery lokal
- [x] Compatibility residual risk dibuktikan lalu final-lockdown dan bypass test lokal lulus
- [x] Integration/concurrency/SQL-grant/RLS dan JWT HTTP test lokal
- [x] Backup/restore serta replay/upgrade baseline lokal sebelum/selama migration
- [ ] Review hosted patch version, backup/rollback, minimum-client cutoff, DTO Kotlin dan deployment utama (izin terpisah)

### G2 — Android

- [x] Remote repository contracts + fake transport
- [x] Paging 3/cursor spike
- [x] Pull-to-refresh/infinite scroll/error states untuk reader lokal yang dimigrasikan (§7.6–§7.7; batas gesture compact dicatat)
- [x] Cancellation dan account-switch tests (foundation; runtime G3 diverifikasi terpisah)
- [x] Foundation inventory/preflight legacy Room 9/6/7 (§7.8); guard Start kemudian terintegrasi di kedua flavor (§7.9, source-verified §7.10)
- [ ] Putus seluruh graph runtime dari Room (auth, exercise/coordinator/template guard masih membawa dependency); timer authoritative final belum selesai

### G3 — remote reads (PASS lokal; release terpisah)

- [x] Production/demo read gateway + auth epoch bridge tanpa profile sebagai sumber autentikasi
- [x] Catalog/picker remote Paging; selection/exclusion ID tidak dipruning oleh halaman/filter
- [x] History summary per tanggal + detail on-demand; agregat/marker terpisah dari loaded pages
- [x] Template Home paginated + detail/editor remote; CAS dari snapshot yang diedit, guard legacy fail-closed
- [x] Home last-workout all-time bounded RPC lokal; Profile dedicated remote state
- [x] Lepas global full-pull pada route/tab/foreground; tidak menambah route
- [x] Perbaiki viewport header/footer melalui stable keys; 5 host regression tests dan fixture Compose PASS (§7.6)
- [x] Tujuh acceptance demo terarah pada emulator API 34 macOS; termasuk editor/picker, history/detail, root/profile, ID/EN/light/dark, refresh/error (§7.6)
- [x] Matriks state/retry/refresh/race diverifikasi pada source final: host kedua flavor + 17 UI demo (§7.7)
- [ ] Production SDK/HTTP integration, manual TalkBack dan matriks release lintas perangkat/API/font yang belum tercakup
- [ ] Exit G4/G5 penuh setelah cutover existing §7.9: dirty/flush, typed calls, authoritative timer, production SDK/UI dan minified proof

### F6 — recovery legacy (parsial)

- [x] F6.1 standalone read-only reader, exact schema detection, owner-scoped metadata inventory
- [x] Foundation preflight Ready/RecoveryRequired/Unknown, discovery bounded, second inventory dan expected-epoch guard sebelum create
- [x] Atomic inventory-evidence checkpoint di no-backup storage; bukan izin Start atau acknowledgement import
- [x] Host SQL/protocol + Android synthetic file/WAL/corrupt/checkpoint verification (§7.8)
- [x] Layar recovery/inspect dan historical archive existing (source-verified §7.10); bukan selected-item import atau receipt acknowledgement
- [ ] Selected-item review/persetujuan, replay intent asli, local-active abandonment dan server legacy resolution receipt-backed
- [ ] Import idempotent dengan immutable submitted/confirmed mapping/checkpoint, installed-data upgrade/restore dan crash recovery
- [x] Guard preflight sudah dipakai Start runtime kedua flavor (§7.9); bukan izin menyatakan recovery selesai
- [ ] Ganti readiness loop seluruh receipt 90 hari/page 100 dengan blocker resource authoritative, tetap second inventory + expected epoch
- [ ] Exit G6a/G6b dan cleanup; tidak menghapus Room/data untuk melewati gate

### G4/G5 — sesi confirmed/dirty dan timer (belum selesai)

- [x] Facade remote-only dan application-scoped `OnlineSessionStore` terikat di kedua flavor; hasil unit/REST/debug/connected §7.9 adalah historis
- [ ] AC13/15: raw draft hanya memori, debounce 400 ms, flush sebelum dependent action, status Saved hanya receipt-confirmed
- [ ] Typed remote child IDs, registration callback sesudah ledger gate, resolve original request/epoch, stale replay tanpa downgrade dan explicit conflict review
- [ ] Finish navigasi memakai `receipt.workoutId` saja; detail summary terpisah; cancel/service stop hanya sesudah receipt/authoritative absence
- [ ] Effective ACTIVE/PAUSED tanpa review, cold remote verification, warm/cold-offline/online timer dan zero-I/O per-tick proof final

### G7 — internal release-ready lokal (NOT_TESTED)

- [ ] Profil authoritative omitted/null dan returned-row; auth/guards bebas Room; invalidasi scoped workout/profile
- [ ] Clean cutover semua caller/API/binding/test obsolete tanpa menghapus database legacy pengguna
- [ ] Backup database + tiga file DataStore allowlist; installed-upgrade/restore 6/7/9 dengan fixture same package/signature
- [ ] Unit/build final, actual production HTTP/UI, normal dan instrumented production minified; emulator API 30/34/37 dan AC01–AC34/T01–T16 dipetakan ke bukti
- [ ] Semua gate §7.10 lulus terhadap source final; hosted deployment/cutoff tetap deferred, bukan syarat yang diam-diam dianggap lulus

## 7. Perubahan yang sudah dilakukan

**Cara membaca §7.1–§7.12:** seluruh catatan iterasi, izin dan urutan eksekusi berikut dipertahankan sebagai histori. Kalimat seperti “belum punya consumer Start” di §7.8 disupersede §7.9, audit §7.10 mendahului tambahan §7.11–§7.12. Jangan memakai roadmap release lama sebagai urutan aktif atau PASS historis sebagai verifikasi ulang source final. Ringkasan aktif ada di §1, langkah berikutnya di §9 dan checklist hanya di tasks.md.

| Perubahan | Status | Catatan |
| --- | --- | --- |
| `docs/plans/full-online-only-feature-spec.md` v2.0 | Revisi kontrak step 0 | Source existing dipisahkan dari kontrak recovery Proposed dan acceptance belum diuji |
| `docs/plans/full-online-only-implementation-plan.md` v2.0 | Revisi roadmap step 0 | Cutover §7.9 dipertahankan; langkah eksekusi 1–6 mengganti pemasangan dari nol |
| `docs/plans/full-online-only-implementation-report.md` v2.0 | Dokumentasi/audit source selesai | Satu laporan ini; gate implementasi baru §7.10 NOT_TESTED |
| `supabase/tests/full_online_only_readiness_audit.sql` | Selesai | Gate metadata lokal diperluas untuk table + column grants dan private receipt RLS (§7.3) |
| `opencode.json` | Selesai | MCP Supabase project-scoped, read-only, database features |
| Branch | Selesai | `online-first` → `online-only`; branch remote diselaraskan ke `origin/online-only` |
| Migration/deployment utama | **Tidak dilakukan** | DDL/DML hanya pada Docker lokal; delapan migration follow-up diterapkan lokal (§7.3) |
| Perubahan aplikasi Android | **G3 dan cutover sesi memiliki PASS historis; gate final terbuka** | Facade/store/guard existing §7.9; audit source dan gap release-ready §7.10, bukan implementasi baru pada pembaruan dokumen ini |

### 7.1 Iterasi F1a / prasyarat F1b — 28 Oktober 2026

**Selesai:** baseline awal c0be6b8/clean diverifikasi; tiga dokumen existing dipakai di tempat; kontrak desain 16 RPC/signature/DTO/contoh request/CAS/error/expiry review ditambahkan ke Feature Spec, fase F1a/F1b dan gates diselaraskan pada Plan. Pengguna mengonfirmasi K01/K03/K04/K05 sebagai paket, K02 weight 2 desimal + numeric exact/string wire tanpa rounding legacy, serta mengizinkan agent setup Docker. Exit F1a hanya desain, bukan runtime. `supabase/config.toml` menonaktifkan seed terpisah yang menunjuk file hilang; catalog tetap melalui migration existing dan fixture akun tetap eksplisit lokal. Tidak membuat dokumen baru, migration, atau perubahan Android. `20261025000100_template_revision_writes.sql` tetap utuh dan tidak diduplikasi.

**Environment/batas:** Windows PowerShell di root repository IMFIT; Node v22.17.0, npm 10.2.1, OpenCode v2.0.32. Bukti berikut adalah working-tree/environment checks pada baseline c0be6b8 + perubahan dokumen, bukan verified commit/CI/device atau audit live baru.

| Check / command | Status | Hasil dan batas bukti |
| --- | --- | --- |
| `git status --short --branch`, `git rev-parse HEAD`, `git log -3 --oneline` | **PASS** | Awal iterasi branch online-only, HEAD c0be6b8, clean; tanpa fetch dan tanpa klaim kondisi remote terkini |
| `package.json` + `package-lock.json` | **PASS** | Supabase CLI dipin tepat 2.116.0; lockfile/manifest tidak diubah |
| Docker discovery PATH, lokasi standar Docker Desktop, dan service Docker/Podman | **BLOCKED** | CLI/instalasi standar/service tidak ditemukan; WSL memiliki Ubuntu-20.04 dan Ubuntu, keduanya stopped; tidak menyalakan/memasang runtime |
| `npm ci --no-audit --no-fund --fetch-retries=0 --fetch-timeout=20000` | **FAIL** | Tool timeout setelah 90 detik; node_modules baru parsial dan tidak di-commit; bukan bukti instalasi sukses |
| `npm ls --depth=0 --offline` | **PASS terbatas** | Tree package melaporkan supabase@2.116.0; tidak membuktikan binary lengkap/runnable |
| Binary lokal `supabase.exe --version` sesudah timeout | **FAIL** | Windows: executable bukan aplikasi valid untuk OS; instalasi parsial, bukan bukti versi CLI runnable |
| `npm ci --offline --no-audit --no-fund` | **PASS terbatas** | 7 package terpasang; optional Windows binary tidak ada, wrapper masih gagal `No matching Supabase CLI binary package found for win32-x64` |
| `npm cache add @supabase/cli-windows-x64@2.116.0 --fetch-retries=0 --fetch-timeout=20000` | **FAIL** | Timeout 90 detik; tidak mengubah dependency pin atau menonaktifkan TLS |
| Unduh paket CLI via `curl.exe --ipv4`, SHA-512 vs lockfile, cache tarball lokal, `npm ci --offline --include=optional --no-audit --no-fund`, `supabase.cmd --version` | **PASS** | HTTP 200, 59.380.419 bytes, integrity sesuai lock; 8 packages installed; binary mengeluarkan 2.116.0; saran upgrade CLI diabaikan sesuai pin |
| `opencode mcp list` (dua observasi) | **FAIL** | pencil connected, supabase failed: getaddrinfo ENOTFOUND mcp.supabase.com; tidak memanggil tool/query database |
| `Resolve-DnsName mcp.supabase.com` dan Node `dns.lookup` | **PASS terbatas** | Hostname dapat resolve IPv4/IPv6 pada shell; tidak membuktikan resolver/koneksi proses service sehat |
| HTTPS GET tanpa autentikasi ke endpoint MCP dengan timeout 10 detik | **FAIL** | ETIMEDOUT; tidak memperoleh respons HTTP, tidak membaca row atau mengirim credential |
| `opencode service status` | **PASS terbatas** | Service lokal terdaftar pada loopback; tidak membuktikan koneksi MCP sehat; tidak restart service |
| Curl IPv4 HTTPS GET tanpa credential ke MCP endpoint | **PASS terbatas** | HTTP 401 (expected unauthenticated); endpoint dapat dijangkau oleh curl IPv4, bukan bukti OAuth/tool connection sehat |
| `supabase.cmd status` sesudah CLI pulih | **BLOCKED** | Exit 1: LegacyStatusDbInspectError, docker/podman command not found; linked_project=null; tidak menghubungi/memigrasikan project utama |
| WSL/OS dan `winget show --id Docker.DockerDesktop --exact --source winget --disable-interactivity` | **PASS terbatas** | Windows 11 Home x64, RAM 15,7 GiB, WSL 3.0.3, HypervisorPresent=true, sesi nonadmin; installer Docker Desktop 4.97.0 teridentifikasi; bukan bukti Linux daemon runnable |
| Unduhan installer Docker via curl IPv4 | **PASS sesudah resume** | Transfer awal timeout di 224.397.052 bytes; HTTP 206 resume menambah 396.099.764 bytes; total 620.496.816 bytes, SHA256 sesuai winget `2668273f82b342a058a28f9ab53ea7bddde10bb6d51307ce2ec54c2d36459744`, Authenticode Valid (Docker Inc) |
| Installer Docker Desktop 4.97.0 `install --user --backend=wsl-2 --quiet` | **PASS** | Exit 0; per-user executable dan CLI tersedia; tanpa RunAs/elevasi atau accept-license; tidak membeli subscription atau reboot |
| Docker CLI `--version`, launch Docker Desktop | **PASS terbatas** | Docker 29.9.2 build a82e528; aplikasi dibuka untuk persetujuan lisensi pengguna; bukan bukti engine berjalan |
| `docker info --format '{{.ServerVersion}}'` dengan Docker bin ditambahkan hanya ke PATH proses shell | **BLOCKED** | Command timeout 30 detik tanpa hasil; command lanjutan dalam shell yang sama tidak dijadikan bukti; tidak mengubah PATH global atau mengklaim penyebab timeout pasti lisensi |
| Python 3.13.15: pemeriksaan Markdown/JSON/TOML dan inventaris kontrak | **PASS** | Tiga dokumen: struktur tabel/fence/15 link lokal valid; 16 contoh request JSON cocok nama parameter/tipe dasar dan 16 nama RPC audit; 34 AC utuh; seed config valid; bukan test backend/DTO Kotlin |
| `git diff --check`, diff source aplikasi/manifest/lock/migration terhadap HEAD | **PASS** | Hanya tiga dokumen existing dan config seed berubah; app/package/migration tetap utuh; tidak ada dokumen baru atau perubahan dependency pin |
| SQL integration/concurrency/security dan HTTP/PostgREST lokal | **NOT_TESTED / BLOCKED** | Belum ada Docker/DB lokal terverifikasi; kontrak desain F1a sudah siap, bukan bukti tests lulus |

**Diagnosis MCP:** error berada di tahap DNS/konektivitas proses OpenCode; penyebab akhir belum terbukti karena shell dapat resolve hostname, Node HTTPS timeout, tetapi curl IPv4 mendapat HTTP 401 tanpa credential (expected). Tidak menyimpulkan token expired atau memaksa perubahan resolver/proxy global. Tidak logout/login OAuth, mengubah config endpoint/protokol, membaca credential, atau memakai service_role. Pemulihan melalui `/mcps` connect ulang setelah koneksi sehat dapat dilakukan pengguna; restart service bersama hanya setelah persetujuan. MCP bukan prasyarat implementasi CLI lokal.

**Blocker iterasi (tidak menggantikan B02–B05):** E01 menunggu pengguna menerima lisensi Docker melalui UI dan Linux daemon sehat (installer/CLI Docker serta CLI Supabase sudah siap); E02 kontrak desain ditutup oleh konfirmasi K01–K05 + detail bounds/provenance/expiry; E03 koneksi MCP masih gagal tetapi independen dari F1b lokal. F1a desain selesai, F1b belum lulus, belum ada implementasi backend dijalankan. Timeout daemon tidak membuktikan akar penyebab; bila tetap gagal setelah first-run, diagnosis dilanjutkan tanpa menyalakan Windows features/reboot secara otomatis.

### 7.2 Kelanjutan F1b — bootstrap baseline lokal

**Scope:** baseline Git tetap c0be6b8 dengan perubahan dokumentasi/config iterasi sebelumnya yang dipertahankan. Pengguna mengonfirmasi Docker sudah Running; verifikasi di bawah membuktikan daemon merespons, bukan sekadar menerima klaim UI. Tidak ada project linking, `db push`, reset/purge, DDL/DML remote, atau perubahan Android. Tidak membuat dokumen laporan baru.

| Check / command | Status | Bukti dan batas |
| --- | --- | --- |
| `docker version --format '{{.Client.Version}} / {{.Server.Version}}'` | **PASS** | Client/server 29.9.2 / 29.9.2; E01 daemon pada iterasi sebelumnya ditutup |
| `docker ps`, `docker volume ls`, `docker images` | **PASS preflight** | Awal kelanjutan tidak ada container, volume, atau image; tidak ada database lokal lama yang di-reset |
| `supabase.cmd --version`, `supabase.cmd status` | **PASS toolchain / NOT_STARTED DB** | Versi 2.116.0; linked_project=null, container supabase_db_IMFIT-MOBILE belum ada; bukan error daemon lagi |
| Ruang disk host sebelum bootstrap | **WARNING** | C: sekitar 13,1 GiB; startup dibatasi layanan minimum, tidak melakukan cleanup data lain |
| `supabase.cmd start --exclude realtime,imgproxy,mailpit,postgres-meta,studio,edge-runtime,logflare,vector,supavisor --output-format json` | **Dalam proses** | Target project lokal IMFIT-MOBILE; tanpa ignore-health-check, tanpa reset; output credential tidak dicetak ke laporan |
| `python -m unittest discover -s supabase/tests -p test_verify_local_baseline.py -v` | **PASS host-only** | 5 tests, 0,023 detik: reject endpoint override, remote context, project mismatch, baseline mismatch, dan mocked success scope; tidak membuktikan SQL/RLS/concurrency |
| `git diff --check`, diff app/manifest/lock/migration existing | **PASS** | Belum mengubah source aplikasi, dependency pin, atau SQL existing; dua file Python test baru, bukan dokumen baru |

Runner `supabase/tests/verify_local_baseline.py` memakai Docker exec ke database container bernama/berlabel project lokal yang diverifikasi dan menolak endpoint non-local socket. Sesudah baseline sehat, runner menjalankan fixture template existing dalam transaksi rollback dan metadata audit dalam transaksi read-only. **Belum dijalankan terhadap database pada titik ini.** Unit test guard berada di `supabase/tests/test_verify_local_baseline.py`; tidak memakai credential atau menghubungi database.

### 7.3 Hasil implementasi dan exit F1b lokal

**Exit F1b lokal: TERCAPAI, 28 Oktober 2026.** Bukti di bagian ini menggantikan status pending/blocked iterasi §7.1–§7.2 untuk lokal, bukan mengubah audit live §3. Backend 16 RPC tersedia; tidak ada source Android, deployment utama, commit, atau push baru. Semua hasil berikut berasal dari root repository, Python 3.13.15, Node 22.17.0, Supabase CLI pinned 2.116.0, Docker Engine 29.9.2, PostgreSQL 17.6 (`public.ecr.aws/supabase/postgres:17.6.1.165`), PostgREST v16.1. API `127.0.0.1:54321`, DB container `supabase_db_IMFIT-MOBILE`/database `postgres`; project tidak di-link ke remote. Auth/Storage/Kong/DB sehat; Studio/realtime/analytics dan layanan non-esensial tidak dijalankan.

**Snapshot bukti:** c0be6b8 + file backend working tree, bukan commit terisolasi/CI. Fingerprint 27 input adalah SHA256 dari manifest UTF-8 `relative/path + spasi + SHA256(file bytes) + newline`, path diurutkan; input mencakup seluruh `supabase/migrations/*.sql`, `supabase/tests/*.sql`, `supabase/tests/*.py`, config.toml, package.json, package-lock.json (tidak mencakup dokumen agar tidak circular). Hasil `abc44bea440f906335864a654e1ac588f8553d35fac2f74d6737a6f9bed40994`. Perubahan input terkait mewajibkan pengujian ulang; tidak ada klaim merge-ready/CI/device.

#### Migration dan perubahan backend

| Version / file di `supabase/migrations/` | Isi | Scope |
| --- | --- | --- |
| `20260828130114`, `20260828130132`, `20260828130154`, `20261025000100` | Empat migration existing direplay dan fixture template baseline lulus; source tidak diedit | Baseline lokal, bukan bukti deployed main |
| `20261028000100_online_only_operation_receipts.sql` | Private global receipt registry, observed-backfill metadata, wrapper template existing, status/discovery cursor | Signature/receipt lama tetap; tidak menduplikasi implementasi migration revision |
| `20261028000200_online_only_session_lifecycle.sql` | Session version/status/schema/review, typed commands, owner/CAS locks, atomic finish/cancel, unique linkage, provenance dan composite FK | Legacy JSON/version null tetap disimpan dan diblokir untuk review; template FK SET NULL |
| `20261028000300_online_only_remote_reads.sql` | Delapan read RPC, stable keyset, detail snapshot, progress/markers/prefill, history/catalog index | Numeric string exact, timezone IANA, tidak full-pull client |
| `20261028000400_online_only_final_write_lockdown.sql` | Revoke table dan sisa column grants, RPC-only bisnis, profile UPDATE tiga kolom, legacy RPC/anon/private deny | **Cutoff eksplisit lokal; tidak boleh deploy main tanpa izin/compatibility gate** |
| `20261028000500_online_only_function_contract_hardening.sql`, `20261028000600_online_only_validation_planning.sql` | Follow-up hasil lint: volatility, typed cursor, explicit exceptional-return paths; RAISE helper STABLE agar tidak eager constant-fold | Applied migration tidak ditulis ulang; lint awal bermasalah dicatat di bawah |
| `20261028042753_online_only_terminal_snapshot_invariants.sql`, `20261028043417_online_only_terminal_time_anchor.sql` | Cap snapshot 256 KiB, terminal pause ditutup/diakumulasi, completed pause memakai canonical workout end_time | SQL dan HTTP assertions untuk paused finish lulus |

#### Bukti pengujian

| Command / check | Hasil | Assertion/output dan batas |
| --- | --- | --- |
| `supabase.cmd migration up --local` bertahap | **PASS** | Baseline 4 lalu follow-up 8, total 12 migration; tidak menggunakan db push/link/remote URL |
| `python supabase/tests/verify_local_baseline.py` sebelum follow-up | **PASS** | PostgreSQL 170006, tepat 4 versi baseline, fixture template rollback dan metadata read-only; 15 target RPC lain memang belum ada saat baseline |
| `python -m unittest discover -s supabase/tests -p test_verify_local_baseline.py -v` | **PASS** | 6 host/mock tests: endpoint override/nonlocal, label, partial initialization, migration mismatch dan scope output; bukan database/RLS proof |
| `python supabase/tests/verify_local_backend.py --phase operations`, lalu `--phase sessions`, lalu `--phase reads` pada stage sesuai | **PASS** | Receipt replay immutable/mismatch/validation, CAS/type/ID/set numbering/cap, pause/resume, cancel/finish, expiry review, legacy fail-closed; finish dengan induced child PK collision rollback parent/child/session/receipt |
| `python supabase/tests/verify_local_backend.py --phase final` pada tip 12 migration | **PASS** | Operations + read + final security fixtures; 602 workout Oktober, ties/microseconds, owner/soft-delete, UTC/Jakarta + DST fold/gap New York, exact numeric/provenance; 10k-row EXPLAIN ANALYZE memilih history keyset index; metadata audit table + column grants |
| `python supabase/tests/verify_local_transport.py --phase compatibility` pada stage 7 migration | **PASS — 52 pemeriksaan** | Dua akun Auth sintetis, real JWT/PostgREST; membuktikan residual direct PATCH session masih berhasil sebelum cutoff (bukan keamanan final) |
| `python supabase/tests/verify_local_transport.py --phase final` pada tip 12 migration | **PASS — 57 pemeriksaan** | Semua 16 RPC lewat HTTP; 400/PT400, 409/PT409/details, 404/owner hiding, anon deny; dua koneksi template/update/create CAS, same-request replay, finish/finish dan finish/cancel; direct/legacy writer dan profile email ditolak, profile name diizinkan; terminal pause/anchor canonical |
| Proxy HTTP loopback dalam transport runner | **PASS** | Proxy membaca respons finish upstream yang sudah commit lalu menutup koneksi sebelum client mendapat status/body; discovery + retry memberikan receipt/workout yang sama (bukan hanya fake timeout) |
| Backup `pg_dump -Fc`, restore terpisah dan fixture baseline | **PASS sesudah koreksi role** | Restore pertama sebagai postgres ditolak ownership schema; restore sebagai admin PostgreSQL lokal supabase_admin lulus, 4 versi/81 catalog item/revision dan fixture cocok; bukan aplikasi memakai superuser |
| `python supabase/tests/verify_local_migration_paths.py` pada tip | **PASS** | Platform-only restore dengan app schema kosong → replay seluruh 12 SQL migration + staged fixtures; restore dump baseline 4 + seed legacy → upgrade delapan follow-up; exact old receipt replay, draft JSON/nullable version/schema dan 90-day observed-backfill metadata terjaga |
| `supabase.cmd db lint --local --level warning` | **PASS** | Results kosong setelah follow-up; temuan volatility/exception paths awal tidak diabaikan atau disamarkan sebagai PASS |
| `supabase.cmd db advisors --local --type all --level warn --fail-on error` | **PASS** | Results kosong pada threshold warn/error; bukan sertifikat pentest atau load/performance SLA |
| Readiness audit read-only, 2026-10-28 04:35 UTC | **PASS** | 10 public tables, seluruh RLS; 0 missing target names; 0 authenticated table-level direct DML; hanya column UPDATE profiles.avatar_url/birth_date/name; private receipt RLS aktif |
| Python static document/input checks dan `git diff --check` | **PASS** | Tiga dokumen existing valid (tabel/fence/JSON/link+anchor), 16 contoh request/parameter, 34 AC, Python AST/TOML; fingerprint 27 backend input tetap sama; source Android/lock dan empat migration original tidak berubah |

**Backup lokal di luar Git:** `%LOCALAPPDATA%\Temp\opencode\imfit-f1b-baseline-c0be6b8.dump`, salinan container `/tmp/imfit-f1b-baseline-c0be6b8.dump`; SHA256 `fde2a8ec36c5eef690561a896e8e94cf14b4b6cd57f6f81ab5a36d247e82c1c5` cocok host/container. Dump mengandung state/credential platform lokal: jangan commit atau publikasikan. Recovery dan fresh replay dilakukan pada database baru dalam cluster lokal, **bukan** reset DB utama lokal dan bukan fresh Docker cluster/CI/hosted restore. Pair bukti terbaru: `imfit_f1b_fresh_f4caea5e` dan `imfit_f1b_upgrade_f4caea5e`. Database verifikasi lain/partial restore dipertahankan untuk inspeksi; tidak melakukan drop/purge. Fixture SQL rollback; transport runner hanya menghapus dua akun sintetis exact-ID yang dibuat invocation itu beserta dependensinya. Ini bukan cleanup data bisnis legacy/production.

**Kegagalan historis dan pemulihan:** startup pertama timeout 600 detik setelah pull; start berikutnya exit 0 tetapi hanya DB aktif, history/Storage belum ada. Verifikasi mencegah false PASS dan fixture belum dijalankan. Stop khusus IMFIT-MOBILE dengan `backup:true` (tanpa no-backup) lalu start melengkapi stack; migration up --local menerapkan baseline. Draft read SQL sempat gagal alias `day`, diperbaiki sebelum diterapkan. Lint mula-mula memberi warning volatility; RAISE helper IMMUTABLE memicu evaluasi planner, dikoreksi STABLE melalui follow-up dan diuji ulang. Boundary fixture dengan nama raksasa tertolak index existing; diganti in-memory snapshot envelope test tanpa menonaktifkan constraint. Fresh harness mula-mula menjumpai schema history kosong yang sudah ada; diperbaiki CREATE SCHEMA IF NOT EXISTS, tanpa menghapusnya. Hasil akhir di atas adalah rerun yang selesai, bukan klaim semua percobaan awal lulus.

**Batas dan blocker release:** F1b lokal selesai; tidak ada deployment/DDL/DML atau business-row access pada project utama. B02/B04/B05 release tetap terbuka untuk hosted compatibility, cutoff client lama dan integrasi Kotlin; B03 Room/device belum diuji. Image lokal pinned PostgreSQL 17.6 bukan rekomendasi production: changelog Supabase 25 September menyebut patch 17.11/security fixes, sehingga versi target dan restore/extension compatibility wajib direview sebelum release; jangan upgrade CLI/image/main diam-diam. Load/latency, seluruh interleaving concurrency, Storage E2E, alarm/timer/device, data pengguna terpasang, dan CI/commit terisolasi belum diverifikasi. Expiry bersifat effective read + explicit review; tidak memasang scheduler/purge. MCP remote tidak digunakan untuk F1b; error koneksi awal tetap historis, bukan blocker hasil lokal.

**Scope working tree:** `.idea/misc.xml` muncul berubah dari luar proses backend pada verifikasi akhir; asal belum diatribusi dan tidak diedit/direvert/stage oleh agent. File itu tidak termasuk fingerprint atau bukti backend. Source aplikasi Android, manifest/lock package, opencode.json, dan empat SQL migration existing tetap tidak diubah. Panduan Supabase tambahan dipasang/dibaca hanya di direktori temporary yang disetujui, bukan dokumen/konfigurasi baru repository.

### 7.4 Android Remote Foundation F2/G2

**Scope pengguna:** lanjut fondasi Android sesuai urutan DTO → repository → error/CAS/receipt/idempotency → fake API → Paging/Flow → unit/race/response-loss → update tiga dokumen existing → commit/push sesuai prosedur. Baseline nyata tetap `online-only@c0be6b8` dengan F1b dirty/untracked dari iterasi sebelumnya; index awal kosong. `.idea/misc.xml` sudah berubah sebelum F2 dan tetap dikecualikan. Pengguna melaporkan MCP **CONNECTED**; iterasi F2 tidak memerlukan query/DDL/DML project utama untuk membuktikan status itu.

**Implementasi:** lima file baru pada `app/src/main/java/com/diajarkoding/imfit/data/remote/online/`: `OnlineDtos.kt`, `OnlineRpc.kt`, `OnlineTransport.kt`, `OnlineRepository.kt`, `OnlinePaging.kt`. Lima file host-test pada package yang sama: `FakeOnlineApi.kt`, `OnlineContractTest.kt`, `OnlineRepositoryTest.kt`, `OnlineTransportTest.kt`, `OnlinePagingTest.kt`. `app/build.gradle.kts` + `gradle/libs.versions.toml` hanya menambahkan Paging common **3.3.6** dan Ktor MockEngine **3.0.2** (test-only). Tidak mengganti Room, DI, AuthRepository, layar, route, WorkoutSessionManager, timer/service, atau writer/importer existing.

**Kontrak:** seluruh 16 request/response memakai factory typed dan `p_`/snake_case yang sesuai SQL. Template request/receipt existing dipakai ulang; finish memakai `SessionMutationReceipt` dengan validasi workout linkage nonnull. Required nullable field tidak diam-diam hilang; decimal wajib JSON string dan tersimpan exact, bukan Double/Float; version/revision/counters Long; timestamp wire mempertahankan microseconds. Dua belas command menyaring shape input; weight baru canonical 2 digit tanpa menerima excess precision/rounding. Detail signature tetap Spec §5.4.F.

**Auth/race:** `OnlineAuth` memisahkan session identity dari profile dan menyediakan epoch login/logout/account switch. Refresh token login sama mempertahankan epoch, request memakai credential yang ditangkap sebelum dispatch. Reset state/intent/confirmed saat identity berubah disinkronkan dengan publish; cancellation dilempar ulang. A→B→A tidak dianggap kembali ke epoch A pertama. Belum ada SDK auth-event bridge/binding production; integrasi future wajib membedakan login baru dari refresh token, meneruskan lifecycle dan menutup listener/feed/repository.

**Mutasi:** body/CAS/request ID dibangun sekali dan dipakai ulang. Satu conservative mutex repository menserialkan write. Outcome network/timeout/gateway ambigu/malformed receipt/cancellation tidak dilabeli Saved. **Owner-wide unknown gate** mencegah write berbeda, termasuk create dengan ID resource baru, sampai original di-resolve/retry. Discovery NOT_FOUND tidak membebaskan gate. `resolve(original)` memerlukan original intent dari login yang sama dan memeriksa operation/resource/receipt; bukan tebakan payload sesudah process death. Receipt replay lebih tua tidak menurunkan confirmed version. Ledger hanya memori login, tanpa durable draft/queue; manager F4/debounce dirty inputs/preflight legacy belum diganti.

**Keputusan adapter error:** inspeksi sources SDK pinned Supabase 3.0.3 menemukan `PostgrestImpl.parseErrorResponse` hanya meneruskan message/details/hint, membuang SQLSTATE code. Adapter baru memakai Ktor raw HTTP (token per request, expectSuccess=false) agar PT400/PT409/PT404, PGRST202, 42501 dan error engine dapat dibedakan. Exception SDK existing tidak dimodifikasi atau dipakai untuk menebak revision conflict. Future DI wajib memberi dedicated client tanpa automatic auth/retry/logging plugin; tidak ada panggilan ke key/URL utama dalam test MockEngine.

**Keputusan Paging:** common 3.3.6 telah ditemukan di metadata Google Maven dan dipilih konservatif terhadap Kotlin existing; bukan upgrade toolchain/dependency global. Forward-only network PagingSource, Pager/flatMapLatest/cachedIn, history 20/catalog 25/templates 15, initialLoadSize sama, prefetch=5, placeholders=false, tanpa RemoteMediator. Adapter PagingDataPresenter mengekspos StateFlow display, bukan framework pagination kedua. Refresh membuat generation baru dari cursor null; kegagalan mempertahankan snapshot read-only hanya query+epoch sama, tanpa meneruskan cursor lama. Append error latch perlu retry eksplisit, query switch langsung membersihkan display sebelum debounce 300 ms, metadata/cursor chain divalidasi dan ID didedup. Semua-duplikat nonterminal page dihentikan sebagai protocol error agar tidak membentuk prefetch loop. Paging Compose/consumer layar menunggu F3; tidak menjanjikan refresh tepat satu request tanpa prefetch.

| Check / command | Hasil | Scope/batas |
| --- | --- | --- |
| `python supabase/tests/verify_local_backend.py --phase final` | **PASS (rerun F2)** | Seluruh 12 migration, selected SQL/security/read/query-plan + read-only metadata audit lokal; backend source tidak diubah F2 |
| `python -m unittest discover -s supabase/tests -p test_verify_local_baseline.py -v` | **PASS — 6** | Host/mock target guard, bukan device/RLS proof |
| `python supabase/tests/verify_local_transport.py --phase final` | **PASS — 57 (rerun F2)** | Real local Auth/PostgREST; exact synthetic account cleanup; bukan panggilan Kotlin/SDK/device |
| `gradlew.bat :app:compileProductionDebugKotlin --console=plain` | **PASS awal** | Compile foundation, bukan APK/device; perubahan berikutnya harus ikut rerun final |
| `gradlew.bat :app:testProductionDebugUnitTest --tests "com.diajarkoding.imfit.data.remote.online.*" --console=plain` | **PASS awal — 35 tests** | 8 DTO/request/command, 14 repository, 10 Paging, 3 MockEngine/error tests; final synchronization/unknown-create/close hardening ditambah setelahnya, butuh rerun |
| `gradlew.bat :app:testProductionDebugUnitTest :app:testDemoDebugUnitTest :app:assembleProductionDebug :app:assembleDemoDebug --console=plain` pada source final dibekukan | **PASS — 275 production, 281 demo; kedua APK debug** | 37 foundation test per flavor (8 contract, 16 repository, 10 Paging, 3 transport), nol fail/error/skipped; bukan perangkat/release |
| Dokumen/static dependency boundary dan `git diff --check` | **PASS** | Tiga dokumen existing, table/fence/JSON/link/anchor; tidak ada DAO/importer/Room/Hilt import pada foundation; manifest/lock package dan empat SQL original tetap |

**Identitas bukti foundation:** Windows host, JDK JetBrains 21, Gradle wrapper 9.5.0, AGP 9.1.1/Kotlin 2.2.10, Android SDK compile 37. Root source awal c0be6b8 + 12 input F2 (lima source + lima test Kotlin + app/build.gradle.kts + gradle/libs.versions.toml). Manifest raw byte dengan algoritme §7.3 menghasilkan `be776f8dbbe7d02e3c664c14b5b2490bae7c080522ccce20dad72b34020ca04e`; untuk perbandingan lintas Git/Windows, normalisasi CRLF→LF sebelum hash file menghasilkan `b57aec90f9142711496d0d283e5b560a43725eb00e67bd050391122bf1d791d6`. Perbedaan normalisasi bukan perubahan source semantik; checkout terisolasi tetap harus diuji. Warning SDK XML/tooling dan deprecation source existing tidak disembunyikan atau diperbaiki di luar scope.

**Percobaan regresi yang gagal:** run pertama full-suite beririsan dengan hardening source/test terakhir dan satu test ambiguous-create timeout di demo (snapshot campuran). Hasil itu **FAIL**, bukan gate PASS. Source kemudian dibekukan dan keempat task di atas dijalankan ulang; build/test final selesai sukses dalam 1m46s. Tidak ada retry network tersembunyi atau test yang di-skip untuk meluluskan gate.

**Batas bukti:** host fake dapat menahan respons, menyelesaikan read terbalik, meniru receipt yang sudah committed lalu kehilangan respons, dan mensimulasikan perpindahan akun. Ia bukan database/backend kedua, dan hasil fake tidak membuktikan HTTP server transaction; bukti real backend terpisah ada di F1b/rerun di atas. JVM MockEngine membuktikan adapter HTTP/error/Authorization, bukan TLS/SDK auth refresh atau integrasi Ktor→backend lokal. Device, R8/release, SDK session lifecycle dan UI acceptance tetap NOT_TESTED. Fresh/upgrade diulang pada SHA backend terisolasi di bawah, bukan diklaim fresh cluster/CI.

**Keputusan/izin Git:** pengguna menyetujui **dua commit**, selective staging dan worktree verification: (1) 20 file backend F1b, (2) 12 file foundation/dependency + tiga dokumen existing; `.idea/misc.xml` tetap defer. Tidak ada izin push otomatis atau deployment/cutoff utama. Index pertama diverifikasi persis 20 path yang disetujui; byte dibandingkan sesudah Git LF normalization (perbandingan raw awal menolak CRLF config, sehingga commit ditahan sampai normalisasi diidentifikasi).

**SHA backend terisolasi:** `65309e83c9e7bb93f50ee590f32878336b5b7fc9`, tree `74ffb8da7314c4619997d998a63b3ba66f41cf79`, `feat(backend): add locally verified online-only RPC contracts`. Checkout bersih `%LOCALAPPDATA%\Temp\opencode\imfit-backend-65309e8`, tanpa menyalin source/secret/node_modules workspace utama. `npm ci --no-audit --no-fund` memasang toolchain dari lock yang sama; 6 guard tests, final SQL, 57 HTTP checks serta fresh/upgrade replay lulus dari SHA itu; status akhir bersih dan lock tidak berubah. Database verifikasi `imfit_f1b_fresh_96283cd2`/`imfit_f1b_upgrade_96283cd2` dipertahankan. Fingerprint 27 backend input dengan LF normalization `7ff09db133c73a5b77b2e9e0c31f723198ba655a3a753a9d35163738d0403dab`; empat SQL original tidak diubah meskipun raw SHA Windows/checkout LF berbeda.

Bukti foundation pada tabel merujuk fingerprint input agar tidak self-reference. **Tindak lanjut Verified:** commit `2ba4f828ceedf4185fc95725b54c143e6b9d7da1` (`feat(android): add online-only remote foundation and paging spike`) diuji dari checkout bersih tanpa `local.properties`: 275 production + 281 demo unit tests dan kedua APK debug lulus. Pengguna kemudian mengizinkan push kedua commit; `origin/online-only` diverifikasi melalui `git ls-remote` menunjuk `2ba4f82`, dengan backend `65309e8` sebagai ancestor. Tidak ada izin deployment utama dari push ini. Worktree registration sudah dilepas; sebagian build files temporary foundation tersisa karena Windows filename-too-long, tidak dibersihkan luas.

### 7.5 G3 — runtime remote reads, 28 Oktober 2026

**Baseline/scope:** HEAD dan remote terverifikasi `2ba4f82`; `.idea/misc.xml` pre-existing tidak disentuh/stage. Pengguna meminta melanjutkan seluruh G3 dan menjawab “lanjutkan” setelah proposal Home summary bounded lokal dan bridge minimum CAS. Implementasi/pengujian backend tetap lokal; tidak memakai akun/data hosted atau mengaktifkan session RPC. Tidak ada queue/draft durable baru, reset/purge database, route feed/template-list baru, atau modifikasi rest-timer engine.

**Implementasi working tree:** `RemoteReadGateway`, production Ktor gateway + SDK session-status bridge, deterministic demo adapter dan DI per flavor. Profile identity tetap berasal dari auth session; token refresh mempertahankan epoch, login/account change menggantinya. `RemoteResource` menahan data stale hanya untuk key/epoch sama dan membatalkan generasi lama. Catalog/picker memakai F2 `RemotePagingFeed`/Paging 3; selection ID terpisah dari snapshot halaman, explicit caller exclusions tetap berlaku. Subgroup taxonomy existing diambil bounded berdasarkan ID, bukan tebak nama. History mempertahankan route per tanggal; child logs di-fetch on-demand. Progress/calendar memakai server aggregate dan marker count, bukan merekayasa log atau menghitung halaman pertama. Home template summaries paginated dan last workout memakai `get_home_summary`; Profile tidak lagi memerlukan history VM. Global route/tab/resume full-pull dilepas. Room/legacy recovery tetap ada, bukan read source layar yang dimigrasikan.

**Interop template:** `TemplateEditBridge` memakai revision Long dan removed exercise IDs dari remote snapshot yang benar-benar diedit; dirty/invalid editor tidak ditimpa reload. Legacy intent/parent/child pending diperiksa read-only dan fail-closed; tidak di-acknowledge/import/delete sebagai efek samping. Request/CAS/ID asli direplay untuk response loss. Ini minimum compatibility G3, bukan pengganti keseluruhan G4 session manager.

**Backend tambahan:** migration `20261028090758_online_only_home_summary.sql`, RPC `get_home_summary()` → `{"last_workout": WorkoutSummary|null}`. Owner-filtered, nondeleted, order `end_time DESC,id DESC LIMIT 1`, stable security-definer dengan search_path kosong dan authenticated-only execute. Mempertahankan last-workout lebih tua dari 366 hari tanpa full-pull atau batas tanggal baru. Draft diuji dalam rollback lalu diterapkan **hanya** via `migration up --local`, tip lokal 13 migration/17 RPC. Fixture `online_only_home_summary.sql` menguji all-time, owner, soft-delete, exact decimal, empty dan anon denial.

| Verifikasi aktual | Status / batas |
| --- | --- |
| Compile production + demo pada irisan awal | **PASS setelah koreksi** nama fixture demo dan Box/Column layout scope; bukan acceptance UI |
| Remote host suite awal (37 F2 + 11 G3) | **FAIL 1/48**: initial auth collector Progress memicu force-refresh ganda dan membatalkan respons awal; lifecycle diperbaiki, rerun final diwajibkan |
| `verify_local_backend.py --phase final` pada tip 13 migration | **PASS** SQL/security/pagination/aggregate/query-plan + Home fixture rollback; bukan deployment |
| Full unit kedua flavor + kedua debug APK, snapshot Android 44 changed inputs LF manifest `6e9aba509c3083e02bdb1c6192f2a74a7d37522db6f8456c8e0b5dd085f566b7` | **PASS 290 production + 296 demo**, nol failure/error/skipped; kedua APK debug terbentuk. Task gabungan **FAIL** di compile instrumentation karena satu test Room-editor masih memakai constructor lama; source test diadaptasi ke explicit remote refresh, bukan dihapus/skip. Hardening berikutnya memerlukan rerun baru |
| Real local Auth/PostgREST transport final | **PASS 59 pemeriksaan**, termasuk 17 RPC; exact synthetic account cleanup, bukan reset database |
| Fresh replay + upgrade `verify_local_migration_paths.py` pada 13 migration | **PASS**; DB `imfit_f1b_fresh_aa0ff6c5` dan `imfit_f1b_upgrade_aa0ff6c5` dipertahankan; legacy JSON/receipt tetap exact/review-blocked |
| Host target guards | **PASS 6**; menolak remote context/override/target salah, bukan SQL/device proof |
| Rerun Android final setelah review | **PASS 292 production + 298 demo**, nol failure/error/skipped; kedua APK debug dan `assembleDemoDebugAndroidTest` berhasil; snapshot Android 47 changed inputs LF manifest `f3eb2463bc02da30cfb4e2b0ec19af6c8b839e7619796da6cf102032431a05c7` |
| UI/runtime `G3NavigationTest` | **BLOCKED/NOT_TESTED**: emulator disposable `imfit_g3_verify`, API 34, serial `emulator-5580`; proses `qemu-system-x86_64-headless.exe` APPCRASH `0xc0000005` (Windows WER) dua kali, termasuk saat instrumentation. APK androidTest terbaru ter-compile setelah matcher diperbaiki, tetapi skenario end-to-end belum selesai/PASS; ini bukan bukti kegagalan aplikasi |
| Review statis final | **OPEN**: `RemotePagingViewport` masih memetakan indeks mentah `LazyColumn` (termasuk header/footer/loading) ke data ID sehingga hint prefetch dapat salah pada Home/history; perlu mapping berbasis item key sebelum acceptance. Warning kondisi redundan di `EditWorkoutViewModel` juga belum dibersihkan |

**Gate terbuka:** acceptance UI/route regression/race serta perbaikan mapping viewport perlu ditutup sebelum G3 dinyatakan siap. Production RPC availability masih blocker deployment terpisah: build production bukan izin memasang/menguji terhadap project bersama. Legacy installed-data upgrade dan sesi remote/timer G4/G5 tetap belum dibuktikan.

### 7.6 Kelanjutan G3 — viewport dan acceptance demo pada macOS

**Baseline/scope:** branch `online-only`, HEAD `8abf100`, working tree awal bersih. Tidak fetch/commit/push, memakai akun hosted, mengubah SQL/backend, memasang production APK, atau menghapus Room/pending. Snapshot §7.5 tetap bukti historis Windows, bukan hasil emulator macOS.

**Implementasi source:** `RemotePagingViewport` menggunakan map stable lazy-item key → ID di Home, history, catalog, dan picker. Hanya data yang beririsan dengan viewport menghasilkan hint; header/footer/loading/error tidak menaikkan posisi data. Hint ditahan saat refresh/stale dan dikirim ulang ketika refresh selesai meskipun ID halaman sama. Lima host regression tests ditambahkan; fixture Compose menguji header/footer/query replacement dan re-hint same-ID refresh. Fixture UI catalog menguji pull-refresh empty/error serta stale/append retry dengan row tetap ada; navigation test ditambah untuk year markers → history → child detail on-demand.

**Environment/batas:** emulator baru `imfit_online_g3_verify`, API 34 Google APIs arm64-v8a, serial `emulator-5580`, disimpan di direktori temporary OpenCode. Emulator existing `emulator-5554` dan ponsel fisik tidak dipakai. Test UI memakai demo/sintetis; tidak membuktikan Ktor→Supabase lokal, RLS hosted, atau upgrade data terpasang.

**Kegagalan awal:** compile pertama menolak helper koleksi `lastNotNullOfOrNull` yang tidak tersedia; diganti `asReversed().firstNotNullOfOrNull`. Run berikutnya beririsan dengan penambahan parameter `enabled`: unit/build app lulus tetapi instrumentation compile gagal karena melihat signature main lama. Run itu bukan bukti snapshot final; tabel berikut memakai rerun setelah source dibekukan.

| Verifikasi aktual | Status / batas |
| --- | --- |
| `./gradlew :app:testProductionDebugUnitTest :app:testDemoDebugUnitTest :app:assembleProductionDebug :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest --console=plain`, source dibekukan | **PASS** — 297 production + 303 demo, nol failure/error/skipped; kedua APK debug dan APK instrumentation demo terbentuk |
| Identitas input Android | Baseline `8abf100` + 9 changed Kotlin inputs; manifest LF SHA256 `859e21aea22c7c09026fb4d2535075ca705d66271cb31eb1bd411219bbec9abd` (algoritme §7.3, hanya sembilan path Kotlin berubah/baru) |
| Toolchain build | macOS, OpenLogic JDK 21.0.6 x86_64, wrapper Gradle 9.5.0, compile SDK 37; tidak upgrade dependency/toolchain. Warning annotation-target, deprecated Compose test API dan SDK XML existing tetap terlihat; warning redundant condition editor dari laporan lama tidak muncul pada rerun ini |
| `adb -s emulator-5580 install -r` untuk APK demo + androidTest, lalu `shell am instrument -w -r -e class <filter di bawah> com.diajarkoding.imfit.demo.test/androidx.test.runner.AndroidJUnitRunner` | **PASS — OK (7 tests), 50,475 detik**, semua test status 0; hanya emulator disposable dan APK demo, tidak menjalankan connected suite pada emulator lain |
| Validasi dokumen + diff | Fence Markdown dan link lokal tiga dokumen **PASS**; `git diff --check` dijalankan kembali sebelum selesai |

**Filter UI exact** (prefix package `com.diajarkoding.imfit.presentation.`): `G3NavigationTest` (2), `components.common.RemotePagingViewportUiTest` (1), `ui.exercise.RemoteCatalogStatesTest` (2), `RedesignNavigationTest#rootTabsSearchCategoryAndProfileUseRealDestinations` (1), dan `RedesignNavigationTest#lightDarkAndBothLocalesRenderAllRootTabs` (1). Membuktikan create/edit/hidden picker selection/save/delete/back, year markers→history→on-demand child detail, catalog search/no-results/Profile navigation, root light/dark × ID/EN, viewport headers/footer/query replacement/same-ID refresh, empty/error pull-refresh dan stale/append explicit retry. Tidak mengklaim seluruh matriks narrow/font-scale/TalkBack, semua error tiap layar, production auth lifecycle atau device timer lulus.

**Artefak lokal:** log final `imfit-g3-final-build.log` dan `imfit-g3-ui.log` di `/private/var/folders/yv/171k2lgd0jdg4y0jclvxjtzw0000gn/T/opencode/`; hasil JUnit di `app/build/test-results/testProductionDebugUnitTest/` dan `testDemoDebugUnitTest/`. Emulator disposable dihentikan melalui `adb -s emulator-5580 emu kill` sesudah test; AVD/data verifikasi tidak dihapus.

**Status gate:** bug viewport dan blocker emulator §7.5 tertutup untuk scope tujuh test tersebut. G3 acceptance lengkap masih terbuka; prioritas berikutnya melengkapi state/error per layar dan race runtime, kemudian inventory/preflight legacy F6 sebelum aktivasi start sesi G4/G5. Tidak ada commit/push/deployment baru pada iterasi ini.

### 7.7 G3 — state/error lintas layar dan lifecycle akun

**Scope:** pengguna menyetujui eksekusi penutupan state/error, race akun, dan compact/font besar sebelum inventory/preflight F6. Baseline tetap `online-only@8abf100` + perubahan §7.6 yang dipertahankan; tidak commit/push, mengubah backend, menggunakan akun hosted, mengaktifkan session RPC atau menghapus Room/pending. Emulator disposable §7.6 dipakai ulang; emulator/perangkat lain tidak dipakai.

**Perubahan source:**

- Picker membedakan kegagalan konteks/exclusions dari kegagalan halaman. Search/filter tidak menyembunyikan context error menjadi spinner tanpa request; pull/retry memuat ulang konteks dahulu. Generation + cancellation menolak hasil konteks lama, halaman tidak dipublish sebelum konteks siap, dan metadata halaman existing tetap diadopsi saat caller exclusions berubah. Reload/retry tidak membawa nama/exclusions caller A ke akun B; selection epoch lama selalu dibersihkan.
- CTA pada legitimate-empty picker memakai refresh, bukan `Paging.retry()` yang memang no-op bila tidak ada load error. Test compact membuktikan initial error → empty sukses → explicit refresh → content tanpa gesture panjang.
- `RemoteReadGateway.template` memeriksa cancellation setelah lookup child yang mungkin tidak kooperatif. Editor/detail membersihkan source saat ID berubah dan memuat ulang retained route pada epoch login baru. Editor memakai source-generation monoton **dan key baris LazyColumn yang mencakup generation** agar raw field Compose tidak memakai ulang saveable registry dari akun/query lama ketika emisi reset ter-conflate atau row sebelumnya keluar viewport.
- Detail rutinitas dan kalender tahunan memakai pesan stale + Retry yang sama dengan reader lain; navigasi, token IMFIT, default set/reps, engine timer dan protokol write tidak diubah.
- `OnlineAuth` menyelesaikan seluruh reset listener sebelum mem-publish StateFlow identity baru; listener menerima identity transisi secara eksplisit agar repository ledger tidak membaca identity lama. Ini urutan publication fence di memori, bukan perubahan kebijakan login/logout/refresh SDK. Collector `Main.immediate` tidak boleh memuat snapshot baru lalu dibersihkan oleh listener yang terlambat.

**Test baru:** 13 host tests di `G3ReadLifecycleTest` mencakup context retry, owner caller isolation, A→B→A, respons profile/template/progress terlambat, query switch, retained-route no-fetch, summary stale dan reentrant identity publication dengan Unconfined collector. `G3ReadStatesTest` memakai route/ViewModel runtime dengan gateway scripted di memori (bukan backend/DAO/queue baru): Home, history/detail, Progres/year calendar, Profile, picker, template detail/editor dan completed summary. Fixture memakai 320 dp/fontScale 1,5 tanpa mengubah preferensi emulator; gesture picker standard font diuji terpisah. Aggregate 602 sesi sengaja berbeda dari satu summary termuat.

**Kegagalan awal dan batas gesture:** run UI awal 3/5 PASS, dua FAIL. Retry kalender berada di luar LazyColumn sehingga matcher scroll salah; diperbaiki untuk menekan tombol di owner yang benar. Pada picker 320 dp/fontScale 1,5, viewport hasil terlalu pendek untuk gesture default pull threshold; test compact menggunakan explicit Retry, sedangkan pull empty/error diuji terpisah pada fontScale 1,0. Ini bukan klaim gesture compact berhasil dan bukan alasan menyembunyikan Retry. Tidak mengubah threshold Material atau desain secara diam-diam.

Run gabungan berikutnya **16/17 PASS, satu FAIL nyata** pada editor sesudah auth berubah. Hipotesis awal saveable registry diperketat dengan key baris yang ikut generation snapshot (tanpa mengganti exercise ID bisnis), tetapi rerun masih gagal. Diagnostik fixture kemudian menunjukkan state **kosong/hasLoaded=false** setelah request sukses: collector Main.immediate berjalan di setter identity, memuat data baru secara sinkron, lalu reset listener menghapusnya. Publication fence diperbaiki dan test urutan reset→identity ditambahkan; key baris tetap melindungi raw field lintas snapshot. Kegagalan tidak ditandai sebagai emulator crash atau di-skip. ComposeTimeoutException pada library ini langsung menurunkan Throwable, sehingga diagnostic wrapper test memakai catch Throwable dan selalu melempar AssertionError kembali.

| Verifikasi aktual | Status / batas |
| --- | --- |
| `./gradlew :app:testProductionDebugUnitTest :app:testDemoDebugUnitTest :app:assembleProductionDebug :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest --console=plain` setelah publication fence diperbaiki | **PASS build/host** — 310 production + 316 demo, nol failure/error/skipped; kedua APK debug dan instrumentation demo, source final dibekukan |
| Editor regression terisolasi setelah publication fence | **PASS — OK (1 test), 4,538 detik**; raw set kembali ke snapshot 3, input dirty 9 tidak bocor ke epoch baru, failed refresh mengunci Save, recomposition tidak fetch |
| Identitas input kumulatif final | Baseline `8abf100` + 20 changed/new Kotlin inputs (§7.6 + §7.7), LF manifest SHA256 `8b374181bd319114cdf190c63b9f10e2c06b2669c1950d6e1391fc3b32b3a8a2`; algoritme §7.3, tanpa dokumen agar tidak circular |
| Final build + install ulang APK demo/test + `am instrument -w -r -e class <filter>` pada source dibekukan, termasuk empty-picker CTA fix | **PASS — OK (17 tests), 68,376 detik**, semua test status 0, wrapper memeriksa summary PASS (exit adb saja tidak cukup). Filter §7.6 ditambah `com.diajarkoding.imfit.presentation.G3ReadStatesTest` (10 test); tujuh regresi lama dijalankan kembali |

**Matriks kontrak yang diverifikasi:**

| Reader | State/aksi di fixture runtime | N/A / batas |
| --- | --- | --- |
| Home | Loading, initial error/Retry, template empty, content, stale refresh, append error latch/footer retry, recomposition no-fetch | Tidak mengaktifkan sesi remote; active indicator tetap legacy |
| Catalog/picker | Catalog query/no-results/stale/append (§7.6), picker initial error/empty refresh/selection hidden/stale confirmation lock | Compact/font150 memakai CTA; gesture empty/error picker teruji pada font100 |
| History + completed summary | Loading, initial error, history empty, content/detail on-demand + detail retry, stale, history append latch + dedup, recomposition | Summary not-found adalah error, bukan legitimate empty workout; tidak menguji finish remote |
| Progres + year calendar | Initial error, aggregate/markers kosong sah, aggregate lengkap 602 vs satu page, stale/retry dan no history-page request | Tidak mengklaim dataset hosted atau benchmark |
| Profile | Loading, initial error/pull retry, content dengan optional fields kosong, stale identity, long-name wrapping | Tidak ada list empty/no-results/append untuk satu mandatory profile |
| Template detail/editor | Initial error/Retry, routine kosong sah, content/stale action lock, dirty-refresh guard, epoch reload/raw-field reset dan no-fetch | Tidak menguji writer session/timer atau process-death upgrade |

**Batas release tetap:** fixture scripted bukan Kotlin→Supabase lokal/hosted E2E. SDK real session lifecycle, installed-data upgrade Room 9/6/7, TalkBack manual, landscape/OEM/API lain, release-minified, dan seluruh kombinasi font/locale tiap layar tetap NOT_TESTED pada iterasi ini. Root light/dark × ID/EN diuji oleh regresi §7.6 yang disertakan kembali, bukan diklaim seluruh matriks aksesibilitas selesai. Tidak ada perubahan schema, deployment, cleanup legacy atau izin push baru.

**Exit lokal dan artefak:** G3 read/state/race lokal PASS dengan batas di atas. Lanjut ke inventory/preflight legacy F6; create session remote tetap tidak diaktifkan sampai guard/recovery terkait tersedia. Log `imfit-g3-state-final.log` (build + UI final) dan `imfit-g3-state-auth-race-fail.log` (kegagalan sebelum publication fix) disimpan di direktori temporary OpenCode §7.6. JUnit final tetap di `app/build/test-results/`. Emulator `emulator-5580` dihentikan sesudah final PASS; AVD/data tidak dihapus. Tidak commit/push pada iterasi ini.

### 7.8 F6.1 — inventory/preflight legacy foundation

**Scope/baseline:** pengguna meminta tahap berikutnya. `online-only@8abf100` + perubahan G3 §7.6–§7.7 dipertahankan; index tetap kosong, tidak fetch/commit/push. Tidak membuka database pengguna/perangkat fisik, menjalankan SQL/HTTP Supabase utama, memigrasikan database bisnis, mengganti session manager, atau menghapus Room/pending. Semua file SQLite perangkat adalah fixture baru di cache package demo, bukan `imfit_database`.

#### Implementasi dan batas integrasi

- Lima source baru di `data/legacy/`: `LegacyInventory.kt`, `LegacySqlInventory.kt`, `AndroidLegacyInventoryReader.kt`, `LegacyCheckpointStore.kt`, `LegacyStartPreflight.kt`. Binding production di `di/LegacyRecoveryModule.kt` menyediakan reader/checkpoint/preflight secara lazy; **tidak menyediakan remote session writer dan belum punya consumer Start runtime**.
- Reader memakai `OPEN_READONLY`, tanpa Room/open helper, migrasi, importer, atau CREATE_IF_NECESSARY. Fresh hanya untuk ENOENT pada parent yang dapat diperiksa dan tanpa orphan WAL/SHM/journal; file nol/rusak, symlink, unsupported schema, dan read failure adalah Unknown. Corruption handler default Android diganti no-delete: handler default dapat menghapus database saat open gagal.
- Schema aktual diverifikasi dari `user_version`, Room identity hash dan digest `PRAGMA table_info` terhadap export 6/7/9. Layout alternatif v7 tanpa metadata sync, schema 4/8, unknown table/column/view/trigger tidak ditebak: blocked/review, tanpa destructive fallback. Layout 6/7 yang dikenali bisa di-inventory tetapi **selalu memerlukan bridge/review** sebelum Ready pada fondasi ini.
- Pembacaan schema/owner rows/parentage memakai private read-only pool tanpa WAL-enable flag. `data_version`/`schema_version`/`user_version` dan file inode/device/size/mtime diperiksa sebelum/sesudah scan; perubahan menjadi `CHANGED_DURING_READ`. Tidak menggunakan BEGIN IMMEDIATE/EXCLUSIVE pada storage legacy. Ini optimistic stability fence, **bukan transaksi atomik SQLite+server**. WAL committed rows terbaca tanpa mengubah byte main/WAL pada fixture; SHM adalah state operasional SQLite, bukan bukti seluruh sidecar tak pernah disentuh.
- Inventory owner-scoped mencakup profile, template/children, logs/sets, active JSON, intent dan dependencies. Hanya metadata/ID/disposition/hash dikembalikan, bukan nama/email/JSON/input bisnis. Cross-parent set mismatch ditolak sebelum payload diambil; orphan tanpa owner yang dapat dibuktikan memblokir scan. B valid/pending tidak ikut inventory A. `SYNCED_CACHE_CANDIDATE` bukan receipt atau izin cleanup. Provenance unknown dan intent/draft tidak di-acknowledge atau direwrite.
- Checkpoint atomik + explicit fsync + mutex di `noBackupFilesDir/legacy_inventory/`, filename SHA256 owner. Isi hanya owner/epoch/schema/fingerprint/decision/reason/count/time; tidak ada draft bisnis atau offline queue. Format/corrupt/foreign checkpoint atau failure load/write memblokir Ready. Checkpoint READY lama **tidak pernah di-reuse** untuk otorisasi; inventory dan remote checks selalu baru.
- Preflight memakai original identity untuk active-session read, recent-operation discovery dan known-intent lookup. `readFor(identity, call)` serta `OnlineRepository.execute(..., expectedIdentity)` memagari credential production. Ada sesi aktif/expired/legacy, item pending/review/problem, atau terminal SESSION receipt dalam window → RecoveryRequired; transport/protocol/cap/checkpoint failure → Unknown.
- Discovery hanya satu page 100 dalam window 90 hari berdasarkan clock client; `has_more`/metadata tidak lengkap → Unknown, bukan full-pull atau asumsi seluruh operasi absen. Ini bukan proof all-time discovery atau server-clock/retention di luar window. Receipt COMMITTED untuk intent lama tidak menyelesaikan payload/receipt review otomatis.
- `LegacyGuardedSessionStart` adalah seam F4 yang diuji dengan fake transport: hanya create RPC typed, re-inventory sebelum dispatch dan original immutable call/epoch tetap dipakai. **Tidak dipasang pada Start existing.** Startup Room lama dan writer lokal belum dikoordinasikan dengan scan; sisa race sesudah scan harus ditutup saat G4/F6 cutover, bukan dianggap lock lintas storage sudah tersedia.

#### Verifikasi aktual

| Check | Hasil / scope |
| --- | --- |
| `:app:testProductionDebugUnitTest` dan `:app:testDemoDebugUnitTest` pada source final | **PASS 345 production + 351 demo**, nol fail/error/skipped; termasuk 17 SQL inventory + 18 preflight tests baru per flavor |
| `:app:assembleProductionDebug :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest` | **PASS**; dependency/toolchain/schema JSON/SQL migration tidak diubah |
| `LegacyInventoryDeviceTest` terisolasi pada emulator `imfit_online_g3_verify`, API 34 arm64-v8a, serial `emulator-5580` | **PASS 8 tests**, source fixture 6/7/9 tetap byte-identical/version tidak berubah; missing tanpa create, corrupt/zero/symlink/orphan WAL blocked, committed WAL terlihat dan byte main/WAL terjaga, checkpoint owner/restart-store/backup-restore/corrupt/foreign payload |
| Final combined build + install demo/test + instrumentation class filter | **PASS — OK (25 tests), 62,988 detik**; delapan test baru + seluruh 17 regresi G3 §7.7. Wrapper memeriksa summary PASS, bukan hanya exit adb |
| Host preflight safety | **PASS** no-create pada pending/review/corrupt/schema6/7/remote active/expired/partial discovery/checkpoint error/changed local data; A→B→A dan cancellation tidak publish Ready; old Ready tidak melewati restored pending; COMMITTED intent tidak di-acknowledge; valid clean path rechecks inventory dan mengirim original request sekali |
| Identitas source kumulatif | `8abf100` + 30 changed/new Kotlin inputs (G3 + F6.1), LF manifest SHA256 `05c143ff070cdb56190ca6c8ffba66c7a5eba13df5e3b4324fc3c4cb0d077339` dengan algoritme §7.3; bukan clean verified commit/CI |

**Kegagalan awal:** host target tests lulus; compile pertama memberi warning nullable parent File (diperbaiki). Delapan instrumentation awal gagal di fixture mkdir sebelum reader berjalan karena cache instrumentation package bukan direktori writable process target. Assets tetap dari instrumentation context, fixture dipindah ke UUID directory di **target demo cache**; rerun delapan test dan combined 25 lulus, bukan test yang di-skip. Desain read-only awal ditinjau sebelum device verification: BEGIN IMMEDIATE/EXCLUSIVE diganti optimistic data-version fence agar tidak meminta write transaction terhadap database lama.

**Artefak:** `imfit-f6-inventory-final.log` (final build + 25 instrumentation) dan `imfit-f6-fixture-setup-fail.log` di temporary OpenCode §7.6; XML JUnit di `app/build/test-results/`. Fixture `f6-inventory-<UUID>` hanya di cache demo dan dipertahankan untuk inspeksi, bukan data pengguna. Emulator disposable dihentikan setelah PASS; tidak membersihkan AVD/volume/database lain.

**Status:** fondasi F6.1 PASS lokal; **G6a/G6b belum tercapai**. Cap inventory 10.000 owned rows dan discovery page 100 fail-closed; tidak memotong data atau memaksa upload. Pending historis masih memblokir sampai classification/recovery archive yang dapat diakses owner tersedia. Recent SESSION receipts belum memiliki workflow acknowledgement/resolution; jangan mengaktifkan seam create ini untuk instalasi pengguna. UI review/manual consent, bridge 6/7, import RPC/idempotent mapping/checkpoint submitted→confirmed, restore/upgrade data terpasang, real process-death dan seluruh interleaving writer/concurrency masih NOT_TESTED/belum diimplementasikan. Tidak membuat klaim transfer, zero-loss, production readiness, atau izin cleanup.

### 7.9 F4 cutover runtime, F5 service/timer, dan smoke kontrak backend — 28 Oktober 2026

**Scope iterasi:** runtime kedua flavor lepas dari jalur bisnis Room menuju `OnlineWorkoutRepository` (facade remote-only), writer sesi versi via `OnlineSessionStore`/`SessionDomainAdapter`, cancel await-receipt di VM/layar/service, ticker timer memori murni, smoke kontrak backend REST, dan verifikasi unit + instrumentation emulator lokal. Backend utama/hosted tidak disentuh; hanya Supabase lokal Colima.

**Label bukti v2.0: PASS historis, bukan current verification.** Seluruh test count, command, log, fingerprint dan smoke REST di §7.9 berasal dari iterasi 28 Oktober yang sudah tercatat. Tidak ada unit, instrumentation, build atau smoke REST yang diulang pada pembaruan 29 Oktober. Bagian ini membuktikan cutover pernah dijalankan pada snapshot tersebut, bukan exit G4/G5/G6 atau release source final.

#### Perubahan runtime

| Item | Isi |
| --- | --- |
| Binding cutover DI | `WorkoutRepository` di Production & Demo terikat ke `OnlineWorkoutRepository` (tanpa Room); `WorkoutRepositoryImpl`/`DemoWorkoutRepository` keluar dari jalur bisnis (kelas tetap ada, binding dilepas) |
| Writer sesi remote | `OnlineSessionStore` (state per-login, pending receipt immutable + retry eksplisit, guard identitas, terminal idempotent) + `SessionDomainAdapter.commandFor` (satu intent per call, CAS `p_expected_version`, weight `0..600`, scale 2); store/repo/facade per-flavor punya test khusus |
| Start guarded di kedua flavor | `OnlineSessionModule` + per-flavor `RemoteReadModule.onlineRepository`; demo `LegacyRecoveryModule` menyediakan `LegacyCheckpointStore` + `LegacyStartPreflight` + arsip; production menyediakan setara; create hanya setelah preflight READY; session remote aktif mengarah ke `LegacyStartBlocked` (RECOVERY_REQUIRED), bukan overwrite |
| Cancel await-receipt | `ActiveWorkoutViewModel.cancelWorkout()` menunggu `workoutRepository.cancelWorkout()`; sukses → `timer.cancel("SESSION_CANCELLED")` + `sessionEnded`; layar hanya navigasi via `state.sessionEnded`; error string baru `error_session_cancel` EN/ID; service `ACTION_STOP` membungkus cancel dalam try/catch lalu selalu `stopWorkout()` |
| `cancelWorkout()` idempotent-terminal | Snapshot in-memory sudah terminal (retained receipt `accept`) → no-op tanpa request; store cold → konfirmasi lewat reload; bila remote ACTIVE/PAUSED ditemukan cancel await receipt, bila tidak ada → no-op sukses |
| Ticker F5 tanpa I/O per-tick | `WorkoutService`/VM polling hanya `getActiveSessionForTimer()` → `store.peek()`; nol Room, nol network per tick; peek cold-unknown → `RemoteError.Network` (bukan absence); konsumen fail-loud (`getTemplates`, `getWorkoutLogs`, `updateActiveSession`, `requestCancelSession`, `getLastExerciseLog`, `getLastWeightsForExercise` memakai `error()`) |

#### Bukti pengujian (host, macOS)

| Check | Hasil |
| --- | --- |
| `OnlineWorkoutRepositoryTest` (11 skenario) | **PASS 11/11**: memory peek rest override; finish→log mapping→peek terminal kosong; cancel→receipt→terminal dengan short-circuit idempoten; cold cancel konfirmasi remote (`get_active_session` → ACTIVE → cancel await receipt) dan no-op bila tanpa session; cancel setelah konfirmasi terminal in-memory zero-request; rest override `p_expected_version`=2 dan `p_command.type`=`set_rest_seconds` fan-out sekali per child berbeda; identity fence timer; bulk legacy fail-loud; prefill owner fence; start guarded-create sekuens `get_template_detail`→`get_active_session`→`list_recent_operations`→`create_active_session` |
| `DemoSessionLifecycleTest` (testDemo, 2 test baru) | **PASS**: create → edit/complete set → finish → log `FakeWorkoutDataSource` terisi (`totalVolume` 10×8=80) → fresh create diizinkan setelah receipt terminal → create ketiga menghasilkan `LegacyStartBlocked` (RECOVERY_REQUIRED), bukan konflik mentah; replay body sama kembalikan receipt (200), replay beda body ditolak `IDEMPOTENCY_MISMATCH` (409), CAS `VERSION_CONFLICT` (409), `get_operation_status` lapor `COMMITTED`/`NOT_FOUND` |
| Suite unit penuh kedua flavor | **PASS** — rerun final fresh (bukan cache): production 387 cases, demo 395 cases, 0 fail (termasuk test baru); log `$TMPDIR/imfit-f4-cutover-suite.log` |
| Assemble `assembleProductionDebug`, `assembleDemoDebug`, `assembleDemoDebugAndroidTest` | **PASS** (138 tasks); graph Hilt valid setelah binding dilepas |
| Smoke kontrak session RPC via REST (lokal Colima, akun Auth sintetis `smoke-f4@example.invalid`, template via `save_template_revision`) | **SMOKE_OK**: create→receipt `version 1 ACTIVE`; `edit_set` weight 60 → v2; `complete_set` → v3; stale CAS → `PT409 VERSION_CONFLICT` (`current_version` 3); `finish_active_session` → `workout_id` non-null; `get_workout_detail` → `total_volume 600.00` (60×10); `list_recent_operations` window limit 100 terisi penuh; **fresh create setelah receipt terminal diperbolehkan** (`version 1`); create paralel saat ada session ACTIVE → `PT409 SESSION_REVIEW_REQUIRED`; `cancel_active_session` → `CANCELLED` v2. Skrip berada di `$TMPDIR/imfit-f7-rest-smoke.sh` |
| Identitas source kumulatif | `8abf100` + 63 file Kotlin changed/new (G3 + F6.1 + cutover + penyesuaian test kontrak rerun), manifest LF SHA256 `5bc83f11813f9e32a518a4ad51ebe65d6f238eed5e88247757b85c282f2fd0fa` algoritme §7.3 (supersede `21627556…`); tambahan non-Kotlin: `values/strings.xml` dan `values-in/strings.xml` (`error_session_cancel`), `HomeScreen.kt` carry tag tester `home_refresh` |

**Kegagalan awal dan pemulihan:** test repo awal dua hang (`UncompletedCoroutinesError`) karena fixture `list_recent_operations` memakai `limit:20` padahal preflight menuntut window penuh `limit:100`, dan receipt builder meng-hard-code id `"session"` padahal validator menuntut echo `p_session_id` asli — keduanya diperbaiki. Main dispatcher lintas test unit demo gagal dua lapis: perbaikan pertama (lifecycle demo `init setMain(Unconfined)` + `logout` di After) tetap gagal saat cache gradle memaksa rerun, karena fixture G3 di source set `test` bersama memanggil `resetMain()` di After — fallback `Dispatchers.Main` Android ("Looper not mocked") ter-cache dan collector gateway yang bertahan lalu di-dispatch ke dispatcher rusak. Kebijakan final: fixture memasang dispatcher testnya di `@Before` dan mengembalikan Main ke `Unconfined` di `@After`, tidak pernah `resetMain`. Instrumentation pertama 6 dari 114 gagal konsisten dengan cutover (bulk `getTemplates` fail-loud, cancel kedua setelah terminal, refusal `updateActiveSession`, state stale layar daftar katalog) — test diperbarui ke kontrak baru (template paging via `RemoteReadGateway`, `RestTimerEntryPoint` tambah `reads()`, asersi refusal `IllegalStateException`, baris dipertahankan + blok `remote_read_stale`); hasil rerun connected dicatat di ringkasan di bawah.

**Rerun connected suite (emulator `imfit_online_g3_verify`, serial 5556):** tiga putaran dicatat. Run pertama: 112/114 hijau, 6 fail konsisten dengan cutover — diperbarui ke kontrak baru (detail di atas). Run kedua: 112/114, sisa 2 fail dibedah berbeda akar dan diperbaiki: (1) `ActiveSessionEditingTest` menunggu baris hasil pencarian picker yang kini lewat remote paging — `waitForText` hanya match field input, diganti wait-until node clickable row; (2) kedua test melakukan batch dua intent (edit+complete) dalam satu `mutateActiveSession` padahal kontrak adapter satu intent per call (app asli memang memisahkan `updateSet`/`completeSet`) — mutasi dibagi dua call; (3) `cancelWorkout()` facade conflict pada in-memory retained terminal snapshot setelah finish — contract diperluas: snapshot in-memory terminal → no-op tanpa request (dua unit test baru: cold cancel konfirmasi remote, dan cancel pasca-terminal zero-request); (4) heading ringkasan Home ditegaskan memakai jalur refresh eksplisit pembaca (tab Home + pull-to-refresh, tag `home_refresh`) — sesuai kontrak §7.7 "global pull dilepas, refresh explicit milik reader"; invalidation hanya query terkait tetap open item plan. Filter rerun kedua class PASS, lalu **run final penuh 114/114 PASS, 0 fail, 13 skipped, EXIT=0 (11m 6s)**; log `$TMPDIR/imfit-f7-connected-3.log`, run kedua `$TMPDIR/imfit-f7-connected-2.log`.

**Batas:** hosted utama, deployment, cutoff, commit/push, dan penghapusan data memerlukan izin terpisah; commit/push khusus iterasi ini sudah diberi izin pengguna. Reader recovery tetap terisolasi; data legacy Room tidak dihapus. Verifikasi hanya terhadap backend lokal Colima; production SDK/backend hosted dan release/proguard belum diuji.

### 7.10 Audit source dan gate eksekusi release-ready lokal — 29 Oktober 2026

#### Scope dan klasifikasi bukti

Tanggal diambil dari jam sistem saat eksekusi. Pembaruan ini hanya membaca source dan menyelaraskan tiga dokumen existing sebelum implementasi langkah 1–6; tidak menjalankan build, unit/SQL/instrumentation test, lint, formatter atau runtime smoke. Tidak ada commit/push, deployment/cutoff utama, reset/purge atau penghapusan data pengguna. Baseline commit metadata dan audit live 27 Oktober tetap historis. LSP tidak tersedia; audit memakai pembacaan source/pencarian terarah.

**Target disetujui:** release-ready **internal lokal**, recovery data lama dan proof aktual production HTTP/UI serta normal/instrumented minified release. Signing debug internal existing bukan publish signing. Backend hanya Supabase lokal `IMFIT-MOBILE`; project bersama `ltcxltahkrjnsjaxndsa` tidak digunakan untuk fixture/deployment. Seluruh gate baru di bawah **NOT_TESTED**; menulis rencana tidak menutup G4/G5/G6/release.

#### Audit source existing dan remaining gaps

Path Kotlin berikut relatif terhadap `app/src/`; **Verified — source** hanya berarti isi yang dibaca, bukan klaim runtime baru.

| Area | Verified — source 29 Oktober | Remaining gap / tindakan yang direncanakan |
| --- | --- | --- |
| DI/cutover | `production/.../di/AppModule.kt` dan `demo/.../di/AppModule.kt` mengikat `WorkoutRepository` ke `OnlineWorkoutRepository`; `main/.../di/OnlineSessionModule.kt` menyediakan singleton store + `LegacyGuardedSessionStart` | Jangan ulang pemasangan dari nol. Production masih mengikat `ExerciseRepositoryImpl` dan coordinator/sync lifecycle; facade bebas Room bukan seluruh aplikasi bebas Room |
| Facade sesi | `main/.../data/repository/OnlineWorkoutRepository.kt` memakai store untuk Start/mutate/finish/cancel/peek; finish masih mengambil detail setelah receipt, API bulk/whole-snapshot lama masih `error()` | Return hanya `receipt.workoutId`, pindahkan fetch ke summary; migrasikan caller typed dan hapus traps/API/binding obsolete setelah fixture siap |
| Store | `main/.../data/remote/online/OnlineSessionStore.kt` punya mutex, confirmed snapshot, exact pending call/explicit retry dan auth reset; belum ada draft/save-phase/debounce/flush. `preserve` mendahului `repository.execute`; accept receipt lebih tua melempar Protocol | AC13/15 tetap wajib: raw input memori + debounce 400 ms + flush/ack status. Registration callback sesudah ledger gate; resolve original, replay tua acknowledge tanpa downgrade; reload tidak acknowledge unknown |
| Timer/service | Store `peek()` memeriksa loaded/identity tetapi hanya raw status ACTIVE/PAUSED, belum effective status/review; `main/.../core/service/WorkoutService.kt` menangkap gagal cancel lalu tetap `stopWorkout()` | Validasi verified effective session, explicit expiry acknowledgement dan cold verification. Gagal cancel tidak boleh tampil stop-success; timer cold-offline menunggu online sesuai D02, deadline asli dipertahankan |
| Readiness | `main/.../data/legacy/LegacyStartPreflight.kt` benar-benar dipakai store lewat guard; memeriksa active session, satu page 100 receipt dalam 90 hari, lalu lookup canonical per resource; `hasMore` → Unknown; second inventory + expected identity existing | >100 receipt terminal dapat memblokir Start tanpa blocker. Ganti dengan resource-authoritative readiness + targeted request lookup, bukan menaikkan cap/full-pull. Komentar “seam only / NOT bound” dalam source sudah stale terhadap DI |
| Recovery UI | `main/.../presentation/ui/recovery/LegacyRecoveryViewModel.kt` punya review/inspect raw record/keepHistory dan reset/cancel saat identity berubah | Reuse layar/archive existing, bukan UI paralel. Lengkapi selected stable aggregate, exact mapper, durable action/checkpoint, validated receipt, local-active/intent/server-legacy resolution dan installed-data proof |
| Auth/profil/guard | `main/.../data/repository/AuthRepositoryImpl.kt` masih menerima database, scheduler, lifecycle dan sync preferences; registration enrichment dapat gagal lalu cache input. `production/.../di/RemoteReadModule.kt` guard template masih DAO Room | Owner/epoch-bound returned-row profile patch, upload failure/cancellation benar, memory-only current user dan auth SDK-only identity; putus Room/coordinator tanpa prune legacy. Invalidasi query workout/profile terarah, bukan global full-pull |
| Fixture release/backup | `androidTestProduction/.../core/timer/RestTimerProductionReleaseTest.kt` mengimpor token sintetis tidak valid dan seed `ActiveSessionEntity` via DAO, menjalankan skenario tanpa network; `main/res/xml/backup_rules.xml` masih template komentar | Pindahkan fixture ke local Auth+RPC/store nyata sebelum menghapus Room. Pisahkan warm/warm-offline/cold-offline/cold-online; backup allowlist database dan tiga DataStore, bukan token/timer/recovery authority |

**Keputusan tetap D01–D06:** tidak ada durable draft/queue runtime baru; recovery checkpoint hanya transisi legacy. Local-active hanya dapat diselesaikan setelah konfirmasi eksplisit **“Simpan sebagai arsip lama, tidak dapat dilanjutkan”**, sumber tetap ada dan bukan imported/resumable session. Completed-workout import tetap implementasi nyata. Unknown/unrepresentable source tetap blocked/recoverable, bukan discard agar gate hijau.

#### Kontrak backend recovery — Proposed, bukan Verified

Empat RPC additive berikut **Proposed** untuk migration baru `20261029000100_online_only_legacy_recovery.sql`, bukan RPC existing atau hasil SQL test. Exact signature untuk parity dengan Feature Spec/Implementation Plan:

```sql
public.get_start_recovery_status(p_contract_version integer default 1) returns jsonb
public.get_legacy_recovery_page(p_cursor text default null,p_limit integer default 20) returns jsonb
public.resolve_legacy_session(p_request_id uuid,p_session_id uuid,p_expected_source_fingerprint text,p_resolution text) returns jsonb
public.import_legacy_workout(p_request_id uuid,p_source jsonb,p_workout jsonb,p_exercises jsonb,p_sets jsonb) returns jsonb
```

- Readiness: satu snapshot, contract 1, `can_create` dari EXISTS seluruh blocker, preview maksimal 20 urut resource ID; `has_more_blockers` tetap RecoveryRequired. Blocker legacy unresolved/ACTIVE/PAUSED/expired unresolved, bukan receipt historis terminal; expired resolved tidak memblokir. Contract lain `PT400/VALIDATION`. Recovery page explicit UI keyset id/owner-query cursor, limit 1–100, raw legacy tetap null-version/schema.
- Server legacy: hanya `KEEP_ARCHIVED`, fingerprint lowercase SHA-256 JSONB field asli tanpa status/review/updated_at; raw_source utuh (termasuk original updated_at) disimpan private sebelum archive. Replay sebelum CAS, owner/row lock, review + receipt atomik, tanpa delete/fabricate version/schema. Missing/foreign `PT404/RESOURCE_NOT_FOUND`; changed source `PT409/LEGACY_SOURCE_CHANGED`. Create/current lookup hanya mengecualikan review yang fingerprint-nya cocok. Versioned expiry tetap `AcknowledgeExpiry`.
- Completed import: schema 6/7/9, `kind:"WORKOUT"`, `mapping_version:1`, UUID asli parent/child/set dan owner token. Exact decimal string/timestamp UTC tanpa fallback-now/rounding, uncompleted sets dan null provenance dipertahankan. Strict allowlist/parentage/dependency/totals; mismatch review, bukan upsert/fallback ID. Registry private + resource WORKOUT + canonical digest server; insert-or-exact-match (`IMPORTED`/`MATCHED`), tanpa rewrite existing. Conflict/child collision rollback seluruh aggregate/registry/receipt, replay same immutable body/IDs; receipt ≥90 hari, tanpa purge.
- Security: reuse helper private, `SECURITY DEFINER search_path=''`, authenticated hanya empat RPC publik, public/anon revoked; registry/reviews RLS tanpa direct grant. Audit exact 17 existing + empat RPC baru, tidak prefix-only dan tidak membuka `upsert_workout_aggregate`.

#### Protokol selected-item recovery — Proposed

Reuse inventory/reader/archive/route/layar existing. `LegacyAggregateReader` membaca stable read-only snapshot parent+children+dependencies; exact 6/7/9 dan cap 10.000 owned rows, tanpa truncated success atau menebak schema 4/8. Mapper tidak memakai Room entities/Double uploader. Recovery checkpoint di `noBackupFilesDir/legacy_recovery`, AtomicFile+fsync, owner-hashed, maksimal 4 MiB/owner dan 10.000 entries; corrupt/foreign/unsupported/overflow blocked, bukan overwrite kosong.

API coordinator suspend: `review():LegacyRecoveryReview`, `prepare(identity:AuthIdentity,selection:LegacySelection):LegacyRecoveryAction`, `submit(identity:AuthIdentity,actionId:String):LegacyRecoveryResult`, `resolve(identity:AuthIdentity,actionId:String):LegacyRecoveryResult`. Durable record formatVersion 1 menyimpan owner/source/aggregate/dependency fingerprint, action/request/operation, immutable JSON+body digest, mapping/review dan validated receipt; state `AWAITING_REVIEW,PREPARED,SUBMITTED,CONFIRMED,CONFLICT,BLOCKED`. PREPARED durable sebelum return; SUBMITTED sebelum network; CONFIRMED hanya receipt tervalidasi identity/operation/resource/source/mapping/counts, bukan hash Android dianggap sama dengan server digest. Source berubah setelah dispatch → resolve original dahulu; auth switch tidak publish sukses ke owner baru.

Pending CREATE workout dipilih explicit; UPDATE/DELETE tanpa CAS tetap review+history, bukan create tersamar. Template intent Room9 memakai request `save_template_revision` asli + targeted operation lookup; COMMITTED tetap replay payload asli, NOT_FOUND hanya retry eksplisit request/IDs asli; sidecar resolution tanpa mark-synced/delete source. Remote ACTIVE/PAUSED resume/cancel await receipt; expired versioned explicit acknowledgement; server legacy page + archive RPC. Tidak auto merge/replace/upload-all. Readiness tetap re-inventory/expected-epoch; A resolved boleh Start meski B pending, tanpa melihat/mengunggah/menghapus data B.

#### Planned execution gates — seluruhnya NOT_TESTED

| Gate / langkah | Scenario dan evidence wajib terhadap source final | Status |
| --- | --- | --- |
| 1 — backend recovery/security | Migration additive + SQL/transport/replay/security; exact 21 signature, grants/private RLS; deny anon/foreign/direct/old RPC/forged receipt; rollback collisions | **NOT_TESTED** |
| 1 — bounded Start | 101/1000 receipt terminal tidak block; blocker di luar preview 20 tetap false; acknowledged expiry tidak block; tanpa all-receipt scan | **NOT_TESTED** |
| 1–2 — import/replay | Same/new request same source satu UUID/mapping; changed fingerprint/payload/child collision conflict tanpa overwrite; exact >2 decimals/null provenance dan completed totals | **NOT_TESTED** |
| 2 — selected recovery | 6/7/9 A/B, local-active+remote-active, template intent response-loss, explicit archive/receipt; source tetap owner-accessible; unknown/conflict zero create | **NOT_TESTED** |
| 2 — crash/restore/upgrade | Kill PREPARED/SUBMITTED/commit-before-response/response-before-checkpoint; restored/changed source review ulang; same-package/signature install -r 6/7/9, committed WAL, corrupt/4/8/unknown blocked; fresh no DB, logout no prune | **NOT_TESTED** |
| 3 — AC12–19 dirty/flush | Edit A receipt tertahan lalu ketik B; complete <400 ms; B tetap, flush→latest-version complete receipt→rest; Saved hanya tanpa drafts/pending/error/review/verification | **NOT_TESTED** |
| 3 — unknown/CAS/registration | Drop response setelah commit, resolve/retry exact original; NOT_FOUND bukan request baru; stale replay tidak downgrade; conflict review; template unknown tidak menciptakan pending sesi palsu | **NOT_TESTED** |
| 3/6 — AC20–21 timer | 10k peek/ticks +60s actual running observation zero transport per tick; warm/offline/cold-online/offline, sleep/doze/reboot/clock/permissions/render failure; cold offline no expiry commit/audio/completion, online deadline asli; failed cancel retain locked state | **NOT_TESTED** |
| 4 — profil/auth | Omitted/null, owner/zero/malformed row, failed upload/PATCH/cancellation, A→B→A; hanya returned row success, canonical avatar path/placeholder, auth tanpa Room/prune | **NOT_TESTED** |
| 4 — AC01–11/22/26–27 read/invalidation | >500 history, paging/filter/append race dan retained routes; finish/import/profile event owner+date scoped, template changes tidak global refresh; no stale account publication | **NOT_TESTED** |
| 5 — clean cutover | Semua caller typed/bounded, obsolete APIs/traps/writers/Room runtime/binding/test/Gradle rules dilepas atomik; schema exports 6/7/9, reader dan database/WAL pengguna dipertahankan | **NOT_TESTED** |
| 6 — backup/fixtures | Database `.` + file DataStore theme/locale/workout preferences allowlist untuk cloud/device transfer; no restored token/timer/ack authority; local real Auth/RPC fixture, adb reverse 127.0.0.1, guarded disposable emulator/non-owner user | **NOT_TESTED** |
| 6 — actual HTTP/UI/minified | Login→routine→Start→edit→complete→finish→summary→history/progress/profile, restart/response loss/account switch actual UI; unit/build dua flavor, production normal dan instrumented minified; API30/34/37 sesuai matriks plan | **NOT_TESTED** |
| 6 — surface AC15/T15 | ID/EN, light/dark, 320dp, font100/150/200%, IME/TalkBack, ≥48dp; screenshots+interaction aktual, bukan source-only | **NOT_TESTED** |
| Exit lokal | AC01–AC34 dan T01–T16 dipetakan evidence/N/A scope sah; manifest SHA256 final, migration inventory/signatures, command/variant/device/count/failure/skip, screenshot dan redacted trace/receipt fixture | **NOT_TESTED** |

Urutan eksekusi: langkah 1 → 2; langkah 3 dan 4 dapat dikerjakan pada area independen; siapkan fixture remote langkah 6 sebelum langkah 5 menghapus Room; proof final sesudah clean cutover. Runtime draft tetap hanya memori (D01), bukan checkpoint recovery. Normal minified release disalin sebelum test keep-delta build menimpa; proof visual tidak dilabeli instrumentation-tested. Signing publish, hosted backup/cutoff/deployment dan vendor-specific physical behavior di luar proof emulator tidak diklaim lulus.

**Prasyarat/evidence final:** gunakan command final di Implementation Plan v2.0, backend lokal dan synthetic accounts saja. Runner migration harus membuat platform-only dump lokal temporary, tidak bergantung `/tmp/imfit-f1b-baseline-c0be6b8.dump` historis atau dump hosted; staged lifecycle/security replay tetap wajib. Preserve/restore nilai unrelated `local.properties`, jangan print secret; loopback transport guard tetap, pakai `adb reverse` + `127.0.0.1`, bukan melonggarkan HTTP ke `10.0.2.2`. Native command gagal menghentikan urutan; environment blocked dicatat konkret dan tidak diganti fake/hosted PASS. Evidence hanya di direktori temp baru, tanpa laporan keempat.

**Inconsistency historis yang dipertahankan:** §7.9 menulis “114/114 PASS, 0 fail, 13 skipped” dan beberapa total putaran awal yang tidak menjelaskan denominator skip secara konsisten. Itu disimpan sebagai kutipan hasil historis, bukan dinormalisasi atau diklaim rerun. Gate final harus mengambil executed/skipped/failure dari artefak aktual. Komentar seam guard source yang stale tidak mengalahkan binding DI; §7.8 tetap catatan historis, bukan keadaan runtime sekarang.

### 7.11 Persiapan commit host-only — 29 Oktober 2026

**Izin eksplisit:** pengguna memilih persiapan saja, perbaikan terarah dan host-only. Tidak ada izin staging/commit/worktree/push, fixture Docker/backend atau emulator/device. Tidak ada Git mutation, remote access atau konfigurasi rahasia yang diubah. Branch `online-only`, base HEAD `8af0142c509e47ecdc2b645e89cf9b872fd03ffc`; index awal kosong. Review mencakup 172 tracked changes dan 27 file baru sebelum perbaikan, bukan bukti pembuatnya. `.idea/misc.xml` tetap di-defer dan tidak disentuh.

**Manifest perbaikan sesi ini:** `KtorReadTables.kt` melepas `limit` pada owner-primary-key profile query agar PATCH tidak memakai limited-update tanpa order; `ProfilePatchTransportTest.kt` mensimulasikan penolakan PGRST109 dan memastikan returned-row success; `G3ReadTablesTest.kt` menghapus hanya assertion incidental `limit=1`, mempertahankan owner/column/token checks. Home callback di `NavGraph.kt` mengirim `allowCreate=false` untuk resume; `run_online_only_ready.py` mengembalikan reverse `54321→54321` sebelum timer suite; blank EOF `SessionDomainAdapter.kt` dan `HomeComponents.kt` dihapus. Tiga dokumen plan/spec/report diperbarui. Perubahan lain sudah ada sebelum sesi ini, asalnya belum diketahui; seluruh index tetap tidak diisi.

Usulan dependency closure tetap **belum dieksekusi**: sembilan path backend recovery/SQL/audit/runner dahulu, lalu satu commit Android berisi source/caller/DI/tests/Room removals/Gradle, tujuh path tools dan tiga dokumen terkait. `G3ReadTablesTest.kt` kini menjadi satu path tracked tambahan akibat regression maintenance sesi ini. Scope/diff harus direvalidasi dan izin staging/commit diminta lagi sebelum mutasi Git; persetujuan perbaikan bukan izin commit.

#### Identitas bukti dan lingkungan

Check dijalankan pada root repository `imfit-mobile`, Windows x64, JBR `21.0.11`, Gradle wrapper `9.5.0`, Python `3.13.15`; Gradle memakai `--offline` dan cache lokal. Source fingerprint final **`298325899be697d114fc766802ac6d5de626a2990952ab990a607561abe64605`**, 404 input menurut `tools/online_ready_support.py::snapshot_sources`. Manifest mencakup source/schema/Gradle/migration/tests/tools, tidak mencakup `.idea`, docs, `local.properties`, build output atau secret. Input manifest tidak berubah selama gate final; ini **dirty working-tree evidence**, bukan snapshot index/commit terisolasi, dependency resolution bersih atau CI.

Metadata/hash dan salinan APK normal/instrumented/test dipertahankan di `%TEMP%/imfit-commit-prep-zbibnylx/host-evidence.json` beserta tiga APK; recorded UTC `2026-10-29T10:04:45+00:00`. APK normal disalin sebelum build keep-delta menimpa output release. Manifest evidence ini bukan build-provenance sidecar yang diminta readiness runner; readiness runner end-to-end tidak dijalankan.

#### Check yang benar-benar dijalankan

Semua baris final di bawah memakai dirty input final di atas, kecuali dua failure historis yang dijelaskan setelah tabel.

| Check/command | Status | Exit / hasil | Batas cakupan |
| --- | --- | --- | --- |
| `./gradlew.bat :app:testDemoDebugUnitTest :app:testProductionDebugUnitTest :app:assembleDemoDebug :app:assembleProductionDebug :app:assembleDemoDebugAndroidTest :app:assembleProductionRelease --console=plain --offline` | **PASS** | Exit 0; demo 421 dan production 414 unit, masing-masing 0 failure/error/skip; kedua debug APK, demo androidTest APK dan normal production minified APK berhasil | Working tree/cache lokal; tidak menjalankan device test |
| `./gradlew.bat -PrestTestBuildType=release :app:assembleProductionRelease :app:assembleProductionReleaseAndroidTest --console=plain --offline` | **PASS** | Exit 0; app dan test APK minified berhasil; hash app berbeda dari normal release | Build saja, bukan instrumentation execution |
| `python -B -m unittest discover -s supabase/tests -p test_verify_local_baseline.py -v` | **PASS** | Exit 0; 12 tests | Host target/dump guard tests, bukan SQL/HTTP/backend proof |
| `python -B -m unittest discover -s tools -p test_rest_timer_regression.py -v` | **PASS** | Exit 0; 13 tests | Trace analysis host, bukan timer perangkat |
| `python -B tools/run_online_only_ready.py --help` dan `python -B tools/run_rest_timer_suite.py --help` | **PASS** | Kedua CLI exit 0 dan menampilkan opsi | Smoke startup/import/parser saja; tanpa fixture/backend/device |
| Python Eval `Exercising readiness reverse handoff` | **PASS** | Statement aktual handoff dan guard timer dieksekusi dengan device/subprocess simulation: tanpa repair ditolak `Conflicting reverse mapping`, sesudah repair diterima | Host simulation tanpa adb; bukan seluruh runner |
| Backend final SQL/transport/replay/security, actual profile PATCH HTTP | **BLOCKED** | Tidak dijalankan; izin host-only | Tidak mengakses atau memodifikasi backend lokal/hosted |
| UI resume, connected demo/production, upgrade/restore/crash, warm/cold timer | **BLOCKED** | Tidak dijalankan; izin host-only | Tidak ada visual/device/runtime acceptance claim |
| Commit snapshot/worktree/CI | **NOT_TESTED** | Tidak ada commit baru; staging/commit/worktree tidak diizinkan | Build dirty tree bukan bukti setiap commit buildable |

**Failure-before dipertahankan:** targeted `:app:testProductionDebugUnitTest --tests com.diajarkoding.imfit.data.remote.online.ProfilePatchTransportTest --console=plain --offline` setelah penambahan simulated PGRST109 guard, sebelum perbaikan query: exit 1, satu dari enam test gagal dengan OnlineFailure. Ini reproduksi host protokol, bukan respons PostgREST nyata. Gate host gabungan pertama setelah query fix juga exit 1 (421 demo tests, satu failure): assertion GET `limit=1` yang incidental. Fingerprint input putaran itu `880ad865616b01a6a73027cfa0b7b5df95a5a0be1cb825a9ace83ec91c98c5a5`; assertion dihapus, bukan dipin ke default baru. Gate final sesudah perubahan lulus; ProfilePatchTransportTest enam dari enam pada masing-masing flavor.

**Batas yang tetap terbuka:** empat acceptance crash/changed-fingerprint restore masih `NOT_TESTED` dalam readiness runner; helper crash tidak terhubung ke entry runner pada review ini. Sidecar provenance APK tetap prerequisite runner. Resume route dikoreksi dan dikompilasi, tetapi actual UI absence/race belum diuji. Tidak ada klaim internal release-ready, hosted deployment, publish signing, atau push-ready. Bukti §7.1–§7.10 tidak diubah menjadi PASS current.


### 7.12 Checkpoint handoff perangkat

**Tujuan dan izin:** setelah meminta panduan pindah perangkat, pengguna kembali meminta commit dan push remote. Scope adalah checkpoint pengembangan lengkap, bukan internal release-ready. Staging selektif, commit lokal, clean-worktree verification dan push `origin/online-only` diizinkan untuk scope source yang sudah direview. Tidak ada izin deployment/cutoff hosted, fixture backend/device, reset/purge atau perubahan data pengguna.

**Manifest:** 199 path include: sembilan backend recovery/migration/SQL/audit/runner, 180 Android source/build/test/deletion, tujuh tools dan tiga dokumen. Perubahan awal tetap beratribusi belum diketahui; hunk perbaikan sesi ini dijelaskan di §7.11. `.idea/misc.xml` di-defer, local.properties/credential/.env/APK/build output tidak dimasukkan. Tidak ada lockfile/generated-output churn dalam scope. Satu commit atomik menggantikan usulan backend-lalu-Android agar source handoff memuat seluruh dependency closure.

**Gate checkpoint:** review seluruh index, `git diff --cached --check`, lalu commit aktual diperiksa terhadap manifest/tree termasuk efek hook. Jalankan kembali unit/build host dua flavor, normal/instrumented minified, Python guard/analysis dan CLI/document smoke pada SHA di worktree bersih sebelum push. Cache toolchain standar boleh digunakan; jangan menyalin source, node_modules, build output atau secret dari workspace utama. Missing dependency/config adalah blocker; tidak bypass hook atau menganggap dirty-tree PASS sebagai clean-SHA PASS. Hasil aktual SHA/command/status dilaporkan saat eksekusi, bukan diklaim terlebih dahulu di catatan ini.

**Gate rilis yang ditunda:** SQL/HTTP/RLS/replay/import aktual, UI resume/race, installed upgrade/restore/crash dan timer warm/cold tetap NOT_TESTED/BLOCKED sesuai §7.11. Empat acceptance crash/changed-fingerprint serta provenance sidecar APK masih terbuka. Checkpoint ini tidak menyatakan fitur selesai, reproducible lintas mesin, CI hijau, merge-ready atau release-ready. Tidak menjalankan migration hosted sebagai efek push.

**Temuan review staged untuk dilanjutkan:** seluruh 199 path staged ditinjau; backend/tools/docs tidak menunjukkan checkpoint scope/secret/dependency blocker. Review Android menemukan tiga defect integrasi pada source yang dipertahankan dalam checkpoint ini, bukan hasil runtime test dan bukan perbaikan yang telah selesai:

| Prioritas | Source evidence | Risiko dan tindakan berikutnya |
| --- | --- | --- |
| P1 | `OnlineRpc.kt:159–162` mewajibkan UUID untuk `exercise_id`; seed catalog memakai `ex_back_1` dan ID text lain | Import legacy dengan ID katalog valid dapat ditolak sebelum dispatch. Pisahkan validasi catalog text dari UUID aggregate; reproduksi dan uji regresi sebelum rilis |
| P1 | `OnlineSessionStore.reload` mempertahankan `requiresVerification=true` untuk conflict berdraft; `ActiveWorkoutScreen.kt:563` menyembunyikan aksi resolve saat flag itu true | Setelah authoritative conflict reload, Use server/Reapply tidak dapat dijangkau lewat UI. Expose readiness setelah reload tanpa melemahkan guard sebelum reload; buktikan actual UI transition |
| P2 | `RestNotificationInteractionTest.kt:52–56` memakai set ID yang sama untuk dua iterasi; timer menolak duplicate-start set | Kasus body-notification kedua berisiko tidak pernah mendapat timer baru. Siapkan dua set aktual berbeda dan jalankan instrumentation |

Temuan ini tidak diperbaiki diam-diam dalam task commit/push: tujuan checkpoint adalah memindahkan pekerjaan beserta defect/acceptance yang masih terbuka. Hasil unit/build host tidak dianggap menyangkal temuan integrasi tersebut; bukan merge-ready.

**Melanjutkan di perangkat lain:** setelah push terkonfirmasi, clone branch `online-only` dari `https://github.com/diajarkoding/imfit-mobile.git` dan cocokkan HEAD dengan SHA handoff yang dilaporkan. Siapkan JDK21, SDK37, Node≥20/Python≥3.11; gunakan `npm ci` untuk dependency CLI pinned. Buat konfigurasi SDK/backend lokal di luar Git tanpa menyalin secret ke repository. Jalankan command host §7.11 terhadap checkout baru sebelum melanjutkan gate backend/device di lingkungan terisolasi yang disetujui.

APK normal/instrumented/test dan evidence `%TEMP%` §7.11 tidak ikut Git. Database Docker, emulator, user fixture dan data aplikasi juga tidak berpindah melalui clone. Konfigurasi sensitif atau data pengguna yang perlu dipindah memerlukan kanal aman dan izin tersendiri, bukan tambahan file commit.

### 7.13 MVP internal preview T5

**Hasil final — 29 Oktober 2026: PASS untuk fresh-install internal preview dengan Supabase lokal.** Source dibekukan pada `online-only@5372f210b26b3ec9f846171cbbfd63e81f4fa482` + perubahan T2/T3 yang masih dirty, fingerprint `544c6a58ebb2b32e978c82a64b9dfc499f04cc7ad1c736e0d885739fb2201e44`. Tidak ada perubahan source/dependency selama T5; docs hasil tidak termasuk source fingerprint. Ini bukan bukti clean commit/CI atau production release-ready.

| Verifikasi final | Hasil / cakupan |
| --- | --- |
| `./gradlew :app:testDemoDebugUnitTest :app:testProductionDebugUnitTest --console=plain --offline` | **PASS sekali pada source final** — production 417, demo 424; 0 failure/error/skip |
| `./gradlew -PmodalDiagnostic=true :app:assembleProductionDebug :app:assembleProductionDebugAndroidTest --console=plain --offline` | **PASS** — production graph nyata, app/test APK debug terisolasi; bukan demo/fake atau build minified |
| `OnlineOnlyReadyTest#onlineUiJourney` | **PASS** — login UI, buat rutinitas Home, catalog picker, guarded Start, pause/resume, edit 12.25 kg × 7, complete/skip rest, Finish → Summary 85.75 kg → History/Progress/Profile |
| `OnlineOnlyReadyTest#verifyFinishedRestart` | **PASS** — proses dihentikan/dibuka kembali; SDK auth pulih dan completed workout ID yang sama tetap ditemukan di backend dan History UI |
| `OnlineOnlyReadyTest#lostResponseAndAccountSwitch` | **PASS** — actual committed Finish response diputus proxy, unknown tidak dianggap sukses, explicit Resolve UI menghasilkan receipt/Summary yang benar; A→B→A tanpa state/data bocor |
| Trace dan persistence backend | **PASS** — tepat 2 request Finish untuk 2 alur, 1 committed response drop, 2 workout unik saat read ulang; cleanup exact 2 akun sintetis dibuat invocation ini |
| Artefak | **PASS** — signature verified, byte/hash identik dengan APK yang berhasil dipasang dan diuji pada Pixel_5_API_34 existing (`emulator-5554`, API34, user fixture10) |

**APK yang diserahkan:** [imfit-online-only-internal-preview-20261029.apk](../../app/build/outputs/apk/internal-preview/imfit-online-only-internal-preview-20261029.apk), **22.911.365 bytes**, variant `productionDebug`, package **`com.diajarkoding.imfit.modaldiagnostic`**, signing **debug/internal**. Ini APK aplikasi, bukan androidTest; package terisolasi dipilih agar tidak mengganti aplikasi/data IMFIT existing. SHA256 **`56fda795603d4edc9f63c52a98e7235b07606cbacea2b4b1611420af3a006af4`**; checksum sidecar `.apk.sha256` tersedia di folder yang sama.

**Konfigurasi penggunaan preview:** API baked-in **`http://127.0.0.1:54321`**, Supabase lokal project `IMFIT-MOBILE`, Docker context `colima-imfit-online-verify`, CLI pinned 2.116.0, PostgreSQL 17.6 existing, 14 migration/21 signature RPC. Backend existing sehat dan exact migration inventory diverifikasi; tidak menerapkan migration baru atau mengulang suite SQL/concurrency/10k-row karena source backend tidak berubah sejak T1. Untuk Pixel 5 tersebut, gunakan `adb -s emulator-5554 reverse tcp:54321 tcp:54321`, lalu login/buat akun uji lokal; akun sintetis smoke telah dibersihkan. Backend lokal dan adb reverse diperlukan setiap pemakaian di lingkungan ini. APK ini **tidak otomatis bekerja dari jaringan klien luar**; deployment backend dan verifikasi koneksi eksternal merupakan tahap terpisah dengan izin eksplisit. `local.properties` asli dipulihkan byte-for-byte, sehingga build ulang harus secara eksplisit memakai URL/key lokal, bukan config hosted existing.

**Evidence final di luar Git:** `/private/var/folders/yv/171k2lgd0jdg4y0jclvxjtzw0000gn/T/opencode/imfit-t5-xa5ue294/` menyimpan `artifact-manifest.json`, `sources-before.json`, `checkout.json`, `build-results.json`, `full-regression-results.json`, `full-regression.log`, `apk-build.log`, `device/result.json`, tiga log instrumentation dan screenshot Summary. Temporary invocation memakai guard/fixture/proxy/adapter instrumentation existing di `tools`; tidak menambah runner repository atau dokumen baru. Jangan membagikan backup `local.properties.original` yang sensitif. User emulator kembali 0, reverse sementara dihapus, dan hanya akun/package fixture milik run dibersihkan; aplikasi/data existing serta database/WAL/schema 6/7/9 tetap dipertahankan.

**Deferred/FAIL/NOT_TESTED tetap jujur:** minified instrumentation T3 masih **FAIL/deferred** (`NoSuchMethodError mutableIntObjectMapOf` pada Compose test), tidak dijalankan ulang atau diklaim PASS oleh build debug. Full connected demo, matriks timer cold/warm/perangkat/API/font/accessibility lengkap, installed upgrade/restore/import/crash legacy, hosted backup/rollout/cutoff, publish signing dan koneksi klien eksternal **belum dibuktikan**. Python guard/analyzer dan suite SQL yang tidak berubah tidak diulang; bukti lama tidak diubah menjadi PASS baru. Seluruh D01–D06/K01–K05 dan 34 AC tetap berlaku; acceptance MVP bukan sertifikasi seluruh matriks production. **Tidak ada blocker internal preview tersisa; berhenti pada penyerahan APK dan backlog deferred.**

## 8. Keputusan yang dibutuhkan

1. **Desain lokal diizinkan, deployment belum.** F1a desain dan K01–K05 dicatat; implementasi follow-up additive di lokal mengikuti instruksi pengguna. Tidak ada izin deployment/cutoff pada Supabase utama.
2. **Lingkungan preview lokal siap dalam scope T1/T5.** Colima/backend existing dan 14 migration diperiksa; proof final ada di §7.13, bukan audit hosted atau restore proof. Cleanup database/volume tetap tidak otomatis dan API lokal tidak otomatis terjangkau klien luar.
3. **Timezone/durasi/volume: selesai K01/K02.** UTC storage, IANA timezone query, tanggal start, history termasuk pause, input weight baru maksimal 2 desimal kg, numeric exact + decimal string wire; data legacy tidak di-round/rewrite.
4. **Scope UI: selesai K03.** History per tanggal + template Home; tidak menambah route.
5. **Profile/cutoff: selesai K04/K05 untuk desain lokal.** Profile tetap direct dengan column grants minimum; compatibility/final-lockdown diuji terpisah. Production cutoff masih menunggu review/izin setelah bukti lokal.

## 9. Langkah selanjutnya

**T0–T5 selesai; tidak ada task MVP aktif tersisa.** Gunakan APK/config §7.13 dan [tasks.md](tasks.md). Jangan mengulang audit/build/suite atau membuka kembali F1a/F1b/F2/G3 tanpa kegagalan baru yang reproduksibel.

Langkah setelah handoff hanya bila diminta: defect preview terarah atau keputusan deployment backend/verifikasi koneksi klien luar. Backlog production/legacy/minified tetap tersedia di Plan; tidak otomatis menjadi pekerjaan baru. Supabase utama/cutoff/penghapusan data lama, release publik dan commit/push memerlukan otorisasi yang sesuai.

**Batas eksekusi terbaru:** MVP fresh-install internal preview selesai pada T5; bukti hanya lingkungan lokal dan Pixel 5 API34 existing yang disebutkan. Deployment Supabase utama, penghapusan data lama, publish signing dan distribusi eksternal tidak dilakukan/diotorisasi oleh penyerahan ini.

## 10. Changelog laporan

| Versi | Tanggal | Perubahan |
| --- | --- | --- |
| 1.0 | 2026-10-27 | Laporan tunggal dibuat: G0 final, audit live G1, matriks temuan, blocker, checklist, dan keputusan berikutnya |
| 1.1 | 2026-10-28 | Baseline c0be6b8; kontrak desain F1a dan K01–K05 diselesaikan; CLI pinned dipulihkan, setup Docker diizinkan; diagnosis MCP DNS/konektivitas; runtime backend tetap NOT_TESTED |
| 1.2 | 2026-10-28 | Exit F1b lokal: 12 migration/16 RPC, SQL fixtures, 57 final transport checks, race/response-loss, security cutoff, backup/replay/upgrade dan lint/advisors; production/Android belum diaktifkan |
| 1.3 | 2026-10-28 | F2 foundation terisolasi: 16 typed RPC/DTO, error adapter Ktor, auth epoch, memory-only mutation recovery, fake API dan Paging common spike; gate regresi/Git dicatat di §7.4 |
| 1.4 | 2026-10-28 | Rekonsiliasi bukti commit/push F1b/F2 dan checklist G2; implementasi runtime G3, bounded Home RPC lokal, minimum template CAS bridge; gate final masih diverifikasi (§7.5) |
| 1.5 | 2026-10-28 | Rekonsiliasi hasil final G3: 292 production + 298 demo test, kedua APK debug dan androidTest APK; acceptance UI emulator tercatat BLOCKED karena crash qemu; bug mapping viewport dan warning tersisa dicatat sebagai gate terbuka |
| 1.6 | 2026-10-28 | Kelanjutan G3 pada 8abf100: stable-key viewport, refresh re-hint, host regression dan acceptance fixture demo pada emulator macOS terisolasi; bukti aktual di §7.6 |
| 1.7 | 2026-10-28 | State/error lintas reader, picker context/owner guard, reentrant auth publication, retained-route lifecycle dan saveable editor isolation; 13 host race tests dan fixture compact/font besar (§7.7) |
| 1.8 | 2026-10-28 | F6.1 read-only inventory, schema/owner safety, evidence checkpoint dan guarded-create seam; 35 host tests baru per flavor, 8 Android file/checkpoint tests dan combined 25 regresi PASS; G6a/G6b tetap terbuka (§7.8) |
| 1.9 | 2026-10-28 | F4 cutover runtime kedua flavor selesai diuji: facade `cancelWorkout` idempotent-terminal (retained/in-memory/reload), writer CAS satu intent per call, cancel await-receipt; F5 timer audit (peek memori murni); F7 smoke REST lokal SMOKE_OK; unit kedua flavor 387/395 PASS; rerun connected final di §7.9; manifest kumulatif `5bc83f11…`; commit/push dengan izin pengguna |
| 2.0 | 2026-10-29 | Rekonsiliasi metadata/ringkasan/checklist/kesimpulan/next steps dengan cutover §7.9; 387 production/395 demo unit dan REST smoke diberi label PASS historis, bukan rerun. Audit source §7.10, empat RPC/protokol recovery Proposed dan planned gates seluruhnya NOT_TESTED; target internal release-ready lokal tanpa deployment/cutoff utama, G4/G5/G6/release tetap belum selesai |
| 2.1 | 2026-10-29 | Persiapan commit host-only dengan izin terarah: PATCH/profile regression, Home resume tanpa create, reverse handoff dan EOF; 421 demo/414 production unit, debug dan normal/instrumented minified builds, 12+13 Python tests serta smoke CLI/simulated handoff PASS pada dirty fingerprint §7.11. Backend/device/commit terisolasi belum teruji; tidak stage/commit/push |
| 2.2 | 2026-10-29 | Penyelarasan terarah MVP: ringkasan bukti/blocker/pekerjaan tersisa, tasks.md sebagai checklist utama dan next steps T0–T5; gate legacy/production serta §7.1–§7.12 tetap historis/backlog. Tidak mengulang audit, build atau runtime test; tidak mengklaim MVP PASS |
| 2.3 | 2026-10-29 | T0–T5 selesai: satu full regression final 417 production/424 demo dan 3 smoke UI/HTTP nyata PASS; APK lokal terisolasi diserahkan dengan signature/checksum/config/evidence §7.13. Batas minified/legacy/production/hosted tetap deferred; histori dan kontrak tidak dihapus |
