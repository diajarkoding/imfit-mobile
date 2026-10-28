package com.diajarkoding.imfit.data.remote.online

import com.diajarkoding.imfit.data.legacy.GuardedLegacyStart
import com.diajarkoding.imfit.data.legacy.LegacyGuardedSessionStart
import com.diajarkoding.imfit.data.legacy.LegacyStartReport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

data class OnlineSessionState(
    val identity: AuthIdentity? = null,
    val loaded: Boolean = false,
    val snapshot: SessionSnapshot? = null,
    val busy: Boolean = false,
    val failure: RemoteError? = null,
    val recovery: LegacyStartReport? = null,
    val lastReceipt: SessionMutationReceipt? = null,
    val pendingRequestId: String? = null,
)

class LegacyStartBlocked(val report: LegacyStartReport) : Exception("Legacy recovery required")

/** Application-scoped confirmed state. No disk drafts, DAO, importer, ticker I/O or auto retries.
 * UI and notifications share this serializer. An ambiguous/cancelled command retains its exact
 * request/IDs/CAS until explicit retry; loading a newer snapshot cannot acknowledge that command.
 */
class OnlineSessionStore(
    private val reads: RemoteReadGateway,
    private val repository: OnlineRepository,
    private val guardedStart: LegacyGuardedSessionStart,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : AutoCloseable {
    init { require(reads.auth === repository.auth) }
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(OnlineSessionState())
    val state = mutable.asStateFlow()
    private data class Pending(val identity: AuthIdentity, val call: RpcCall<SessionMutationReceipt>)
    private var pending: Pending? = null
    private val subscription = reads.auth.onIdentityChanged { identity ->
        pending = null
        mutable.value = OnlineSessionState(identity = identity)
    }

    /** Memory only. Unknown cold state is NOT absence, including in a background process. */
    fun peek(): SessionSnapshot? {
        val identity = reads.auth.capture().identity
        val current = mutable.value
        if (current.identity != identity || !current.loaded) throw OnlineFailure(RemoteError.Network)
        return current.snapshot?.takeIf { it.status == SessionStatus.ACTIVE || it.status == SessionStatus.PAUSED }
    }

    suspend fun reload(): SessionSnapshot? = mutex.withLock {
        val identity = reads.auth.capture().identity
        change(identity) { it.copy(busy = true, failure = null) }
        try {
            val found = reads.readFor(identity, OnlineRpc.activeSession())
            change(identity) { current ->
                val old = current.snapshot
                val incoming = found.session
                if (incoming != null && old?.id == incoming.id && old.version > incoming.version) {
                    throw OnlineFailure(RemoteError.Protocol)
                }
                current.copy(loaded = true, snapshot = incoming, busy = false)
            }
            found.session
        } catch (cancelled: CancellationException) {
            changeIfCurrent(identity) { it.copy(busy = false) }; throw cancelled
        } catch (failure: Exception) {
            changeIfCurrent(identity) { it.copy(busy = false, failure = error(failure)) }; throw failure
        }
    }

    suspend fun start(templateId: String, revision: Long): SessionSnapshot = mutex.withLock {
        val identity = reads.auth.capture().identity
        pending?.let {
            if (it.identity != identity) throw StaleAuthException()
            // Never silently turn another user intent into retry. Explicit retry() owns replay.
            throw OnlineFailure(RemoteError.UnresolvedMutation)
        }
        val call = OnlineRpc.createSession(newId(), newId(), templateId, revision)
        change(identity) { it.copy(busy = true, failure = null, recovery = null) }
        try {
            when (val result = guardedStart.create(call) { preserve(identity, call) }) {
                is GuardedLegacyStart.Blocked -> {
                    change(identity) { it.copy(busy = false, recovery = result.report) }
                    throw LegacyStartBlocked(result.report)
                }
                is GuardedLegacyStart.Created -> accept(identity, result.receipt)
            }
        } catch (cancelled: CancellationException) {
            // No retry permission if cancellation occurred during legacy preflight, before dispatch.
            changeIfCurrent(identity) { it.copy(busy = false, failure = if (pending != null) RemoteError.UnknownOutcome else null) }
            throw cancelled
        } catch (failure: Exception) {
            if (!ambiguous(failure)) reads.auth.withCurrent(identity) { pending = null }
            changeIfCurrent(identity) { it.copy(busy = false, pendingRequestId = pending?.call?.mutation?.requestId,
                failure = if (failure is LegacyStartBlocked) null else error(failure)) }
            throw failure
        }
    }

    suspend fun command(sessionId: String, command: SessionCommand): SessionSnapshot = mutex.withLock {
        val identity = reads.auth.capture().identity
        val snapshot = editable(identity, sessionId)
        send(identity, OnlineRpc.updateSession(newId(), sessionId, snapshot.version, command)).snapshot
    }

    /** Compatibility boundary only; callers never persist a whole local session snapshot. */
    suspend fun mutate(sessionId: String, transform: (com.diajarkoding.imfit.domain.model.WorkoutSession) -> com.diajarkoding.imfit.domain.model.WorkoutSession): SessionSnapshot = mutex.withLock {
        val identity = reads.auth.capture().identity
        val snapshot = editable(identity, sessionId)
        val command = snapshot.commandFor(transform(snapshot.toDomain()), newId) ?: return@withLock snapshot
        send(identity, OnlineRpc.updateSession(newId(), sessionId, snapshot.version, command)).snapshot
    }

    suspend fun finish(sessionId: String): SessionMutationReceipt = terminal(sessionId, true)
    suspend fun cancel(sessionId: String): SessionMutationReceipt = terminal(sessionId, false)
    private suspend fun terminal(sessionId: String, finish: Boolean): SessionMutationReceipt = mutex.withLock {
        val identity = reads.auth.capture().identity
        mutable.value.lastReceipt?.takeIf {
            mutable.value.identity == identity && it.sessionId == sessionId &&
                it.operation == if (finish) "finish_active_session" else "cancel_active_session"
        }?.let { return@withLock it }
        val snapshot = editable(identity, sessionId)
        val call = if (finish) OnlineRpc.finishSession(newId(), sessionId, snapshot.version)
            else OnlineRpc.cancelSession(newId(), sessionId, snapshot.version)
        send(identity, call)
    }

    /** Explicit retry only, same original JSON. NOT_FOUND is not permission for a fresh request. */
    suspend fun retry(): SessionMutationReceipt = mutex.withLock {
        val original = pending ?: throw OnlineFailure(RemoteError.NotFound)
        if (!reads.auth.isCurrent(original.identity)) throw StaleAuthException()
        send(original.identity, original.call)
    }

    private fun editable(identity: AuthIdentity, id: String): SessionSnapshot {
        if (pending != null) throw OnlineFailure(RemoteError.UnresolvedMutation)
        val current = mutable.value
        if (current.identity != identity || !current.loaded) throw OnlineFailure(RemoteError.Network)
        val snapshot = current.snapshot?.takeIf { it.id == id } ?: throw OnlineFailure(RemoteError.NotFound)
        if (snapshot.status !in setOf(SessionStatus.ACTIVE, SessionStatus.PAUSED)) throw OnlineFailure(RemoteError.Conflict("SESSION_TERMINAL", null))
        return snapshot
    }

    private suspend fun send(identity: AuthIdentity, call: RpcCall<SessionMutationReceipt>): SessionMutationReceipt {
        change(identity) { it.copy(busy = true, failure = null) }
        preserve(identity, call)
        try {
            val receipt = repository.execute(call, expectedIdentity = identity)
            accept(identity, receipt)
            return receipt
        } catch (cancelled: CancellationException) {
            changeIfCurrent(identity) { it.copy(busy = false, failure = RemoteError.UnknownOutcome) }; throw cancelled
        } catch (failure: Exception) {
            if (!ambiguous(failure)) reads.auth.withCurrent(identity) { pending = null }
            changeIfCurrent(identity) { it.copy(busy = false, failure = error(failure), pendingRequestId = pending?.call?.mutation?.requestId) }
            throw failure
        }
    }

    private fun preserve(identity: AuthIdentity, call: RpcCall<SessionMutationReceipt>) = reads.auth.withCurrent(identity) {
        pending = Pending(identity, call)
        mutable.value = mutable.value.copy(identity = identity,
            pendingRequestId = call.mutation!!.requestId)
    }
    private suspend fun accept(identity: AuthIdentity, receipt: SessionMutationReceipt): SessionSnapshot {
        currentCoroutineContext().ensureActive()
        change(identity) {
            val old = it.snapshot
            if (old?.id == receipt.sessionId && old.version > receipt.version) throw OnlineFailure(RemoteError.Protocol)
            pending = null
            it.copy(loaded = true, snapshot = receipt.snapshot, busy = false, failure = null,
                recovery = null, lastReceipt = receipt, pendingRequestId = null)
        }
        return receipt.snapshot
    }
    private fun error(failure: Exception) = (failure as? OnlineFailure)?.reason ?: RemoteError.Network
    private fun ambiguous(failure: Exception) = error(failure) in setOf(RemoteError.UnknownOutcome, RemoteError.UnresolvedMutation)
    private fun change(identity: AuthIdentity, transform: (OnlineSessionState) -> OnlineSessionState) = reads.auth.withCurrent(identity) {
        mutable.value = transform(mutable.value.takeIf { it.identity == identity } ?: OnlineSessionState(identity))
    }
    private fun changeIfCurrent(identity: AuthIdentity, transform: (OnlineSessionState) -> OnlineSessionState) {
        if (reads.auth.isCurrent(identity)) change(identity, transform)
    }
    override fun close() { subscription.close() }
}
