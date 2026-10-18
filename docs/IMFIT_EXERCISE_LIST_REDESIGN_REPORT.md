# Exercise category list redesign

Date: 18 October 2026. Existing uncommitted work retained; no commit or push.

## Scope and reference

Active route: `MainScreen` exercise category → `ExerciseList(categoryName)` in
`presentation/navigation/NavGraph.kt` → `presentation/ui/exercise/ExerciseListScreen.kt`.
Reference: the current `ExercisePickerContent`/`ExercisePickerRow` in
`presentation/ui/exercise/ExerciseSelectionScreen.kt`, not a new Pencil mockup.

- Preserve back + category title, search, and a read-only exercise list.
- Use `RoutineAppBar`, Training palette, shared typography/spacing, 20 dp gutters,
  solid search field, 12 dp outlined cards, and 48 dp existing muscle artwork.
- Keep descriptions; names/descriptions wrap without truncation. Search stays visible
  while the results scroll, following the picker structure.
- No selection, checkbox, filter sheet, footer CTA, new detail route, or business rule.
- Search offers clear and keyboard Search dismissal. Back/clear targets remain ≥48 dp.
- App bar owns status inset; Scaffold owns navigation inset; IME padding is consumed.

## State contract

| State | UI / action |
| --- | --- |
| Loading (including query debounce) | Shared `RoutineLoading`; search/back remain available |
| Load failure | Shared `RoutineFailure`; retry delegates to existing `refresh()` with query intact |
| Category empty | Existing `exercise_no_results` message |
| Unmapped subgroup empty | Existing `exercise_subgroup_empty` message |
| Nonblank search without results | Existing `redesign_no_results`; clear delegates to `setQuery("")` |
| Content | Existing category predicate and ID deduplication; non-clickable outlined rows |
| Back | Existing graph callback; no result or mutation |

`ExerciseBrowserViewModel`, repository, 300 ms debounce, cancellation, query trimming,
sorting, subgroup mappings and navigation graph are unchanged. Submission/validation,
selection/save/delete, sync and active-session states are N/A for this read-only screen.
All UI copy uses existing ID/EN resources; catalog descriptions remain repository data.

## Files

- `app/src/main/java/com/diajarkoding/imfit/presentation/ui/exercise/ExerciseListScreen.kt`:
  runtime route, stateless content, local components and Hilt-free previews.
- `app/src/androidTestDemo/java/com/diajarkoding/imfit/presentation/ui/exercise/ExerciseListScreenTest.kt`:
  five synthetic UI tests; themes/locales, responsive text, states/actions, category
  mappings/deduplication, read-only semantics and long-list access.
- `app/src/androidTestDemo/java/com/diajarkoding/imfit/presentation/RedesignNavigationTest.kt`:
  locate search by stable tag now that it uses the picker's placeholder presentation.

## Verification

**PASS**

- `:app:compileDemoDebugKotlin :app:compileProductionDebugKotlin`.
- `:app:testDemoDebugUnitTest :app:testProductionDebugUnitTest`: 153 + 146 = **299 tests**, zero failures.
- `:app:connectedDemoDebugAndroidTest` with class filter
  `com.diajarkoding.imfit.presentation.ui.exercise.ExerciseListScreenTest,com.diajarkoding.imfit.presentation.RedesignNavigationTest#rootTabsSearchCategoryAndProfileUseRealDestinations`:
  **6/6**, Pixel_5_API_34 / Android 14, `demoDebug`.
- Content matrix: light/dark × ID/EN × 412 dp/font 100% and 320 dp/font 150%.
  Additional captures: loading, error retaining query, three empty states, long text,
  final list row. Sample light, dark/compact, long-text and error captures were reviewed.
- Initial UI run: 5/6 passed; text layout reported overflow on the exercise name.
  Filling the text column width fixed the paragraph constraint; rerun passed 6/6.
- `git diff --check`.

**FAIL — existing lint errors outside scope**

`:app:lintDemoDebug` and `:app:lintProductionDebug` report `NonObservableLocale` in
`AuthBirthDatePicker.kt:48` and `RegisterScreen.kt:158`. Neither file was changed in this
task. No diagnostic references the exercise list screen or its new tests.

**NOT_TESTED**: physical devices, TalkBack traversal, landscape, production account/network
behavior. Instrumentation uses synthetic/demo data; screenshots are not visual approval.

Reports: `app/build/reports/androidTests/connected/debug/flavors/demo/index.html`,
`app/build/reports/tests/testDemoDebugUnitTest/`,
`app/build/reports/tests/testProductionDebugUnitTest/` and
`app/build/reports/lint-results-{demoDebug,productionDebug}.html`.

Screenshots (15):
`app/build/outputs/connected_android_test_additional_output/demoDebugAndroidTest/connected/Pixel_5_API_34(AVD) - 14/exercise-list-redesign-evidence/`.
