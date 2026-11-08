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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject

@Serializable
data class RawSetTargetDraft(
    @SerialName("id") val rowId: String = UUID.randomUUID().toString(),
    @SerialName("w") val weight: String = "",
    @SerialName("r") val reps: String = "",
    @SerialName("d") val duration: String = "",
    @SerialName("rest") val rest: String = "60"
) {
    fun parsedWeightKg(): Double? =
        weight.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isTwoDecimalIn(0.0, 1000.0) }

    fun parsedReps(): Int? =
        reps.trim().toIntOrNull()?.takeIf { it in 1..100 }

    fun parsedDurationSeconds(): Int? =
        parseRoutineDurationSeconds(duration.trim())

    fun parsedRestSeconds(): Int? =
        rest.trim().toIntOrNull()?.takeIf { it in 0..600 }

    fun isWeightValid(trackingType: TrackingType): Boolean = when (trackingType) {
        TrackingType.DURATION, TrackingType.DISTANCE_DURATION -> true
        else -> parsedWeightKg() != null
    }

    fun isRepsValid(trackingType: TrackingType): Boolean = when (trackingType) {
        TrackingType.DURATION, TrackingType.DISTANCE_DURATION -> true
        else -> parsedReps() != null
    }

    fun isDurationValid(trackingType: TrackingType): Boolean = when (trackingType) {
        TrackingType.DURATION, TrackingType.DISTANCE_DURATION -> parsedDurationSeconds() != null
        else -> true
    }

    val isRestValid: Boolean get() = parsedRestSeconds() != null

    fun isValidFor(trackingType: TrackingType): Boolean =
        rowId.isNotBlank() &&
            isWeightValid(trackingType) &&
            isRepsValid(trackingType) &&
            isDurationValid(trackingType) &&
            isRestValid

    fun toSetTarget(setNumber: Int, trackingType: TrackingType): SetTarget {
        val durationBased = trackingType == TrackingType.DURATION || trackingType == TrackingType.DISTANCE_DURATION
        return SetTarget(
            rowId = rowId,
            setNumber = setNumber,
            weightKg = if (durationBased) null else parsedWeightKg(),
            reps = if (durationBased) null else parsedReps(),
            durationSeconds = if (durationBased) parsedDurationSeconds() else null,
            restSeconds = parsedRestSeconds() ?: 60
        )
    }
}

data class RawExerciseConfig(
    val trackingType: TrackingType = TrackingType.WEIGHT_REPS,
    val setRows: List<RawSetTargetDraft> = emptyList()
) {
    val sets: String get() = setRows.size.toString()
    val reps: String get() = setRows.firstOrNull()?.reps.orEmpty()
    val durationSeconds: String get() = setRows.firstOrNull()?.duration.orEmpty()

    val isValid: Boolean
        get() = setRows.size in 1..MAX_ROUTINE_SET_ROWS &&
            setRows.map { it.rowId }.distinct().size == setRows.size &&
            setRows.all { it.isValidFor(trackingType) }
}

internal const val MAX_ROUTINE_SET_ROWS = 20

internal fun Exercise.v3TrackingType(): TrackingType = when (modality) {
    ExerciseModality.CARDIO, ExerciseModality.ISOMETRIC -> TrackingType.DURATION
    ExerciseModality.STRENGTH, ExerciseModality.CALISTHENICS -> TrackingType.WEIGHT_REPS
    ExerciseModality.CONDITIONING ->
        if (defaultTrackingType == TrackingType.DURATION || defaultTrackingType == TrackingType.DISTANCE_DURATION) {
            TrackingType.DURATION
        } else {
            TrackingType.WEIGHT_REPS
        }
}

internal fun Exercise.defaultInitialRowCount(): Int = when (modality) {
    ExerciseModality.CARDIO -> when (trackingProfile) {
        TrackingProfile.DISTANCE_ACTIVITY, TrackingProfile.EQUIPMENT_METRICS -> 1
        TrackingProfile.DURATION_ONLY -> 3
        null -> if (defaultTrackingType == TrackingType.DISTANCE_DURATION || TrackingType.DISTANCE_DURATION in allowedTrackingTypes) 1 else 3
    }
    ExerciseModality.STRENGTH,
    ExerciseModality.CALISTHENICS,
    ExerciseModality.ISOMETRIC,
    ExerciseModality.CONDITIONING -> 3
}

