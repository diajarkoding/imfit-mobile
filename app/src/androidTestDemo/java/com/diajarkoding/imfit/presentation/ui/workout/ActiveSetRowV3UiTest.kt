package com.diajarkoding.imfit.presentation.ui.workout

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.diajarkoding.imfit.domain.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** T39/T40 presentation fixtures against the v3 snapshot contract. No account or repository involved. */
class ActiveSetRowV3UiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun cardio(duration: Int? = 1800, completed: Boolean = false) = WorkoutSet(1, weightKg = null, reps = null,
        durationSeconds = duration, isCompleted = completed, id = "00000000-0000-4000-8000-000000000001",
        targetRowId = "t1", measurementVersion = 3)
    private fun strength(completed: Boolean = false) = WorkoutSet(1, weightKg = 40.0, reps = 10, isCompleted = completed,
        id = "00000000-0000-4000-8000-000000000002", targetRowId = "s1", measurementVersion = 3)

    @Test fun onlySupportedMetricsAreShownInCanonicalOrderAndAreOptionalWithClearToNull() {
        val set = mutableStateOf(cardio())
        rule.setContent {
            WorkoutTestEnvironment(indonesian = false) {
                ActiveSetRow(set.value, "Jogging", true, true, false, onComplete = {}, onDelete = {},
                    trackingType = TrackingType.DURATION, modality = ExerciseModality.CARDIO,
                    trackingProfile = TrackingProfile.DISTANCE_ACTIVITY,
                    supportedMetrics = listOf(CardioMetric.LAPS, CardioMetric.SPEED, CardioMetric.DISTANCE),
                    restTargetSeconds = 60, onStopwatchToggle = {},
                    onMeasurementUpdate = { m -> set.value = set.value.copy(distanceMeters = m.distanceMeters,
                        speedKph = m.speedKph, lapReps = m.lapReps, caloriesKcal = m.caloriesKcal, inclinePercent = m.inclinePercent) })
            }
        }
        rule.onNodeWithTag("metric_distance").assertExists()
        rule.onNodeWithTag("metric_speed").assertExists()
        rule.onNodeWithTag("metric_laps").assertExists()
        rule.onNodeWithTag("metric_calories").assertDoesNotExist() // unsupported → not rendered
        rule.onNodeWithTag("metric_incline").assertDoesNotExist()
        rule.onNodeWithText("Optional metrics · empty = not recorded").assertExists()
        val tops = listOf("distance", "speed", "laps").map { rule.onNodeWithTag("metric_$it").fetchSemanticsNode().boundsInRoot }
        assertTrue("canonical order distance, speed, laps (left→right/top→bottom)",
            tops.zipWithNext().all { (a, b) -> b.top > a.top || b.left > a.left })

        rule.onNodeWithContentDescription("Distance (km)").performTextReplacement("5,25")
        rule.runOnIdle { assertEquals(5250.0, set.value.distanceMeters!!, 0.001); assertNull(set.value.speedKph) }
        rule.onNodeWithContentDescription("Speed (km/h)").performTextReplacement("10,5")
        rule.runOnIdle { assertEquals(10.5, set.value.speedKph!!, 0.001); assertEquals(5250.0, set.value.distanceMeters!!, 0.001) } // omitted field unchanged
        rule.onNodeWithContentDescription("Distance (km)").performTextReplacement("")
        rule.runOnIdle { assertNull("clear → null, not 0", set.value.distanceMeters); assertEquals(10.5, set.value.speedKph!!, 0.001) }
        rule.onNodeWithContentDescription("Speed (km/h)").performTextReplacement("1.234") // third decimal rejected
        rule.runOnIdle { assertEquals(10.5, set.value.speedKph!!, 0.001) }
    }

    @Test fun durationOnlyProfileShowsNoMetricFieldsLikeIsometric() {
        rule.setContent {
            WorkoutTestEnvironment(indonesian = false) {
                ActiveSetRow(cardio(), "Jumping Jacks", true, true, false, onComplete = {}, onDelete = {},
                    trackingType = TrackingType.DURATION, modality = ExerciseModality.CARDIO,
                    trackingProfile = TrackingProfile.DURATION_ONLY, supportedMetrics = emptyList(),
                    restTargetSeconds = 60, onStopwatchToggle = {}, onMeasurementUpdate = {})
            }
        }
        for (tag in listOf("distance", "speed", "laps", "calories", "incline")) rule.onNodeWithTag("metric_$tag").assertDoesNotExist()
        rule.onNodeWithText("Optional metrics · empty = not recorded").assertDoesNotExist()
    }

    @Test fun restTargetIsReadOnlyTextIncludingZeroAndNeverAnInput() {
        val rest = mutableStateOf(90)
        rule.setContent {
            WorkoutTestEnvironment(indonesian = false) {
                ActiveSetRow(strength(), "Bench", true, true, false, onComplete = {}, onDelete = {},
                    restTargetSeconds = rest.value, onStopwatchToggle = {}, onMeasurementUpdate = {})
            }
        }
        rule.onNodeWithText("Rest target: 90 s").assertExists().assert(hasNoClickAction())
        rule.runOnIdle { rest.value = 0 }
        rule.onNodeWithText("Rest target: 0 s").assertExists() // 0 is a value, not "missing"
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(2) // only weight and reps are inputs
    }

    @Test fun strengthShowsOnlyWeightAndRepsWithoutAdditionalOrAssistanceEditors() {
        rule.setContent {
            WorkoutTestEnvironment(indonesian = false) {
                ActiveSetRow(strength(), "Bench", true, true, false, onComplete = {}, onDelete = {},
                    restTargetSeconds = 60, onStopwatchToggle = {}, onMeasurementUpdate = {})
            }
        }
        rule.onNodeWithContentDescription("Bench, set 1, weight in kg").assertExists()
        rule.onNodeWithContentDescription("Bench, set 1, reps").assertExists()
        rule.onAllNodesWithText("Add load", substring = true, ignoreCase = true).assertCountEquals(0)
        rule.onAllNodesWithText("Assistance", substring = true, ignoreCase = true).assertCountEquals(0)
    }

    @Test fun completedV3SetCanBeUndoneButLegacyCompletedStaysLockedAndPauseLocksUndo() {
        var undone = 0
        val paused = mutableStateOf(false)
        rule.setContent {
            WorkoutTestEnvironment(indonesian = false) {
                ActiveSetRow(strength(completed = true), "Bench", true, false, paused.value, onComplete = {}, onDelete = {},
                    restTargetSeconds = 60, onStopwatchToggle = {}, onMeasurementUpdate = {}, onUndo = { undone++ })
            }
        }
        rule.onNodeWithContentDescription("Bench, set 1, weight in kg").assertIsNotEnabled() // completed stays read-only
        rule.onNodeWithContentDescription("Undo completion of Bench set 1").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(1, undone); paused.value = true }
        rule.onNodeWithContentDescription("Undo completion of Bench set 1").assertDoesNotExist() // locked while paused
        rule.onNodeWithContentDescription("Complete Bench, set 1").assertIsNotEnabled()
    }

    @Test fun legacyCompletedSetHasNoUndo() {
        rule.setContent {
            WorkoutTestEnvironment(indonesian = false) {
                ActiveSetRow(WorkoutSet(1, 40.0, 10, isCompleted = true), "Bench", true, false, false,
                    onComplete = {}, onDelete = {}, onStopwatchToggle = {}, onMeasurementUpdate = {})
            }
        }
        rule.onNodeWithContentDescription("Complete Bench, set 1").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Undo completion of Bench set 1").assertDoesNotExist()
    }

    @Test fun restDialogCanBePausedAndResumedAndFreezesTheOtherControlsWhilePaused() {
        val paused = mutableStateOf(false)
        var toggles = 0
        rule.setContent {
            WorkoutTestEnvironment(indonesian = false) {
                RestTimerCountdownDialog(remainingSeconds = 42, exerciseName = "Plank", onExtend = {}, onDismiss = {}, onReduce = {},
                    totalSeconds = 90f, isPaused = paused.value, onPauseToggle = { toggles++; paused.value = !paused.value })
            }
        }
        rule.onNodeWithTag("rest_pause_toggle").assertIsEnabled().assertTextContains("Pause")
        rule.onNodeWithText("Skip").assertIsEnabled()
        rule.onNodeWithTag("rest_pause_toggle").performClick()
        rule.runOnIdle { assertEquals(1, toggles) }
        rule.onNodeWithTag("rest_pause_toggle").assertTextContains("Resume")
        rule.onNodeWithText("Paused").assertExists()
        rule.onNodeWithText("Skip").assertIsNotEnabled()
        rule.onNodeWithText("0:42").assertExists() // frozen remaining, not a restarted target
    }

    @Test fun reconciliationBannerExplainsThatNoTimeWasInvented() {
        rule.setContent { WorkoutTestEnvironment(indonesian = false) { ActiveSessionReconciliationBanner() } }
        rule.onNodeWithTag("active_workout_reconciliation_banner").assertExists()
        rule.onNodeWithText("no time was invented", substring = true).assertExists()
    }
}

