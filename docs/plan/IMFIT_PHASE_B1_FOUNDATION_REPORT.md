# IMFIT — Phase B1 Foundation, Verification & Safety Stop

**Tanggal:** 22 Oktober 2026. **Branch:** `fix-bug`, HEAD `37bfdf9d817d60a8b9fb2e6e2cae1478c5ffca8d` + seluruh pekerjaan existing yang belum committed.

## 1. Outcome / authorization

**B1 PARTIAL — EXIT GATE FAIL / BLOCKED. Tidak lanjut B2–G. Tidak siap release.**

Persetujuan pengguna terbaru mengizinkan B1–G berurutan, tanpa approval administratif tiap fase, tetapi tetap melarang perubahan schema/backend di luar scope dan mewajibkan STOP bila pending-write safety/account isolation belum terbukti. A/B0 ditutup sesuai instruksi pengguna. Status izin historis pada dokumen A/B0/v2/v2.1 tidak ditulis ulang; persetujuan terbaru mengunggulinya untuk pekerjaan ini, bukan menghapus safety contract.

Fondasi B1 baru dibuat dan diuji **sebagai staged primitives**. **Belum ada DI binding, SDK adapter, atau integrasi ke SyncManager/SyncRunner/Worker/repository/UI.** Jadi production runtime tetap memakai algoritme existing, termasuk risiko B0. Ini bukan klaim telah memperbaiki sinkronisasi pengguna.

Tiga acceptance assertions baru terhadap **SyncManager produksi asli** gagal pada demo dan production: acknowledgement lama mengosongkan pending setelah same-ms parent edit, child-only edit, dan operation/content ABA. Assertions ini tetap merah; tidak diberi ignore/expected-failure, tidak dilemahkan, dan bukan dianggap acceptance unsafe.

Untuk memenuhi strict revision S06, diajukan [scope khusus durable aggregate revision / additive migration lokal](IMFIT_B1_REVISION_SCOPE_REQUEST.md). **Belum menerapkan migration/schema baru.** Account/SDK transition linearization juga belum terbukti. Integrasi runtime ditahan, bukan diakali melalui UI atau menghentikan rutinitas diam-diam.

## 2. Changes / files impacted

Semua path Kotlin di bawah relatif terhadap `app/src/`. Seluruhnya **file baru**, bukan overwrite pekerjaan sebelumnya.

| File | Implementasi dan batas |
| --- | --- |
| `main/java/com/diajarkoding/imfit/data/sync/RefreshCoordinator.kt` | Read-flight registry per session token + dataset, shared Deferred outcomes, full/subset/master demand API, cancellation ownership, satu user lane + independent catalog lane, monotonic automatic terminal cooldown termasuk typed failures, aggregate counters tanpa payload. Belum menjadi facade production entry points/outbox scheduler |
| `main/java/com/diajarkoding/imfit/data/sync/RefreshOutcome.kt` | Typed read outcomes: success/partial/offline/network/server/auth-required/auth-changed/cancelled/blocked; explicit retry eligibility. Tidak memetakan Worker legacy atau menganggap read success sebagai outbox acknowledgement |
| `main/java/com/diajarkoding/imfit/data/sync/SessionCommitGate.kt` | Epoch token referential baru untuk setiap transition termasuk same owner; lock order fence→Room, commit dan synchronous publication callbacks; stale/foreign token ditolak. Belum dihubungkan ke lifecycle SDK/observers aplikasi |
| `main/java/com/diajarkoding/imfit/data/sync/AggregateSnapshotStore.kt` | Capture parent + children + pending operation dalam satu transaksi Room, owner check, graph validation, deterministic ordering, termasuk template tombstone. Read-only; **tidak memiliki acknowledge API** berbasis equality/timestamp dan belum dipakai upload produksi |
| `test/java/com/diajarkoding/imfit/data/sync/RefreshCoordinatorTest.kt` |14 unit tests untuk shared outcomes/joins, subsets, lanes, cooldown, cancellation, stale epoch, failure propagation, terminal retry classification |
| `test/java/com/diajarkoding/imfit/data/sync/SessionCommitGateTest.kt` |6 unit tests untuk ABA/same-owner relogin, commit/transition ordering, publication guard, cancellation, exceptions, foreign token |
| `androidTest/java/com/diajarkoding/imfit/b1/AggregateSnapshotStoreTest.kt` |8 real Room7 tests: capture/owner/flags, concurrent mutation, malformed graph, unknown operation, template tombstone/order, actual Room fence, cancellation rollback, schema7 ABA indistinguishability proof |
| `androidTest/java/com/diajarkoding/imfit/b1/SyncRevisionGateTest.kt` |3 **desired S06 gate tests** terhadap real SyncManager/SDK/Room; semuanya FAIL pada algoritme existing. Tetap sebagai blocking regression tests untuk repair berikutnya |
| `docs/plan/IMFIT_B1_REVISION_SCOPE_REQUEST.md` | Request scope schema terpisah, alternatives/trade-offs, preservation/test gates. Bukan migration atau keputusan D2/D6 |
| `docs/plan/IMFIT_PHASE_B1_FOUNDATION_REPORT.md` | Laporan ini |

