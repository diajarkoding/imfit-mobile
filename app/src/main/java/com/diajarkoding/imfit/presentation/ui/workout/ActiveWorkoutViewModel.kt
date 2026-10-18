package com.diajarkoding.imfit.presentation.ui.workout

import androidx.lifecycle.ViewModel
import androidx.annotation.StringRes
import com.diajarkoding.imfit.R
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.appendExercises
import com.diajarkoding.imfit.domain.model.removeIncompleteExercise
import com.diajarkoding.imfit.domain.repository.ExerciseRepository
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
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
    @StringRes val mutationErrorRes: Int? = null
)

@HiltViewModel
class ActiveWorkoutViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository,
    private val authRepository: AuthRepository,
    private val timer: RestTimerCoordinator,
    private val timerRuntime: com.diajarkoding.imfit.core.timer.RestTimerAndroidRuntime,
    private val exerciseRepository: ExerciseRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ActiveWorkoutState())
    val state = _state.asStateFlow()

    private val _workoutTimerUpdates = MutableSharedFlow<WorkoutTimerUpdate>(replay = 1)
    val workoutTimerUpdates: SharedFlow<WorkoutTimerUpdate> = _workoutTimerUpdates.asSharedFlow()
    
    private var elapsedTimeJob: Job? = null
    private var started = false
    private var sessionRevision = 0L
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
                    isRestTimerActive = rest?.status == TimerStatus.RUNNING,
                    restTimerSeconds = rest?.remainingSeconds(android.os.SystemClock.elapsedRealtime()) ?: 0,
                    restTimerExerciseName = rest?.exerciseName
                ) }
            }
        }
    }

    fun startWorkout(templateId: String, expectedSessionId: String? = null) {
        if (started) return
        started = true
        viewModelScope.launch {
            // First check for an existing active session
            val existingSession = try {
                withTimeout(2500) { workoutRepository.getActiveSessionForTimer() }
            } catch (e: TimeoutCancellationException) {
                _state.update { it.copy(timerError = true) }
                return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(timerError = true) }
                return@launch
            }
            timer.reconcile()
            if (expectedSessionId != null && existingSession?.id != expectedSessionId) {
                _state.update { it.copy(sessionEnded = true) }
                return@launch
            }
            
            if (existingSession != null) {
                // Restore existing session - preserve all data including completed sets
                android.util.Log.d("ActiveWorkoutVM", "Restoring existing session: ${existingSession.id}")
                
                // Restore session rest override if available
                val sessionOverride = workoutRepository.getSessionRestOverride()
                
                _state.update { 
                    it.copy(
                        session = existingSession, 
                        isPaused = existingSession.isPaused,
                        sessionRestOverride = sessionOverride
                    ) 
                }
                
                startElapsedTimeCounter()
                return@launch
            }
            
            // No existing session - create a new one
            val template = workoutRepository.getTemplateById(templateId) ?: return@launch
            
            val session = workoutRepository.startWorkout(template)
            
            // Prefill volumes BEFORE updating state to prevent race condition
            // where UI renders empty values before prefill completes
            val prefilledSession = prefillVolumes(session)
            
            _state.update { it.copy(session = prefilledSession) }

            startElapsedTimeCounter()
        }
    }

    /**
     * Pre-fills workout sets with weights from the last completed workout.
     * Uses local Room database for efficient per-set weight lookup.
     */
    private suspend fun prefillVolumes(session: WorkoutSession): WorkoutSession {
        return try {
            val userId = authRepository.getLocalUserId() ?: return session
            
            val updatedLogs = session.exerciseLogs.map { log ->
                // Get per-set weights from local Room database
                val lastWeights = workoutRepository.getLastWeightsForExercise(log.exercise.id, userId)
                android.util.Log.d("ActiveWorkoutVM", "Prefill ${log.exercise.name}: lastWeights=$lastWeights")
                
                if (lastWeights.isNotEmpty()) {
                    val newSets = log.sets.map { set ->
                        val lastWeight = lastWeights[set.setNumber]
                        if (lastWeight != null && set.weight == 0f) {
                            android.util.Log.d("ActiveWorkoutVM", "  Set ${set.setNumber}: prefilling weight=$lastWeight")
                            set.copy(weight = lastWeight)
                        } else {
                            set
                        }
                    }
                    log.copy(sets = newSets)
                } else {
                    log
                }
            }
            
            workoutRepository.mutateActiveSession(session.id) { current ->
                current.copy(exerciseLogs = current.exerciseLogs.map { log ->
                    val prefilled = updatedLogs.find { it.exercise.id == log.exercise.id }
                    log.copy(sets = log.sets.map { set ->
                        if (!set.isCompleted && set.weight == 0f) prefilled?.sets?.find { it.setNumber == set.setNumber }?.let { set.copy(weight = it.weight) } ?: set else set
                    })
                })
            } ?: session
        } catch (e: Exception) {
            android.util.Log.e("ActiveWorkoutVM", "Error prefilling volumes: ${e.message}", e)
            session // Return original session on error
        }
    }

    private fun startElapsedTimeCounter() {
        elapsedTimeJob?.cancel()
        elapsedTimeJob = viewModelScope.launch {
            while (true) {
                try {
                    val revision = sessionRevision
                    val session = withTimeout(2500) { workoutRepository.getActiveSessionForTimer() }
                    if (session == null) {
                        if (_state.value.session != null && !_state.value.isFinishing && !_state.value.isCancelling) {
                            _state.update { it.copy(session = null, sessionEnded = true, isRestTimerActive = false) }
                            return@launch
                        }
                        delay(1000)
                        continue
                    }
                    // Use actualElapsedMs which excludes paused time.
                    val elapsed = session.actualElapsedMs / 1000
                    timer.reconcile(source = "ui_ticker")
                    if (revision != sessionRevision || _state.value.isCancelling) continue
                    val rest = timer.state.value?.takeIf { it.workoutId == session.id }
                    _state.update { it.copy(elapsedSeconds = elapsed.coerceAtLeast(0), session = session, isPaused = session.isPaused,
                        isRestTimerActive = rest?.status == TimerStatus.RUNNING,
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
        viewModelScope.launch {
            _state.update { it.copy(sessionRestOverride = seconds) }
            workoutRepository.updateSessionRestOverride(seconds)
        }
        android.util.Log.d("ActiveWorkoutVM", "Set session rest override: $seconds seconds")
    }
    
    /**
     * Gets the current rest time configuration.
     * Returns session override if set, otherwise returns the default from first exercise.
     */
    fun getCurrentRestTime(): Int {
        val override = _state.value.sessionRestOverride
        if (override != null) return override
        
        // Return first exercise's rest time as default display value
        return _state.value.session?.exerciseLogs?.firstOrNull()?.restSeconds ?: 60
    }

    private fun publishSession(session: WorkoutSession) {
        sessionRevision++
        _state.update { it.copy(session = session, isPaused = session.isPaused) }
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

    fun updateRestTimer(exerciseIndex: Int, seconds: Int) = editExercise(exerciseIndex) { it.copy(restSeconds = seconds) }

    fun updateSet(exerciseIndex: Int, setIndex: Int, weight: Float, reps: Int) = editExercise(exerciseIndex) { log ->
        log.copy(sets = log.sets.mapIndexed { index, set ->
            if (index == setIndex && !set.isCompleted) set.copy(weight = weight, reps = reps) else set
        })
    }

    fun completeSet(exerciseIndex: Int, setIndex: Int) {
        val snapshot = _state.value
        if (snapshot.isPaused || snapshot.isFinishing || snapshot.isCancelling) return
        val session = snapshot.session ?: return
        val exercise = session.exerciseLogs.getOrNull(exerciseIndex) ?: return
        val setNumber = exercise.sets.getOrNull(setIndex)?.setNumber ?: return
        viewModelScope.launch {
            try {
                var completed: WorkoutSet? = null
                val updated = workoutRepository.mutateActiveSession(session.id) { current ->
                    if (current.isPaused) current else current.copy(exerciseLogs = current.exerciseLogs.map { log ->
                        if (log.exercise.id != exercise.exercise.id) log else log.copy(sets = log.sets.map { set ->
                            if (set.setNumber == setNumber && !set.isCompleted && set.weight > 0 && set.reps > 0) {
                                completed = set.copy(isCompleted = true); completed!!
                            } else set
                        })
                    })
                } ?: return@launch
                publishSession(updated)
                val set = completed ?: return@launch
                val restSeconds = _state.value.sessionRestOverride ?: updated.exerciseLogs.first { it.exercise.id == exercise.exercise.id }.restSeconds
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

    fun addSet(exerciseIndex: Int) = editExercise(exerciseIndex) { log ->
        log.copy(sets = log.sets + WorkoutSet(setNumber = (log.sets.maxOfOrNull { it.setNumber } ?: 0) + 1))
    }

    fun removeSet(exerciseIndex: Int, setIndex: Int) = editExercise(exerciseIndex) { log ->
        if (log.sets.size <= 1 || log.sets.getOrNull(setIndex)?.isCompleted != false) log
        else log.copy(sets = log.sets.filterIndexed { index, _ -> index != setIndex })
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

    fun addExercisesByIds(sessionId: String, exerciseIds: List<String>, onConsumed: () -> Unit) {
        if (_state.value.isAddingExercises || _state.value.isFinishing || _state.value.isCancelling) return
        _state.update { it.copy(isAddingExercises = true, mutationErrorRes = null) }
        viewModelScope.launch {
            try {
                val userId = authRepository.getLocalUserId() ?: return@launch
                val additions = exerciseIds.distinct().mapNotNull { id ->
                    exerciseRepository.getExerciseById(id)?.let { exercise ->
                        val config = TemplateExercise(exercise)
                        val weights = workoutRepository.getLastWeightsForExercise(id, userId)
                        ExerciseLog(exercise, (1..config.sets).map { WorkoutSet(it, weights[it] ?: 0f, config.reps, false) }, config.restSeconds)
                    }
                }
                workoutRepository.mutateActiveSession(sessionId) { it.appendExercises(additions) }?.let(::publishSession)
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
        viewModelScope.launch {
            val allowed = timer.whenRestInactive { persistWorkoutPause() }
            if (!allowed) _state.update { it.copy(pauseErrorRes = R.string.error_rest_pause) }
        }
    }
    private suspend fun persistWorkoutPause() {
        val session = _state.value.session ?: return
        workoutRepository.mutateActiveSession(session.id) { current ->
            if (current.isPaused) current else current.copy(isPaused = true, lastPauseTime = System.currentTimeMillis())
        }?.let(::publishSession)
    }
    
    fun resumeWorkout() {
        viewModelScope.launch {
            val currentSession = _state.value.session ?: return@launch
            
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
