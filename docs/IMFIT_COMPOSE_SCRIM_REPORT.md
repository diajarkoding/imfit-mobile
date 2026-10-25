# Shared Compose scrim — dialogs, sheets and sync

18 October 2026. `redisgn` HEAD `3ec72efbf43dc8b749aa3e4ae413a4a0373d8fa7` plus
retained, uncommitted work. This is the user's approved replacement backdrop mechanism,
not a claim that the earlier emulator/native-rendering discrepancy has been diagnosed.

## Final contract

- One token: **`IMFITOpacity.scrim = 0.50f`**. Old dialog/sheet intensity tokens are removed.
- Wrapper-based dialogs: a fullscreen **Compose** black scrim, not native window dim.
- Both Material bottom sheets: their existing `scrimColor` uses the same token.
- Fullscreen initial-sync overlay: existing Compose background uses the same token.
- No blur; no extra native dim underneath the dialog scrim.
- Keep content, draft, input, dismissal/back, navigation, timer and sync behavior.

Android-owned permission/camera/document-picker windows are not app-owned backdrops.
Popup menus are not converted into additional modal scrims.

## Implementation

`IMFITPlatformDialog.kt` retains one native Compose Dialog for window focus, back and
security. The host now fills the screen (`usePlatformDefaultWidth=false`, edge-to-edge),
so its Compose scrim can cover the background including system-bar areas. Only content
receives safe-drawing/IME padding. Caller content width constraints and theme surfaces
remain. For the default-width API option, platform theme width / the framework
`config_prefDialogWidth` fallback constrain content, not the fullscreen scrim.

Outside taps invoke the latest dismiss callback only when `dismissOnClickOutside` is
enabled. Blank content/card taps are consumed without dismissing or intercepting child
controls. Native dismiss-on-back, secure policy, title, window type and token are
forwarded. The previous parent Context/Resources/Configuration/Density propagation is
retained; existing user work was not replaced with an older wrapper snapshot.

`IMFITDialogWindowEffects` clears native dim, sets dim amount to zero and disables native
blur while mounted. Disposal restores only the owned attributes/flags, leaving unrelated
window attributes intact. The opt-in diagnostic observer remains read-only. Attributes
are applied once per window, not per input character/countdown tick.

`RegisterScreen.kt` and `ExerciseSelectionScreen.kt` change only the sheet token reference.
`SyncProgressDialog.kt` already references `IMFITOpacity.scrim`; its opacity changes
through that token without altering sync code. No ViewModel, timer, repository/domain,
Pencil, dependency or Compose version changed in this phase.

Tests:

- `ModalScrimTest`: six actual component types × light/dark; outside/inside taps; native
  default-width comparison. Includes birth-date picker, routine/message dialogs, photo
  and filter sheets, and synthetic sync state.
- `RegisterScrimRouteTest`: actual MainActivity navigation through Profile/logout/Login
  into Register, then calendar and photo sheet; retains synthetic name and uncommitted
  date on cancel. No form submission, production account, camera/gallery operation or
  backend sync. The test restores its in-process synthetic demo session in `finally`.
- Existing modal/native-cleanup, actual Home and routine diagnostic fixtures now assert
  Compose-scrim policy. The old experimental blur-on loop is removed; its saved historical
  source/results remain in the earlier evidence directories.

## Runtime evidence — API 34

Isolated package `com.diajarkoding.imfit.demo.modaldiagnostic`, 1080×2340, density 440 dpi.
Final app APK SHA-256:
`074d106d0a77c1903281b45d9519b493ef5e0861db17532cc97b870a9b047c2e`.

| Check | Result |
| --- | --- |
| Actual Register baseline RGB | 247,249,252 |
| Actual Register calendar backdrop RGB | **123,124,126** |
| Actual Register photo-sheet backdrop RGB | **123,124,126** |
| Actual Home T1/T2 backdrop RGB | **123,124,126**, with IME shown/hidden |
| Actual Home white card RGB | **255,255,255** |
| Actual Home after dismiss | 247,249,252 |
| Dialog native dim flag / amount, T0/T1/T2 | **off / 0** |
| Native blur flag / radius | **off / 0 px** |
| Six-type component matrix, light/dark | White-reference RGB **127,127,127** throughout |
| Dialog Compose scrim bounds | Full 1080×2340 window |
| Legacy default-width fixture | Native and replacement content both **880 px** |

Small rounding differences from baseline×0.5 are expected in 8-bit alpha composition.
Pixel checks use system screenshots, with separate Activity/dialog captures where useful.
Calendar content bounds are (0,281)–(1080,2064), compared with the pre-migration installed
native calendar window (0,280)–(1080,2063): same size, 1 px centering rounding difference.
The calendar's 12 dp content margin and internal layout were not changed.

**PASS:** final instrumentation **13/13**; demo/production debug Kotlin compilation;
demo instrumentation compilation; unit suites **153/153 demo + 146/146 production = 299**;
source-scope review and whitespace/skill-package checks.

**Initial failures retained:** first run found default-width growth (880→1080 px), which
was corrected using the actual framework preferred-width fallback. Back-input fixtures
needed Compose idle before native input and system-event synchronization after input.
The added Register route test initially left the shared synthetic process logged out,
causing the subsequent Home fixture to time out; its cleanup now restores demo auth.
Initial runs remain failures in the evidence directory, not passing acceptance evidence.
Only the separate final 13/13 run is acceptance evidence.

**FAIL — existing lint baseline:** both flavor lint tasks still report the two unrelated
`NonObservableLocale` errors at `AuthBirthDatePicker.kt:48` and `RegisterScreen.kt:158`.
No suppression or unrelated locale change was applied.

**NOT_TESTED:** user-observed host-emulator presentation/visual approval of this new APK,
physical Realme, other API levels, landscape/tablet platform widths, TalkBack, rotation/
process death, real sync/network work and complete active rest-timer countdown. A generic
countdown-style dismiss/skip fixture passes, not a timer-engine E2E test. Native/system
and Compose captures do not substitute for the user's direct visual review.
Existing sync text/foreground styling is unchanged; contrast against arbitrary underlying
content is not guaranteed by a fixed 50% scrim and needs separate accessibility review.

## Preservation and reproduction

All **18 package/device checks** pass: original demo/production APK hashes, user, font,
rotation, brightness, animation scales, blur setting, density, resolution and night mode
unchanged; temporary diagnostic packages removed. Existing app data was not cleared or
APKs replaced. The ordinary installed app therefore is not silently upgraded by this test.
Pre-existing timer, ViewModel, domain and repository source hashes are unchanged.
No commit/push was requested or performed.

Build: `./gradlew :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest -PmodalDiagnostic=true`.
Instrumentation classes (common package
`com.diajarkoding.imfit.presentation.components.common`): `ModalScrimTest`,
`HomeBackdropDiagnosticTest`, `ModalDimTest`, `RoutineBackdropDiagnosticTest`,
`RegisterScrimRouteTest`. Build/unit/lint commands and outputs are retained in logs.

Evidence:

`/private/var/folders/yv/171k2lgd0jdg4y0jclvxjtzw0000gn/T/opencode/imfit-compose-scrim50-20261018/`

Final component/Register PNGs and matrix in `final/compose-scrim-evidence/`; actual Home
and routine reports/screenshots in their corresponding `final/` subdirectories.
Also contains original installed-calendar reference, baseline source/status/hashes,
failed run evidence, final APK hashes, instrumentation/build/unit/lint logs and
`restoration-checks.json`. Earlier 28%/60% diagnostic reports remain historical evidence.