Tidak mengubah Gradle/dependency, Room entities/DAO/schema7, SyncManager/Worker/scheduler, auth/logout, backup rules, backend/RPC/RLS, timer, nav, metric/prefill, theme/language atau renderer. Tidak ada commit/push/deployment.

## 3. Tests executed / actual results

**Host:** macOS, OpenJDK21. **Device:** `emulator-5554`, Android API34. Debug diagnostic variants; runner opt-in B0 yang memakai plain Application, sehingga tidak memulai Hilt/network/timer normal.

| Execution final | PASS execution | FAIL | Skipped / errors | Interpretasi |
| --- | ---: | ---: | --- | --- |
| Unit demoDebug |194 |0 |0 /0 |174 baseline +20 B1 primitive tests |
| Unit productionDebug |187 |0 |0 /0 |167 baseline +20 B1 primitive tests |
| Build demo application + AndroidTest |PASS |0 |— |Isolated diagnostic debug, bukan minified release |
| Build production application + AndroidTest |PASS |0 |— |Isolated diagnostic debug, bukan Supabase E2E |
| Instrumentation demoDebug/API34 |56 |**3** |0 /0 |59 executions:8 B1 Room +3 S06 +28 historical B0 +20 existing data regression |
| Instrumentation productionDebug/API34 |38 |**3** |0 /0 |41 executions:8 B1 Room +3 S06 +28 historical B0 +2 scheduler characterization |
| `git diff --check` |PASS |0 |— |Existing tracked diff whitespace check; new paths diperiksa terpisah saat finalisasi |

**Penting:** historical B0 yang assertion-nya lulus masih mereproduksi unsafe baseline.28 common cases mencakup20 `EXPECTED_UNSAFE_BEHAVIOR` +8 `BASELINE_PASS`; dua scheduler cases mencakup1 unsafe +1 baseline. Ini **bukan** target invariants yang lulus. Delapan B1 Room tests juga mencakup **satu blocker-proof** bahwa schema7 tidak membedakan ABA; bukan delapan bukti full sync safety.

### 3.1 FAIL — desired production acknowledgement safety

Kedua variant menghasilkan tiga failures yang sama:

| Test / desired assertion | Actual |
| --- | --- |
| `sameMillisecondParentEditMustRemainPendingAfterOldRemoteAcknowledgement` — parent operation tetap `CREATE` |`null` setelah acknowledgement lama |
| `childOnlyEditMustRemainPendingAfterOldRemoteAcknowledgement` — newer child operation tetap `CREATE` |`null` karena clear seluruh child flags |
| `operationAndContentABAMustNotAcceptAcknowledgementOfOldRevision` — exact old revision tidak boleh ack newer ABA write |Parent operation `null` |

Ini existing P0 safety defects, bukan regresi yang diintroduksi oleh integrasi baru: tidak ada production caller yang dialihkan ke primitives. Namun **S06 tetap FAIL**, sehingga B1 tidak boleh lulus hanya karena unit/local primitives hijau.

### 3.2 PASS dengan batas klaim

-10 overlapping primitive requests:1 started,9 joined,1 terminal, registry active0, waiter menerima result object yang sama. Bukan10 real SDK/public SyncManager calls; B0 public-entry requests tetap unsafe.
- Primitive full/subset/master demands join dataset yang overlap; user lane serial, catalog independent. Burst automatic typed network failure dibatasi oleh monotonic cooldown; manual bypass terminal auto cooldown tetapi tetap join flight aktif. Immediate sequential **manual** terminal bursts / adapter-exception backoff belum ditutup sebagai S02 production proof.
- Cancel satu waiter tidak membatalkan shared read. Cancel service/invalid epoch membersihkan registry; old completion tidak menghapus successor. Ini tidak menguji OS process death atau durable outbox upload.
- Gate menolak token epoch lama/foreign termasuk A→B→A; transition yang datang ketika commit memegang fence menunggu commit selesai. Actual Room rollback pada cancellation dan stale publication callback diuji.
- Snapshot baru memakai satu transaksi; concurrent writer tertahan sampai capture selesai, sehingga parent/children berasal satu snapshot. Tidak ada network dalam transaksi. Capture tidak clear flags dan bukan ack revision.

### 3.3 Initial test failures / corrections, tidak disembunyikan

Initial unit demo:194 tests,2 failures. Logs dan XML retained.

