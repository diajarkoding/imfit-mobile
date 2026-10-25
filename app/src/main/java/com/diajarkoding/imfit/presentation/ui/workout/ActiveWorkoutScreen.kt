package com.diajarkoding.imfit.presentation.ui.workout

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.core.service.WorkoutService
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.presentation.components.common.Add
import com.diajarkoding.imfit.presentation.components.common.RoutineButton
import com.diajarkoding.imfit.presentation.components.common.Symbols
import com.diajarkoding.imfit.theme.IMFITColors
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.IMFITTheme
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
fun ActiveWorkoutScreen(
    templateId: String,
    onNavigateBack: () -> Unit,
    onWorkoutFinished: (String) -> Unit,
    viewModel: ActiveWorkoutViewModel = hiltViewModel(),
    onAddExercises: (String, List<String>) -> Unit = { _, _ -> },
    selectionSessionId: String? = null,
    selectedExerciseIds: List<String>? = null,
    onSelectedExercisesConsumed: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showRestConfigSheet by rememberSaveable { mutableStateOf(false) }
    val restState by viewModel.restState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val serviceErrorMessage = stringResource(R.string.workout_background_service_error)
    val settingsErrorMessage = stringResource(R.string.workout_alarm_settings_error)
    val timerStorageMessage = stringResource(R.string.rest_storage_error)

    // Service binding
    var workoutService by remember { mutableStateOf<WorkoutService?>(null) }
    var isBound by remember { mutableStateOf(false) }

    val serviceConnection = remember {
        object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val binder = service as? WorkoutService.WorkoutBinder
                workoutService = binder?.getService()
                isBound = true
                viewModel.refreshTimerCapabilities()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                workoutService = null
                isBound = false
            }
        }
    }

    DisposableEffect(Unit) {
        val intent = Intent(context, WorkoutService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        onDispose {
            if (isBound) {
                context.unbindService(serviceConnection)
                isBound = false
            }
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) viewModel.refreshTimerCapabilities()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pauseErrorMessage = state.pauseErrorRes?.let { stringResource(it) }
    LaunchedEffect(pauseErrorMessage) {
        pauseErrorMessage?.let { error ->
            coroutineScope.launch {
                snackbarHostState.showSnackbar(error)
                viewModel.clearPauseError()
            }
        }
    }

    LaunchedEffect(templateId) {
        viewModel.startWorkout(templateId, selectionSessionId)
    }

    LaunchedEffect(state.session?.id, selectedExerciseIds, selectionSessionId) {
        val ids = selectedExerciseIds ?: return@LaunchedEffect
        val session = state.session ?: return@LaunchedEffect
        if (session.id == selectionSessionId) {
            viewModel.addExercisesByIds(session.id, ids, onSelectedExercisesConsumed)
        } else {
            onSelectedExercisesConsumed()
        }
    }

    val mutationErrorMessage = state.mutationErrorRes?.let { stringResource(it) }
    LaunchedEffect(mutationErrorMessage) {
        mutationErrorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMutationError()
        }
    }

    LaunchedEffect(state.sessionEnded) {
        if (state.sessionEnded) onNavigateBack()
    }

    LaunchedEffect(state.session?.id, isBound) {
        if (state.isCancelling || state.isFinishing) return@LaunchedEffect
        state.session?.let { session ->
            if (isBound && workoutService?.isRunning?.value == true) {
                android.util.Log.d("ActiveWorkout", "Service already running, skipping start")
                return@let
            }
            val intent = Intent(context, WorkoutService::class.java).apply {
                action = WorkoutService.ACTION_START_WORKOUT
                putExtra(WorkoutService.EXTRA_WORKOUT_NAME, session.templateName)
                putExtra(WorkoutService.EXTRA_TEMPLATE_ID, session.templateId)
                putExtra(WorkoutService.EXTRA_START_TIME, session.startTime)
                putExtra(WorkoutService.EXTRA_TOTAL_PAUSED_TIME, session.totalPausedTimeMs)
            }
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: RuntimeException) {
                com.diajarkoding.imfit.core.timer.RestTimerTrace.event(
                    "ui_fgs_start_failed",
                    detail = "exception=${e.javaClass.simpleName}"
                )
                snackbarHostState.showSnackbar(serviceErrorMessage)
            }
        }
    }

    LaunchedEffect(workoutService) {
        viewModel.workoutTimerUpdates.collect { update ->
            workoutService?.updateWorkoutNotification(update)
        }
    }

    LaunchedEffect(state.workoutLogId) {
        state.workoutLogId?.let { logId ->
            onWorkoutFinished(logId)
        }
    }

    if (state.isRestTimerActive) {
        RestTimerCountdownDialog(
            remainingSeconds = state.restTimerSeconds,
            exerciseName = state.restTimerExerciseName,
            onExtend = viewModel::extendRest,
            onReduce = viewModel::reduceRest,
            onDismiss = { viewModel.skipRestTimer() },
            totalSeconds = restState?.let { it.totalDurationMs / 1000f } ?: 0f
        )
    }

    LaunchedEffect(state.timerError) {
        if (state.timerError) snackbarHostState.showSnackbar(timerStorageMessage)
    }

    if (showRestConfigSheet) {
        val currentRestTime = viewModel.getCurrentRestTime()
        RestTimerConfigDialog(
            currentRestSeconds = currentRestTime,
            soundEnabled = state.restSoundEnabled,
            onSoundChange = viewModel::setRestSoundEnabled,
            vibrationEnabled = state.restVibrationEnabled,
            onVibrationChange = viewModel::setRestVibrationEnabled,
            fullScreenAllowed = state.fullScreenAlarmAllowed,
            onAlarmSettings = {
                val settingsAction = if (Build.VERSION.SDK_INT >= 34) {
                    android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT
                } else {
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                }
                try {
                    context.startActivity(
                        Intent(settingsAction, android.net.Uri.parse("package:${context.packageName}"))
                    )
                } catch (_: android.content.ActivityNotFoundException) {
                    coroutineScope.launch { snackbarHostState.showSnackbar(settingsErrorMessage) }
                }
            },
            onDismiss = { showRestConfigSheet = false },
            onConfirm = { seconds ->
                viewModel.setSessionRestOverride(seconds)
                showRestConfigSheet = false
            }
        )
    }

    if (state.showCancelDialog) {
        CancelWorkoutDialog(
            completedSets = state.session?.totalCompletedSets ?: 0,
            totalSets = state.session?.totalSets ?: 0,
            onConfirm = {
                if (viewModel.cancelWorkout()) onNavigateBack()
            },
            onDismiss = { viewModel.dismissCancelDialog() }
        )
    }

    ActiveWorkoutScreenContent(
        state = state,
        snackbarHostState = snackbarHostState,
        onCancelClick = { viewModel.showCancelDialog() },
        onPauseToggle = {
            if (state.isPaused) viewModel.resumeWorkout() else viewModel.pauseWorkout()
        },
        onRestSettingsClick = { showRestConfigSheet = true },
        onFinishClick = { viewModel.finishWorkout() },
        onAddExercises = {
            state.session?.let { session ->
                onAddExercises(session.id, session.exerciseLogs.map { it.exercise.id })
            }
        },
        onRemoveExercise = { exerciseId -> viewModel.removeExercise(exerciseId) },
        onSetUpdate = { exerciseIndex, setIndex, weight, reps ->
            viewModel.updateSet(exerciseIndex, setIndex, weight, reps)
        },
        onSetComplete = { exerciseIndex, setIndex ->
            viewModel.completeSet(exerciseIndex, setIndex)
        },
        onAddSet = { exerciseIndex -> viewModel.addSet(exerciseIndex) },
        onRemoveSet = { exerciseIndex, setIndex -> viewModel.removeSet(exerciseIndex, setIndex) }
    )
}

