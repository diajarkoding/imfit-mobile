package com.diajarkoding.imfit.presentation

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.presentation.ui.exercise.*
import com.diajarkoding.imfit.presentation.ui.profile.ProfileScreenContent
import com.diajarkoding.imfit.presentation.ui.progress.ProgressState
import com.diajarkoding.imfit.presentation.ui.progress.WorkoutHistoryDetailScreenContent
import com.diajarkoding.imfit.presentation.ui.workout.*
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

/** Synthetic renderers only: no account, timer, repository or device-setting mutation. */
class HeaderSpacingTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private data class Scene(val page: String = "profile", val compact: Boolean = false)
    private val scene = mutableStateOf(Scene())
    private var density = 1f

    @Test fun taskHeadersHaveExactlyEightDpBeforeFirstContentAtNormalAndLargeFont() {
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        rule.setContent {
            val current = scene.value
            WorkoutTestEnvironment(width = if (current.compact) 320 else 412,
                fontScale = if (current.compact) 1.5f else 1f) {
                density = LocalDensity.current.density
                key(current) {
                    when (current.page) {
                        "profile", "profile-loading" -> ProfileScreenContent(
                            ProgressState(isLoading = current.page.endsWith("loading"), userName =
                                if (current.page.endsWith("loading")) "" else "Synthetic User"),
                            false, true, {}, {}, {}, {}, {})
                        "catalog" -> ExerciseListContent(ExerciseCategory.CHEST, ExerciseBrowserState(), {}, {}, {})
                        "picker" -> ExercisePickerContent(ExerciseSelectionState(), onBack = {}, onSearch = {},
                            onCategory = {}, onToggle = {}, onReset = {}, onClearFilters = {}, onRetry = {},
                            onRefresh = {}, onLoadNext = {}, onFilter = {}, onConfirm = {})
                        "detail", "detail-loading" -> WorkoutDetailContent(
                            WorkoutDetailState(workout = if (current.page.endsWith("loading")) null
                                else WorkoutTemplate("fixture", "fixture", "Synthetic Routine", emptyList()),
                                isLoading = current.page.endsWith("loading")), 0)
                        "edit", "edit-loading" -> EditWorkoutContent(EditWorkoutState(
                            workoutName = "Synthetic Routine", hasLoaded = !current.page.endsWith("loading"),
                            isLoading = current.page.endsWith("loading")))
                        "active", "active-loading" -> ActiveWorkoutScreenContent(
                            ActiveWorkoutState(session = if (current.page.endsWith("loading")) null
                                else WorkoutSession("fixture", "fixture", "Synthetic Session", 0, emptyList())),
                            SnackbarHostState(), {}, {}, {}, {}, {}, {}, { _, _, _, _ -> }, { _, _ -> }, {}, { _, _ -> })
                        "summary-loading", "summary-unavailable" -> WorkoutSummaryScreenContent(
                            if (current.page.endsWith("loading")) WorkoutSummaryPresentation.Loading
                            else WorkoutSummaryPresentation.Unavailable, {})
                        "history" -> WorkoutHistoryDetailScreenContent(LocalDate.of(2026, 10, 18),
                            ProgressState(isLoading = false), {}, {})
                    }
                }
            }
        }
        val cases = listOf(
            Triple("profile", "screen_header", "profile_identity"),
            Triple("profile-loading", "screen_header", "profile_loading"),
            Triple("catalog", "screen_header", "exercise_list_search"),
            Triple("picker", "screen_header", "exercise_picker_search"),
            Triple("detail", "screen_header", "routine_detail_list"),
            Triple("detail-loading", "screen_header", "routine_detail_list"),
            Triple("edit", "screen_header", "routine_edit_list"),
            Triple("edit-loading", "screen_header", "routine_edit_list"),
            Triple("active", "active_workout_top_bar", "active_session_overview"),
            Triple("active-loading", "active_workout_top_bar", "active_workout_loading"),
            Triple("summary-loading", "workout_summary_top_bar", "workout_summary_loading"),
            Triple("summary-unavailable", "workout_summary_top_bar", "workout_summary_unavailable"),
            Triple("history", "screen_header", "workout_history_date_header")
        )
        for (compact in listOf(false, true)) for ((page, headerTag, contentTag) in cases) {
            rule.runOnIdle { scene.value = Scene(page, compact) }
            rule.waitForIdle()
            val header = rule.onNodeWithTag(headerTag).fetchSemanticsNode().boundsInRoot
            val content = rule.onNodeWithTag(contentTag).fetchSemanticsNode()
            // Lazy prefetch can expose off-viewport semantics with an empty clipped rect.
            val firstTop = if (contentTag.endsWith("_list")) content.children
                .filter { it.boundsInRoot.height > 0f }.minOf { it.boundsInRoot.top }
                else content.boundsInRoot.top
            assertEquals("$page compact=$compact", 8 * density, firstTop - header.bottom, 2f)
        }
    }
}
