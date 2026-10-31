package com.diajarkoding.imfit.presentation.ui.workout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.AuthSessionState
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.RoutineFlowContext
import com.diajarkoding.imfit.domain.model.RoutineFlowState
import com.diajarkoding.imfit.domain.model.RoutineFormMode
import com.diajarkoding.imfit.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** The navigation owner outlives picker entries; only compact draft handoff state is saved. */
@HiltViewModel
class RoutineFlowManager internal constructor(
    private val savedStateHandle: SavedStateHandle,
    private val authRepository: AuthRepository,
    overrideScope: CoroutineScope?
) : ViewModel() {
    @Inject
    constructor(savedStateHandle: SavedStateHandle, authRepository: AuthRepository) :
        this(savedStateHandle, authRepository, null)

    private val scope = overrideScope ?: viewModelScope
    private val restored = savedStateHandle.get<String>(STATE_KEY)?.let {
        runCatching { Json.decodeFromString<SavedFlows>(it) }.getOrNull()
    } ?: SavedFlows()
    private var ownerId: String? = restored.ownerId
    private val _flows = MutableStateFlow(restored.flows.associate { it.context.flowId to it.toState() })
    val flows = _flows.asStateFlow()
    private val _activeResult = MutableStateFlow(restored.activeResult)
    val activeResult = _activeResult.asStateFlow()
    private val _accountGeneration = MutableStateFlow(0L)
    val accountGeneration = _accountGeneration.asStateFlow()

    init {
        scope.launch {
            setOwner(authRepository.getLocalUserId())
            authRepository.userProfileState.collect { profile ->
                when (profile.sessionState) {
                    AuthSessionState.Initializing -> Unit
                    AuthSessionState.Unauthenticated -> setOwner(null)
                    else -> setOwner(authRepository.getLocalUserId())
                }
            }
        }
    }

    suspend fun startFlow(
        routineId: String,
        mode: RoutineFormMode,
        flowId: String = UUID.randomUUID().toString(),
        expectedOwnerId: String? = null
    ): RoutineFlowContext? {
        val currentOwner = currentOwnerId() ?: return null
        if (expectedOwnerId != null && currentOwner != expectedOwnerId) return null
        val context = RoutineFlowContext(flowId, routineId, currentOwner, mode)
        val existing = _flows.value[flowId]
        if (existing != null && existing.context != context) return null
        if (existing == null) {
            _flows.value = _flows.value + (flowId to RoutineFlowState(context))
            persist()
        }
        return context
    }

    suspend fun currentOwnerId(): String? {
        val current = authRepository.getLocalUserId()
        setOwner(current)
        return current
    }

    suspend fun isCurrent(context: RoutineFlowContext): Boolean =
        currentOwnerId() == context.ownerId && getFlow(context) != null

    fun getFlow(context: RoutineFlowContext): RoutineFlowState? =
        _flows.value[context.flowId]?.takeIf { it.context == context && ownerId == context.ownerId }

    suspend fun updateSelection(
        context: RoutineFlowContext,
        selectedExercises: List<Exercise>,
        resultId: String = UUID.randomUUID().toString()
    ): Boolean {
        if (!isCurrent(context)) return false
        val current = getFlow(context) ?: return false
        if (selectedExercises.isEmpty() || selectedExercises.map { it.id }.distinct().size != selectedExercises.size) return false
        _flows.value = _flows.value + (context.flowId to current.copy(
            selectedExercises = selectedExercises.toList(),
            selectionRevision = current.selectionRevision + 1,
            pendingResultId = resultId
        ))
        persist()
        return true
    }

    fun acknowledgeResult(context: RoutineFlowContext, resultId: String) {
        val current = getFlow(context) ?: return
        if (current.pendingResultId != resultId) return
        _flows.value = _flows.value + (context.flowId to current.copy(acceptedResultId = resultId))
        persist()
    }

    fun clearFlow(context: RoutineFlowContext) {
        if (getFlow(context) == null) return
        _flows.value = _flows.value - context.flowId
        persist()
    }

    suspend fun publishActiveResult(result: ActiveSelectionResult): Boolean {
        if (currentOwnerId() != result.ownerId) return false
        _activeResult.value = result
        persist()
        return true
    }

    fun acknowledgeActiveResult(resultId: String) {
        if (_activeResult.value?.resultId != resultId) return
        _activeResult.value = null
        persist()
    }

    fun clear() {
        _flows.value = emptyMap()
        _activeResult.value = null
        persist()
    }

    private fun setOwner(value: String?) {
        if (ownerId == value) return
        ownerId = value
        clear()
        _accountGeneration.value += 1
    }

    private fun persist() {
        savedStateHandle[STATE_KEY] = Json.encodeToString(SavedFlows(
            ownerId,
            _flows.value.values.map { SavedFlow.from(it) },
            _activeResult.value
        ))
    }

    companion object {
        internal const val STATE_KEY = "routine.navigation.flows"
    }
}

@Serializable
data class ActiveSelectionResult(
    val ownerId: String,
    val sessionId: String,
    val pickerId: String,
    val resultId: String,
    val selectedExerciseIds: List<String>
)

@Serializable
private data class SavedFlows(
    val ownerId: String? = null,
    val flows: List<SavedFlow> = emptyList(),
    val activeResult: ActiveSelectionResult? = null
)

@Serializable
private data class SavedFlow(
    val context: RoutineFlowContext,
    val exercises: List<SavedExercise>,
    val revision: Long,
    val pendingResultId: String?,
    val acceptedResultId: String?
) {
    fun toState() = RoutineFlowState(context, exercises.map { it.toExercise() }, revision, pendingResultId, acceptedResultId)

    companion object {
        fun from(state: RoutineFlowState) = SavedFlow(
            state.context, state.selectedExercises.map { SavedExercise.from(it) },
            state.selectionRevision, state.pendingResultId, state.acceptedResultId
        )
    }
}

@Serializable
private data class SavedExercise(
    val id: String,
    val name: String,
    val category: String,
    val description: String = "",
    val isActive: Boolean
) {
    fun toExercise() = Exercise(id, name, MuscleCategory.valueOf(category), description, null, isActive)

    companion object {
        fun from(exercise: Exercise) = SavedExercise(
            exercise.id, exercise.name, exercise.muscleCategory.name,
            exercise.description, exercise.isActive
        )
    }
}
