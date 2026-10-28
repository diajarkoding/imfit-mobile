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
import kotlinx.coroutines.ensureActive
import javax.inject.Inject
import com.diajarkoding.imfit.data.remote.online.*

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
    val isDeleted: Boolean = false,
    val refreshing: Boolean = false,
    val stale: Boolean = false
)

@HiltViewModel
class WorkoutDetailViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository,
    private val savedStateHandle: SavedStateHandle,
    private val reads: RemoteReadGateway,
    private val edits: TemplateEditBridge
) : ViewModel() {

    private var workoutId: String? = savedStateHandle["workoutId"]
    private var initialized = false
    private var observeJob: Job? = null
    private var pendingExerciseIds: List<String> = emptyList()
    private var source: TemplateDetail? = null
    
    private val _state = MutableStateFlow(WorkoutDetailState())
    val state = _state.asStateFlow()
    private val authListener = reads.auth.onIdentityChanged { observeJob?.cancel(); source = null; _state.value = WorkoutDetailState() }
    init {
        viewModelScope.launch { reads.auth.identity.collect { identity ->
            if (identity != null && initialized && _state.value.workout == null && observeJob?.isActive != true) loadWorkout()
        } }
        viewModelScope.launch { edits.changes.collect { change ->
            if (change.templateId == workoutId && reads.auth.isCurrent(change.identity)) loadWorkout()
        } }
    }

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
        source = null
        _state.value = WorkoutDetailState()
        loadWorkout()
    }

    fun loadWorkout() {
        val workoutId = workoutId ?: return
        observeJob?.cancel()
        observeJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = it.workout == null, refreshing = true, errorRes = null) }
            try {
                val identity = reads.auth.capture().identity
                val (snapshot, workout) = reads.template(workoutId)
                val activeSession = workoutRepository.getActiveSession()
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (!reads.auth.isCurrent(identity)) throw StaleAuthException()
                source = snapshot
                val isCurrentActive = activeSession?.templateId == workoutId
                val hasAnyActiveSession = activeSession != null
                _state.update { it.copy(
                    workout = workout, 
                    isLoading = false, 
                    isWorkoutActive = hasAnyActiveSession,
                    isCurrentWorkoutActive = isCurrentActive,
                    errorRes = null, refreshing = false, stale = false
                ) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(errorRes = R.string.error_routine_load, isLoading = false, refreshing = false, stale = it.workout != null) }
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
                val snapshot = source ?: error("Reload routine before deleting")
                val current = _state.value.workout ?: error("Routine unavailable")
                edits.save(snapshot, current.name, current.exercises, deleted = true)
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
                val current = _state.value.workout ?: error("Routine not found")
                val snapshot = source ?: error("Reload routine first")
                val existing = current.exercises.map { it.id }.toSet()
                val additions = pendingExerciseIds.filterNot { it in existing }.map { exerciseId ->
                    TemplateExercise(reads.exercises(listOf(exerciseId)).singleOrNull() ?: error("Exercise not found"))
                }
                if (additions.isNotEmpty()) edits.save(snapshot, current.name, current.exercises + additions)
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
            val currentWorkout = _state.value.workout ?: return@launch
            val updatedExercises = currentWorkout.exercises.filter { it.id != templateExercise.id }
            
            edits.save(source ?: error("Reload first"), currentWorkout.name, updatedExercises)
            loadWorkout()
        }
    }

    fun updateExerciseConfig(exerciseId: String, sets: Int, reps: Int, restSeconds: Int) {
        val workoutId = workoutId ?: return
        viewModelScope.launch {
            val current = _state.value.workout ?: return@launch
            edits.save(source ?: error("Reload first"), current.name, current.exercises.map {
                if (it.id == exerciseId) it.copy(sets = sets, reps = reps, restSeconds = restSeconds) else it
            })
            loadWorkout()
        }
    }

    val estimatedDuration: Int
        get() = (_state.value.workout?.exerciseCount ?: 0) * 8
    override fun onCleared() { authListener.close() }
}
