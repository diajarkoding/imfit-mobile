**Status:** T01–T10 selesai di repository dan terverifikasi di PostgreSQL terisolasi. G01/G02 selesai untuk lingkup repository. Deployment Supabase live **BLOCKED oleh G03**; G04/T11 belum deployed. T12–T18 menunggu G04 sesuai urutan eksekusi. Implemented tidak berarti deployed.

## Phase 1 — Database & Backend Contract v3

- [x] T01 — Rekonsiliasi database. Bandingkan schema Supabase live, SQL migrations repository, RPC
  aktif, dan Room v9. Identifikasi perubahan yang belum diterapkan serta siapkan backup SQL sebelum
  DDL.
- [x] T02 — Migrasi katalog exercise. Tambahkan `tracking_profile` dan `supported_metrics` pada tabel
  katalog existing. Buat validasi profil, capability, dan format tracking berdasarkan kategori.
- [x] T03 — Backfill metadata katalog. Petakan setiap exercise Cardio ke `duration_only`,
  `distance_activity`, atau `equipment_metrics` beserta subset metrik yang sesuai. Petakan
  `CALISTHENICS` ke Strength untuk kontrak baru tanpa mengubah snapshot historis.
- [x] T04 — Migrasi routine target. Tambahkan `set_targets JSONB` dan versi kontrak pada
  `template_exercises`. Setiap baris memiliki `row_id`, `set_number`, `weight`, `reps`,
  `duration_seconds`, `rest_seconds`.
- [x] T05 — Validasi target SQL. Terapkan batas 1–20 baris, ID unik, nomor berurutan, format field,
  nilai numerik, dan rest 0–600. Pastikan `sets` selalu diturunkan dari panjang array.
- [x] T06 — Migrasi workout results. Ubah `weight`/`reps` agar nullable. Tambahkan `target_row_id`,
  `calories_kcal`, `speed_kph`, `lap_reps`, `incline_percent`, serta presisi jarak sesuai kontrak
  v3.
- [x] T07 — Upgrade `save_template_revision`. Simpan seluruh target array secara atomik. Pertahankan
  ownership, CAS revision, receipt, dan replay idempotent. Tolak scalar write lama pada routine v3.
- [x] T08 — Upgrade RPC active session. Perluas `create_active_session`, `update_active_session`, dan
  `terminal_session` untuk membaca snapshot v3, target association, hasil nullable, dan optional
  metrics.
- [x] T09 — Upgrade `persist_local_workout_aggregate`. Validasi measurement v3, metadata snapshot, ID
  set, capability, dan hasil per set. Pertahankan receipt berbasis workout ID serta penolakan
  payload replay berbeda.
- [x] T10 — Upgrade RPC read-back. Sesuaikan `get_exercise_prefill`, `get_workout_detail`, dan
  `get_progress_summary` agar mempertahankan target individual, durasi, jarak, dan semantik versi.
- [ ] T11 — **BLOCKED G03/G04.** Terapkan migrasi backend. Jalankan SQL migration baru secara berurutan setelah backup dan
  validasi dependensi. Pastikan reader v1/v2/v3 tersedia sebelum Android writer v3 diaktifkan.

## Phase 2 — Android Domain, Room & Serialization

T12–T18 **belum diimplementasikan**, menunggu G03/G04 sesuai urutan yang diminta. Android tetap Room v9; writer v3 belum diaktifkan.

- [ ] T12 — Refactor model domain. Perbarui `ExerciseTarget`, `TemplateExercise`, `SetMeasurement`,
  `WorkoutSet`, dan `ExerciseInputDefaults` untuk daftar target per baris, satu `weightKg`, dan
  optional cardio metrics.
- [ ] T13 — Implementasikan metadata tracking. Tambahkan `trackingProfile` dan `supportedMetrics` pada
  domain, DTO, mapper, dan cache. Writer baru hanya memakai `WEIGHT_REPS` untuk Strength dan
  `DURATION` untuk Cardio/Isometric; legacy enum tetap readable.
- [ ] T14 — Implementasikan stable target/result IDs. Gunakan `row_id` untuk konfigurasi dan UUID
  `set_id` untuk hasil. `set_number` hanya posisi tampilan. Hasil menyimpan `target_row_id`.
- [ ] T15 — Implementasikan codec v3. Serialization/deserialization untuk `set_targets`,
  `extra_set_targets`, snapshot metadata, measurement v3, dan runtime timer trigger. Pertahankan
  codec legacy.
- [ ] T16 — Migrasi Room v9 → v10. Perbarui entity, TypeConverter, DAO, dan migration tanpa destructive
  fallback. Pertahankan primary key, foreign key, status sync, pending command, serta JSON sesi
  lama.
- [ ] T17 — Implementasikan adapter legacy target. Ekspansi scalar routine lama menjadi N baris dengan
  ID deterministik. Pertahankan jumlah set, urutan, nilai, rest 0, dan unknown weight sebagai null.
  Jangan mutasi histori atau pending payload.
