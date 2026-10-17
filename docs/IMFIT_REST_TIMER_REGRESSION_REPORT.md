# Rest timer — production release regression investigation

Date: 17 October 2026. Branch: `timer` (regression evidence captured before categorized commits).

## Scope and acceptance

Primary target: **productionRelease**, minification/resource shrinking enabled, target SDK 37,
running on **Pixel_5_API_34 / Android 14**, serial `emulator-5554`.
Debug/demo results are supporting evidence only, not release acceptance or OEM equivalence.
Current bug status: **BERHASIL SEMENTARA / provisional success**, per the user's latest
manual validation on a physical REALME X, Android 10 / API 29. Further issues will be
reported by the user. The historical 49,472 ms lateness has not been remeasured.

## Latest physical-device validation — user report

Recorded: 17 October 2026; source: user-performed manual testing, not agent-run ADB tests.

| Device | Scenario | Reported outcome |
| --- | --- | --- |
| REALME X, Android 10 / API 29, physical device | Rest extended by +30 seconds twice, then IMFIT left in background while using Instagram, Threads, Telegram and other apps | PASS manual: rest alarm notification appeared at the end of the approximately two-minute rest |
| Same device | Rest set directly to two minutes, then other apps used while IMFIT remained backgrounded | PASS manual: rest alarm notification appeared after approximately two minutes |

**Acceptance: bug rest timer marked successful for now; monitor and reopen investigation
if the user reports a recurrence.** No additional device testing is claimed from this update.
APK flavor/hash, exact test timestamps/repetition count, alarm API/window, monotonic lateness,
screen-off/Doze behavior and audio/heads-up/FSI details were not supplied. This confirms the
reported visible notification behavior, not universal OEM compatibility or zero lateness.

No new permissions, wake locks, proactive watchdogs, or changes to alarm API selection are
introduced by this instrumentation phase. The existing receiver recovery remains unchanged.
`EXACT_ALLOW_IDLE` in application logs means the API call returned; it does not assert that
the OS stored `windowLength=0`. Compare the correlated `dumpsys alarm` snapshot.

## Baseline retained

Artifacts (local, not committed):
`/private/var/folders/yv/171k2lgd0jdg4y0jclvxjtzw0000gn/T/opencode/imfit-rest-20261017/`

- Checkout baseline includes the previous recovery/session-validation patch; it is not a
  reconstruction of the original failing APK. HEAD, working patch, untracked sources,
  unit XML results, logcat and device snapshots are retained.
- Baseline demoDebug: **63 unit tests, 0 failures/errors**.
- Baseline productionDebug: **59 unit tests, 0 failures/errors**.
- Baseline demoDebug background integration:
  `twoMinuteRestInAnotherAppDismissAndStopDoNotReplay` **PASS**, one test, 194.329 s.
  First completion: receiver lateness 17 ms, commit lateness 111 ms, notify lateness 121 ms.
  The additional short rest committed at +512 ms. These are demo/debug measurements only.

## Root cause (confirmed on Pixel_5_API_34, productionRelease)

Supabase Auth defaults (`alwaysAutoRefresh=true`, `enableLifecycleCallbacks=true`) reset
auth to `Initializing` on every app backgrounding (`onStop` → `stopAutoRefresh` +
`resetLoadingState`), and only re-load on foreground (`onStart`). While backgrounded,
`awaitInitialization()` in the timer's session validation hangs until the 2.5 s bound,
so expiry cannot commit during the reproduced background attempt. Captures
`production-phase-auth` and `production-status-snap` show `auth_init_wait` timeouts and
`authStatus=Initializing`; after the config change `production-fix-verify` commits at
+354 ms while rendering JSON remains broken. This explains the emulator reproduction.
The historical physical-device retry timing is consistent with this mechanism, but lacks
phase/status traces: attribution of its 49,472 ms delay remains unproven.

Fix: `enableLifecycleCallbacks = false` in production `SupabaseModule`, so auth state stays
stable regardless of foreground/background. Logout still cancels the timer synchronously
before the session is cleared, and any loaded identity mismatch still cancels on next check.
Fast-path/init-wait phase logging is retained to prove background validation no longer waits.

## Test-first evidence

`RestTimerTickerIterationTest` reproduces the current service ordering: full session read,
then reconcile, with the whole operation skipped during read backoff.

RED run: **5 tests, 2 failures**. Failing tests:

- `sessionReadFailureAndBackoffMustNotGateExpiryReconcile`
- `timeoutIsRetryableAndNextIterationStillChecksExpiry`

This proves a source-level coupling of full rendering-session reads/backoff to expiry checks.
It does not prove that the same coupling caused the physical API 29 incident.
GREEN after decoupling is recorded below in the execution matrix.

## Release fixture safety

