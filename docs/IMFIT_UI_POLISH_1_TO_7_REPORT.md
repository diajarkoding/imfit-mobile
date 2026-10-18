# UI polish — approved points 1–7

18 October 2026. Branch `redisgn`, baseline HEAD
`3ec72efbf43dc8b749aa3e4ae413a4a0373d8fa7` plus pre-existing uncommitted work.
No commit/push. Existing redesign, Compose-scrim, timer and user changes were preserved.

**Later revision (19 October 2026):** the 12/0 dp spacing and token below are historical.
All page headers now use `headerContentGap = 8.dp`; Exercises no longer has an appbar
avatar. See [the separate revision report](IMFIT_HEADER_GAP_8DP_REPORT.md) for current
implementation and fresh verification. Commit/push was authorized after this phase.

## Implemented scope

| Point | Implementation / behavior |
| --- | --- |
| 1. Main top spacing | User revision: Home, Exercises and Progress use `IMFITSpacing.mainContentTop = 0.dp` under the existing shared app bar, replacing the initially approved 12 dp. Internal appbar padding/system insets remain. No second header/inset owner. |
| 2. Fit 12 categories | Measure available content viewport and localized label heights; calculate 4 rows at 3 columns (normal phone), or 6 at 2 columns (adaptive). Scale square artwork and use 8 dp row gaps. Remove the forced 154 dp minimum. All 12 fit on the API 34 test phone without scrolling or overlapping navigation. |
| 3. Toggle sizes | Login/Profile visual language/theme tracks use shared 72×36 dp sizes, with 30 dp thumb token and at least 48 dp touch targets. Login ID/EN remain separate radio choices; Profile retains row-owned switch actions. Theme/locale persistence and callbacks are unchanged. |
| 4. Remove black limitation warning | Remove automatic `rest_alert_limited` Snackbar and its alarm-settings action from ActiveWorkoutScreen, including the notification-denial callback (which is moved out). Do not remove storage, mutation or pause errors. Rare service-start/manual-settings failures retain separate short contextual error messages. |
| 5. Notification consent before session | New session Start waits for the permission result before navigating to ActiveWorkout and creating its session. Allow or Deny both continue; notification consent is not a workout prerequisite. No prompt when granted, already asked/previously denied, or resuming an existing session. |
| 6. Add exercise border | Detail, Edit and Active-session outlined Add exercise actions opt into matching border/content color. Other outlined buttons are unchanged. |
| 7. Remote-name quotes | All three auth metadata name fallbacks read JSON string content instead of `JsonElement.toString()`/manual quote removal. `Cecep` is decoded without JSON wrapper quotes; valid inner quotes, Unicode and escapes are preserved. |

Grid accessibility fallback: if the remaining screen cannot hold minimum artwork plus
all measured labels, it scrolls normally. It does not force smaller text or clipped
names to claim all categories fit at every font scale/window height.

## Source and state ownership

- `theme/DesignSystem.kt`: shared top-spacing and preference-size tokens.
- `HomeScreen.kt`, `ProgressScreen.kt`, `ImfitMainAppBar.kt`: shared spacing and stable
  test tags; state, shell/backstack and loading/empty/error rendering remain intact.
- `ExerciseBrowserScreen.kt`, `CategoryGridLayout.kt`: layout-only fitting logic.
  Category IDs, assets, filters and navigation are unchanged. No catalog query added.
- `AuthComponents.kt`, `ProfileComponents.kt`: tokenized control sizes. Profile row
  semantics and Login selected-language no-op behavior are retained.
- `ImfitRoutineComponents.kt` and the three workout screen callers: opt-in matching
  outline with the effective enabled/disabled content color, not a global recolor.
- `WorkoutNotificationPermission.kt`: UI permission/start coordinator, used in
  `WorkoutDetailScreen.kt`. The notification launcher is removed from ActiveWorkout.
- `AuthMetadata.kt`, `AuthRepositoryImpl.kt`: typed metadata decoding only. Existing
  profile DTO values are not blindly stripped; no remote-data migration, auth-rule or
  backend operation was executed. Missing/null/blank/non-string metadata uses `User`.
- New ID/EN `workout_permission_strings.xml`: meaningful service/settings errors.

The permission helper persists an installation-scoped `asked_before_session` marker in
`workout_notification_permission`. It also respects Android's rationale signal for a
denial made before this build had that marker. It marks before launch, saves pending
request state, uses the latest callback and disables conflicting Start presses. On a
permission result it continues the pending start only once. Active-session resume and
notification-entry paths do not trigger a new permission request. Returning to Detail
resets the local navigation guard on resume.

Only Android 13+ requests `POST_NOTIFICATIONS`; older versions bypass it. This permission
does not grant exact-alarm, notification-channel or full-screen privileges. Denial can
prevent normal session/rest notifications, but does not itself prohibit a foreground
service; its required service notification handling remains unchanged. Users can later
change notification permission in Android Settings; no automatic Settings redirect or
repeated nag was added.

**Protected source:** timer coordinator/codec/runtime, foreground service, all ViewModels,
navigation graph, sync algorithms, schemas and backend policy were not modified in this
phase. Existing changes in those files belong to the baseline and were not reverted.

## Verification

**PASS**

