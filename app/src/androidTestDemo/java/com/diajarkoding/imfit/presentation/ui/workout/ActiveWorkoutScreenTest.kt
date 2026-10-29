package com.diajarkoding.imfit.presentation.ui.workout

import androidx.activity.enableEdgeToEdge
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.data.remote.online.SessionSnapshot
import com.diajarkoding.imfit.data.remote.online.SessionStatus
import com.diajarkoding.imfit.data.remote.online.SessionExercise
import com.diajarkoding.imfit.data.remote.online.SessionSavePhase
import com.diajarkoding.imfit.data.remote.online.InputDefaults
import com.diajarkoding.imfit.data.remote.online.WorkoutSet as RemoteWorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ActiveWorkoutScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private data class Scene(
        val state: ActiveWorkoutState = sampleActiveState,
        val dark: Boolean = false,
        val indonesian: Boolean = true,
        val width: Int = 412,
        val fontScale: Float = 1f
    )

    private val scene = mutableStateOf(Scene())
    private var cancelClicks = 0
    private var pauseClicks = 0
    private var finishClicks = 0

    @Test
    fun pencilMatrixRendersAllSessionStates() {
        showScreen()
        val variants = listOf(
            "A01-light-id" to Scene(),
            "A02-dark-id" to Scene(dark = true),
            "A03-light-en" to Scene(indonesian = false),
            "A04-320-font130" to Scene(
                width = 320,
                fontScale = 1.3f,
                state = sampleActiveState
            ),
            "A05-paused" to Scene(
                state = sampleActiveState.copy(isPaused = true)
            ),
            "A06-all-completed" to Scene(
                state = sampleActiveState.copy(
                    session = sampleActiveState.session?.copy(
                        exerciseLogs = listOf(
                            ExerciseLog(
                                exercise = Exercise("ex_1", "Barbell Bench Press", MuscleCategory.CHEST, ""),
                                sets = listOf(
                                    WorkoutSet(1, 60f, 10, isCompleted = true),
                                    WorkoutSet(2, 60f, 10, isCompleted = true)
                                )
                            )
                        )
                    )
                )
            ),
            "A07-empty" to Scene(
                state = sampleActiveState.copy(
                    session = sampleActiveState.session?.copy(exerciseLogs = emptyList())
                )
            ),
            "A08-saving" to Scene(state = sampleActiveState.copy(isFinishing = true)),
            "A09-retained-after-error" to Scene(state = sampleActiveState.copy(mutationErrorRes = R.string.error_session_finish)),
            "A10-loading" to Scene(state = ActiveWorkoutState())
        )

        for ((name, variant) in variants) {
            rule.runOnIdle { scene.value = variant }
            rule.waitForIdle()

            val session = variant.state.session
            if (session == null) {
                rule.onNodeWithTag("active_workout_loading").assertExists()
            } else if (session.exerciseLogs.isEmpty()) {
                rule.onNodeWithTag("active_workout_empty_state").assertExists()
            } else {
                rule.onNodeWithTag("active_workout_screen").assertExists()
                rule.onNodeWithTag("active_workout_top_bar").assertExists()
                rule.onNodeWithTag("active_session_overview").assertExists()
                rule.onNodeWithTag("active_workout_footer").assertExists()

                if (variant.state.isPaused) {
                    rule.onNodeWithTag("active_workout_pause_banner").assertExists()
                }
                val total = session.totalSets
                val completed = session.totalCompletedSets
                if (total > 0 && completed == total && !variant.state.isPaused) {
                    rule.onNodeWithTag("active_workout_finish_banner").assertExists()
                }
            }
            captureWorkoutEvidence(name)
            if (variant.width == 320) {
                rule.onNodeWithTag("active_workout_list").performScrollToNode(hasText("Tambah gerakan"))
                rule.onNodeWithText("Tambah gerakan").assertIsDisplayed()
                captureWorkoutEvidence("$name-scrolled")
                // Reset before the next variant, without relying on a fixed delay.
                rule.onNodeWithTag("active_workout_list").performScrollToNode(hasText("Set selesai"))
            }
        }
    }

    @Test
    fun savingDisablesHeaderAndFooterActionsWithoutLosingSession() {
        scene.value = Scene(state = sampleActiveState.copy(isFinishing = true))
        showScreen()
        rule.onNodeWithContentDescription("Batal").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Jeda sesi").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Timer Istirahat").assertIsNotEnabled()
        rule.onNodeWithText("Menyimpan sesi…").assertIsNotEnabled()
        rule.runOnIdle { scene.value = Scene(state = sampleActiveState.copy(mutationErrorRes = R.string.error_session_finish)) }
        rule.onNodeWithContentDescription("Jeda sesi").assertIsEnabled()
        rule.onNodeWithText("Chest & Triceps Focus").assertExists()
        rule.onNodeWithText("Selesaikan sesi").assertIsEnabled()
    }

    @Test
    fun cancelDialogVariationsRenderCorrectMessages() {
        var confirmed = false
        var dismissed = false

        showContent(indonesian = true) {
            CancelWorkoutDialog(
                completedSets = 2,
                totalSets = 6,
                onConfirm = { confirmed = true },
                onDismiss = { dismissed = true }
            )
        }
        rule.waitForIdle()

        rule.onNodeWithTag("cancel_workout_dialog").assertExists()
        rule.onNodeWithText("Batalkan sesi?").assertExists()
        rule.onNodeWithText("2 dari 6 set sudah selesai. Jika dibatalkan, progres sesi ini akan hilang dan tidak dicatat ke riwayat.").assertExists()

        rule.onNodeWithText("Batalkan sesi").performClick()
        rule.waitForIdle()
        assertEquals(true, confirmed)
    }

    @Test
    fun restTimerCountdownDialogRendersAndAdjustsBothDirections() {
        var extendClicked = false
        var reduceClicked = false
        var dismissClicked = false

        showContent(indonesian = true) {
            RestTimerCountdownDialog(
                remainingSeconds = 45,
                totalSeconds = 60f,
                exerciseName = "Barbell Bench Press",
                onExtend = { extendClicked = true },
                onReduce = { reduceClicked = true },
                onDismiss = { dismissClicked = true }
            )
        }
        rule.waitForIdle()

        rule.onNodeWithTag("rest_timer_countdown_dialog").assertExists()
        rule.onNodeWithText("0:45").assertExists()
        rule.onNodeWithText("Barbell Bench Press").assertExists()

        rule.onNodeWithText("−30 detik").performClick()
        rule.waitForIdle()
        assertEquals(true, reduceClicked)
        assertEquals(false, dismissClicked)

        rule.onNodeWithText("+30 detik").performClick()
        rule.waitForIdle()
        assertEquals(true, extendClicked)

        rule.onNodeWithText("Lewati").performClick()
        rule.waitForIdle()
        assertEquals(true, dismissClicked)
    }

    @Test
    fun restTimerConfigDialogRendersAndModifiesDuration() {
        var selectedDuration = 0

        showContent(indonesian = true) {
            RestTimerConfigDialog(
                currentRestSeconds = 90,
                soundEnabled = true,
                onSoundChange = {},
                vibrationEnabled = true,
                onVibrationChange = {},
                fullScreenAllowed = true,
                onAlarmSettings = {},
                onDismiss = {},
                onConfirm = { selectedDuration = it }
            )
        }
        rule.waitForIdle()

        rule.onNodeWithTag("rest_timer_config_dialog").assertExists()
        rule.onNodeWithText("Timer Istirahat").assertExists()

        rule.onNodeWithText("Simpan").performClick()
        rule.waitForIdle()
        assertEquals(90, selectedDuration)
    }

    private fun showContent(
        indonesian: Boolean = true,
        dark: Boolean = false,
        content: @Composable () -> Unit
    ) {
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        rule.setContent {
            WorkoutTestEnvironment(indonesian = indonesian, dark = dark, content = content)
        }
    }

    private fun showScreen() {
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        rule.setContent {
            val current = scene.value
            WorkoutTestEnvironment(indonesian = current.indonesian, dark = current.dark,
                width = current.width, fontScale = current.fontScale) {
                    val snackbarHostState = remember { SnackbarHostState() }
                    Box(Modifier.fillMaxSize()) {
                        ActiveWorkoutScreenContent(
                            state = current.state,
                            snackbarHostState = snackbarHostState,
                            onCancelClick = { cancelClicks++ },
                            onPauseToggle = { pauseClicks++ },
                            onRestSettingsClick = {},
                            onFinishClick = { finishClicks++ },
                            onAddExercises = {},
                            onRemoveExercise = {},
                            onSetUpdate = { _, _, _ -> },
                            onSetComplete = {},
                            onAddSet = {},
                            onRemoveSet = {}
                        )
                    }
            }
        }
    }

    companion object {
        private val sampleExerciseLogs = listOf(
            ExerciseLog(
                exercise = Exercise("ex_1", "Barbell Bench Press", MuscleCategory.CHEST, ""),
                sets = listOf(
                    WorkoutSet(1, 60f, 10, isCompleted = true),
                    WorkoutSet(2, 65f, 10, isCompleted = true),
                    WorkoutSet(3, 70f, 8, isCompleted = false)
                )
            ),
            ExerciseLog(
                exercise = Exercise("ex_2", "Dumbbell Fly", MuscleCategory.CHEST, ""),
                sets = listOf(
                    WorkoutSet(1, 14f, 12, isCompleted = false),
                    WorkoutSet(2, 14f, 12, isCompleted = false)
                )
            )
        )

        private val sampleConfirmed = SessionSnapshot(
            id = "session_test_1", version = 1, status = SessionStatus.ACTIVE, effectiveStatus = SessionStatus.ACTIVE,
            reviewRequired = false, templateId = "tmpl_1", templateName = "Chest & Triceps Focus", templateRevision = 1,
            startedAt = "2026-10-29T10:00:00Z", pausedAt = null, totalPausedMs = 0,
            lastActivityAt = "2026-10-29T10:00:00Z", expiresAt = "2026-11-28T10:00:00Z", schemaVersion = 1,
            currentExerciseId = "child-ex_1", completedWorkoutId = null,
            exercises = sampleExerciseLogs.mapIndexed { index, log ->
                SessionExercise("child-${log.exercise.id}", log.exercise.id, log.exercise.name, index, 60,
                    InputDefaults(log.sets.size, 10), log.sets.map {
                        RemoteWorkoutSet("${log.exercise.id}-${it.setNumber}", it.setNumber, it.weight.toString(), it.reps, it.isCompleted)
                    }, "Chest")
            }
        )

        val sampleActiveState = ActiveWorkoutState(
            confirmed = sampleConfirmed,
            entryPhase = SessionEntryPhase.CONFIRMED,
            savePhase = SessionSavePhase.CONFIRMED,
            requiresVerification = false,
            session = WorkoutSession(
                id = "session_test_1",
                templateId = "tmpl_1",
                templateName = "Chest & Triceps Focus",
                startTime = System.currentTimeMillis() - 1200_000L,
                exerciseLogs = sampleExerciseLogs,
                currentExerciseIndex = 0
            ),
            elapsedSeconds = 1200L,
            isPaused = false,
            isFinishing = false,
            isCancelling = false
        )
    }
}
