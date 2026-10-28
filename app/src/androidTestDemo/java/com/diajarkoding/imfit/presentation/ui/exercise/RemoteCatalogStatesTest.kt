package com.diajarkoding.imfit.presentation.ui.exercise

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.theme.IMFITTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Exercises actual G3 rendering/gestures with transient synthetic states, not a hosted account. */
class RemoteCatalogStatesTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val state = mutableStateOf(ExerciseBrowserState(endReached = true))
    private var refreshes = 0
    private var retries = 0

    private fun show() = rule.setContent {
        IMFITTheme {
            ExerciseListContent(ExerciseCategory.ALL, state.value, {}, {}, { retries++ },
                onRefresh = { refreshes++ })
        }
    }

    @Test fun emptyAndInitialErrorRemainRefreshableAndErrorHasExplicitRetry() {
        show()
        rule.onNodeWithTag("exercise_list_empty").assertExists()
        rule.onNodeWithTag("exercise_list").performTouchInput { swipeDown() }
        rule.runOnIdle {
            assertTrue("Empty list supports pull-to-refresh", refreshes > 0)
            refreshes = 0
            state.value = ExerciseBrowserState(hasError = true, endReached = true)
        }
        rule.onNodeWithTag("exercise_list_error").assertExists()
        rule.onNodeWithTag("exercise_list_empty").assertDoesNotExist()
        rule.onNodeWithTag("exercise_list").performTouchInput { swipeDown() }
        rule.runOnIdle { assertTrue("Initial error supports pull-to-refresh", refreshes > 0) }
        rule.onNodeWithText(rule.activity.getString(R.string.routine_retry)).performClick()
        rule.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun staleRefreshAndAppendFailureKeepRowsWithExplicitRetry() {
        val row = Exercise("bench", "Synthetic bench", MuscleCategory.CHEST, "")
        state.value = ExerciseBrowserState(results = listOf(row), hasError = true, stale = true, endReached = true)
        show()
        rule.onNodeWithTag("exercise_list_row_bench").assertExists()
        rule.onNodeWithTag("exercise_list_empty").assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.remote_read_stale)).assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.routine_retry)).performClick()
        rule.runOnIdle {
            assertEquals(1, retries)
            state.value = state.value.copy(hasError = false, stale = false, appendError = true, endReached = false)
        }
        rule.onNodeWithTag("exercise_list_row_bench").assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.remote_read_stale)).assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(R.string.redesign_retry)).performClick()
        rule.runOnIdle { assertEquals(2, retries) }
    }
}
