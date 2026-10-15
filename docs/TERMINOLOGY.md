# IMFIT terminology — PLAN v2

## Canonical glossary

| Domain | Indonesia | English |
| --- | --- | --- |
| WorkoutTemplate | Rutinitas | Routine |
| Exercise | Gerakan | Exercise |
| TemplateExercise | Gerakan dalam rutinitas | Routine Exercise |
| WorkoutSession | Sesi latihan; Sesi when context is clear | Workout Session |
| WorkoutLog | Catatan sesi | Workout Log |
| Collection of logs | Riwayat latihan | Workout History |
| WorkoutSet | Set | Set |
| Repetition | Repetisi / Rep | Reps |
| Weight | Beban | Weight |
| Rest interval | Istirahat | Rest |
| Exercise catalog | Pustaka gerakan | Exercise Library |
| Calendar grouping | Tanggal | Date |

The lifecycle is **Routine → Workout Session → Workout Log → Workout History**. A routine can produce many logs. “Push Day”, “Pull Day”, “Leg Day” and user-authored names are free text, not scheduled dates. User names, catalog data and persisted routine/history names are never rewritten by this terminology change.

“Mulai latihan / Start Workout” is the natural main CTA. Pause, resume, finish and discard refer to the session. Remove Exercise means removing an item from a routine/session, not deleting a catalog entry. An empty calendar date means no logged session; it does not imply a prescribed rest day.

## Active resource and caller mapping

Paths in the following table are relative to `app/src/main`. Both `res/values` and `res/values-in` contain the localized counterpart.

| Resource key | Caller | Prior ID / EN | Final ID / EN |
| --- | --- | --- | --- |
| `home_my_workouts` | `HomeComponents.kt` | Latihan saya / My workouts | Rutinitas saya / My Routines |
| `home_add_day` | `HomeComponents.kt` | Tambah hari / Add day | Tambah rutinitas / Add Routine |
| `home_empty_templates` | `HomeScreen.kt` | No workouts; add a day | No routines; add a routine |
| `home_create_workout_title` | `HomeScreen.kt` | Buat Latihan / Create Workout | Buat rutinitas / Create Routine |
| `home_create_workout_message`, `home_workout_placeholder` | Home and Edit | Name a workout / day examples | Beri nama rutinitasmu / Name your routine |
| `workout_edit_title`, `workout_name_label` | `EditWorkoutScreen.kt` | Edit/Nama Latihan / Edit/Workout Name | Edit/Nama rutinitas / Edit Routine/Routine Name |
| `action_save_changes`, `action_save_template` | Edit, dedicated creation screen | Save Changes/Template | Simpan rutinitas / Save Routine |
| `template_*` creation/name/empty-state labels | `CreateTemplateScreen.kt` | Template / Latihan | Rutinitas / Gerakan; Routine / Exercise |
| `dialog_delete_workout`, `routine_delete_action` | `WorkoutDetailScreen.kt` | Hapus Latihan / Delete Workout | Hapus rutinitas? / Delete Routine? |
| `dialog_remove_exercise*` | Detail and Edit | Hapus Latihan; dari latihan ini | Hapus gerakan?; dari rutinitas ini / Remove Exercise?; from this routine |
| `workout_exercises_label`, `label_exercises` | Detail, Edit, History | Latihan / Exercises | Gerakan / Exercises |
| `action_add_exercise`, `active_session_add_exercises` | Detail, active session | Tambah Latihan | Tambah gerakan / Add Exercise |
| `exercise_select_title` | `ExerciseSelectionScreen.kt` | Pilih Latihan / Select Exercises | Pilih gerakan / Select Exercise |
| `exercise_search_hint`, `redesign_search` | Selector and catalog list | Cari latihan | Cari gerakan / Search Exercises |
| `nav_exercises`, `exercise_library_title` | `MainScreen.kt`, `ExerciseBrowserScreen.kt` | Hardcoded Exercise | Gerakan / Exercises; Pustaka gerakan / Exercise Library |
| `home_resume`, `action_resume` | Active Home card and session action | Lanjutkan / Resume | Lanjutkan sesi / Resume Workout |
| `action_pause` | Active session | Jeda / Pause | Jeda sesi / Pause Workout |
| `action_finish_workout` | Active session | Selesaikan Latihan | Selesaikan sesi / Finish Workout |
| `active_workout_cancel_title`, `action_cancel_workout` | Session discard dialog | Batalkan Latihan / Cancel Workout | Batalkan sesi / Discard Workout |
| `summary_rest_day`, `summary_no_workouts` | `WorkoutHistoryDetailScreen.kt` | Hari Istirahat / Rest Day | Belum ada sesi tercatat / No workouts logged |
| `summary_workouts_completed` | History summary | N latihan selesai | N sesi selesai / N workouts completed, quantity resource |
| `progress_overview_day_description`, `progress_overview_day_workouts` | Shared month calendar and selected-date summary | N latihan | N sesi tercatat / N workouts logged, quantity resources |
| `action_remove_set` | `EditWorkoutScreen.kt` | Hardcoded Remove set | Hapus set / Remove Set |
| `workout_sets_reps_format`, `template_exercise_sets` | Detail and creation preview | Hardcoded reps | Localized set/repetition format |