- Demo/production debug Kotlin compilation and demo instrumentation compilation.
- Full unit suites: **164/164 demo + 157/157 production = 321/321**. New pure tests cover
  grid fit/fallback geometry, notification-request decisions and safe JSON-name decoding.
- API 34 synthetic instrumentation: **17/17 total**, executed as an 11-test UI group
  plus six separately prepared notification cases on the same final app APK.
  - Initial point 1–7 tests measured 12 dp; the subsequent user revision requires a separate 0 dp check (see revision below).
  - All 12 category bounds are inside the grid viewport; short 320 dp / font 150% fixture
    scrolls to the final category rather than clipping it.
  - Login compact/large-font targets, Profile state/callback matrix, and Add exercise
    primary border pixels pass in light/dark fixtures.
  - Allow and Deny: system permission popup is on **routine detail**, and repository
    active session is **null until the result**. Deny still starts the session with no
    old limitation message.
  - Already granted, saved denial and older-build system denial do not prompt.
  - Actual Home resume keeps the existing session ID and shows no permission popup.
- **15 preservation checks**: ordinary installed APK hashes, user and recorded device
  settings unchanged; both temporary diagnostic packages removed. Original app data was
  not cleared or APKs replaced. Permission grant/revoke/flags affect only the disposable
  `.modaldiagnostic` package, before individual instrumentation invocations.
- Scoped diff review, shared-resource parity, skill-package and whitespace checks.

Final demo-debug diagnostic APK SHA-256:
`127164e3850d3fad5a240d219c5845b08f14e3729b89b22c290437c69e032b42`.

Initial failures remain in evidence: the resume fixture originally expected an already
open Detail to update after externally seeding a session, then waited for a Home greeting
that was outside its retained lazy-list viewport. The fixture now uses the actual Home
resume affordance. The first border capture sampled the system-bar region; its isolated
fixture now applies explicit edge-to-edge/inset placement. No production behavior was
changed to make those assertions pass. An initial combined Gradle run timed out during
lint under concurrent instrumentation; the final full command completed separately.

**FAIL — pre-existing lint baseline:** demo/production lint still report two
`NonObservableLocale` errors at `AuthBirthDatePicker.kt:48` and `RegisterScreen.kt:158`.
They were not suppressed or changed. The new notification API use has an explicit API
33 guard; no permission-related lint error is introduced.

**NOT_TESTED:** physical Realme/OEM behavior, other API runtime versions, real Supabase
accounts/data, process death/rotation during permission UI, rapid-input stress, exact
alarm/full-screen permission flows, complete rest-timer E2E and TalkBack speech/focus.
Unit policy cases cover pre-33 logic; this is not a cross-API runtime claim. Existing
stored profile names containing literal quotes are not automatically rewritten.

## Commands and evidence

```bash
./gradlew :app:compileDemoDebugKotlin :app:compileProductionDebugKotlin \
  :app:testDemoDebugUnitTest :app:testProductionDebugUnitTest \
  :app:compileDemoDebugAndroidTestKotlin --console=plain
./gradlew :app:assembleDemoDebug :app:assembleDemoDebugAndroidTest \
  -PmodalDiagnostic=true --console=plain
./gradlew :app:lintDemoDebug :app:lintProductionDebug --continue --console=plain
```

UI classes: `MainPolishTest`, `ExerciseGridFitTest`, `AuthPreferencesTest`,
`ProfileScreenTest`, `AddExerciseOutlineTest`. Notification cases:
`WorkoutStartPermissionRouteTest`, one method per instrumentation invocation, with the
isolated package initially denied and user-set/user-fixed flags cleared. The older-build
denial case sets user-set on that isolated package. Do not revoke permission inside the
instrumented process: Android may kill it. Full invocation logs are retained.

Evidence directory:

`/private/var/folders/yv/171k2lgd0jdg4y0jclvxjtzw0000gn/T/opencode/imfit-polish-1to7-20261018/`

Contains baseline diff/status/hashes, APKs and hashes, final tab/permission/Profile PNGs,
failed fixture logs, final UI/permission results, build/unit/lint logs, unit summary and
`restoration-checks.json`. The installed ordinary app is intentionally not upgraded.

## Point 1 revision: zero additional main-content gap

The user subsequently replaced 12 dp with **0 dp** on Home, Exercises and Progress.
The same shared token is updated; appbar internal padding and system insets are retained.
`MainPolishTest` now explicitly expects zero gap and still checks all 12 categories fit.
The 17-test evidence and APK above describe the initial 12 dp build; the revision's
new APK/test evidence is recorded separately rather than retroactively relabeled.

Revision verification: **PASS** demo/production compilation, demo instrumentation
compilation, **321/321 unit tests**, and the dedicated API 34 `MainPolishTest` **1/1**
(zero gap on all three tabs, all 12 categories inside the viewport). The other point
1–7 runtime scenarios were not rerun for this token-only revision; their earlier
17/17 results remain separate evidence. Ordinary app APKs/settings remain unchanged.

Revision app APK SHA-256:
`717fd686090bef48fee477a0f05b552c7f3017db35fbcc3ea9851d796ff936e8`.
Evidence: `revision-zero-gap/` under the directory above, including APK hashes,
build/unit and instrumentation logs, and three new tab screenshots. The isolated
app/test packages were removed after the check.
