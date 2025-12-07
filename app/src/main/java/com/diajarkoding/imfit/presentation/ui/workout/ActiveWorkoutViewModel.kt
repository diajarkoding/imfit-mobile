package com.diajarkoding.imfit.presentation.ui.workout

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutSet
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
    val elapsedMinutes: Int = 0,
    val isRestTimerActive: Boolean = false,
    val restTimerSeconds: Int = 90,
    val showCancelDialog: Boolean = false,
    val workoutLogId: String? = null
)

@HiltViewModel
class ActiveWorkoutViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ActiveWorkoutState())
    val state = _state.asStateFlow()

    private var elapsedTimeJob: Job? = null
    private var restTimerJob: Job? = null

    fun startWorkout(templateId: String) {
        val template = workoutRepository.getTemplateById(templateId) ?: return

        val session = workoutRepository.startWorkout(template)
        _state.update { it.copy(session = session) }

        startElapsedTimeCounter()
    }

    private fun startElapsedTimeCounter() {
        elapsedTimeJob?.cancel()
        elapsedTimeJob = viewModelScope.launch {
            while (true) {
                delay(60000) // Update every minute
                _state.update { it.copy(elapsedMinutes = it.session?.durationMinutes ?: 0) }
            }
        }

        // Also update immediately and then every 10 seconds for more responsive UI
        viewModelScope.launch {
            while (true) {
                delay(10000)
                _state.update { it.copy(elapsedMinutes = it.session?.durationMinutes ?: 0) }
            }
        }
    }

    fun updateSet(exerciseIndex: Int, setIndex: Int, weight: Float, reps: Int) {
        val currentSession = _state.value.session ?: return

        val updatedExerciseLogs = currentSession.exerciseLogs.toMutableList()
        val exerciseLog = updatedExerciseLogs.getOrNull(exerciseIndex) ?: return

        val updatedSets = exerciseLog.sets.toMutableList()
        val currentSet = updatedSets.getOrNull(setIndex) ?: return

        updatedSets[setIndex] = currentSet.copy(weight = weight, reps = reps)
        updatedExerciseLogs[exerciseIndex] = exerciseLog.copy(sets = updatedSets)

        val updatedSession = currentSession.copy(exerciseLogs = updatedExerciseLogs)
        workoutRepository.updateActiveSession(updatedSession)
        _state.update { it.copy(session = updatedSession) }
    }

    fun completeSet(exerciseIndex: Int, setIndex: Int) {
        val currentSession = _state.value.session ?: return

        val updatedExerciseLogs = currentSession.exerciseLogs.toMutableList()
        val exerciseLog = updatedExerciseLogs.getOrNull(exerciseIndex) ?: return

        val updatedSets = exerciseLog.sets.toMutableList()
        val currentSet = updatedSets.getOrNull(setIndex) ?: return

        if (currentSet.weight <= 0 || currentSet.reps <= 0) return

        updatedSets[setIndex] = currentSet.copy(isCompleted = true)
        updatedExerciseLogs[exerciseIndex] = exerciseLog.copy(sets = updatedSets)

        val updatedSession = currentSession.copy(exerciseLogs = updatedExerciseLogs)
        workoutRepository.updateActiveSession(updatedSession)
        _state.update { it.copy(session = updatedSession) }

        // Start rest timer
        startRestTimer()
    }

    private fun startRestTimer() {
        restTimerJob?.cancel()
        _state.update {
            it.copy(
                isRestTimerActive = true,
                restTimerSeconds = it.session?.restTimerSeconds ?: 90
            )
        }

        restTimerJob = viewModelScope.launch {
            var remaining = _state.value.restTimerSeconds
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
        elapsedTimeJob?.cancel()
        restTimerJob?.cancel()
        workoutRepository.cancelWorkout()
    }

    fun finishWorkout() {
        elapsedTimeJob?.cancel()
        restTimerJob?.cancel()

        val workoutLog = workoutRepository.finishWorkout()
        workoutLog?.let { log ->
            _state.update { it.copy(workoutLogId = log.id) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        elapsedTimeJob?.cancel()
        restTimerJob?.cancel()
    }
}