### Quantity resources

These active callers use `pluralStringResource`, retaining the existing base key while changing its resource type where necessary:

- `exercise_add_count`: selected catalog exercises, not sessions.
- `redesign_exercise_count`: routine exercise count.
- `workout_sets_configured`: configured set count.
- `summary_sets_count`: set count in recorded exercise summaries.
- `summary_workouts_completed`: number of logs for the selected date.
- `progress_overview_day_description`: accessibility description including date and session count.
- `progress_overview_day_workouts`: selected-date session count, duration and volume.

English has `one` and `other`; Indonesian uses `other`. Counts and statistical calculations are unchanged.

## Error and accessibility presentation

User-visible editor and active-session errors carry string resource IDs, resolved by the UI in the current locale. Technical exception details remain diagnostics, not raw English snackbar text. Session state transitions, mutation/cancellation behavior and save validation remain unchanged.

Shared generic actions (`action_create`, `action_delete`, etc.) retain their generic meanings. Routine creation/deletion confirmations use dedicated resources instead. Animation labels and diagnostic logs are not UI copy.

## Legacy audit / cleanup policy

No orphan resource deletion is part of this pass. `workout_add_day_*`, `workout_content_tab_day_details`, `workout_copy_day`, `workout_delete_day`, `workout_day_limit`, `home_title_my_workouts`, the older `history_*` labels and unused redesign-hero strings are retained: they are not evidence of a current schedule feature. Source-wide searches include Kotlin, XML, test and preview callers; further removal requires verified cross-module references and successful builds.

`summary_workouts_completed_plural` is retained as a legacy string; active history uses the quantity resource `summary_workouts_completed`. Named routine examples and user-authored routine names are not automatically localized or renamed.

## Regression scope

Build and unit tests cover both demo and production. Existing UI navigation tests use localized tab/action resources and quantity resources. Exercise-name fixtures remain data, not translated labels. Verify both locales, routine create/edit/delete, add/remove exercise, session actions, history/calendar counts and large-font text. This pass introduces no schema/API/serialized-route/domain rename, scheduling feature, statistic change or independent layout redesign.

### Verification performed

- Demo and production debug APK builds passed.
- Unit tests: 54 demo tests and 48 production tests passed.
- Both debug lint tasks passed (existing/deprecation warnings remain).
- Eleven relevant instrumentation scenarios were executed across navigation, active-session editing, Progress selection and Room persistence. Nine passed initially; two required more precise test selectors and then passed on targeted reruns. Input fields are selected by editing actions, confirmation buttons by click actions, and exercise results exclude the search input.
- The root-tab light/dark and Indonesian/English rendering test also passed with emulator font scale 1.5. The prior font scale was restored afterwards.
- `git diff --check` passed.

### Remaining findings

- Legacy day/planning resources and unused historical labels are retained, not shown as current features. Their removal is deferred until separately verified safe.
- Authentication validation still contains older raw English messages (for example, `RegisterViewModel` name validation). That is outside this fitness-terminology pass and is not claimed as localized by it.
- Proper exercise names, user-authored routine names, and descriptive catalog data retain their original values. This pass localizes interface copy, not stored content.
