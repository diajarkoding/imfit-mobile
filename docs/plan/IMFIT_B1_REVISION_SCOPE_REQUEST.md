# IMFIT B1 — Request scope khusus: durable aggregate revision

**22 Oktober 2026 — `fix-bug` — PROPOSED, BELUM DIOTORISASI / BELUM DITERAPKAN.**

Persetujuan fase B1–G terbaru tetap berlaku. Dokumen ini bukan permintaan approval perpindahan fase: ini mengajukan **perubahan schema lokal di luar scope default**, sesuai stop condition pengguna dan S06 baseline v2.1. Tidak ada migration, perubahan entity/schema export, backend, atau deployment yang dilakukan.

## Mengapa berhenti di boundary acknowledgement

`SyncManager.syncWorkoutAggregate` membaca parent dari pending scan, membaca children terpisah, lalu `markAsSyncedIfUnchanged` membandingkan `updated_at` + `pending_operation` sebelum mengosongkan flags seluruh children. Pola serupa ada pada template. Schema7 tidak menyimpan identitas revisi aggregate.

Tiga acceptance test baru **mengharuskan pending tetap ada**, bukan menormalkan unsafe behavior B0:

- `SyncRevisionGateTest.sameMillisecondParentEditMustRemainPendingAfterOldRemoteAcknowledgement`
- `SyncRevisionGateTest.childOnlyEditMustRemainPendingAfterOldRemoteAcknowledgement`
- `SyncRevisionGateTest.operationAndContentABAMustNotAcceptAcknowledgementOfOldRevision`

Hasil eksekusi dicatat pada [laporan B1](IMFIT_PHASE_B1_FOUNDATION_REPORT.md). Test memakai real SyncManager/Room/SDK serialization dengan transport fixture, **bukan** Supabase nyata.

`AggregateSnapshotStore` baru menyelesaikan consistent capture saja. Test `schema7BlockerFullStateEqualityCannotDetectSameMillisecondOperationABA` menunjukkan bahwa dua write yang kembali ke state identik menghasilkan snapshot identik. Menambahkan pemeriksaan hash/equality tidak membuktikan **exact revision**. Tidak menambahkan API ack berbasis equality, tidak mengubah timestamp/status menjadi revision terselubung, dan tidak melemahkan S06.

## Scope yang dimintakan persetujuan

**Rekomendasi untuk dirancang dan diuji: migration Room7→8 additive/non-destructive, dengan metadata revisi aggregate terpisah.** Desain SQL final harus direview sebelum dipakai; bentuk di bawah adalah kontrak yang diusulkan, bukan implementasi.

1. Tambahkan metadata owner + jenis aggregate (`workout`/`template`) + aggregate ID + revision monotonik. Revision harus bertahan selama lifetime aggregate; delete/recreate dan `INSERT OR REPLACE` tidak boleh mereset identitas mutasi yang masih dapat menerima ack lama.
2. Capture parent + seluruh children + revision dalam satu transaksi Room singkat. Kirim payload existing di luar transaksi, tanpa menambahkan field backend secara diam-diam.
3. Ack di bawah session fence + transaksi: verifikasi owner/epoch yang sah, aggregate identity, operation dan **revision persis yang dikirim**. Jika berbeda, jangan clear parent/child pending. Revision wajib berubah untuk child-only write, same-ms dan operation/content ABA.
4. Audit **semua** writer: DAO insert/update/delete/replace, repository, importer, acknowledgement, migration/create DB. Pertimbangkan database triggers agar writer yang melewati repository tetap terlindungi; definisi trigger dan acknowledgement tidak boleh menyebabkan retry loop atau recursive updates.
5. Increment/sequence overflow harus fail closed. Tidak mengandalkan clock, wall-clock ordering, in-memory counters, DataStore terpisah dari transaksi, atau asumsi satu caller.
6. Migration memelihara seluruh rows, flags pending, active sessions, target provenance, timer fields, IDs dan metrik existing. Inisialisasi metadata tidak boleh menandai data sebagai synced. Tidak ada DROP/TRUNCATE/reseed/fallback destructive.
7. Lifecycle penghapusan metadata mengikuti keputusan D2 dan owner purge yang disetujui; bukan izin menetapkan retention baru. Tidak menyimpan token, email, payload, atau metadata tambahan di luar kebutuhan revisi.

### Files yang berpotensi berubah setelah scope disetujui

- `data/local/database/IMFITDatabase.kt`, entity metadata baru, DAO metadata baru, export schema8, migration/create callbacks dan module database demo/production.
- DAO/repository/importer yang memutasi parent/children; `SyncManager` capture/ack; tests Room migration/DAO/concurrency/reopen.
- Tidak mengubah schema Supabase, RLS/RPC, payload workout, timer engine, navigation atau calculations Progress/prefill.

## Test/exit gate scope khusus

- Migration7→8: row/flag/content preservation termasuk pending CREATE/UPDATE/DELETE, schema validation, reopen, no destructive fallback. Existing migration4→5→6→7 tetap diuji.
- Same-ms, child-only, reorder/remove, operation ABA, content ABA, delete/recreate, replace dan concurrent importer; tiga acceptance test merah harus lulus tanpa melemahkan assertion.
- RPC failure/cancellation sebelum/selama/setelah ack; ack atomic rollback; process reopen sebelum/sesudah remote response. Reopen sintetis bukan bukti OS kill-process aktual.
- Snapshot capture tidak menahan network di transaksi. Retry memakai IDs existing dan pending tetap durable.
- Account/epoch commit-publication fence dan adapter SDK harus terbukti **terpisah**. Metadata revision tidak menyelesaikan auth ordering, lost wakeup, logout policy atau coherence remote.
- Unit/Room/concurrency kedua variant. Real Supabase/RLS/RPC tetap memerlukan disposable project + akunA/B berizin; local/mock PASS bukan production safety PASS.

## Alternatif yang tidak dipilih sepihak

| Alternatif | Konsekuensi / batas |
| --- | --- |
| Full-state equality/fingerprint tanpa revision | Menangkap banyak perubahan, tetapi tidak membedakan ABA. Tidak memenuhi kontrak strict S06 saat ini |
| Counter in-memory / lock network sepanjang semua mutasi | Perlu proof seluruh writer/process/lifecycle; belum terbukti, dapat mengunci UX. Bukan substitusi otomatis untuk durable revision |
| Timestamp dijadikan revision | Mengubah semantik timestamp/ordering, tetap perlu enforcement semua writer; tidak digunakan sebagai metadata terselubung |
| Hentikan acknowledgement/upload permanen | Pending aman tetapi menonaktifkan fitur; merupakan degradation/policy, bukan fix B1 yang boleh diaktifkan diam-diam |

**Keputusan yang dibutuhkan:** izinkan scope desain + implementasi + pengujian migration lokal additive/non-destructive di atas, atau pilih alternatif untuk direview tanpa melonggarkan safety contract. Approval ini tidak sekaligus menyetujui D2/D6, backend protocol, commit/push atau deployment.
