package com.diajarkoding.imfit.presentation.ui.exercise

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.Pager
import androidx.paging.compose.collectAsLazyPagingItems
import com.diajarkoding.imfit.data.repository.DemoCatalogPagingSource
import com.diajarkoding.imfit.data.repository.DemoExerciseRepository
import com.diajarkoding.imfit.data.repository.exerciseCatalogPagingConfig
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.ExerciseCatalogFilter
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.repository.ExerciseRepository
import com.diajarkoding.imfit.theme.IMFITTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Real screen/ViewModel with151 synthetic records; no account, backend or persistent demo data. */
class ExerciseListPagingUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private class Repository : ExerciseRepository by DemoExerciseRepository() {
        val records = (0..150).map { Exercise("id_%03d".format(it), "Exercise $it", MuscleCategory.CHEST, "") }
        @Volatile var refreshes = 0
        @Volatile var success = true
        override fun pageExercises(filter: ExerciseCatalogFilter) = Pager(
            exerciseCatalogPagingConfig()
        ) { DemoCatalogPagingSource(records.filter(filter::includes)) }.flow
        override suspend fun refreshExercises(force: Boolean): Boolean { refreshes++; return success }
    }
    private val repository = Repository()
    private lateinit var vm: ExerciseBrowserViewModel
    @Volatile private var loaded = 0

    private fun show() {
        rule.runOnUiThread {
            vm = ExerciseBrowserViewModel(repository)
            rule.activity.viewModelStore.put("exercise-list-proof", vm)
        }
        rule.setContent {
            val state by vm.state.collectAsStateWithLifecycle()
            val pages = vm.exercises.collectAsLazyPagingItems()
            loaded = pages.itemCount
            IMFITTheme {
                ExerciseListContent(ExerciseCategory.ALL, state, {}, vm::setQuery, vm::refresh, pages = pages)
            }
        }
        rule.waitUntil(10_000) { loaded >= 10 }
        rule.onNodeWithTag("exercise_list_refresh").assertDoesNotExist()
    }

    @Test fun scrollingAppendsLocallyAndSearchFindsAnInitiallyUnloadedRecord() {
        show()
        val initialRefreshes = repository.refreshes
        for (index in 8..148 step 10) {
            rule.onNodeWithTag("exercise_list").performScrollToIndex(index)
            rule.waitUntil(10_000) { loaded >= minOf(index + 12, 151) }
        }
        rule.onNodeWithTag("exercise_list").performScrollToIndex(150)
        rule.onNodeWithTag("exercise_list_row_id_150").assertIsDisplayed()
        rule.runOnIdle { assertEquals("Appending must not refresh remote", initialRefreshes, repository.refreshes) }
        rule.onNodeWithTag("exercise_list_search").performTextInput("Exercise 149")
        rule.waitUntil(10_000) { loaded == 1 }
        rule.onNodeWithTag("exercise_list_row_id_149").assertIsDisplayed()
    }

    @Test fun repeatedPullsReachTheViewModelWithoutButtonsAndErrorKeepsCachedRows() {
        show()
        val before = repository.refreshes
        rule.onNodeWithTag("exercise_list_refresh_container").performTouchInput {
            swipeDown(startY = height * .2f, endY = height * .9f, durationMillis = 350)
        }
        rule.waitUntil(10_000) { repository.refreshes > before }
        rule.runOnIdle { repository.success = false }
        rule.onNodeWithTag("exercise_list_refresh_container").performTouchInput {
            swipeDown(startY = height * .2f, endY = height * .9f, durationMillis = 350)
        }
        rule.waitUntil(10_000) { vm.state.value.hasError }
        rule.onNodeWithTag("exercise_list_error").assertExists()
        rule.onNodeWithTag("exercise_list_row_id_000").assertExists()
        rule.onNodeWithTag("exercise_list_loading").assertDoesNotExist()
        rule.onNodeWithTag("exercise_list_refresh").assertDoesNotExist()
        rule.onNodeWithText(rule.activity.getString(com.diajarkoding.imfit.R.string.routine_retry)).assertDoesNotExist()
    }

    @Test fun draggingDownInTheMiddleScrollsWithoutStartingRefresh() {
        show()
        rule.onNodeWithTag("exercise_list").performScrollToIndex(8)
        rule.waitUntil(10_000) { loaded >= 20 }
        rule.onNodeWithTag("exercise_list").performScrollToIndex(15)
        val before = repository.refreshes
        rule.onNodeWithTag("exercise_list_refresh_container").performTouchInput {
            swipeDown(startY = height * .45f, endY = height * .6f, durationMillis = 350)
        }
        rule.runOnIdle { assertEquals("A drag away from the top must only scroll", before, repository.refreshes) }
        rule.onNodeWithTag("exercise_list").performScrollToIndex(0)
        rule.onNodeWithTag("exercise_list_refresh_container").performTouchInput {
            swipeDown(startY = height * .2f, endY = height * .9f, durationMillis = 350)
        }
        rule.waitUntil(10_000) { repository.refreshes > before }
    }
}
