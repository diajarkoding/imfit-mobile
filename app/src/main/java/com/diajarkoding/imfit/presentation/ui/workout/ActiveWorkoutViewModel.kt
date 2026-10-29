package com.diajarkoding.imfit.presentation.ui.workout

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.core.model.WorkoutTimerUpdate
import com.diajarkoding.imfit.core.network.NetworkMonitor
import com.diajarkoding.imfit.core.timer.*
import com.diajarkoding.imfit.data.remote.online.*
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.repository.AuthRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull

enum class SessionEntryPhase { LOADING, BLOCKED, UNKNOWN, CONFIRMED }

data class ActiveWorkoutState(
    val session: WorkoutSession? = null,
    val confirmed: SessionSnapshot? = null,
    val identity: AuthIdentity? = null,
    val drafts: Map<SessionSetKey, SessionSetDraft> = emptyMap(),
    val savePhase: SessionSavePhase = SessionSavePhase.RELOADING,
    val entryPhase: SessionEntryPhase = SessionEntryPhase.LOADING,
    val requiresVerification: Boolean = true,
    val onlineHint: Boolean? = null,
    val pendingRequestId: String? = null,
    val reviewRecentOperations: Boolean = false,
    val recentOperations: List<OperationReceipt> = emptyList(),
    val recentHasMore: Boolean = false,
    val recentLoading: Boolean = false,
    val canRetryExerciseAddition: Boolean = false,
    val elapsedSeconds: Long = 0,
    val isRestTimerActive: Boolean = false,
    val restTimerSeconds: Int = 60,
    val restTimerExerciseName: String? = null,
    val showCancelDialog: Boolean = false,
    val workoutLogId: String? = null,
    val sessionRestOverride: Int? = null,
    val isPaused: Boolean = false,
    @StringRes val pauseErrorRes: Int? = null,
    val isFinishing: Boolean = false,
    val restLimited: Boolean = false,
    val timerError: Boolean = false,
    val restSoundEnabled: Boolean = true,
    val restVibrationEnabled: Boolean = false,
    val sessionEnded: Boolean = false,
    val fullScreenAlarmAllowed: Boolean = true,
    val isCancelling: Boolean = false,
    val isAddingExercises: Boolean = false,
    @StringRes val mutationErrorRes: Int? = null
) {
    val actionsEnabled: Boolean get() = entryPhase == SessionEntryPhase.CONFIRMED && !requiresVerification &&
        onlineHint != false && (savePhase == SessionSavePhase.CONFIRMED || savePhase == SessionSavePhase.DIRTY) &&
        !isFinishing && !isCancelling && !isAddingExercises && confirmed?.reviewRequired == false &&
        (confirmed?.effectiveStatus == SessionStatus.ACTIVE || confirmed?.effectiveStatus == SessionStatus.PAUSED)
    val inputEnabled: Boolean get() = !requiresVerification && onlineHint != false && !isFinishing && !isCancelling &&
        !isAddingExercises && confirmed?.reviewRequired == false &&
        (confirmed?.effectiveStatus == SessionStatus.ACTIVE || confirmed?.effectiveStatus == SessionStatus.PAUSED) &&
        (savePhase == SessionSavePhase.CONFIRMED || savePhase == SessionSavePhase.DIRTY || savePhase == SessionSavePhase.SAVING)
    val unconfirmed: Boolean get() = drafts.isNotEmpty() || pendingRequestId != null || requiresVerification || savePhase != SessionSavePhase.CONFIRMED
}

