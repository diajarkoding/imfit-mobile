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
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(name))
            rule.onNodeWithText(name).performClick()
            waitForText(text(R.string.action_start_workout))
            rule.onNodeWithText(text(R.string.action_start_workout)).performClick()
            waitForText(first.name)
            val original = runBlocking { requireNotNull(repository.getActiveSession()) }
            rule.onNodeWithContentDescription(text(R.string.active_session_remove_exercise, first.name)).assertExists()
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text(R.string.active_session_add_exercises)))
            rule.onNodeWithText(text(R.string.active_session_add_exercises)).performClick()
            rule.onNode(hasSetTextAction()).performTextInput(added.name)
            waitForText(added.name)
            rule.onNode(hasText(added.name) and hasClickAction() and !hasSetTextAction()).performClick()
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
}
