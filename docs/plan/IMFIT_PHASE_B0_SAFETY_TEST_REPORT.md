# IMFIT — Phase B0 Safety Characterization & Deterministic Test Harness

**Tanggal:** 22 Oktober 2026. **Branch:** `fix-bug`, baseline HEAD `37bfdf9d817d60a8b9fb2e6e2cae1478c5ffca8d` beserta seluruh perubahan enam issue yang belum committed.
**Authorization:** APPROVED — PHASE B0 ONLY. Kontrak: [v2](IMFIT_REFRESH_PAGING_SHIMMER_SYNC_PLAN_v2.md), [v2.1](IMFIT_REFRESH_PAGING_SHIMMER_SYNC_PLAN_v2.1.md), [Phase A](IMFIT_PHASE_A_BASELINE_AND_SAFETY_GATE.md).

## 1. Outcome dan cara membaca hasil

Fondasi test B0 dibuat tanpa mengubah source produksi, algoritme sync, kebijakan logout/retention, template dispatch, Room schema, backend, timer, atau renderer UI. Refactor produksi **tidak diperlukan**: SDK existing menerima injected HTTP engine; DAO delegation dan Room transaction/query executors dipakai hanya di fixture.

**Final B0 exit verdict: SELESAI untuk fondasi characterization terbatas, bukan safe-sync/release PASS.** Final unit regression: **174 demo +167 production =341**, zero failures/errors/skipped. Final instrumentation API34: **48 demo** (28 B0 +20 existing data regression) dan **30 production** (28 B0 +2 scheduler), zero execution failures/skips. Delapan test harness unit baru dijalankan pada masing-masing variant.

Dalam28 common Android cases terdapat **8 BASELINE_PASS +20 EXPECTED_UNSAFE_BEHAVIOR per variant**. Production scheduler menambah1 BASELINE_PASS dan1 EXPECTED_UNSAFE_BEHAVIOR; unit backup inspection juga EXPECTED_UNSAFE pada kedua variant. Angka unsafe bukan jumlah insiden produksi atau unique root causes.

**Penting:** successful characterization berarti test berhasil **mereproduksi** perilaku baseline, termasuk perilaku yang tidak aman. Itu bukan bukti target safety invariant telah diperbaiki. Tidak ada perubahan B1/B2 atau invariant yang dinyatakan PASS setelah production fix.

Klasifikasi:

- **BASELINE_PASS:** existing behavior memenuhi assertion baseline yang disebutkan, hanya pada boundary/lingkungan test tersebut.
- **EXPECTED_UNSAFE_BEHAVIOR:** unsafe behavior terbukti reproducible pada fixture yang menjalankan class produksi asli. Named test dan evidence record secara eksplisit menyebut unsafe, bukan dianggap perilaku produk yang diterima.
- **BASELINE_FAILURE:** desired safety invariant belum terpenuhi, atau initial test execution gagal; keduanya dibedakan pada §5/§6. Tidak menyembunyikan failure dengan blanket expected-exception/ignore.
- **NOT_TESTED:** tidak ada eksekusi untuk klaim terkait.
- **BLOCKED_ENVIRONMENT:** lingkungan/izin/protocol belum tersedia. Tidak dijadikan PASS.

## 2. File added / modified dan runtime boundary

