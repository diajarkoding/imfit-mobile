**Status:** T01–T25 selesai (Phase 1–3). Backend v3 **DEPLOYED live pada 2026-11-06** (G01–G04 PASS); Phase 2 (T12–T18) terverifikasi 2026-11-07 ([PLAN §15](PLAN_ARCHITECTURAL_EXERCISES_REFACTOR.md#15-implementasi-t12t18-android-domain-room-v10--serialization--2026-11-07)); Phase 3 (T19–T25) terimplementasi ([PLAN §16](PLAN_ARCHITECTURAL_EXERCISES_REFACTOR.md#16-implementasi-t19t25-routine-setup-edit-picker--detail--2026-11-07)). Room v10; writer Android v3 **belum diaktifkan**. **Revisi 2026-11-08 (PLAN v1.5):** Phase 4–6 disusun ulang menjadi Active Session Semi-Offline (T26–T38, urut mengikat), Phase 5B (T39–T43, item UI/importer/estimasi dari daftar lama), dan Phase 6 (T44–T49). Penomoran T39–T45 lama bergeser menjadi T43–T49 (T39 lama = T43; T40–T45 lama = T44–T49). Belum ada task T26+ yang diimplementasikan.

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
- [x] T11 — **DEPLOYED / G03–G04 PASS.** Terapkan migrasi backend. Dua belas SQL migration diterapkan
  berurutan setelah full backup live, restore terisolasi, validasi dependensi, dan rehearsal.
  Reader v1/v2/v3 lulus smoke live; writer Android v3 tetap belum diaktifkan. Bukti: PLAN §14.

## Phase 2 — Android Domain, Room & Serialization
T12–T18 **selesai diimplementasikan dan terverifikasi** (2026-11-07). Database lokal kini Room v10; writer sesi v3 belum diaktifkan (writer lokal tetap v2; writer rutin v3 hanya menerima perintah v3 eksplisit).


- [x] T12 — Refactor model domain. Perbarui `ExerciseTarget`, `TemplateExercise`, `SetMeasurement`,
  `WorkoutSet`, dan `ExerciseInputDefaults` untuk daftar target per baris, satu `weightKg` (Double?,
  0 kg sah), dan optional cardio metrics.
- [x] T13 — Implementasikan metadata tracking. Tambahkan `trackingProfile` dan `supportedMetrics` pada
  domain, DTO, mapper, dan cache Room. Writer baru hanya memakai `WEIGHT_REPS` untuk Strength dan
  `DURATION` untuk Cardio/Isometric; legacy enum tetap readable.
- [x] T14 — Implementasikan stable target/result IDs. Gunakan `row_id` untuk konfigurasi dan UUID
  `set_id` untuk hasil. `set_number` hanya posisi tampilan. Hasil menyimpan `target_row_id`.
- [x] T15 — Implementasikan codec v3. Serialization/deserialization untuk `set_targets`,
  `extra_set_targets`, snapshot metadata, measurement v3, dan runtime timer trigger via
  `WorkoutSessionCodec`. Pertahankan codec legacy v1/v2 dan simpan raw row saat recovery gagal.
- [x] T16 — Migrasi Room v9 → v10. Perbarui entity, DAO, dan `MIGRATION_9_10` tanpa destructive
  fallback. Pertahankan primary key, index, foreign key, status sync, pending command, serta JSON
  sesi lama byte-for-byte. Widen numeric results dan distance.
- [x] T17 — Implementasikan adapter legacy target. `LegacyTargetAdapter.expand` menghasilkan N baris
  dengan ID deterministik berlingkup. Pertahankan jumlah set, urutan, nilai, rest 0, dan unknown
  weight sebagai null tanpa mutasi histori atau pending payload.
- [x] T18 — Sinkronkan DTO Android/backend. `input_defaults` v3 dikirim sebagai objek JSON native,
  bukan string ganda. `WorkoutAggregatePayloadCodec` memisahkan payload v3 dari legacy; pertahankan
  integer distance dan representasi float legacy pada upload lama.

## Phase 3 — Routine Setup, Edit & Picker

- [x] T19 — Refactor `EditWorkoutViewModel`. Simpan dynamic set rows berdasarkan stable `row_id` pada
  durable draft existing. Implementasikan tambah, edit, hapus, renumber, dan restore tanpa mengubah
  nilai baris lainnya.
- [x] T20 — Implementasikan default first insert. Strength, Isometric, dan Cardio `duration_only`
  menghasilkan 3 baris; Cardio `distance_activity` dan `equipment_metrics` menghasilkan 1 baris.
  Rest awal 60 detik. Edit/recovery tidak menjalankan default ulang.
- [x] T21 — Refactor `EditWorkoutScreen` / `RoutineExerciseEditor`. Hapus input jumlah set dan selector
  mode. Strength menampilkan `Set | Beban | Reps | Rest`; Cardio/Isometric `Set | Durasi | Rest`,
  seluruh field sejajar horizontal.
- [x] T22 — Implementasikan validasi draft. Beban 0–1000 kg, reps target 1–100, rest 0–600, durasi
  `HH:MM:SS` positif maksimal 86400 detik, dan jumlah baris 1–20. Raw input invalid tetap tersimpan
  di draft.
- [x] T23 — Upgrade `SaveCommand` / `saveChanges`. Kirim seluruh array target beserta ID dan urutan
  melalui RPC existing. Pertahankan request ID untuk retry identik, draft ketika save gagal, serta
  canonical state saat cancel.
- [x] T24 — Refactor Exercise Picker. Pisahkan `memberIds` dan `selectedNewIds`; cegah duplikasi dan
  replay result. CTA menghitung tambahan baru. Hapus badge kategori/modalitas tanpa membuang
  metadata internal atau mengubah navigasi existing.
- [x] T25 — Refactor Routine Detail. Tampilkan gambar existing, nama, dan ringkasan target tersimpan.
  Format target homogen/heterogen tanpa menampilkan beban, rest, atau badge tambahan.

## Phase 4 — Active Session Semi-Offline & Timer (revisi v1.5)

Target: Active Workout memakai Room sebagai sumber utama, tetap berjalan tanpa internet, semua timer akurat saat Pause/Resume, dan backend hanya menerima **satu batch aggregate** setelah Finish. Kontrak lengkap, tabel batas write, dan acceptance A-22–A-32: [PLAN §17](PLAN_ARCHITECTURAL_EXERCISES_REFACTOR.md#17-revisi-v15--active-session-semi-offline-timer-monotonic-dan-batch-sync). **Urutan eksekusi (dikoreksi): T26→T34 → T37 → T35 → T36 → T38** (T35 membutuhkan kolom Room v11 dari T37; nomor task tidak berubah). T39–T43 dikerjakan paralel sesuai dependensi.

**Aturan lintas task:** tidak ada Room UPDATE per detik dan tidak ada request backend saat tick/input/Pause/Resume/Complete. Aggregate tidak boleh mengirim `rest_outcome`/`active_elapsed_ms` sebelum backend T37 deployed. **Writer Android v3 tidak boleh diaktifkan** sebelum T26–T38 selesai dan alur Room-first, finalisasi completed-only, serta aggregate upload terhubung penuh. Rest aktual adalah ekstensi kontrak (T37) berupa migrasi additive, bukan perubahan `rest_seconds` target.

- [x] T26 — Start Workout & immutable routine snapshot. Ambil `template_id`, revision, `set_targets`, rest per baris, dan metadata exercise; simpan snapshot lengkap di `ActiveSessionEntity` **sebelum** sesi aktif dibuka. Buat `set_id` UUID stabil per target (`target_row_id` terhubung); `extra_set_targets=[]` awal. Edit routine/katalog setelah start tidak mengubah snapshot. Sesi berjalan tetap dapat dilanjutkan offline; start offline hanya jika snapshot routine valid tersedia lokal.
  - *Progress 2026-11-08:* `startWorkout` membangun sesi v3 (satu draft ber-UUID per target, `target_row_id`, snapshot `inputDefaults` v3, `templateRevision`, `extra_set_targets=[]`) di balik `ActiveSessionV3Gate` (default **off**; dinyalakan hanya di T48). Terverifikasi: unit codec + instrumentasi Room (`ActiveSessionSemiOfflineTest`: snapshot, ID stabil, cold read identik, routine legacy tetap v2, gate menolak v3). **Sisa:** start dari routine cache offline hanya teruji pada `RoutineSessionStarter` existing (belum ada test v3); Finish v3 menunggu T35.
  - *Tutup 2026-11-08:* sesi v3 teruji end-to-end pada backend live (PLAN §17.14) dan Finish/UI aktif memprosesnya; gate tetap off untuk pengguna sampai T48.
- [x] T27 — Pisahkan target, draft, completed result, dan runtime timer. `set_targets` = konfigurasi routine; `sets` = hasil `DRAFT`/`COMPLETED`; `executionTimer`, `restTimer`, `pauseState` = runtime. Countdown tidak pernah ditulis ke measurement atau target.
  - *Progress:* terpisah di model: `set_targets`/`extra_set_targets` (snapshot), `sets` (+`restOutcome` terpisah dari target), runtime `ExecutionStopwatch` per set, `sessionStopwatch`/`pauseStopwatch`, `pendingRestTrigger`, dan store `RestTimerState`. Countdown tidak ditulis ke measurement/target (unit test).
- [x] T28 — Timer monotonic. Acuan `SystemClock.elapsedRealtime()`; simpan elapsed terakumulasi + anchor saat Start/Pause/Resume/Stop; tampilan dihitung tiap tick dari state memori Compose. Tidak ada Room UPDATE per detik; Room hanya pada transisi bermakna.
  - *Progress:* ticker `ActiveWorkoutViewModel` kini hanya membaca Room dan menurunkan tampilan di memori (`withDisplayElapsed`); `checkpointExecutionStopwatches` (yang sebelumnya **menulis Room tiap detik**) dihapus. Test instrumentasi memastikan JSON sesi dan `updatedAt` tidak berubah selama 30 tick. **Sisa:** notifikasi `RestTimerAndroidRuntime`/`WorkoutService` masih memakai `actualElapsedMs` (wall clock) untuk teks elapsed.
- [x] T29 — Global Pause/Resume. Pause membekukan execution stopwatch dan rest aktif; Resume melanjutkan dari elapsed tersimpan dengan anchor baru. Background tanpa Pause tetap berjalan. Durasi hasil tidak memuat interval Pause (60 s aktif + 30 s pause + 20 s aktif = **80 s** durasi set). `active_elapsed_ms` sesi menghitung seluruh waktu tanpa pause **termasuk rest** (80 s set + 35 s rest ≥ 115 s), terpisah dari `duration_seconds` set.
  - *Progress:* `pauseWorkout`/`resumeWorkout` (domain + VM) membekukan/melanjutkan execution stopwatch (`pausedByWorkout`), `sessionStopwatch`, dan rest (`TimerStatus.PAUSED` + `pausedRemainingMs`; sisa waktu tidak terikat boot). Unit test: 60+30+20 = 80 s, active sesi 115 s dengan rest, paused 30 s. Pause tidak lagi diblokir saat rest untuk sesi v3 (v2 tetap lama). **Sisa:** indikator UI rest-paused (state `isRestPaused` sudah ada).
- [x] T30 — Set completion sebagai batas validitas. Draft boleh diubah tetapi tidak masuk statistik. Complete memvalidasi sesuai snapshot, membekukan stopwatch milik set itu (satu execution timer aktif per sesi, terikat `set_id`; Start set lain ditolak selama ada timer aktif), menyimpan **durasi aktual aktif** (bukan target), dan hasil + status completed atomik di Room. Completed read-only; Undo → `DRAFT` dengan input dipertahankan dan keluar dari statistik; double Complete tidak menduplikasi hasil.
  - *Progress:* `completeSet`/`undoSet` pure + VM (`completeSetV3`, `undoSetCompletion`): validasi snapshot, stopwatch milik set dibekukan, satu execution timer per sesi (Start set lain ditolak, pesan `error_execution_timer_busy`), double complete no-op, undo → draft tanpa kehilangan input. Catatan keputusan: nilai durasi manual yang diisi user dianggap hasil user; jika stopwatch dipakai, durasi aktual stopwatch yang menang (target hanya prefill). **Sisa:** kontrol Undo belum terhubung ke `ActiveSetRow` (T39).
  - *Tutup:* Undo kini terpasang di UI (`ActiveSetRow`: kotak centang set completed v3 menjadi "Batalkan selesai", terkunci saat Pause; set legacy tetap terkunci) lewat `ActiveWorkoutViewModel.undoSetCompletion`. 8 test Compose.
- [x] T31 — Rest timer per target. `rest_seconds` dari `target_row_id` snapshot; mulai hanya setelah commit Complete berhasil; rest 0 tanpa countdown; berjalan terpisah dari execution stopwatch; Pause membekukan, Resume melanjutkan sisa; satu countdown aktif per sesi. Target 90 s tetap 90 meski sisa tampil 60 s.
  - *Progress:* rest dari `target_row_id` via `restSecondsForSet`; start hanya setelah commit; rest 0 → tanpa countdown dan countdown aktif sebelumnya dihentikan; satu countdown (`start` mengganti); `startedSetIds` mencegah timer ulang setelah undo→complete. Teruji unit + coordinator.
- [x] T32 — Durasi rest aktual. Auto-expired mencatat durasi yang benar-benar tercapai pada deadline; Skip mencatat durasi aktif sampai Skip; interval Pause dikecualikan. Simpan `rest_elapsed_ms` + `rest_end_reason` (`EXPIRED`/`SKIPPED`) **hanya saat rest berakhir**, terkait `set_id` pemicu, sekali. Rest yang dibatalkan/digantikan/di-undo/dihentikan oleh Finish sebelum selesai tidak menghasilkan outcome palsu; `SKIPPED` hanya jika user menekan Skip. `rest_seconds` target tidak diubah. (Skip 35 s pada target 90 s → 35 s.)
  - *Progress:* `RestTimerState` memutuskan outcome sekali (`outcomeReason/outcomeElapsedMs`) dan `recordRestOutcome` menulisnya at-least-once idempoten ke `restOutcome` set (`withRestOutcome` no-op saat replay). EXPIRED = durasi tercapai pada deadline (juga bila alert terlambat pada boot sama); SKIPPED = elapsed aktif; replaced/undo/finish/boot-berubah/sesi invalid = tanpa outcome. 9 test coordinator. **Sisa:** persist ke kolom Room v11 + payload (T37/T35).
- [ ] T33 — Atomic completion & timer recovery. Completed set + pending rest trigger dalam satu transaksi Room; key `(session_id, exercise_id, set_id)`; simpan anchor, accumulated elapsed, deadline, pause state, boot identity. Setelah process death baca sesi yang sama tanpa ID baru; rest yang sudah lewat deadline (boot sama) diproses expiry **satu kali** dari deadline tersimpan (`EXPIRED`, tanpa restart 90 s); countdown tidak reset ke target; trigger yang sudah diproses tidak dijalankan ulang. Setelah reboot (clock continuity tidak terbukti) jangan mengarang elapsed — pertahankan elapsed terakhir yang valid dan pulihkan sebagai **perlu rekonsiliasi**.
  - *Progress:* completion + `pendingRestTrigger` dalam satu `mutateActiveSession` (satu transaksi); recovery VM (`recoverRuntime`) menerapkan trigger dengan deadline tersimpan (`start(triggerDeadlineElapsedMs)`), trigger hanya di-clear setelah timer ada, boot berbeda → tidak ada timer/outcome; `reconcileAfterRestore` mempertahankan elapsed valid dan menandai `needsTimerReconciliation`. Catatan: karena tick tidak lagi menulis, reboot kehilangan segmen stopwatch sejak transisi terakhir (sesuai aturan "jangan mengarang"); process death di boot sama tidak kehilangan apa pun. **Sisa:** test process-death nyata (kill proses di emulator) dan UI penanda rekonsiliasi.
  - *Progress tambahan:* banner rekonsiliasi (`ActiveSessionReconciliationBanner`) tampil bila `needsTimerReconciliation`. **Sisa:** test kill proses nyata (di emulator) belum ada.
- [x] T34 — Persistent offline active session (`WorkoutRepositoryImpl`, `ActiveSessionEntity`, `WorkoutSessionCodec`). Semua edit manual, add/remove set, completion, undo, pause/resume, dan transisi timer dipersist di Room; gagal commit tidak tampil sukses; offline tidak memblokir Complete; reopen memulihkan snapshot, draft, completed, runtime timer. Tidak memakai RPC `update_active_session` per perubahan lokal; baca sesi v1/v2 tetap sesuai kontrak historis.
  - *Progress:* semua transisi baru melalui `mutateActiveSession` (Room, tanpa RPC); codec menyimpan `templateRevision`, stopwatch sesi/pause, `restOutcome` (v1/v2 tetap terbaca; legacy menolak `restOutcome`). Teruji di Room nyata (cold repository). **Sisa:** add/remove set v3 (`addDefaultSet` masih menolak v3 → T41) dan test offline-airplane end-to-end.

## Phase 5 — Finish, Batch Sync, Rest Actual Contract & Statistics

  - *Tutup:* tambah set v3 (`ExerciseLog.addV3Set`: UUID baru + target tambahan rest 60) dan hapus draft (`removeDraftSet` existing, renumber tanpa mengubah ID) teruji; semua transisi lewat Room; cold-read dan E2E lulus. Batas: mode pesawat tidak dimatikan secara literal pada emulator.
- [x] T35 — Finalisasi workout atomik. **Dikerjakan setelah T37** (membutuhkan Room v11). Finish saat rest berjalan menghentikan rest tanpa outcome. Minimal satu completed set; bekukan timer aktif dan selesaikan lifecycle runtime; persist `WorkoutLogEntity`, `ExerciseLogEntity`, `WorkoutSetEntity` dalam satu transaksi; hanya completed sets masuk hasil; rest aktual hanya jika outcome valid terkait completed set; simpan template reference, revision, snapshot target, versi measurement, semua ID stabil, `paused_elapsed_ms`/`active_elapsed_ms`; tandai `PENDING_SYNC`; hapus active session hanya setelah commit sukses.
  - *Progress 2026-11-08:* `finishWorkout` (transaksi Room tunggal, hanya set completed, ID stabil, outcome rest, `PENDING_SYNC/CREATE`, hapus sesi hanya jika commit sukses) kini juga menyimpan `paused_elapsed_ms`/`active_elapsed_ms` dari clock sesi yang dibekukan (`freezeForFinish` dipanggil VM sebelum finish; di-clamp agar `paused+active ≤ gross` sesuai kontrak server; null bila tidak terbukti/legacy). Teruji: 4 unit test + 2 instrumentasi (commit, draft tidak ikut, payload identik saat retry, finish tanpa completed menjaga sesi). **Sisa:** revisi routine belum punya kolom Room/server (hanya di JSON sesi; keputusan terpisah); Finish saat rest berjalan = tanpa outcome (diatur timer).
- [x] T36 — Batch aggregate sync, bukan upload per set. **Setelah T37**; jangan kirim `rest_outcome`/durasi sesi sebelum backend deployed (`SyncManager`, `SyncWorker`, `WorkoutAggregatePayloadCodec`). Tidak ada request saat tick/input/Pause/Resume/Complete. Setelah Finish bangun satu payload aggregate dari data committed lewat RPC existing `persist_local_workout_aggregate`; payload immutable; retry memakai payload/workout ID/request identity yang sama; `SYNCED` hanya setelah ack valid; kegagalan jaringan mempertahankan data lokal.
  - *Progress 2026-11-08:* jalur existing `SyncManager.syncWorkoutAggregate` sudah memenuhi kontrak (satu RPC `persist_local_workout_aggregate` per workout, tanpa upload per set/tick, `SYNCED` hanya setelah ack lewat `markAsSyncedIfUnchanged`, `SYNC_FAILED` pada penolakan server dengan data lokal utuh); `WorkoutAggregatePayloadCodec` mengirim outcome + durasi hanya untuk v3 (T37) dan test memastikan payload identik saat retry. **Sisa:** belum ada test `SyncManager` (tidak ada seam fake untuk `SupabaseClient`) dan belum ada bukti E2E terhadap backend live; tidak dicentang.
- [x] T37 — Kontrak backend & Room untuk rest aktual. **Dikerjakan sebelum T35/T36.** `active_elapsed_ms` = waktu sesi tanpa pause termasuk rest (bukan durasi set). Tambah outcome rest (`rest_elapsed_ms`, `rest_end_reason`) dan durasi sesi (`paused_elapsed_ms`, `active_elapsed_ms`) pada **tabel existing** lewat migrasi additive (Room v10 → v11; migration SQL baru; tanpa tabel/service baru). Perluas validator `persist_local_workout_aggregate` (tolak set ID tak dikenal, durasi negatif, asosiasi tidak valid, pasangan field tidak lengkap), pertahankan outcome di `get_workout_detail` dan read-back; payload v1/v2/v3 lama dan receipt terbit tidak berubah (replay immutable). Round-trip Room → Backend → Room tanpa mengubah arti `rest_seconds`. Wajib backup + rehearsal terisolasi sebelum deploy (pola G03/G04).
  - *Progress 2026-11-08:* **kode selesai; backend DEPLOYED live 2026-11-08 (PLAN §17.13: backup + restore PASSED, rehearsal PASS, ledger 32, baris/receipt tidak berubah).** Room v11 (`MIGRATION_10_11`, `11.json`), entity/DTO/importer/codec, dan migrasi SQL `20261108000100_rest_outcome_contract.sql` + test `supabase/tests/rest_outcome_contract.sql` (PASS pada restore backup 2026-11-06 + migrasi T11 + T37; row count dan hash receipt tidak berubah). Wire final flat: `rest_elapsed_ms`/`rest_end_reason` per set, `paused_elapsed_ms`/`active_elapsed_ms` per workout (PLAN §17.11). **Sisa:** hanya bukti end-to-end Android → backend live (T36/Phase C); belum dicentang sampai itu ada.
  - *E2E 2026-11-08 (T35–T37 dicentang atas bukti ini):* `RestOutcomeEndToEndTest` (production flavor, emulator, akun uji, backend live) lulus: routine v3 → start → timer eksekusi → Complete → outcome rest SKIPPED → Pause/Resume → Finish hanya ke Room (server belum menerima apa pun) → satu upload aggregate → server menyimpan outcome + durasi, snapshot target rest tetap 90/0/60 → retry payload identik menghasilkan receipt identik → hapus lokal + hydrate ulang: outcome dan durasi kembali sama. Batas yang jujur: "offline" dibuktikan dengan tidak adanya data di server sebelum sync, bukan dengan mematikan jaringan emulator; revisi routine belum punya kolom Room/server; `SyncManager` belum punya unit test sendiri.
- [ ] T38 — Statistik dan UI dari completed data. Active statistics hanya dari completed; reps/volume/durasi/jarak dari hasil aktual; countdown dan draft tidak masuk statistik; Undo langsung mengeluarkan kontribusi; bedakan durasi sesi gross, waktu aktif tanpa pause, dan durasi exercise; Home, Summary, History, Progress memakai completed + versi snapshot yang benar.

## Phase 5B — Active UI, Metrics & Importer (paralel dengan T27–T38)

Item di bawah berasal dari T27–T29 dan T36 revisi sebelumnya; tetap wajib dan tidak digantikan T26–T38.

  - *Progress 2026-11-08 (belum dicentang):* statistik Active sudah hanya dari set completed (domain existing + tes); Summary menampilkan **Waktu aktif** (tanpa pause, termasuk rest) sebagai metrik terpisah dari durasi gross bila tersedia (v3). **Sisa:** History detail/Progress belum menampilkan waktu aktif, dan audit pemisahan volume legacy vs volume tercatat di Home/Progress belum dilakukan ulang.
- [x] T39 — Refactor `ActiveSetRow.kt`. Strength hanya Beban/Reps; Cardio/Isometric hanya Durasi. Hapus `+ Tambah Beban`, additional/assistance editor, dan selector mode. Tampilkan Rest read-only dari target baris terkait (nilai target, bukan sisa countdown). Tampilkan kontrol Pause/Resume/Skip dari T29/T32 tanpa menulis countdown ke field hasil. Bergantung T27.
  - *Tutup 2026-11-08 (rujukan desain TXyaw dibaca via Pencil MCP, read-only):* baris set sesuai referensi: Set → Beban/Reps atau Durasi, teks read-only `Rest target: N s` (0 tampil sebagai 0) dari target snapshot, Delete terkunci saat completed, tombol selesai/undo; dialog rest kini punya Jeda/Lanjutkan (membekukan sisa waktu; Skip/±30 s nonaktif saat dijeda). Editor additional/assistance hanya tetap ada untuk sesi **legacy v2** yang masih berjalan (tidak terjangkau pada sesi v3 karena hanya `WEIGHT_REPS`/`DURATION`). Test: `ActiveSetRowV3UiTest` (8).
- [x] T40 — Dynamic cardio metrics. Render hanya field di `supportedMetrics` snapshot, berurutan: calories, distance, speed, laps, incline. Semua opsional; clear → null, omitted tidak mengubah nilai.
  - *Tutup:* field hanya untuk `supportedMetrics` snapshot (urutan calories, distance, speed, laps, incline), semua opsional, clear → null, field yang tidak diubah tetap, 3 desimal ditolak, jarak km→meter; `duration_only` tanpa field metrik (test Compose).
- [x] T41 — Mutasi active set. Tambah set menghasilkan UUID hasil + konfigurasi tambahan rest 60; hapus hanya draft, sisakan minimal satu set per exercise, renumber tanpa mengganti ID/target association/nilai lain. Dipersist Room-first (T34).
  - *Tutup:* lihat T34.
- [x] T42 — Upgrade `RemoteSnapshotImporter`. Decode snapshot v3 dan legacy sesuai versi; pertahankan target array, optional metrics, null, precision, quality/provenance, serta **rest outcome dan durasi sesi** dari T37. Jangan overwrite perubahan lokal pending.
  - *Tutup:* `RemoteSnapshotImporter`/`SyncManager`/DTO membawa outcome rest + durasi sesi, equality memperhitungkannya, dan hydrate dari backend live terbukti identik di E2E (PLAN §17.14).
- [x] T43 — Refactor estimasi durasi routine. Routine duration-only: Σ durasi target + rest antarbaris; Strength/mixed tampilkan `—` sampai formula produk ditetapkan; jangan pertahankan 8 menit per exercise sebagai angka pasti. (Estimasi berbasis **target**; tidak berkaitan dengan rest aktual.)

## Phase 6 — Integration & Release Gate

  - *Tutup:* `estimateRoutineDurationMinutes` (target saja; contoh 60/90/60 s rest 60/60/120 → 6 menit; Strength/mixed/data tidak lengkap → `—`), 4 unit test; layar detail menampilkan `—` bila null.
- [ ] T44 — Integrasikan seluruh jalur. Katalog → picker → setup/edit → save routine → detail → start (snapshot) → active (Room-first, timer monotonic, Pause/Resume) → finish lokal → history → batch sync → read-back memakai kontrak v3 yang konsisten, termasuk rest aktual.
- [ ] T45 — Verifikasi invariant utama. Jalankan acceptance A-01–A-21 **dan A-22–A-32 (PLAN §17.8)** pada implementasi terkait; prioritas target independen, stable IDs, completion/undo, Pause/Resume, rest aktual, recovery, dan hasil sync batch.
- [ ] T46 — Verifikasi backward compatibility. Routine scalar lama, active session v1/v2, enam format measurement legacy, dan pending aggregate lama (tanpa `rest_outcome`) tetap dapat dibaca/diselesaikan tanpa konversi destruktif; Room 10→11 tidak mengubah data/payload pending.
- [ ] T47 — Build dan release checks. Build Android production, Room migration 9→10→11, validasi SQL/RPC, Compose UI pada lebar 360/412, dan uji offline (mode pesawat di tengah workout dan saat Finish). Perbaiki kegagalan yang menghalangi jalur utama.
- [ ] T48 — Closed Beta cutover. **Gate:** T26–T38 selesai dan terverifikasi. Aktifkan writer v3 setelah backend reader/validator rest aktual siap; deploy internal build; pastikan save routine, finish lokal offline, serta upload batch/read-back menghasilkan satu data konsisten.
- [ ] T49 — Finalisasi implementasi. Perbarui laporan implementasi, tandai task selesai berdasarkan bukti kode/migrasi, catat blocker tersisa, pertahankan reader legacy dan migration history.

Batas implementasi: gunakan repository, Room, RPC, draft, sync, timer, dan komponen UI existing. Tidak membuat tabel, service, workflow, atau layer baru; rest aktual dan durasi sesi ditambahkan sebagai **kolom additive pada tabel existing**.


## Deployment gates — berjalan paralel

- [x] G01 — Rekonsiliasi `20261101000300` dan `20261102144339`; preflight pristine live PASS sebelum deployment. Keduanya benar-benar diterapkan pada T11, bukan repair ledger; baseline 19 migration dipertahankan.
- [x] G02 — Hilangkan fallback katalog pada read/import/finalization snapshot legacy. Migration sudah deployed; regresi terisolasi dan smoke snapshot/receipt legacy live PASS.
- [x] G03 — **PASS:** full logical backup live `backup-20261106T131705Z`, SHA256 dan restore fidelity tersedia di luar Git. Seluruh 1.704 manifest record, 54 tabel data, 4 sequence, schema/owner/grant/RLS cocok; batas role passwords/Storage bytes dicatat di PLAN §14.
- [x] G04 — **DEPLOYED / PASS:** 12 migration committed atomik per file bersama ledger, total ledger 31 hingga `20261106000800`. Smoke authenticated SQL reader v1/v2/v3 live PASS, 9 tabel existing tetap identik, route HTTP PostgREST ter-resolve dan menolak anon. Android writer v3 tidak diaktifkan.

Kontrak revisi T26–T38 (v1.5): [PLAN §17](PLAN_ARCHITECTURAL_EXERCISES_REFACTOR.md#17-revisi-v15--active-session-semi-offline-timer-monotonic-dan-batch-sync). Bukti T01–T03: [PLAN §11](PLAN_ARCHITECTURAL_EXERCISES_REFACTOR.md#11-implementasi-t01t03--2026-11-06). Bukti T04–T07: [PLAN §12](PLAN_ARCHITECTURAL_EXERCISES_REFACTOR.md#12-implementasi-t04t07-dan-deployment-gates--2026-11-06). Bukti T08–T10 terisolasi: [PLAN §13](PLAN_ARCHITECTURAL_EXERCISES_REFACTOR.md#13-implementasi-t08t10-dan-status-gate--2026-11-06). Bukti deployment T11 dan status gate terkini: [PLAN §14](PLAN_ARCHITECTURAL_EXERCISES_REFACTOR.md#14-deployment-t11-dan-penutupan-g03g04--2026-11-06). Kelulusan backend bukan aktivasi atau pengujian Android v3.