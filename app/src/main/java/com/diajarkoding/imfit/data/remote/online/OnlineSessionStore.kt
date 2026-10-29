package com.diajarkoding.imfit.data.remote.online

import com.diajarkoding.imfit.data.legacy.GuardedLegacyStart
import com.diajarkoding.imfit.data.legacy.LegacyGuardedSessionStart
import com.diajarkoding.imfit.data.legacy.LegacyStartReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** Raw input is deliberately memory-only and separate from the last server acknowledgement. */
data class SessionSetKey(val exerciseLogId: String, val setId: String)
data class SessionSetDraft(val weightText: String, val repsText: String, val editRevision: Long)
enum class SessionSavePhase { CONFIRMED, DIRTY, SAVING, OFFLINE_BLOCKED, UNKNOWN, RESOLVING, CONFLICT, RELOADING }

data class OnlineSessionState(
    val identity: AuthIdentity? = null,
    val loaded: Boolean = false,
    val snapshot: SessionSnapshot? = null,
    val busy: Boolean = false,
    val failure: RemoteError? = null,
    val recovery: LegacyStartReport? = null,
    val lastReceipt: SessionMutationReceipt? = null,
    val pendingRequestId: String? = null,
    val drafts: Map<SessionSetKey, SessionSetDraft> = emptyMap(),
    val savePhase: SessionSavePhase = SessionSavePhase.UNKNOWN,
    val connectivityHint: Boolean? = null,
    val requiresVerification: Boolean = true,
)

class LegacyStartBlocked(val report: LegacyStartReport) : Exception("Legacy recovery required")

