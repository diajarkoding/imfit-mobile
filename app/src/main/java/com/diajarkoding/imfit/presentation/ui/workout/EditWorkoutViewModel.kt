package com.diajarkoding.imfit.presentation.ui.workout

import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.*
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.ExerciseCatalogRepository
import com.diajarkoding.imfit.domain.repository.ExerciseHydrationResult
import com.diajarkoding.imfit.domain.repository.RoutineError
import com.diajarkoding.imfit.domain.repository.RoutineRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

data class RawExerciseConfig(val sets: String, val reps: String, val rest: String) {
    val isValid: Boolean get() = sets.toIntOrNull() in 1..20 &&
        reps.toIntOrNull() in 1..100 && rest.toIntOrNull() in 0..600
}

data class EditWorkoutState(
    val workoutName: String = "",
    val exercises: List<TemplateExercise> = emptyList(),
    val rawConfigs: Map<String, RawExerciseConfig> = emptyMap(),
    val mode: RoutineFormMode = RoutineFormMode.EDIT,
    val flowId: String? = null,
    val baseRevision: Long? = null,
    val isLoading: Boolean = false,
    val isSaved: Boolean = false,
    val isSaving: Boolean = false,
    val hasPendingSave: Boolean = false,
    val hasLoaded: Boolean = false,
    val isDirty: Boolean = false,
    val invalidConfigIds: Set<String> = emptySet(),
    @StringRes val nameErrorRes: Int? = null,
    @StringRes val errorRes: Int? = null,
    val errorMessage: String? = null,
    val saveError: RoutineError? = null,
    val showErrorDialog: Boolean = false,
    val accountInvalid: Boolean = false
)

@Serializable
private data class DraftRow(val id: String, val sets: String, val reps: String, val rest: String)

@Serializable
private data class CommandExercise(
    val id: String, val name: String, val category: String,
    val active: Boolean, val sets: Int, val reps: Int, val rest: Int
) {
    fun template() = TemplateExercise(
        Exercise(id, name, MuscleCategory.valueOf(category), "", null, active),
        sets = sets, reps = reps, restSeconds = rest
    )
}

@Serializable
private data class SaveCommand(
    val requestId: String, val name: String, val exercises: List<CommandExercise>,
    val revision: Long?, val baselineIds: List<String>
)

@Serializable
private data class DurableRoutineDraft(
    val routineId: String, val ownerId: String, val mode: String, val flowId: String,
    val name: String = "", val rows: List<DraftRow> = emptyList(),
    val revision: Long? = null, val baselineIds: List<String> = emptyList(),
    val loaded: Boolean = false, val dirty: Boolean = false,
    val acceptedResults: Set<String> = emptySet(), val command: SaveCommand? = null,
    val committed: Boolean = false, val successConsumed: Boolean = false
)