| File | Perubahan B0 |
| --- | --- |
| `app/build.gradle.kts` | Test-only shared Kotlin source directories, test dependency Ktor MockEngine versi existing3.0.2 dan Android WorkManager testing versi existing; opt-in `b0Instrumentation=true` memilih B0 runner. Default runner/runtime dependency aplikasi tidak berubah. Existing schema-assets line dari pekerjaan sebelumnya tetap dipertahankan |
| `app/src/b0Test/kotlin/com/diajarkoding/imfit/b0/SafetyHarness.kt` | ScriptedSyncRemote, RequestLedger, ControlledSession/Clock, suspend/blocking probes, Room executor probe, explicit classifications |
| `app/src/test/java/com/diajarkoding/imfit/b0/SafetyHarnessTest.kt` |8 unit tests: probe ordering/cancellation, ledger counting/double-completion, epoch ABA/events, fixed clock collision, executor, backup XML unsafe characterization |
| `app/src/androidTest/java/com/diajarkoding/imfit/b0/SyncSafetyFixture.kt` | Real Supabase SDK with MockEngine + real SyncManager/DAOs/Room7/preferences/auth repository; synthetic identities, isolated DB, typed read/write probes |
| `app/src/androidTest/java/com/diajarkoding/imfit/b0/SyncSafetyCharacterizationTest.kt` |28 characterization tests against real production classes/private stages/public entries; B01–B14 coverage limits below |
| `app/src/androidTest/java/com/diajarkoding/imfit/b0/B0TestRunner.kt` | Opt-in plain Application **only inside diagnostic packages**, avoiding Hilt application startup/network/timer runtime. Does not change normal app startup |
| `app/src/androidTestProduction/java/com/diajarkoding/imfit/b0/WorkerSchedulingCharacterizationTest.kt` | Real production WorkManagerSyncScheduler, real WorkManager testing DB/driver, held fake worker bodies; burst chain and successor enqueue tests |
| `docs/plan/IMFIT_PHASE_B0_SAFETY_TEST_REPORT.md` | Laporan ini |
| `docs/plan/evidence/b0/production-characterization-records.txt` |30 final redacted production evidence records; tidak memuat token/email/payload |

Tidak mengubah `app/src/main/**`, `app/src/demo/**`, `app/src/production/**`, schema export, baseline tests, version catalog, Supabase migration/RLS/RPC, maupun tiga dokumen baseline. Report/evidence B0 merupakan tambahan tersendiri, bukan pengganti Phase A.

## 3. Harness architecture dan batas klaim

```text
JUnit / AndroidJUnit
  ├─ ControlledSession -> SDK importSession (memory storage, auto-refresh off)
  ├─ actual SyncManager / AuthRepositoryImpl / ExerciseRepositoryImpl / SyncWorker
  │    ├─ actual PostgREST / RPC serialization -> SDK injected MockEngine
  │    │    └─ ScriptedSyncRemote -> RequestLedger + gates + synthetic response/error
  │    └─ real Room7 / DAOs / RemoteSnapshotImporter / SyncPreferences
  │         └─ delegated DAO scan probes + transaction executor + query callback gates
  └─ actual production WorkManagerSyncScheduler -> WorkManager testing database/driver
       └─ held worker future (not real remote worker body in scheduling tests)
```

### Network dan session control

- URL client selalu `https://phase-b0.invalid`; engine menolak non-fixture host dan tidak melakukan socket request. Tidak membaca kredensial project atau memakai akun Supabase nyata. Production URL yang tertanam pada diagnostic APK tidak dipakai oleh B0 runner/fixture.
- Script dapat mengembalikan catalog/profile/template/history/RPC JSON, page/range berbeda, HTTP401/403/500, timeout exception, cancellation, duplicate IDs dan graph yang berubah. Request melewati **SDK serialization + actual SyncManager**, bukan implementasi sync tiruan.
- Remote mutation/empty-child window disimulasikan pada handler tiap request; parent recheck existing benar-benar dieksekusi. Ini menunjukkan client behavior, bukan membuktikan frekuensi kejadian atau transaction server nyata.
- ControlledSession membedakan synthetic owner ID dan epoch serta event LOGIN/LOGOUT/RESTORE/EXPIRY/DEEP_LINK. SDK identity diubah dengan memory-only `importSession`. Test membuktikan check ownerId existing tidak mengenali ABA; **bukan** bukti login/restore/expiry/deep-link callback SDK asli sudah difence.
- ControlledClock diuji sendiri dan digunakan untuk same-millisecond entity timestamps. Tidak menginjeksi/mengubah SystemClock produksi. Virtual production cooldown/backoff timing tetap NOT_TESTED; successful cooldown dan repeated immediate requests diuji dengan rendezvous, bukan sleep.

### Probes, request counters dan job ownership