@HiltViewModel
class ActiveWorkoutViewModel @Inject constructor(
    private val sessions: OnlineSessionStore,
    private val authRepository: AuthRepository,
    private val timer: RestTimerCoordinator,
    private val timerRuntime: RestTimerAndroidRuntime,
    private val remoteReads: RemoteReadGateway,
    network: NetworkMonitor
) : ViewModel() {
    private val _state = MutableStateFlow(ActiveWorkoutState())
    val state = _state.asStateFlow()
    private val _workoutTimerUpdates = MutableSharedFlow<WorkoutTimerUpdate>(replay = 1)
    val workoutTimerUpdates = _workoutTimerUpdates.asSharedFlow()
    val restState = timer.state
    private var entryJob: Job? = null
    private var entered = false
    private var template: String? = null
    private var target: String? = null
    private var allowCreate = true
    private var recentJob: Job? = null
    private var recentFrom: String? = null
    private var recentUntil: String? = null
    private var recentCursor: String? = null
    private data class PendingRest(val identity: AuthIdentity, val sessionId: String, val key: SessionSetKey)
    private var pendingRest: PendingRest? = null
    private var selectedAddition: Pair<String, List<String>>? = null
    private var consumeAddition: (() -> Unit)? = null

    init {
        _state.update { it.copy(restSoundEnabled = timerRuntime.soundEnabled(), restVibrationEnabled = timerRuntime.vibrationEnabled(),
            fullScreenAlarmAllowed = timerRuntime.fullScreenIntentAvailable()) }
        viewModelScope.launch {
            sessions.state.collect { current ->
                val terminal = current.snapshot?.takeIf { it.effectiveStatus == SessionStatus.COMPLETED || it.effectiveStatus == SessionStatus.CANCELLED }
                if (terminal != null && _state.value.confirmed?.version != terminal.version) {
                    try { timer.cancel("SESSION_TERMINAL") }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { _state.update { it.copy(timerError = true) } }
                }
                _state.update { old ->
                    val changedOwner = old.identity != null && old.identity != current.identity
                    val base = if (changedOwner) ActiveWorkoutState(onlineHint = old.onlineHint) else old
                    if (changedOwner) {
                        entryJob?.cancel()
                        recentJob?.cancel()
                        entered = false
                        recentCursor = null
                        recentFrom = null
                        recentUntil = null
                        pendingRest = null
                        selectedAddition = null
                        consumeAddition = null
                    }
                    val snapshot = current.snapshot
                    base.copy(identity = current.identity, confirmed = snapshot,
                        session = if (snapshot === base.confirmed) base.session else snapshot?.toDomain(),
                        drafts = current.drafts, savePhase = current.savePhase, requiresVerification = current.requiresVerification,
                        pendingRequestId = current.pendingRequestId,
                        mutationErrorRes = if (current.failure is RemoteError.Validation) R.string.error_session_save else base.mutationErrorRes,
                        isPaused = snapshot?.effectiveStatus == SessionStatus.PAUSED,
                        entryPhase = when {
                            current.recovery != null -> SessionEntryPhase.BLOCKED
                            current.loaded && snapshot == null && allowCreate && !entered -> SessionEntryPhase.UNKNOWN
                            current.loaded && !current.requiresVerification -> SessionEntryPhase.CONFIRMED
                            current.busy -> SessionEntryPhase.LOADING
                            else -> SessionEntryPhase.UNKNOWN
                        },
                        workoutLogId = snapshot?.completedWorkoutId ?: base.workoutLogId,
                        sessionEnded = base.sessionEnded || snapshot?.effectiveStatus == SessionStatus.CANCELLED)
                }
            }
        }
        viewModelScope.launch {
            var wasOffline = false
            network.networkStatus.collect { online ->
                sessions.updateConnectivity(online)
                _state.update { it.copy(onlineHint = online) }
                if (online && wasOffline && template != null) reload()
                wasOffline = !online
            }
        }
        viewModelScope.launch {
            timer.state.collect { rest -> _state.update { it.copy(
                isRestTimerActive = rest?.status == TimerStatus.RUNNING,
                restTimerSeconds = rest?.remainingSeconds(android.os.SystemClock.elapsedRealtime()) ?: 0,
                restTimerExerciseName = rest?.exerciseName) } }
        }
        viewModelScope.launch {
            while (true) {
                // Pure memory projection. A cold/unknown store is never treated as authoritative absence.
                val session = _state.value.session
                if (session != null) {
                    val elapsed = (session.actualElapsedMs / 1000).coerceAtLeast(0)
                    val rest = timer.state.value?.takeIf { it.workoutId == session.id }
                    _state.update { it.copy(elapsedSeconds = elapsed, timerError = timer.error.value != null,
                        isRestTimerActive = rest?.status == TimerStatus.RUNNING,
                        restTimerSeconds = rest?.remainingSeconds(android.os.SystemClock.elapsedRealtime()) ?: 0,
                        restTimerExerciseName = rest?.exerciseName,
                        restLimited = timerRuntime.capabilityIssue() != null) }
                    _workoutTimerUpdates.emit(WorkoutTimerUpdate(session.templateName, elapsed, session.totalCompletedSets,
                        session.totalSets, session.totalVolume, session.isPaused))
                }
                delay(1000)
            }
        }
    }

    fun startWorkout(templateId: String, expectedSessionId: String? = null, allowCreate: Boolean = true) {
        if (entered || entryJob?.isActive == true) return
        template = templateId
        target = expectedSessionId
        this.allowCreate = allowCreate
        entryJob = viewModelScope.launch {
            _state.update { it.copy(entryPhase = SessionEntryPhase.LOADING) }
            try {
                val identity = remoteReads.auth.capture().identity
                val existing = sessions.reload(expectedSessionId)
                if (existing == null && expectedSessionId != null) {
                    // Targeted authoritative absence may close the route; never start the template again.
                    _state.update { it.copy(sessionEnded = true) }
                } else if (existing == null && allowCreate) {
                    val revision = remoteReads.template(templateId).first.revision
                    if (!remoteReads.auth.isCurrent(identity)) throw StaleAuthException()
                    sessions.start(templateId, revision)
                } else if (existing == null) {
                    _state.update { it.copy(reviewRecentOperations = true, entryPhase = SessionEntryPhase.UNKNOWN) }
                }
                entered = true
                timer.reconcile(source = "ui_entry")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: LegacyStartBlocked) { _state.update { it.copy(entryPhase = SessionEntryPhase.BLOCKED) } }
            catch (_: Exception) { _state.update { it.copy(entryPhase = SessionEntryPhase.UNKNOWN, requiresVerification = true) } }
        }
    }

    fun reload() {
        if (entryJob?.isActive == true) return
        entryJob = viewModelScope.launch {
            try {
                val found = sessions.reload(target ?: _state.value.confirmed?.id)
                entered = found != null || !allowCreate || target != null
                if (found == null && target != null) _state.update { it.copy(sessionEnded = true) }
                if (found == null && !allowCreate) _state.update { it.copy(reviewRecentOperations = true) }
                timer.reconcile(source = "ui_reload")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(entryPhase = SessionEntryPhase.UNKNOWN, requiresVerification = true) } }
        }
    }
    fun retryEntry() { if (!entered) template?.let { startWorkout(it, target, allowCreate) } else reload() }
    fun resolvePending() = action(R.string.error_session_save) {
        sessions.resolvePending()?.let { restoreConfirmedRest() }
    }
    fun retryPending() = action(R.string.error_session_save) {
        sessions.retry()
        restoreConfirmedRest()
    }
    fun useServer() = action(R.string.error_session_save) { sessions.discardDrafts(requireSessionId()) }
    fun reapplyDrafts() = action(R.string.error_session_save) { sessions.reapplyDrafts(requireSessionId()) }
    fun acknowledgeExpiry() = action(R.string.error_session_save) { sessions.acknowledgeExpiry(requireSessionId()) }
    fun loadRecentOperations() {
        if (recentJob?.isActive == true) return
        recentJob = viewModelScope.launch {
            val identity = remoteReads.auth.capture().identity
            _state.update { it.copy(recentLoading = true) }
            try {
                if (recentUntil == null) {
                    val now = Instant.now()
                    recentUntil = now.toString()
                    recentFrom = now.minus(90, ChronoUnit.DAYS).toString()
                }
                val page = remoteReads.readFor(identity, OnlineRpc.recentOperations(requireNotNull(recentFrom),
                    requireNotNull(recentUntil), recentCursor, 20))
                if (!remoteReads.auth.isCurrent(identity)) return@launch
                recentCursor = page.pageInfo.nextCursor
                _state.update { it.copy(recentOperations = (it.recentOperations + page.data).distinctBy { receipt -> receipt.requestId },
                    recentHasMore = page.pageInfo.hasMore) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (remoteReads.auth.isCurrent(identity)) _state.update { it.copy(mutationErrorRes = R.string.error_session_save) } }
            finally { if (remoteReads.auth.isCurrent(identity)) _state.update { it.copy(recentLoading = false) } }
        }
    }
    fun reviewOperation(receipt: OperationReceipt) = action(R.string.error_session_save) {
        check(_state.value.recentOperations.any { it.requestId == receipt.requestId })
        if (receipt.resourceType == "SESSION") {
            target = receipt.resourceId
            sessions.reload(receipt.resourceId)
        } else if (receipt.resourceType == "WORKOUT") {
            val id = receipt.result["workout_id"]?.jsonPrimitive?.contentOrNull ?: error("Missing workout receipt")
            _state.update { it.copy(workoutLogId = id) }
        }
    }
    private fun requireSessionId() = requireNotNull(_state.value.confirmed?.id)
    private fun action(@StringRes error: Int, block: suspend (AuthIdentity) -> Unit) {
        val identity = sessions.state.value.identity ?: return
        viewModelScope.launch {
            try {
                if (!remoteReads.auth.isCurrent(identity)) return@launch
                block(identity)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (remoteReads.auth.isCurrent(identity)) _state.update { it.copy(mutationErrorRes = error) } }
        }
    }
    fun updateSet(key: SessionSetKey, weight: String, reps: String) {
        if (!_state.value.inputEnabled) return
        try { sessions.editSet(requireSessionId(), key, weight, reps) }
        catch (_: Exception) { _state.update { it.copy(mutationErrorRes = R.string.error_session_save) } }
    }
    fun completeSet(key: SessionSetKey) = action(R.string.error_set_complete) { identity ->
        val id = requireSessionId()
        pendingRest = PendingRest(identity, id, key)
        sessions.command(id, SessionCommand.CompleteSet(key.exerciseLogId, key.setId))
        restoreConfirmedRest()
    }
    private suspend fun restoreConfirmedRest() {
        val request = pendingRest ?: return
        val confirmed = sessions.state.value.snapshot ?: return
        if (!remoteReads.auth.isCurrent(request.identity) || confirmed.id != request.sessionId) return
        val exercise = confirmed.exercises.singleOrNull { it.id == request.key.exerciseLogId } ?: run { pendingRest = null; return }
        val set = exercise.sets.singleOrNull { it.id == request.key.setId }
        if (set?.isCompleted != true) { pendingRest = null; return }
        if (exercise.restSeconds > 0 && confirmed.effectiveStatus == SessionStatus.ACTIVE && !confirmed.reviewRequired) {
            val userId = authRepository.getLocalUserId() ?: return
            if (!remoteReads.auth.isCurrent(request.identity)) return
            timer.start(RestTimerState(timerId = "", userId = userId, workoutId = request.sessionId, templateId = confirmed.templateId.orEmpty(),
                exerciseId = exercise.exerciseId, setId = set.id, exerciseName = exercise.name,
                durationMs = exercise.restSeconds * 1000L, deadlineElapsedMs = 0, bootIdentity = null, startedAtEpochMs = 0))
        }
        pendingRest = null
    }
    fun addSet(exerciseLogId: String) = command(SessionCommand.AddSet(exerciseLogId, UUID.randomUUID().toString()))
    fun removeSet(key: SessionSetKey) = command(SessionCommand.RemoveSet(key.exerciseLogId, key.setId))
    fun removeExercise(exerciseLogId: String) = command(SessionCommand.RemoveExercise(exerciseLogId))
    fun updateRestTimer(exerciseLogId: String, seconds: Int) = command(SessionCommand.SetRestSeconds(exerciseLogId, seconds))
    private fun command(command: SessionCommand) = action(R.string.error_session_save) { sessions.command(requireSessionId(), command) }
    fun addExercisesByIds(sessionId: String, exerciseIds: List<String>, onConsumed: () -> Unit) {
        if (!_state.value.actionsEnabled) return
        val identity = sessions.state.value.identity ?: return
        selectedAddition = sessionId to exerciseIds.distinct()
        consumeAddition = onConsumed
        _state.update { it.copy(isAddingExercises = true, canRetryExerciseAddition = false) }
        viewModelScope.launch {
            try {
                for (id in exerciseIds.distinct()) {
                    if (!remoteReads.auth.isCurrent(identity)) return@launch
                    val current = sessions.state.value.snapshot ?: error("Missing confirmed session")
                    check(current.id == sessionId)
                    if (current.exercises.any { it.exerciseId == id }) continue
                    sessions.command(sessionId, SessionCommand.AddExercise(UUID.randomUUID().toString(), id))
                }
                if (remoteReads.auth.isCurrent(identity)) {
                    selectedAddition = null
                    consumeAddition = null
                    onConsumed()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (remoteReads.auth.isCurrent(identity)) _state.update { it.copy(mutationErrorRes = R.string.error_exercise_add, canRetryExerciseAddition = true) } }
            finally { if (remoteReads.auth.isCurrent(identity)) _state.update { it.copy(isAddingExercises = false) } }
        }
    }
    fun retryExerciseAddition() {
        val selection = selectedAddition ?: return
        addExercisesByIds(selection.first, selection.second, consumeAddition ?: return)
    }
    fun setSessionRestOverride(seconds: Int) = action(R.string.error_session_save) { identity ->
        require(seconds in 0..600)
        val snapshot = sessions.state.value.snapshot ?: error("Missing confirmed session")
        // Publish a common override only after every receipt; partial failure remains visible per exercise.
        _state.update { it.copy(sessionRestOverride = null) }
        for (exercise in snapshot.exercises) {
            if (!remoteReads.auth.isCurrent(identity)) return@action
            if (exercise.restSeconds != seconds) sessions.command(snapshot.id, SessionCommand.SetRestSeconds(exercise.id, seconds))
        }
        if (remoteReads.auth.isCurrent(identity)) _state.update { it.copy(sessionRestOverride = seconds) }
    }
    fun getCurrentRestTime() = _state.value.confirmed?.exercises?.map { it.restSeconds }?.distinct()?.singleOrNull() ?: 60
    fun pauseWorkout() = action(R.string.error_rest_pause) { identity ->
        if (!timer.whenRestInactive { sessions.command(requireSessionId(), SessionCommand.Pause) })
            if (remoteReads.auth.isCurrent(identity)) _state.update { it.copy(pauseErrorRes = R.string.error_rest_pause) }
    }
    fun resumeWorkout() = command(SessionCommand.Resume)
    fun cancelWorkout(): Boolean {
        val id = _state.value.confirmed?.id ?: return false
        if (_state.value.isFinishing || _state.value.isCancelling) return false
        val identity = sessions.state.value.identity ?: return false
        _state.update { it.copy(isCancelling = true, showCancelDialog = false) }
        viewModelScope.launch {
            try {
                if (!remoteReads.auth.isCurrent(identity)) return@launch
                sessions.cancel(id)
                if (!remoteReads.auth.isCurrent(identity)) return@launch
                timer.cancel("SESSION_CANCELLED")
                _state.update { it.copy(sessionEnded = true) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (remoteReads.auth.isCurrent(identity)) _state.update { it.copy(mutationErrorRes = R.string.error_session_cancel) } }
            finally { if (remoteReads.auth.isCurrent(identity)) _state.update { it.copy(isCancelling = false) } }
        }
        return true
    }
    fun finishWorkout() {
        if (!_state.value.actionsEnabled) return
        val id = _state.value.confirmed?.id ?: return
        val identity = sessions.state.value.identity ?: return
        _state.update { it.copy(isFinishing = true) }
        viewModelScope.launch {
            try {
                if (!remoteReads.auth.isCurrent(identity)) return@launch
                val receipt = sessions.finish(id)
                if (!remoteReads.auth.isCurrent(identity)) return@launch
                val workoutId = requireNotNull(receipt.workoutId)
                timer.cancel("SESSION_FINISHED")
                _state.update { it.copy(workoutLogId = workoutId) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (remoteReads.auth.isCurrent(identity)) _state.update { it.copy(mutationErrorRes = R.string.error_session_finish) } }
            finally { if (remoteReads.auth.isCurrent(identity)) _state.update { it.copy(isFinishing = false) } }
        }
    }
    fun showCancelDialog() { _state.update { it.copy(showCancelDialog = true) } }
    fun dismissCancelDialog() { _state.update { it.copy(showCancelDialog = false) } }
    fun clearMutationError() { _state.update { it.copy(mutationErrorRes = null) } }
    fun clearPauseError() { _state.update { it.copy(pauseErrorRes = null) } }
    fun skipRestTimer() = action(R.string.rest_storage_error) { timer.skip() }
    fun extendRest() = action(R.string.rest_storage_error) { timer.extend() }
    fun reduceRest() = action(R.string.rest_storage_error) { timer.reduce() }
    fun refreshTimerCapabilities() {
        _state.update { it.copy(fullScreenAlarmAllowed = timerRuntime.fullScreenIntentAvailable(), restLimited = timerRuntime.capabilityIssue() != null) }
        action(R.string.rest_storage_error) { timer.capabilitiesChanged(source = "ui_resume") }
    }
    fun timerSettingsIntent() = timerRuntime.capabilitySettingsIntent()
    fun setRestSoundEnabled(enabled: Boolean) { timerRuntime.setSoundEnabled(enabled); _state.update { it.copy(restSoundEnabled = enabled) } }
    fun setRestVibrationEnabled(enabled: Boolean) { timerRuntime.setVibrationEnabled(enabled); _state.update { it.copy(restVibrationEnabled = enabled) } }
}
