package com.diajarkoding.imfit.presentation.ui.workout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import com.diajarkoding.imfit.data.remote.online.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    onSelectedExercisesConsumed: () -> Unit = {},
    expectedSessionId: String? = null,
    onRecovery: () -> Unit = {},
    allowCreate: Boolean = true
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showLeaveWarning by remember(state.identity) { mutableStateOf(false) }
    BackHandler {
        if (state.unconfirmed) showLeaveWarning = true else onNavigateBack()
    }
    if (showLeaveWarning) {
        AlertDialog(onDismissRequest = { showLeaveWarning = false },
            title = { Text(stringResource(R.string.session_leave_title)) },
            text = { Text(stringResource(R.string.session_leave_message)) },
            confirmButton = { TextButton(onClick = { showLeaveWarning = false; onNavigateBack() }) { Text(stringResource(R.string.session_leave)) } },
            dismissButton = { TextButton(onClick = { showLeaveWarning = false }) { Text(stringResource(R.string.session_stay)) } })
    }
    var showRestConfigSheet by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.identity) { showRestConfigSheet = false }
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

    LaunchedEffect(templateId, expectedSessionId, allowCreate) {
        viewModel.startWorkout(templateId, expectedSessionId ?: selectionSessionId, allowCreate)
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
                putExtra(WorkoutService.EXTRA_SESSION_ID, session.id)
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
                viewModel.cancelWorkout()
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
        onSetUpdate = viewModel::updateSet,
        onSetComplete = viewModel::completeSet,
        onAddSet = viewModel::addSet,
        onRemoveSet = viewModel::removeSet,
        onRetry = viewModel::retryEntry,
        onReload = viewModel::reload,
        onResolve = viewModel::resolvePending,
        onRetryOriginal = viewModel::retryPending,
        onUseServer = viewModel::useServer,
        onReapply = viewModel::reapplyDrafts,
        onAcknowledgeExpiry = viewModel::acknowledgeExpiry,
        onRecovery = onRecovery,
        onRecentOperations = viewModel::loadRecentOperations,
        onReviewOperation = viewModel::reviewOperation,
        onRetryExercises = viewModel::retryExerciseAddition
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
    onSetUpdate: (SessionSetKey, String, String) -> Unit,
    onSetComplete: (SessionSetKey) -> Unit,
    onAddSet: (String) -> Unit,
    onRemoveSet: (SessionSetKey) -> Unit,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit = {},
    onReload: () -> Unit = {},
    onResolve: () -> Unit = {},
    onRetryOriginal: () -> Unit = {},
    onUseServer: () -> Unit = {},
    onReapply: () -> Unit = {},
    onAcknowledgeExpiry: () -> Unit = {},
    onRecovery: () -> Unit = {},
    onRecentOperations: () -> Unit = {},
    onReviewOperation: (OperationReceipt) -> Unit = {},
    onRetryExercises: () -> Unit = {}
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
    val actionsEnabled = state.actionsEnabled
    val canFinish = completedSets > 0 && actionsEnabled

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
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = IMFITSpacing.screenHorizontal)) {
                    SessionConfirmationStatus(state, onRetry, onReload, onResolve, onRetryOriginal, onUseServer, onReapply, onAcknowledgeExpiry, onRecovery)
                    if (state.reviewRecentOperations) {
                        Text(stringResource(R.string.session_recent_review))
                        state.recentOperations.forEach { receipt ->
                            TextButton(onClick = { onReviewOperation(receipt) }) {
                                Text("${receipt.operation} · ${receipt.committedAt}")
                            }
                        }
                        if (state.recentOperations.isEmpty() || state.recentHasMore) {
                            TextButton(onClick = onRecentOperations, enabled = !state.recentLoading) {
                                Text(stringResource(R.string.session_recent_load))
                            }
                        }
                    }
                    if (!state.reviewRecentOperations && state.entryPhase != SessionEntryPhase.LOADING && state.entryPhase != SessionEntryPhase.BLOCKED) {
                        TextButton(onClick = onRetry) { Text(stringResource(R.string.session_retry)) }
                    }
                    if (state.entryPhase == SessionEntryPhase.LOADING) ActiveSessionLoadingState()
                }
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
                    item(key = "confirmation") {
                        SessionConfirmationStatus(state, onRetry, onReload, onResolve, onRetryOriginal, onUseServer, onReapply, onAcknowledgeExpiry, onRecovery)
                        if (state.canRetryExerciseAddition) {
                            TextButton(onClick = onRetryExercises, enabled = state.actionsEnabled) {
                                Text(stringResource(R.string.session_retry_exercises))
                            }
                        }
                    }
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
                        state.confirmed?.exercises?.forEach { exerciseLog ->
                            item(key = "${state.identity}_${session.id}_${exerciseLog.id}") {
                                ActiveExerciseCard(
                                    exerciseLog = exerciseLog,
                                    drafts = state.drafts,
                                    mutationEnabled = actionsEnabled,
                                    inputEnabled = state.inputEnabled,
                                    isPaused = state.isPaused,
                                    onRemoveExercise = { onRemoveExercise(exerciseLog.id) },
                                    onSetUpdate = onSetUpdate,
                                    onSetComplete = onSetComplete,
                                    onAddSet = { onAddSet(exerciseLog.id) },
                                    onRemoveSet = onRemoveSet
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
                                enabled = actionsEnabled,
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
    val snapshot = SessionSnapshot(
        id = "session-preview", version = 1, status = SessionStatus.ACTIVE, effectiveStatus = SessionStatus.ACTIVE,
        reviewRequired = false, templateId = "template-preview", templateName = "Upper Body", templateRevision = 1,
        startedAt = "2026-10-29T10:00:00Z", pausedAt = null, totalPausedMs = 0,
        lastActivityAt = "2026-10-29T10:00:00Z", expiresAt = "2026-11-28T10:00:00Z",
        schemaVersion = 1, currentExerciseId = "child-preview", completedWorkoutId = null,
        exercises = listOf(SessionExercise("child-preview", "exercise-preview", "Barbell Bench Press", 0, 60,
            InputDefaults(3, 10), listOf(
                com.diajarkoding.imfit.data.remote.online.WorkoutSet("set-preview-1", 1, "60", 10, true),
                com.diajarkoding.imfit.data.remote.online.WorkoutSet("set-preview-2", 2, "60", 10, true),
                com.diajarkoding.imfit.data.remote.online.WorkoutSet("set-preview-3", 3, "60", 10, false)
            ), "Chest"))
    )
    val state = ActiveWorkoutState(session = snapshot.toDomain(), confirmed = snapshot,
        entryPhase = SessionEntryPhase.CONFIRMED, savePhase = SessionSavePhase.CONFIRMED,
        requiresVerification = false, elapsedSeconds = 23 * 60 + 42)

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
            onSetUpdate = { _, _, _ -> },
            onSetComplete = {},
            onAddSet = {},
            onRemoveSet = {}
        )
    }
}