- `SuspendProbe`: CompletableDeferred reached/release; `BlockingProbe`: CountDownLatch bounded10s untuk API executor/query callback nonsuspend. Scenario timeout30s mendeteksi hang. Tidak ada arbitrary `Thread.sleep` atau `delay()` di seluruh file B0.
- Before-network/remote-ack response: scripted transport barriers; after-parent/queue scan: delegated real DAO; before-Room-transaction: test Room transaction executor; inside transaction: synchronous query callback. Seluruh mutasi masih memakai implementasi Room/SyncManager existing.
- Probe acknowledgement ditempatkan **sebelum** production memperoleh transaksi Room. Menahan transaction sambil mencoba menulis lewat connection yang sama adalah deadlock fixture, bukan cara sah menciptakan concurrent local write.
- RequestLedger mencatat endpoint/method, dataset, synthetic epoch, started/terminal/cancelled, active dan max transport concurrency; supports explicit joined events. Existing production belum mempunyai join hook: observed `joined=0` bukan introspeksi internal SDK, sedangkan10 actual transport starts dan overlapping catalog requests adalah bukti duplicate calls yang dapat diassert.
- Per-request terminal ledger adalah **transport** outcome, misalnya HTTP500 response completed; sync result/outcome diassert terpisah. Tidak mencampur keberhasilan HTTP delivery dengan sinkronisasi berhasil.
- Manually constructed SyncManager startup scope dihentikan dan di-join **sebelum import identity**. Public `run`, `syncAll` (target reconnect), `refreshMasterData` diuji eksplisit. Real OS network callback delivery, process-owned reconnect subscription lifetime, dan foreground navigation lifecycle NOT_TESTED.
- Private suspend stage invocation melalui reflection menjalankan metode produksi asli dan memeriksa nama/signature. Ini membatasi boundary test; bukan klaim seluruh public lifecycle diuji oleh setiap stage test. Jika signature berubah, fixture harus fail, bukan fallback ke implementasi tiruan.
- Test runner hanya menerima `com.diajarkoding.imfit.demo.modaldiagnostic` / `com.diajarkoding.imfit.modaldiagnostic`, memakai plain Application sehingga production Hilt runtime tidak otomatis terhubung backend. Synthetic logout menggunakan empty timer/no-op effects dan intercepted stopService; timer aktif/perangkat bukan bagian test logout ini.

### Persistence dan worker control

- In-memory Room untuk importer/transaction/ack/identity tests; temporary named database untuk close/reopen pending recovery. Ini bukan kill-process OS atau crash Android yang nyata.
- Existing SyncPreferences DataStore hanya di diagnostic package; marker unknown/cache coexistence diuji sebagai storage state. Tidak menambahkan coverage algorithm D8 atau renderer.
- Production scheduler tests memakai WorkManager test initialization/driver dan held future sebagai worker body; completion dikendalikan eksplisit. Enqueue policy existing diuji, tanpa menjalankan backend. Real pending-drain-to-enqueue race serta OS recovery tetap terpisah dari scheduler-chain proof ini.

## 4. Commands dan evidence locations

Host: macOS, OpenJDK21.0.6. Device: `emulator-5554`, Android API34. APK normal tidak diganti. Build/test variants debug; release/minified dan physical OEM NOT_TESTED.

### Commands reproducible

```sh
./gradlew :app:testDemoDebugUnitTest :app:testProductionDebugUnitTest --console=plain

./gradlew :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest :app:assembleProductionDebug :app:assembleProductionDebugAndroidTest -PmodalDiagnostic=true -Pb0Instrumentation=true --console=plain

adb install app/build/outputs/apk/demo/debug/app-demo-debug.apk
adb install app/build/outputs/apk/androidTest/demo/debug/app-demo-debug-androidTest.apk
adb install app/build/outputs/apk/production/debug/app-production-debug.apk
adb install app/build/outputs/apk/androidTest/production/debug/app-production-debug-androidTest.apk

adb shell am instrument -w -r -e class com.diajarkoding.imfit.b0.SyncSafetyCharacterizationTest,com.diajarkoding.imfit.data.sync.RemoteSnapshotImporterTest,com.diajarkoding.imfit.data.repository.ExerciseReactiveRepositoryTest,com.diajarkoding.imfit.data.repository.WorkoutRepositoryRoomFirstTest com.diajarkoding.imfit.demo.modaldiagnostic.test/com.diajarkoding.imfit.b0.B0TestRunner

adb shell am instrument -w -r -e class com.diajarkoding.imfit.b0.SyncSafetyCharacterizationTest,com.diajarkoding.imfit.b0.WorkerSchedulingCharacterizationTest com.diajarkoding.imfit.modaldiagnostic.test/com.diajarkoding.imfit.b0.B0TestRunner
```

Untuk rerun, pastikan diagnostic packages bukan milik pekerjaan lain; fixture menolak normal packages. Jangan memakai install/clear/uninstall terhadap `com.diajarkoding.imfit` atau `.demo`. Tidak mengubah permission, appops, network, tema, font, animator scale atau device settings untuk test ini.