@Composable
internal fun ActiveWorkoutScreenContent(
    state: ActiveWorkoutState,
    snackbarHostState: SnackbarHostState,
    onCancelClick: () -> Unit,
    onPauseToggle: () -> Unit,
    onRestSettingsClick: () -> Unit,
    onFinishClick: () -> Unit,
    onAddExercises: () -> Unit,
    onRemoveExercise: (exerciseId: String) -> Unit,
    onSetUpdate: (exerciseIndex: Int, setIndex: Int, weight: Float, reps: Int) -> Unit,
    onSetComplete: (exerciseIndex: Int, setIndex: Int) -> Unit,
    onAddSet: (exerciseIndex: Int) -> Unit,
    onRemoveSet: (exerciseIndex: Int, setIndex: Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = IMFITColors.training
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0] ?: Locale.US
    val fontScale = configuration.fontScale
    val screenWidthDp = configuration.screenWidthDp.dp
    val stacked = screenWidthDp <= 340.dp || fontScale >= 1.25f

    val session = state.session
    val completedSets = session?.totalCompletedSets ?: 0
    val totalSets = session?.totalSets ?: 0
    val canFinish = completedSets > 0 && !state.isFinishing && !state.isCancelling && !state.isAddingExercises
    val actionsEnabled = session != null && !state.isFinishing && !state.isCancelling && !state.isAddingExercises

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .imePadding()
            .testTag("active_workout_screen"),
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            ActiveWorkoutTopBar(
                title = session?.templateName ?: "",
                elapsedSeconds = state.elapsedSeconds,
                isPaused = state.isPaused,
                actionsEnabled = actionsEnabled,
                onCancelClick = onCancelClick,
                onPauseToggle = onPauseToggle
            )
        },
        bottomBar = {
            ActiveSessionFooter(
                onRestSettingsClick = onRestSettingsClick,
                onFinishClick = onFinishClick,
                canFinish = canFinish,
                isFinishing = state.isFinishing,
                settingsEnabled = actionsEnabled,
                stacked = stacked
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background)
                .padding(padding)
                .padding(top = IMFITSpacing.headerContentGap)
        ) {
            if (session == null) {
                ActiveSessionLoadingState(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = IMFITSpacing.screenHorizontal)
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().testTag("active_workout_list"),
                    contentPadding = PaddingValues(
                        start = IMFITSpacing.screenHorizontal,
                        end = IMFITSpacing.screenHorizontal,
                        top = 0.dp,
                        bottom = 24.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Live Session Overview
                    item(key = "overview") {
                        ActiveSessionOverview(
                            completedSets = completedSets,
                            totalSets = totalSets,
                            volume = session.totalVolume,
                            locale = locale,
                            stacked = stacked
                        )
                    }

                    // 2. Status Banners
                    if (state.isPaused) {
                        item(key = "banner_paused") {
                            ActiveSessionPausedBanner()
                        }
                    } else if (totalSets > 0 && completedSets == totalSets) {
                        item(key = "banner_all_complete") {
                            ActiveSessionFinishReminderBanner()
                        }
                    }

                    // 3. Exercise Cards or Empty Guidance
                    if (session.exerciseLogs.isEmpty()) {
                        item(key = "empty_guidance") {
                            ActiveSessionEmptyState(
                                onAddExercises = onAddExercises,
                                isLoadingAdd = state.isAddingExercises,
                                enabled = actionsEnabled
                            )
                        }
                    } else {
                        session.exerciseLogs.forEachIndexed { exerciseIndex, exerciseLog ->
                            item(key = "exercise_${exerciseLog.exercise.id}") {
                                ActiveExerciseCard(
                                    exerciseLog = exerciseLog,
                                    mutationEnabled = actionsEnabled,
                                    isPaused = state.isPaused,
                                    onRemoveExercise = { onRemoveExercise(exerciseLog.exercise.id) },
                                    onSetUpdate = { setIndex, weight, reps ->
                                        onSetUpdate(exerciseIndex, setIndex, weight, reps)
                                    },
                                    onSetComplete = { setIndex ->
                                        onSetComplete(exerciseIndex, setIndex)
                                    },
                                    onAddSet = { onAddSet(exerciseIndex) },
                                    onRemoveSet = { setIndex ->
                                        onRemoveSet(exerciseIndex, setIndex)
                                    }
                                )
                            }
                        }

                        // Bottom Add Exercise Action Button
                        item(key = "add_exercise_action") {
                            RoutineButton(
                                text = stringResource(R.string.active_session_add_exercises),
                                icon = Symbols.Filled.Add,
                                onClick = onAddExercises,
                                outlined = true,
                                matchOutlineToContent = true,
                                enabled = !state.isFinishing && !state.isCancelling && !state.isAddingExercises,
                                loading = state.isAddingExercises
                            )
                        }
                    }

                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun ActiveWorkoutScreenPreview() {
    val sampleExercises = listOf(
        ExerciseLog(
            exercise = Exercise("1", "Barbell Bench Press", MuscleCategory.CHEST, ""),
            sets = listOf(
                WorkoutSet(1, 60f, 10, isCompleted = true),
                WorkoutSet(2, 60f, 10, isCompleted = true),
                WorkoutSet(3, 60f, 10, isCompleted = false)
            )
        ),
        ExerciseLog(
            exercise = Exercise("2", "Dumbbell Shoulder Press", MuscleCategory.SHOULDERS, ""),
            sets = listOf(
                WorkoutSet(1, 20f, 12, isCompleted = false),
                WorkoutSet(2, 20f, 12, isCompleted = false),
                WorkoutSet(3, 20f, 12, isCompleted = false)
            )
        )
    )
    val state = ActiveWorkoutState(
        session = WorkoutSession(
            id = "ses_preview",
            templateId = "tmpl_1",
            templateName = "Upper Body",
            startTime = System.currentTimeMillis() - 23 * 60 * 1000L,
            exerciseLogs = sampleExercises
        ),
        elapsedSeconds = 23 * 60 + 42
    )

    IMFITTheme(darkTheme = false) {
        ActiveWorkoutScreenContent(
            state = state,
            snackbarHostState = remember { SnackbarHostState() },
            onCancelClick = {},
            onPauseToggle = {},
            onRestSettingsClick = {},
            onFinishClick = {},
            onAddExercises = {},
            onRemoveExercise = {},
            onSetUpdate = { _, _, _, _ -> },
            onSetComplete = { _, _ -> },
            onAddSet = {},
            onRemoveSet = { _, _ -> }
        )
    }
}
