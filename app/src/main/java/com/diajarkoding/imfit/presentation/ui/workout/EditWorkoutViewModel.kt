package com.diajarkoding.imfit.presentation.ui.workout

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import com.diajarkoding.imfit.domain.repository.ExerciseRepository
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
    val isSaving: Boolean = false,
    val isAdding: Boolean = false,
    val hasLoaded: Boolean = false,
    val invalidConfigIds: Set<String> = emptySet(),
    @StringRes val nameErrorRes: Int? = null,
    @StringRes val errorRes: Int? = null,
    val sourceRevision: Int = 0
)

@HiltViewModel
class EditWorkoutViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository,
    private val exerciseRepository: ExerciseRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private var workoutId: String? = savedStateHandle["workoutId"]
    private var initialized = false
    private var observedSource: com.diajarkoding.imfit.domain.model.WorkoutTemplate? = null
    private var observeJob: kotlinx.coroutines.Job? = null
    private var pendingExerciseIds: List<String> = emptyList()

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
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = !it.hasLoaded, errorRes = null) }
            try {
                workoutRepository.observeTemplate(workoutId).collect { workout ->
                val draft = _state.value
                val source = observedSource
                val dirty = source != null && (draft.workoutName != source.name || draft.exercises != source.exercises ||
                    draft.invalidConfigIds.isNotEmpty() || draft.isAdding || draft.isSaving)
                if (dirty) return@collect
                if (workout != null) {
                    observedSource = workout
                    _state.update {
                        it.copy(
                            workoutName = workout.name,
                            exercises = workout.exercises,
                            isLoading = false, hasLoaded = true, sourceRevision = it.sourceRevision + 1
                        )
                    }
                } else _state.update { it.copy(errorRes = R.string.error_routine_not_found, isLoading = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("EditWorkoutVM", "Routine load failed", e)
                _state.update { it.copy(errorRes = R.string.error_routine_load, isLoading = false) }
            }
        }
    }

    fun updateWorkoutName(name: String) {
        if (_state.value.isLoading || _state.value.isSaving) return
        _state.update { it.copy(workoutName = name, nameErrorRes = null) }
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
        if (_state.value.isLoading || _state.value.isSaving) return
        _state.update { state ->
            val updatedExercises = state.exercises.toMutableList()
            if (exerciseIndex in updatedExercises.indices) {
                updatedExercises.removeAt(exerciseIndex)
            }
            state.copy(exercises = updatedExercises, invalidConfigIds = state.invalidConfigIds.intersect(updatedExercises.map { it.id }.toSet()))
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
        if (_state.value.isLoading || _state.value.isSaving || _state.value.isAdding || _state.value.isSaved || !_state.value.hasLoaded) return
        if (_state.value.workoutName.isBlank()) {
            _state.update { it.copy(nameErrorRes = R.string.error_template_name_required) }
            return
        }
        if (_state.value.invalidConfigIds.isNotEmpty()) return
        val workoutId = workoutId ?: return
        val draft = _state.value
        _state.update { it.copy(isSaving = true, errorRes = null) }
        viewModelScope.launch {
            try {
                val saved = workoutRepository.updateTemplate(
                    templateId = workoutId,
                    name = draft.workoutName.trim(),
                    exercises = draft.exercises
                )
                if (saved == null) {
                    _state.update { it.copy(errorRes = R.string.error_routine_not_found, isSaving = false) }
                } else {
                    _state.update { it.copy(isSaved = true, isSaving = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e("EditWorkoutVM", "Routine save failed", e)
                _state.update { it.copy(errorRes = R.string.error_routine_save, isSaving = false) }
            }
        }
    }

    fun updateExerciseConfig(id: String, sets: Int, reps: Int, rest: Int) {
        if (_state.value.isLoading || _state.value.isSaving || sets < 1 || reps < 1 || rest < 0) return
        _state.update { state -> state.copy(exercises = state.exercises.map {
            if (it.id == id) it.copy(sets = sets, reps = reps, restSeconds = rest) else it
        }) }
    }

    fun setConfigValidity(id: String, valid: Boolean) {
        _state.update { it.copy(invalidConfigIds = if (valid) it.invalidConfigIds - id else it.invalidConfigIds + id) }
    }

    fun addExercisesByIds(ids: List<String>) {
        if (_state.value.isAdding || _state.value.isSaving) return
        pendingExerciseIds = ids.distinct()
        _state.update { it.copy(isAdding = true, errorRes = null) }
        viewModelScope.launch {
            try {
                val additions = pendingExerciseIds.map { id -> TemplateExercise(exerciseRepository.getExerciseById(id) ?: error("Exercise not found")) }
                _state.update { state -> state.copy(exercises = (state.exercises + additions).distinctBy { it.id }, isAdding = false) }
                pendingExerciseIds = emptyList()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(isAdding = false, errorRes = R.string.routine_add_failed) } }
        }
    }

    fun retryAddExercises() { addExercisesByIds(pendingExerciseIds) }
}
