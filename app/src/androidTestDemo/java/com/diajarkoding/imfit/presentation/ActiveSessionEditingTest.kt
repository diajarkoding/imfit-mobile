package com.diajarkoding.imfit.presentation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.diajarkoding.imfit.MainActivity
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.data.local.FakeExerciseDataSource
import com.diajarkoding.imfit.di.RestTimerEntryPoint
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.theme.LocaleManager
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ActiveSessionEditingTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun text(id: Int, vararg args: Any) = LocaleManager.getUpdatedContext(rule.activity).getString(id, *args)
    private fun waitForText(value: String) {
        rule.waitUntil(15_000) { rule.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun addRemoveCompletedProtectionAndCancelUseSamePersistedSession() {
        waitForText(text(R.string.redesign_ready))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${rule.activity.packageName} android.permission.POST_NOTIFICATIONS").close()
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            instrumentation.uiAutomation.executeShellCommand("appops set ${rule.activity.packageName} SCHEDULE_EXACT_ALARM allow").close()
        }
        if (android.os.Build.VERSION.SDK_INT >= 34) {
            instrumentation.uiAutomation.executeShellCommand("appops set ${rule.activity.packageName} USE_FULL_SCREEN_INTENT allow").close()
        }
        val ports = EntryPointAccessors.fromApplication(rule.activity.application, RestTimerEntryPoint::class.java)
        val repository = ports.workouts()
        val routines = ports.routines()
        val first = FakeExerciseDataSource.exercises.first { it.id == "ex_chest_1" }
        val added = FakeExerciseDataSource.exercises.first { it.id == "ex_back_3" }
        val name = "Session edits ${System.nanoTime()}"
        val template = runBlocking {
            repository.cancelWorkout()
            val created = routines.createRoutine(requireNotNull(ports.auth().getLocalUserId()), name).getOrThrow()
            routines.saveRoutine(created.id, name, listOf(TemplateExercise(first)), created.revision).getOrThrow()
        }
        try {
            // Home's routine snapshot is refreshed explicitly after this out-of-UI fixture write.
            rule.onNodeWithTag("home_pull_to_refresh").performTouchInput { swipeDown() }
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(name))
            rule.onNodeWithText(name).performClick()
            waitForText(text(R.string.action_start_workout))
            rule.onNodeWithText(text(R.string.action_start_workout)).performClick()
            waitForText(first.name)
            val original = runBlocking { requireNotNull(repository.getActiveSession()) }
            rule.onNodeWithContentDescription(text(R.string.active_session_remove_exercise, first.name)).assertExists()
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text(R.string.active_session_add_exercises)))
            rule.onNodeWithText(text(R.string.active_session_add_exercises)).performClick()
            rule.onNodeWithTag("exercise_picker_search").performTextInput(added.name)
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            val addedItem = hasText(added.name) and hasClickAction() and !hasSetTextAction()
            rule.waitUntil(15_000) { rule.onAllNodes(addedItem).fetchSemanticsNodes().size == 1 }
            rule.onNode(addedItem).performClick()
            val addOne = text(R.string.routine_add_count, 1)
            rule.onNodeWithText(addOne).performClick()
            rule.waitUntil(15_000) { runBlocking { repository.getActiveSession()?.exerciseLogs?.any { it.exercise.id == added.id } == true } }
            assertEquals(original.id, runBlocking { repository.getActiveSession()?.id })
            assertEquals(1, runBlocking { routines.getRoutineById(template.id).getOrThrow()?.exercises?.size })
            runBlocking {
                repository.mutateActiveSession(original.id) { current -> current.copy(exerciseLogs = current.exerciseLogs.map { log ->
                    if (log.exercise.id != first.id) log else log.copy(sets = log.sets.mapIndexed { index, set ->
                        if (index == 0) set.copy(isCompleted = true, weight = 20f) else set
                    })
                }) }
            }
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(first.name))
            rule.waitUntil(15_000) {
                rule.onAllNodesWithContentDescription(text(R.string.active_session_remove_exercise, first.name)).fetchSemanticsNodes().isEmpty()
            }
            rule.onNodeWithText(first.name).assertIsDisplayed()
            val removeAdded = text(R.string.active_session_remove_exercise, added.name)
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription(removeAdded))
            rule.onNodeWithContentDescription(removeAdded).performClick()
            rule.waitUntil(15_000) { runBlocking { repository.getActiveSession()?.exerciseLogs?.size == 1 } }
            rule.onNodeWithContentDescription(text(R.string.action_cancel)).performClick()
            rule.onNodeWithText(text(R.string.action_cancel_workout)).performClick()
            rule.waitUntil(15_000) { runBlocking { repository.getActiveSession() == null } }
            rule.waitForIdle()
            rule.onNodeWithText(text(R.string.active_workout_cancel_title)).assertDoesNotExist()
            runBlocking { repository.updateActiveSession(original) }
            assertNull(runBlocking { repository.getActiveSession() })
        } finally {
            runBlocking {
                repository.cancelWorkout()
                val current = routines.getRoutineById(template.id).getOrThrow()
                routines.deleteRoutine(template.id, current?.revision,
                    baselineExerciseIds = current?.exercises?.map { it.id }.orEmpty(), name = template.name).getOrThrow()
            }
        }
    }
    @Test fun bodyweightAndAssistedMeasurementsPersistFromRealSessionInputs() {
        waitForText(text(R.string.redesign_ready))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${rule.activity.packageName} android.permission.POST_NOTIFICATIONS").close()
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            instrumentation.uiAutomation.executeShellCommand("appops set ${rule.activity.packageName} SCHEDULE_EXACT_ALARM allow").close()
        }
        val ports = EntryPointAccessors.fromApplication(rule.activity.application, RestTimerEntryPoint::class.java)
        val repository = ports.workouts()
        val routines = ports.routines()
        val push = FakeExerciseDataSource.exercises.first { it.id == "ex_chest_4" }
        val pull = FakeExerciseDataSource.exercises.first { it.id == "ex_back_2" }
        val assisted = com.diajarkoding.imfit.domain.model.Exercise(
            "assisted-fixture", "Assisted pull-up", com.diajarkoding.imfit.domain.model.MuscleCategory.BACK, "",
            defaultTrackingType = com.diajarkoding.imfit.domain.model.TrackingType.ASSISTED_REPS,
            modality = com.diajarkoding.imfit.domain.model.ExerciseModality.CALISTHENICS)
        val name = "Bodyweight inputs ${System.nanoTime()}"
        val template = runBlocking {
            repository.cancelWorkout()
            val created = routines.createRoutine(requireNotNull(ports.auth().getLocalUserId()), name).getOrThrow()
            routines.saveRoutine(created.id, name, listOf(push, pull, assisted).map {
                TemplateExercise(it, sets = 1, reps = 1, restSeconds = 120)
            }, created.revision).getOrThrow()
        }
        try {
            rule.onNodeWithTag("home_pull_to_refresh").performTouchInput { swipeDown() }
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(name))
            rule.onNodeWithText(name).performClick()
            waitForText(text(R.string.action_start_workout))
            rule.onNodeWithText(text(R.string.action_start_workout)).performClick()
            for ((exercise, reps) in listOf(push to "15", pull to "8", assisted to "10")) {
                val repsLabel = text(R.string.active_session_reps_input, exercise.name, 1)
                rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription(repsLabel))
                rule.onNodeWithContentDescription(repsLabel).performTextReplacement(reps)
                if (exercise == pull) {
                    rule.onNodeWithText(text(R.string.active_set_add_load)).performClick()
                    rule.onNodeWithContentDescription(text(R.string.active_set_additional_weight)).performTextReplacement("10")
                }
                if (exercise == assisted) {
                    rule.onNodeWithContentDescription(text(R.string.active_set_assistance_weight)).performTextReplacement("25")
                }
                androidx.test.espresso.Espresso.closeSoftKeyboard()
                rule.onNodeWithContentDescription(text(R.string.active_session_complete_set, exercise.name, 1)).performClick()
                rule.waitUntil(5_000) {
                    runBlocking { repository.getActiveSession()?.exerciseLogs?.firstOrNull { it.exercise.id == exercise.id }?.sets?.single()?.isCompleted == true }
                }
                waitForText(text(R.string.action_skip))
                val expectedRest = when (exercise) { push -> 60_000L; pull -> 90_000L; else -> 121_000L }
                assertEquals(expectedRest, ports.timer().state.value?.durationMs)
                rule.onNodeWithText(text(R.string.action_skip)).performClick()
                rule.waitUntil(5_000) {
                    rule.onAllNodesWithText(text(R.string.action_skip)).fetchSemanticsNodes().isEmpty()
                }
                if (exercise == push || exercise == pull) {
                    rule.onNodeWithContentDescription(text(R.string.active_workout_rest_timer)).performClick()
                    rule.onNodeWithTag("rest_timer_config_dialog").assertIsDisplayed()
                    for (preset in listOf(30, 60, 90, 120)) rule.onNodeWithTag("rest_preset_$preset").assertExists()
                    val preset = if (exercise == push) 90 else 120
                    rule.onNodeWithTag("rest_preset_$preset").performClick()
                    if (exercise == pull) {
                        rule.onNodeWithContentDescription("1 " + text(R.string.rest_timer_unit_seconds)).performClick()
                        rule.waitForIdle()
                    }
                    com.diajarkoding.imfit.presentation.ui.workout.captureWorkoutEvidence("v12-runtime-rest-config")
                    rule.onNodeWithText(text(R.string.action_save)).performScrollTo().performClick()
                    rule.waitUntil(5_000) {
                        runBlocking { repository.getSessionRestOverride() == if (exercise == push) 90 else 121 }
                    }
                    if (exercise == push) {
                        rule.activityRule.scenario.recreate()
                        waitForText(name)
                    }
                }
            }
            val session = runBlocking { requireNotNull(repository.getActiveSession()) }
            assertEquals(15, session.exerciseLogs[0].totalReps)
            assertEquals(80f, session.exerciseLogs[1].additionalVolume)
            assertEquals(25f, session.exerciseLogs[2].sets.single().assistanceWeight)
            assertEquals(0f, session.exerciseLogs.sumOf { it.totalVolume.toDouble() }.toFloat())
            assertTrue(runBlocking { routines.getRoutineById(template.id).getOrThrow() }!!.exercises.all { it.restSeconds == 120 })
            com.diajarkoding.imfit.presentation.ui.workout.captureWorkoutEvidence("v12-real-bodyweight-session")
        } finally {
            runBlocking {
                repository.cancelWorkout()
                val current = requireNotNull(routines.getRoutineById(template.id).getOrThrow())
                routines.deleteRoutine(template.id, current.revision, baselineExerciseIds = current.exercises.map { it.id }, name = name).getOrThrow()
            }
        }
    }

    @Test fun cardioTimeOnlyAndDistanceTimePersistWithoutInventingDistance() {
        waitForText(text(R.string.redesign_ready))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${rule.activity.packageName} android.permission.POST_NOTIFICATIONS").close()
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) {
            instrumentation.uiAutomation.executeShellCommand("appops set ${rule.activity.packageName} SCHEDULE_EXACT_ALARM allow").close()
        }
        val ports = EntryPointAccessors.fromApplication(rule.activity.application, RestTimerEntryPoint::class.java)
        val repository = ports.workouts()
        val routines = ports.routines()
        val run = FakeExerciseDataSource.exercises.first { it.id == "ex_cardio_1" }
        val bike = FakeExerciseDataSource.exercises.first { it.id == "ex_cardio_2" }
        val name = "Cardio inputs ${System.nanoTime()}"
        val template = runBlocking {
            repository.cancelWorkout()
            val created = routines.createRoutine(requireNotNull(ports.auth().getLocalUserId()), name).getOrThrow()
            routines.saveRoutine(created.id, name, listOf(
                TemplateExercise(run, sets = 1, reps = null,
                    trackingType = com.diajarkoding.imfit.domain.model.TrackingType.DURATION, targetDurationSeconds = 45),
                TemplateExercise(bike, sets = 1, reps = null, targetDurationSeconds = 600, targetDistanceMeters = 1000)
            ), created.revision).getOrThrow()
        }
        try {
            rule.onNodeWithTag("home_pull_to_refresh").performTouchInput { swipeDown() }
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(name))
            rule.onNodeWithText(name).performClick()
            waitForText(text(R.string.action_start_workout))
            rule.onNodeWithText(text(R.string.action_start_workout)).performClick()
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(run.name))
            rule.onAllNodesWithContentDescription("Durasi dalam detik").onFirst().performTextReplacement("01:30")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            rule.onNodeWithContentDescription(text(R.string.active_session_complete_set, run.name, 1)).performClick()
            waitForText(text(R.string.action_skip))
            rule.onNodeWithText(text(R.string.action_skip)).performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithText(text(R.string.action_skip)).fetchSemanticsNodes().isEmpty() }
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(bike.name))
            rule.onNodeWithContentDescription("Jarak dalam meter").performTextClearance()
            rule.onNodeWithContentDescription(text(R.string.active_session_complete_set, bike.name, 1)).assertIsNotEnabled()
            rule.waitUntil(5_000) {
                runBlocking { repository.getActiveSession()?.exerciseLogs?.last()?.sets?.single()?.distanceMeters == null }
            }
            assertEquals(com.diajarkoding.imfit.domain.model.TrackingType.DISTANCE_DURATION,
                runBlocking { repository.getActiveSession()?.exerciseLogs?.last()?.trackingType })
            rule.onNodeWithContentDescription("Jarak dalam meter").performTextReplacement("5000")
            rule.onAllNodesWithContentDescription("Durasi dalam detik").onLast().performTextReplacement("30:00")
            androidx.test.espresso.Espresso.closeSoftKeyboard()
            rule.onNodeWithContentDescription(text(R.string.active_session_complete_set, bike.name, 1)).performClick()
            waitForText(text(R.string.action_skip))
            rule.onNodeWithText(text(R.string.action_skip)).performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithText(text(R.string.action_skip)).fetchSemanticsNodes().isEmpty() }
            val actual = runBlocking { requireNotNull(repository.getActiveSession()) }
            assertEquals(90, actual.exerciseLogs.first().sets.single().durationSeconds)
            assertNull(actual.exerciseLogs.first().sets.single().distanceMeters)
            assertNull(actual.exerciseLogs.first().sets.single().paceSecondsPerKilometer)
            assertEquals(1800, actual.exerciseLogs.last().sets.single().durationSeconds)
            assertEquals(5000, actual.exerciseLogs.last().sets.single().distanceMeters)
            assertEquals(360.0, actual.exerciseLogs.last().sets.single().paceSecondsPerKilometer!!, 0.001)
            com.diajarkoding.imfit.presentation.ui.workout.captureWorkoutEvidence("v12-real-cardio-session")
            rule.onNodeWithText(text(R.string.action_finish_workout)).performClick()
            waitForText(text(R.string.summary_saved_title))
            rule.waitUntil(5_000) {
                rule.onAllNodesWithTag("active_workout_screen").fetchSemanticsNodes().isEmpty()
            }
            rule.waitForIdle()
            rule.onNodeWithText(text(R.string.measurement_external_volume)).assertDoesNotExist()
            com.diajarkoding.imfit.presentation.ui.workout.captureWorkoutEvidence("v12-cardio-summary-metrics")
            rule.onNodeWithTag("workout_summary_list").performScrollToNode(hasText(bike.name))
            com.diajarkoding.imfit.presentation.ui.workout.captureWorkoutEvidence("v12-cardio-summary-sets")
            rule.onNodeWithText(text(R.string.action_done)).performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithTag("workout_summary_list").fetchSemanticsNodes().isEmpty() }
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("home_last_workout_card"))
            rule.onNodeWithTag("home_last_workout_card").assertIsDisplayed()
            com.diajarkoding.imfit.presentation.ui.workout.captureWorkoutEvidence("v12-cardio-home")
            rule.onNodeWithText(text(R.string.nav_progress)).performClick()
            waitForText(text(R.string.progress_overview_volume))
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text(R.string.progress_cardio_duration)))
            com.diajarkoding.imfit.presentation.ui.workout.captureWorkoutEvidence("v12-cardio-progress")
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasTestTag("progress_selected_day"))
            rule.onNodeWithTag("progress_selected_day").performClick()
            rule.waitUntil(5_000) { rule.onAllNodesWithTag("workout_history_list").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("workout_history_list").performScrollToNode(hasTestTag("history_set_${bike.id}_1"))
            rule.onNodeWithTag("history_set_${bike.id}_1").assertIsDisplayed()
            rule.onNodeWithText(text(R.string.active_set_pace, "6:00"), substring = true).assertIsDisplayed()
            rule.waitForIdle()
            com.diajarkoding.imfit.presentation.ui.workout.captureWorkoutEvidence("v12-cardio-history-sets")
        } finally {
            runBlocking {
                repository.cancelWorkout()
                val current = requireNotNull(routines.getRoutineById(template.id).getOrThrow())
                routines.deleteRoutine(template.id, current.revision, baselineExerciseIds = current.exercises.map { it.id }, name = name).getOrThrow()
            }
        }
    }

}
