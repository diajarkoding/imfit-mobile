# IMFIT — Implementation Report (Satu Laporan yang Terus Diperbarui)

**ID:** IMFIT-RPS-STATUS-001  
**Terakhir direkonsiliasi:** 23 Oktober 2026 (SDK-AWAIT-API A implemented/tested proof-only; Samsung API31 **135 PASS**, proof JVM **121 PASS/1 SKIP** of122, normal app units **381 PASS**, package/source/provenance verified)  
**Overall status:** `PARTIAL` B1.1b: awaited API/job/retry/MFA/explicit-flow slices verified, full external-flow/startup/lifecycle matrix unfinished. Normal application, dependencies, Room and backup implementation remain unchanged. **Fitur UI 0/6 refresh, 0/2 infinite scroll, 0/6 shimmer baru yang terbukti selesai**  
**Next executable boundary:** finish external-flow provenance across remaining raw/high-level entrypoints and rapid lifecycle/recovery ordering; **no normal-app SDK-BIND**.

> **Cara pakai:** ini adalah **satu-satunya laporan status yang diperbarui** setiap slice. Jangan membuat laporan fase/ADR baru. Semua angka bertanggal dan dikaitkan dengan evidence; `PASS` historis tidak sama dengan fresh execution atau safety selesai. Edit checklist, register dan log di file ini; jangan menghapus riwayat gagal penting.

## 1. Dashboard progres

| Milestone | Status saat laporan ini dibuat | Bukti / celah |
|---|---|---|
| `PREP/A` | `DONE_VERIFIED` (historis) | Source audit/baseline dan Phase A selesai pada scope-nya, bukan release proof |
| `B0` | `DONE_VERIFIED` (harness saja) | Deterministic unsafe characterization harness tersedia; unsafe assertions tidak berarti safe-sync PASS |
| `Room 7→8 / durable aggregate revision` | `DONE_VERIFIED` pada isolated tests | Migration+triggers dan exact-ack integrated real SyncManager; S06 6/6 demo+production historis |
| `B1.0 SDK source audit` | `DONE_VERIFIED` (source-only) | Auth 3.0.3 sealed/internal mutation path audit; no public awaited fence terbukti |
| `B1.1 isolated Auth prototype` | `PARTIAL` (historical FAIL retained) | Original17/17 now PASS unchanged JVM + Samsung API31 + earlier Pixel_5 API36; full writer audit/SDK-BIND still absent |
| Pre-approval reconciliation 2026-10-23 | `FAIL_SAFETY` (pre-fix evidence) | Branch/HEAD sesuai anchor; SDK provenance PASS; targeted original recovery **0 PASS / 1 FAIL**, original assertion unchanged; diperbaiki pada proof run berikutnya |
| `B1.1a REC-AUTH ADR + G-STORE plan` | `DONE_VERIFIED` (desain saja) | 3 design documents, 578/578 baseline byte-identical; **new G-STORE tests NOT_RUN** |
| `B1.1a durable store implementation` | **`PARTIAL / BLOCKED_DECISION` (backup/normal recovery)** | Final JVM53 PASS/1 SKIP of54; Samsung API31 **61 PASS** including actual Android processes/self-kill recovery; backup characterization is not safety PASS. No new AVD required |
| `B1.1b` complete SDK writers | `PARTIAL` | SDK-AWAIT-API A approved and implemented; 30 prior writer +38 new awaited/job/MFA cases JVM/API31 and6 Android deep-link cases PASS. Full proof135 PASS API31,121 PASS/1 SKIP JVM; remaining raw/high-level external provenance, lifecycle ordering and expired recovery not certified |
| `B1.2` SDK→Room→SyncManager integration | `NOT_STARTED` | SDK-BIND, normal legacy/offline policy dan P0 proof dahulu |
| `B1.3` shared flight/Worker/outbox | `NOT_STARTED` | primitives saja, belum runtime wiring |
| `B1.4` full technical B1 | `NOT_STARTED` | Original account gate merah dan B0 mapping terbuka |
| `B2` D2/D6/privacy/snapshot | `BLOCKED_DECISION` + `NOT_STARTED` | D2/D6 perlu persetujuan; tidak diimplementasikan |
| `C` availability + 6 shimmer | `NOT_STARTED` | existing legacy shimmer bukan target renderer final |
| `D` Room Paging3/benchmark | `NOT_STARTED` | no current Paging implementation/81-150-1500 benchmark |
| `E` list/picker gesture+infinite scroll | `NOT_STARTED` | 2 target refresh+2 pagination belum |
| `F` Home/Progress/Profile/History refresh | `NOT_STARTED` | 4 target refresh belum |
| `G` full regression/E2E/release review | `NOT_STARTED` | Real Supabase E2E unauthorized/unavailable; device/release proof incomplete |

### Last executed tests (historis, dari laporan yang dilampirkan)

| Evidence snapshot | Result | Interpretasi / **bukan** bukti berikut |
|---|---|---|
| B1.1 isolated SDK final JVM | **16 PASS / 1 FAIL** dari 17 | Failed original ambiguous persistence recovery test; unbound prototype |
| B1.1 isolated SDK API31 Samsung | **16 PASS / 1 FAIL** dari 17 | Kegagalan yang sama; diagnostic APK saja |
| App unit demo+production | **194+187 = 381 PASS / 0 FAIL** | APK masih memakai upstream Auth 3.0.3, bukan patched SDK; tidak menguji REC-AUTH adapter baru |
| Room8/revision/S06 per variant | **27 PASS demo + 27 PASS production** | Total 54, termasuk desired S06 3/3 per variant; historical sebelum fork binding |
| B1 Room8 instrumentation aggregate | **129 PASS / 8 FAIL** dari 137 | 6 FAIL = tiga B0 outdated unsafe expectations × dua variant; 2 FAIL = genuine desired SDK account ABA gate |
| Desired real SyncManager SDK ABA gate | **0 PASS / 2 FAIL** | `oldUploadMustNotAcknowledgeAfterSdkAccountABATransition` tetap gagal pada demo/production historical integration |
| Terakhir B1.1a design-only | **NO RUNTIME TEST RUN** | 578/578 source preserved; 20/20 Room8 unchanged; hanya docs audit/decision/test matrix |

### Fresh execution — 23 Oktober 2026

| Pemeriksaan | Hasil aktual | Boundary |
|---|---|---|
| SDK upstream provenance | **PASS**, 58 imported upstream files, 56 byte-identical, 2 expected patches; 1 added authority source | Pinned archive SHA-256 verified; bukan dependency/runtime binding proof |
| Original `SdkPersistenceRecoveryGateTest` (JVM, isolated proof module) | **0 PASS / 1 FAIL / 0 ERROR / 0 SKIPPED** | Original ambiguous persistence assertion masih gagal setelah fresh client recreation; synthetic credentials + MockEngine `.invalid`, bukan OS death/device atau real backend |
| Workspace preservation sebelum edit laporan ini | **566/566 files byte-identical** terhadap inventory awal sesi | Termasuk existing dirty/untracked source/schema/tests; bukan klaim ekuivalensi terhadap manifest historis 578/20 file yang belum direvalidasi |

Pada **slice pre-approval ini**, app unit demo/production, full SDK suite, instrumentation/API24/31/34, G-STORE baru, dependency graph aktual dan release build belum diuji. Eksekusi pasca-approval di bawah memperbarui sebagian status tersebut; targeted run ini sendiri tidak mengganti angka historis.

### Approved proof implementation — intermediate execution, 23 Oktober 2026

Approval A sudah dicatat di seksi 4. Hasil sebelum review shutdown/final device-build gate:

| Suite | Fresh result | Scope/limitation |
|---|---|---|
| Auth proof JVM (`run-03`) | **48 PASS / 0 FAIL / 0 ERROR / 1 SKIPPED**, 49 total | Original 17 cases seluruhnya PASS, termasuk original red recovery; 25 store protocol + 6 SDK recovery + 1 actual two-process CAS. Directory sync protocol disimulasikan secara eksplisit; actual host-directory durability test SKIPPED di Windows |
| App demo JVM (`run-03`) | **194 PASS / 0 FAIL / 0 ERROR / 0 SKIPPED** | Normal upstream Auth, bukan SDK-BIND proof |
| App production JVM (`run-03`) | **187 PASS / 0 FAIL / 0 ERROR / 0 SKIPPED** | Normal upstream Auth, bukan SDK-BIND proof |
| SDK source provenance | **PASS**, 58 upstream = 56 unchanged + 2 expected patched | 4 proof additions allowlisted; no additional upstream file patched |
| Python tooling syntax | **PASS** | `python -m py_compile tools/verify_auth_fence_provenance.py tools/run_auth_fence_proof_device.py` |

Raw evidence `build/evidence/auth-store-proof-20261023/run-03/{sdk,demo,production}/TEST-*.xml`, `gradle.log`, and `test-summary.json` holds per-suite counts, timestamps and SHA-256. All **380 existing app files** and all three original SDK test sources remain byte-identical to initial inventory. No device action beyond read-only enumeration/API query: Samsung `SM_A315G`, serial `RR8NC03E76A`, API31 available. Installing/running diagnostic APK has not been authorized by option A and has not been performed. The original failed pre-fix XML remains in `build/evidence/core-reconcile-20261023/`.

### Latest executed results — B1.1a final local slice, 23 Oktober 2026

| Verification | Latest result | Exact boundary |
|---|---|---|
| Proof JVM `run-05` | **50 PASS / 0 FAIL / 0 ERROR / 1 SKIPPED**, 51 total | Original 17 tests unchanged and PASS; protocol/failpoints, exact receipt/recreation, cancellation+shutdown, ten concurrent handles and two actual child JVMs. One strict platform durability test SKIPPED: Windows directory fsync unsupported |
| App JVM `run-04` | **194 demo + 187 production = 381 PASS**, 0 fail/error/skip | Both test tasks explicitly rerun; application still uses upstream SDK. Not real SDK↔Room account-isolation proof |
| Diagnostic Android build | **PASS**, `assembleDebugAndroidTest` | Compiled Android53-case suite; compilation is not instrumentation PASS |
| APK package + target | **PASS**, both `com.diajarkoding.imfit.sdkfenceproof.test` | aapt2 badging and manifest read; APK SHA-256 `a8404356267ca38e7c22e3fbff5363ce9031ad69cb7c6de018d86c29956d07ba` |
| Actual app runtime dependency graphs | **PASS** for unchanged dependency isolation | demoDebugRuntimeClasspath + productionDebugRuntimeClasspath resolve upstream `auth-kt:3.0.3` / `auth-kt-android-debug:3.0.3`, no proof substitution |
| Final SDK provenance + tooling syntax | **PASS** | 56/58 upstream files byte-identical; only Auth.kt/AuthImpl.kt upstream patches, 4 allowlisted additions; Python compile checks PASS |
| Device instrumentation/API24/31/34, Android cross-process, OS kill, power-loss, DB-only/metadata-only backup | **NOT_TESTED / BLOCKED_DECISION** | API31 physical device available but diagnostic install/run/cleanup permission pending; API24/34 not inventoried; no power-loss or complete rollback claims |

