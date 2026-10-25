# Dialog backdrop — final 60% dim-only design

> Historical policy. Superseded by the user-approved
> [shared Compose scrim 50% migration](IMFIT_COMPOSE_SCRIM_REPORT.md), including both
> bottom sheets and fullscreen sync. The measurements below remain unchanged evidence.

18 October 2026. Branch `redisgn`, HEAD `3ec72efbf43dc8b749aa3e4ae413a4a0373d8fa7`
plus retained, uncommitted work. **Design change, not a root-cause repair.**

## Decision and scope

The user's final decision supersedes the previous 28% dialog and conditional-blur
proposals: all dialogs using `IMFITPlatformDialog` use **native black window dim 60%,
without blur**, on every supported Android version. The light-mode dialog surface
remains white; existing theme-aware dark surfaces are not forced white.

The earlier actual-Home API 34 diagnostic is **ACCEPTED / CLOSED — PASS**.
The original screenshot investigation remains **OPEN**: screenshot provenance and the
Realme APK/intensity comparison are still unresolved. Neither a hierarchy fix nor
per-frame dim reapplication is justified by that diagnostic.

| Owner | Final value in this scope |
| --- | --- |
| Dialog windows: `IMFITOpacity.dialogDim` | **0.60f** |
| Existing two bottom-sheet scrims: `IMFITOpacity.modalDim` | **0.28f**, unchanged |
| Fullscreen sync overlay: `IMFITOpacity.scrim` | **0.6f**, unchanged and independently owned |

Only wrapper-based dialogs were approved for this intensity change. Keeping a separate
dialog token prevents silently changing the photo-source and muscle-filter sheets.
There is no native dim added over either sheet's Compose scrim.

## Implementation and preserved behavior

- `theme/DesignSystem.kt`: add `dialogDim = 0.60f`; clarify ownership of the existing
  sheet/sync tokens.
- `components/common/IMFITPlatformDialog.kt`: change **only the backdrop default token**
  in this phase. Direct-parent lookup, `DisposableEffect(window, dimAmount)`, assignment
  order, diagnostic hooks and dim/flag restoration are unchanged. There is no native
  blur activation in the normal wrapper and no additional Compose overlay.
- `ModalDimPolicyTest.kt`: verify dialog 60%, sheets 28% and unchanged sync opacity.
- `ModalDimTest.kt`: use the new dialog token for native-window assertions; existing
  back, processing, explicit-skip, light/dark and restoration checks remain.
- `HomeBackdropDiagnosticTest.kt`: use the current dialog token, assert zero blur flag/
  radius at T0/T1/T2, sample the actual white card and verify background pixels recover
  after dismiss. Existing Home navigation, autofocus and three input changes are retained.
- The historical isolated `RoutineBackdropDiagnosticTest` now checks the current token
  rather than hardcoding 28%. Its experimental blur comparison is **not run for this
  policy acceptance** and does not enable blur in normal app UI. Historical reports and
  saved 28% evidence are retained, not rewritten as 60% results.

Inherited callers: session cancellation, rest settings/countdown, create/delete routine,
birth-date picker, and the shared input-dialog component (currently without a screen
caller). No caller, timer/repository/domain, dialog width, inset, draft, keyboard,
validation, save/delete or dismissal behavior was changed. Loading/error/empty/filter
states are N/A for this token-only change; each caller keeps its existing state contract.
No Pencil design, dependency, Compose version, device preference or production account
was changed. No commit/push was requested or performed.

## Runtime results — API 34 actual Home

Isolated synthetic demo package: `com.diajarkoding.imfit.demo.modaldiagnostic`.
App APK SHA-256:
`211ba05473a8d75b788e6d785506d44dc505471dbd7b62fc923e403f8f2a1814`.

Real route: `MainActivity → NavGraph → MainScreen → HomeScreen → RoutineDialog`.
Capture uses `UiAutomation.takeScreenshot()`; Activity/dialog Compose captures are
retained separately, not substituted for system evidence.

