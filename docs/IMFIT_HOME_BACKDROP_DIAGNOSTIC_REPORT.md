# Backdrop diagnosis — actual Home route, API 34

18 October 2026. **ROOT CAUSE NOT CONFIRMED for the original screenshots.**
The missing native dim was **not reproduced on actual Home**. A difference between
system-composited and Activity-only Compose captures **was reproduced and measured**.
No backdrop fix or policy change was made.

## Scope and APK identity

- Source: `redisgn` HEAD `3ec72efbf43dc8b749aa3e4ae413a4a0373d8fa7` plus existing
  uncommitted modal/Profile work. All unrelated work was retained.
- API 34 emulator, 1080×2340 px, density 440 dpi, light mode. Fingerprint:
  `google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.050/12077443:userdebug/dev-keys`.
- Actual `MainActivity → NavGraph → MainScreen → HomeScreen → RoutineDialog`, opened
  with Home's Add routine action. The test does not replace `setContent`, render a copy
  of Home, change autofocus, create a routine, or access a production account.
- Build: `demoDebug -PmodalDiagnostic=true`, package
  `com.diajarkoding.imfit.demo.modaldiagnostic`. Demo uses synthetic data.
- One app APK for actual Home, isolated RoutineDialog blur-off/on, and policy tests:
  SHA-256 `cb1d8f74771b4ade29a164756918c12a7d84d60032f92130fd1d3f370d2cf781`.
  Its installed hash was independently checked before cleanup. Only test APKs changed
  while refining fixture selectors/assertions.
- The normally installed demo at the start of this run had SHA-256
  `9d0d219da96dc7353c96a8c834243a294ed5733d5920cf1f1ce08763fb2a6573`.
  This is different from both the earlier diagnostic APK and earlier installed-demo
  baseline. Read-only APK disassembly confirms that this installed demo's
  `IMFITDialogBackdrop` default is `0.28f`. This does not establish that it produced
  either original screenshot, nor identify the Realme APK's default.

The screenshot RGB values cited in the user's analysis are user-provided evidence;
the original image files/capture provenance and original Realme APK were not supplied
for independent measurement in this phase. Approximately 60% dim on Realme is plausible
only assuming the same homogeneous background, black overlay and unmodified image.
Do not equate that estimate with a proven token value, native theme value or stacked dim.

## Diagnostic changes, not a rendering fix

`IMFITPlatformDialog.kt` retains the direct-parent lookup, original `DisposableEffect`
keys, dim assignment and cleanup. A nullable `DialogBackdropDiagnosticBridge` allows
instrumentation to connect the existing observer point to the real Activity tree.
The fallback is read only for debug builds with the isolated `.modaldiagnostic` suffix;
normal app builds do not invoke it or emit diagnostic logs. Composition-local observers
still take precedence. The test registers before opening the dialog and clears it in
`finally`.

`HomeBackdropDiagnosticTest.kt` observes only. It does not set dim/blur, repair a null
window, traverse to choose another window, change themes, add scrims or upgrade Compose.
Traversal is compared solely as diagnostic evidence. `MainActivity`, navigation,
`HomeScreen`, timer/workout logic, and Pencil were not edited in this phase.

## Procedure

1. Navigate normally to Home and scroll its Add routine action into view. Save the
   Activity window flags/dim and capture the background without a dialog.
2. Tap Add routine. Record **T0** immediately after the existing assignment.
3. Wait for Compose/UI idle and rendered-frame barriers. Record **T1-autofocus** with
   the actual Home autofocus and visible IME. Capture the whole display using
   `UiAutomation.takeScreenshot()`.
4. Hide only this fixture's keyboard; record/capture **T1-ime-hidden**.
5. Replace the actual field's input three times through Compose text semantics.
   Record **T2.1–T2.3**, assert the draft text, then capture T2. No Save/Create/IME Done
   action is invoked.
6. Tap outside through native input; verify dismissal and unchanged Activity window.
   Reopen through Home, verify empty `EditableText` after dismiss, then cancel normally.
7. At every capture stage also use `captureToImage()` on the Activity Compose root,
   identified by the root-tab label, and capture dialog Compose content separately.
   These are intentionally **not interchangeable** with a system screenshot.
8. Run the existing isolated dialog blur-off/on diagnostic and seven policy tests on
   the **same app APK**. The Home diagnostic never changes blur.

No fixed sleeps or device density/font/theme/animation changes were used. System and
Compose captures are sequential at stable state, not literally simultaneous frames;
ROIs avoid animated content, the dialog, IME and system bars.

## Actual Home results

| Observation | T0 | T1, IME shown/hidden | T2.1–T2.3 |
| --- | --- | --- | --- |
| Direct-parent provider/window | Found | Found | Found |
| Traversal resolves the same window | Yes | Yes | Yes |
| `FLAG_DIM_BEHIND` | true | true | true |
| `dimAmount` | 0.28 | 0.28 | 0.28 |
| Window identity, first opening | 99547004 | 99547004 | 99547004 |
| Blur flag / radius | false / 0 px | false / 0 px | false / 0 px |

The first opening produced one observer callback, not one per character. The second
opening produced one more callback and a new window identity. Initial window focus
was false at T0 and true after rendering; dim remained active through that transition.

Matched **8×8 px ROIs** at screen coordinates **(22,385), (22,495), (22,605)** all returned:

| Capture method | Baseline | T1 autofocus | T1 IME hidden | T2 | After dismiss |
| --- | --- | --- | --- | --- | --- |
| System screenshot | 247,249,252 | 178,180,182 | 178,180,182 | 178,180,182 | 247,249,252 |
| Activity Compose capture | 247,249,252 | 247,249,252 | 247,249,252 | 247,249,252 | 247,249,252 |

