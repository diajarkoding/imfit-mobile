package com.diajarkoding.imfit.presentation.ui.workout

import androidx.lifecycle.ViewModel
import androidx.annotation.StringRes
import com.diajarkoding.imfit.R
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.domain.model.SetMeasurement
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.ExerciseInputDefaults
import com.diajarkoding.imfit.domain.model.ExerciseModality
import com.diajarkoding.imfit.domain.model.SetTarget
import com.diajarkoding.imfit.domain.model.TrackingProfile
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.inputDefaults
import com.diajarkoding.imfit.domain.model.addDefaultSet
import com.diajarkoding.imfit.domain.model.removeDraftSet
import com.diajarkoding.imfit.domain.model.appendExercises
import com.diajarkoding.imfit.domain.model.removeIncompleteExercise
import com.diajarkoding.imfit.domain.model.TrackingType
import com.diajarkoding.imfit.domain.model.checkpointExecutionStopwatch
import com.diajarkoding.imfit.domain.model.freezeExecutionStopwatch
import com.diajarkoding.imfit.domain.model.toggleExecutionStopwatch
import com.diajarkoding.imfit.domain.model.SetCompletionResult
import com.diajarkoding.imfit.domain.model.SetUndoResult
import com.diajarkoding.imfit.domain.model.activeElapsedMs
import com.diajarkoding.imfit.domain.model.clearRestTrigger
import com.diajarkoding.imfit.domain.model.completeSet
import com.diajarkoding.imfit.domain.model.addV3Set
import com.diajarkoding.imfit.domain.model.ensureRuntime
import com.diajarkoding.imfit.domain.model.freezeForFinish
import com.diajarkoding.imfit.domain.model.needsTimerReconciliation
import com.diajarkoding.imfit.domain.model.pauseWorkout
import com.diajarkoding.imfit.domain.model.reconcileAfterRestore
import com.diajarkoding.imfit.domain.model.resumeWorkout
import com.diajarkoding.imfit.domain.model.toggleExecutionTimer
import com.diajarkoding.imfit.domain.model.undoSet
import com.diajarkoding.imfit.domain.model.withDisplayElapsed
import com.diajarkoding.imfit.core.timer.AndroidMonotonicClock
import com.diajarkoding.imfit.domain.repository.ExerciseCatalogRepository
import com.diajarkoding.imfit.domain.repository.ExerciseHydrationResult
import com.diajarkoding.imfit.domain.repository.RoutineError
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import com.diajarkoding.imfit.domain.repository.RoutineRepository
import com.diajarkoding.imfit.core.model.WorkoutTimerUpdate
import com.diajarkoding.imfit.core.timer.RestTimerCoordinator
import com.diajarkoding.imfit.core.timer.RestTimerState
import com.diajarkoding.imfit.core.timer.TimerStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

data class ActiveWorkoutState(
    val session: WorkoutSession? = null,
    val elapsedSeconds: Long = 0,
    val isRestTimerActive: Boolean = false,
    val restTimerSeconds: Int = 60,
    val restTimerExerciseName: String? = null,
    val showCancelDialog: Boolean = false,
    val workoutLogId: String? = null,
    val sessionRestOverride: Int? = null,
    val isPaused: Boolean = false,
    @StringRes val pauseErrorRes: Int? = null,
    val isFinishing: Boolean = false,
    val restLimited: Boolean = false,
    val timerError: Boolean = false,
    val restSoundEnabled: Boolean = true,
    val restVibrationEnabled: Boolean = false,
    val sessionEnded: Boolean = false,
    val fullScreenAlarmAllowed: Boolean = true,
    val isCancelling: Boolean = false,
    val isAddingExercises: Boolean = false,
    @StringRes val mutationErrorRes: Int? = null,
    val isStarting: Boolean = false,
    @StringRes val startErrorRes: Int? = null,
    val lastKnownRevision: Long? = null,
    val isRestPaused: Boolean = false,
    /** v3: a reboot made some elapsed time unprovable; durable time was kept, nothing was invented. */
    val needsTimerReconciliation: Boolean = false
)