- [ ] T18 — Sinkronkan DTO Android/backend. Pastikan `input_defaults` v3 dikirim sebagai objek JSON,
  bukan string JSON ganda. Jaga nilai null, angka desimal, urutan baris, dan versi pada seluruh
  mapper.

## Phase 3 — Routine Setup, Edit & Picker

- T19 — Refactor `EditWorkoutViewModel`. Simpan dynamic set rows berdasarkan stable `row_id` pada
  durable draft existing. Implementasikan tambah, edit, hapus, renumber, dan restore tanpa mengubah
  nilai baris lainnya.
- T20 — Implementasikan default first insert. Strength, Isometric, dan Cardio `duration_only`
  menghasilkan 3 baris; Cardio `distance_activity` dan `equipment_metrics` menghasilkan 1 baris.
  Rest awal 60 detik. Edit/recovery tidak menjalankan default ulang.
- T21 — Refactor `EditWorkoutScreen` / `RoutineExerciseEditor`. Hapus input jumlah set dan selector
  mode. Strength menampilkan `Set | Beban | Reps | Rest`; Cardio/Isometric `Set | Durasi | Rest`,
  seluruh field sejajar horizontal.
- T22 — Implementasikan validasi draft. Beban 0–1000 kg, reps target 1–100, rest 0–600, durasi
  `HH:MM:SS` positif maksimal 86400 detik, dan jumlah baris 1–20. Raw input invalid tetap tersimpan
  di draft.
- T23 — Upgrade `SaveCommand` / `saveChanges`. Kirim seluruh array target beserta ID dan urutan
  melalui RPC existing. Pertahankan request ID untuk retry identik, draft ketika save gagal, serta
  canonical state saat cancel.
- T24 — Refactor Exercise Picker. Pisahkan `memberIds` dan `selectedNewIds`; cegah duplikasi dan
  replay result. CTA menghitung tambahan baru. Hapus badge kategori/modalitas tanpa membuang
  metadata internal atau mengubah navigasi existing.
- T25 — Refactor Routine Detail. Tampilkan gambar existing, nama, dan ringkasan target tersimpan.
  Format target homogen/heterogen tanpa menampilkan beban, rest, atau badge tambahan.

## Phase 4 — Active Session & Timer

- T26 — Refactor `startWorkout`. Snapshot seluruh target/rest/metadata ke `ActiveSessionEntity`.
  Buat tepat satu draft hasil ber-ID stabil untuk setiap target; `extra_set_targets=[]` saat awal.
  Snapshot tidak berubah ketika routine/katalog diedit.
- T27 — Refactor `ActiveSetRow.kt`. Strength hanya Beban/Reps; Cardio/Isometric hanya Durasi. Hapus
  `+ Tambah Beban`, additional/assistance editor, dan selector mode. Tampilkan Rest read-only dari
  target baris terkait.
- T28 — Implementasikan dynamic cardio metrics. Render hanya field yang terdaftar dalam
  `supportedMetrics` snapshot, berurutan: calories, distance, speed, laps, incline. Semua opsional;
  clear menjadi null, omitted tidak mengubah nilai.
- T29 — Refactor mutasi active set. Tambah set menghasilkan UUID hasil dan konfigurasi tambahan
  ber-rest 60. Hapus hanya draft set, sisakan minimal satu set per exercise, renumber tanpa
  mengganti ID/target association/nilai set lainnya.
- T30 — Implementasikan completion & undo. Complete memvalidasi hasil dan commit ke Room; completed
  read-only. Undo mengembalikan status draft tanpa menghapus nilai, lalu mengizinkan edit/hapus.
  Double complete menjadi no-op.
- T31 — Integrasikan rest per set. `RestTimerCoordinator` menggunakan `rest_seconds` dari
  `target_row_id` dan stable `(session, exercise, set_id)`. Rest 0 tidak memulai countdown; timer
  lama yang relevan dibersihkan.
- T32 — Implementasikan atomic rest trigger. Simpan pending trigger dengan stable set ID, deadline
  monotonic, dan boot identity dalam transaksi completion Room yang sama. Reconcile secara
  idempotent tanpa mengulang timer.
- T33 — Implementasikan recovery active session. Restore ID, snapshot, hasil, completion, pause,
  stopwatch, dan deadline yang sama setelah process death. JSON rusak/versi unsupported tidak
  menghapus record; timer invalid dibersihkan sesuai recovery policy existing.

## Phase 5 — Finish, Sync, History & Statistics

- T34 — Refactor `finishWorkout`. Minimal satu completed set. Persist `WorkoutLogEntity`,
  `ExerciseLogEntity`, `WorkoutSetEntity` dan snapshot lengkap secara atomik. Gunakan session ID
  sebagai workout ID serta UUID hasil yang sudah tersimpan; hapus active session hanya setelah
  commit sukses.