- A temporary Android user **10 / IMFITRestRegression** isolates test data from owner user 0.
- The previously installed production APK is retained locally for restoration.
- Production instrumentation uses the real Hilt/AuthRepository/Room/runtime/coordinator,
  with an explicitly opted-in synthetic SDK session and synthetic active-session row.
- Tests refuse existing non-fixture accounts/workouts and require an emulator with networking
  disabled. No remote sign-in, profile creation, workout upload, or remote sign-out is used.
- Exact access is set by the host before instrumentation. Revoking it inside the instrumented
  process can kill that process and produce a misleading lifecycle failure.
- The application APK stays minified, but instrumentation-only keep rules change its R8
  surface; it is not byte-identical to an ordinary release. Error Prone annotations needed by AndroidX Test tracing
  are compile-only test dependencies, not additions to the production APK.

## Execution matrix

| ID | Verification | Status / evidence |
| --- | --- | --- |
| TEST-01 | Exact method selection | PASS unit (5/5 scheduler) and initial release exact E2E with OS window=0 |
| TEST-02 | SecurityException fallback + diagnostics | PASS unit injection; permission-denied fallback PASS release. Device SecurityException injection NOT_TESTED |
| TEST-03 | Authoritative deadline/generation | PASS unit (coordinator 47/47) and initial release generation-2 E2E |
| TEST-04 | Session-read failure/backoff does not block expiry | PASS unit (ticker 7/7) + PASS final release fault E2E (commit +807 ms with broken rendering JSON) |
| TEST-05 | Single expiry under concurrent sources | PASS coordinator integration-style unit concurrency; observed initial release E2E has one commit per rest/generation. Forced runtime contention NOT_TESTED |
| TEST-06 | Extend/stale generation | PASS unit; PASS productionRelease background E2E (gen 0/1 stale silent, gen 2 alarm commit) |
| TEST-07 | Other application foreground | PASS productionRelease (Settings foreground 120 s): receiver +3 ms, commit +30 ms, notify +43 ms |
| Supplemental | Receiver without FGS (same process) | PASS productionRelease: receiver +6 ms, commit +34 ms, notify +40 ms, no auth wait; not process recreation |
| TEST-08 | Screen off | PASS productionRelease (screen off, exact): receiver +10 ms, commit +51 ms, notify +62 ms |
| TEST-09 | Forced Doze | PASS productionRelease (forced deep doze, exact): receiver +4 ms, commit +52 ms, notify +58 ms |
| TEST-10 | Exact access allowed/denied | PASS productionRelease both modes (exact: alarm commit; denied: INEXACT fallback, ticker commit +477 ms) |
| TEST-11 | Actual process death and persisted recovery | PASS final cold retry: SIGKILL PID 11982 → alarm PID 12068, commit +333 ms, active 1002 observed; persisted EXPIRED/presentationHandled verified without reconcile |
| TEST-12 | Cancel/logout prevents stale completion | PASS productionRelease cancel; logout/account-switch unit coverage only, logout E2E NOT_TESTED |

### Build/harness history and final artifacts

Early release runs were BLOCKED by cross-APK class stripping (Trace, Kotlin stdlib,
coroutines, ContextCompat, correlation helper), not timer assertions. Final harness APK
uses `-dontshrink/-dontoptimize`; app inspection APIs and shared Kotlin/coroutines are kept
only with `-PrestTestBuildType=release`. Broad timer-package keep and diagnostic
`-dontobfuscate` have been removed. App minification, obfuscation, resource shrinking and
optimization of non-kept code remain enabled; kept inspection/library code is a residual
delta from the ordinary release. ContextCompat is avoided in tests in favor of framework APIs.

Final instrumented application SHA256:
`7fdd4d07d15976fbed00961a54ab4082f626e6a69934ea6752456caa598fc5a7`.
Test APK SHA256:
`7981af0f42eb7f710916c058e477aef6e279601eb19b6bb629d907ce916de243`.
Both APKs and app R8 mapping/configuration are retained in `release-final/` under the
artifact root. Same-artifact regression in `final-suite/` completed: **7 PASS, 1 FAIL** on
the initial pass. The cold scenario committed at +257 ms and persisted state verified,
but its first host notification-list query did not contain 1002. This failure is retained.
After correcting the observer to bounded active-notification polling (5 s), cold-only retry
in `final-cold-observation/` **PASS**: first query lacks 1002, second query contains it;
observation took 160 ms. No application APK change was made for this observer correction.
Final scenario coverage is **8/8 PASS across the final suite plus this cold retry**, not
an assertion that the original suite invocation was clean.

