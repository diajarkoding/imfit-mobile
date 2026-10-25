# Workout History Detail (Ringkasan Hari) redesign — Pencil implementation

Date: 18 October 2026. Branch: `redisgn`.

## Approved source and scope

Read the actual nodes in `imfit.pen` through Pencil MCP; do not read the encrypted file
through the filesystem. Implement these references without editing the design:

| Node | Android presentation |
| --- | --- |
| `a1m64P` | H01 · Ringkasan Hari / Light / 2 sesi |
| `U6dXe5` | H02 · Ringkasan Hari / Dark / 2 sesi |
| `gUWYo` | H03 · Daily summary / Light / EN |
| `C0SWr0` | H04 · Ringkasan Hari / 320dp / teks 130% · stacked layout |
| `TXJ9X` | H05 · Ringkasan Hari / Loading awal (skeleton) |
| `c47uVm` | H06 · Ringkasan Hari / Tanpa sesi (legitimate empty) |
| `uab4Z` | H07 · Ringkasan Hari / Gagal muat & retry (full error) |
| `eFGuU` | H08 · Ringkasan Hari / Data tersedia + update gagal (retained content + retry banner) |
| `ZKrsp` | History handoff title and design contract |

The user's unrelated, uncommitted work in working tree was retained. This task does not
modify database schema, navigation keys, or domain calculations.

## Files and visual decisions

- `presentation/ui/progress/WorkoutHistoryDetailScreen.kt`: route collects `ProgressViewModel`
  state via `collectAsStateWithLifecycle()` and triggers `viewModel.loadData()`. A stateless
  renderer `WorkoutHistoryDetailScreenContent` is shared by runtime, previews and tests.
- `presentation/ui/progress/WorkoutHistoryComponents.kt`:
  - `WorkoutHistoryDateHeader`: uppercase category context (`RIWAYAT LATIHAN`), local day of week, and full date.
  - `WorkoutHistoryDailyTotals`: 4 metric cards (duration, volume, exercise count, completed sets) with 2x2 grid in normal mode and full-width stacked rows in narrow/large font mode.
  - `WorkoutHistorySessionCard`: ordinal `Sesi X dari Y` (hidden for single session), template name, duration pill, volume/exercises/completed sets summary with centered column alignment, and clean direct exercise breakdown list with completed check icons (omitting redundant sub-heading).
  - `WorkoutHistoryLoadingSkeleton`: shimmering/static neutral skeleton cards avoiding empty-state flashes.
  - `WorkoutHistoryEmptyState`: clean calendar illustration container, friendly copy ("Belum ada sesi latihan"), no false error reporting.
  - `WorkoutHistoryErrorState`: full-screen error state with retry button delegating to `loadData()`.
  - `WorkoutHistoryUpdateFailureBanner`: non-blocking banner at top when update fails while keeping existing workout logs visible.
- `presentation/ui/progress/WorkoutHistoryPresentation.kt`: presentation mapping (`Loading`, `Empty`, `FullError`, `Content`), totals calculator, and locale-aware volume/date formatting.
- `res/values/workout_history_strings.xml` and `res/values-in/workout_history_strings.xml`: English and Indonesian strings and plurals (`workout_history_sessions_count`, `workout_history_exercise_sets_count`).
- `test/.../WorkoutHistoryPresentationTest.kt`: Unit tests verifying presentation state transitions, totals calculations matching Pencil illustration (45+20=65m, 3600+1200=4800kg, 3+2=5 exercises, 9+6=15 sets), volume locale formatting, and date headers.
- `androidTestDemo/.../WorkoutHistoryDetailScreenTest.kt`: Instrumentation test suite testing all 8 reference states, Back and Retry callbacks, single session ordinal hiding, and touch target accessibility (>= 48dp).

## State and behavior contracts

| State | Presentation / action |
| --- | --- |
| Initial loading without data | Skeleton cards, accessible loading description |
| Known workout data, reload | Keep content visible |
| Failed load without data | Full error card with primary retry button delegating to `viewModel.loadData()` |
| Failed update with existing data | Retained content with top warning banner and retry action |
| Legitimate empty (no sessions logged) | Clean empty state with calendar icon; does not claim sync error |
| Single session logged | Sesi ordinal hidden ("Sesi 1 dari 1" omitted) |
| Multiple sessions logged | "Sesi %1$d dari %2$d" displayed above session title |
| Back button | Standard `RoutineAppBar` back button with 48dp target calling `onNavigateBack` |

## Verification and evaluation

**PASS**
- Unit tests: Demo debug `testDemoDebugUnitTest` and Production debug `testProdDebugUnitTest` both PASS (including 11 new unit tests in `WorkoutHistoryPresentationTest`).
- Instrumentation tests: All 3 tests in `WorkoutHistoryDetailScreenTest` PASS on Android emulator (`emulator-5554`), including:
  - 8-node visual matrix check covering `H01`, `H02`, `H03`, `H04`, `H05`, `H06`, `H07`, `H08`.
  - Back button and both Retry buttons (full error and banner).
  - Single session ordinal suppression.
- Regression tests: `RedesignNavigationTest` (5/5 PASS on `emulator-5554`).
- Visual comparison: Screenshots captured directly from emulator and inspected against Pencil exports in `pencil/` and `ui-final/`.
- Touch target requirements: Interactive targets >= 48dp verified.

**FAIL**
- None in this scope.

**NOT_TESTED**
- TalkBack audio announcement order (semantics tags provided).
- Physical device testing across other OEM Android skins.
- User visual approval.