1. Subset demand dapat baru terdaftar sesudah shared flight selesai karena child `async` default dispatch. **Production primitive diperbaiki:** subset registration memakai `CoroutineStart.UNDISPATCHED` sebelum yielding. Assertion dataset count1 tetap sama.
2. Identity assertion standard `IllegalArgumentException` berhadapan dengan coroutine stacktrace recovery yang dapat menyalin exception. Fixture memakai custom exception dengan identity marker agar tidak dapat dicopy oleh recovery; **assertSame tetap dipertahankan**, tidak diganti menjadi blanket catch/success.

Rerun unit kedua variant lulus20 tests baru per variant. Instrumentation pertama sudah mencapai hasil final tabel: tidak ada perbaikan assertions S06 untuk menghilangkan failure. Satu diagnostic package-presence inspection awal menganggap exit1 untuk absent package sebagai command failure; diperbaiki pada host inspection script sebelum install, tanpa perubahan app/perangkat.

## 4. Commands / evidence

```sh
./gradlew :app:testDemoDebugUnitTest :app:testProductionDebugUnitTest --console=plain

./gradlew :app:testDemoDebugUnitTest :app:testProductionDebugUnitTest :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:assembleProductionDebug :app:assembleProductionDebugAndroidTest -PmodalDiagnostic=true -Pb0Instrumentation=true --console=plain

./gradlew :app:assembleDemoDebugAndroidTest :app:assembleProductionDebugAndroidTest -PmodalDiagnostic=true -Pb0Instrumentation=true --console=plain

adb shell am instrument -w -r -e class com.diajarkoding.imfit.b1.AggregateSnapshotStoreTest,com.diajarkoding.imfit.b1.SyncRevisionGateTest,com.diajarkoding.imfit.b0.SyncSafetyCharacterizationTest,com.diajarkoding.imfit.data.sync.RemoteSnapshotImporterTest,com.diajarkoding.imfit.data.repository.ExerciseReactiveRepositoryTest,com.diajarkoding.imfit.data.repository.WorkoutRepositoryRoomFirstTest com.diajarkoding.imfit.demo.modaldiagnostic.test/com.diajarkoding.imfit.b0.B0TestRunner

adb shell am instrument -w -r -e class com.diajarkoding.imfit.b1.AggregateSnapshotStoreTest,com.diajarkoding.imfit.b1.SyncRevisionGateTest,com.diajarkoding.imfit.b0.SyncSafetyCharacterizationTest,com.diajarkoding.imfit.b0.WorkerSchedulingCharacterizationTest com.diajarkoding.imfit.modaldiagnostic.test/com.diajarkoding.imfit.b0.B0TestRunner
```

Instrumentation bukan exit0/PASS hanya karena `adb` command return0: log final memiliki `FAILURES!!!` dan `Tests run:59/41, Failures:3`. Host parser menghitung terminal status0/-2 serta skipped/error secara terpisah.

Evidence host:
`/private/var/folders/yv/171k2lgd0jdg4y0jclvxjtzw0000gn/T/opencode/imfit-phase-b1-20261022/`

- `baseline-files.json`, `baseline-source.tar.gz`, `baseline-status.txt`, `baseline.diff`: preservation483 files sebelum B1.
- `unit-baseline.log`: baseline174 demo /167 production; seluruhnya lulus.
- `build-test-initial.log`, `unit-initial-xml/`: initial2 unit failures.
- `build-test-corrected.log`, `unit-demo-xml/`, `unit-production-xml/`: successful final unit/build evidence.
- `build-revision-gate.log`: compiled blocking acceptance tests kedua variants.
- `demo-instrumentation-initial.log`, `production-instrumentation-initial.log`: final instrumentation outcomes, termasuk6 failed executions. Nama initial dipertahankan; tidak ada subsequent instrumentation rerun.
- `final-results.json`: per-class executed/pass/fail/error/skipped counts dan failed test names.
- `sdk-transition-inspection.txt`: source-only inspection SDK Auth3.0.3, artifact hash + relevant line excerpts; **bukan** runtime adapter ordering proof.
- `device-baseline.json`, `device-final.json`, install/cleanup logs dan preservation manifest: protection evidence.

SHA-256 instrumentation logs:

- Demo: `3c04a557aa005a66c376794bd702a4889ff555b33f8237325271528e880a608c`.
- Production: `e6a6bfc218206345e951f133711e2d0f614eed303c835b3cfb50f537e9cb041d`.

## 5. B1 contract / remaining risks / exit verdict