/** Application-scoped serializer. Neither drafts nor the immutable unresolved call are persisted. */
class OnlineSessionStore(
    private val reads: RemoteReadGateway,
    private val repository: OnlineRepository,
    private val guardedStart: LegacyGuardedSessionStart,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val changes: OnlineMutationChanges? = null,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : AutoCloseable {
    init { require(reads.auth === repository.auth) }
    private val workerScope = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(OnlineSessionState())
    val state = mutable.asStateFlow()
    private data class Pending(
        val identity: AuthIdentity,
        val call: RpcCall<SessionMutationReceipt>,
        val revisions: Map<SessionSetKey, Long> = emptyMap(),
        val discardDrafts: Boolean = false,
        val registeredReadRevision: Long = -1,
    )
    private var pending: Pending? = null
    private var delayed: Job? = null
    private var editRevision = 0L
    private var draftSessionId: String? = null
    private var stagingFrozen = false
    private var conflictReloaded = false
    private var reloadRevision = 0L
    private var lastReloadTarget: String? = null
    private val subscription = reads.auth.onIdentityChanged { identity ->
        delayed?.cancel()
        delayed = null
        pending = null
        draftSessionId = null
        stagingFrozen = false
        conflictReloaded = false
        mutable.value = OnlineSessionState(identity = identity)
    }

    /** Hot timer path: no read/refresh/RPC. Cold unknown is never authoritative absence. */
    fun peek(): SessionSnapshot? {
        val identity = reads.auth.capture().identity
        val current = mutable.value
        if (current.identity != identity || !current.loaded) throw OnlineFailure(RemoteError.Network)
        if (!reads.auth.isCurrent(identity)) throw StaleAuthException()
        return current.snapshot?.takeIf {
            !it.reviewRequired && (it.effectiveStatus == SessionStatus.ACTIVE || it.effectiveStatus == SessionStatus.PAUSED)
        }
    }

    /** A connectivity signal is only a hint; reconnect requires a read, never an implicit write. */
    fun updateConnectivity(available: Boolean) {
        val identity = reads.auth.identity.value ?: return
        changeIfCurrent(identity) {
            if (!available) cancelDelay()
            phase(it.copy(connectivityHint = available,
                requiresVerification = it.requiresVerification || (available && it.connectivityHint == false)))
        }
    }

    fun editSet(sessionId: String, key: SessionSetKey, weightText: String, repsText: String) {
        val identity = reads.auth.capture().identity
        change(identity) { current ->
            if (stagingFrozen || current.requiresVerification || current.connectivityHint == false ||
                (current.failure != null && current.failure !is RemoteError.Validation) ||
                (pending != null && current.savePhase != SessionSavePhase.SAVING)) {
                throw OnlineFailure(RemoteError.UnresolvedMutation)
            }
            val snapshot = requireSession(current, sessionId)
            val set = snapshot.exercises.singleOrNull { it.id == key.exerciseLogId }?.sets?.singleOrNull { it.id == key.setId }
                ?: throw OnlineFailure(RemoteError.NotFound)
            require(!set.isCompleted) { "Completed sets cannot be edited" }
            require(draftSessionId == null || draftSessionId == sessionId)
            draftSessionId = sessionId
            val draft = SessionSetDraft(weightText, repsText, ++editRevision)
            val next = phase(current.copy(drafts = current.drafts + (key to draft), failure = null))
            schedule(identity, sessionId)
            next
        }
    }

    suspend fun reload(sessionId: String? = null): SessionSnapshot? {
        val identity = reads.auth.capture().identity
        val enteredAt = reads.auth.withCurrent(identity) { reloadRevision }
        return mutex.withLock {
            val coalesced = reads.auth.withCurrent(identity) { reloadRevision != enteredAt && lastReloadTarget == sessionId }
            if (coalesced) return@withLock confirmed(identity)
            reloadLocked(identity, sessionId)
        }
    }

    private suspend fun reloadLocked(identity: AuthIdentity, sessionId: String?): SessionSnapshot? {
        cancelDelay()
        change(identity) { it.copy(busy = true, savePhase = SessionSavePhase.RELOADING) }
        try {
            val found = reads.readFor(identity, OnlineRpc.activeSession(sessionId))
            change(identity) { current ->
                val old = current.snapshot
                val incoming = found.session
                val confirmed = if (incoming != null && old?.id == incoming.id && old.version > incoming.version) old else incoming
                val conflict = current.failure is RemoteError.Conflict && current.drafts.isNotEmpty()
                conflictReloaded = conflict
                reloadRevision++
                lastReloadTarget = sessionId
                phase(current.copy(loaded = true, snapshot = confirmed, busy = false,
                    requiresVerification = conflict, failure = if (conflict) current.failure else null))
            }
            return confirmed(identity)
        } catch (cancelled: CancellationException) {
            changeIfCurrent(identity) { phase(it.copy(busy = false)) }
            throw cancelled
        } catch (failure: Exception) {
            changeIfCurrent(identity) { phase(it.copy(busy = false, requiresVerification = true, failure = error(failure))) }
            throw failure
        }
    }

    suspend fun start(templateId: String, revision: Long): SessionSnapshot = mutex.withLock {
        val identity = reads.auth.capture().identity
        guardPending(identity)
        if (mutable.value.drafts.isNotEmpty()) throw OnlineFailure(RemoteError.UnresolvedMutation)
        val call = OnlineRpc.createSession(newId(), newId(), templateId, revision)
        change(identity) { it.copy(busy = true, failure = null, recovery = null, savePhase = SessionSavePhase.SAVING) }
        try {
            when (val result = guardedStart.create(call) { preserve(Pending(identity, call)) }) {
                is GuardedLegacyStart.Blocked -> {
                    change(identity) { phase(it.copy(busy = false, recovery = result.report, requiresVerification = true)) }
                    throw LegacyStartBlocked(result.report)
                }
                is GuardedLegacyStart.Created -> accept(identity, result.receipt)
            }
        } catch (cancelled: CancellationException) {
            failed(identity, cancelled)
            throw cancelled
        } catch (failure: Exception) {
            failed(identity, failure)
            throw failure
        }
    }

    suspend fun flushEdits(sessionId: String): SessionSnapshot = mutex.withLock {
        val identity = reads.auth.capture().identity
        cancelDelay()
        flushLocked(identity, sessionId)
    }

    /** Snapshot this batch once: receipt A must never acknowledge revision B typed in flight. */
    private suspend fun flushLocked(identity: AuthIdentity, sessionId: String): SessionSnapshot {
        var snapshot = editable(identity, sessionId)
        val batch = reads.auth.withCurrent(identity) { mutable.value.drafts.toMap() }
        if (batch.isEmpty()) return snapshot
        reads.auth.withCurrent(identity) { require(draftSessionId == sessionId) }
        val ordered = snapshot.exercises.sortedWith(compareBy<SessionExercise> { it.orderIndex }.thenBy { it.id })
            .flatMap { child -> child.sets.sortedWith(compareBy<WorkoutSet> { it.setNumber }.thenBy { it.id })
                .mapNotNull { set -> SessionSetKey(child.id, set.id).let { key -> batch[key]?.let { key to it } } } }
        if (ordered.size != batch.size) throw validation("SET_NOT_FOUND")
        try {
            // Validate every draft before dispatching any of this flush.
            val commands = ordered.map { (key, draft) -> key to parse(key, draft) }
            for ((key, command) in commands) {
                snapshot = editable(identity, sessionId)
                val draft = batch.getValue(key)
                send(Pending(identity, OnlineRpc.updateSession(newId(), sessionId, snapshot.version, command),
                    mapOf(key to draft.editRevision)))
            }
            return requireNotNull(confirmed(identity))
        } catch (failure: IllegalArgumentException) {
            val invalid = validation("INVALID_SET_INPUT")
            changeIfCurrent(identity) { phase(it.copy(failure = invalid.reason, busy = false)) }
            throw invalid
        }
    }

    private fun parse(key: SessionSetKey, draft: SessionSetDraft): SessionCommand.EditSet {
        val weight = draft.weightText.replace(',', '.')
        validateInputWeight(weight)
        // Parsing is decimal-only and exact; untouched legacy weights never pass through here.
        val canonicalWeight = weight.toBigDecimal().toPlainString()
        require(draft.repsText.isNotEmpty() && draft.repsText.all { it in '0'..'9' })
        val reps = draft.repsText.toIntOrNull() ?: throw IllegalArgumentException("Invalid reps")
        require(reps in 0..1000)
        return SessionCommand.EditSet(key.exerciseLogId, key.setId, canonicalWeight, reps)
    }

    suspend fun command(sessionId: String, command: SessionCommand): SessionSnapshot = mutex.withLock {
        val identity = reads.auth.capture().identity
        frozen(identity) {
            var snapshot = flushLocked(identity, sessionId)
            if (command is SessionCommand.CompleteSet) {
                val set = snapshot.exercises.singleOrNull { it.id == command.exerciseLogId }?.sets?.singleOrNull { it.id == command.setId }
                    ?: throw OnlineFailure(RemoteError.NotFound)
                if (set.weight.toBigDecimal().signum() <= 0 || set.reps <= 0) throw validation("POSITIVE_SET_REQUIRED")
            }
            snapshot = editable(identity, sessionId)
            send(Pending(identity, OnlineRpc.updateSession(newId(), sessionId, snapshot.version, command)))
            requireNotNull(confirmed(identity))
        }
    }

    suspend fun finish(sessionId: String): SessionMutationReceipt = terminal(sessionId, true)
    suspend fun cancel(sessionId: String): SessionMutationReceipt = terminal(sessionId, false)
    private suspend fun terminal(sessionId: String, finish: Boolean): SessionMutationReceipt = mutex.withLock {
        val identity = reads.auth.capture().identity
        guardPending(identity)
        mutable.value.lastReceipt?.takeIf {
            mutable.value.identity == identity && it.sessionId == sessionId &&
                it.operation == if (finish) "finish_active_session" else "cancel_active_session"
        }?.let { return@withLock it }
        frozen(identity) {
            val snapshot = if (finish) flushLocked(identity, sessionId) else editable(identity, sessionId)
            val call = if (finish) OnlineRpc.finishSession(newId(), sessionId, snapshot.version)
                else OnlineRpc.cancelSession(newId(), sessionId, snapshot.version)
            send(Pending(identity, call, discardDrafts = true))
        }
    }

    /** Explicit review acknowledgement, never an edit/flush of an expired draft. */
    suspend fun acknowledgeExpiry(sessionId: String): SessionSnapshot = mutex.withLock {
        val identity = reads.auth.capture().identity
        guardPending(identity)
        frozen(identity) {
            val snapshot = reloadLocked(identity, sessionId) ?: throw OnlineFailure(RemoteError.NotFound)
            if (snapshot.effectiveStatus != SessionStatus.EXPIRED || !snapshot.reviewRequired) throw validation("SESSION_REVIEW_REQUIRED")
            send(Pending(identity, OnlineRpc.updateSession(newId(), sessionId, snapshot.version, SessionCommand.AcknowledgeExpiry),
                discardDrafts = true))
            requireNotNull(confirmed(identity))
        }
    }

    suspend fun resolvePending(): SessionMutationReceipt? = mutex.withLock {
        val original = pending ?: return@withLock null
        change(original.identity) { it.copy(busy = true, savePhase = SessionSavePhase.RESOLVING) }
        try {
            val receipt = repository.resolve(original.call, expectedIdentity = original.identity)
            if (receipt != null) accept(original.identity, receipt)
            else change(original.identity) { phase(it.copy(busy = false, failure = RemoteError.UnknownOutcome)) }
            receipt
        } catch (cancelled: CancellationException) {
            failed(original.identity, cancelled, resolving = true)
            throw cancelled
        } catch (failure: Exception) {
            failed(original.identity, failure, resolving = true)
            throw failure
        }
    }

    /** Explicit retry only: same immutable JSON, request ID, resource IDs and original CAS. */
    suspend fun retry(): SessionMutationReceipt = mutex.withLock {
        val original = pending ?: throw OnlineFailure(RemoteError.NotFound)
        if (!reads.auth.isCurrent(original.identity)) throw StaleAuthException()
        send(original)
    }

    suspend fun discardDrafts(sessionId: String) = mutex.withLock {
        val identity = reads.auth.capture().identity
        guardPending(identity)
        change(identity) {
            require(it.snapshot?.id == sessionId || draftSessionId == sessionId)
            if (it.failure is RemoteError.Conflict && !conflictReloaded) throw OnlineFailure(RemoteError.UnresolvedMutation)
            cancelDelay()
            conflictReloaded = false
            draftSessionId = null
            phase(it.copy(drafts = emptyMap(), failure = null,
                requiresVerification = if (it.failure is RemoteError.Conflict) false else it.requiresVerification))
        }
    }

    suspend fun reapplyDrafts(sessionId: String): SessionSnapshot = mutex.withLock {
        val identity = reads.auth.capture().identity
        guardPending(identity)
        change(identity) {
            if (!conflictReloaded || it.failure !is RemoteError.Conflict) throw OnlineFailure(RemoteError.UnresolvedMutation)
            conflictReloaded = false
            phase(it.copy(failure = null, requiresVerification = false))
        }
        frozen(identity) { flushLocked(identity, sessionId) }
    }

    private fun requireSession(current: OnlineSessionState, id: String): SessionSnapshot {
        if (!current.loaded) throw OnlineFailure(RemoteError.Network)
        val snapshot = current.snapshot?.takeIf { it.id == id } ?: throw OnlineFailure(RemoteError.NotFound)
        if (snapshot.reviewRequired || (snapshot.effectiveStatus != SessionStatus.ACTIVE && snapshot.effectiveStatus != SessionStatus.PAUSED)) {
            throw OnlineFailure(RemoteError.Conflict("SESSION_REVIEW_REQUIRED", null))
        }
        return snapshot
    }

    private fun editable(identity: AuthIdentity, id: String): SessionSnapshot = reads.auth.withCurrent(identity) {
        guardPending(identity)
        val current = mutable.value
        if (current.identity != identity || current.requiresVerification || current.connectivityHint == false) throw OnlineFailure(RemoteError.Network)
        if (current.failure != null && current.failure !is RemoteError.Validation) throw OnlineFailure(current.failure)
        requireSession(current, id)
    }

    private fun guardPending(identity: AuthIdentity) = reads.auth.withCurrent(identity) {
        if (pending != null) throw OnlineFailure(RemoteError.UnresolvedMutation)
    }

    private suspend fun <T> frozen(identity: AuthIdentity, action: suspend () -> T): T {
        reads.auth.withCurrent(identity) { cancelDelay(); stagingFrozen = true }
        try { return action() }
        finally { if (reads.auth.isCurrent(identity)) reads.auth.withCurrent(identity) { stagingFrozen = false } }
    }

    private suspend fun send(original: Pending): SessionMutationReceipt {
        val identity = original.identity
        change(identity) { it.copy(busy = true, failure = null, savePhase = SessionSavePhase.SAVING) }
        try {
            val receipt = repository.execute(original.call, expectedIdentity = identity, onRegistered = { preserve(original) })
            accept(identity, receipt)
            return receipt
        } catch (cancelled: CancellationException) {
            failed(identity, cancelled)
            throw cancelled
        } catch (failure: Exception) {
            failed(identity, failure)
            throw failure
        }
    }

    private fun preserve(original: Pending) = reads.auth.withCurrent(original.identity) {
        pending = if (original.registeredReadRevision < 0) original.copy(registeredReadRevision = reloadRevision) else original
        mutable.value = mutable.value.copy(identity = original.identity, pendingRequestId = original.call.mutation!!.requestId)
    }

    private suspend fun accept(identity: AuthIdentity, receipt: SessionMutationReceipt): SessionSnapshot {
        currentCoroutineContext().ensureActive()
        change(identity) { current ->
            val original = pending
            val old = current.snapshot
            // An old replay acknowledges its request but never downgrades a newer authoritative read.
            val observedReplacement = old != null && old.id != receipt.sessionId && original != null &&
                reloadRevision > original.registeredReadRevision
            val confirmed = if (observedReplacement || (old?.id == receipt.sessionId && old.version > receipt.version)) old else receipt.snapshot
            val drafts = if (original?.discardDrafts == true) emptyMap() else current.drafts.filterNot { (key, draft) ->
                original?.revisions?.get(key) == draft.editRevision
            }
            pending = null
            conflictReloaded = false
            if (drafts.isEmpty()) draftSessionId = null
            phase(current.copy(loaded = true, snapshot = confirmed, drafts = drafts, busy = false,
                failure = null, requiresVerification = false, recovery = null, lastReceipt = receipt, pendingRequestId = null))
        }
        reads.auth.withCurrent(identity) {
            receipt.workoutId?.let { workoutId ->
                changes?.publish(WorkoutCommitted(identity, receipt.requestId, workoutId, receipt.snapshot.startedAt))
            }
        }
        return requireNotNull(confirmed(identity))
    }

    private fun failed(identity: AuthIdentity, failure: Exception, resolving: Boolean = false) {
        changeIfCurrent(identity) { current ->
            val registered = pending != null
            val reason = if (failure is CancellationException && registered) RemoteError.UnknownOutcome else error(failure)
            // A lookup failure can never acknowledge the original write.
            val keep = registered && (resolving || failure is CancellationException || reason == RemoteError.UnknownOutcome || reason == RemoteError.UnresolvedMutation)
            if (!keep) pending = null
            if (reason is RemoteError.Conflict) conflictReloaded = false
            phase(current.copy(busy = false, pendingRequestId = pending?.call?.mutation?.requestId,
                requiresVerification = current.requiresVerification || reason is RemoteError.Conflict || reason == RemoteError.Network,
                failure = if (failure is LegacyStartBlocked || (failure is CancellationException && !registered)) null else reason))
        }
    }

    private fun schedule(identity: AuthIdentity, sessionId: String) {
        cancelDelay()
        delayed = workerScope.launch {
            delay(400)
            // Never cancel a mutation already dispatched when later input resets the delay.
            val context = currentCoroutineContext()
            val dispatched = reads.auth.withCurrent(identity) {
                context.ensureActive()
                if (delayed !== context[Job]) false else { delayed = null; true }
            }
            if (!dispatched) return@launch
            try {
                mutex.withLock {
                    if (reads.auth.isCurrent(identity) && mutable.value.drafts.isNotEmpty() &&
                        mutable.value.failure == null && !mutable.value.requiresVerification && mutable.value.connectivityHint != false) {
                        flushLocked(identity, sessionId)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                changeIfCurrent(identity) { phase(it.copy(busy = false, failure = error(failure))) }
            }
        }
    }

    private fun cancelDelay() { delayed?.cancel(); delayed = null }
    private fun phase(current: OnlineSessionState): OnlineSessionState = current.copy(savePhase = when {
        current.busy -> when (current.savePhase) {
            SessionSavePhase.RELOADING, SessionSavePhase.RESOLVING -> current.savePhase
            else -> SessionSavePhase.SAVING
        }
        current.pendingRequestId != null -> SessionSavePhase.UNKNOWN
        current.failure is RemoteError.Conflict || current.snapshot?.reviewRequired == true -> SessionSavePhase.CONFLICT
        current.connectivityHint == false || current.failure == RemoteError.Network -> SessionSavePhase.OFFLINE_BLOCKED
        current.requiresVerification -> SessionSavePhase.UNKNOWN
        current.drafts.isNotEmpty() -> SessionSavePhase.DIRTY
        current.failure != null || current.recovery != null || !current.loaded -> SessionSavePhase.UNKNOWN
        else -> SessionSavePhase.CONFIRMED
    })
    private fun validation(reason: String) = OnlineFailure(RemoteError.Validation(reason, null))
    private fun error(failure: Exception) = (failure as? OnlineFailure)?.reason ?: RemoteError.Network
    private fun confirmed(identity: AuthIdentity): SessionSnapshot? = reads.auth.withCurrent(identity) { mutable.value.snapshot }
    private fun change(identity: AuthIdentity, transform: (OnlineSessionState) -> OnlineSessionState) = reads.auth.withCurrent(identity) {
        mutable.value = transform(mutable.value.takeIf { it.identity == identity } ?: OnlineSessionState(identity = identity))
    }
    private fun changeIfCurrent(identity: AuthIdentity, transform: (OnlineSessionState) -> OnlineSessionState) {
        if (reads.auth.isCurrent(identity)) change(identity, transform)
    }
    override fun close() { subscription.close(); workerScope.cancel() }
}
