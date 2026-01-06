package com.diajarkoding.imfit.presentation.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import com.diajarkoding.imfit.core.model.RestTimerUpdate
import com.diajarkoding.imfit.core.model.WorkoutTimerUpdate
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
import javax.inject.Inject

data class ActiveWorkoutState(
    val session: WorkoutSession? = null,
    val elapsedSeconds: Long = 0,
    val isRestTimerActive: Boolean = false,
    val restTimerSeconds: Int = 60,
    val showCancelDialog: Boolean = false,
    val workoutLogId: String? = null,
    val sessionRestOverride: Int? = null,
    val isPaused: Boolean = false,
    val pauseError: String? = null
)

@HiltViewModel
class ActiveWorkoutViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ActiveWorkoutState())
    val state = _state.asStateFlow()

    private val _workoutTimerUpdates = MutableSharedFlow<WorkoutTimerUpdate>(replay = 1)
    val workoutTimerUpdates: SharedFlow<WorkoutTimerUpdate> = _workoutTimerUpdates.asSharedFlow()
    
    private val _restTimerUpdates = MutableSharedFlow<RestTimerUpdate>(replay = 1)
    val restTimerUpdates: SharedFlow<RestTimerUpdate> = _restTimerUpdates.asSharedFlow()

    private var elapsedTimeJob: Job? = null
    private var restTimerJob: Job? = null

    fun startWorkout(templateId: String) {
        viewModelScope.launch {
            // First check for an existing active session
            val existingSession = workoutRepository.getActiveSession()
            
            if (existingSession != null) {
                // Restore existing session - preserve all data including completed sets
                android.util.Log.d("ActiveWorkoutVM", "Restoring existing session: ${existingSession.id}")
                _state.update { 
                    it.copy(
                        session = existingSession, 
                        isPaused = existingSession.isPaused
                    ) 
                }
                // Only start timer if not paused
                if (!existingSession.isPaused) {
                    startElapsedTimeCounter()
                }
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
                val session = _state.value.session
                if (session == null) {
                    delay(1000)
                    continue
                }
                // Use actualElapsedMs which excludes paused time
                val elapsed = session.actualElapsedMs / 1000
                _state.update { it.copy(elapsedSeconds = elapsed) }
                
                // Only emit workout timer update when rest timer is NOT active
                // This prevents flickering between workout and rest timer notifications
                if (!_state.value.isRestTimerActive) {
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
        _state.update { it.copy(sessionRestOverride = seconds) }
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

            if (currentSet.weight <= 0 || currentSet.reps <= 0) return@launch

            updatedSets[setIndex] = currentSet.copy(isCompleted = true)
            updatedExerciseLogs[exerciseIndex] = exerciseLog.copy(sets = updatedSets)

            val updatedSession = currentSession.copy(exerciseLogs = updatedExerciseLogs)
            workoutRepository.updateActiveSession(updatedSession)
            _state.update { it.copy(session = updatedSession) }

            // Start rest timer
            startRestTimer(exerciseIndex)
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

    /**
     * Starts the rest timer.
     * Uses session-level override if set, otherwise uses the exercise's configured rest time.
     */
    private fun startRestTimer(exerciseIndex: Int) {
        restTimerJob?.cancel()
        
        // Use session override if set, otherwise use exercise's rest time
        val restSeconds = _state.value.sessionRestOverride 
            ?: _state.value.session?.getRestSecondsForExercise(exerciseIndex) 
            ?: 60
        
        _state.update {
            it.copy(
                isRestTimerActive = true,
                restTimerSeconds = restSeconds
            )
        }

        restTimerJob = viewModelScope.launch {
            var remaining = restSeconds
            val exerciseName = _state.value.session?.exerciseLogs?.getOrNull(exerciseIndex)?.exercise?.name
            
            // Emit initial rest timer update
            _restTimerUpdates.emit(RestTimerUpdate(remaining, true, exerciseName))
            
            while (remaining > 0) {
                delay(1000)
                remaining--
                _state.update { it.copy(restTimerSeconds = remaining) }
                
                // Emit rest timer update for notification
                _restTimerUpdates.emit(RestTimerUpdate(remaining, true, exerciseName))
            }
            _state.update { it.copy(isRestTimerActive = false) }
            
            // Emit rest ended update
            _restTimerUpdates.emit(RestTimerUpdate(0, false, null))
        }
    }

    fun skipRestTimer() {
        restTimerJob?.cancel()
        _state.update { it.copy(isRestTimerActive = false) }
        
        // Emit rest ended update
        viewModelScope.launch {
            _restTimerUpdates.emit(RestTimerUpdate(0, false, null))
        }
    }

    fun pauseWorkout() {
        viewModelScope.launch {
            val currentSession = _state.value.session ?: return@launch
            
            // Cannot pause during rest timer
            if (_state.value.isRestTimerActive) {
                _state.update { it.copy(pauseError = "Skip rest timer before pausing") }
                return@launch
            }
            
            // Already paused
            if (_state.value.isPaused) return@launch
            
            // Cancel elapsed timer
            elapsedTimeJob?.cancel()
            
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
            
            // Restart timer
            startElapsedTimeCounter()
            
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
            restTimerJob?.cancel()
            workoutRepository.cancelWorkout()
        }
    }

    fun finishWorkout() {
        viewModelScope.launch {
            elapsedTimeJob?.cancel()
            restTimerJob?.cancel()

            val workoutLog = workoutRepository.finishWorkout()
            workoutLog?.let { log ->
                _state.update { it.copy(workoutLogId = log.id) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        elapsedTimeJob?.cancel()
        restTimerJob?.cancel()
    }
}
