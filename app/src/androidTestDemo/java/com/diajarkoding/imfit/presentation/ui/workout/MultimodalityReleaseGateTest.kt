package com.diajarkoding.imfit.presentation.ui.workout

import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.SetMeasurement
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.TrackingType
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.domain.model.checkpointExecutionStopwatch
import com.diajarkoding.imfit.domain.model.freezeExecutionStopwatch
import com.diajarkoding.imfit.domain.model.toggleExecutionStopwatch
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Consumer-visible release gates; no account, network, service, or device setting changes. */
class MultimodalityReleaseGateTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val set = mutableStateOf(WorkoutSet(1))
    private val paused = mutableStateOf(false)
    private val mode = mutableStateOf(TrackingType.DURATION)

    private val bootCount: Int
        get() = Settings.Global.getInt(rule.activity.contentResolver, Settings.Global.BOOT_COUNT)
    private fun showSet() {
        rule.setContent {
            WorkoutTestEnvironment(indonesian = false) {
                // The production controller persists checkpoints; this UI fixture models that owner.
                LaunchedEffect(paused.value, set.value.isCompleted) {
                    if (paused.value) {
                        set.value = set.value.freezeExecutionStopwatch(SystemClock.elapsedRealtime(), bootCount)
                    } else {
                        while (!set.value.isCompleted) {
                            delay(200)
                            set.value = set.value.checkpointExecutionStopwatch(SystemClock.elapsedRealtime(), bootCount)
                        }
                    }
                }
                key(mode.value) {
                    ActiveSetRow(set.value, "Exercise", true, true, paused.value,
                        onComplete = {
                            set.value = set.value.freezeExecutionStopwatch(SystemClock.elapsedRealtime(), bootCount)
                                .copy(isCompleted = true)
                        },
                        onDelete = {}, trackingType = mode.value,
                        onMeasurementUpdate = { measurement ->
                            // Match the active workout's immutable completed-set policy.
                            if (!set.value.isCompleted) set.value = set.value.withMeasurement(measurement)
                        },
                        onStopwatchToggle = {
                            set.value = set.value.toggleExecutionStopwatch(SystemClock.elapsedRealtime(), bootCount)
                        })
                }
            }
        }
    }

    @Test fun allSixModesAcceptTheirActualInputs() {
        showSet()
        val inputs = listOf(
            TrackingType.WEIGHT_REPS to listOf("Exercise, set 1, weight in kg" to "40", "Exercise, set 1, reps" to "10"),
            TrackingType.BODYWEIGHT_REPS to listOf("Exercise, set 1, reps" to "15"),
            TrackingType.ASSISTED_REPS to listOf("-kg bantuan mesin" to "20", "Exercise, set 1, reps" to "8"),
            TrackingType.REPS_ONLY to listOf("Exercise, set 1, reps" to "30"),
            TrackingType.DURATION to listOf("Durasi dalam detik" to "45"),
            TrackingType.DISTANCE_DURATION to listOf("Jarak dalam meter" to "5000", "Durasi dalam detik" to "1800")
        )
        inputs.forEach { (type, fields) ->
            rule.runOnIdle { set.value = WorkoutSet(1); mode.value = type }
            fields.forEach { (label, value) ->
                rule.onNodeWithContentDescription(label).performTextReplacement(value)
            }
            rule.onNodeWithContentDescription("Complete Exercise, set 1").assertIsEnabled().performClick()
            rule.runOnIdle { assertTrue("Completion must commit for $type", set.value.isCompleted) }
            captureWorkoutEvidence("phase5-${type.name}")
        }
    }

    @Test fun zeroExternalWeightCannotCompleteStrengthSet() {
        mode.value = TrackingType.WEIGHT_REPS
        showSet()
        rule.onNodeWithContentDescription("Exercise, set 1, weight in kg").performTextReplacement("0")
        rule.onNodeWithContentDescription("Exercise, set 1, reps").performTextReplacement("10")
        rule.onNodeWithContentDescription("Complete Exercise, set 1").assertIsNotEnabled()
    }

    @Test fun manualMinutesAndSecondsPersistAsNinetySeconds() {
        showSet()
        rule.onNodeWithContentDescription("Durasi dalam detik").performTextReplacement("01:30")
        captureWorkoutEvidence("phase5-manual-mmss")
        rule.runOnIdle { assertEquals("01:30 must represent 90 seconds", 90, set.value.durationSeconds) }
    }

    @Test fun workoutPauseAndResumeNeverDiscardElapsedSetDuration() {
        showSet()
        rule.onNodeWithContentDescription("Start Stopwatch").performClick()
        rule.waitUntil(8_000) { (set.value.durationSeconds ?: 0) >= 2 }
        var beforePause = 0
        rule.runOnIdle {
            set.value = set.value.freezeExecutionStopwatch(SystemClock.elapsedRealtime(), bootCount)
            beforePause = set.value.durationSeconds!!
            paused.value = true
        }
        SystemClock.sleep(1_200)
        rule.runOnIdle { assertEquals(beforePause, set.value.durationSeconds) }
        rule.runOnIdle { paused.value = false }
        SystemClock.sleep(650)
        captureWorkoutEvidence("phase5-stopwatch-resume")
        rule.runOnIdle {
            assertTrue("Resume lost duration: before=$beforePause after=${set.value.durationSeconds}",
                set.value.durationSeconds!! >= beforePause)
        }
    }

    @Test fun completedSetStopsItsExecutionStopwatch() {
        showSet()
        rule.onNodeWithContentDescription("Start Stopwatch").performClick()
        rule.waitUntil(8_000) { (set.value.durationSeconds ?: 0) >= 1 }
        rule.onNodeWithContentDescription("Complete Exercise, set 1").performClick()
        var finalSeconds = 0
        rule.runOnIdle { finalSeconds = set.value.durationSeconds!! }
        SystemClock.sleep(1_500)
        captureWorkoutEvidence("phase5-stopwatch-completed")
        rule.onNodeWithContentDescription("Durasi dalam detik").assertTextEquals(finalSeconds.toString())
    }

    @Test fun routineDurationEditChangesTheTargetShownToTheUser() {
        val config = mutableStateOf(RawExerciseConfig("3", rest = "60",
            trackingType = TrackingType.DURATION, durationSeconds = "45"))
        val exercise = TemplateExercise(Exercise("ex_core_1", "Plank", MuscleCategory.CORE, ""),
            trackingType = TrackingType.DURATION, reps = null, targetDurationSeconds = 45)
        rule.setContent {
            WorkoutTestEnvironment(indonesian = false) {
                RoutineExerciseEditor(exercise, true, {}, rawConfig = config.value,
                    onRawConfig = { config.value = it }, onTrackingMode = {})
            }
        }
        rule.onNodeWithContentDescription("Duration (s)").performTextReplacement("90")
        captureWorkoutEvidence("phase5-routine-duration-edit")
        rule.onNodeWithContentDescription("Duration (s)").assertTextEquals("90")
    }

    @Test fun routineDistanceAndDurationFieldsDeliverTheirActualRawValues() {
        val config = mutableStateOf(RawExerciseConfig("3", rest = "60",
            trackingType = TrackingType.DISTANCE_DURATION, durationSeconds = "600", distanceMeters = "1000"))
        val exercise = TemplateExercise(Exercise("ex_cardio_1", "Run", MuscleCategory.CARDIO, ""),
            reps = null, targetDurationSeconds = 600, targetDistanceMeters = 1000)
        var selectedMode: TrackingType? = null
        rule.setContent {
            WorkoutTestEnvironment(indonesian = false) {
                RoutineExerciseEditor(exercise, true, {}, rawConfig = config.value,
                    onRawConfig = { config.value = it }, onTrackingMode = {
                        selectedMode = it
                        config.value = config.value.copy(trackingType = it, distanceMeters = "")
                    })
            }
        }
        rule.onNodeWithContentDescription("Distance (m, optional)").performTextReplacement("5000")
        rule.onNodeWithContentDescription("Duration (s)").performTextReplacement("1800")
        rule.onNodeWithContentDescription("Distance (m, optional)").assertTextEquals("5000")
        rule.onNodeWithContentDescription("Duration (s)").assertTextEquals("1800")
        rule.runOnIdle {
            assertEquals("5000", config.value.distanceMeters)
            assertEquals("1800", config.value.durationSeconds)
            assertEquals("3", config.value.sets)
            assertEquals("60", config.value.rest)
        }
        rule.onNodeWithText("Duration").performClick()
        rule.runOnIdle { assertEquals(TrackingType.DURATION, selectedMode) }
        rule.onNodeWithText("Duration").assertIsSelected()
        rule.onNodeWithContentDescription("Distance (m, optional)").assertDoesNotExist()
        rule.onNodeWithContentDescription("Duration (s)").assertTextEquals("1800")
        rule.onNodeWithText("External weight and reps").assertDoesNotExist()
    }

    private fun WorkoutSet.withMeasurement(m: SetMeasurement) = copy(
        weight = m.weight ?: 0f, reps = m.reps ?: 0,
        durationSeconds = m.durationSeconds, distanceMeters = m.distanceMeters,
        additionalWeight = m.additionalWeight, assistanceWeight = m.assistanceWeight)
}
