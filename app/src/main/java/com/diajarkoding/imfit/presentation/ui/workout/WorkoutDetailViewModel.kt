package com.diajarkoding.imfit.presentation.ui.workout

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.RoutineError
import com.diajarkoding.imfit.domain.repository.RoutineRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class WorkoutDetailState(
    val workout: WorkoutTemplate? = null,
    val isLoading: Boolean = true,
    @StringRes val errorRes: Int? = null,
    @StringRes val deleteErrorRes: Int? = null,
    val isDeleting: Boolean = false,
    val isWorkoutActive: Boolean = false,
    val isCurrentWorkoutActive: Boolean = false,
    val isDeleted: Boolean = false,
    val hasPendingDelete: Boolean = false
)

/** Persist the entire immutable command, not only its request identity. */
internal data class RoutineDeleteCommand(
    val ownerId: String,
    val routineId: String,
    val revision: Long,
    val name: String,
    val exerciseIds: List<String>,
    val requestId: String
) {
    suspend fun execute(repository: RoutineRepository): Result<Boolean> =
        repository.deleteRoutine(routineId, revision, requestId,
            baselineExerciseIds = exerciseIds, name = name)

    fun persist(handle: SavedStateHandle) {
        handle[KEY] = arrayListOf(ownerId, routineId, revision.toString(), name, requestId).apply { addAll(exerciseIds) }
    }

    companion object {
        private const val KEY = "routineDeleteCommand"
        fun restore(handle: SavedStateHandle): RoutineDeleteCommand? {
            val values = handle.get<ArrayList<String>>(KEY) ?: return null
            if (values.size < 5) return null
            return RoutineDeleteCommand(values[0], values[1], values[2].toLongOrNull() ?: return null,
                values[3], values.drop(5), values[4])
        }
        fun clear(handle: SavedStateHandle) { handle.remove<ArrayList<String>>(KEY) }
    }
}

