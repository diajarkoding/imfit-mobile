package com.diajarkoding.imfit.presentation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.diajarkoding.imfit.MainActivity
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.theme.LocaleManager
import org.junit.Rule
import org.junit.Test

/** Disposable emulator + synthetic demo only. Exercises the runtime remote gateway/DI/navigation. */
class G3NavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private fun text(id: Int, vararg args: Any) = LocaleManager.getUpdatedContext(rule.activity).getString(id, *args)
    private fun waitFor(value: String) = rule.waitUntil(20_000) { rule.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }

    @Test fun progressMarkerOpensRemoteHistoryAndLoadsChildDetailOnDemand() {
        waitFor(text(R.string.redesign_ready))
        val owner = requireNotNull(com.diajarkoding.imfit.data.local.FakeUserDataSource.getCurrentUser()).id
        val fixture = com.diajarkoding.imfit.data.local.FakeWorkoutDataSource.getWorkoutLogs(owner).first()
        val day = java.time.Instant.ofEpochMilli(fixture.startTime).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        rule.onNodeWithText(text(R.string.nav_progress)).performClick()
        waitFor(text(R.string.progress_overview_volume))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription(text(R.string.calendar_view_yearly)))
        // The yearly route can open a fixture from the previous month at the start of a month.
        rule.onNodeWithContentDescription(text(R.string.calendar_view_yearly)).performClick()
        rule.waitUntil(20_000) { rule.onAllNodesWithContentDescription(text(R.string.calendar_previous_year)).fetchSemanticsNodes().isNotEmpty() }
        if (day.year < java.time.LocalDate.now().year) rule.onNodeWithContentDescription(text(R.string.calendar_previous_year)).performClick()
        val locale = LocaleManager.getUpdatedContext(rule.activity).resources.configuration.locales[0]
        val dayLabel = day.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", locale))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription(dayLabel, substring = true))
        rule.onNode(hasContentDescription(dayLabel, substring = true)).performClick()
        waitFor(text(R.string.workout_history_app_bar_title))
        rule.onNodeWithTag("workout_history_list").performScrollToNode(hasText(fixture.templateName))
        rule.onNodeWithText(fixture.templateName).assertExists()
        fixture.exerciseLogs.firstOrNull()?.let { exercise ->
            rule.onAllNodesWithText(exercise.exercise.name).assertCountEquals(0)
            rule.onNodeWithTag("workout_history_list").performScrollToNode(hasText(text(R.string.workout_day_card_view_details)))
            rule.onAllNodesWithText(text(R.string.workout_day_card_view_details)).onFirst().performClick()
            waitFor(exercise.exercise.name)
        }
        rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        rule.waitUntil(20_000) { rule.onAllNodesWithContentDescription(text(R.string.calendar_previous_year)).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun createEditPickerHiddenSelectionSaveAndDeleteReturnToHome() {
        waitFor(text(R.string.redesign_ready))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text(R.string.home_add_day)))
        rule.onNodeWithText(text(R.string.home_add_day)).performClick()
        val original = "G3 synthetic ${System.nanoTime()}"
        val renamed = "$original edited"
        rule.onNode(hasText(text(R.string.home_workout_placeholder)) and hasSetTextAction()).performTextInput(original)
        rule.onNodeWithText(text(R.string.routine_create_action)).performClick()
        waitFor(original)
        rule.waitUntil(20_000) { rule.onAllNodesWithContentDescription(text(R.string.action_edit)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithContentDescription(text(R.string.action_edit)).performClick()
        rule.onNode(hasSetTextAction()).performTextReplacement(renamed)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.onNodeWithText(text(R.string.action_add_exercise)).performClick()
        waitFor(text(R.string.routine_picker_title))
        rule.onNodeWithTag("exercise_picker_search").performTextInput("Barbell Bench Press")
        val benchRow = hasText("Barbell Bench Press") and hasClickAction() and !hasSetTextAction()
        rule.waitUntil(20_000) { rule.onAllNodes(benchRow).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(benchRow).performClick()
        waitFor(text(R.string.routine_selected_count, 1))
        rule.onNodeWithTag("exercise_picker_search").performTextReplacement("zz_no_fixture")
        waitFor(text(R.string.routine_search_empty))
        rule.onNodeWithText(text(R.string.routine_selected_count, 1)).assertExists()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.onNodeWithText(text(R.string.routine_add_count, 1)).performClick()
        waitFor(renamed)
        rule.onNodeWithText(text(R.string.action_save_changes)).performClick()
        rule.waitUntil(20_000) { rule.onAllNodesWithText(text(R.string.workout_edit_title)).fetchSemanticsNodes().isEmpty() }
        waitFor(renamed)
        rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(renamed))
        rule.onNodeWithText(renamed).performClick()
        waitFor(renamed)
        rule.onNodeWithContentDescription(text(R.string.action_delete)).performClick()
        rule.onNodeWithText(text(R.string.routine_delete_action)).performClick()
        rule.waitUntil(20_000) { rule.onAllNodesWithText(renamed).fetchSemanticsNodes().isEmpty() }
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text(R.string.redesign_ready)))
        waitFor(text(R.string.redesign_ready))
        rule.onAllNodesWithText(renamed).assertCountEquals(0)
    }
}
