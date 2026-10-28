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