Evidence host:
`/private/var/folders/yv/171k2lgd0jdg4y0jclvxjtzw0000gn/T/opencode/imfit-phase-b0-20261022/`.

- `baseline-files.json`, `baseline-status.txt`, `baseline.diff`, `device-baseline.json`: preservation snapshots sebelum B0.
- `unit-initial.log`, `unit-second.log`, `instrumentation-compile-initial.log`: initial harness compile failures dan corrections, bukan dihapus.
- `instrumentation-initial.log`: first27 execution,23 passed/4 failed; analisis §6.
- `instrumentation-corrected.log`:28 corrected characterization executions passed.
- `instrumentation-final-regression.log`:48 executions passed sebelum opt-in runner; retained sebagai reproducibility evidence, bukan menggantikan final runner runs.
- `build-both-isolated.log`, `build-production-runner-corrected.log`, `unit-final.log`, `demo-final.log`, `production-final-corrected.log`: successful final build/run evidence. `production-final.log` dipertahankan sebagai initial scheduler initialization failure.
- `app/build/test-results/testDemoDebugUnitTest/TEST-*.xml` dan production counterpart: JUnit unit reports; build outputs dapat ditimpa rerun, host evidence dir mempertahankan summary/logs yang disalin saat finalisasi.
- [Final redacted production records](evidence/b0/production-characterization-records.txt) disalin dari PID final pada logcat; raw host file `characterization-final-both-variants.txt` juga dipertahankan. Tidak menyimpan token/email/user payload sebagai evidence.

### Final execution summary

| Run final | Test executions | Execution failures / skipped | Batas |
| --- | --- | --- | --- |
| Unit demoDebug |174 |0 /0 | Termasuk8 new harness tests dan166 existing |
| Unit productionDebug |167 |0 /0 | Termasuk8 new harness tests dan159 existing |
| Instrumentation demoDebug isolated/API34 |48 |0 /0 |28 B0 +20 existing data regression |
| Instrumentation productionDebug isolated/API34 |30 |0 /0 |28 B0 +2 real scheduler tests, plain test Application |

Tidak menjumlahkan rerun development menjadi jumlah test unik. Final `demo-final.log` SHA-256 `13c96014ec3189de4d4b547b38823b745ca40ec0860c4b77b54308b04e78f97b`; final `production-final-corrected.log` SHA-256 `527759f53974d282291854f17e7914d5e56285c070263933b5c4966a3e7b2c13`. `final-results.json` di evidence host mencatat counts, execution assertions, APK hashes dan namespace checks; unit XML disalin ke subfolder host evidence agar tidak hilang saat build berikutnya menimpa `app/build`.

## 5. Hasil characterization dan B01–B14 coverage

### 5.1 Results yang terbukti dari actual production boundary

