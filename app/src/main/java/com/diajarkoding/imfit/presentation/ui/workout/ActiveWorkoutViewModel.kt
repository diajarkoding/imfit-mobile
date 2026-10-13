package com.diajarkoding.imfit.presentation.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutSet
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
    val pauseError: String? = null,
    val isFinishing: Boolean = false,
    val restLimited: Boolean = false,
    val timerError: Boolean = false,
    val restSoundEnabled: Boolean = true,
    val restVibrationEnabled: Boolean = false,
    val sessionEnded: Boolean = false,
    val fullScreenAlarmAllowed: Boolean = true
)

@HiltViewModel
class ActiveWorkoutViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository,
    private val authRepository: AuthRepository,
    private val timer: RestTimerCoordinator,
    private val timerRuntime: com.diajarkoding.imfit.core.timer.RestTimerAndroidRuntime
) : ViewModel() {

    private val _state = MutableStateFlow(ActiveWorkoutState())
    val state = _state.asStateFlow()

    private val _workoutTimerUpdates = MutableSharedFlow<WorkoutTimerUpdate>(replay = 1)
    val workoutTimerUpdates: SharedFlow<WorkoutTimerUpdate> = _workoutTimerUpdates.asSharedFlow()
    
    private var elapsedTimeJob: Job? = null
    val restState = timer.state

    fun extendRest() { viewModelScope.launch { timer.extend() } }
    fun refreshTimerCapabilities() {
        _state.update { it.copy(fullScreenAlarmAllowed = timerRuntime.fullScreenIntentAvailable(),
            restLimited = timerRuntime.capabilityIssue() != null) }
        viewModelScope.launch { timer.capabilitiesChanged() }
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

    fun startWorkout(templateId: String) {
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
            val userId = authRepository.getCurrentUser()?.id ?: return session
            
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
            
            val updatedSession = session.copy(exerciseLogs = updatedLogs)
            workoutRepository.updateActiveSession(updatedSession)
            updatedSession
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
                    val session = withTimeout(2500) { workoutRepository.getActiveSessionForTimer() }
                    if (session == null) {
                        if (_state.value.session != null && !_state.value.isFinishing) {
                            _state.update { it.copy(session = null, sessionEnded = true, isRestTimerActive = false) }
                            return@launch
                        }
                        delay(1000)
                        continue
                    }
                    // Use actualElapsedMs which excludes paused time.
                    val elapsed = session.actualElapsedMs / 1000
                    timer.reconcile()
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

    fun updateRestTimer(exerciseIndex: Int, seconds: Int) {
        viewModelScope.launch {
            val currentSession = _state.value.session ?: return@launch
            
            val updatedExerciseLogs = currentSession.exerciseLogs.toMutableList()
            val exerciseLog = updatedExerciseLogs.getOrNull(exerciseIndex) ?: return@launch
            
            updatedExerciseLogs[exerciseIndex] = exerciseLog.copy(restSeconds = seconds)
            
            val updatedSession = currentSession.copy(exerciseLogs = updatedExerciseLogs)
            workoutRepository.updateActiveSession(updatedSession)
            _state.update { it.copy(session = updatedSession) }
        }
    }

    fun updateSet(exerciseIndex: Int, setIndex: Int, weight: Float, reps: Int) {
        viewModelScope.launch {
            val currentSession = _state.value.session ?: return@launch

            val updatedExerciseLogs = currentSession.exerciseLogs.toMutableList()
            val exerciseLog = updatedExerciseLogs.getOrNull(exerciseIndex) ?: return@launch

            val updatedSets = exerciseLog.sets.toMutableList()
            val currentSet = updatedSets.getOrNull(setIndex) ?: return@launch

            updatedSets[setIndex] = currentSet.copy(weight = weight, reps = reps)
            updatedExerciseLogs[exerciseIndex] = exerciseLog.copy(sets = updatedSets)

            val updatedSession = currentSession.copy(exerciseLogs = updatedExerciseLogs)
            workoutRepository.updateActiveSession(updatedSession)
            _state.update { it.copy(session = updatedSession) }
        }
    }

    fun completeSet(exerciseIndex: Int, setIndex: Int) {
        viewModelScope.launch {
            val currentSession = _state.value.session ?: return@launch
            
            // Cannot complete sets while paused
            if (_state.value.isPaused) return@launch

            val updatedExerciseLogs = currentSession.exerciseLogs.toMutableList()
            val exerciseLog = updatedExerciseLogs.getOrNull(exerciseIndex) ?: return@launch

            val updatedSets = exerciseLog.sets.toMutableList()
            val currentSet = updatedSets.getOrNull(setIndex) ?: return@launch

            if (currentSet.isCompleted || currentSet.weight <= 0 || currentSet.reps <= 0) return@launch

            updatedSets[setIndex] = currentSet.copy(isCompleted = true)
            updatedExerciseLogs[exerciseIndex] = exerciseLog.copy(sets = updatedSets)

            val updatedSession = currentSession.copy(exerciseLogs = updatedExerciseLogs)
            workoutRepository.updateActiveSession(updatedSession)
            _state.update { it.copy(session = updatedSession) }

            // Start rest timer
            val restSeconds = _state.value.sessionRestOverride 
                ?: _state.value.session?.getRestSecondsForExercise(exerciseIndex) 
                ?: 60
            val exerciseName = _state.value.session?.exerciseLogs?.getOrNull(exerciseIndex)?.exercise?.name
            
            val user = authRepository.getCurrentUser() ?: return@launch
            timer.start(RestTimerState(
                timerId = "", userId = user.id, workoutId = currentSession.id, templateId = currentSession.templateId,
                exerciseId = exerciseLog.exercise.id,
                setId = "${currentSession.id}:${exerciseLog.exercise.id}:$exerciseIndex:${currentSet.setNumber}",
                exerciseName = exerciseName.orEmpty(), durationMs = restSeconds.toLong() * 1000,
                deadlineElapsedMs = 0, bootIdentity = null, startedAtEpochMs = 0
            ))
            _state.update { it.copy(isRestTimerActive = timer.state.value?.status == TimerStatus.RUNNING,
                restTimerSeconds = restSeconds, restTimerExerciseName = exerciseName) }
        }
    }

    fun addSet(exerciseIndex: Int) {
        viewModelScope.launch {
            val currentSession = _state.value.session ?: return@launch

            val updatedExerciseLogs = currentSession.exerciseLogs.toMutableList()
            val exerciseLog = updatedExerciseLogs.getOrNull(exerciseIndex) ?: return@launch

            val newSetNumber = exerciseLog.sets.size + 1
            val newSet = WorkoutSet(setNumber = newSetNumber)
            val updatedSets = exerciseLog.sets + newSet
            updatedExerciseLogs[exerciseIndex] = exerciseLog.copy(sets = updatedSets)

            val updatedSession = currentSession.copy(exerciseLogs = updatedExerciseLogs)
            workoutRepository.updateActiveSession(updatedSession)
            _state.update { it.copy(session = updatedSession) }
        }
    }

    fun removeSet(exerciseIndex: Int, setIndex: Int) {
        viewModelScope.launch {
            val currentSession = _state.value.session ?: return@launch

            val updatedExerciseLogs = currentSession.exerciseLogs.toMutableList()
            val exerciseLog = updatedExerciseLogs.getOrNull(exerciseIndex) ?: return@launch

            if (exerciseLog.sets.size <= 1) return@launch

            val updatedSets = exerciseLog.sets.toMutableList()
            updatedSets.removeAt(setIndex)
            val renumberedSets = updatedSets.mapIndexed { index, set ->
                set.copy(setNumber = index + 1)
            }
            updatedExerciseLogs[exerciseIndex] = exerciseLog.copy(sets = renumberedSets)

            val updatedSession = currentSession.copy(exerciseLogs = updatedExerciseLogs)
            workoutRepository.updateActiveSession(updatedSession)
            _state.update { it.copy(session = updatedSession) }
        }
    }

    fun skipRestTimer() {
        viewModelScope.launch {
            timer.skip()
            _state.update { it.copy(isRestTimerActive = false, restTimerExerciseName = null) }
        }
    }

    fun pauseWorkout() {
        viewModelScope.launch {
            val allowed = timer.whenRestInactive { persistWorkoutPause() }
            if (!allowed) _state.update { it.copy(pauseError = "Skip rest timer before pausing") }
        }
    }
    private suspend fun persistWorkoutPause() {
            val currentSession = workoutRepository.getActiveSession() ?: return
            
            // Cannot pause during rest timer
            
            // Already paused
            if (currentSession.isPaused) return
            
            // Record pause start time
            val pauseStartTime = System.currentTimeMillis()
            val updatedSession = currentSession.copy(
                isPaused = true,
                lastPauseTime = pauseStartTime
            )
            
            workoutRepository.updateActiveSession(updatedSession)
            _state.update { it.copy(session = updatedSession, isPaused = true) }
            
            android.util.Log.d("ActiveWorkoutVM", "Workout paused at $pauseStartTime")
    }
    
    fun resumeWorkout() {
        viewModelScope.launch {
            val currentSession = _state.value.session ?: return@launch
            
            // Not paused
            if (!_state.value.isPaused) return@launch
            
            // Calculate pause duration
            val pauseEnd = System.currentTimeMillis()
            val pauseDuration = currentSession.lastPauseTime?.let { pauseEnd - it } ?: 0L
            
            // Accumulate pause time
            val updatedSession = currentSession.copy(
                isPaused = false,
                lastPauseTime = null,
                totalPausedTimeMs = currentSession.totalPausedTimeMs + pauseDuration
            )
            
            workoutRepository.updateActiveSession(updatedSession)
            _state.update { it.copy(session = updatedSession, isPaused = false) }
            
            android.util.Log.d("ActiveWorkoutVM", "Workout resumed. Pause duration: ${pauseDuration}ms, Total paused: ${updatedSession.totalPausedTimeMs}ms")
        }
    }
    
    fun clearPauseError() {
        _state.update { it.copy(pauseError = null) }
    }

    fun showCancelDialog() {
        _state.update { it.copy(showCancelDialog = true) }
    }

    fun dismissCancelDialog() {
        _state.update { it.copy(showCancelDialog = false) }
    }

    fun cancelWorkout() {
        viewModelScope.launch {
            elapsedTimeJob?.cancel()
            workoutRepository.cancelWorkout()
        }
    }

    fun finishWorkout() {
        if (_state.value.isFinishing) return
        
        viewModelScope.launch {
            _state.update { it.copy(isFinishing = true) }
            elapsedTimeJob?.cancel()

            val workoutLog = workoutRepository.finishWorkout()
            workoutLog?.let { log ->
                _state.update { it.copy(workoutLogId = log.id) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        elapsedTimeJob?.cancel()
    }
}
