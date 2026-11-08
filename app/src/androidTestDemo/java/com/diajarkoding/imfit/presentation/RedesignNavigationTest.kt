package com.diajarkoding.imfit.presentation

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diajarkoding.imfit.MainActivity
import com.diajarkoding.imfit.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import androidx.lifecycle.ViewModelProvider
import com.diajarkoding.imfit.theme.ThemeViewModel
import com.diajarkoding.imfit.theme.LocaleManager
import androidx.test.platform.app.InstrumentationRegistry
import com.diajarkoding.imfit.di.RestTimerEntryPoint
import dagger.hilt.android.EntryPointAccessors
import androidx.compose.ui.test.printToString
import com.diajarkoding.imfit.domain.model.SetTarget
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.TrackingType
import com.diajarkoding.imfit.domain.repository.RoutineRepository
import com.diajarkoding.imfit.data.local.FakeExerciseDataSource
import kotlinx.coroutines.runBlocking
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Demo only: real host/backstack, category route, search and profile navigation. */
@RunWith(AndroidJUnit4::class)
class RedesignNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @org.junit.Before fun ensureDefaultLocale() {
        rule.runOnUiThread { LocaleManager.setLanguage(rule.activity, "in") }
    }
    private fun text(id: Int): String = LocaleManager.getUpdatedContext(rule.activity).getString(id)
    private fun waitForText(value: String) {
        rule.waitUntil(15_000) { rule.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        val directory = requireNotNull(rule.activity.getExternalFilesDir("redesign-evidence"))
        val profile = InstrumentationRegistry.getArguments().getString("artifactSuffix", "default")
        File(directory, "$name-$profile.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    private suspend fun deleteFixture(repository: RoutineRepository, id: String) {
        val current = repository.getRoutineById(id).getOrThrow() ?: return
        repository.deleteRoutine(id, current.revision, baselineExerciseIds = current.exercises.map { it.id }, name = current.name).getOrThrow()
    }

    @Test fun rootTabsSearchCategoryAndProfileUseRealDestinations() {
        waitForText(text(R.string.redesign_ready))
        screenshot("home-after")
        rule.onNodeWithText(text(R.string.nav_exercises)).performClick()
        waitForText(text(R.string.muscle_category_chest))
        screenshot("exercise-after")
        rule.onAllNodesWithText(text(R.string.muscle_category_chest)).onFirst().performClick()
        waitForText(text(R.string.redesign_search))
        rule.onNodeWithTag("exercise_list_search").performTextInput("zz_no_imfit_result")
        waitForText(text(R.string.redesign_no_results))
        rule.onNodeWithTag("exercise_list_search").performTextClearance()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        waitForText("Barbell Bench Press")
        rule.waitForIdle()
        rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        rule.waitForIdle()
        waitForText(text(R.string.nav_progress))
        rule.onNodeWithText(text(R.string.nav_progress)).performClick()
        try { waitForText(text(R.string.progress_overview_volume)) }
        catch (e: Exception) { screenshot("progress-failure"); throw e }
        screenshot("progress-after")
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription(text(R.string.calendar_view_yearly)))
        screenshot("progress-calendar")
        rule.onNodeWithContentDescription(text(R.string.calendar_view_yearly)).performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithContentDescription(text(R.string.calendar_previous_year)).fetchSemanticsNodes().isNotEmpty()
        }
        val locale = LocaleManager.getUpdatedContext(rule.activity).resources.configuration.locales[0]
        val displayedMonth = YearMonth.now().format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).replaceFirstChar { it.uppercaseChar() }
        rule.onNodeWithText(displayedMonth).assertIsDisplayed()
        rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        waitForText(text(R.string.nav_progress))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription(text(R.string.redesign_profile)))
        rule.onNodeWithContentDescription(text(R.string.redesign_profile)).performClick()
        waitForText(text(R.string.title_profile))
    }

    @Test fun homeCreateDialogOpensInitialPickerAndBackShowsOneEmptyDetail() {
        waitForText(text(R.string.redesign_ready))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text(R.string.home_add_day)))
        rule.onNodeWithText(text(R.string.home_add_day)).performClick()
        rule.onNode(hasText(text(R.string.home_workout_placeholder)) and hasSetTextAction())
            .performTextInput("Redesign smoke template")
        rule.onNode(hasText(text(R.string.routine_create_action)) and hasClickAction()).performClick()
        waitForText(text(R.string.routine_picker_title))
        rule.onNodeWithTag("exercise_picker_search").assertIsDisplayed()
        rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        waitForText("Redesign smoke template")
        rule.onNodeWithText(text(R.string.routine_empty_badge)).assertIsDisplayed()
        rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        waitForText(text(R.string.redesign_ready))
        rule.onNodeWithText(text(R.string.routine_picker_title)).assertDoesNotExist()
    }

    @Test fun lightDarkAndBothLocalesRenderAllRootTabs() {
        waitForText(text(R.string.redesign_ready))
        val theme = ViewModelProvider(rule.activity)[ThemeViewModel::class.java]
        val originalDark = theme.uiState.value.isDarkMode
        val originalIndonesian = LocaleManager.isIndonesian
        try {
            for (dark in listOf(false, true)) for (indonesian in listOf(false, true)) {
                rule.runOnUiThread {
                    theme.setDarkMode(dark)
                    if (LocaleManager.isIndonesian != indonesian) LocaleManager.toggleLanguage(rule.activity)
                }
                rule.waitForIdle()
                val suffix = (if (dark) "dark" else "light") + "-" + (if (indonesian) "id" else "en")
                rule.onNodeWithText(text(R.string.nav_home)).performClick()
                waitForText(text(R.string.redesign_ready))
                screenshot("home-$suffix")
                rule.onNodeWithText(text(R.string.nav_exercises)).performClick()
                waitForText(text(R.string.muscle_category_chest))
                screenshot("exercise-$suffix")
                rule.onNodeWithText(text(R.string.nav_progress)).performClick()
                waitForText(text(R.string.progress_overview_volume))
                screenshot("progress-$suffix")
            }
        } finally {
            rule.runOnUiThread {
                theme.setDarkMode(originalDark)
                if (LocaleManager.isIndonesian != originalIndonesian) LocaleManager.toggleLanguage(rule.activity)
            }
        }
    }

    @Test fun editedNameAppearsOnRetainedDetailAndHomeWithoutSync() {
        waitForText(text(R.string.redesign_ready))
        val ports = EntryPointAccessors.fromApplication(rule.activity.application, RestTimerEntryPoint::class.java)
        val repository = ports.routines()
        val before = "Rename ${System.nanoTime()}"
        val after = "$before saved"
        val template = runBlocking {
            repository.createRoutine(requireNotNull(ports.auth().getLocalUserId()), before).getOrThrow()
        }
        try {
            rule.onNodeWithTag("home_pull_to_refresh").performTouchInput { swipeDown() }
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(before))
            rule.onNodeWithText(before).performClick()
            waitForText(before)
            rule.onNodeWithContentDescription(text(R.string.action_edit)).performClick()
            rule.onNode(hasSetTextAction()).performTextReplacement(after)
            rule.onNodeWithText(text(R.string.action_save_changes)).performClick()
            waitForText(after)
            rule.onNodeWithText(text(R.string.workout_edit_title)).assertDoesNotExist()
            rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(after))
            rule.onNodeWithText(after).assertIsDisplayed()
        } finally { runBlocking { deleteFixture(repository, template.id) } }
    }

    @Test fun homeHistoryUpdatesToOneFinishedSessionWithoutReopeningHomeOrWaitingForCloud() {
        waitForText(text(R.string.redesign_ready))
        val ports = EntryPointAccessors.fromApplication(rule.activity.application, RestTimerEntryPoint::class.java)
        val repository = ports.workouts()
        val name = "Latest card ${System.nanoTime()}"
        val template = runBlocking {
            repository.cancelWorkout()
            val empty = ports.routines().createRoutine(requireNotNull(ports.auth().getLocalUserId()), name).getOrThrow()
            ports.routines().saveRoutine(empty.id, name,
                listOf(TemplateExercise(FakeExerciseDataSource.exercises.first(), sets = 1)), empty.revision).getOrThrow()
        }
        try {
            val finished = runBlocking {
                val session = repository.startWorkout(template)
                repository.mutateActiveSession(session.id) { current -> current.copy(exerciseLogs = current.exerciseLogs.map { log ->
                    log.copy(sets = log.sets.map { it.copy(weightKg = 20.0, reps = 8, isCompleted = true) })
                }) }
                requireNotNull(repository.finishWorkout())
            }
            val localized = LocaleManager.getUpdatedContext(rule.activity)
            val locale = localized.resources.configuration.locales[0]
            val heading = localized.getString(R.string.home_summary_heading, name.uppercase(locale))
            waitForText(heading)
            rule.onNodeWithText(heading).assertIsDisplayed()
        } finally { runBlocking { repository.cancelWorkout(); deleteFixture(ports.routines(), template.id) } }
    }

    @Test fun completeExistingRoutineRecreatesSetupAndAcceptsInitialSelectionOnce() {
        waitForText(text(R.string.redesign_ready))
        val ports = EntryPointAccessors.fromApplication(rule.activity.application, RestTimerEntryPoint::class.java)
        val repository = ports.routines()
        val owner = runBlocking { requireNotNull(ports.auth().getLocalUserId()) }
        val name = "Complete existing ${System.nanoTime()}"
        val routine = runBlocking { repository.createRoutine(owner, name).getOrThrow() }
        val beforeCount = runBlocking { repository.getRoutines(owner).getOrThrow().size }
        try {
            rule.onNodeWithTag("home_pull_to_refresh").performTouchInput { swipeDown() }
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(name))
            rule.onNodeWithText(name).performClick()
            waitForText(text(R.string.routine_complete_action))
            rule.onNodeWithText(text(R.string.routine_complete_action)).performClick()
            waitForText(text(R.string.routine_picker_title))
            val exercise = FakeExerciseDataSource.exercises.first { it.id == "ex_chest_1" }
            rule.onNodeWithTag("exercise_row_${exercise.id}").performClick()
            val addOne = LocaleManager.getUpdatedContext(rule.activity).getString(R.string.routine_add_count, 1)
            rule.onNodeWithText(addOne).performClick()
            waitForText(text(R.string.routine_setup_title))
            rule.activityRule.scenario.recreate()
            rule.waitForIdle()
            screenshot("after-recreate")
            val setupTitle = text(R.string.routine_setup_title)
            val saveAction = text(R.string.action_save_template)
            rule.waitUntil(15_000) {
                rule.onAllNodes(hasText(setupTitle) or hasText("Set up routine") or hasText("Atur rutinitas"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            rule.onAllNodesWithText(exercise.name).assertCountEquals(1)
            rule.onNode(hasText(saveAction) or hasText("Save template") or hasText("Simpan rutinitas")).performClick()
            waitForText(name)
            rule.onNode(hasText(setupTitle) or hasText("Set up routine") or hasText("Atur rutinitas")).assertDoesNotExist()
            val saved = runBlocking { requireNotNull(repository.getRoutineById(routine.id).getOrThrow()) }
            org.junit.Assert.assertEquals(listOf(exercise.id), saved.exercises.map { it.exercise.id })
            org.junit.Assert.assertEquals(beforeCount, runBlocking { repository.getRoutines(owner).getOrThrow().size })
            rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
            rule.waitUntil(15_000) {
                rule.onAllNodes(hasText(text(R.string.redesign_ready)) or hasTestTag("home_pull_to_refresh"))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithText(text(R.string.routine_picker_title)).assertDoesNotExist()
        } finally { runBlocking { deleteFixture(repository, routine.id) } }
    }
    @Test fun populatedRoutineAddsDirectlyAndPickerCancelLeavesNoEditor() {
        waitForText(text(R.string.redesign_ready))
        val ports = EntryPointAccessors.fromApplication(rule.activity.application, RestTimerEntryPoint::class.java)
        val repository = ports.routines()
        val name = "Direct add ${System.nanoTime()}"
        val bench = FakeExerciseDataSource.exercises.first { it.id == "ex_chest_1" }
        val added = FakeExerciseDataSource.exercises.first { it.id == "ex_chest_2" }
        val routine = runBlocking {
            val empty = repository.createRoutine(requireNotNull(ports.auth().getLocalUserId()), name).getOrThrow()
            repository.saveRoutine(
                empty.id,
                name,
                listOf(
                    TemplateExercise(
                        exercise = bench,
                        sets = 3,
                        reps = 10,
                        restSeconds = 60,
                        setTargets = listOf(
                            SetTarget("b-1", 1, 20.0, 10, restSeconds = 60),
                            SetTarget("b-2", 2, 20.0, 10, restSeconds = 60),
                            SetTarget("b-3", 3, 20.0, 10, restSeconds = 60)
                        ),
                        measurementVersion = 3
                    )
                ),
                empty.revision
            ).getOrThrow()
        }
        try {
            rule.onNodeWithTag("home_pull_to_refresh").performTouchInput { swipeDown() }
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(name))
            rule.onNodeWithText(name).performClick()
            waitForText(name)
            rule.onNodeWithTag("routine_detail_list").performScrollToNode(hasText(text(R.string.action_add_exercise)))
            rule.onNodeWithText(text(R.string.action_add_exercise)).performClick()
            waitForText(text(R.string.routine_picker_title))
            rule.onNodeWithTag("exercise_row_${bench.id}").assertDoesNotExist()
            androidx.test.espresso.Espresso.pressBack()
            waitForText(name)
            rule.onNodeWithText(text(R.string.workout_edit_title)).assertDoesNotExist()
            rule.onNodeWithTag("routine_detail_list").performScrollToNode(hasText(text(R.string.action_add_exercise)))
            rule.onNodeWithText(text(R.string.action_add_exercise)).performClick()
            waitForText(text(R.string.routine_picker_title))
            rule.onNodeWithTag("exercise_picker_search").performTextInput(added.name)
            rule.waitUntil(10_000) {
                rule.onAllNodesWithTag("exercise_row_${added.id}").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("exercise_row_${added.id}").performClick()
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            rule.onNodeWithText(LocaleManager.getUpdatedContext(rule.activity).getString(R.string.routine_add_count, 1)).performClick()
            waitForText(text(R.string.workout_edit_title))
            rule.onAllNodesWithText(bench.name).assertCountEquals(1)
            rule.onAllNodesWithText(added.name).assertCountEquals(1)
            screenshot("direct-add-editor")
            rule.onNodeWithText(text(R.string.action_save_changes)).performClick()
            waitForText(name)
            val saved = runBlocking { requireNotNull(repository.getRoutineById(routine.id).getOrThrow()) }
            org.junit.Assert.assertEquals(listOf(bench.id, added.id), saved.exercises.map { it.id })
            rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
            rule.waitUntil(10_000) {
                rule.onAllNodesWithTag("home_pull_to_refresh").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("home_pull_to_refresh").assertIsDisplayed()
            rule.onNodeWithText(text(R.string.workout_edit_title)).assertDoesNotExist()
        } finally { runBlocking { deleteFixture(repository, routine.id) } }
    }
    @Test fun mixedRoutineTargetsSurvivePickerRoundTripAndSave() {
        waitForText(text(R.string.redesign_ready))
        val ports = EntryPointAccessors.fromApplication(rule.activity.application, RestTimerEntryPoint::class.java)
        val repository = ports.routines()
        val name = "Mixed targets ${System.nanoTime()}"
        val plank = FakeExerciseDataSource.exercises.first { it.id == "ex_core_1" }
        val run = FakeExerciseDataSource.exercises.first { it.id == "ex_cardio_1" }
        val bench = FakeExerciseDataSource.exercises.first { it.id == "ex_chest_1" }
        val routine = runBlocking {
            val empty = repository.createRoutine(requireNotNull(ports.auth().getLocalUserId()), name).getOrThrow()
            repository.saveRoutine(empty.id, name, listOf(
                TemplateExercise(plank, reps = null, targetDurationSeconds = 45),
                TemplateExercise(run, reps = null, targetDurationSeconds = 600, targetDistanceMeters = 1000)
            ), empty.revision).getOrThrow()
        }
        try {
            rule.onNodeWithTag("home_pull_to_refresh").performTouchInput { swipeDown() }
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(name))
            rule.onNodeWithText(name).performClick()
            waitForText(name)
            rule.onNodeWithContentDescription(text(R.string.action_edit)).performClick()
            waitForText(text(R.string.workout_edit_title))
            rule.onNodeWithTag("routine_duration_${plank.id}_1").performTextReplacement("00:01:30")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            rule.onNodeWithTag("routine_edit_list").performScrollToNode(hasTestTag("routine_duration_${run.id}_1"))
            screenshot("mixed-targets-time-only-editor")
            rule.onNodeWithTag("routine_duration_${run.id}_1").performTextReplacement("00:30:00")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            rule.onNodeWithTag("routine_edit_list").performScrollToNode(hasText(text(R.string.action_add_exercise)))
            rule.onNodeWithText(text(R.string.action_add_exercise)).performClick()
            waitForText(text(R.string.routine_picker_title))
            rule.onNodeWithTag("exercise_row_${bench.id}").performClick()
            rule.onNodeWithText(LocaleManager.getUpdatedContext(rule.activity).getString(R.string.routine_add_count, 1)).performClick()
            waitForText(text(R.string.workout_edit_title))
            rule.onNodeWithText(text(R.string.action_save_changes)).performClick()
            waitForText(name)
            val saved = runBlocking { requireNotNull(repository.getRoutineById(routine.id).getOrThrow()) }
            org.junit.Assert.assertEquals(listOf(plank.id, run.id, bench.id), saved.exercises.map { it.id })
            org.junit.Assert.assertEquals(90, saved.exercises[0].setTargets?.first()?.durationSeconds)
            org.junit.Assert.assertEquals(1800, saved.exercises[1].setTargets?.first()?.durationSeconds)
            org.junit.Assert.assertEquals(TrackingType.DURATION, saved.exercises[1].trackingType)
            org.junit.Assert.assertNull(saved.exercises[1].targetDistanceMeters)
            org.junit.Assert.assertEquals(com.diajarkoding.imfit.domain.model.ExerciseModality.CARDIO, saved.exercises[1].exercise.modality)
            screenshot("mixed-targets-detail")
        } finally { runBlocking { deleteFixture(repository, routine.id) } }
    }
}