@HiltViewModel
class WorkoutDetailViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository,
    private val routineRepository: RoutineRepository,
    private val authRepository: AuthRepository,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    private var workoutId: String? = savedStateHandle["workoutId"]
    private var ownerId: String? = null
    private var readJob: Job? = null
    private var readGeneration = 0L
    private val _state = MutableStateFlow(WorkoutDetailState(
        hasPendingDelete = RoutineDeleteCommand.restore(savedStateHandle) != null))
    val state = _state.asStateFlow()

    fun initialize(workoutId: String) {
        if (workoutId.isBlank()) return
        this.workoutId = workoutId
        savedStateHandle["workoutId"] = workoutId
        loadWorkout()
    }

    fun loadWorkout() {
        val id = workoutId ?: return
        val generation = ++readGeneration
        readJob?.cancel()
        readJob = viewModelScope.launch {
            val owner = authRepository.getLocalUserId()
            val pendingDelete = RoutineDeleteCommand.restore(savedStateHandle)
            if (pendingDelete != null && (pendingDelete.ownerId != owner || pendingDelete.routineId != id)) {
                RoutineDeleteCommand.clear(savedStateHandle)
                _state.update { it.copy(hasPendingDelete = false) }
            }
            if (owner == null) {
                _state.value = WorkoutDetailState(isLoading = false, errorRes = R.string.routine_auth_required)
                return@launch
            }
            if (ownerId != null && ownerId != owner) {
                _state.value = WorkoutDetailState()
                RoutineDeleteCommand.clear(savedStateHandle)
            }
            ownerId = owner
            _state.update { it.copy(isLoading = it.workout == null, errorRes = null) }
            try {
                val result = routineRepository.getRoutineById(id)
                if (!isCurrentRequest(owner, generation)) return@launch
                val workout = result.getOrThrow() ?: throw RoutineError.NotFound()
                if (workout.userId != owner) throw RoutineError.Unauthorized()
                val active = workoutRepository.getActiveSessionForTimer()
                if (!isCurrentRequest(owner, generation)) return@launch
                _state.update { current ->
                    current.copy(workout = if ((current.workout?.revision ?: -1) > workout.revision) current.workout else workout,
                        isLoading = false, isWorkoutActive = active != null,
                        isCurrentWorkoutActive = active?.templateId == id, errorRes = null)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (!isCurrentRequest(owner, generation)) return@launch
                _state.update { it.copy(isLoading = false, errorRes = errorResource(e, R.string.routine_read_failed)) }
            }
        }
    }

    fun deleteWorkout() {
        if (_state.value.isDeleting || _state.value.isDeleted) return
        val owner = ownerId ?: return
        val pending = RoutineDeleteCommand.restore(savedStateHandle)?.takeIf {
            it.ownerId == owner && it.routineId == workoutId
        }
        val routine = _state.value.workout
        if (pending == null && routine == null) return
        if (_state.value.isWorkoutActive) {
            _state.update { it.copy(deleteErrorRes = R.string.routine_mutation_active) }
            return
        }
        val command = pending ?: requireNotNull(routine).let {
            RoutineDeleteCommand(owner, it.id, it.revision, it.name,
                it.exercises.map { exercise -> exercise.id }, UUID.randomUUID().toString())
                .also { command -> command.persist(savedStateHandle) }
        }
        _state.update { it.copy(isDeleting = true, deleteErrorRes = null, hasPendingDelete = true) }
        viewModelScope.launch {
            try {
                if (authRepository.getLocalUserId() != command.ownerId) throw RoutineError.Unauthorized()
                if (workoutRepository.getActiveSessionForTimer() != null) throw RoutineError.ActiveSessionConflict()
                if (authRepository.getLocalUserId() != command.ownerId) throw RoutineError.Unauthorized()
                val result = command.execute(routineRepository)
                if (authRepository.getLocalUserId() != command.ownerId) throw RoutineError.Unauthorized()
                if (!result.getOrThrow()) throw RoutineError.ValidationError("Delete was not acknowledged")
                ++readGeneration
                readJob?.cancel()
                RoutineDeleteCommand.clear(savedStateHandle)
                _state.update { it.copy(isDeleted = true, isDeleting = false, hasPendingDelete = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (authRepository.getLocalUserId() != command.ownerId) {
                    RoutineDeleteCommand.clear(savedStateHandle)
                    _state.value = WorkoutDetailState(isLoading = false, errorRes = R.string.routine_auth_required)
                    return@launch
                }
                // Transport/unknown outcomes retain exactly this command for an idempotent replay.
                if (e is RoutineError && e !is RoutineError.Network && e !is RoutineError.Server) {
                    RoutineDeleteCommand.clear(savedStateHandle)
                }
                _state.update { it.copy(isDeleting = false, deleteErrorRes = errorResource(e, R.string.routine_delete_unknown),
                    hasPendingDelete = RoutineDeleteCommand.restore(savedStateHandle) != null) }
            }
        }
    }

    fun clearDeleteError() { _state.update { it.copy(deleteErrorRes = null) } }

    val deleteRoutineName: String
        get() = RoutineDeleteCommand.restore(savedStateHandle)?.name ?: _state.value.workout?.name.orEmpty()

    private suspend fun isCurrentRequest(owner: String, generation: Long): Boolean {
        if (generation != readGeneration) return false
        if (authRepository.getLocalUserId() == owner) return true
        RoutineDeleteCommand.clear(savedStateHandle)
        _state.value = WorkoutDetailState(isLoading = false, errorRes = R.string.routine_auth_required)
        return false
    }

    private fun errorResource(error: Throwable, @StringRes fallback: Int): Int = when (error) {
        is RoutineError.NotFound -> R.string.error_routine_not_found
        is RoutineError.RevisionConflict -> R.string.routine_revision_conflict
        is RoutineError.Unauthorized -> R.string.routine_auth_required
        is RoutineError.ActiveSessionConflict -> R.string.routine_mutation_active
        else -> fallback
    }

    val estimatedDuration: Int get() = (_state.value.workout?.exerciseCount ?: 0) * 8
}