Ordinary `:app:assembleProductionRelease` (without the instrumentation property): **PASS**,
including lintVital. SHA256:
`ed11335a61b3245632356e57e260caa3e5ae1f900f7accac2116949b62947555`.
APK/config/mapping are retained in `ordinary-release/`. Configuration inspection confirms
no instrumentation Kotlin/coroutines/timer-package keep or `-dontobfuscate` rule.
This ordinary APK was not installed over the running E2E target; runtime E2E on that
byte-identical ordinary artifact is **NOT_TESTED**.

Final unit execution: demoDebug **80**, productionDebug **76**, **0 failures/errors/skips**.
Timer suites per flavor: scheduler 5, coordinator 47, recovery policy 3, diagnostics 4,
session validator 9, ticker iteration 7. Python timing-analysis tests: **3/3 PASS**.
XML and source hashes are retained as `final-unit-*` and `final-source-sha256.json`.

Historical failures are retained, not erased:

- **FAIL reproduction:** background auth-init timeouts before lifecycle config fix.
- **BLOCKED harness:** missing classes before setup; first Doze attempt failed UI setup
  before arming; two initial cold attempts produced `finished inst` force-stop rather
  than process death. Raw runner files retain their original FAIL labels, but these are
  not product timer failures. Corrected cold test records SIGNALED/status=9.
- **NOT_TESTED:** actual logout E2E, injected runtime SecurityException, forced device
  mutex contention, real-account auth refresh, and System UI heads-up/audio presentation.

Agent-run runtime coverage outside API 34: **NOT_TESTED**. Physical REALME X / API 29
background notification scenarios: **PASS manual, user-reported**, as recorded above.
Other physical OEM/API coverage and unreported scenarios remain **NOT_TESTED**.

## Reproduction commands

Build the actual release application and release instrumentation APK:

```bash
./gradlew :app:assembleProductionRelease :app:assembleProductionReleaseAndroidTest -PrestTestBuildType=release --console=plain
```

After installing both APKs for the isolated user, run a selected scenario, e.g.:

```bash
python3 tools/rest_timer_regression.py --package com.diajarkoding.imfit --user 10 --offline --exact allow --class-name 'com.diajarkoding.imfit.core.timer.RestTimerProductionReleaseTest#backgroundExactProductionRelease' --output /path/to/artifacts/background
```

Run the full same-APK suite serially (output directory must not exist):

```bash
python3 tools/run_rest_timer_suite.py --serial emulator-5554 --user 10 --output /path/to/artifacts/full-suite
python3 -m unittest discover -s tools -p 'test_*.py'
```

Use `--exact deny` for `exactDeniedUsesInexactProductionRelease`. A class-wide invocation is
not equivalent: permission revocation can kill the target. Invoke scenario methods separately.
Cold-process testing uses `prepareColdProcessFixture` with `--cold-process`; the fixture
stays alive and the host uses emulator `su 0 kill -9 <verified-pid>` (not force-stop),
observes a new PID and real receiver/commit/notify, then invokes
`verifyAndCleanColdProcessFixture` to read persisted state without triggering reconcile.

The runner stores metadata, raw logcat, instrumentation outcome, before/near-deadline/after
alarm-service-idle snapshots, and `timings.json`. Null timing fields mean missing evidence,
not zero latency. Processing delay uses the last matching source request before commit.

## Residual risks

- Emulator instrumentation itself can affect process priority and CPU wakefulness. Forced
  Doze state is observable, but this does not replace unattended physical screen-off testing.
- `notify_result success=true` is not evidence of visible heads-up/FSI, audible playback,
  or vibration. Normal E2E additionally checks the active notification; System UI presentation
  still needs device validation.
- A short session-read fault test intentionally cancels its primary alarm to isolate the FGS
  ticker. It is not an assertion of real AlarmManager delivery.
- WorkManager is best-effort, not an exact deadline scheduler.
- Disabling Supabase lifecycle callbacks also prevents lifecycle-driven refresh stop/reload.
  Auth initialization and automatic refresh remain enabled. Background token refresh/network
  cost and real-account sign-in/refresh behavior have not been exercised by offline fixtures.

## Measured post-fix evidence (initial instrumented release)

All rows below: Pixel 5 AVD, API 34, synthetic local account, FSI denied. Times are
monotonic milliseconds; columns R/C/N/P mean receiver/commit/notify lateness and processing
delay. `—` means no corresponding event, not zero. Each completion row has one commit.
The artifact directory names identify the complete metadata, logcat and alarm snapshots.