The directory-sync simulation exists **only in test fixtures**, explicitly named `ProtocolTestFiles`; the default Android adapter uses actual `Os.rename`, file fd.sync, directory fd.fsync with fstat/S_ISDIR. Unsupported host sync returns unavailable and cannot publish; it is not replaced by a production no-op. Actual Android-specific tests are compiled separately and still NOT_RUN. `close()` now drains the authority fence in NonCancellable cleanup, invalidates eligibility, rejects delayed restore/new imports, and retains durable bytes; both ordinary and cancelled-caller shutdown tested locally.

### Latest emulator execution — Pixel_5 API36, 23 Oktober 2026

| Verification | Fresh result | Boundary/evidence |
|---|---|---|
| Target allowlist/identity | **PASS** | Explicit `-s emulator-5554` for every targeted adb command; `emu avd name` = Pixel_5, `ro.kernel.qemu` =1, API36. No Samsung-directed command after user's restriction |
| Emulator-only runner guards | **7 PASS / 0 FAIL / 0 ERROR / 0 SKIP** | Synthetic serial/identity tests; physical serial rejected before **any subprocess**; missing serial/wrong AVD/qemu0 rejected. Log `pixel5-preflight/emulator-guard-tests.log` |
| Actual instrumentation | **53 PASS / 0 FAIL / 0 ERROR / 0 SKIPPED**, no infrastructure failure | Original17 + protocol25 + SDK recovery8 + Android-specific actual filesystem3; Pixel_5 API36, model sdk_gphone64_x86_64, diagnostic APK only. Not API31 evidence |
| Actual filesystem recovery | **PASS on API36 within tested boundary** | `actualPlatformDirectorySyncAndFreshSdkRecovery` ran, not skipped; Android default adapter fresh SDK recovery/no-resave, actual prepare+commit failpoints and concurrent CAS PASS. ProtocolTestFiles still explicitly simulates directory sync in protocol-only cases |
| Package preservation/cleanup | **PASS** | Before/after exactly `com.diajarkoding.imfit.demo` + `com.diajarkoding.imfit`; newly installed diagnostic package removed. No normal app clear/update/uninstall or backend operation |
| Remaining platform gates | **NOT_TESTED / BLOCKED_ENVIRONMENT** | No API31 AVD currently exists; only Pixel_5/API36 and Medium_Tablet_API_33 AVD configs found. API31 google_apis/x86_64 system-image revision12 already installed, but creating/booting a new AVD is not inferred from existing-emulator approval. Android two-process/OS-kill/power-loss/backup gates not established by these53 tests |

