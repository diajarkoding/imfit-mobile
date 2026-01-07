package com.diajarkoding.imfit.presentation.ui.workout

import android.util.Log
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
    val isDeleting: Boolean = false,
    val isUpdating: Boolean = false,
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
            _state.update { it.copy(isDeleting = true, error = null) }
            try {
                workoutRepository.deleteTemplate(workoutId)
                _state.update { it.copy(isDeleted = true, isDeleting = false) }
            } catch (e: Exception) {
                Log.e("WorkoutDetailViewModel", "Failed to delete workout: ${e.message}", e)
                _state.update { 
                    it.copy(
                        isDeleting = false, 
                        error = "Failed to delete workout. Please check your connection."
                    ) 
                }
            }
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
            
            _state.update { it.copy(isUpdating = true, error = null) }
            
            val templateExercises = newExercises.map { exercise ->
                TemplateExercise(exercise = exercise)
            }
            val updatedExercises = currentWorkout.exercises + templateExercises
            
            try {
                workoutRepository.updateTemplateExercises(workoutId, updatedExercises)
                _state.update { it.copy(isUpdating = false) }
                loadWorkout()
            } catch (e: Exception) {
                Log.e("WorkoutDetailViewModel", "Failed to add exercises: ${e.message}", e)
                _state.update { 
                    it.copy(
                        isUpdating = false, 
                        error = "Failed to add exercises. Please check your connection."
                    ) 
                }
            }
        }
    }

    fun removeExercise(templateExercise: TemplateExercise) {
        viewModelScope.launch {
            val currentWorkout = _state.value.workout ?: return@launch
            val updatedExercises = currentWorkout.exercises.filter { it.id != templateExercise.id }
            
            _state.update { it.copy(isUpdating = true, error = null) }
            
            try {
                workoutRepository.updateTemplateExercises(workoutId, updatedExercises)
                _state.update { it.copy(isUpdating = false) }
                loadWorkout()
            } catch (e: Exception) {
                Log.e("WorkoutDetailViewModel", "Failed to remove exercise: ${e.message}", e)
                _state.update { 
                    it.copy(
                        isUpdating = false, 
                        error = "Failed to remove exercise. Please check your connection."
                    ) 
                }
            }
        }
    }

    fun updateExerciseConfig(exerciseId: String, sets: Int, reps: Int, restSeconds: Int) {
        viewModelScope.launch {
            _state.update { it.copy(isUpdating = true, error = null) }
            
            try {
                workoutRepository.updateTemplateExercise(workoutId, exerciseId, sets, reps, restSeconds)
                _state.update { it.copy(isUpdating = false) }
                loadWorkout()
            } catch (e: Exception) {
                Log.e("WorkoutDetailViewModel", "Failed to update exercise: ${e.message}", e)
                _state.update { 
                    it.copy(
                        isUpdating = false, 
                        error = "Failed to update exercise. Please check your connection."
                    ) 
                }
            }
        }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }

    val estimatedDuration: Int
        get() = (_state.value.workout?.exerciseCount ?: 0) * 8
}