| Contract | Hasil baseline / fakta terukur | Status dan gap yang tetap terbuka |
| --- | --- | --- |
| B01 |10 concurrent `run` menghasilkan10 catalog requests serial, maxConcurrency1; full/master overlap menghasilkan2 catalog requests, maxConcurrency2; first initialization mendownload catalog/profile masing-masing2 kali | EXPECTED_UNSAFE_BEHAVIOR. Desired S01 shared single-flight belum terpenuhi; OS reconnect/UI-origin delivery tidak diuji |
| B02 | Successful repository cooldown:1 request,1true +9 skipped false;10 failed concurrent entries menghasilkan10 requests | BASELINE_PASS untuk success throttling, EXPECTED_UNSAFE untuk failure storm; bukan shared result, clock injection produksi belum ada |
| B03 | A→B transition sebelum/di dalam Room write tetap mengizinkan old commit; A→B→A epoch baru tidak dikenali ID check; old private aggregate ter-commit ke partisiA setelah SDK menjadiB | EXPECTED_UNSAFE_BEHAVIOR. Tidak mengklaim row berubah ownerB atau actual UI data leak; row tetap ownerA, fence/publication perlu B1 |
| B04 | observeCurrentUser yang sudah mengamatiA masih mengeluarkanA setelah SDK switchB dan RoomA diupdate | EXPECTED_UNSAFE_BEHAVIOR pada observer asli; actual screen/pager/selection/result delivery NOT_TESTED |
| B05 | Payload workout memakai parent lama + child weight99 dari revisi baru; newer timestamp menjaga pending, tetapi same timestamp/op membersihkan pending concurrent edit dan child flags menjadi SYNCED | EXPECTED_UNSAFE_BEHAVIOR. Capture dan ack revision target gagal; full operation ABA payload replay/revision repair belum dilakukan |
| B06 | Pending template terhapus sebelum HTTP logout dikirim; SDK logout HTTP500 gagal, pending tetap hilang | EXPECTED_UNSAFE_BEHAVIOR. Synthetic empty timer; safe logout/retention/D2 belum diterapkan, actual SDK offline signOut dan privacy NOT_TESTED |
| B07 | Page3 gagal setelah1000 remote catalog rows: cache tetap,3 requests, no partial commit; parent recheck berbeda menolak import; nested importer transaction rollback mempertahankan parent/children | BASELINE_PASS untuk guard yang sudah ada. Server cap short-page menonaktifkan cached missing row; duplicate IDs last-wins; unchanged parent + empty children menghapus cached child: EXPECTED_UNSAFE |
| B08 | CREATE/UPDATE benar-benar mengirim parent→DELETE children→INSERT; empty CREATE tetap DELETE children; DELETE hanya parent tombstone; failed child INSERT tetap pending dan retry mengulang DELETE | EXPECTED_UNSAFE_BEHAVIOR. Tidak mengaktifkan denylist, tidak memutuskan D6, tidak mengklaim atomic backend |
| B09 | SyncWorker enum mapping diuji; pending survive Room reopen; late direct-DAO write tersisa pending walau runResult SUCCESS. Actual APPEND_OR_REPLACE burst3 menghasilkan3 WorkRequests berantai; enqueue saat worker pertama running mempertahankan successor sampai keduanya SUCCEEDED | BASELINE_PASS untuk successor/persistence/mapping, EXPECTED_UNSAFE untuk burst load amplification dan coarse-success. Bukan end-to-end no-lost-wakeup/process-death proof; direct DAO fixture tidak mengemulasi repository enqueue |
| B10 |401/403/500/timeout mempertahankan cache tetapi semua menjadi RETRYABLE_FAILURE; cancellation menyelesaikan transport active0 dan melepas mutex, namun SyncState tertinggal SYNCING; next run berhasil | BASELINE_PASS untuk cache/mutex; EXPECTED_UNSAFE untuk stale terminal state. Typed outcomes B1 belum ada |
| B11 | Source manifest allowBackup=true; backup/data extraction XML active exclusion count0 | EXPECTED_UNSAFE_BEHAVIOR untuk pending-retention privacy risk. Bukan actual backup/restore test, tidak mengubah rules |
| B12 | ControlledSession events/epoch helper diuji; actual SDK memory import digunakan pada ABA dan observer tests | PARTIAL. Real login/restore/expiry/deep-link lifecycle callbacks, SDK persistence/revocation dan server sessions NOT_TESTED; tidak dinyatakan invariant PASS |
| B13 | Pending dan cached row survive temporary DB reopen saat initial-completed=false | BASELINE_PASS storage coexistence saja. D8 marker crash-linkage, restore skew dan actual renderer masih NOT_TESTED |
| B14 | getAllExercises/search lokal menghasilkan0 remote requests | BASELINE_PASS pada existing repository read. Paging belum dibuat; append/retry invariant actual pager NOT_TESTED |

### 5.2 Desired invariant failure bukan production fix

S01/S02 shared-flight/burst, S03/S04 identity/publication, S05/S06 consistent capture/ack, S07/S08 unverified snapshot protection, S10 reliable terminal state, serta S12 pending-safe logout **belum memenuhi target** berdasarkan unsafe characterization di atas. Inilah BASELINE_FAILURE terhadap safety target, walaupun assertion yang mendokumentasikan unsafe state berhasil.

S09 hanya memiliki worker mapping/scheduler-chain/persistence evidence; bukan full pending-liveness proof. S11 belum dapat disahkan untuk append karena paging belum ada. Tidak mempromosikan partial test ini menjadi Phase B1/B2 exit PASS.

## 6. Initial failures dan fixture corrections — tidak disembunyikan