| Artifact directory | Method / OS window | Deadline | Correlation / generation | Source | R / C / N / P (ms) | Result |
| --- | --- | ---: | --- | --- | --- | --- |
| `production-fix-verify` | EXACT scheduled then cancelled to isolate ticker | 12309529 | `9a4e52d7c451` / 0 | fgs_ticker | — / 354 / 363 / 5 | PASS |
| `production-background-exact` | EXACT / 0 | 12444510 | `ba72d35bf185` / 2 | alarm | 3 / 30 / 43 / 5 | PASS |
| `production-receiver-only` | EXACT / 0 | 12576346 | `82b2f80a79c6` / 2 | alarm | 6 / 34 / 40 / 8 | PASS |
| `production-cancel` | EXACT / 0 before cancel | 12708670 | see logcat | none | — / — / — / — | PASS, zero commits |
| `production-inexact` | INEXACT / 89793 ms | 12839080 | `b23d9da14bfb` / 2 | fgs_ticker | — / 477 / 485 / 10 | PASS, alarm did not deliver first |
| `production-screenoff` | EXACT / 0 | 12969926 | `2304322b48ac` / 2 | alarm | 10 / 51 / 62 / 7 | PASS |
| `production-doze2` | EXACT / 0 | 13156014 | `f3e3d1ef02f0` / 2 | alarm | 4 / 52 / 58 / 8 | PASS |
| `production-cold3` | EXACT / 0 | 14417101 | `b7ce0ee40edf` / 0 | alarm, new PID | 216 / 344 / 349 / 99 | PASS |

Final same-APK measurements (`final-suite/` plus cold retry; active notification asserted
for non-cancel scenarios):

| Scenario | Deadline | Correlation / generation | Source | R / C / N / P (ms) | Result |
| --- | ---: | --- | --- | --- | --- |
| render-fault | 14510765 | `6da832b2b8f6` / 0 | fgs_ticker | — / 807 / 812 / 6 | PASS |
| background | 14635993 | `90595d09ed0f` / 2 | alarm | 19 / 61 / 66 / 6 | PASS |
| receiver-only | 14760431 | `acd9f7eb6ac0` / 2 | alarm | 16 / 66 / 72 / 6 | PASS |
| cancel | see armed snapshot | see logcat | none | — / — / — / — | PASS, zero commits |
| inexact | 15009667 | `6047b3321c31` / 2 | fgs_ticker | — / 880 / 889 / 15 | PASS |
| screen-off | 15133589 | `5741615dcf97` / 2 | alarm | 21 / 50 / 54 / 6 | PASS |
| doze | 15257922 | `cc515cd01332` / 2 | alarm | 15 / 51 / 56 / 8 | PASS |
| cold | 15320615 | `56c3528b7aae` / 0 | alarm | 169 / 257 / 260 / 69 | FAIL active-card observation; commit/persistence PASS |
| cold retry | 15422811 | `471696192c6f` / 0 | alarm | 225 / 333 / 338 / 73 | PASS; active card on second host query (160 ms observation) |

Final exact OS windows are 0; the final inexact window is 89817 ms, correlated to deadline
15009667 in `final-suite/inexact/inexact-armed-alarm.txt`. The rendering fault cancels its
primary alarm deliberately, so no delivered alarm/window is claimed for that isolated ticker test.

Compared with baseline demoDebug +111 ms commit, production exact +30 ms is not a controlled
performance comparison (different flavor/build/session). The stronger before/after result
is the production broken-rendering reproduction: repeated auth-init timeout/no commit before
the lifecycle config fix, followed by background commit +354 ms after it. Neither comparison
proves the historical physical-device 49,472 ms delay has been eliminated.

## Scoped engineering gate and restoration

| Gate | Result (tested Pixel 5 API 34 instrumented release only) |
| --- | --- |
| Exact/fallback API and OS window observed | PASS; SecurityException itself unit-injected only |
| Ticker progress and expiry sources observed | PASS |
| No duplicate/stale completion | PASS unit concurrency + one commit per current E2E generation |
| Unit/integration-style regression | PASS 156 unit tests + real Room/Hilt E2E + 3 host tests |
| Background E2E on tested device | PASS all eight scenarios, cold with observer retry |
| Timing compared with retained baseline | PASS analysis; not a controlled OEM performance comparison |
| API/OEM/presentation limitations stated | PASS documentation; other API/OEM runtime remains NOT_TESTED |

**Scoped API 34 instrumented-release gate: 7/7.** This is not a universal resolved claim:
the historical physical 49,472 ms delay, ordinary-APK byte-identical E2E, real-account auth
refresh, and System UI heads-up/audio behavior remain unverified.

Emulator restoration: **PASS**, recorded in `restoration/`. Original production APK restored
and installed SHA256 verified (`e08f9a9a67ce73e73d6409a56756544a4d1afb660f8ce7df018f16284990a763`),
current user returned to 0, temporary user 10 stopped then removed, Doze unforced, battery
simulation reset, Wi-Fi/mobile data restored enabled, and stay-on setting restored to 1.
Owner-user app data was not cleared. Initial remove-user attempt during the user switch
failed; explicit `am stop-user -w -f 10` then removal succeeded. No commit or push was made.