Raw evidence `build/evidence/auth-store-proof-20261023/pixel5-api36-run01/`: `sdk-proof-device-results.json`, `sdk-proof-device.json`, `sdk-proof-instrumentation.log`, verified APK manifest, before/after package inventories. **Raw file** instrumentation log SHA-256 **`71c807ed8f398b295952c14ccb591143d65afb6bfa418e5456002560d226c5c5`**; normalized LF text SHA-256 **`b2dea8c8dae940cbc89cdbfd63254aa71126825c74d4fc5984f263105dbd5dd2`** (run01's original JSON `log_sha256` field records this normalized-text value). Windows text writing produced CRLF; raw evidence is preserved, and runner bookkeeping now records both digests explicitly on future runs. Tested APK SHA-256 remains **`a8404356267ca38e7c22e3fbff5363ce9031ad69cb7c6de018d86c29956d07ba`**. Prior local-only/NOT_RUN statements above describe earlier dated slices and are superseded only for the specific API36 paths executed here. No P0 assertion failed; full B1.1a exit remains unchecked rather than bypassing required API31 evidence.

### Samsung API31 approved diagnostic — intermediate slice, 23 Oktober 2026

- User explicitly revoked the physical-device prohibition for diagnostic scope: **“guanakan samsung sebagai API31”**. No extra AVD was created; existing Pixel_5 left unchanged.
- Identity verified: serial `RR8NC03E76A`, raw model `SM-A315G`, API31, qemu0. Runner still rejects physical targets by default; physical opt-in requires exact serial+model+API and non-emulated identity before any install. Guard tests **15 PASS**, log `samsung-preflight/target-guard-tests.log`.
- First `samsung-api31-run01` stopped before install because `SM_A315G` from adb display differed from raw getprop `SM-A315G`. Preserved ledger contains only3 getprop calls; **0 test executions**, not a safety assertion failure. Correct raw value verified read-only, no guard weakened.
- `samsung-api31-run02`: **53 PASS / 0 FAIL / 0 ERROR / 0 SKIPPED**, no infrastructure failure; original recovery assertion PASS unchanged and actual Android adapter recovery/failpoints/CAS PASS. Package inventory before/after identical; diagnostic uninstalled, no normal app operation.
- Raw evidence under `build/evidence/auth-store-proof-20261023/samsung-api31-run02/`, including new command ledger with every explicit target. Raw log SHA-256 `5f6484b332651f7b19e3a32ec5ccd94faa74005a17289368e82eb9834eef22b3`; normalized text SHA-256 `43262a74a57b66919195446f1f4986173747044ccc9e67896e9dbc73bdfb05d9`; APK SHA-256 `a8404356267ca38e7c22e3fbff5363ce9031ad69cb7c6de018d86c29956d07ba`.
- Continuing authorized proof-only work: private non-exported services in diagnostic APK's two separate processes; deterministic Binder barriers, two-process CAS, cancellation of cross-process lock wait, and **self-SIGKILL of diagnostic child only** after PREPARED/COMMITTED/while holding OS lock. No shell kill/force-stop of a normal app, device reboot/power loss, global settings change or real backend access. These new cases are **NOT_TESTED until expanded APK execution**, not retroactively part of the53-pass run.

### Expanded actual Android process proof — Samsung API31, 23 Oktober 2026

- `run-06` compiled the expanded diagnostic manifest/services and reran proof JVM plus app demo/production units. `samsung-api31-run03-process` then executed **58 PASS / 0 FAIL / 0 ERROR / 0 SKIPPED**, including **5 new actual Android process tests**. Newly installed diagnostic removed afterward; before/after IMFIT package inventories are both empty on this Samsung (no normal IMFIT app installed at this check).
- Actual process oracles: two distinct service PIDs differ from instrumentation PID and each other, same expected-head CAS yields one winner; self-SIGKILL after PREPARED cannot recover B or fallback A, new service incarnation differs; self-SIGKILL after COMMITTED allows fresh SDK exact recovery/no-resave; kernel releases dead process OS lock; cross-process lock wait cancels while holder remains alive and does not leak lock. No device reboot, full-main-process death, power-loss or normal-app privacy proof is inferred.
- Test-only services are `exported=false`, run under diagnostic UID in `:storeproof_a`/`:storeproof_b`, and validate directory is this test's UUID cache sandbox plus caller PID differs before any self-kill. Source changes outside tests: one additional **internal** `WAITING_FILE_LOCK` deterministic failpoint callback in the isolated proof adapter, no algorithm/dependency/normal-app change.
- Raw run03 log SHA-256 **`240d7c6708adcdd8d58e4b2f0734425f0c9ef9c309d85b0064e35ed2f01d9bf8`**, APK SHA-256 **`9bb0f7c2ae4425cacaaa64c9a8ce340ac795c660be91085ba031a93e62aafcd3`**. Source provenance checker still verifies 56 identical upstream +2 expected patches +4 allowlisted additions.
- Remaining G-STORE10 boundary is not solved by a local receipt: standalone store has no trusted Room/context input. Additional synthetic witness/backup fixtures (metadata-only/session-only exact-ticket conflict, external DB-only rollback limitation) are being tested in `StoreBackupBoundaryTest`; **actual Room restore is not exercised and no normal-app backup policy is approved**.

### Latest verified slice — Samsung API31 final + backup boundary, 23 Oktober 2026

| Check | Result | Exact scope |
|---|---|---|
| Final Samsung diagnostic `samsung-api31-run04-final` | **61 PASS / 0 FAIL / 0 ERROR / 0 SKIP** | Original17 + store protocol25 + SDK recovery8 + synthetic backup3 + actual Android filesystem3 + actual Android process5. **Includes2 limitation characterizations, not2 extra safety PASS** |
| Final isolated proof JVM `run-07/sdk` | **53 PASS / 0 FAIL / 0 ERROR / 1 SKIP**, 54 total | Original tests unchanged; one strict directory-fsync case unavailable on Windows. Corresponding actual Android API31 case executes and passes |
| Final normal app units `run-07` | **194 demo +187 production =381 PASS**, 0 fail/error/skip | Tasks explicitly rerun on unchanged app source/upstream Auth; not SDK-BIND/account-isolation integration proof |
| SDK source provenance/tooling | **PASS** | 56 upstream-identical +2 expected patches +4 allowlisted additions; Python tooling syntax PASS, target-guard tests15 PASS |
| Package preservation | **PASS** | Every successful Samsung run installs without `-r`, removes only new diagnostic, before/after IMFIT package inventories identical and empty on Samsung. No normal app was installed/cleared/stopped/uninstalled |
| G-STORE10 exact witness proof | **PASS within synthetic fixture** | Metadata-only/session-only rollback rejected by `resolve(exactTicket)` **if independent current witness exists**; not actual Room/backup engine validation |
| G-STORE10 missing external context/full rollback | **CHARACTERIZED LIMITATION / BLOCKED_DECISION** | Standalone receipt still verifies B while a separate synthetic database marker says A; full coherent rollback to A is also undetectable. These2 passing characterizations are explicitly **not** app safety PASS |

Final APK SHA-256 **`3abd3224fc7798163e84fa9b6989af3267d176d3d9911962586690b1b5b6c75b`**; raw instrumentation log SHA-256 **`260ea60e9f5e7c420da1fee5ae711eeb39f260bb5a8d70f3dd5c373de04b0801`**, normalized LF text **`b78d39266ae68086e458d1d4686671a022c53c4e8f16557026dff1364299373e`**. Raw logs, exact targeted command ledger, aapt2 manifest/package verification, test statuses and before/after inventories are in `build/evidence/auth-store-proof-20261023/samsung-api31-run04-final/`. `samsung-final-test-summary.json` maps final JUnit XML paths/counts/hashes. `samsung-preservation-final.json` records protected source/app/original test hashes and deltas. Earlier raw failures and runs are preserved, not overwritten.

No device reboot/power loss, full main-process kill, live Room restore/downgrade, real Supabase E2E or normal backup change occurred. Actual child process self-SIGKILL is proven by Binder disconnection, different process PIDs/service incarnation and successful fresh-handle/SDK oracle; it is not merely object recreation. API24/API34 and release/privacy gates remain untested. Safe source-only writer audit found remaining `_sessionStatus`/sessionJob/storage writers in AuthImpl, but no B1.1b writer changes or dependent gate bypass were made.

### Latest B1.1b execution — approved backup contract + SDK suspend-writer slices

User approved **D2-BACKUP A** via “A lanjutkan”. The approved contract is now reflected in spec/plan; **normal backup XML/Auth DI/Room code remains unchanged**. This closes the policy choice, not the normal-user restore implementation or full D2/SDK-BIND gates.

| Verification | Actual latest result | Scope/limitation |
|---|---|---|
| New `SdkWriterFenceTest` | **30 PASS JVM +30 PASS Samsung API31** | Login/signup/anonymous/3 OTP variants, profile lookup/update/unlink/logout+ABA, local commit ordering, wrong-owner replies, actual delayed Authorization/prepared request, actual session_missing cleanup with positive+foreign-JWT controls, MFA verification/composite challenge, PKCE exchange/conditional verifier consumption |
| Full isolated proof `auth-writer-proof-20261023/run-04-controls-fixed` | **83 PASS / 0 FAIL / 0 ERROR /1 SKIP**,84 total | Original tests remain byte-identical. Windows directory-fsync case remains SKIPPED; actual Android case runs and passes |
| Samsung API31 `samsung-api31-slice03-controls` | **91 PASS /0 FAIL /0 ERROR /0 SKIP**, no infrastructure failure | Earlier61 proof cases +30 new cases. Still includes2 explicitly classified backup/rollback limitation characterizations, **not** blanket G-SDK/app safety PASS |
| Normal app units `app-regression-01` | **194 demo +187 production =381 PASS**,0 fail/error/skip | Explicit rerun using normal upstream SDK, no proof substitution; normal app source unchanged |
| Proof source provenance | **PASS**,54/58 upstream byte-identical +4 exact allowlisted patches | Auth.kt existing proof API, AuthImpl.kt, AuthenticatedSupabaseApi.kt and mfa/MfaApi.kt;4 existing proof additions. Pinned archive checksum/license unchanged |
| Diagnostic cleanup | **PASS** | Before/after normal IMFIT package inventory identical (empty on Samsung); only newly installed diagnostic removed. No AVD, normal app, device settings/reboot, real credentials/backend or release operation |

**Implemented:** SDK call context captures exact operation+immutable session before the tested async work; actual AuthenticatedSupabaseApi raw/prepared requests use captured credential and request attributes, not successor credential. Profile updates and logout publication/persistence use the same authority. `session_not_found` cleanup is awaited and uses the originating request's operation **only if its actual Authorization matches that operation's session**; stale replies cannot clear a successor. Successful logout does not double-clear when its own missing-session response already cleared the exact operation. MFA verification captures before request, composite captures before challenge creation, and rejects another owner's session. PKCE exchange captures before load/network; verifier consumption is conditional and under the same authority before publication or result delivery. These do not prove PKCE generation/external-flow lifecycle or all MFA operations safe.

**Actual header oracle:** a user-update payload built before account switch is rejected before HTTP when its operation is already stale; a request paused inside actual Ktor send pipeline retains A's captured Authorization after switch to B (**0 A-as-B**); a prepared SDK request likewise retains the captured header. This is rebuilt SDK+MockEngine evidence, not real SyncManager/Worker HTTP pairing or SDK→Room binding proof. Logs record synthetic owner labels, not real access/refresh tokens.

First slice (`run-01`, `samsung-api31-slice01`) had20 new cases and **81/81 device PASS**; MFA/PKCE slice added8 cases and **89/89 device PASS**; final positive error controls added2 cases. Earlier typed session_missing claims are qualified by the fixture correction below. First-device raw log hash `4659f7f269830d6b7a9e1e906139860647ca2dcb0b4689fd0d9f827603ed087b`, first APK `60dffde30e0f5c74689c940f077dad0d9a58d58388ab615efdacf831caaf7d18`; intermediate89-case raw log `8b9cbed3b4959ff38745650ecee05b2da34ffca40469ddd96d09e51f6722175e`, APK `717417d472febfa66fd9755ab75a54b9e0151c19a5da28554e31a902c954aa6d`. **Final91-case** raw log SHA-256 **`50c9db743d11d5df4919377e893bcdb5c9f2c0792eff27f2affd6c62c17be809`**, normalized text **`b72fe1c8ec7114a07d5c49895979e4a784942609a5d4b2d93f132a879964f69c`**, APK **`6bb5255ce43c0cf184f36a4cef748f81ef46a55ddf451cfb35a0d9d436d94107`**. Raw ledger/manifest/statuses/inventories under `build/evidence/auth-writer-proof-20261023/samsung-api31-slice03-controls/`; per-suite XML/counts/hashes in `slice03-test-summary.json`, final source/artifact preservation `slice03-preservation.json`.

**Coverage failure preserved and corrected, no assertion weakened:** adding a positive missing-session lookup control in `run-03-controls` produced **82 PASS /1 FAIL /1 SKIP** of84; desired cleanup assertion expected1 deletion, observed0. Pinned SDK `GoTrueErrorResponse.kt:36` reads `error_code`, while the new fixture mistakenly sent `code`. Earlier81/89-case successes therefore only established generic401 behavior for those mislabeled error cases, **not** the typed session_missing branch. Corrected all4 fixture payloads to the pinned wire field without changing any assertion or SDK algorithm, and retained the failed XML/log. `run-04-controls-fixed` and final API31 run prove the actual typed branch: current lookup awaits cleanup/invalidates the old lease, stale reply cannot clear B, explicit other-JWT reply cannot clear current A, and own logout clears exactly once. Original3 SDK tests remain byte-identical.

**Historical remaining boundary at91 tests:** public synchronous stop-refresh/getOAuthUrl/deep-link entrypoints required the API decision in section4.4. The following approved slice supersedes that compatibility blocker and the old raw-fragment parser, but does not imply full G-SDK/SDK-BIND PASS.

### Latest SDK-AWAIT-API execution — proof-only, 23 Oktober 2026

User explicitly approved option A: **“A — direkomendasikan: izinkan API tertentu menjadi bisa di-await, hanya pada fork proof, agar melewati satu fence keamanan”**. No additional administrative approval was requested or inferred for these proof-only API/internal caller/test changes.

| Verification | Latest executed result | Exact boundary |
|---|---|---|
| `SdkAwaitedWriterTest` | **38 PASS JVM +38 PASS Samsung API31** | Awaited stop/start, same-fence commit ordering, automatic/manual/expired-import refresh, cancellation, original-session retry/late error, persistence-failure no-retry, owner mismatch, profile task rebind; stale lifecycle helpers; PKCE/implicit explicit flow/state/replay/foreign-client guards; enrollment/challenge/unenroll and ancillary stale result delivery |
| `AndroidAwaitedDeepLinkTest` | **6 PASS Samsung API31** | Actual Android Intent/Uri parsing, awaited PKCE and implicit import-before-callback, delayed callback vs B, duplicate/missing state and wrong scheme/host. No real browser/provider/backend |
| Proof JVM `run-03/sdk` | **121 PASS /0 FAIL /0 ERROR /1 SKIP**,122 total | Original3 test sources and all30 previous writer assertions unchanged. Strict Windows directory-fsync remains the only skip; actual API31 test runs |
| Samsung `samsung-api31-run01` | **135 PASS /0 FAIL /0 ERROR /0 SKIP**, no infrastructure failure | Previous91 +38 shared +6 Android cases; still includes2 backup limitation characterizations, not2 additional app-safety PASS |
| App `app-regression-01` | **194 demo +187 production =381 PASS**,0 fail/error/skip | Explicit rerun, normal upstream SDK unchanged, not SDK↔Room isolation proof |
| Provenance/preservation | **PASS**,51/58 upstream-identical +7 exact allowlisted patches,4 existing additions | Added Utils.kt, Android.kt and setupPlatform.kt to existing exact patch allowlist. All380 app files, settings/build configuration, original3 SDK tests, previous writer tests, pinned archive/license and spec/plan unchanged |
| Diagnostic package lifecycle | **PASS** | Exact authorized Samsung RR8NC03E76A/SM-A315G/API31/qemu0; install without replacement; newly installed diagnostic removed. Before/after normal IMFIT package inventory identical/empty |

**Implementation:** public stop-refresh and OAuth URL preparation suspend; implicit parser and Android deep-link handler suspend and require an explicitly retained initiation handle. No coroutine-launched session import, raw-fragment log, callback-before-import, callback lookup of the current successor, or guessing the latest flow. URL return awaits verifier persistence; each flow prepares once. State mismatch/duplicate, replaced flow, foreign authority, old generation and replay cannot publish/consume/deliver. Callback runs after persistence+publication under final same-context validation and receives the actual published session, including renewed session if expiry required it. The caller-provided callback is synchronous bounded delivery, not a place to reenter the authority or block on network.

Job controls use the shared awaited authority, advance operation generation while retaining same-identity local leases, and refresh jobs receive the post-publication ticket. Retry retains its original session/ticket; stale/cancelled tasks never recapture a successor. Network/server retry handling excludes local persistence/publication failures. Refresh publication does not cancel its own in-progress commit; user updates replace stale scheduled work with a task bound to the new generation. Framework callbacks synchronously capture an opaque operation snapshot before launching awaited validation; this is not a StateFlow collector authorizing a write.

**Explicit remaining limits / not certified:** the raw `exchangeCodeForSession(code, saveSession)` entrypoint still captures at invocation rather than proving initiation and may read a cache without an opaque flow; it is **not** a certified external callback path. High-level OAuth/SSO/link/email-recovery initiation does not yet uniformly expose/transport/retain the handle. Real Supabase/provider state echo is unverified; synthetic fixtures cannot establish it. Process-recreated/missing handle has no automatic reconstruction/fallback. Expired durable recovery currently returns false without refreshing or deleting bytes; this is not complete expired recovery support. Real ProcessLifecycleOwner onStop/onStart overlap, operation-generation invalidation during an external browser round trip, rapid-event liveness, remaining startup/restore interleavings and full remaining writer audit are not certified by helper tests. **Do not enable/bind this fork in the normal app or claim full G-SDK/S03/S04 PASS.** No normal backup exclusions, legacy/offline/logout policy, SDK-BIND, backend or release change occurred.

Raw evidence: `build/evidence/auth-awaited-proof-20261023/`; `run-01/gradle.log` preserves a compile-only failure caused by inferred AuthOperation return types on Unit APIs (0 tests, corrected by explicit Unit); `run-02`108 executions =107 PASS/1 SKIP; `run-03`122 executions =121 PASS/1 SKIP. No desired test failed and no assertion was weakened. Final raw device log SHA-256 **`d540ebc218ae0e9f6df339de1762c32ff06c7f1b277992023c228a50202645c9`**; APK **`f4a1e88534feedf1cde8fd1450513f05e36ac44e919381acf0bc1671421b6c73`** (`tested-run03.apk`). Per-suite raw XML/digests/counts are in `slice01-test-summary.json`; protected-source hashes/deltas in `slice01-preservation.json`; full explicit-target command ledger, manifests, package inventories and results in `samsung-api31-run01/`.

**Konvensi:** `DONE_VERIFIED` hanya dengan bukti dan scope jelas; `PARTIAL`, `FAIL_SAFETY`, `NOT_STARTED`, `NOT_TESTED`, `BLOCKED_DECISION`, `BLOCKED_ENVIRONMENT` berbeda. B0 unsafe reproductions lulus = karakterisasi berfungsi, bukan safety invariant PASS.

## 2. Checklist implementasi master (ubah `[ ]` menjadi `[x]` hanya bila gate terkait terbukti)

### A/B0/B1 fondasi
- [x] Audit source baseline/Phase A selesai pada boundary historis.
- [x] B0 deterministic characterization harness dan bukti unsafe baseline tersedia.
- [x] Room7→8 additive migration, persistent aggregate revision, snapshot capture/exact ack wired ke real SyncManager; original S06 lulus pada pengujian historis.
- [x] B1.0 SDK source audit dan minimum isolated fork feasibility proof dibuat.
- [x] B1.1a REC-AUTH ADR, G-STORE01–10 planned matrix, dan desain review dibuat; original red assertion tetap utuh.
- [x] **Decide** REC-AUTH-PROOF, REC-AUTH-COMMIT, LEGACY-AUTH-PROOF secara eksplisit (2026-10-23, user: “A: setujui dan lanjutkan”).
- [x] Implement actual filesystem-backed AtomicSessionStore/envelope/CAS/receipt **proof-only** (Android adapter compiled; JVM protocol verified; platform acceptance remains unchecked below).
- [ ] G-STORE01–10, original red recovery & positive committed restore PASS dengan environment/limitations jelas.
- [ ] B1.1b seluruh SDK writer/expiry/PKCE/MFA/refresh/restore/error/credential headers PASS.
- [ ] **Decide** LEGACY-AUTH-NORMAL, SDK-BIND, backup/restore/downgrade compatibility sebelum replacement aplikasi.
- [ ] B1.2 patched SDK/Auth authority/Room/SyncManager/observers terintegrasi, real desired SDK ABA gate **2/2 PASS**, current S06 **6/6 PASS**.
- [ ] B1.3 shared read-flight coordinator, typed outcomes, worker wakeup/cancel/retry/pending safety PASS.
- [ ] B1.4 full technical safety regressions S01–S06/S09/S10 PASS dan B0 unsafe-case mapping ke positif terdokumentasi.

### Full safety Phase B2
- [ ] D2 pending-safe logout + privacy/backup/retention/offline exit keputusan APPROVED dan tes PASS.
- [ ] D6 template destructive multi-request dispatch policy APPROVED, protected before first request, pending preserved, typed blocked, no retry storm.
- [ ] Remote unverified snapshot/complete graph fail-safe (S07/S08), coverage marker unknown/verified-empty/D8 dan D3 scope overlay safety PASS.
- [ ] Full Phase B gate S01–S10/S12 PASS dalam scope yang dapat dibuktikan; server guarantees tersisa diberi remote gate tersendiri.

### Enam fitur dan Phase G
- [ ] C: Home skeleton sesuai section/shape/cache.
- [ ] C: Exercise List skeleton sesuai row/deskripsi.
- [ ] C: Exercise Picker skeleton dengan checkbox noninteractive.
- [ ] C: Progress skeleton 2 existing cards/calendar/selected date.
- [ ] C: Profile skeleton avatar72/read-only fields.
- [ ] C: History-by-date skeleton 4 total harian/session summary.
- [ ] D: Room Paging3 production+demo, SQL full filter/count/resolution/sort parity.
- [ ] D: benchmark 81/150/1500 accepted; S11 0 remote append/retry.
- [ ] E: Exercise List Pull-to-Refresh + Infinite Scroll PASS.
- [ ] E: Exercise Picker Pull-to-Refresh + Infinite Scroll + selection/result guards PASS.
- [ ] F: Home Pull-to-Refresh PASS.
- [ ] F: Progress Pull-to-Refresh PASS.
- [ ] F: Profile Pull-to-Refresh PASS.
- [ ] F: History-by-date Pull-to-Refresh PASS.
- [ ] G: unit/instrumentation, Compose/visual, real device/process-death, migration/release/minified regression PASS.
- [ ] G: authorized disposable Supabase A/B E2E/RLS/protocol PASS.
- [ ] G: privacy/legacy/backup and full visual sign-off PASS, release-ready verdict recorded.

**Feature delivery progress:** Pull-to-Refresh **0/6 verified** · Infinite Scroll **0/2 verified** · Content-aware shimmer **0/6 verified**. Pre-existing reactive Room/LazyColumn/legacy shimmer tidak dihitung sebagai implementasi target baru.

## 3. Bottleneck / blocker registry — prioritas sekarang

| ID | Priority / type | Status | Masalah dan bukti | Penyelesaian / gate |
|---|---|---|---|---|
| `BLK-01` | P0 / DECISION | **CLOSED 2026-10-23** | User memilih “A: setujui dan lanjutkan” untuk tiga keputusan proof-only | Implement contract seksi 4.1; SDK-BIND/normal legacy tetap PENDING |
| `BLK-02` | P0 / SAFETY / RECOVERY | **PARTIAL / STORE+API31 FIX VERIFIED** | Original recovery assertion PASS unchanged; actual API31 filesystem/process/self-kill proof PASS. Final Android61 passes include2 limitation characterizations, not full backup safety | D2-BACKUP/normal recovery decision and actual integration gates remain; no SDK-BIND bypass |
| `BLK-03` | P0 / SAFETY | **OPEN** | Real `SyncAccountIsolationGateTest` gagal pada A→B→A (0/2); prototype belum di-bind | Full writer matrix, SDK-BIND+actual Room fence, 2/2 current tests PASS |
| `BLK-04` | P0 / DECISION | **OPEN** | Legacy raw record trust/offline restoration/downgrade/backup normal user semantics belum diputuskan | `LEGACY-AUTH-NORMAL`, `SDK-BIND` separate approval; rollback limitation reviewed |
| `BLK-05` | P0 / SAFETY | **OPEN** | `RefreshCoordinator` primitive saja; reconnect, full/master/Worker, typed outcomes, wakeup belum terintegrasi | B1.3 runtime integration + counters/liveness tests |
| `BLK-06` | P0 / DECISION | **OPEN** | D2 existing logout bisa hapus pending sebelum SDK signout sukses; privacy/backup & offline logout belum approved | D2 user decision, safe implementation, tests incl online/offline/shared device |
| `BLK-07` | P0 / DECISION | **OPEN** | D6 template CREATE/UPDATE remote parent→delete-all children→insert non-atomic; conservative snapshot policy perlu persetujuan | D6 explicit degraded/denylist protocol, block before request or separately approved backend work |
| `BLK-08` | P0 / SAFETY | **OPEN** | Remote snapshot completeness/parent-child coherence dan coverage unknown tidak boleh dianggap fully verified | B2 non-destructive apply, typed partial/unknown, S07/S08 tests |
| `BLK-09` | P1 / REGRESSION | **OPEN** | Tiga B0 historical expected-unsafe cases per variant sekarang FAIL karena fixes; jangan hide failures | B1.4 map each to positive regression, old evidence retained; genuine P0 failure tetap merah sampai fix |
| `BLK-10` | P1 / UX | **OPEN** | Fullscreen initial-sync overlay, local loading flicker/false empty, availability/shimmer belum diperbaiki | D3/D8 decision where needed, C+F behavior/Compose proofs |
| `BLK-11` | P2 / DECISION/TEST | **OPEN** | Paging dependency/ID sorting change and 81/150/1500 benchmarking belum selesai | D4/D5 benchmark/SQL parity; do not silently replace pager with full-list slice |
| `BLK-12` | P1 / ENV/DECISION | **OPEN** | Authorized disposable Supabase A/B, full device/backup/OS death, final mockup sign-off belum tersedia | Obtain scope/resources, run G E2E and device/visual gates or remain BLOCKED |
| `BLK-13` | P2 / PRODUCT BACKLOG | **OPEN / OUTSIDE CURRENT SCOPE** | `PROG-SEM-01` Weekly Time uses monthlyMinutes; `PROG-VIS-02` 2 shown vs 4 calculated metrics | Separate product/visual decision; preserve existing two cards until approved |
| `BLK-14` | P0 / DEVICE PERMISSION | **CLOSED for Pixel_5 API36 ONLY** | User approved diagnostic only on Pixel5 emulator and explicitly forbade Samsung; emulator53/53 PASS, diagnostic cleaned up, normal packages preserved | No permission for physical device inferred; no Samsung-directed test/cleanup |
| `BLK-15` | P0 / ENVIRONMENT + DECISION | **CLOSED 2026-10-23** | User chose Samsung API31 instead; exact device identity verified and diagnostic53/53 PASS | No new AVD needed or created; remaining process/backup evidence not waived |
| `BLK-16` | P0 / RECOVERY POLICY DECISION | **DECISION CLOSED 2026-10-23; IMPLEMENTATION DEFERRED** | User “A lanjutkan” approves fail-closed restore/transfer authentication policy. Receipt limitations remain real, not a new anti-rollback claim | Freeze contract; continue isolated SDK proof only. Normal backup exclusions/restore integration still NOT_IMPLEMENTED and separately gated |
| `BLK-17` | P0 / SDK API COMPATIBILITY DECISION | **CLOSED / APPROVED SLICE VERIFIED 2026-10-23** | Proof signatures/callers changed, raw fragment log removed;38 shared +6 Android new tests PASS, full135 API31 | Does not close remaining external-flow/lifecycle/recovery audit or SDK-BIND; no repeated question for this same compatibility approval |
| `BLK-18` | P0 / REMAINING SDK PROOF | **OPEN / TECHNICAL WORK** | Raw PKCE exchange/high-level handle provenance, external-browser generation/lifecycle roundtrip, rapid events, expired durable recovery and remaining startup/restore interleavings not certified; live state echo unverified | Continue approved proof-only corrective work/tests, no normal app substitution or blanket G-SDK PASS. Ask only if a genuinely new backend/policy/API authorization boundary arises |

**Immediate critical chain:** `BLK-18/B1.1b remaining flow/lifecycle/recovery proof → BLK-04/LEGACY-AUTH-NORMAL+SDK-BIND → BLK-03/05 → normal G-STORE09/10+D2/D6 → C/D/E/F/G`. SDK-AWAIT-API A and D2-BACKUP A approved; normal implementation still deferred.

## 4. Decision register — hanya user approval yang benar-benar diberikan

| Decision ID | Current authorization | Relevansi/opsi perlu user | Evidence of approval |
|---|---|---|---|
| Room7→8 additive, non-destructive | **APPROVED (historis); implemented** | No schema9 automatically | Earlier explicit approval for migration, reflected in Room8 report |
| SDK Auth3.0.3 isolated patch/prototype | **APPROVED limited proof (historis)** | No normal app substitution | B1.1 proof authorization shown in report |
| `REC-AUTH-PROOF` | **APPROVED 2026-10-23, proof-only** | Atomic filesystem store/envelope/CAS/receipt/failpoints, synthetic credentials; no normal app binding | User: “A: setujui dan lanjutkan”, answering joint option A |
| `REC-AUTH-COMMIT` | **APPROVED 2026-10-23, proof-only** | Exact durably verified COMMITTED receipt may recover despite caller exception; ambiguous/unreadable unavailable | Same explicit option A approval |
| `LEGACY-AUTH-PROOF` | **APPROVED 2026-10-23, proof-only** | Preserve raw bytes + typed unverified; no automatic trust/migration/delete/logout | Same explicit option A approval |
| `PROOF-DEVICE-31` (historical ID; actual approved API36) | **APPROVED 2026-10-23, Pixel_5 emulator ONLY** | Install/run/cleanup isolated diagnostic package only on verified Pixel_5; physical devices/Samsung `SM_A315G`/`RR8NC03E76A` forbidden. Emulator verified `emulator-5554`, AVD Pixel_5, qemu1, API36; API31 requirement not waived | User: “A: izinkan pengujian diagnostik (direkomendasikan) gunakan perangkat emulator pixel 5 jangan gunakan real device samsung SM A315G” |
| `PROOF-SAMSUNG-31` | **APPROVED 2026-10-23, diagnostic ONLY** | Supersedes previous Samsung prohibition only for isolated diagnostic install/run/cleanup, exact `RR8NC03E76A` + raw getprop model `SM-A315G` + API31 + qemu0 must match (`adb devices -l` renders model as SM_A315G). No normal app/data/backend/reboot/settings change | User: “guanakan samsung sebagai API31”, answering explicit question whether previous prohibition is revoked for this diagnostic |
| `PROOF-AVD-31` | **NOT NEEDED / NOT AUTHORIZED** | User chose Samsung API31 instead; do not create, boot, wipe or modify any additional AVD | Superseded next-action proposal, not approval to create AVD |
| `LEGACY-AUTH-NORMAL` | **PENDING** | Offline reauthentication/read/migration/downgrade behavior of real users | None confirmed |
| `SDK-BIND` | **PENDING** | Substitute fork into app DI/dependencies when G-STORE/G-SDK pass | None confirmed |
| `D2` logout/retention/privacy | **PENDING** | Safe online/offline exit, retention/discard, backup/device transfer rules | None confirmed |
| `D2-BACKUP` (subdecision, not all D2) | **APPROVED 2026-10-23, option A** | Exclude auth secrets/trust metadata from cloud/transfer; require fresh authentication after restore/transfer, preserve pending+owner isolation. Ordinary offline startup unchanged; no automatic normal-app implementation/SDK-BIND/all D2 approval | User: “A lanjutkan”, answering D2-BACKUP A/B question |
| `SDK-AWAIT-API` | **APPROVED 2026-10-23, option A, isolated fork ONLY** | Awaitable/suspending proof entrypoints + SDK-internal caller/test adaptation, one shared fence; source/binary compatibility change limited to proof artifact, no normal app substitution | User: “A — direkomendasikan: izinkan API tertentu menjadi bisa di-await, hanya pada fork proof, agar melewati satu fence keamanan” |
| `D6` template write/snapshot | **PENDING** | Fail-closed/degraded template policy or separately scoped atomic remote protocol | None confirmed |
| `D3/D8` scope overlay/coverage | **Conceptual recommendations; verify approval before behavior change** | Eligibility six contexts; unknown-first coverage, no new schema | Plan v2.1 recommendations not themselves runtime approval |
| `D4/D5` paging & picker sort | **Verify before choice** | Room Paging after benchmark, ID lexical order for picker | Existing source baseline and plan recommendation |
| `D9` visual references | **OPEN** | Approved mockups for six screens; source baseline where absent | No final 6-screen sign-off |
| `REMOTE-G` | **PENDING** | Disposable project + accounts A/B and authorized E2E fixture writes | No allowed test environment confirmed |
| `RELEASE` (commit/push only) | **APPROVED 2026-10-23, commit+push ONLY** | Track all current work in categorized commits without co-author, then push `fix-bug` to `origin`; excludes normal app install, backend mutation and deployment | User: “track semua perubahan buat comit sesuai kategori perubahan tanpa co author kemudian push ke remote” |

**How to record decisions:** On direct user approval, update only this row to `APPROVED [date, scope, user wording]`, note restrictions and reference. If ambiguous, keep `PENDING`. Do not regard a proposal drafted *by an assistant* as an approval *by the user*.

### 4.1 Frozen B1.1a proof contract — 2026-10-23

- Scope: only `sdk/auth-fence-proof` and its provenance/test tooling. Normal app dependencies, Room8/revisions, SDK-BIND and real-user legacy/backup/logout policies remain unchanged. Original tests/assertions remain intact.
- `AtomicSessionStore` is an explicit recovery capability, not inferred from `SessionManager` or a successful legacy save. Legacy `loadSession()` may be read for compatibility/stale-operation validation, but its value cannot publish an authenticated identity in this proof. No legacy bytes are changed by recovery.
- Format v1: bounded canonical binary encoding (UTF-8 length-prefixed strings, strict field order/no trailing bytes), maximum record 1 MiB and session JSON 512 KiB; UUID incarnation/operation, positive checked `Long` generation, predecessor SHA-256, phase PREPARED/COMMITTED, owner and canonical session JSON binding. A COMMITTED receipt is SHA-256 of the exact domain-separated record body; it is integrity/coherence evidence, **not** encryption/signature/token validity or complete rollback detection. Session absence is an explicit committed tombstone, never physical clear/delete.
- One record (`session.v1`) atomically carries both payload and receipt; stable `session.lock` is never deleted/replaced. Exact full-head CAS prevents ABA/late completion; prepare supersedes the previous recovery candidate, and PREPARED never falls back to earlier committed A. First initialization is allowed only from verified absence; invalid/legacy/future bytes are preserved and block writes.
- Adapter: private supplied proof directory, process-shared canonical-path lock plus OS file lock, unique same-directory stage, file sync → atomic replace → directory sync → bounded readback. Reads of COMMITTED must sync the observed file and directory under the lock before returning verified recovery, so an earlier write-then-throw can be resolved only if the exact receipt can now be durably verified. Unsupported sync/replace/lock capability or any unresolvable I/O returns typed unavailable; never silently use rename/copy or SharedPreferences as a durable substitute.
- Operations: `readVerified`/`readCommitted`, `prepare(expectedHead, operationId, session)`, `verifyPrepared`, `commitIfCurrent`, `resolve(exactTicket)`. Cancellation propagates, releases locks, and prevents memory publication; a completed durable commit is not rolled back or claimed absent on caller cancellation. No network inside storage/SDK fence; lock order SDK authority → storage only.
- Device/power-loss limits: JVM protocol/failpoint results do not prove Android durability. Windows directory-fsync limitations must be reported explicitly, not hidden by a no-op production adapter. Actual Android API31 and cross-process/OS death remain separate gated evidence; no diagnostic device operation is inferred from this approval.

### 4.2 Decision D2-BACKUP — option A approved 2026-10-23

**Verified finding:** `AtomicSessionStore` validates only its exact record/receipt; an independent current ticket detects metadata/session skew, but a standalone receipt cannot validate external Room/owner rollback or complete coherent rollback. Synthetic fixture evidence is not actual Room restore evidence. App source currently has `android:allowBackup="true"` in `app/src/main/AndroidManifest.xml:27`; `backup_rules.xml` has only commented sample rules, and `data_extraction_rules.xml` has an empty cloud-backup rules block with device-transfer sample commented. No targeted session/receipt exclusions or independently trusted restore policy are configured in these source files. This is source inspection, not a claim that any real credential backup/restore was performed.

**Option A — APPROVED, user “A lanjutkan”:** after detected restore/device transfer, do not automatically trust restored authentication sessions/receipts/identity eligibility. Exclude auth secrets and trust metadata from cloud backup/device transfer, require fresh authentication before account data access, and preserve owner-scoped workout/pending data without silent purge or cross-account publication. This policy is specifically for restore/transfer; it does **not** newly force reauthentication on ordinary offline startup or settle all legacy/offline/logout retention policy. Whether workout data itself should be cloud-backed-up/exported is still a separate privacy decision.

**Option B:** support authentication/offline account access from restored backups. Requires a separately reviewed trusted recovery mechanism outside the restored snapshot (or server verification when online), explicit rollback/privacy assumptions and new tests. Self-contained hash/clock/userId equality cannot establish that trust; do not implement this as a fallback.

**Boundary:** policy choice recorded, not automatically implemented. No SDK-BIND, live user storage migration, normal-app install, backend work, all D2/logout approval or release. Applicable B1.1a proof oracles (typed legacy preserved, exact receipt recovery, filesystem/cross-process/cancellation and explicit rollback limitations) already ran JVM+API31. Proceed to B1.1b proof-only; normal-user legacy/restore/backup integration remains a separate incomplete gate and may not be claimed PASS. Spec/plan now include the approved contract without runtime-success claims.

### 4.3 B1.1b first slice contract (proof-only)

- Capture SDK operation plus immutable credential/session **before** provider/login/user-update/logout/OTP network suspension. No network inside authority/storage fence. Async completion validates the same ticket before persistence/publication; old logout/error cannot clear a successor.
- Pin actual outgoing SDK Authorization to the captured operation; do not reread successor token after payload creation. Record no raw credentials. Error callbacks must carry their originating request ticket, not capture a fresh ticket inside an asynchronous clear.
- First slice may cover only awaiting suspend writers; full expiry, PKCE/MFA/deep-link/lifecycle and non-suspending job controls remain explicitly incomplete until their required hooks/compatibility are resolved and tested. Existing tests stay byte-identical, normal app/Room/dependencies untouched, provenance allowlist updated only for actual scoped SDK patches.

### 4.4 Decision SDK-AWAIT-API — option A approved 2026-10-23

**Verified compatibility boundary:** `Auth.stopAutoRefreshForCurrentSession()` and `Auth.getOAuthUrl(...)` are non-suspending public API. `Auth.parseFragmentAndImportSession(...)` and Android `SupabaseClient.handleDeeplinks(...)` are also non-suspending, launching work internally; internal `resetLoadingState()` directly publishes state. They cannot await the authority mutex while preserving their exact synchronous contract. PKCE generation currently asynchronously saves verifier state, and implicit parsing logs the raw fragment; neither is safe to certify as a complete writer/credential boundary. These paths are not newly exercised with real credentials and remain incomplete.

**A — APPROVED (proof-only):** permit explicitly awaited/suspending entrypoints in the isolated fork and adapt **SDK-internal** callers/tests, so operation/flow ticket capture, verifier publication, refresh-job changes, deep-link import and success delivery can be sequenced under the same authority. No UI runBlocking, fire-and-forget trust publication or independent app mutex. This is a source/binary compatibility change in the proof artifact, not normal app substitution. Normal callers such as `app/src/production/java/com/diajarkoding/imfit/data/auth/SupabaseAuthDeepLinkHandler.kt:18–20` are synchronous today and require a separately authorized integration decision before SDK-BIND.

**B:** preserve upstream synchronous signatures unchanged and pause these paths for a compatible redesign. Cannot mark shared-awaited-fence or full G-SDK PASS merely by launching another coroutine, rereading current owner at completion or using a second lock. No such workaround is selected automatically.

Approval recorded verbatim in register; do not repeatedly ask administrative permission for these proof changes. Frozen implementation contract: awaited job controls advance operation generation without reusing identity/credential tickets; refresh tasks capture a post-publication ticket, never recapture a successor in retry/error; external callbacks require an explicit opaque flow handle captured at initiation, matching active flow/epoch and one-shot consumption. No guessing current flow from a late callback. Missing/recreated flow provenance is unverified, never automatically trusted/migrated or deleted. Publication precedes success delivery and final same-context validation guards delivery; no raw fragments/credentials in logs. Ordinary normal-app offline/startup/legacy semantics remain separately gated, and backend/state-echo assumptions are not proven by a MockEngine fixture.

## 5. G-STORE01–10 tracker (API31 proof verified; normal recovery/backup gate open)

| Gate | Current | Exact next evidence |
|---|---|---|
| G-STORE01 capability/envelope | `[x] PASS` **isolated proof boundary** | JVM+API31 unsupported/malformed/future version/owner/UUID/oversize/overflow typed fail-closed; actual Android adapter capability PASS. Not normal-app storage authority proof |
| G-STORE02 PREPARE/write-then-throw | `[x] PASS` **isolated proof boundary** | Protocol and actual Android file+directory sync/replace prepare failpoints + killed prepared writer never trust staged B; no fallback A after durable PREPARED |
| G-STORE03 readback | `[x] PASS` **tested proof paths** | JVM/API31 read errors, truncation/receipt skew, unexpected head, actual readback failpoints: no publication/repair-delete. Not power-loss proof |
| G-STORE04 exact final CAS | `[x] PASS` **tested proof paths** | Interleaved/ABA/duplicate/foreign predecessor, ten actual handles and two actual Android processes one-winner CAS; exact committed recovery |
| G-STORE05 ambiguous receipt | `[x] PASS` **tested proof paths** | Actual Android commit write-then-throw and exact receipt resolution; unknown/unreadable/unsupported remains unavailable; no exception-as-proof |
| G-STORE06 cancel/publication | `[x] PASS` **tested proof paths** | Cancelled prepare/commit/publication/shutdown, actual cross-process lock wait cancellation and dead-holder release; no tested lock leak/purge. Main-process death/reboot untested |
| G-STORE07 concurrency/multiprocess | `[x] PASS` **tested proof paths** | Real two JVMs + two distinct Android service processes and ten handles, ABA/clear/recreate/incarnation/overflow; JVM directory sync simulated but actual Android paths verified |
| G-STORE08 recreation/filesystem | `[x] PASS` **API31 proof boundary** | Original red unchanged, positive actual filesystem/fresh SDK, killed prepared+committed child writers and recreated service verified API31. API24/34, full main-process kill, reboot/power-loss not tested |
| G-STORE09 legacy compatibility | `[ ] PARTIAL` — JVM/API31 typed proof PASS | Raw/corrupt/empty/future preserved/unverified/no auto recovery; normal-user legacy/offline/downgrade semantics still PENDING; no live app downgrade attempted |
| G-STORE10 backup/restore | `[ ] PARTIAL` — policy A APPROVED, normal implementation deferred | Synthetic exact-witness metadata/session skew rejection PASS; external DB-only/full coherent rollback **characterization only**. Actual Room/backup restore untested; normal exclusions/restore integration await ordered binding/privacy gates |

## 6. Evidence log — update in place each slice

**Historical anchor, 23 October 2026:** Phase B1.1a design-only review reported 578/578 baseline byte-identical, 20/20 Room8 preserved, SDK provenance 58 sources (56 identical+2 existing patches); **no new runtime test**. Prior B1.1 actual-SDK proof 16/17 PASS JVM and API31 with failed recovery; app unit 381/381; Room8 S06 6/6; account desired 0/2; instrumentation 129/137. This log is **not** a new execution.

| Date / slice | Source diff & preservation | Command / environment | Executions / outcome | Evidence and exact limitation | Next decision/action |
|---|---|---|---|---|---|
| 2026-10-23 / B1.1a design | 3 design docs only; 578/578 baseline intact | source/structure/provenance checks, no runtime | `NOT_RUN` runtime; docs checks PASS | ADR/matrix/design review; 20 Room8 unchanged | Seek REC-AUTH 3 approvals, freeze storage contract |
| 2026-10-23 / fresh PREP + recovery reproduction | `fix-bug`, HEAD `5c0c813e262ec7d004f9e72922e0ba98e7444087`; 566 files hashed before/after test, all unchanged before report edit; existing dirty/deleted/untracked work preserved | `git status --short`; `git log -1 --format="%H%n%D%n%ci"`; `python tools/verify_auth_fence_provenance.py --report-dir build/evidence/core-reconcile-20261023`; targeted Gradle command below | Provenance PASS; JVM **0 PASS / 1 FAIL / 0 ERROR / 0 SKIPPED**, exit 1, `FAIL_SAFETY`; implementation `BLOCKED_DECISION` | Raw inventory/provenance/patch/JUnit XML and SHA-256 in `build/evidence/core-reconcile-20261023/`; no device install/backend/schema/SDK-BIND | Ask joint REC-AUTH-PROOF/COMMIT/LEGACY-AUTH-PROOF decision; no safety-dependent phase advance |
| 2026-10-23 / approval A + first storage slice | Atomic store contract/API/Android filesystem adapter, authority validation and SDK restore, protocol/recovery/process tests, module test source set and provenance/device tooling; existing app untouched | `gradlew.bat -PsdkFenceProof=true :auth-fence-proof:testDebugUnitTest --rerun :app:testDemoDebugUnitTest --rerun :app:testProductionDebugUnitTest --rerun --console=plain` | SDK 48 PASS / 1 SKIPPED; demo194 + production187 PASS; **PARTIAL**, no device durability PASS | `auth-store-proof-20261023/run-03`; original recovery test now PASS unchanged; cross-process real JVM CAS has simulated directory sync | Review shutdown/cancellation boundary, finish diagnostic APK/manifest checks; ask separate device permission before executing |
| 2026-10-23 / final local B1.1a + shutdown | Same proof-only files + ordinary/cancelled shutdown regression; compiled Android actual-filesystem tests; app/Room8 and original SDK tests preserved | `run-04`: proof+APK+demo+production rerun; `run-05`: final proof rerun+APK; exact commands below; aapt2 and dependencyInsight | Final SDK **50 PASS / 1 SKIPPED / 0 FAIL / 0 ERROR** of51; app **381 PASS**; APK/provenance/dependency isolation PASS; overall **PARTIAL / BLOCKED_DECISION** | Raw final XML/logs/manifest/provenance/hashes under `build/evidence/auth-store-proof-20261023/`; Android53 tests compiled, no install/run; original failure kept | Ask PROOF-DEVICE-31; stay at B1.1a, no SDK-BIND/B1.1b advancement until mandatory gate reconciled |
| 2026-10-23 / authorized Pixel_5 emulator diagnostic | Only runner emulator guards + this report; proof source/APK/original tests/app unchanged | Explicit Pixel_5 `emulator-5554`, qemu1, API36; exact runner command below; 7 inline Python guard tests | Android **53 PASS / 0 FAIL / 0 ERROR / 0 SKIP**; guards **7 PASS**; normal package preservation+diagnostic cleanup PASS | `pixel5-api36-run01/` logs/results/manifests/inventories, `pixel5-preflight/` guards+source snapshot; **not API31**, no Samsung-directed adb command | Ask PROOF-AVD-31; mandatory API31 + actual Android processes/OS-kill/backup not waived |
| 2026-10-23 / user-authorized Samsung API31 | Only runner explicit physical-target opt-in + report during53-test run; proof source/APK unchanged until subsequent process-test implementation | Same runner with `--serial RR8NC03E76A --allow-physical-serial RR8NC03E76A --require-model SM-A315G --require-api 31 --include-store`; `samsung-api31-run02` | **53 PASS / 0 FAIL / 0 ERROR / 0 SKIP**, guards15 PASS; package preservation/cleanup PASS | Raw logs+ledger/results under Samsung run02; run01 preflight mismatch preserved, 0 tests/no install. Normal app/backend untouched | Implement/test diagnostic-only Android processes and self-kill recovery; no policy/SDK-BIND bypass |
| 2026-10-23 / final API31 process+backup witness slice | Added diagnostic-only manifest, StoreProbeServices, AndroidStoreProcessTest, StoreBackupBoundaryTest; one internal WAITING_FILE_LOCK hook in proof adapter; runner guard/ledger updates. App, original SDK tests, core spec/plan preserved | `run-06/07` proof+APK+demo+production; Samsung run03-process and run04-final, exact commands below; provenance+Python guards | Final API31 **61 PASS**, 0 fail/error/skip (**includes2 limitation characterizations**); JVM **53 PASS/1 SKIP**; app **381 PASS**. G01–08 PASS at stated proof boundaries; G09 PARTIAL/G10 **BLOCKED_DECISION** | `samsung-api31-run04-final`, `run-07`, `samsung-final-test-summary.json`, `samsung-preservation-final.json`; diagnostic removed, empty package inventory restored. Historical FAIL retained | Ask D2-BACKUP A/B section4.2; no normal-app backup policy change or dependent phase bypass |
| 2026-10-23 / D2-BACKUP A + B1.1b suspend writer slices01/02 | Spec/plan approved contract updated in-place; proof AuthImpl/Authority/Auth API/MFA patches +SdkWriterFenceTest28 cases; scoped provenance/runner lists updated; original tests/app/Room preserved | Proof+APK `run-01/02`, Samsung API31 `slice01/02`, normal demo+production unit rerun; exact commands below | Latest proof JVM **81 PASS/1 SKIP** of82; Samsung **89 PASS**,0 fail/error/skip (2 limitation characterizations remain); app **381 PASS**; provenance54 identical+4 patched PASS | `build/evidence/auth-writer-proof-20261023/` raw logs/XML/ledger/manifest/inventories/source hashes. No new desired P0 test failure or weakened original assertion | SDK-AWAIT-API decision at actual public API boundary; full writer/gate remains PARTIAL/BLOCKED_DECISION |
| 2026-10-23 / missing-session positive controls and fixture correction | Two additional desired controls; fixed4 new fixture JSON fields `code`→pinned `error_code`, no original assertion/source test weakened and no new SDK algorithm change | `run-03-controls` then `run-04-controls-fixed` proof+APK; Samsung `samsung-api31-slice03-controls` | First desired control FAIL:82 PASS/1 FAIL/1 SKIP; after correction **83 PASS/1 SKIP** JVM, **91 PASS/0 FAIL/0 ERROR/0 SKIP** API31; typed-error branch now positively verified | Failed raw XML/log retained; earlier typed branch coverage explicitly downgraded to generic401. Final raw/artifact/source hashes in slice03 summary/preservation | Ask SDK-AWAIT-API A/B, no whole-SDK PASS or normal-app change |
| 2026-10-23 / SDK-AWAIT-API A + awaited/job/explicit-flow slices | Approved proof APIs/internal callers, post-commit task tickets, retry/error guards, additional MFA/result guards;38 shared +6 Android tests; exact provenance/runner scope updated. Original3 tests, previous writer tests and380 app files preserved | `auth-awaited-proof-20261023/run-01/02/03`; same proof JVM+APK command below; app units explicit rerun; authorized Samsung diagnostic | Compile-only error retained/corrected (0 tests); final **121 PASS/1 SKIP JVM**, **135 PASS API31**, **381 PASS app**; no desired assertion failure | `slice01-test-summary.json`, `slice01-preservation.json`, `tested-run03.apk`, `samsung-api31-run01/` full ledger/logs/inventories;51 upstream-identical+7 patches provenance PASS | Continue BLK-18 raw/high-level flow provenance and lifecycle/recovery matrix; no SDK-BIND/normal policy or full G-SDK claim |

**Intermediate failures preserved:** First compile attempt failed because Android public `OsConstants` does not expose `O_DIRECTORY`; fixed with `Os.open(O_RDONLY)` plus `Os.fstat`/`S_ISDIR` before fsync. No tests executed on that attempt. A subsequent expanded JVM run (`run-02/gradle.log`, copied JUnit XML) had **47 PASS / 1 FAIL / 1 SKIPPED** out of 49: the new cross-process harness could not discover the Gradle worker classpath, not a safety assertion failure. Fixed by passing actual Gradle `Test.classpath` to child JVMs; same CAS assertion then PASS in `run-03`. The earlier compile-only failure and first successful pre-process-test run were observed in tool output, without separate retained raw log; do not infer missing raw artifacts.

#### Fresh reconciliation details (2026-10-23)

- Initial Git status: **32 entries** (directory-level untracked entries included); 7 tracked deletions already present, including 6 archived planning/report files and `docs/plan/evidence/b0/production-characterization-records.txt`. These were not restored/deleted by this session. The three core documents are currently untracked user work and remain in place. Historical evidence that depended on the missing archive is not freshly certified.
- `settings.gradle.kts` keeps `:auth-fence-proof` opt-in with `-PsdkFenceProof=true`; no app dependency substitution was introduced. Source inspection is not a fresh dependency graph or app isolation proof.
- **Exact test command:** `.\gradlew.bat -PsdkFenceProof=true :auth-fence-proof:testDebugUnitTest --tests "io.github.jan.supabase.auth.SdkPersistenceRecoveryGateTest" --rerun-tasks --console=plain`.
- Result: compilation completed; the unchanged desired recovery assertion failed (`ambiguousPersistFailureMustNotBecomeTrustedRestoreAfterAuthorityRecreation`), **1 execution, 1 failure**, JUnit timestamp `2026-10-23T07:19:12.320Z`. No assertion was weakened, ignored, or reclassified as expected-unsafe. This run does not rerun or supersede the earlier complete 17-test suite/API31 result.
- Evidence root: `build/evidence/core-reconcile-20261023/`. Raw copied XML `TEST-io.github.jan.supabase.auth.SdkPersistenceRecoveryGateTest.xml` SHA-256 `50c1b87a74e97fb4cfa0b2bdd3b0f2f92df140362bde43ff10e42a4d1e7a95f4`; original test source SHA-256 `992b234a88a70e2c7b0900fbfe269fd21cbc18fecde7813e185f0c974f241bde`. `workspace-before.json` captures all 566 available non-ignored tracked/untracked file hashes and missing paths; `verification.json` records preservation and artifact digests.
- Pada slice **pre-approval**, only this report was edited by the agent; no storage implementation was performed and the red test remained P0. Setelah approval A, source changes dan hasil baru tercatat di atas. Raw evidence tetap under `build/evidence`, tanpa plan/ADR/narrative report baru; spec/plan contracts and ordering unchanged. Tidak ada commit/push, app install/clear/uninstall atau backend mutation pada kedua slice.

#### Final local slice commands and evidence (2026-10-23)

- `run-04`: `.\gradlew.bat -PsdkFenceProof=true :auth-fence-proof:testDebugUnitTest --rerun :auth-fence-proof:assembleDebugAndroidTest :app:testDemoDebugUnitTest --rerun :app:testProductionDebugUnitTest --rerun --console=plain`.
- `run-05` (final proof source): `.\gradlew.bat -PsdkFenceProof=true :auth-fence-proof:testDebugUnitTest --rerun :auth-fence-proof:assembleDebugAndroidTest --console=plain`.
- Dependency graphs, separately per variant: `.\gradlew.bat :app:dependencyInsight --dependency auth-kt --configuration demoDebugRuntimeClasspath --console=plain` and the same with `productionDebugRuntimeClasspath`; `build-and-dependencies/{demo-auth,production-auth}.log`. Both resolve upstream Auth3.0.3 with no proof project substitution.
- `python tools/verify_auth_fence_provenance.py --report-dir build/evidence/auth-store-proof-20261023/provenance-final`; `python -m py_compile tools/verify_auth_fence_provenance.py tools/run_auth_fence_proof_device.py`.
- `C:\Users\SMMA-Softeng\AppData\Local\Android\Sdk\build-tools\37.0.0\aapt2.exe dump badging sdk/auth-fence-proof/build/outputs/apk/androidTest/debug/auth-fence-proof-debug-androidTest.apk`; same executable `dump xmltree --file AndroidManifest.xml <same APK>`. Saved `apk-badging.txt`, `apk-manifest.txt`; both package and instrumentation target must equal isolated diagnostic package before any later install.
- Read-only device commands: `adb devices -l`; `adb -s RR8NC03E76A shell getprop ro.build.version.sdk` →31; `adb -s RR8NC03E76A shell pm list packages com.diajarkoding.imfit` → empty at this check. Runner rechecks absence before install and refuses replacing any pre-existing diagnostic package.
- `final-test-summary.json` maps final SDK and app suites to exact raw XML paths, timestamps/counts/hashes and APK digest; `preservation-final.json` records per-source hashes/deltas and original SDK test/app preservation against initial inventory. Filesystem/process protocol PASS is explicitly weaker than Android durability/OS-death/real-user backup proof.
- New source: `AtomicSessionStore.kt`, Android `AtomicStoreFiles.kt`/`FileAtomicSessionStore.kt`; modified proof `AtomicSessionAuthority.kt`/`AuthImpl.kt`; tests `StoreProofFixtures.kt`, `AtomicSessionStoreProtocolTest.kt`, `SdkAtomicStoreRecoveryTest.kt`, JVM-only `AtomicStoreProcessTest.kt`, Android-only `AndroidAtomicStoreDeviceTest.kt`; module `build.gradle.kts`, provenance/device scripts. No production source/dependency/schema change.
- Technical limitation: `resolve(exactTicket)` never falls back to old A. A failure **before any new durable PREPARED write** cannot encode an attempted identity change in storage; the prior committed record may still exist. Normal-app recovery/expected-account policy is not decided by this proof; do not claim detection of intentions never persisted or of complete store rollback. Errors after a durable PREPARED never restore the previous record.

#### Pixel_5 emulator slice commands/evidence (2026-10-23)

- `adb devices -l` enumerated the emulator and connected Samsung, but **all subsequent targeted adb calls use `-s emulator-5554` only**; no command is directed to Samsung after user's explicit restriction.
- `adb -s emulator-5554 emu avd name` → Pixel_5; `adb -s emulator-5554 shell getprop ro.kernel.qemu` →1; `adb -s emulator-5554 shell getprop ro.build.version.sdk` →36. Existing `Pixel_5.avd/config.ini` confirms profile `pixel_5` and android36; file read-only, never edited. API31 installed-image `source.properties` confirms API31/x86_64 revision12; no image download/AVD creation/boot/wipe performed.
- **Exact diagnostic command:** `python tools/run_auth_fence_proof_device.py --aapt2 "C:\Users\SMMA-Softeng\AppData\Local\Android\Sdk\build-tools\37.0.0\aapt2.exe" --evidence-dir build/evidence/auth-store-proof-20261023/pixel5-api36-run01 --serial emulator-5554 --require-avd-name Pixel_5 --include-store` → exit0.
- Runner now requires explicit emulator serial, checks exact authorized AVD and qemu before install, then verifies APK package+target and refuses replacing any existing diagnostic package. Guard validation **7/7 PASS** using inline Python unittest/mock, log `pixel5-preflight/emulator-guard-tests.log`; physical-serial CLI case asserts `subprocess.run` was never called. `python -m py_compile tools/run_auth_fence_proof_device.py` PASS.
- Per-test raw statuses include original recovery assertion PASS; total53 = original17 + protocol25 + SDK recovery8 + Android actual adapter3. Only the latter actual-filesystem cases and strict platform-directory test establish their stated platform boundary; protocol tests remain explicitly simulation-backed for directory-sync.
- `sdk-proof-packages-before.txt` and `sdk-proof-packages-after.txt` are byte-identical, retaining both normal IMFIT demo and production packages. Runner installed without `-r` and uninstalled only the new isolated diagnostic package after instrumentation. No normal app installation, clear, uninstall, force-stop, or backend access occurred.
- `pixel5-preflight/workspace-before.json` captures all available source hashes before run; `pixel5-api36-preservation.json` captures after-run comparisons/artifact SHA-256. Existing app files, all SDK proof/test source files, APK and the two other core docs unchanged in this slice; only runner guards/digest bookkeeping and this report changed relative to previous local final snapshot. Run01 original logs/results are preserved; normalized-vs-raw Windows newline hash discrepancy is explicitly distinguished above, not silently overwritten or classified as a test failure.

#### Final Samsung/API31 commands and evidence (2026-10-23)

- `run-06` and `run-07`: `.\gradlew.bat -PsdkFenceProof=true :auth-fence-proof:testDebugUnitTest --rerun :auth-fence-proof:assembleDebugAndroidTest :app:testDemoDebugUnitTest --rerun :app:testProductionDebugUnitTest --rerun --console=plain` → successful; raw logs + copied per-variant JUnit XML retained separately in each run directory.
- Final device: `python tools/run_auth_fence_proof_device.py --aapt2 "C:\Users\SMMA-Softeng\AppData\Local\Android\Sdk\build-tools\37.0.0\aapt2.exe" --evidence-dir build/evidence/auth-store-proof-20261023/samsung-api31-run04-final --serial RR8NC03E76A --allow-physical-serial RR8NC03E76A --require-model SM-A315G --require-api 31 --include-store` → exit0,61 tests. Same options with `samsung-api31-run03-process` previously ran58 tests before the3 backup fixtures were added.
- `python tools/verify_auth_fence_provenance.py --report-dir build/evidence/auth-store-proof-20261023/provenance-samsung-final` → source provenance PASS; `python -m py_compile tools/run_auth_fence_proof_device.py tools/verify_auth_fence_provenance.py` → PASS. Runner defaults still forbid physical devices; exact serial/model/API explicit opt-in tested in15 guard cases.
- Raw command ledger for each successful run proves exact adb serial and limited operations: readonly identity/package queries, new diagnostic install, instrumentation, diagnostic uninstall and final inventory. It contains no normal app install/clear/force-stop, device reboot/settings/AVD change or production credential/backend command. Actual child self-kill originates only in private diagnostic service code under its own PID.
- Source-only backup review: `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/{backup_rules,data_extraction_rules}.xml`, production `SupabaseModule.kt`; no active targeted exclusions or custom session recovery policy set here. These files remain unchanged. No real backup capture/restore, normal-user session migration, server protocol change, commit or push performed.
- Original three SDK test sources and all380 existing app files remain byte-identical to initial inventory; spec/plan unchanged. New4 diagnostic/test files and internal adapter hook are recorded in `samsung-preservation-final.json`, along with artifact/source SHA-256. User's pre-existing dirty/untracked/deleted work is retained.

#### B1.1b suspend-writer slice commands/evidence (2026-10-23)

- Proof `run-01`, `run-02`, `run-03-controls`, `run-04-controls-fixed`: `.\gradlew.bat -PsdkFenceProof=true :auth-fence-proof:testDebugUnitTest --rerun :auth-fence-proof:assembleDebugAndroidTest --console=plain`; latest84 JVM executions =83 PASS/1 Windows directory-sync SKIP, 0 failure/error. Intermediate positive-control failure preserved as described above. Raw `gradle.log` + JUnit XML copied per run; original assertions retained.
- App `app-regression-01`: `.\gradlew.bat :app:testDemoDebugUnitTest --rerun :app:testProductionDebugUnitTest --rerun --console=plain` →381 PASS; intentionally no SDK substitution/property. This is normal app regression, not proof SDK integration.
- Final diagnostic: `python tools/run_auth_fence_proof_device.py --aapt2 "C:\Users\SMMA-Softeng\AppData\Local\Android\Sdk\build-tools\37.0.0\aapt2.exe" --evidence-dir build/evidence/auth-writer-proof-20261023/samsung-api31-slice03-controls --serial RR8NC03E76A --allow-physical-serial RR8NC03E76A --require-model SM-A315G --require-api 31 --include-store` →91 PASS, 0 fail/error/skip. Earlier invocations with `samsung-api31-slice01/02` ran81/89 cases. Every targeted adb command uses the explicitly approved serial; newly installed diagnostic uninstalled afterward, normal inventory identical.
- `python tools/verify_auth_fence_provenance.py --report-dir build/evidence/auth-writer-proof-20261023/provenance-slice02` →54 identical +4 exact allowed upstream patches,4 own additions; no version/BOM/dependency upgrade. `python -m py_compile tools/run_auth_fence_proof_device.py tools/verify_auth_fence_provenance.py` PASS.
- Changed source relative to store-proof slice: proof `AtomicSessionAuthority.kt`, `AuthImpl.kt`, `AuthenticatedSupabaseApi.kt`, `mfa/MfaApi.kt`; new `SdkWriterFenceTest.kt`30 tests; provenance allowlist/device test class list. Core spec/plan changed only to carry the approved restore contract (and remove stale dynamic-status header from plan); report alone holds runtime counts/decisions. All380 app files, Room8/revision/migrations, original3 SDK tests, settings/dependency configuration and license/pinned source checksum preserved. `slice03-preservation.json` records exact hashes, deltas and raw artifact hashes; no live app/backup/data/normal device settings/backend operation, commit or push.

**Do not overwrite past FAIL evidence.** When new test output arrives, add/update new dated row and dashboard counts; store raw JUnit XML/log/artifact hashes in build/evidence directory without making a new narrative report document.

#### SDK-AWAIT-API commands (2026-10-23)

- `run-01/02/03`: `.\gradlew.bat -PsdkFenceProof=true :auth-fence-proof:testDebugUnitTest --rerun :auth-fence-proof:assembleDebugAndroidTest --console=plain`; latest build/test exit0. The first attempt was compile-only, not a failed runtime safety test.
- `app-regression-01`: `.\gradlew.bat :app:testDemoDebugUnitTest --rerun :app:testProductionDebugUnitTest --rerun --console=plain` →381 PASS; no opt-in proof property/substitution.
- `python tools/run_auth_fence_proof_device.py --aapt2 "C:\Users\SMMA-Softeng\AppData\Local\Android\Sdk\build-tools\37.0.0\aapt2.exe" --evidence-dir build/evidence/auth-awaited-proof-20261023/samsung-api31-run01 --serial RR8NC03E76A --allow-physical-serial RR8NC03E76A --require-model SM-A315G --require-api 31 --include-store` →135 PASS, exit0. No real provider/browser launched, normal package modified, AVD changed or backend contacted.
- `python tools/verify_auth_fence_provenance.py --report-dir build/evidence/auth-awaited-proof-20261023/provenance-01` →51/58 byte-identical +7 expected patches +4 existing additions. Per-source hashes include all proof changes; license/archive checksum unchanged. Branch `fix-bug`, HEAD `5c0c813e262ec7d004f9e72922e0ba98e7444087` unchanged.

## 7. Next action & STOP / completion contract

**Next action now:** finish BLK-18 proof-only external-flow provenance (raw PKCE/high-level initiation) and lifecycle/recovery ordering with deterministic tests. SDK-AWAIT-API A is already approved and its current slice verified; do not ask it again or skip remaining work to SDK-BIND. Preserve original tests and normal app; update this same report after the next tested slice.

**Decision resolved:** User approved option A verbatim “A: setujui dan lanjutkan”. This does **not** authorize normal-app SDK-BIND, real-user legacy/offline policy, backup/privacy changes, D2/D6, backend/schema changes, or release/device operations. JVM proof alone cannot establish Android crash/power-loss or complete backup rollback guarantees; those remain explicit gates.

**Latest device decision:** user now explicitly authorizes Samsung as API31 for diagnostic tests, superseding the preceding Pixel_5-only restriction **only within that diagnostic scope**. Preserve prior restriction/execution history as evidence. No normal app/backend/release authorization, physical reboot/power-loss or AVD creation is included.

**Resolved environment choice:** use existing authorized Samsung API31; the additional-AVD question is no longer needed. Remaining mandatory process/backup properties are not waived by device approval.

**Latest policy decision:** D2-BACKUP A approved via “A lanjutkan”; no repeated administrative approval required for that contract. SDK-BIND, normal legacy/offline/logout/privacy choices not covered by the restore-only policy remain separate. Ask only when their actual boundary is reached or a new unsafe/unresolved SDK hook is found.

**Latest API decision:** SDK-AWAIT-API A explicitly approved by user, proof-only. SDK-BIND/LEGACY-AUTH-NORMAL remain PENDING and must be decided only at their actual integration boundary.

**Latest release decision:** user explicitly authorized **commit and push only** of all current work in categorized commits without co-author, to `origin` on the current `fix-bug` branch. This is not approval for deployment, normal-app installation, backend/schema changes beyond the already-tested work, or a release-ready verdict. Technical status remains `PARTIAL` as recorded above.

**Mandatory STOP:** trusted raw staged/legacy session, old epoch ack, wrong-owner request/header/read/publish, lost pending write, destructive unsupported template operations, unproven fsync/CAS/account isolation, silent backup/credential purge, unapproved changes to schema/backend/privacy/normal device. Update this same report with a decision question; other independent safe tasks may continue.

**Exit only if:** all mandatory G gates verified, six refresh/two infinite/six shimmer work and have tests, privacy/E2E/visual approval and regressions complete, no P0 unresolved, final verdict `READY_FOR_USER_RELEASE_APPROVAL`. This does **not** authorize commit/push/deployment.