- T35 — Upgrade `SyncManager` / `SyncWorker`. Upload aggregate v3 lengkap lewat RPC existing.
  Pertahankan payload immutable, receipt, ownership, retry, dan status pending. Jangan membuat ulang
  ID saat retry atau rewrite payload gagal.
- T36 — Upgrade `RemoteSnapshotImporter`. Decode snapshot v3 dan legacy sesuai versi; pertahankan
  target array, optional metrics, null, precision, dan quality/provenance. Jangan overwrite
  perubahan lokal pending.
- T37 — Refactor statistik Active. Hitung completed sets dari `is_completed`, total Strength reps
  dari completed `WEIGHT_REPS`, volume `Σ(weightKg × reps)`, durasi Cardio/Isometric, dan total
  jarak tercatat. Undo langsung mengurangi kontribusi.
- T38 — Refactor Home, History & Progress. Gunakan completed results dan snapshot versi
  masing-masing. Pisahkan volume legacy dari volume beban tercatat v3, hindari duplikasi workout ID,
  serta bedakan durasi sesi keseluruhan dengan waktu aktif tanpa pause.
- T39 — Refactor estimasi durasi routine. Jumlahkan durasi target dan rest antarbaris untuk routine
  duration-only. Routine Strength/mixed tampilkan `—` sampai formula produk ditetapkan; jangan
  mempertahankan estimasi 8 menit per exercise sebagai angka pasti.

## Phase 6 — Integration & Release Gate

- T40 — Integrasikan seluruh jalur. Pastikan katalog → picker → setup/edit → save routine → detail →
  start → active → finish → history → sync → read-back memakai kontrak v3 yang konsisten.
- T41 — Verifikasi invariant utama. Jalankan acceptance A-01–A-21 pada implementasi terkait, dengan
  prioritas target independen, metadata capability, stable IDs, completion/undo, rest timer, dan
  hasil sync.
- T42 — Verifikasi backward compatibility. Pastikan routine scalar lama, active session v1/v2, enam
  format measurement legacy, dan pending aggregate lama tetap dapat dibaca/diselesaikan tanpa
  konversi destruktif.
- T43 — Jalankan build dan release checks. Build Android production, Room migration 9→10, validasi
  SQL/RPC, dan Compose UI pada lebar 360/412. Perbaiki kegagalan yang menghalangi jalur utama.
- T44 — Closed Beta cutover. Aktifkan writer v3 setelah backend reader siap, deploy internal build,
  dan pastikan save routine, finish lokal, serta upload/read-back menghasilkan satu data konsisten.
- T45 — Finalisasi implementasi. Perbarui laporan implementasi existing, tandai task selesai
  berdasarkan bukti kode/migrasi, catat blocker yang benar-benar tersisa, dan pertahankan reader
  legacy serta migration history.

Batas implementasi: gunakan repository, Room, RPC, draft, sync, timer, dan komponen UI existing.
Tidak membuat tabel, service, workflow, atau layer baru.

## Deployment gates — berjalan paralel

- [x] G01 — Rekonsiliasi `20261101000300` dan `20261102144339`; keduanya benar-benar belum efektif. Urutan apply ditetapkan, preflight live read-only PASS; tidak ada repair ledger atau apply live.
- [x] G02 — Hilangkan fallback katalog pada read/import/finalization snapshot legacy. Migration baru dan regresi failing-before/passing-after terverifikasi terisolasi; belum deployed.
- [ ] G03 — **BLOCKED:** Dashboard langsung mengarah ke Sign in dan Chrome relay belum tersambung; belum ada credential PostgreSQL berizin. API terbaru menunjukkan 0 backup yang dapat diunduh dan physical backup aktif. Full backup live, checksum, dan bukti restore belum tersedia. Tool backup/restore nyata serta bukti lokal tidak menggantikan backup live.
- [ ] G04 — **BLOCKED / NOT DEPLOYED:** T08–T10 sudah kompatibel dan terverifikasi di PostgreSQL terisolasi, tetapi G03 belum PASS. Jangan deploy schema v3 atau mengaktifkan writer Android baru sebelum full backup/restore live dan preflight lolos.

Bukti T01–T03: [PLAN §11](PLAN_ARCHITECTURAL_EXERCISES_REFACTOR.md#11-implementasi-t01t03--2026-11-06). Bukti T04–T07: [PLAN §12](PLAN_ARCHITECTURAL_EXERCISES_REFACTOR.md#12-implementasi-t04t07-dan-deployment-gates--2026-11-06). Bukti T08–T10 dan status gate terkini: [PLAN §13](PLAN_ARCHITECTURAL_EXERCISES_REFACTOR.md#13-implementasi-t08t10-dan-status-gate--2026-11-06). Checklist implemented tidak mengubah status deployed.