package com.diajarkoding.imfit.presentation.ui.workout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WorkoutDetailState(
    val workout: WorkoutTemplate? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val isWorkoutActive: Boolean = false,       // True if ANY active session exists
    val isCurrentWorkoutActive: Boolean = false, // True if this specific template has active session
    val workoutFinished: Boolean = false,
    val isDeleted: Boolean = false
)

@HiltViewModel
class WorkoutDetailViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val workoutId: String = savedStateHandle.get<String>("workoutId") ?: ""
    
    private val _state = MutableStateFlow(WorkoutDetailState())
    val state = _state.asStateFlow()

    init {
        loadWorkout()
    }

    fun loadWorkout() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val workout = workoutRepository.getTemplateById(workoutId)
                val activeSession = workoutRepository.getActiveSession()
                val isCurrentActive = activeSession?.templateId == workoutId
                val hasAnyActiveSession = activeSession != null
                _state.update { it.copy(
                    workout = workout, 
                    isLoading = false, 
                    isWorkoutActive = hasAnyActiveSession,
                    isCurrentWorkoutActive = isCurrentActive
                ) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun endWorkout() {
        viewModelScope.launch {
            workoutRepository.finishWorkout()
            _state.update { it.copy(isWorkoutActive = false, workoutFinished = true) }
        }
    }

    fun clearWorkoutFinished() {
        _state.update { it.copy(workoutFinished = false) }
    }

    fun deleteWorkout() {
        viewModelScope.launch {
            workoutRepository.deleteTemplate(workoutId)
            _state.update { it.copy(isDeleted = true) }
        }
    }

    fun addExercises(exercises: List<Exercise>) {
        viewModelScope.launch {
            val currentWorkout = _state.value.workout ?: return@launch
            
            // Get IDs of exercises already in the template
            val existingExerciseIds = currentWorkout.exercises.map { it.exercise.id }.toSet()
            
            // Filter out exercises that are already in the template
            val newExercises = exercises.filter { it.id !in existingExerciseIds }
            
            if (newExercises.isEmpty()) return@launch
            
            val templateExercises = newExercises.map { exercise ->
                TemplateExercise(exercise = exercise)
            }
            val updatedExercises = currentWorkout.exercises + templateExercises
            
            workoutRepository.updateTemplateExercises(workoutId, updatedExercises)
            loadWorkout()
        }
    }

    fun removeExercise(templateExercise: TemplateExercise) {
        viewModelScope.launch {
            val currentWorkout = _state.value.workout ?: return@launch
            val updatedExercises = currentWorkout.exercises.filter { it.id != templateExercise.id }
            
            workoutRepository.updateTemplateExercises(workoutId, updatedExercises)
            loadWorkout()
        }
    }

    fun updateExerciseConfig(exerciseId: String, sets: Int, reps: Int, restSeconds: Int) {
        viewModelScope.launch {
            workoutRepository.updateTemplateExercise(workoutId, exerciseId, sets, reps, restSeconds)
            loadWorkout()
        }
    }

    val estimatedDuration: Int
        get() = (_state.value.workout?.exerciseCount ?: 0) * 8
}