| B1 requirement | Status akhir dan gate |
| --- | --- |
| Shared single-flight / dataset dedup |Primitive implemented/local PASS; actual SDK/SyncManager/public entry integration **NOT_IMPLEMENTED** |
| Account/session epoch isolation |Gate local PASS; SDK lifecycle adapter, owner observers/by-ID/results **BLOCKED / NOT_PROVEN** |
| Atomic commit/publication fence |Primitive + actual Room local tests PASS; production SDK transition/observer publication ordering **NOT_PROVEN** |
| Consistent aggregate snapshot capture |New read-only store/Room tests PASS; existing upload belum dialihkan |
| Revision-safe acknowledgement |**FAIL**:3 desired assertions per variant. Schema scope khusus diminta, belum diterapkan |
| Cancellation/finalization |Primitive local PASS; production SyncState/outbox ownership belum diperbaiki |
| Durable scheduling / lost wakeup |**NOT_IMPLEMENTED / NOT_PROVEN**. APPEND_OR_REPLACE/backoff existing tidak diubah. Scheduler characterization bukan handshake proof |
| Typed outcomes |Read contract prepared; production runner/worker/per-dataset mapping belum diintegrasikan |

**B1 exit: FAIL/BLOCKED.** P0 pending-write acknowledgement dan SDK account isolation belum dapat disahkan. Tidak mengklaim keamanan produksi dari tests mock/local.

## 6. Consolidated phase status

| Phase | Changes / test / regression / risk / verdict |
| --- | --- |
| A, B0 |Closed sesuai authorization terbaru; dokumen/test baseline tidak diubah |
| **B1** |Staged foundation + tests + scope request, daftar file §2. Unit/local Room lulus pada batas §3; desired production S06 FAIL. Tidak ada observed baseline test regression; P0 baseline tetap ada. **PARTIAL, EXIT FAIL/BLOCKED** |
| **B2** |Belum diimplementasikan/dijalankan karena B1 belum PASS. D2 retention/logout dan D6 template degradation/protocol tetap **DECISION-GATED**. Tidak mengaktifkan denylist, retention, purge atau destructive replacement baru |
| **C** |Tidak ada perubahan UI/loading, **NOT_TESTED**, blocked predecessor. Cache/no-flicker/reduced-motion/semantics belum diimplementasikan |
| **D** |Tidak ada Paging dependency/DAO/pager/benchmark, **NOT_TESTED**, blocked predecessor.81/150/1500 benchmark belum dijalankan |
| **E** |Tidak ada perubahan list/picker/selection/footer, **NOT_TESTED**, blocked predecessor |
| **F** |Tidak ada perubahan Home/Progress/Profile/History, **NOT_TESTED**, blocked predecessor. Header8/nav/date/draft/metrics/theme/language baseline tidak disentuh; PROG-SEM-01 dan PROG-VIS-02 tetap OPEN |
| **G** |Hanya B1-scoped verification §3, bukan full G. Compose UI/screenshots/timer device E2E/minified release/OEM/actual OS process death **NOT_TESTED**. Supabase E2E **BLOCKED_ENVIRONMENT**: disposable project + akunA/B berizin belum tersedia; MCP Supabase tidak diperlukan untuk tests lokal ini dan tidak digunakan |

## 7. Preservation / repository and device safety

- Seluruh483 baseline files, termasuk dirty source/schema7/B0/enam-issue work, tetap byte-identik; B1 hanya menambahkan10 files yang disebutkan §2. Baseline documents tidak ditulis ulang. Fingerprint final dicatat pada `preservation-final.json` di evidence host.
- Tidak reset/revert/delete user work, mengubah git index, commit atau push.
- Aplikasi normal tidak diinstall/clear/uninstall. APK/paths `com.diajarkoding.imfit` dan `.demo` tetap identik: SHA-256 masing-masing `87e2ffc32dd4028c89c59294923a3d74121820c5ecc44aadeadb48d4f8def0b2` / `a1ffd03ba5dcf9521f4bc63baecdbcf9e840021fc0132240710d675c616b8bb9`.
- Empat diagnostic packages diverifikasi absent sebelum install; package manifest dibuktikan tepat diagnostic sebelum `adb install`. Hanya empat packages buatan sesi ini yang di-uninstall sesudah tests. Normal data tidak dibuka/disentuh oleh fixtures; tidak mengklaim byte comparison DB normal.
- Device snapshot normal APK/paths, diagnostic absence, airplane mode, rotation, animator scale dan night mode pre/post sama persis. Settings tidak diubah.
- Tidak ada request ke backend asli: new Room tests tanpa SDK; B0/revision gate memakai MockEngine host `phase-b0.invalid`, memory sessions sintetis. Tidak memakai MCP Supabase/kredensial nyata/production mutation.

**Next action:** keputusan scope khusus pada [revision request](IMFIT_B1_REVISION_SCOPE_REQUEST.md), kemudian lanjutkan repair/integrasi B1 dan buktikan ulang gate. Approval fase normal sudah ada; tidak diminta ulang. D2/D6 dan remote/backend/schema scope lain tetap keputusan terpisah.