@Composable
private fun SessionConfirmationStatus(
    state: ActiveWorkoutState,
    onRetry: () -> Unit, onReload: () -> Unit, onResolve: () -> Unit,
    onRetryOriginal: () -> Unit, onUseServer: () -> Unit, onReapply: () -> Unit,
    onAcknowledgeExpiry: () -> Unit, onRecovery: () -> Unit
) {
    val status = when {
        state.entryPhase == SessionEntryPhase.BLOCKED -> R.string.session_blocked
        state.confirmed?.effectiveStatus == SessionStatus.EXPIRED || state.confirmed?.reviewRequired == true -> R.string.session_expired
        state.onlineHint == false -> R.string.session_offline
        state.entryPhase == SessionEntryPhase.UNKNOWN -> R.string.session_unknown
        else -> when (state.savePhase) {
            SessionSavePhase.CONFIRMED -> if (state.requiresVerification) R.string.session_unknown else R.string.session_saved
            SessionSavePhase.DIRTY -> R.string.session_dirty
            SessionSavePhase.SAVING -> R.string.session_saving
            SessionSavePhase.OFFLINE_BLOCKED -> R.string.session_offline
            SessionSavePhase.UNKNOWN -> R.string.session_unknown
            SessionSavePhase.RESOLVING -> R.string.session_resolving
            SessionSavePhase.CONFLICT -> R.string.session_conflict
            SessionSavePhase.RELOADING -> R.string.session_reloading
        }
    }
    var confirmDiscard by remember(state.identity, state.confirmed?.id) { mutableStateOf<(() -> Unit)?>(null) }
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag("session_confirmation_status")) {
        Text(stringResource(status), color = IMFITColors.training.text)
        if (state.entryPhase == SessionEntryPhase.BLOCKED) {
            TextButton(onClick = onRecovery) { Text(stringResource(R.string.session_recovery)) }
            TextButton(onClick = onRetry) { Text(stringResource(R.string.session_retry)) }
        } else if (state.entryPhase == SessionEntryPhase.UNKNOWN || state.requiresVerification || state.savePhase == SessionSavePhase.CONFLICT) {
            TextButton(onClick = onReload) { Text(stringResource(R.string.session_reload)) }
        }
        if (state.pendingRequestId != null) {
            TextButton(onClick = onResolve) { Text(stringResource(R.string.session_resolve)) }
            TextButton(onClick = onRetryOriginal) { Text(stringResource(R.string.session_retry_original)) }
        }
        if (state.savePhase == SessionSavePhase.CONFLICT && !state.requiresVerification && state.pendingRequestId == null) {
            TextButton(onClick = { confirmDiscard = onUseServer }) { Text(stringResource(R.string.session_use_server)) }
            TextButton(onClick = onReapply) { Text(stringResource(R.string.session_reapply)) }
        }
        if (state.confirmed?.effectiveStatus == SessionStatus.EXPIRED || state.confirmed?.reviewRequired == true) {
            TextButton(onClick = { confirmDiscard = onAcknowledgeExpiry }) { Text(stringResource(R.string.session_acknowledge)) }
        }
    }
    confirmDiscard?.let { action ->
        AlertDialog(onDismissRequest = { confirmDiscard = null },
            title = { Text(stringResource(R.string.session_leave_title)) },
            text = { Text(stringResource(R.string.session_leave_message)) },
            confirmButton = { TextButton(onClick = { confirmDiscard = null; action() }) { Text(stringResource(R.string.action_continue)) } },
            dismissButton = { TextButton(onClick = { confirmDiscard = null }) { Text(stringResource(R.string.session_stay)) } })
    }
}
