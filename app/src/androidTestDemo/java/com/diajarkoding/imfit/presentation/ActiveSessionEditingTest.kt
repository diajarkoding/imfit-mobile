package com.diajarkoding.imfit.presentation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.diajarkoding.imfit.MainActivity
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.data.local.FakeExerciseDataSource
import com.diajarkoding.imfit.di.RestTimerEntryPoint
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.data.remote.online.SessionCommand
import com.diajarkoding.imfit.data.remote.online.SessionSetKey
import java.util.UUID
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
        val reads = ports.reads()
        val sessions = ports.sessions()
        val first = FakeExerciseDataSource.exercises.first { it.id == "ex_chest_1" }
        val added = FakeExerciseDataSource.exercises.first { it.id == "ex_back_3" }
        val name = "Session edits ${System.nanoTime()}"
        val edits = ports.edits()
        val receipt = runBlocking {
            sessions.reload()?.let { sessions.cancel(it.id) }
            edits.save(null, name, listOf(TemplateExercise(first, sets = 3, reps = 10, restSeconds = 60)))
        }
        val templateId = receipt.templateId
        try {
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(name))
            rule.onNodeWithText(name).performClick()
            waitForText(text(R.string.action_start_workout))
            rule.onNodeWithText(text(R.string.action_start_workout)).performClick()
            waitForText(first.name)
            val original = requireNotNull(sessions.state.value.snapshot)
            rule.onNodeWithContentDescription(text(R.string.active_session_remove_exercise, first.name)).assertExists()
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text(R.string.active_session_add_exercises)))
            rule.onNodeWithText(text(R.string.active_session_add_exercises)).performClick()
            rule.onNode(hasSetTextAction()).performTextInput(added.name)
            // Remote search renders via paging; the field text alone is not the result row.
            rule.waitUntil(15_000) {
                rule.onAllNodes(hasText(added.name) and hasClickAction() and !hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNode(hasText(added.name) and hasClickAction() and !hasSetTextAction()).performClick()
            val addOne = text(R.string.routine_add_count, 1)
            rule.onNodeWithText(addOne).performClick()
            rule.waitUntil(15_000) { sessions.state.value.snapshot?.exercises?.any { it.exerciseId == added.id } == true }
            assertEquals(original.id, sessions.state.value.snapshot?.id)
            assertEquals(1, runBlocking { reads.template(templateId).second.exercises.size })
            runBlocking {
                val exercise = requireNotNull(sessions.state.value.snapshot).exercises.single { it.exerciseId == first.id }
                val set = exercise.sets.first()
                sessions.editSet(original.id, SessionSetKey(exercise.id, set.id), "20", "10")
                sessions.command(original.id, SessionCommand.CompleteSet(exercise.id, set.id))
            }
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(first.name))
            rule.waitUntil(15_000) {
                rule.onAllNodesWithContentDescription(text(R.string.active_session_remove_exercise, first.name)).fetchSemanticsNodes().isEmpty()
            }
            rule.onNodeWithText(first.name).assertIsDisplayed()
            val removeAdded = text(R.string.active_session_remove_exercise, added.name)
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription(removeAdded))
            rule.onNodeWithContentDescription(removeAdded).performClick()
            rule.waitUntil(15_000) { sessions.state.value.snapshot?.exercises?.size == 1 }
            rule.onNodeWithContentDescription(text(R.string.action_cancel)).performClick()
            rule.onNodeWithText(text(R.string.action_cancel_workout)).performClick()
            rule.waitUntil(15_000) { sessions.state.value.snapshot?.effectiveStatus == com.diajarkoding.imfit.data.remote.online.SessionStatus.CANCELLED }
            rule.waitForIdle()
            rule.onNodeWithText(text(R.string.active_workout_cancel_title)).assertDoesNotExist()
            // A terminal session refuses a new typed mutation and cannot be resurrected.
            runBlocking {
                val exercise = original.exercises.first()
                assertTrue(runCatching { sessions.command(original.id, SessionCommand.AddSet(exercise.id, UUID.randomUUID().toString())) }.isFailure)
            }
            assertNull(sessions.peek())
        } finally {
            runBlocking {
                sessions.peek()?.let { sessions.cancel(it.id) }
                val (detail, domain) = reads.template(templateId)
                edits.save(detail, domain.name, domain.exercises, deleted = true)
            }
        }
    }
}