internal fun Exercise.toV3Exercise(): Exercise {
    if (validTrackingMetadata(modality, defaultTrackingType, trackingProfile, supportedMetrics)) return this
    val normalizedModality = if (modality == ExerciseModality.CALISTHENICS) ExerciseModality.STRENGTH else modality
    val normalizedType = v3TrackingType()
    val normalizedProfile = when (normalizedModality) {
        ExerciseModality.CARDIO -> trackingProfile ?: if (
            defaultTrackingType == TrackingType.DISTANCE_DURATION || TrackingType.DISTANCE_DURATION in allowedTrackingTypes
        ) TrackingProfile.DISTANCE_ACTIVITY else TrackingProfile.DURATION_ONLY
        else -> null
    }
    val normalizedMetrics = when (normalizedModality) {
        ExerciseModality.CARDIO -> when (normalizedProfile) {
            TrackingProfile.DURATION_ONLY -> emptyList()
            TrackingProfile.DISTANCE_ACTIVITY -> supportedMetrics.filter {
                it == CardioMetric.DISTANCE || it == CardioMetric.SPEED || it == CardioMetric.LAPS
            }
            TrackingProfile.EQUIPMENT_METRICS -> supportedMetrics
            null -> emptyList()
        }
        else -> emptyList()
    }
    val normalizedAllowed = (listOf(normalizedType) + allowedTrackingTypes).distinct()
    return copy(
        modality = normalizedModality,
        defaultTrackingType = normalizedType,
        allowedTrackingTypes = normalizedAllowed,
        trackingProfile = normalizedProfile,
        supportedMetrics = normalizedMetrics
    )
}

internal fun defaultSetTargetDraft(trackingType: TrackingType): RawSetTargetDraft =
    if (trackingType == TrackingType.DURATION || trackingType == TrackingType.DISTANCE_DURATION) {
        RawSetTargetDraft(
            rowId = UUID.randomUUID().toString(),
            weight = "",
            reps = "",
            duration = "00:01:00",
            rest = "60"
        )
    } else {
        RawSetTargetDraft(
            rowId = UUID.randomUUID().toString(),
            weight = "0",
            reps = "10",
            duration = "",
            rest = "60"
        )
    }