@HiltViewModel
class EditWorkoutViewModel(
    private val routineRepository: RoutineRepository,
    private val exerciseCatalogRepository: ExerciseCatalogRepository,
    private val authRepository: AuthRepository,
    private val savedStateHandle: SavedStateHandle,
    private val overrideScope: CoroutineScope? = null
) : ViewModel() {
    @Inject constructor(
        routineRepository: RoutineRepository,
        exerciseCatalogRepository: ExerciseCatalogRepository,
        authRepository: AuthRepository,
        savedStateHandle: SavedStateHandle
    ) : this(routineRepository, exerciseCatalogRepository, authRepository, savedStateHandle, null)

    private val scope get() = overrideScope ?: viewModelScope
    private var durable = savedStateHandle.get<String>(DRAFT_KEY)?.let {
        Json.decodeFromString<DurableRoutineDraft>(it)
    }
    private var initialized = false
    private var loadJob: Job? = null
    private var accountJob: Job? = null
    private val _state = MutableStateFlow(EditWorkoutState())
    val state = _state.asStateFlow()

    fun initialize(workoutId: String, mode: RoutineFormMode, flowId: String, ownerId: String, initialName: String = "") {
        if (initialized) return
        initialized = true
        val restored = durable
        if (restored != null && (restored.routineId != workoutId || restored.ownerId != ownerId ||
                restored.flowId != flowId || restored.mode != mode.name)) {
            invalidateAccount()
            return
        }
        durable = restored ?: DurableRoutineDraft(workoutId, ownerId, mode.name, flowId, name = initialName)
        persist()
        _state.value = EditWorkoutState(workoutName = durable?.name.orEmpty(), mode = mode, flowId = flowId)
        accountJob = scope.launch {
            authRepository.observeCurrentUser().collect {
                if (authRepository.getLocalUserId() != durable?.ownerId) invalidateAccount()
            }
        }
        loadWorkout()
    }

    private fun persist() {
        durable?.let { savedStateHandle[DRAFT_KEY] = Json.encodeToString(it) }
    }

    private fun invalidateAccount() {
        loadJob?.cancel()
        durable = null
        savedStateHandle.remove<String>(DRAFT_KEY)
        _state.value = EditWorkoutState(accountInvalid = true, errorRes = R.string.error_routine_load)
    }

    private suspend fun ownsDraft(): Boolean {
        val owner = durable?.ownerId ?: return false
        if (authRepository.getLocalUserId() == owner) return true
        invalidateAccount()
        return false
    }

    fun loadWorkout() {
        val draft = durable ?: return
        if (_state.value.isSaving || (_state.value.hasLoaded && draft.dirty)) return
        loadJob?.cancel()
        loadJob = scope.launch {
            if (!ownsDraft()) return@launch
            if (draft.committed) {
                _state.value = _state.value.copy(isSaved = !draft.successConsumed, hasLoaded = true)
                return@launch
            }
            _state.value = _state.value.copy(isLoading = true, errorRes = null, errorMessage = null)
            try {
                if (draft.command != null) {
                    // Resolving an unknown commit must not depend on a fresh catalogue read.
                    publishDraft(draft.command.exercises.map { it.template().exercise })
                } else if (draft.loaded) {
                    val exercises = hydrate(draft.rows.map { it.id })
                    if (!ownsDraft()) return@launch
                    publishDraft(exercises)
                } else {
                    val routine = routineRepository.getRoutineById(draft.routineId).getOrThrow()
                        ?: throw RoutineError.NotFound()
                    if (!ownsDraft()) return@launch
                    if (routine.userId != draft.ownerId) { invalidateAccount(); return@launch }
                    durable = draft.copy(name = routine.name, rows = routine.exercises.map { it.row() },
                        revision = routine.revision, baselineIds = routine.exercises.map { it.id }, loaded = true)
                    persist()
                    publishDraft(routine.exercises.map { it.exercise })
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (ownsDraft()) _state.value = _state.value.copy(isLoading = false,
                    errorRes = if (e is RoutineError.NotFound) R.string.error_routine_not_found else R.string.error_routine_load,
                    errorMessage = e.message)
            }
        }
    }

    private suspend fun hydrate(ids: List<String>): List<Exercise> {
        if (ids.isEmpty()) return emptyList()
        val found = when (val result = exerciseCatalogRepository.getExercisesByIds(ids)) {
            is ExerciseHydrationResult.Success -> result.exercises
            is ExerciseHydrationResult.Missing -> throw RoutineError.MissingExercises(result.missingIds)
            is ExerciseHydrationResult.Error -> throw result.cause
        }.associateBy { it.id }
        val missing = ids.filterNot(found::containsKey)
        if (missing.isNotEmpty()) throw RoutineError.MissingExercises(missing)
        return ids.map { found.getValue(it) }
    }

    private fun publishDraft(exercises: List<Exercise>) {
        val draft = durable ?: return
        val byId = exercises.associateBy { it.id }
        val templates = draft.rows.map { row ->
            TemplateExercise(byId.getValue(row.id), sets = row.sets.toIntOrNull() ?: 0,
                reps = row.reps.toIntOrNull() ?: 0, restSeconds = row.rest.toIntOrNull() ?: 0)
        }
        val raw = draft.rows.associate { it.id to RawExerciseConfig(it.sets, it.reps, it.rest) }
        _state.value = _state.value.copy(workoutName = draft.name, exercises = templates, rawConfigs = raw,
            baseRevision = draft.revision, hasLoaded = true, isLoading = false, isDirty = draft.dirty,
            hasPendingSave = draft.command != null,
            invalidConfigIds = templates.filter { !raw.getValue(it.id).isValid || !it.exercise.supportsRoutinePrescription() }.map { it.id }.toSet(),
            errorRes = null)
    }

    private fun canEdit() = _state.value.let {
        it.hasLoaded && !it.isLoading && !it.isSaving && !it.hasPendingSave &&
            !it.isSaved && !it.accountInvalid && durable?.committed == false
    }

    private fun mutate(change: (DurableRoutineDraft) -> DurableRoutineDraft) {
        if (!canEdit()) return
        durable = change(durable ?: return).copy(dirty = true)
        persist()
        publishDraft(_state.value.exercises.map { it.exercise })
    }

    fun updateWorkoutName(name: String) {
        mutate { it.copy(name = name) }
        _state.value = _state.value.copy(nameErrorRes = null)
    }

    fun updateRawConfig(id: String, sets: String, reps: String, rest: String) {
        if (_state.value.exercises.find { it.id == id }?.exercise?.supportsRoutinePrescription() != true) return
        mutate { it.copy(rows = it.rows.map { row -> if (row.id == id) DraftRow(id, sets, reps, rest) else row }) }
    }

    fun removeExercise(index: Int) = mutate { draft ->
        draft.copy(rows = draft.rows.filterIndexed { rowIndex, _ -> rowIndex != index })
    }

    fun moveExerciseUp(index: Int) = moveExercise(index, index - 1)
    fun moveExerciseDown(index: Int) = moveExercise(index, index + 1)
    private fun moveExercise(from: Int, to: Int) = mutate { draft ->
        val rows = draft.rows.toMutableList()
        if (from in rows.indices && to in rows.indices) rows.add(to, rows.removeAt(from))
        draft.copy(rows = rows)
    }

    /** Snapshots are accepted and persisted before the navigation holder may acknowledge. */
    fun acceptSelection(result: RoutineSelectionResult): Boolean {
        val draft = durable ?: return false
        val context = result.context
        if (context.ownerId != draft.ownerId || context.routineId != draft.routineId ||
            context.flowId != draft.flowId || context.mode.name != draft.mode) return false
        if (result.resultId in draft.acceptedResults) return true
        if (!canEdit()) return false
        val existing = draft.rows.map { it.id }.toSet()
        val additions = result.selectedExercises.distinctBy { it.id }.filterNot { it.id in existing }
        durable = draft.copy(rows = draft.rows + additions.map {
            if (it.supportsRoutinePrescription()) DraftRow(it.id, "3", "10", "60") else DraftRow(it.id, "", "", "")
        }, dirty = draft.dirty || additions.isNotEmpty(), acceptedResults = draft.acceptedResults + result.resultId)
        persist()
        publishDraft(_state.value.exercises.map { it.exercise } + additions)
        return true
    }


    fun saveChanges() {
        val draft = durable ?: return
        val state = _state.value
        if (!state.hasLoaded || state.isLoading || state.isSaving || draft.committed || state.accountInvalid) return
        if (draft.command == null && (draft.name.isBlank() || state.invalidConfigIds.isNotEmpty())) {
            if (draft.name.isBlank()) _state.value = state.copy(nameErrorRes = R.string.error_template_name_required)
            return
        }
        val command = draft.command ?: SaveCommand(UUID.randomUUID().toString(), draft.name.trim(),
            state.exercises.map { row -> CommandExercise(row.id, row.name, row.exercise.muscleCategory.name,
                row.exercise.isActive, row.sets, row.reps, row.restSeconds) },
            draft.revision, draft.baselineIds)
        durable = draft.copy(command = command)
        persist() // Save the immutable command BEFORE dispatch, including its request identity.
        _state.value = state.copy(isSaving = true, hasPendingSave = false, errorRes = null, showErrorDialog = false)
        scope.launch {
            if (!ownsDraft()) return@launch
            try {
                val result = routineRepository.saveRoutine(draft.routineId, command.name,
                    command.exercises.map { it.template() }, command.revision, command.requestId, command.baselineIds)
                if (!ownsDraft()) return@launch
                val saved = result.getOrThrow()
                if (saved.userId != draft.ownerId) { invalidateAccount(); return@launch }
                durable = durable?.copy(command = null, committed = true, revision = saved.revision)
                persist() // Acknowledged commit is independent of any later Detail read.
                _state.value = _state.value.copy(isSaved = true, isSaving = false, hasPendingSave = false, isDirty = false)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (!ownsDraft()) return@launch
                val typed = e as? RoutineError ?: RoutineError.Network(e.message ?: "Save outcome unknown", e)
                val unknown = typed is RoutineError.Network || typed is RoutineError.Server
                if (!unknown) { durable = durable?.copy(command = null); persist() }
                val messageRes = when (typed) {
                    is RoutineError.RevisionConflict -> R.string.routine_revision_conflict
                    is RoutineError.NotFound -> R.string.error_routine_not_found
                    is RoutineError.Unauthorized -> R.string.routine_auth_required
                    is RoutineError.ActiveSessionConflict -> R.string.routine_mutation_active
                    else -> R.string.error_routine_save
                }
                _state.value = _state.value.copy(isSaving = false, hasPendingSave = unknown,
                    saveError = typed, errorRes = messageRes, errorMessage = typed.message, showErrorDialog = true)
            }
        }
    }

    fun consumeSaved() {
        durable = durable?.copy(successConsumed = true)
        persist()
        _state.value = _state.value.copy(isSaved = false)
    }

    fun dismissErrorDialog() { _state.value = _state.value.copy(showErrorDialog = false) }

    private fun TemplateExercise.row() = DraftRow(id, sets.toString(), reps.toString(), restSeconds.toString())
    private fun Exercise.supportsRoutinePrescription() = isActive && prescriptionCapability == ExercisePrescriptionCapability.REPS_LOAD

    companion object { const val DRAFT_KEY = "routine_editor_durable_draft" }
}
