package com.diajarkoding.imfit.presentation.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ActiveWorkoutState(
    val session: WorkoutSession? = null,
    val elapsedSeconds: Long = 0,
    val isRestTimerActive: Boolean = false,
    val restTimerSeconds: Int = 60,
    val showCancelDialog: Boolean = false,
    val workoutLogId: String? = null
)

@HiltViewModel
class ActiveWorkoutViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ActiveWorkoutState())
    val state = _state.asStateFlow()

    private var elapsedTimeJob: Job? = null
    private var restTimerJob: Job? = null

    fun startWorkout(templateId: String) {
        viewModelScope.launch {
            // First check for an existing active session
            val existingSession = workoutRepository.getActiveSession()
            
            if (existingSession != null) {
                // Restore existing session - preserve all data including completed sets
                android.util.Log.d("ActiveWorkoutVM", "Restoring existing session: ${existingSession.id}")
                _state.update { it.copy(session = existingSession) }
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
                val startTime = _state.value.session?.startTime ?: System.currentTimeMillis()
                val elapsed = (System.currentTimeMillis() - startTime) / 1000
                _state.update { it.copy(elapsedSeconds = elapsed) }
                delay(1000)
            }
        }
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

            // Start rest timer with exercise-specific rest time
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
     * Starts the rest timer using the rest time from the specified exercise.
     * @param exerciseIndex The index of the exercise to get rest time from.
     */
    private fun startRestTimer(exerciseIndex: Int) {
        restTimerJob?.cancel()
        
        // Get rest time from the specific exercise in the session
        val restSeconds = _state.value.session?.getRestSecondsForExercise(exerciseIndex) ?: 60
        
        _state.update {
            it.copy(
                isRestTimerActive = true,
                restTimerSeconds = restSeconds
            )
        }

        restTimerJob = viewModelScope.launch {
            var remaining = restSeconds
            while (remaining > 0) {
                delay(1000)
                remaining--
                _state.update { it.copy(restTimerSeconds = remaining) }
            }
            _state.update { it.copy(isRestTimerActive = false) }
        }
    }

    fun skipRestTimer() {
        restTimerJob?.cancel()
        _state.update { it.copy(isRestTimerActive = false) }
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
