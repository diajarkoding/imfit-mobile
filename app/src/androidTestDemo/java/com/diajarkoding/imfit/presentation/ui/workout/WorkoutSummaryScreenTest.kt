package com.diajarkoding.imfit.presentation.ui.workout

import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutSummaryScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private data class Scene(
        val presentation: WorkoutSummaryPresentation = WorkoutSummaryPresentation.Content(sampleLog),
        val dark: Boolean = false,
        val indonesian: Boolean = true,
        val width: Int = 412,
        val fontScale: Float = 1f
    )

    private val scene = mutableStateOf(Scene())
    private var homeCalls = 0

    @Test
    fun pencilReferenceMatrixRendersAllSummaryStates() {
        show()
        val variants = listOf(
            "S01-light-id" to Scene(),
            "S02-dark-id" to Scene(dark = true),
            "S03-light-en" to Scene(indonesian = false),
            "S04-320-font130" to Scene(
                width = 320,
                fontScale = 1.3f,
                presentation = WorkoutSummaryPresentation.Content(sampleLog)
            ),
            "S05-loading" to Scene(presentation = WorkoutSummaryPresentation.Loading),
            "S06-unavailable" to Scene(presentation = WorkoutSummaryPresentation.Unavailable),
            "S07-no-completed-sets" to Scene(
                presentation = WorkoutSummaryPresentation.Content(sampleLogIncomplete)
            )
        )

        for ((name, variant) in variants) {
            rule.runOnIdle { scene.value = variant }
            rule.waitForIdle()

            when (val pres = variant.presentation) {
                is WorkoutSummaryPresentation.Loading -> {
                    rule.onNodeWithTag("workout_summary_loading").assertExists()
                }
                is WorkoutSummaryPresentation.Unavailable -> {
                    rule.onNodeWithTag("workout_summary_unavailable").assertExists()
                }
                is WorkoutSummaryPresentation.Content -> {
                    rule.onNodeWithTag("workout_summary_screen").assertExists()
                    rule.onNodeWithTag("workout_summary_top_bar").assertExists()
                    rule.onNodeWithTag("workout_summary_outcome").assertExists()
                    rule.onNodeWithTag("workout_summary_metrics").assertExists()
                    rule.onNodeWithTag("workout_summary_footer").assertExists()
                    rule.onNodeWithText(pres.log.templateName).assertExists()
                }
            }
            val header = rule.onNodeWithTag("workout_summary_top_bar").fetchSemanticsNode().boundsInRoot
            val firstTag = when (variant.presentation) {
                WorkoutSummaryPresentation.Loading -> "workout_summary_loading"
                WorkoutSummaryPresentation.Unavailable -> "workout_summary_unavailable"
                is WorkoutSummaryPresentation.Content -> "workout_summary_outcome"
            }
            val first = rule.onNodeWithTag(firstTag).fetchSemanticsNode().boundsInRoot
            // WorkoutTestEnvironment maps the physical width to the fixture dp width.
            val density = rule.activity.resources.displayMetrics.widthPixels.toFloat() / variant.width
            assertEquals("$name header gap", 8 * density, first.top - header.bottom, 2f)
            captureWorkoutEvidence(name)
        }
    }

    @Test
    fun completedSetDetailsKeepFractionalWeightAndExcludeUnfinishedSets() {
        scene.value = Scene(presentation = WorkoutSummaryPresentation.Content(sampleLog.copy(
            exerciseLogs = listOf(sampleExercises.first().copy(sets = listOf(
                WorkoutSet(3, 62.5f, 10, isCompleted = true),
                WorkoutSet(4, 99.5f, 20, isCompleted = false)
            )))
        )))
        show()
        rule.onNodeWithTag("workout_summary_list").performScrollToNode(hasText("62,5 kg × 10 repetisi"))
        rule.onNodeWithText("62,5 kg × 10 repetisi").assertIsDisplayed()
        rule.onNodeWithText("99,5 kg × 20 repetisi").assertDoesNotExist()
    }

    @Test
    fun doneButtonTriggersHomeCallback() {
        show()
        homeCalls = 0
        rule.runOnIdle {
            scene.value = Scene(presentation = WorkoutSummaryPresentation.Content(sampleLog))
        }
        rule.waitForIdle()

        rule.onNode(hasTestTag("workout_summary_footer")).assertExists()
        val doneLabel = if (scene.value.indonesian) "Selesai" else "Done"
        rule.onNodeWithText(doneLabel).performClick()

        assertEquals(1, homeCalls)
    }

    @Test
    fun unavailableButtonTriggersHomeCallback() {
        show()
        homeCalls = 0
        rule.runOnIdle {
            scene.value = Scene(presentation = WorkoutSummaryPresentation.Unavailable)
        }
        rule.waitForIdle()

        rule.onNodeWithTag("workout_summary_unavailable").assertExists()
        val backLabel = if (scene.value.indonesian) "Kembali ke Beranda" else "Back to Home"
        rule.onNodeWithText(backLabel).performClick()

        assertEquals(1, homeCalls)
    }

    private fun show() {
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        rule.setContent {
            val current = scene.value
            WorkoutTestEnvironment(indonesian = current.indonesian, dark = current.dark,
                width = current.width, fontScale = current.fontScale) {
                        WorkoutSummaryScreenContent(
                            presentation = current.presentation,
                            onNavigateToHome = { homeCalls++ }
                        )
            }
        }
    }

    companion object {
        private val sampleExercises = listOf(
            ExerciseLog(
                exercise = Exercise("1", "Barbell Bench Press", MuscleCategory.CHEST, ""),
                sets = listOf(
                    WorkoutSet(1, 60f, 10, isCompleted = true),
                    WorkoutSet(2, 65f, 10, isCompleted = true),
                    WorkoutSet(3, 70f, 8, isCompleted = true)
                )
            ),
            ExerciseLog(
                exercise = Exercise("2", "Incline Dumbbell Press", MuscleCategory.CHEST, ""),
                sets = listOf(
                    WorkoutSet(1, 22f, 12, isCompleted = true),
                    WorkoutSet(2, 24f, 10, isCompleted = true)
                )
            )
        )

        val sampleLog = WorkoutLog(
            id = "log_demo_1",
            userId = "usr_1",
            templateName = "Upper Body Hypertrophy",
            date = 1700000000000L,
            startTime = 1700000000000L,
            endTime = 1700002880000L,
            totalVolume = 3280f,
            exerciseLogs = sampleExercises
        )

        val sampleLogIncomplete = WorkoutLog(
            id = "log_demo_2",
            userId = "usr_1",
            templateName = "Full Body Starter",
            date = 1700000000000L,
            startTime = 1700000000000L,
            endTime = 1700000900000L,
            totalVolume = 0f,
            exerciseLogs = listOf(
                ExerciseLog(
                    exercise = Exercise("1", "Barbell Bench Press", MuscleCategory.CHEST, ""),
                    sets = listOf(
                        WorkoutSet(1, 60f, 10, isCompleted = false),
                        WorkoutSet(2, 60f, 10, isCompleted = false)
                    )
                )
            )
        )
    }
}