1. Shared Kotlin helper awal didaftarkan sebagai Java sources pada AGP9 sehingga unit compiler tidak menemukannya. Dipindah ke **test Kotlin source directories**; tidak mengubah main source set.
2. Instrumentation compile awal menemukan unsupported star import Companion dan RoomDatabase yang bukan Closeable. Diperbaiki pada test code (explicit imports, try/finally close). Tidak mengubah assertion keamanan.
3. First instrumentation27: empat failures. Counter tests mengharapkan10/2 tetapi mendapat11/3, dan late-write pendingCount berbeda. Fixture menyetel initial-completed=true namun lastSync=0 + empty templates tetap memicu `needsInitialSync`. Warm fixture kini menyetel lastSync=1 sesuai intended warm context; assertions10/2 **tetap**. Initial-download double requests dipertahankan sebagai test terpisah, bukan dihilangkan dari temuan.
4. Same-ms ack test timeout karena probe berada di DAO acknowledgement **di dalam Room transaction**, sementara test mencoba mutation yang membutuhkan writer yang sama. Probe dipindah ke remote acknowledgement response sebelum transaksi. Assertion tetap menuntut reproduksi lost-pending flag; final case membuktikan unsafe state tersebut. Initial timeout adalah fixture defect, bukan bukti app deadlock pada skenario ini.
5. First production scheduler run memiliki JUnit initialization failure: dua expression-body tests mengembalikan `Log.i` Int, bukan void. Diganti menjadi `runBlocking<Unit>` pada test saja.28 common characterizations pada run tersebut tetap berjalan; scheduler tests baru dinilai setelah rerun, bukan disimpulkan lulus dari build.

Tidak memakai ignore, blanket expected-failure annotation, sleep tambahan, atau perubahan production sync untuk membuat suite tampak hijau. Test names/evidence `EXPECTED_UNSAFE_BEHAVIOR` tetap eksplisit; future production repair harus menambahkan assertion desired invariant dan mereview perubahan characterization, bukan diam-diam menormalkan unsafe baseline.

## 7. Perubahan yang diperlukan untuk B1 / B2 — belum diimplementasikan

**B1 berdasarkan bukti:** shared-flight lintas entry point dan shared outcomes; remove duplicated initial read; auth epoch/atomic commit fence/live owner subscription; snapshot parent+children atomik; ack revision yang tahan same-ms/ABA; reliable finalization/cause outcomes; wakeup handshake yang mempertahankan keselamatan successor dari scheduler existing. Jangan mengganti APPEND_OR_REPLACE menjadi KEEP hanya untuk mengurangi jumlah requests tanpa drain proof.

**B2 setelah keputusan policy:** safe logout memisahkan auth termination/purge dan memberi privacy/backup policy jelas; temporary template denylist beserta blocked/no-retry-loop semantics; strict snapshot completeness/coherence guard dan conservative reconciliation; D8 cache-known/completeness-unknown contract. Jalur pemulihan template atomic replacement harus dirancang sebagai protocol/backend work terpisah dengan child identities, revisions, pruning, concurrency dan authorized E2E sebelum kemampuan upload dipulihkan.

**Tetap terbuka:** D2 retention/remove/backup policy, D6 intentional degradation dan restoration protocol. D3/D8 hanya konseptual. Tidak mengubah backend, migration/RLS/RPC atau memutuskan menghentikan template sync di B0 ini.

## 8. Preservation dan runtime non-change proof

