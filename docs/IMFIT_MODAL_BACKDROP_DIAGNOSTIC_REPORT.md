# IMFIT modal backdrop — API 34 runtime diagnostic

Date: 18 October 2026. **ROOT CAUSE NOT CONFIRMED — reported missing-dim symptom not reproduced in this fixture.**

## Scope and baseline

Per the user's scope reduction, test the actual **Add routine** `RoutineDialog` and
`RoutineNameField`, not Rest Timer. The host is an isolated `ComponentActivity`, not the
full Home route. Autofocus is off, the name is synthetic, and Create never calls a
repository. T2 changes the name three times to cause recomposition; it is **not** a
countdown or timer-engine test.

- Source baseline: `redisgn` HEAD `3ec72efbf43dc8b749aa3e4ae413a4a0373d8fa7`, with the
  pre-existing uncommitted dim-only changes retained and saved in the evidence directory.
- Pixel 5 emulator, Android 14 / API 34; Compose UI 1.12.0, BOM 2026.08.00.
- Build: `demoDebug`, opt-in `-PmodalDiagnostic=true`, package
  `com.diajarkoding.imfit.demo.modaldiagnostic`.
- One app APK for both blur modes; SHA-256:
  `e68b62dbcf6338430726901d1a2730ece8cad92a0ff6bab6fed1123b4085b509`.
- A nullable, opt-in observer reports the exact `LocalView` and direct-parent window
  immediately after the existing dim assignment. With no observer, normal UI does not
  log, enable blur, or change its dim-only policy.
- Blur off/on is selected inside the instrumentation fixture on the same APK. It only
  changes native blur attributes; it does **not** set dim, add its flag, replace the
  direct-parent lookup, or add a manual scrim to make the test pass.

## Observation sequence

1. Capture an unchanged synthetic background with no dialog.
2. **T0-after-dim:** observe immediately after the app applies its window attributes.
3. **T0-after-blur-choice:** request native blur off/on while keeping dim untouched.
4. **T1:** wait for Compose idle, rendered-frame callbacks and UI idle; observe and capture
   the system-composited screen using `UiAutomation.takeScreenshot()`.
5. **T2.1–T2.3:** change the routine-name state, wait for rendering, and observe each
   recomposition. Capture again after the third change.
6. Tap the background action through native input. Verify the routine dialog dismisses
   normally but the background button receives no click. Check the Activity window is
   unchanged after closing the dialog.

The two scenarios start with the same name and background. Synchronization uses idle and
frame barriers rather than fixed sleeps. PNGs capture the whole composed display, not
only the dialog's Compose content, so the native dim layer is included.

## Results

| Check | Blur off | Blur on |
| --- | --- | --- |
| Direct-parent window found, T0/T1/T2 | PASS | PASS |
| Parent traversal resolves the same window | PASS | PASS |
| `FLAG_DIM_BEHIND` remains active | PASS | PASS |
| `dimAmount` remains 0.28 | PASS | PASS |
| Blur flag / radius | false / 0 px | true / 28 px (10 dp rounded) |
| System cross-window blur enabled | true | true |
| White background ROI, baseline → T1 → T2 | 255 → 184 → 184 | 255 → 184 → 184 |
| Dialog-card ROI at T1/T2 | RGB 255,255,255 | RGB 255,255,255 |
| Checkerboard standard deviation, T1/T2 | 92 | 0 |
| Native outside-dismiss; background clicks | PASS; 0 | PASS; 0 |
| Activity window flags/dim unchanged after close | PASS | PASS |

The reference ROI is the same homogeneous white area outside the dialog in every
screenshot, approximately x=22, y=1170 with an 11×11 px sample on this 1080×2340 display.
The change to `#B8B8B8` is consistent with black dim at 28%, allowing 8-bit quantization.
The independent white-card sample stays white. The sharp versus blurred checkerboard
and its measured variance confirm that blur actually rendered; it was not only a flag.

The observed hierarchy begins:

```text
AndroidComposeView → DialogLayout (DialogWindowProvider) → FrameLayout → …
```

In this runtime fixture, `(view.parent as? DialogWindowProvider)?.window` succeeds.
There is no evidence here that it needs replacing with traversal, that dim gets
overwritten, or that active blur removes dim. These findings apply to this build,
component and emulator, not every API 31+ device or the original affected APK.

## Verification and evidence

```bash
./gradlew :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest \
  -PmodalDiagnostic=true --console=plain

adb -s emulator-5554 shell am instrument -w -r \
  -e class com.diajarkoding.imfit.presentation.components.common.RoutineBackdropDiagnosticTest,com.diajarkoding.imfit.presentation.components.common.ModalDimTest \
  -e apkSha256 e68b62dbcf6338430726901d1a2730ece8cad92a0ff6bab6fed1123b4085b509 \
  com.diajarkoding.imfit.demo.modaldiagnostic.test/androidx.test.runner.AndroidJUnitRunner
```

**PASS:** diagnostic build and final instrumentation **8/8**, including the two-mode
diagnostic and seven existing modal-policy tests (window restoration, processing guard,
back dismissal, explicit skip and light/dark window attributes). The initial diagnostic
also passed; its evidence was preserved before resetting the draft identically between
scenarios and adding the native outside-click check for the final run.

Evidence directory:

`/private/var/folders/yv/171k2lgd0jdg4y0jclvxjtzw0000gn/T/opencode/imfit-modal-diagnostic-20261018/`

Key files: `report.json`, `instrumentation-final.txt`, `diagnostic-apks.json`, six
`light-blur-*.png` images, `image-hashes.json`, baseline/final source diffs, build logs,
and `restoration-checks.json`. `initial-run/` preserves the first run.

**FAIL:** none in the final API 34 diagnostic acceptance checks.

**NOT_TESTED / availability gap:** API 30 and API 31 comparison (no matching AVD found),
original affected APK/device, full Home route with autofocus/IME, Rest Timer countdown,
rest alerts, rotation and dark-mode pixel comparison. Existing API 35/36 AVDs were not
booted; no system images or dependencies were installed. This phase did not rerun lint
or unit tests; the prior unrelated locale-lint failures remain outside this diagnosis.

## Cleanup and decision

The isolated app/test packages were removed after evidence collection. The original
demo and demo-test APK hashes still match their saved baseline; neither package was
replaced or cleared. User 0, brightness, rotation/font settings, animation scales,
stay-on and the system blur setting were verified unchanged. No account, production
APK, network setting, timer engine, alarm, notification, FGS or workout logic changed.

Do not infer a fix from this passing reproduction attempt. Preserve the current policy
changes as separate work; no global blur restoration or speculative window/theme fix
was applied in this phase. The next useful comparison requires the affected APK/device
or an API 30/31 environment using these same saved APKs and fixture.

## Follow-up: actual Home route

The subsequent [actual Home diagnostic](IMFIT_HOME_BACKDROP_DIAGNOSTIC_REPORT.md)
uses a new, internally matched app APK for both actual Home and isolated fixtures.
It records T0/T1/T2, autofocus/IME, matched system versus Activity-only Compose captures,
and APK identities. The findings above remain the historical isolated-host results;
the follow-up does not retroactively confirm the original missing-dim root cause.