internal fun formatWeightInput(weight: Double): String {
    val asLong = weight.toLong()
    return if (weight == asLong.toDouble()) asLong.toString()
    else weight.toBigDecimal().stripTrailingZeros().toPlainString()
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
private data class DraftRow(
    val id: String,
    @SerialName("t") val trackingType: String? = null,
    @SerialName("r") val setRows: List<RawSetTargetDraft> = emptyList()
)

@Serializable
private data class CommandExercise(
    val id: String, val name: String, val category: String,
    val active: Boolean = true, val sets: Int = 3, val reps: Int? = null,
    val trackingType: String? = null,
    val targetDurationSeconds: Int? = null,
    val targetDistanceMeters: Int? = null,
    @SerialName("rest") val legacyRest: Int? = null,
    val modality: ExerciseModality? = null,
    val defaultTrackingType: TrackingType? = null,
    val allowedTrackingTypes: List<TrackingType>? = null,
    val trackingProfile: TrackingProfile? = null,
    val supportedMetrics: List<CardioMetric> = emptyList(),
    @SerialName("targets") val setTargets: List<SetTarget>? = null,
    @SerialName("mv") val measurementVersion: Int? = null
) {
    fun template(): TemplateExercise {
        val parsedTracking = trackingType?.let(TrackingType::valueOf) ?: TrackingType.WEIGHT_REPS
        val defTracking = defaultTrackingType ?: parsedTracking
        val allowed = allowedTrackingTypes ?: listOf(defTracking)
        val rawExercise = Exercise(
            id = id,
            name = name,
            muscleCategory = MuscleCategory.valueOf(category),
            description = "",
            imageUrl = null,
            isActive = active,
            defaultTrackingType = defTracking,
            modality = modality ?: ExerciseModality.STRENGTH,
            allowedTrackingTypes = if (defTracking in allowed) allowed else listOf(defTracking) + allowed,
            trackingProfile = trackingProfile,
            supportedMetrics = supportedMetrics
        )
        val exercise = if (measurementVersion == 3) rawExercise.toV3Exercise() else rawExercise
        val effectiveTracking = if (measurementVersion == 3) exercise.defaultTrackingType else parsedTracking
        return TemplateExercise(
            exercise = exercise,
            sets = if (measurementVersion == 3) setTargets?.size ?: sets else sets,
            reps = if (measurementVersion == 3) setTargets?.firstOrNull()?.reps else reps,
            restSeconds = if (measurementVersion == 3) setTargets?.firstOrNull()?.restSeconds ?: 60 else (legacyRest ?: 60),
            trackingType = effectiveTracking,
            targetDurationSeconds = if (measurementVersion == 3) setTargets?.firstOrNull()?.durationSeconds else targetDurationSeconds,
            targetDistanceMeters = if (measurementVersion == 3) null else targetDistanceMeters,
            legacyRestSeconds = if (measurementVersion == 3) null else legacyRest,
            setTargets = setTargets,
            measurementVersion = measurementVersion
        )
    }
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
    private val draftJson = Json { ignoreUnknownKeys = true }
    private var durable = savedStateHandle.get<String>(DRAFT_KEY)?.let {
        draftJson.decodeFromString<DurableRoutineDraft>(it)
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
        durable = restored ?: DurableRoutineDraft(workoutId, ownerId, mode.name, flowId, name = initialName, loaded = (mode == RoutineFormMode.SETUP))
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
        durable?.let { savedStateHandle[DRAFT_KEY] = draftJson.encodeToString(it) }
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
                    if (draft.dirty) {
                        // A dirty restored draft already owns its baseline (name, revision,
                        // membership). Re-reading the routine would silently rebase the
                        // user's unpersisted edits across process recreation.
                        publishDraft(exercises)
                    } else {
                        val routine = routineRepository.getRoutineById(draft.routineId).getOrNull()
                        val routineName = if (draft.name.isBlank()) routine?.name ?: draft.name else draft.name
                        val revision = draft.revision ?: routine?.revision
                        if (routineName != draft.name || revision != draft.revision) {
                            durable = draft.copy(name = routineName, revision = revision)
                            persist()
                        }
                        publishDraft(exercises)
                    }
                } else {
                    val routine = routineRepository.getRoutineById(draft.routineId).getOrThrow()
                        ?: throw RoutineError.NotFound()
                    if (!ownsDraft()) return@launch
                    if (routine.userId != draft.ownerId) { invalidateAccount(); return@launch }
                    durable = draft.copy(
                        name = routine.name,
                        rows = routine.exercises.map { it.toDraftRow(draft.routineId) },
                        revision = routine.revision,
                        baselineIds = routine.exercises.map { it.id },
                        loaded = true
                    )
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
        val raw = draft.rows.associate { row ->
            val ex = byId.getValue(row.id)
            val parsedTracking = ex.v3TrackingType()
            row.id to RawExerciseConfig(
                trackingType = parsedTracking,
                setRows = row.setRows
            )
        }
        val templates = draft.rows.map { row ->
            val ex = byId.getValue(row.id).toV3Exercise()
            val config = raw.getValue(row.id)
            val targets = config.setRows.mapIndexed { index, setRow ->
                setRow.toSetTarget(setNumber = index + 1, trackingType = config.trackingType)
            }
            TemplateExercise(
                exercise = ex,
                sets = targets.size,
                reps = targets.firstOrNull()?.reps,
                restSeconds = targets.firstOrNull()?.restSeconds ?: 60,
                trackingType = config.trackingType,
                targetDurationSeconds = targets.firstOrNull()?.durationSeconds,
                targetDistanceMeters = null,
                setTargets = targets,
                measurementVersion = 3
            )
        }
        _state.value = _state.value.copy(
            workoutName = draft.name,
            exercises = templates,
            rawConfigs = raw,
            baseRevision = draft.revision,
            hasLoaded = true,
            isLoading = false,
            isDirty = draft.dirty,
            hasPendingSave = draft.command != null,
            invalidConfigIds = templates.filter {
                val config = raw.getValue(it.id)
                !config.isValid || !it.exercise.supportsRoutinePrescription()
            }.map { it.id }.toSet(),
            errorRes = null
        )
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

    fun updateRawConfig(id: String, config: RawExerciseConfig) {
        val exercise = _state.value.exercises.find { it.id == id }?.exercise ?: return
        if (!exercise.supportsRoutinePrescription()) return
        val mode = exercise.v3TrackingType()
        mutate { draft ->
            draft.copy(rows = draft.rows.map { row ->
                if (row.id == id) DraftRow(id, mode.name, config.setRows) else row
            })
        }
    }

    fun addSetRow(exerciseId: String) {
        val exercise = _state.value.exercises.find { it.id == exerciseId }?.exercise ?: return
        if (!exercise.supportsRoutinePrescription()) return
        val mode = exercise.v3TrackingType()
        mutate { draft ->
            draft.copy(rows = draft.rows.map { row ->
                if (row.id != exerciseId || row.setRows.size >= MAX_ROUTINE_SET_ROWS) row
                else row.copy(
                    trackingType = mode.name,
                    setRows = row.setRows + defaultSetTargetDraft(mode)
                )
            })
        }
    }

    fun removeSetRow(exerciseId: String, rowId: String) {
        val exercise = _state.value.exercises.find { it.id == exerciseId }?.exercise ?: return
        if (!exercise.supportsRoutinePrescription()) return
        mutate { draft ->
            draft.copy(rows = draft.rows.map { row ->
                if (row.id != exerciseId) row
                else row.copy(setRows = row.setRows.filterNot { it.rowId == rowId })
            })
        }
    }

    fun updateSetRow(exerciseId: String, updated: RawSetTargetDraft) {
        updateSetRow(exerciseId, updated.rowId) { updated }
    }

    fun updateSetRow(
        exerciseId: String,
        rowId: String,
        transform: (RawSetTargetDraft) -> RawSetTargetDraft
    ) {
        val exercise = _state.value.exercises.find { it.id == exerciseId }?.exercise ?: return
        if (!exercise.supportsRoutinePrescription()) return
        mutate { draft ->
            draft.copy(rows = draft.rows.map { row ->
                if (row.id != exerciseId) row
                else row.copy(setRows = row.setRows.map { setRow ->
                    if (setRow.rowId == rowId) transform(setRow).copy(rowId = rowId) else setRow
                })
            })
        }
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
        durable = draft.copy(
            loaded = true,
            rows = draft.rows + additions.map { ex ->
                val mode = ex.v3TrackingType()
                if (ex.supportsRoutinePrescription()) {
                    val count = ex.defaultInitialRowCount()
                    DraftRow(
                        id = ex.id,
                        trackingType = mode.name,
                        setRows = List(count) { defaultSetTargetDraft(mode) }
                    )
                } else {
                    DraftRow(id = ex.id, trackingType = mode.name, setRows = emptyList())
                }
            },
            dirty = draft.dirty || additions.isNotEmpty(),
            acceptedResults = draft.acceptedResults + result.resultId
        )
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
        val command = draft.command ?: SaveCommand(
            requestId = UUID.randomUUID().toString(),
            name = draft.name.trim(),
            exercises = state.exercises.map { row ->
                val v3Ex = row.exercise.toV3Exercise()
                val targets = requireNotNull(row.setTargets)
                CommandExercise(
                    id = row.id,
                    name = row.name,
                    category = v3Ex.muscleCategory.name,
                    active = v3Ex.isActive,
                    sets = targets.size,
                    reps = targets.firstOrNull()?.reps,
                    trackingType = v3Ex.defaultTrackingType.name,
                    targetDurationSeconds = targets.firstOrNull()?.durationSeconds,
                    targetDistanceMeters = null,
                    modality = v3Ex.modality,
                    defaultTrackingType = v3Ex.defaultTrackingType,
                    allowedTrackingTypes = v3Ex.allowedTrackingTypes,
                    trackingProfile = v3Ex.trackingProfile,
                    supportedMetrics = v3Ex.supportedMetrics,
                    setTargets = targets,
                    measurementVersion = 3
                )
            },
            revision = draft.revision,
            baselineIds = draft.baselineIds
        )
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

    private fun TemplateExercise.toDraftRow(routineId: String): DraftRow {
        val mode = exercise.v3TrackingType()
        val durationBased = mode == TrackingType.DURATION || mode == TrackingType.DISTANCE_DURATION
        val targets = if (measurementVersion == 3 && !setTargets.isNullOrEmpty()) {
            setTargets
        } else {
            expandedLegacyTargets("$routineId:$id")
        }
        val rows = targets.map { target ->
            if (durationBased) {
                val formattedDuration = when {
                    target.durationSeconds != null -> formatDurationHhMmSs(target.durationSeconds)
                    exercise.modality == ExerciseModality.ISOMETRIC -> "00:01:00"
                    else -> ""
                }
                RawSetTargetDraft(
                    rowId = target.rowId,
                    weight = "",
                    reps = "",
                    duration = formattedDuration,
                    rest = target.restSeconds.toString()
                )
            } else {
                RawSetTargetDraft(
                    rowId = target.rowId,
                    weight = target.weightKg?.let(::formatWeightInput).orEmpty(),
                    reps = target.reps?.toString().orEmpty(),
                    duration = "",
                    rest = target.restSeconds.toString()
                )
            }
        }
        return DraftRow(
            id = id,
            trackingType = mode.name,
            setRows = rows
        )
    }
    private fun Exercise.supportsRoutinePrescription() = isActive

    companion object { const val DRAFT_KEY = "routine_editor_durable_draft" }
}