- Phase A fingerprint diverifikasi **sebelum B0**:474 original paths, SHA-256 `da851411fd911ad35f789ff6c56874fbf1541835aec1609da6fadfb8e6e0f2ad`; tracked diff SHA-256 `353f8a785b095753302fbda806f56436da3bfe14d0bd45c239cb4036d011fd42`.
- Pre-B0 manifest menyimpan475 files karena mencakup report Phase A juga. New B0 files dipisahkan dari baseline manifest, bukan ikut hash scope lama.
- **Satu baseline file yang boleh berbeda:** `app/build.gradle.kts`, untuk test wiring B0. Hash working tree mentah karena itu **tidak diklaim identik** dengan Phase A. Setelah hanya B0 test-source/dependency/opt-in-runner blocks dikeluarkan secara in-memory, Gradle file baseline dapat direkonstruksi byte-for-byte dan projected Phase A fingerprint harus sama. Tidak melakukan revert file di disk.
- Seluruh baseline file lain, source produksi dan changes enam issue, schema/report/plan/test existing dipertahankan. Bukti comparison manifest saat finalisasi dipisahkan dari klaim installed app.
- **Final preservation PASS:**474 dari475 pre-B0 files byte-identik; hanya Gradle test wiring berbeda. Projected Phase A fingerprint sesudah mengeluarkan hanya additions B0 secara in-memory tetap `da851411fd911ad35f789ff6c56874fbf1541835aec1609da6fadfb8e6e0f2ad`. Current raw digest atas474 path scope Phase A adalah `7975355a4ac89e9d45ea03f186c7516f510cb82cd97ac89da018d77bd11dd6c4`, berbeda secara sah karena Gradle. `preservation.json` dan baseline manifest tersedia pada evidence host. Delapan file tambahan B0 dicatat terpisah pada §2.
- Final diagnostic demo application APK SHA-256 `e4c5bd06121fb306dfdc686e25e864ffa2e1f20d196cd09ae89340e5848ff45c`, sama dengan application APK pada laporan enam issue. Production diagnostic APK SHA-256 `466d768dee6044a327759726b23131e1c1384c365604a8f8868481d076d8ea56`. Test namespace `Lcom/diajarkoding/imfit/b0/` **absent** pada kedua application DEX, **present** pada kedua instrumentation APKs; ini dan unchanged production sources mendukung no-production-semantics-change boundary.
- **Final device preservation PASS:** normal installed `com.diajarkoding.imfit` SHA-256 `87e2ffc32dd4028c89c59294923a3d74121820c5ecc44aadeadb48d4f8def0b2` dan `.demo` `a1ffd03ba5dcf9521f4bc63baecdbcf9e840021fc0132240710d675c616b8bb9`, beserta installation paths, identik dengan snapshot awal. Airplane-mode, rotation, ui-night-mode dan animator-scale values yang dicatat juga identik. Empat diagnostic packages sudah di-uninstall secara spesifik sesudah final tests; normal app tidak di-uninstall/clear. `device-baseline.json`/`device-final.json` dibandingkan secara exact. Tidak mengklaim isi DB normal diperiksa byte-for-byte: normal app data tidak diakses/dihapus oleh fixtures.

## 9. B0 exit gate dan batas verifikasi

| Gate | Evidence / batas |
| --- | --- |
| Harness compilable relevant variants | Unit demo/production dan instrumentation builds; hasil final diringkas pada evidence final |
| Production/test-double boundary jelas | SDK-injected engine, real classes/Room, test-only runner/proxies; no production source refactor |
| Deterministic tanpa arbitrary sleep | Reached/release probes, typed event flow, bounded timeouts; old unrelated regression test sleeps bukan pola harness baru |
| Counters verified | Unit ledger invariants + actual HTTP endpoint counts/concurrency assertions |
| Room rollback testable | Actual nested importer+outer transaction rollback dan persisted reopen |
| Session transitions controlled | Fake epoch/events + memory SDK imports; real SDK callbacks bukan bagian gate yang dianggap lulus |
| Existing risks reproducible | B01–B14 mapping, counter/private-commit/observer/capture/ack/logout/snapshot/dispatch/worker evidence |
| Failures classified | Initial compile/fixture failures retained; unsafe results eksplisit, target safety failures tidak disembunyikan |
| No unauthorized production behavior change | Source/fingerprint reconciliation dan application APK/DEX evidence; no policy/schema/backend/UI changes |
| B1/B2 repair list | §7; tidak ada implementasi lanjutan atau keputusan D2/D6 tersirat |

**B0 bukan release gate:** physical OEM, minified release, real Supabase/RLS/protocol, cloud backup/transfer, actual OS process death, actual SDK transition ordering, UI/Paging behavior dan privacy policy tetap NOT_TESTED/BLOCKED sesuai boundary. Production scheduler harness tidak membuktikan exactly-once remote writes atau seluruh queue recovery.

**Final B0 verdict:** exit gate fondasi characterization terbatas terpenuhi dengan evidence di atas dan batas klaim eksplisit. **Phase B1/B2 belum disetujui, dan target safe synchronization belum lulus.** D2/D6 tetap keputusan terbuka; tidak meminta/mengasumsikan implementasi lanjutan otomatis.

**STOP setelah B0. Tidak memulai B1/B2, Full Build fitur, commit, push, reset atau keputusan policy baru.**
