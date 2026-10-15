package com.diajarkoding.imfit.presentation.ui.workout

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

data class EditWorkoutState(
    val workoutName: String = "",
    val exercises: List<TemplateExercise> = emptyList(),
    val isLoading: Boolean = false,
    val isSaved: Boolean = false,
    @StringRes val errorRes: Int? = null
)

@HiltViewModel
class EditWorkoutViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private var workoutId: String? = savedStateHandle["workoutId"]
    private var initialized = false

    private val _state = MutableStateFlow(EditWorkoutState())
    val state = _state.asStateFlow()

    fun initialize(workoutId: String) {
        if (workoutId.isBlank() || (initialized && this.workoutId == workoutId)) return

        this.workoutId = workoutId
        savedStateHandle["workoutId"] = workoutId
        initialized = true
        loadWorkout()
    }

    fun loadWorkout() {
        val workoutId = workoutId ?: return
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
                } else _state.update { it.copy(errorRes = R.string.error_routine_not_found, isLoading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("EditWorkoutVM", "Routine load failed", e)
                _state.update { it.copy(errorRes = R.string.error_routine_load, isLoading = false) }
            }
        }
    }

    fun updateWorkoutName(name: String) {
        if (_state.value.isLoading) return
        _state.update { it.copy(workoutName = name) }
    }

    fun updateExerciseSets(exerciseIndex: Int, sets: Int) {
        if (_state.value.isLoading) return
        _state.update { state ->
            val updatedExercises = state.exercises.toMutableList()
            if (exerciseIndex in updatedExercises.indices) {
                updatedExercises[exerciseIndex] = updatedExercises[exerciseIndex].copy(sets = sets)
            }
            state.copy(exercises = updatedExercises)
        }
    }

    fun updateExerciseReps(exerciseIndex: Int, reps: Int) {
        if (_state.value.isLoading) return
        _state.update { state ->
            val updatedExercises = state.exercises.toMutableList()
            if (exerciseIndex in updatedExercises.indices) {
                updatedExercises[exerciseIndex] = updatedExercises[exerciseIndex].copy(reps = reps)
            }
            state.copy(exercises = updatedExercises)
        }
    }

    fun updateExerciseRest(exerciseIndex: Int, rest: Int) {
        if (_state.value.isLoading) return
        _state.update { state ->
            val updatedExercises = state.exercises.toMutableList()
            if (exerciseIndex in updatedExercises.indices) {
                updatedExercises[exerciseIndex] = updatedExercises[exerciseIndex].copy(restSeconds = rest)
            }
            state.copy(exercises = updatedExercises)
        }
    }

    fun removeExercise(exerciseIndex: Int) {
        if (_state.value.isLoading) return
        _state.update { state ->
            val updatedExercises = state.exercises.toMutableList()
            if (exerciseIndex in updatedExercises.indices) {
                updatedExercises.removeAt(exerciseIndex)
            }
            state.copy(exercises = updatedExercises)
        }
    }

    fun addSet(exerciseIndex: Int) {
        if (_state.value.isLoading) return
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
        if (_state.value.isLoading) return
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
        if (_state.value.isLoading || _state.value.isSaved || _state.value.workoutName.isBlank()) return
        val workoutId = workoutId ?: return
        val draft = _state.value
        _state.update { it.copy(isLoading = true, errorRes = null) }
        viewModelScope.launch {
            try {
                val saved = workoutRepository.updateTemplate(
                    templateId = workoutId,
                    name = draft.workoutName.trim(),
                    exercises = draft.exercises
                )
                if (saved == null) {
                    _state.update { it.copy(errorRes = R.string.error_routine_not_found, isLoading = false) }
                } else {
                    _state.update { it.copy(isSaved = true, isLoading = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("EditWorkoutVM", "Routine save failed", e)
                _state.update { it.copy(errorRes = R.string.error_routine_save, isLoading = false) }
            }
        }
    }
}
