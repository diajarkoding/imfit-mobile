package com.diajarkoding.imfit.presentation.ui.workout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class EditWorkoutState(
    val workoutName: String = "",
    val exercises: List<TemplateExercise> = emptyList(),
    val isLoading: Boolean = false,
    val isSaved: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class EditWorkoutViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val workoutId: String = savedStateHandle.get<String>("workoutId") ?: ""

    private val _state = MutableStateFlow(EditWorkoutState())
    val state = _state.asStateFlow()

    init {
        loadWorkout()
    }

    fun loadWorkout() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                val workout = workoutRepository.getTemplateById(workoutId)
                if (workout != null) {
                    _state.update {
                        it.copy(
                            workoutName = workout.name,
                            exercises = workout.exercises,
                            isLoading = false
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }

    fun updateWorkoutName(name: String) {
        _state.update { it.copy(workoutName = name) }
    }

    fun updateExerciseSets(exerciseIndex: Int, sets: Int) {
        _state.update { state ->
            val updatedExercises = state.exercises.toMutableList()
            if (exerciseIndex in updatedExercises.indices) {
                updatedExercises[exerciseIndex] = updatedExercises[exerciseIndex].copy(sets = sets)
            }
            state.copy(exercises = updatedExercises)
        }
    }

    fun updateExerciseReps(exerciseIndex: Int, reps: Int) {
        _state.update { state ->
            val updatedExercises = state.exercises.toMutableList()
            if (exerciseIndex in updatedExercises.indices) {
                updatedExercises[exerciseIndex] = updatedExercises[exerciseIndex].copy(reps = reps)
            }
            state.copy(exercises = updatedExercises)
        }
    }

    fun updateExerciseRest(exerciseIndex: Int, rest: Int) {
        _state.update { state ->
            val updatedExercises = state.exercises.toMutableList()
            if (exerciseIndex in updatedExercises.indices) {
                updatedExercises[exerciseIndex] = updatedExercises[exerciseIndex].copy(restSeconds = rest)
            }
            state.copy(exercises = updatedExercises)
        }
    }

    fun removeExercise(exerciseIndex: Int) {
        _state.update { state ->
            val updatedExercises = state.exercises.toMutableList()
            if (exerciseIndex in updatedExercises.indices) {
                updatedExercises.removeAt(exerciseIndex)
            }
            state.copy(exercises = updatedExercises)
        }
    }

    fun addSet(exerciseIndex: Int) {
        _state.update { state ->
            val updatedExercises = state.exercises.toMutableList()
            if (exerciseIndex in updatedExercises.indices) {
                val current = updatedExercises[exerciseIndex]
                updatedExercises[exerciseIndex] = current.copy(sets = current.sets + 1)
            }
            state.copy(exercises = updatedExercises)
        }
    }

    fun removeSet(exerciseIndex: Int, setIndex: Int) {
        _state.update { state ->
            val updatedExercises = state.exercises.toMutableList()
            if (exerciseIndex in updatedExercises.indices) {
                val current = updatedExercises[exerciseIndex]
                if (current.sets > 1) {
                    updatedExercises[exerciseIndex] = current.copy(sets = current.sets - 1)
                }
            }
            state.copy(exercises = updatedExercises)
        }
    }

    fun saveChanges() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            try {
                workoutRepository.updateTemplate(
                    templateId = workoutId,
                    name = _state.value.workoutName,
                    exercises = _state.value.exercises
                )
                _state.update { it.copy(isSaved = true, isLoading = false) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message, isLoading = false) }
            }
        }
    }
}
