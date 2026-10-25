package com.diajarkoding.imfit.presentation.ui.progress

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.theme.IMFITTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class WorkoutHistoryDetailScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private data class Scene(
        val state: ProgressState = sampleState,
        val date: LocalDate = sampleDate,
        val dark: Boolean = false,
        val indonesian: Boolean = true,
        val width: Int = 412,
        val fontScale: Float = 1f
    )

    private val scene = mutableStateOf(Scene())
    private var backCalls = 0
    private var retryCalls = 0

    @Test fun pencilReferenceMatrixRendersAllEightStates() {
        show()
        val variants = listOf(
            "H01-a1m64P-light-id" to Scene(),
            "H02-U6dXe5-dark-id" to Scene(dark = true),
            "H03-gUWYo-light-en" to Scene(indonesian = false),
            "H04-C0SWr0-320-font130" to Scene(
                width = 320,
                fontScale = 1.3f,
                state = sampleStateLongTitles
            ),
            "H05-TXJ9X-loading" to Scene(state = ProgressState(isLoading = true, hasError = false)),
            "H06-c47uVm-empty" to Scene(state = ProgressState(isLoading = false, hasError = false)),
            "H07-uab4Z-error" to Scene(state = ProgressState(isLoading = false, hasError = true)),
            "H08-eFGuU-retained-error-banner" to Scene(state = sampleState.copy(hasError = true))
        )

        for ((name, variant) in variants) {
            rule.runOnIdle { scene.value = variant }
            rule.waitForIdle()

            val presentation = workoutHistoryPresentation(
                workoutsForDate = variant.state.workoutLogsByDate[variant.date] ?: emptyList(),
                isLoading = variant.state.isLoading,
                hasError = variant.state.hasError
            )

            when (presentation) {
                is WorkoutHistoryPresentation.Loading -> {
                    rule.onNodeWithTag("workout_history_loading").assertExists()
                }
                is WorkoutHistoryPresentation.Empty -> {
                    rule.onNodeWithTag("workout_history_empty").assertExists()
                }
                is WorkoutHistoryPresentation.FullError -> {
                    rule.onNodeWithTag("workout_history_error").assertExists()
                    rule.onNodeWithTag("workout_history_retry").assertExists().assertIsEnabled()
                    assertMinimumTarget("workout_history_retry")
                }
                is WorkoutHistoryPresentation.Content -> {
                    rule.onNodeWithTag("workout_history_daily_totals").assertExists()
                    rule.onNodeWithTag("workout_history_session_card_1").assertExists()
                    if (presentation.showUpdateFailureBanner) {
                        rule.onNodeWithTag("workout_history_banner_error").assertExists()
                        rule.onNodeWithTag("workout_history_banner_retry").assertExists().assertIsEnabled()
                        assertMinimumTarget("workout_history_banner_retry")
                    }
                }
            }

            screenshot(name + "-top")
            rule.onNodeWithTag("workout_history_list").performScrollToIndex(0)
        }
    }

    @Test fun backButtonAndRetryCallbacksFunctionProperly() {
        show()
        // Test back button
        rule.onNodeWithContentDescription(localized(R.string.action_back)).performClick()
        rule.runOnIdle { assertEquals(1, backCalls) }

        // Test retry in full error state
        scene.value = Scene(state = ProgressState(isLoading = false, hasError = true))
        rule.waitForIdle()
        rule.onNodeWithTag("workout_history_retry").performClick()
        rule.runOnIdle { assertEquals(1, retryCalls) }

        // Test retry in banner state
        scene.value = Scene(state = sampleState.copy(hasError = true))
        rule.waitForIdle()
        rule.onNodeWithTag("workout_history_banner_retry").performClick()
        rule.runOnIdle { assertEquals(2, retryCalls) }
    }

    @Test fun singleSessionHidesOrdinal() {
        val singleSessionState = ProgressState(
            isLoading = false,
            hasError = false,
            workoutLogsByDate = mapOf(sampleDate to listOf(session1))
        )
        scene.value = Scene(state = singleSessionState)
        show()
        rule.onNodeWithTag("workout_history_session_card_1").assertExists()
        rule.onAllNodesWithText("Sesi 1 dari 1").assertCountEquals(0)
    }

    private fun show() {
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        rule.setContent { Fixture() }
    }

    @Composable
    private fun Fixture() {
        val value = scene.value
        val base = LocalConfiguration.current
        val context = LocalContext.current
        val locale = Locale.forLanguageTag(if (value.indonesian) "id-ID" else "en-US")
        val configuration = remember(base, value.width, value.fontScale, locale) {
            Configuration(base).apply {
                setLocale(locale)
                screenWidthDp = value.width
                fontScale = value.fontScale
            }
        }
        val resources = remember(context, configuration) {
            context.createConfigurationContext(configuration).resources
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = Density(constraints.maxWidth.toFloat() / value.width, value.fontScale)
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalResources provides resources,
                LocalDensity provides density
            ) {
                IMFITTheme(darkTheme = value.dark) {
                    WorkoutHistoryDetailScreenContent(
                        localDate = value.date,
                        state = value.state,
                        onNavigateBack = { backCalls++ },
                        onRetry = { retryCalls++ }
                    )
                }
            }
        }
    }

    private fun assertMinimumTarget(tag: String) {
        val density = rule.onNodeWithTag("workout_history_list").fetchSemanticsNode().boundsInRoot.width / scene.value.width
        val bounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue("$tag height must be at least 48 fixture dp", bounds.height + 1 >= 48 * density)
    }

    private fun localized(id: Int): String {
        val config = Configuration(rule.activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(if (scene.value.indonesian) "id-ID" else "en-US"))
        }
        return rule.activity.createConfigurationContext(config).getString(id)
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        rule.runOnUiThread {
            WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView).apply {
                isAppearanceLightStatusBars = !scene.value.dark
                isAppearanceLightNavigationBars = !scene.value.dark
            }
        }
        val directory = File(rule.activity.filesDir, "workout-history-redesign-evidence").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    companion object {
        private val sampleDate = LocalDate.of(2026, 10, 16)

        private val session1 = WorkoutLog(
            id = "session_1",
            userId = "user_1",
            templateName = "Upper Body",
            date = 1792137600000L,
            startTime = 1792137600000L,
            endTime = 1792140300000L, // 45 minutes
            totalVolume = 3600f,
            exerciseLogs = listOf(
                ExerciseLog(
                    exercise = Exercise("1", "Barbell Bench Press", MuscleCategory.CHEST, ""),
                    sets = (1..3).map { WorkoutSet(it, 100f, 6, isCompleted = true) }
                ),
                ExerciseLog(
                    exercise = Exercise("2", "Incline Dumbbell Press", MuscleCategory.CHEST, ""),
                    sets = (1..3).map { WorkoutSet(it, 60f, 6, isCompleted = true) }
                ),
                ExerciseLog(
                    exercise = Exercise("3", "Triceps Pushdown", MuscleCategory.TRICEPS, ""),
                    sets = (1..3).map { WorkoutSet(it, 40f, 6, isCompleted = true) }
                )
            )
        )

        private val session2 = WorkoutLog(
            id = "session_2",
            userId = "user_1",
            templateName = "Lower Body",
            date = 1792144800000L,
            startTime = 1792144800000L,
            endTime = 1792146000000L, // 20 minutes
            totalVolume = 1200f,
            exerciseLogs = listOf(
                ExerciseLog(
                    exercise = Exercise("4", "Goblet Squat", MuscleCategory.LEGS, ""),
                    sets = (1..3).map { WorkoutSet(it, 50f, 6, isCompleted = true) }
                ),
                ExerciseLog(
                    exercise = Exercise("5", "Dumbbell Romanian Deadlift", MuscleCategory.LEGS, ""),
                    sets = (1..3).map { WorkoutSet(it, 50f, 2, isCompleted = true) }
                )
            )
        )

        private val sampleState = ProgressState(
            isLoading = false,
            hasError = false,
            workoutLogsByDate = mapOf(sampleDate to listOf(session1, session2))
        )

        private val sampleStateLongTitles = ProgressState(
            isLoading = false,
            hasError = false,
            workoutLogsByDate = mapOf(
                sampleDate to listOf(
                    session1.copy(templateName = "Upper Body — Dada dan Trisep"),
                    session2.copy(templateName = "Lower Body — Kaki dan Pinggul")
                )
            )
        )
    }
}
