# IMFIT Full Online-Only — Laporan Implementasi

## 0. Metadata dan aturan pemakaian

| Item | Nilai |
| --- | --- |
| Status | **G0 selesai; audit live G1 selesai; implementasi backend/Android belum dimulai** |
| Versi laporan | 1.0 |
| Tanggal diperbarui | 27 Oktober 2026 |
| Baseline commit | `fb96784d3164a700250e7032d6565779d288029d` (`online-only`) |
| Project Supabase | `ltcxltahkrjnsjaxndsa` — database bersama development/testing/production |
| Dokumen acuan | [Feature Spec v1.2](full-online-only-feature-spec.md) · [Plan Implementasi v1.2](full-online-only-implementation-plan.md) |
| Metode audit live | Supabase MCP, project-scoped, `read_only=true`, `features=database`; role `supabase_read_only_user`; `transaction_read_only=on` |
| Batas | Metadata saja; tanpa row bisnis, tanpa pemanggilan RPC aplikasi, tanpa DDL/DML, tanpa build/device test |

**Aturan:** file ini adalah **satu-satunya laporan implementasi** dan diperbarui di tempat. Jangan membuat laporan baru setiap iterasi. Klaim harus diberi label **Verified** (diamati pada source/live metadata), **Proposed** (desain), atau **NOT_TESTED** (runtime/test). Keberadaan SQL di repository bukan bukti deployment.

## 1. Ringkasan status

| Gate / workstream | Status 27 Oktober 2026 | Bukti / catatan |
| --- | --- | --- |
| G0 — keputusan produk | **Selesai** | D01–D06 tercatat di Feature Spec §1 dan Plan §1 |
| G1 — audit backend read-only | **Selesai** | L01–L09 pada Plan §10; matriks §2.1 |
| G1 — implementasi backend | **Belum mulai** | Menunggu persetujuan desain migration dan lingkungan uji terisolasi |
| G1 — integration/concurrency/security test | **NOT_TESTED** | Belum ada staging terpisah; D06 mengatur schema terisolasi/local |
| G2 — Android remote foundation | **Belum mulai** | Boleh paralel dengan fake transport setelah kontrak disetujui |
| Migrasi fitur Android | **Belum mulai** | Menunggu G1 dan G2 |
| Aktivasi production | **Terblokir** | B02–B05 belum ditutup |

**Kesimpulan:** pekerjaan dokumentasi dan audit sudah cukup untuk memulai desain backend additive. Database live **belum** memenuhi kontrak Full Online-Only; aplikasi **belum** boleh dipindahkan ke remote-only.

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

| ID | Blocker | Status | Gate penutupan |
| --- | --- | --- | --- |
| B01 | Kehilangan input unconfirmed | **Ditutup D01** | UI/error copy dan AC15/16/21 konsisten |
| B02 | Backend final | **Terbuka** — 16 RPC/revision/receipt absen | G1: migration + contract + integration tests |
| B03 | Migrasi legacy | **Terbuka** — scope Room 9/6/7 ditetapkan, device proof belum | Preflight §6, G6a–G6b |
| B04 | Write bypass | **Terbuka** — direct DML 7 tabel, child consistency gap | G1/G7: cutoff + bypass tests |
| B05 | Recovery/idempotency | **Terbuka** — belum ada schema/RPC | G1/G4/G5: replay/finish/cancel tests |

## 6. Checklist progres

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
- [ ] Konfirmasi timezone/durasi history/precision volume (P1)
- [ ] Konfirmasi scope feed history/template list (P1)

### G1 — backend

- [x] Audit read-only schema/RPC/RLS/grants/migration
- [ ] Setujui desain migration additive dan lingkungan uji terisolasi
- [ ] Migration revision/receipt template
- [ ] Migration session version/status/receipt/unique link
- [ ] Perbaikan FK template, unique owner, dan konsistensi `workout_sets`
- [ ] RPC read/pagination/detail/progress/prefill
- [ ] RPC create/get/update/cancel/finish + operation discovery
- [ ] Direct-write lockdown dan bypass test
- [ ] Integration/concurrency/RLS test
- [ ] Backup/recovery verification sebelum migration berisiko

### G2 — Android

- [ ] Remote repository contracts + fake transport
- [ ] Paging 3/cursor spike
- [ ] Pull-to-refresh/infinite scroll/error states
- [ ] Cancellation dan account-switch tests
- [ ] Legacy Room 9/6/7 inventory dan preflight
- [ ] Timer adapter terpisah dari Room

## 7. Perubahan yang sudah dilakukan

| Perubahan | Status | Catatan |
| --- | --- | --- |
| `docs/plans/full-online-only-feature-spec.md` v1.2 | Selesai | D01–D06, AC31–AC34, status live, TTL/receipt, scope legacy |
| `docs/plans/full-online-only-implementation-plan.md` v1.2 | Selesai | Matriks backend live, B01–B05, evidence L01–L09, fase F0/F1 |
| `docs/plans/full-online-only-implementation-report.md` | Selesai | Laporan tunggal ini |
| `supabase/tests/full_online_only_readiness_audit.sql` | Selesai | Script audit metadata read-only; belum dijalankan sebagai gate integration |
| `opencode.json` | Selesai | MCP Supabase project-scoped, read-only, database features |
| Branch | Selesai | `online-first` → `online-only`; branch remote diselaraskan ke `origin/online-only` |
| Migration/deployment | **Tidak dilakukan** | Tidak ada DDL/DML ke database |
| Perubahan aplikasi Android | **Tidak dilakukan** | Tidak ada perubahan source aplikasi |

## 8. Keputusan yang dibutuhkan

1. **Persetujuan desain migration F1.** Setujui urutan additive: revision/receipt template → session lifecycle/receipt → read/pagination RPC → constraint/RLS hardening. Belum ada deployment sampai desain dan rollback direview.
2. **Lingkungan uji terisolasi.** Karena belum ada staging (D06), tentukan apakah pengujian memakai schema terisolasi pada project bersama atau Supabase lokal. Backup dan verifikasi recovery wajib sebelum migration berisiko.
3. **Timezone/durasi/volume.** Konfirmasi definisi tanggal/timezone, durasi history (tetap end−start termasuk pause), dan precision volume sebelum UI/statistik dikerjakan.
4. **Scope feed history/template list.** Konfirmasi apakah route baru diperlukan atau cukup mempertahankan entry per tanggal dan Home template section.
5. **Kebijakan profile.** Putuskan apakah update profil tetap direct atau dipindahkan ke RPC saat lockdown; ini memengaruhi daftar grant yang dicabut.

## 9. Langkah selanjutnya

1. Review dan setujui desain migration F1 serta lingkungan uji.
2. Susun migration additive dan fixture SQL terisolasi **tanpa deploy**.
3. Siapkan kontrak RPC/read/session/receipt beserta DTO dan error mapping.
4. Jalankan audit readiness pada environment uji dan simpan evidence role/read-only/timestamp.
5. Mulai G2 parallel: remote contracts, fake transport, Paging spike, dan preflight legacy.
6. Perbarui laporan ini pada setiap iterasi; jangan membuat laporan baru.

## 10. Changelog laporan

| Versi | Tanggal | Perubahan |
| --- | --- | --- |
| 1.0 | 2026-10-27 | Laporan tunggal dibuat: G0 final, audit live G1, matriks temuan, blocker, checklist, dan keputusan berikutnya |