@HiltViewModel
class ActiveWorkoutViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository,
    private val authRepository: AuthRepository,
    private val timer: RestTimerCoordinator,
    private val timerRuntime: com.diajarkoding.imfit.core.timer.RestTimerAndroidRuntime,
    private val exerciseCatalogRepository: ExerciseCatalogRepository,
    private val routineRepository: RoutineRepository,
    private val executionClock: AndroidMonotonicClock
) : ViewModel() {

    private val _state = MutableStateFlow(ActiveWorkoutState())
    val state = _state.asStateFlow()

    private val _workoutTimerUpdates = MutableSharedFlow<WorkoutTimerUpdate>(replay = 1)
    val workoutTimerUpdates: SharedFlow<WorkoutTimerUpdate> = _workoutTimerUpdates.asSharedFlow()
    
    private var elapsedTimeJob: Job? = null
    private var started = false
    private var sessionRevision = 0L
    private var sessionOwnerId: String? = null
    private val acceptedSelectionResults = mutableSetOf<String>()
    val restState = timer.state

    fun extendRest() { viewModelScope.launch { timer.extend() } }
    fun reduceRest() { viewModelScope.launch { timer.reduce() } }
    fun refreshTimerCapabilities() {
        _state.update { it.copy(fullScreenAlarmAllowed = timerRuntime.fullScreenIntentAvailable(),
            restLimited = timerRuntime.capabilityIssue() != null) }
        viewModelScope.launch { timer.capabilitiesChanged(source = "ui_resume") }
    }
    fun timerSettingsIntent() = timerRuntime.capabilitySettingsIntent()
    fun setRestSoundEnabled(enabled: Boolean) {
        timerRuntime.setSoundEnabled(enabled)
        _state.update { it.copy(restSoundEnabled = enabled) }
    }
    fun setRestVibrationEnabled(enabled: Boolean) {
        timerRuntime.setVibrationEnabled(enabled)
        _state.update { it.copy(restVibrationEnabled = enabled) }
    }
    init {
        _state.update { it.copy(restSoundEnabled = timerRuntime.soundEnabled(), restVibrationEnabled = timerRuntime.vibrationEnabled(),
            fullScreenAlarmAllowed = timerRuntime.fullScreenIntentAvailable()) }
        viewModelScope.launch {
            timer.state.collect { rest ->
                _state.update { it.copy(
                    isRestTimerActive = rest?.status == TimerStatus.RUNNING || rest?.status == TimerStatus.PAUSED,
                    isRestPaused = rest?.status == TimerStatus.PAUSED,
                    restTimerSeconds = rest?.remainingSeconds(android.os.SystemClock.elapsedRealtime()) ?: 0,
                    restTimerExerciseName = rest?.exerciseName
                ) }
            }
        }
    }

    fun startWorkout(templateId: String, expectedSessionId: String? = null) {
        if (started || _state.value.isStarting) return
        _state.update { it.copy(isStarting = true, startErrorRes = null) }
        viewModelScope.launch {
            try {
                val ownerId = authRepository.getLocalUserId() ?: throw RoutineError.Unauthorized()
                val result = RoutineSessionStarter(workoutRepository, routineRepository, authRepository)
                    .start(templateId, expectedSessionId)
                if (authRepository.getLocalUserId() != ownerId) throw RoutineError.Unauthorized()
                if (result == null) {
                    _state.update { it.copy(sessionEnded = true) }
                    return@launch
                }
                val sessionOverride = if (result.restored) workoutRepository.getSessionRestOverride() else null
                if (authRepository.getLocalUserId() != ownerId) throw RoutineError.Unauthorized()
                sessionOwnerId = ownerId
                timer.reconcile()
                val restored = recoverRuntime(result.session)
                started = true
                _state.update { it.copy(session = restored, isPaused = restored.isPaused,
                    sessionRestOverride = sessionOverride, lastKnownRevision = result.lastKnownRevision) }
                startElapsedTimeCounter()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { it.copy(startErrorRes = when (e) {
                    is RoutineError.NotFound -> R.string.error_routine_not_found
                    is RoutineError.Unauthorized -> R.string.routine_auth_required
                    is RoutineError.Network -> R.string.routine_start_offline_unavailable
                    is RoutineError.ValidationError -> R.string.routine_start_requirement
                    else -> R.string.routine_start_failed
                }) }
            } finally {
                _state.update { it.copy(isStarting = false) }
            }
        }
    }


    /**
     * Process-death / reopen (T33): one durable transition on open, never per tick. Same boot re-anchors from
     * stored monotonic anchors; a different boot keeps the last durable elapsed and flags reconciliation.
     * Then the pending rest trigger is applied with its stored deadline (not now + rest) and the rest timer
     * is aligned with the persisted pause state.
     */
    private suspend fun recoverRuntime(session: WorkoutSession): WorkoutSession {
        if (session.schemaVersion != 3) return session
        val now = executionClock.elapsedRealtime()
        val boot = executionClock.bootIdentity()
        val wall = System.currentTimeMillis()
        val runtime = workoutRepository.mutateActiveSession(session.id) { current ->
            current.ensureRuntime(now, boot, wall).reconcileAfterRestore(now, boot)
        } ?: session
        processPendingRestTrigger(runtime)
        if (runtime.isPaused) timer.pause() else timer.resume()
        return workoutRepository.getActiveSessionForTimer() ?: runtime
    }

    /** Idempotent: a trigger whose set already has a timer is only cleared; a foreign boot starts nothing. */
    private suspend fun processPendingRestTrigger(session: WorkoutSession) {
        val trigger = session.pendingRestTrigger ?: return
        val exercise = session.exerciseLogs.firstOrNull { it.exercise.id == trigger.exerciseId }
        val set = exercise?.sets?.firstOrNull { it.id == trigger.setId }
        val triggerBoot = trigger.bootId.toIntOrNull()
        fun hasTimer() = timer.state.value?.let {
            it.workoutId == session.id && (it.setId == trigger.setId || trigger.setId in it.startedSetIds)
        } == true
        if (exercise != null && set != null && set.isCompleted && !hasTimer() && triggerBoot == executionClock.bootIdentity()) {
            val userId = authRepository.getLocalUserId() ?: return
            timer.start(RestTimerState(timerId = "", userId = userId, workoutId = session.id, templateId = session.templateId,
                exerciseId = trigger.exerciseId, setId = trigger.setId, exerciseName = exercise.exercise.name,
                durationMs = trigger.restSeconds.toLong() * 1000, deadlineElapsedMs = 0, bootIdentity = null, startedAtEpochMs = 0),
                triggerDeadlineElapsedMs = trigger.deadlineElapsedMs, triggerBootIdentity = triggerBoot)
        }
        // Keep the trigger only if the same-boot timer could not be started yet (store/validation failure → retry).
        val retryable = exercise != null && set != null && set.isCompleted && triggerBoot == executionClock.bootIdentity() && !hasTimer()
        if (!retryable) workoutRepository.mutateActiveSession(session.id) { it.clearRestTrigger(trigger.setId) }
    }

    private fun startElapsedTimeCounter() {
        elapsedTimeJob?.cancel()
        elapsedTimeJob = viewModelScope.launch {
            while (true) {
                try {
                    if (authRepository.getLocalUserId() != sessionOwnerId) {
                        _state.update { it.copy(session = null, sessionEnded = true) }
                        return@launch
                    }
                    val revision = sessionRevision
                    val stored = withTimeout(2500) { workoutRepository.getActiveSessionForTimer() }
                    if (authRepository.getLocalUserId() != sessionOwnerId) {
                        _state.update { it.copy(session = null, sessionEnded = true) }
                        return@launch
                    }
                    if (stored == null) {
                        if (_state.value.session != null && !_state.value.isFinishing && !_state.value.isCancelling) {
                            _state.update { it.copy(session = null, sessionEnded = true, isRestTimerActive = false) }
                            return@launch
                        }
                        delay(1000)
                        continue
                    }
                    // Display-only derivation from stored anchors: no Room write on a tick (PLAN §17.3).
                    val nowElapsed = executionClock.elapsedRealtime()
                    val boot = executionClock.bootIdentity()
                    val session = stored.withDisplayElapsed(nowElapsed, boot)
                    // Active time excludes Pause (v3 monotonic clock; legacy sessions fall back to actualElapsedMs).
                    val elapsed = session.activeElapsedMs(nowElapsed, boot) / 1000
                    timer.reconcile(source = "ui_ticker")
                    if (revision != sessionRevision || _state.value.isCancelling) continue
                    val rest = timer.state.value?.takeIf { it.workoutId == session.id }
                    _state.update { it.copy(elapsedSeconds = elapsed.coerceAtLeast(0), session = session, isPaused = session.isPaused,
                        isRestTimerActive = rest?.status == TimerStatus.RUNNING || rest?.status == TimerStatus.PAUSED,
                        isRestPaused = rest?.status == TimerStatus.PAUSED,
                        needsTimerReconciliation = session.needsTimerReconciliation,
                        restTimerSeconds = rest?.remainingSeconds(android.os.SystemClock.elapsedRealtime()) ?: 0,
                        restTimerExerciseName = rest?.exerciseName,
                        restLimited = timerRuntime.capabilityIssue() != null ||
                            (rest != null && rest.status == TimerStatus.RUNNING && rest.schedulingMode != com.diajarkoding.imfit.core.timer.SchedulingMode.EXACT),
                        timerError = timer.error.value != null) }
                    _workoutTimerUpdates.emit(
                        WorkoutTimerUpdate(
                            workoutName = session.templateName,
                            elapsedSeconds = elapsed,
                            completedSets = session.totalCompletedSets,
                            totalSets = session.totalSets,
                            totalVolume = session.totalVolume,
                            isPaused = _state.value.isPaused
                        )
                    )
                } catch (e: TimeoutCancellationException) {
                    _state.update { it.copy(timerError = true) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update { it.copy(timerError = true) }
                }
                delay(1000)
            }
        }
    }

    /**
     * Sets session-level rest time override.
     * This value will be used for ALL exercises during this session.
     */
    fun setSessionRestOverride(seconds: Int) {
        val sessionId = _state.value.session?.id ?: return
        viewModelScope.launch {
            try {
                workoutRepository.updateSessionRestOverride(seconds)
                if (_state.value.session?.id == sessionId) {
                    _state.update { it.copy(sessionRestOverride = seconds) }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.e("ActiveWorkoutVM", "Rest configuration save failed", e)
                _state.update { it.copy(mutationErrorRes = R.string.error_session_save) }
            }
        }
    }
    
    /**
     * Gets the current rest time configuration.
     * Returns the session override, otherwise the runtime default of 60 seconds.
     */
    fun getCurrentRestTime(): Int = _state.value.sessionRestOverride ?: 60

    private fun publishSession(session: WorkoutSession) {
        sessionRevision++
        _state.update {
            it.copy(
                session = session,
                isPaused = session.isPaused,
                needsTimerReconciliation = session.needsTimerReconciliation
            )
        }
    }

    private fun editExercise(index: Int, transform: (ExerciseLog) -> ExerciseLog) {
        val snapshot = _state.value
        if (snapshot.isFinishing || snapshot.isCancelling) return
        val session = snapshot.session ?: return
        val exerciseId = session.exerciseLogs.getOrNull(index)?.exercise?.id ?: return
        viewModelScope.launch {
            try {
                workoutRepository.mutateActiveSession(session.id) { current ->
                    current.copy(exerciseLogs = current.exerciseLogs.map { if (it.exercise.id == exerciseId) transform(it) else it })
                }?.let(::publishSession)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.e("ActiveWorkoutVM", "Session save failed", e); _state.update { it.copy(mutationErrorRes = R.string.error_session_save) } }
        }
    }


    fun updateSetMeasurement(exerciseIndex: Int, setIndex: Int, measurement: SetMeasurement) = editExercise(exerciseIndex) { log ->
        log.copy(sets = log.sets.mapIndexed { index, set ->
            if (index == setIndex && !set.isCompleted) {
                if (set.measurementVersion == 3) {
                    set.copy(
                        weightKg = measurement.weightKg,
                        reps = measurement.reps,
                        durationSeconds = measurement.durationSeconds,
                        distanceMeters = measurement.distanceMeters,
                        caloriesKcal = measurement.caloriesKcal,
                        speedKph = measurement.speedKph,
                        lapReps = measurement.lapReps,
                        inclinePercent = measurement.inclinePercent,
                        executionStopwatch = if (measurement.durationSeconds != set.durationSeconds) null else set.executionStopwatch
                    )
                } else {
                    set.copy(
                        weightKg = measurement.weightKg ?: set.weightKg,
                        reps = measurement.reps ?: set.reps,
                        durationSeconds = measurement.durationSeconds,
                        distanceMeters = measurement.distanceMeters,
                        additionalWeight = measurement.additionalWeight,
                        assistanceWeight = measurement.assistanceWeight,
                        executionStopwatch = if (measurement.durationSeconds != set.durationSeconds) null else set.executionStopwatch
                    )
                }
            } else set
        })
    }

    fun toggleSetStopwatch(exerciseIndex: Int, setIndex: Int) {
        val snapshot = _state.value
        if (snapshot.isPaused || snapshot.isFinishing || snapshot.isCancelling) return
        val session = snapshot.session ?: return
        val exercise = session.exerciseLogs.getOrNull(exerciseIndex) ?: return
        if (exercise.trackingType != TrackingType.DURATION && exercise.trackingType != TrackingType.DISTANCE_DURATION) return
        val setNumber = exercise.sets.getOrNull(setIndex)?.setNumber ?: return
        viewModelScope.launch {
            try {
                val now = executionClock.elapsedRealtime()
                val boot = executionClock.bootIdentity()
                if (session.schemaVersion == 3) {
                    val setId = exercise.sets.getOrNull(setIndex)?.id ?: return@launch
                    var rejected = false
                    workoutRepository.mutateActiveSession(session.id) { current ->
                        current.toggleExecutionTimer(setId, now, boot) ?: current.also { rejected = true }
                    }?.let(::publishSession)
                    if (rejected) _state.update { it.copy(mutationErrorRes = R.string.error_execution_timer_busy) }
                    return@launch
                }
                workoutRepository.mutateActiveSession(session.id) { current ->
                    if (current.isPaused) current else current.copy(exerciseLogs = current.exerciseLogs.map { log ->
                        if (log.exercise.id != exercise.exercise.id) log else log.copy(sets = log.sets.map { set ->
                            if (set.setNumber == setNumber) set.toggleExecutionStopwatch(now, boot) else set
                        })
                    })
                }?.let(::publishSession)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.e("ActiveWorkoutVM", "Stopwatch save failed", e)
                _state.update { it.copy(mutationErrorRes = R.string.error_session_save) }
            }
        }
    }

    fun completeSet(exerciseIndex: Int, setIndex: Int) {
        val snapshot = _state.value
        if (snapshot.isPaused || snapshot.isFinishing || snapshot.isCancelling) return
        val session = snapshot.session ?: return
        val exercise = session.exerciseLogs.getOrNull(exerciseIndex) ?: return
        val setNumber = exercise.sets.getOrNull(setIndex)?.setNumber ?: return
        if (session.schemaVersion == 3) { completeSetV3(session, exercise, exercise.sets[setIndex].id); return }
        viewModelScope.launch {
            try {
                var completed: WorkoutSet? = null
                val updated = workoutRepository.mutateActiveSession(session.id) { current ->
                    if (current.isPaused) current else current.copy(exerciseLogs = current.exerciseLogs.map { log ->
                        if (log.exercise.id != exercise.exercise.id) log else log.copy(sets = log.sets.map { set ->
                            if (set.setNumber == setNumber && !set.isCompleted) {
                                val frozen = set.freezeExecutionStopwatch(executionClock.elapsedRealtime(), executionClock.bootIdentity())
                                if (frozen.measurement.isCompleteEligible(log.trackingType)) {
                                    completed = frozen.copy(isCompleted = true); completed!!
                                } else set
                            } else set
                        })
                    })
                } ?: return@launch
                publishSession(updated)
                val set = completed ?: return@launch
                val restSeconds = getCurrentRestTime()
                val userId = authRepository.getLocalUserId() ?: return@launch
                timer.start(RestTimerState(timerId = "", userId = userId, workoutId = session.id, templateId = session.templateId,
                    exerciseId = exercise.exercise.id, setId = "${session.id}:${exercise.exercise.id}:${set.setNumber}",
                    exerciseName = exercise.exercise.name, durationMs = restSeconds.toLong() * 1000,
                    deadlineElapsedMs = 0, bootIdentity = null, startedAtEpochMs = 0))
                // The coordinator collector owns the displayed deadline, including rejected starts.
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.e("ActiveWorkoutVM", "Set completion failed", e); _state.update { it.copy(mutationErrorRes = R.string.error_set_complete) } }
        }
    }

    /**
     * T30/T31/T33: completion and its pending rest trigger commit in one Room transaction; the rest timer starts only
     * after that commit succeeds, from the target row's rest and the trigger's stored deadline.
     */
    private fun completeSetV3(session: WorkoutSession, exercise: ExerciseLog, setId: String) {
        viewModelScope.launch {
            try {
                val now = executionClock.elapsedRealtime()
                val boot = executionClock.bootIdentity()
                var result: SetCompletionResult? = null
                val updated = workoutRepository.mutateActiveSession(session.id) { current ->
                    val r = current.completeSet(exercise.exercise.id, setId, now, boot)
                    result = r
                    if (r is SetCompletionResult.Completed) r.session else current
                } ?: return@launch
                publishSession(updated)
                val done = result as? SetCompletionResult.Completed ?: return@launch
                val userId = authRepository.getLocalUserId() ?: return@launch
                val trigger = done.trigger
                if (trigger == null) {
                    // Rest 0: no countdown, and a countdown of an earlier set must not stay associated with this one.
                    if (timer.state.value?.let { it.status == TimerStatus.RUNNING || it.status == TimerStatus.PAUSED } == true) timer.cancel("REST_ZERO")
                    return@launch
                }
                timer.start(RestTimerState(timerId = "", userId = userId, workoutId = session.id, templateId = session.templateId,
                    exerciseId = exercise.exercise.id, setId = setId, exerciseName = exercise.exercise.name,
                    durationMs = done.restSeconds.toLong() * 1000, deadlineElapsedMs = 0, bootIdentity = null, startedAtEpochMs = 0),
                    triggerDeadlineElapsedMs = trigger.deadlineElapsedMs, triggerBootIdentity = boot)
                val handled = timer.state.value?.let { it.workoutId == session.id && (it.setId == setId || setId in it.startedSetIds) } == true
                if (handled) workoutRepository.mutateActiveSession(session.id) { it.clearRestTrigger(setId) }?.let(::publishSession)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.e("ActiveWorkoutVM", "Set completion failed", e); _state.update { it.copy(mutationErrorRes = R.string.error_set_complete) } }
        }
    }

    /** Undo → DRAFT: keeps inputs, discards the rest outcome, stops only the matching countdown. */
    fun undoSetCompletion(exerciseIndex: Int, setIndex: Int) {
        val snapshot = _state.value
        if (snapshot.isPaused || snapshot.isFinishing || snapshot.isCancelling) return
        val session = snapshot.session?.takeIf { it.schemaVersion == 3 } ?: return
        val exercise = session.exerciseLogs.getOrNull(exerciseIndex) ?: return
        val setId = exercise.sets.getOrNull(setIndex)?.id ?: return
        viewModelScope.launch {
            try {
                var undone = false
                workoutRepository.mutateActiveSession(session.id) { current ->
                    (current.undoSet(exercise.exercise.id, setId) as? SetUndoResult.Undone)?.also { undone = true }?.session ?: current
                }?.let(::publishSession)
                if (undone) timer.cancelForSet(setId)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.e("ActiveWorkoutVM", "Undo failed", e); _state.update { it.copy(mutationErrorRes = R.string.error_session_save) } }
        }
    }

    fun addSet(exerciseIndex: Int) = editExercise(exerciseIndex) { log ->
        if (log.measurementVersion == 3) log.addV3Set() else log.addDefaultSet()
    }

    fun removeSet(exerciseIndex: Int, setIndex: Int) = editExercise(exerciseIndex) { log ->
        log.removeDraftSet(setIndex)
    }

    fun removeExercise(exerciseId: String) {
        val snapshot = _state.value
        if (snapshot.isFinishing || snapshot.isCancelling) return
        val session = snapshot.session ?: return
        viewModelScope.launch {
            try { workoutRepository.mutateActiveSession(session.id) { it.removeIncompleteExercise(exerciseId) }?.let(::publishSession) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.e("ActiveWorkoutVM", "Exercise removal failed", e); _state.update { it.copy(mutationErrorRes = R.string.error_exercise_remove) } }
        }
    }

    fun addExercisesByIds(
        sessionId: String,
        exerciseIds: List<String>,
        ownerId: String?,
        resultId: String?,
        onConsumed: () -> Unit
    ) {
        if (resultId != null && resultId in acceptedSelectionResults) { onConsumed(); return }
        if (_state.value.isAddingExercises || _state.value.isFinishing || _state.value.isCancelling) return
        if (_state.value.session?.id != sessionId || ownerId == null || ownerId != sessionOwnerId) return
        _state.update { it.copy(isAddingExercises = true, mutationErrorRes = null) }
        viewModelScope.launch {
            try {
                if (authRepository.getLocalUserId() != ownerId) return@launch
                val ids = exerciseIds.distinct()
                val hydrated = when (val result = exerciseCatalogRepository.getExercisesByIds(ids)) {
                    is ExerciseHydrationResult.Success -> result.exercises
                    is ExerciseHydrationResult.Missing -> error("Selected exercises no longer available: ${result.missingIds}")
                    is ExerciseHydrationResult.Error -> throw result.cause
                }.associateBy { it.id }
                val isV3Session = _state.value.session?.schemaVersion == 3
                val additions = ids.map { id ->
                    val exercise = hydrated[id]?.takeIf { it.isActive } ?: error("Exercise is unavailable: $id")
                    if (isV3Session) {
                        val isDuration = exercise.modality == ExerciseModality.CARDIO || exercise.modality == ExerciseModality.ISOMETRIC
                        val trackingType = if (isDuration) TrackingType.DURATION else TrackingType.WEIGHT_REPS
                        val rowCount = if (
                            exercise.modality == ExerciseModality.CARDIO &&
                            (exercise.trackingProfile == TrackingProfile.DISTANCE_ACTIVITY ||
                                exercise.trackingProfile == TrackingProfile.EQUIPMENT_METRICS)
                        ) 1 else 3
                        val targets = (1..rowCount).map { setNumber ->
                            SetTarget(
                                rowId = java.util.UUID.randomUUID().toString(),
                                setNumber = setNumber,
                                weightKg = if (isDuration) null else 0.0,
                                reps = if (isDuration) null else 8,
                                durationSeconds = if (isDuration) 60 else null,
                                restSeconds = 60
                            )
                        }
                        val defaults = ExerciseInputDefaults(
                            sets = rowCount,
                            reps = 0,
                            trackingType = trackingType.name,
                            schemaVersion = 3,
                            setTargets = targets,
                            extraSetTargets = emptyList(),
                            modality = exercise.modality,
                            trackingProfile = exercise.trackingProfile,
                            supportedMetrics = exercise.supportedMetrics
                        ).also { it.requireV3Snapshot() }
                        ExerciseLog(
                            exercise = exercise,
                            sets = targets.map { target ->
                                WorkoutSet(
                                    setNumber = target.setNumber,
                                    weightKg = target.weightKg,
                                    reps = target.reps,
                                    durationSeconds = target.durationSeconds,
                                    isCompleted = false,
                                    id = java.util.UUID.randomUUID().toString(),
                                    targetRowId = target.rowId,
                                    measurementVersion = 3
                                )
                            },
                            restSeconds = 60,
                            inputDefaults = defaults,
                            trackingType = trackingType,
                            modality = exercise.modality,
                            measurementVersion = 3
                        )
                    } else {
                        val config = TemplateExercise(exercise)
                        val lastPerf = workoutRepository.getPrefillForExercise(id, ownerId, config.inputDefaults())
                        ExerciseLog(
                            exercise = exercise,
                            sets = (1..config.sets).map { setNumber ->
                                val last = lastPerf[setNumber]
                                WorkoutSet(
                                    setNumber = setNumber,
                                    weightKg = last?.weightKg ?: 0.0,
                                    reps = last?.reps ?: (config.reps ?: 0),
                                    durationSeconds = last?.durationSeconds ?: config.targetDurationSeconds,
                                    distanceMeters = last?.distanceMeters ?: config.targetDistanceMeters?.toDouble(),
                                    additionalWeight = last?.additionalWeight,
                                    assistanceWeight = last?.assistanceWeight,
                                    isCompleted = false
                                )
                            },
                            restSeconds = config.restSeconds,
                            inputDefaults = config.inputDefaults(),
                            trackingType = config.trackingType,
                            modality = exercise.modality
                        )
                    }
                }
                if (authRepository.getLocalUserId() != ownerId) return@launch
                val updated = workoutRepository.mutateActiveSession(sessionId) { it.appendExercises(additions) }
                    ?: error("The selected workout session has ended")
                if (authRepository.getLocalUserId() != ownerId) return@launch
                publishSession(updated)
                if (resultId != null) acceptedSelectionResults.add(resultId)
                onConsumed()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.e("ActiveWorkoutVM", "Exercise append failed", e); _state.update { it.copy(mutationErrorRes = R.string.error_exercise_add) } }
            finally { _state.update { it.copy(isAddingExercises = false) } }
        }
    }

    fun clearMutationError() { _state.update { it.copy(mutationErrorRes = null) } }

    fun skipRestTimer() {
        viewModelScope.launch {
            timer.skip()
            _state.update { it.copy(isRestTimerActive = false, restTimerExerciseName = null) }
        }
    }

    fun pauseWorkout() {
        if (_state.value.session?.schemaVersion == 3) { pauseWorkoutV3(); return }
        viewModelScope.launch {
            val allowed = timer.whenRestInactive { persistWorkoutPause() }
            if (!allowed) _state.update { it.copy(pauseErrorRes = R.string.error_rest_pause) }
        }
    }
    /** Global Pause (T29): freezes execution timers and the active rest; both persisted as transitions, no tick writes. */
    private fun pauseWorkoutV3() {
        viewModelScope.launch {
            try {
                val session = _state.value.session ?: return@launch
                val now = executionClock.elapsedRealtime()
                val boot = executionClock.bootIdentity()
                workoutRepository.mutateActiveSession(session.id) { it.pauseWorkout(now, boot, System.currentTimeMillis()) }?.let(::publishSession)
                timer.pause()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.e("ActiveWorkoutVM", "Pause failed", e); _state.update { it.copy(pauseErrorRes = R.string.error_session_save) } }
        }
    }

    private suspend fun persistWorkoutPause() {
        val session = _state.value.session ?: return
        workoutRepository.mutateActiveSession(session.id) { current ->
            if (current.isPaused) current else {
                val now = executionClock.elapsedRealtime()
                val boot = executionClock.bootIdentity()
                current.copy(isPaused = true, lastPauseTime = System.currentTimeMillis(),
                    exerciseLogs = current.exerciseLogs.map { log ->
                        log.copy(sets = log.sets.map { it.freezeExecutionStopwatch(now, boot) })
                    })
            }
        }?.let(::publishSession)
    }
    
    fun resumeWorkout() {
        viewModelScope.launch {
            val currentSession = _state.value.session ?: return@launch
            if (currentSession.schemaVersion == 3) {
                try {
                    val now = executionClock.elapsedRealtime()
                    val boot = executionClock.bootIdentity()
                    workoutRepository.mutateActiveSession(currentSession.id) { it.resumeWorkout(now, boot, System.currentTimeMillis()) }?.let(::publishSession)
                    timer.resume()
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { android.util.Log.e("ActiveWorkoutVM", "Resume failed", e); _state.update { it.copy(pauseErrorRes = R.string.error_session_save) } }
                return@launch
            }
            
            workoutRepository.mutateActiveSession(currentSession.id) { current ->
                if (!current.isPaused) current else current.copy(isPaused = false, lastPauseTime = null,
                    totalPausedTimeMs = current.totalPausedTimeMs + (System.currentTimeMillis() - (current.lastPauseTime ?: System.currentTimeMillis())).coerceAtLeast(0))
            }?.let(::publishSession)
        }
    }
    
    fun clearPauseError() {
        _state.update { it.copy(pauseErrorRes = null) }
    }

    fun showCancelDialog() {
        _state.update { it.copy(showCancelDialog = true) }
    }

    fun dismissCancelDialog() {
        _state.update { it.copy(showCancelDialog = false) }
    }

    fun cancelWorkout(): Boolean {
        val snapshot = _state.value
        if (snapshot.isFinishing || snapshot.isCancelling) return false
        val session = snapshot.session ?: return false
        _state.update { it.copy(isCancelling = true, showCancelDialog = false, isRestTimerActive = false) }
        elapsedTimeJob?.cancel()
        workoutRepository.requestCancelSession(session.id)
        return true
    }

    fun finishWorkout() {
        if (_state.value.isFinishing || _state.value.isCancelling) return
        
        viewModelScope.launch {
            _state.update { it.copy(isFinishing = true) }
            elapsedTimeJob?.cancel()

            try {
                // Decide/deliver any expiry before finalizing; a rest still running is cancelled without an outcome.
                timer.reconcile(source = "finish")
                // v3: freeze session clocks so finish can read stable paused/active elapsed (one transition write).
                val frozenAt = executionClock.elapsedRealtime()
                val frozenBoot = executionClock.bootIdentity()
                _state.value.session?.takeIf { it.schemaVersion == 3 }?.let { s ->
                    workoutRepository.mutateActiveSession(s.id) { it.freezeForFinish(frozenAt, frozenBoot) }
                }
                val log = workoutRepository.finishWorkout()
                if (log != null) _state.update { it.copy(workoutLogId = log.id) }
                else {
                    _state.update { it.copy(isFinishing = false, mutationErrorRes = R.string.error_session_finish) }
                    startElapsedTimeCounter()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                android.util.Log.e("ActiveWorkoutVM", "Session finish failed", e)
                _state.update { it.copy(isFinishing = false, mutationErrorRes = R.string.error_session_finish) }
                startElapsedTimeCounter()
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        elapsedTimeJob?.cancel()
    }
}