| Observation | Result |
| --- | --- |
| Direct-parent window, T0/T1/T2 | Found; same window throughout first opening |
| Native dim flag / amount | true / **0.60**, stable |
| Blur flag / radius | false / **0 px**, stable |
| Background RGB: baseline → T1 → T2 → dismissed | **247,249,252 → 99,100,101 → 99,100,101 → 247,249,252** |
| Light card RGB, T1/T2 | **255,255,255** |
| Activity-only Compose background capture | 247,249,252, without native dim |
| Autofocus/IME, then keyboard hidden | Dim works in both states |
| Input recomposition, outside-dismiss, reopen empty draft | PASS |
| Effect callback count | One per opening, not per input change |
| Activity window flags/dim after dismiss | Unchanged |

Three 8×8 px background ROIs at (22,385), (22,495), (22,605) pass against
`baseline × (1 − dialogDim)` with 3-channel quantization tolerance of 3.
An additional offline check of matched system-image regions confirms dim/restoration
on the header, exposed hero, routine list and bottom navigation. White list/navigation
regions change 255→102; the dark hero region also matches its own baseline ×0.4.
Coordinates, measurements and acceptance booleans are in `matched-home-regions.json`.

The current background is `#F7F9FC`, so **99,100,101**, not an exact required
**99,100,102**, is the expected approximate 60% result. The reference Realme estimate
used a different assumed baseline (`#F8FAFF`). No fake color adjustment was made to
force the same RGB across different backgrounds.

## Verification and limitations

**PASS**

- Demo/production debug Kotlin compilation and demo instrumentation compilation.
- Unit suites: **126/126 demo + 119/119 production = 245/245**.
- API 34 instrumentation: **8/8** — actual Home plus seven modal-policy fixtures.
  This is a new run, not the earlier 9/9 diagnostic including a blur-on experiment.
- Pixel/card/blur and restoration checks above; visual review of the system screenshot.
- **19 device/package preservation checks**: all pre-existing app/test APK hashes and
  device settings match this phase's baseline; temporary app/test packages removed.
- Protected source hashes for Home, active workout, registration and exercise selection
  match the pre-change baseline. Skill-package check and diff whitespace check pass.

**FAIL — existing lint baseline:** demo/production lint still report the two unrelated
`NonObservableLocale` errors in `AuthBirthDatePicker.kt:48` and `RegisterScreen.kt:158`.
No suppression or out-of-scope locale fix was applied.

**NOT_TESTED / BLOCKED:** API 29/30/31/33/35+, physical Realme/OEM rendering, original
affected APKs/screenshots, production-flavor runtime, release runtime, full rest-timer
countdown, rotation/process death, TalkBack and user visual review of the new intensity.
Policy tests cover light/dark window attributes; pixel measurements are light Home only.
The implementation uses existing native dim APIs, but a cross-API visual guarantee is
not claimed from this single-device run.

Commands:

```bash
./gradlew :app:compileDemoDebugKotlin :app:compileProductionDebugKotlin \
  :app:testDemoDebugUnitTest :app:testProductionDebugUnitTest \
  :app:compileDemoDebugAndroidTestKotlin --console=plain
./gradlew :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest \
  -PmodalDiagnostic=true --console=plain
adb -s emulator-5554 shell am instrument -w -r \
  -e class com.diajarkoding.imfit.presentation.components.common.HomeBackdropDiagnosticTest,com.diajarkoding.imfit.presentation.components.common.ModalDimTest \
  -e apkSha256 211ba05473a8d75b788e6d785506d44dc505471dbd7b62fc923e403f8f2a1814 \
  com.diajarkoding.imfit.demo.modaldiagnostic.test/androidx.test.runner.AndroidJUnitRunner
./gradlew :app:lintDemoDebug :app:lintProductionDebug --continue --console=plain
```

Evidence directory:

`/private/var/folders/yv/171k2lgd0jdg4y0jclvxjtzw0000gn/T/opencode/imfit-dialog-dim60-20261018/`

Contains baseline source/status/hashes, APKs, `apks.json`, installed-APK hash,
`home-backdrop-diagnostic/report.json` and PNGs, matched-region measurements,
instrumentation/build/unit/lint logs, `unit-summary.json` and `restoration-checks.json`.