System pixels are consistent with a single black dim of 28% within 8-bit quantization.
The Activity capture contains neither the separate dialog window nor its native dim
layer. Separate dialog-content captures also exist; they must not be presented as
whole-system screenshots. No composite image was substituted for system evidence.

**Interpretation:** actual Home does not support a window-resolution, lifecycle or
flag-overwrite failure in this tested binary/device. Capture method demonstrably
explains a bright Activity-only image while the real display is dimmed. It is a
candidate explanation for the original screenshot, **not a confirmed explanation**
without its capture method and APK identity.

## Same-APK isolated comparison

The existing isolated `RoutineBackdropDiagnosticTest` still passes both modes:

| Observation | Blur off | Blur on |
| --- | --- | --- |
| Native dim amount / flag at T0/T1/T2 | 0.28 / true | 0.28 / true |
| White ROI baseline → T1 → T2 | 255 → 184 → 184 | 255 → 184 → 184 |
| Card RGB | 255,255,255 | 255,255,255 |
| Checkerboard standard deviation | 92 | 0 |

Thus the same binary dims both isolated and actual-Home backgrounds. Different ROI
base colors explain 184 versus 178/180/182; compare normalized dim, not raw channel
values from unrelated surfaces. This is not an Android 10/API 34 matched-APK comparison.

## Verification, evidence and cleanup

**PASS:**

- Isolated build and final instrumentation **9/9**: actual Home diagnostic, existing
  isolated two-mode diagnostic and seven modal-policy tests.
- Demo/production debug Kotlin compilation, demo instrumentation compilation, unit
  suites **125/125 demo + 118/118 production**.
- All three Home ROI checks, T0/T1/T2 observations, native outside-dismiss, draft
  dismissal/reopen and Activity attribute restoration.
- **19 restoration checks**: all three pre-existing app/test APK hashes, user, blur,
  brightness, rotation/font, animation/stay-on settings, density, resolution and night
  mode unchanged; both temporary diagnostic packages removed. No original app data was
  cleared or APK replaced. Current baseline was preserved, not an older saved APK.

**Initial fixture failures retained:** two attempts used ambiguous Compose root
selectors (the dialog marker is a descendant; Home action and dialog title also share
text). A third reached all screenshot stages but its empty-field assertion included
placeholder text. Test selectors/assertions were corrected to use the root-tab label
and `EditableText`. No app/window behavior was changed to make these pass. These runs
are failures, not passing acceptance evidence; final acceptance is the separate 9/9 run.

**NOT_TESTED / BLOCKED:** original screenshot capture method, original affected APKs,
Realme/API 29 or API 30/31 matched-binary run, production-flavor runtime, physical OEM,
rotation/process death, full Home with blur enabled and timer countdown. Full lint was
not rerun in this diagnostic-only phase; the two previously documented unrelated
`NonObservableLocale` failures remain unresolved. Unit/instrumentation PASS is not a
lint PASS or proof of universal platform behavior.

Commands:

```bash
./gradlew :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest \
  -PmodalDiagnostic=true --console=plain

adb -s emulator-5554 shell am instrument -w -r \
  -e class com.diajarkoding.imfit.presentation.components.common.HomeBackdropDiagnosticTest,com.diajarkoding.imfit.presentation.components.common.RoutineBackdropDiagnosticTest,com.diajarkoding.imfit.presentation.components.common.ModalDimTest \
  -e apkSha256 cb1d8f74771b4ade29a164756918c12a7d84d60032f92130fd1d3f370d2cf781 \
  com.diajarkoding.imfit.demo.modaldiagnostic.test/androidx.test.runner.AndroidJUnitRunner

./gradlew :app:compileDemoDebugKotlin :app:compileProductionDebugKotlin \
  :app:testDemoDebugUnitTest :app:testProductionDebugUnitTest \
  :app:compileDemoDebugAndroidTestKotlin --console=plain
```

Evidence directory:

`/private/var/folders/yv/171k2lgd0jdg4y0jclvxjtzw0000gn/T/opencode/imfit-home-backdrop-20261018/`

- `final/home-backdrop-diagnostic/report.json`, system/Activity/dialog PNGs.
- `final/modal-diagnostic/report.json` and same-APK blur-off/on screenshots.
- `instrumentation-final.txt`, initial run logs and `run-1/`, `run-2/`, `run-3/`.
- Saved APKs, `diagnostic-apks.json`, `installed-diagnostic-sha256.txt`, evidence hashes.
- `baseline.json`, read-only installed-demo disassembly, compile/unit logs/summary and
  `restoration-checks.json`.

## Decision

Do **not** replace direct-parent lookup, reapply dim after rendering, add a Compose
scrim or change dialog width based on these results. No such fix is supported here.
Next obtain original screenshot provenance and both original APK hashes/builds; repeat
with a system screenshot on the affected device if the visible symptom persists.
The Realme approximate 60% versus current 28% remains an unresolved build/policy
comparison, not evidence that API 34 disables dim.

The separately approved conditional-blur policy remains pending; this diagnostic
preserves the current dim-only working tree. No commit/push was performed.

## Final audit and subsequent design decision

The user accepted this diagnostic: **API 34 Home Diagnostic CLOSED — PASS**;
**Original Screenshot Investigation OPEN**. The findings and 28% measurements above
remain historical evidence. A separate final design decision now supersedes the
previous conditional-blur proposal: wrapper-based dialogs use 60% native dim with no
blur. See the [60% policy implementation report](IMFIT_DIALOG_BACKDROP_POLICY_REPORT.md)
for its scope, new APK, tests and pixel measurements. This is not a root-cause patch.
