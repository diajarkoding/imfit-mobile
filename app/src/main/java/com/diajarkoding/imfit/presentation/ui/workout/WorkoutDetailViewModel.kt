package com.diajarkoding.imfit.presentation.ui.workout

import androidx.lifecycle.SavedStateHandle
import androidx.annotation.StringRes
import com.diajarkoding.imfit.R
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.ExerciseRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

data class WorkoutDetailState(
    val workout: WorkoutTemplate? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    @StringRes val errorRes: Int? = null,
    @StringRes val operationErrorRes: Int? = null,
    @StringRes val deleteErrorRes: Int? = null,
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
    private val exerciseRepository: ExerciseRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    private var workoutId: String? = savedStateHandle["workoutId"]
    private var initialized = false
    private var observeJob: Job? = null
    private var pendingExerciseIds: List<String> = emptyList()
    
    private val _state = MutableStateFlow(WorkoutDetailState())
    val state = _state.asStateFlow()

    fun initialize(workoutId: String) {
        if (workoutId.isBlank()) return
        if (initialized && this.workoutId == workoutId) {
            viewModelScope.launch {
                val active = workoutRepository.getActiveSession()
                _state.update { it.copy(isWorkoutActive = active != null, isCurrentWorkoutActive = active?.templateId == workoutId) }
            }
            return
        }

        this.workoutId = workoutId
        savedStateHandle["workoutId"] = workoutId
        initialized = true
        loadWorkout()
    }

    fun loadWorkout() {
        val workoutId = workoutId ?: return
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = it.workout == null, errorRes = null) }
            try {
                workoutRepository.observeTemplate(workoutId).collect { workout ->
                val activeSession = workoutRepository.getActiveSession()
                val isCurrentActive = activeSession?.templateId == workoutId
                val hasAnyActiveSession = activeSession != null
                _state.update { it.copy(
                    workout = workout, 
                    isLoading = false, 
                    isWorkoutActive = hasAnyActiveSession,
                    isCurrentWorkoutActive = isCurrentActive,
                    errorRes = if (workout == null && !it.isDeleted && !it.isDeleting) R.string.error_routine_not_found else null
                ) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorRes = R.string.error_routine_load, isLoading = false) }
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
        val workoutId = workoutId ?: return
        if (_state.value.isDeleting || _state.value.isDeleted || _state.value.isWorkoutActive) return
        _state.update { it.copy(isDeleting = true, deleteErrorRes = null) }
        viewModelScope.launch {
            try {
                if (workoutRepository.getActiveSession() != null) {
                    _state.update { it.copy(isDeleting = false, deleteErrorRes = R.string.warning_workout_active) }
                    return@launch
                }
                check(workoutRepository.deleteTemplate(workoutId)) { "Routine was not deleted" }
                _state.update { it.copy(isDeleted = true, isDeleting = false) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(isDeleting = false, deleteErrorRes = R.string.routine_delete_failed) } }
        }
    }

    fun clearDeleteError() { _state.update { it.copy(deleteErrorRes = null) } }

    fun addExercises(exercises: List<Exercise>) {
        addExercisesByIds(exercises.map { it.id })
    }

    fun addExercisesByIds(exerciseIds: List<String>) {
        val id = workoutId ?: return
        if (_state.value.isUpdating || _state.value.isDeleting || exerciseIds.isEmpty()) return
        pendingExerciseIds = exerciseIds.distinct()
        _state.update { it.copy(isUpdating = true, operationErrorRes = null) }
        viewModelScope.launch {
            try {
                if (workoutRepository.getActiveSession() != null) {
                    _state.update { it.copy(operationErrorRes = R.string.warning_workout_active) }
                    return@launch
                }
                val current = workoutRepository.getTemplateById(id) ?: error("Routine not found")
                val existing = current.exercises.map { it.id }.toSet()
                val additions = pendingExerciseIds.filterNot { it in existing }.map { exerciseId ->
                    TemplateExercise(exerciseRepository.getExerciseById(exerciseId) ?: error("Exercise not found"))
                }
                if (additions.isNotEmpty()) checkNotNull(workoutRepository.updateTemplateExercises(id, current.exercises + additions))
                pendingExerciseIds = emptyList()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(operationErrorRes = R.string.routine_add_failed) } }
            finally { _state.update { it.copy(isUpdating = false) } }
        }
    }

    fun retryAddExercises() { addExercisesByIds(pendingExerciseIds) }

    fun removeExercise(templateExercise: TemplateExercise) {
        val workoutId = workoutId ?: return
        viewModelScope.launch {
            val currentWorkout = workoutRepository.getTemplateById(workoutId) ?: return@launch
            val updatedExercises = currentWorkout.exercises.filter { it.id != templateExercise.id }
            
            workoutRepository.updateTemplateExercises(workoutId, updatedExercises)
            loadWorkout()
        }
    }

    fun updateExerciseConfig(exerciseId: String, sets: Int, reps: Int, restSeconds: Int) {
        val workoutId = workoutId ?: return
        viewModelScope.launch {
            workoutRepository.updateTemplateExercise(workoutId, exerciseId, sets, reps, restSeconds)
            loadWorkout()
        }
    }

    val estimatedDuration: Int
        get() = (_state.value.workout?.exerciseCount ?: 0) * 8
}
