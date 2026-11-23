package com.diajarkoding.imfit.presentation.ui.workout

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.ExerciseModality
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.TrackingType
import com.diajarkoding.imfit.domain.model.WorkoutSet
import org.junit.Rule
import org.junit.Test
import java.util.Locale

class ActiveSessionOverviewTest {
    @get:Rule val rule = createComposeRule()

    private val bench = Exercise(
        id = "ex_bench",
        name = "Bench Press",
        muscleCategory = MuscleCategory.CHEST,
        description = "Chest",
        defaultTrackingType = TrackingType.WEIGHT_REPS,
        modality = ExerciseModality.STRENGTH
    )
    private val run = Exercise(
        id = "ex_run",
        name = "Treadmill Run",
        muscleCategory = MuscleCategory.LEGS,
        description = "Cardio",
        defaultTrackingType = TrackingType.DISTANCE_DURATION,
        modality = ExerciseModality.CARDIO
    )
    private val logs = listOf(
        ExerciseLog(
            exercise = bench,
            trackingType = TrackingType.WEIGHT_REPS,
            modality = ExerciseModality.STRENGTH,
            sets = listOf(
                WorkoutSet(setNumber = 1, weightKg = 40.0, reps = 10, isCompleted = true),
                WorkoutSet(setNumber = 2, weightKg = 40.0, reps = 10, isCompleted = false)
            )
        ),
        ExerciseLog(
            exercise = run,
            trackingType = TrackingType.DISTANCE_DURATION,
            modality = ExerciseModality.CARDIO,
            sets = listOf(WorkoutSet(setNumber = 1, durationSeconds = 1800, distanceMeters = 5000.0, isCompleted = true))
        )
    )

    @Test fun compactOverviewShowsSetProgressAndSecondaryLine() {
        rule.setContent {
            WorkoutTestEnvironment(indonesian = true) {
                ActiveSessionOverview(2, 3, logs, Locale.forLanguageTag("id-ID"))
            }
        }
        rule.onNodeWithTag("active_session_overview").assertIsDisplayed()
        rule.onNodeWithText("Set selesai").assertIsDisplayed()
        rule.onNodeWithText("2").assertIsDisplayed()
        rule.onNodeWithText("/ 3").assertIsDisplayed()
        rule.onNodeWithText("10 reps · 400 kg · 30 mnt kardio").assertIsDisplayed()
        rule.onNodeWithText("Statistik").assertIsDisplayed()
        rule.onNodeWithTag("active_session_stats_list").assertDoesNotExist()
    }

    @Test fun statsToggleOpensFullBreakdownAndCloses() {
        rule.setContent {
            WorkoutTestEnvironment(indonesian = true) {
                ActiveSessionOverview(2, 3, logs, Locale.forLanguageTag("id-ID"))
            }
        }
        rule.onNodeWithTag("active_session_stats_toggle").performClick()
        rule.onNodeWithTag("active_session_stats_list").assertIsDisplayed()
        rule.onNodeWithText("Tutup").assertIsDisplayed()
        rule.onNodeWithText("Repetisi").assertIsDisplayed()
        rule.onNodeWithText("Volume beban").assertIsDisplayed()
        rule.onNodeWithText("Kardio").assertIsDisplayed()
        rule.onNodeWithText("Jarak").assertIsDisplayed()
        rule.onNodeWithText("5,0 km").assertIsDisplayed()
        rule.onNodeWithTag("active_session_secondary_line").assertDoesNotExist()
        rule.onNodeWithTag("active_session_stats_toggle").performClick()
        rule.onNodeWithTag("active_session_stats_list").assertDoesNotExist()
        rule.onNodeWithText("Statistik").assertIsDisplayed()
    }
}
