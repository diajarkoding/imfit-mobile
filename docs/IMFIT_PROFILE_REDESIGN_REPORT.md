# Profile redesign — Pencil implementation

Date: 18 October 2026. Branch: `redisgn`, HEAD `3ec72efbf43dc8b749aa3e4ae413a4a0373d8fa7`
plus uncommitted work. No commit or push was requested.

## Approved source and scope

Read the actual nodes in `imfit.pen` through Pencil MCP; do not read the encrypted file
through the filesystem. Implement these references without editing the design:

| Node | Android presentation |
| --- | --- |
| `NWdx5` | Light / Indonesian content |
| `nD10u` | Dark content |
| `M9lMH` | English content |
| `BP1O0` | 320 dp / font 130%, long identity, stacked header |
| `HJCP9` | Initial loading skeleton; preferences/logout remain available |
| `G9W2eh` | Unavailable profile, contextual error and retry |
| `KCR2P` | Avatar fallback and missing birth date |

Use the current nodes, including their identity header without an account eyebrow or
gradient card. The original user's unrelated, uncommitted modal/diagnostic/skill work
was retained. This task does not implement or revise modal backdrop policy.

## Files and visual decisions

- `presentation/ui/profile/ProfileScreen.kt`: route collects the existing
  `ProgressViewModel`; one stateless renderer is used by runtime, previews and tests.
- `ProfileComponents.kt`: identity, information, preferences, logout, loading and error
  use `IMFITColors.training`, shared typography/spacing/radii, 20 dp gutters, 72 dp avatar,
  14 dp outlined cards, 76/88 dp minimum information/preference rows and a 54 dp logout CTA.
- `ProfilePresentation.kt`: view-only state mapping and locale-aware ISO birth-date
  formatting. Missing values use an em dash; non-ISO legacy text is kept unchanged.
- `IMFITProfilePhoto.kt`: optional placeholder brush/tint let Profile reuse Coil/cache
  behavior with a solid semantic fallback. Defaults preserve other callers' gradient
  and foreground. A placeholder test tag verifies the actual failed-image fallback.
- `values/profile_strings.xml` and `values-in/profile_strings.xml`: Profile-owned ID/EN
  labels, error/retry/loading and logout copy. Shared labels used by other screens are
  not globally recased or replaced.
- `ProfilePresentationTest.kt` and `ProfileScreenTest.kt`: pure mapping/formatting tests,
  seven-node screenshot matrix, callbacks, retry, photo failure and compact text layout.

Use the existing task `RoutineAppBar`, not the root-tab app bar. The top bar owns status
insets; Scaffold owns bottom navigation insets. There is no duplicate app navigation.
The identity stacks when its content width is below 320 dp or font scale exceeds 1.3.
Text wraps without ellipsis; controls use minimum rather than fixed text-container heights.
Native icon assets are reused; no icon dependency or generated artwork was introduced.

## State and behavior contracts

| State | Presentation / action |
| --- | --- |
| Initial loading, no identity | Skeleton, accessible loading description |
| Known identity, including reload | Keep information visible |
| `hasError` without identity, no loading | Error card; retry delegates to existing `loadData()` |
| `hasError` with identity | Keep content: the error may belong to the shared history observer |
| Legitimate empty/guest | Content/fallback, not an invented network failure |
| Missing or failed photo | Existing photo loader's placeholder, styled for Profile |
| Null/blank birth date | Em dash; no fabricated date |
| Theme | Existing toggle callback; whole preference row has switch semantics |
| Language | ID/EN radio options, each at least 48 dp; selecting the current option is a no-op |
| Logout | Existing `viewModel.logout(onLogout)`; no new confirmation or auth policy |
| Back | Existing caller callback/backstack |

The `Profile` entry in `NavGraph` and `ProgressViewModel` were not changed. No repository,
Room query, persistence, sync, timer, auth rule, edit-photo action or profile editing was
added. Filter/no-results/form validation/submission states are N/A for this read-only
screen. There is no existing logout-progress/error state; this task does not invent one.

## Verification and limitations

**PASS**

- Demo and production debug Kotlin compilation; demo instrumentation compilation.
- Full unit suites: demo **125/125**, production **118/118** (243 total), including
  12 new Profile presentation/formatting cases in each flavor.
- API 34 instrumentation: **7/7** — six isolated Profile UI tests plus the existing
  `RedesignNavigationTest#rootTabsSearchCategoryAndProfileUseRealDestinations` smoke test.
- All seven reference states captured; 320 dp with font 130% captured. Additional 150%
  font text-layout checks confirm that name/email have no width/height overflow.
- Actual image-error fallback, callback counts, selected-language no-op, retry loading,
  retained identity after history error/reload, and minimum language-option touch sizes.
- Diff whitespace checks and source review; Pencil references were exported for evidence,
  not changed.

The first UI run passed 5/6 tests. Its large-font check reported header-email paragraph
width overflow. Identity text now explicitly fills its column, matching Pencil's fixed-
width model, and the repeat run passed. Initial logs/images and layout diagnostics remain
in the evidence directory; the original failure was not discarded or declared a pass.

**FAIL — existing lint baseline:** demo/production lint still report
`NonObservableLocale` at `AuthBirthDatePicker.kt:48` and `RegisterScreen.kt:158`. These
pre-existing `Locale.getDefault()` reads are outside Profile and were not changed or
suppressed. No Profile-source lint error was reported. A new animated-offset warning was
addressed by using the placement-lambda overload.

**NOT_TESTED:** other Android versions/OEMs, physical device, TalkBack speech/focus order,
screen rotation/process death, real-account logout, successful remote photo delivery,
all locale/theme combinations, release runtime and user visual approval. The matrix uses
synthetic data and fixture-local density/locale/font configuration; it does not change
emulator preferences. Native system bars and line breaking differ from Pencil's
illustrative phone chrome; no pixel-perfect approval or full pixel-diff threshold is claimed.

Evidence:

`/private/var/folders/yv/171k2lgd0jdg4y0jclvxjtzw0000gn/T/opencode/imfit-profile-redesign-20261018/`

Contains reference PNGs in `pencil/`, final screenshots/layout data in `ui-final/`, initial
screenshots in `ui-run-1/`, instrumentation and compile/unit/lint logs, APK hashes and
baseline/restoration metadata. Runtime uses the existing opt-in isolated debug package
(`-PmodalDiagnostic=true`), not the installed demo/production package. No installed app
data is cleared. Temporary fixture packages are removed after evidence collection.
