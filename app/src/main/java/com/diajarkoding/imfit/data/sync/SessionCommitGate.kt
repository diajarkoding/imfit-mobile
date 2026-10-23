package com.diajarkoding.imfit.data.sync

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Identity is referential: even the same owner logging in again receives a different token. */
internal class SyncSession internal constructor(val ownerId: String, val epoch: Long)

internal class SyncSessionChangedException : IllegalStateException("Synchronization session changed")

/**
 * Lock order: this fence -> Room. Never acquire this fence from inside a Room transaction.
 *
 * Staged B1 primitive, deliberately not wired to SDK sessionStatus: an asynchronous SDK listener
 * is not an atomic authentication transition. The production adapter must prove that *all*
 * identity changes linearize here before this can authorize production commits/publication.
 */
internal class SessionCommitGate {
    private val mutex = Mutex()
    private var epoch = 0L
    private val mutableSession = MutableStateFlow<SyncSession?>(null)
    val session = mutableSession.asStateFlow()

    /** Call for logout/expiry as well as login/restore, including same-owner reauthentication. */
    suspend fun transition(ownerId: String?): SyncSession? = mutex.withLock {
        require(ownerId == null || ownerId.isNotBlank())
        epoch = Math.addExact(epoch, 1L)
        ownerId?.let { SyncSession(it, epoch) }.also { mutableSession.value = it }
    }

    fun isCurrent(expected: SyncSession): Boolean = session.value === expected

    /** Hold through the entire short local transaction, not through network or job draining. */
    suspend fun <T> commit(expected: SyncSession, transaction: suspend () -> T): T = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(expected)) throw SyncSessionChangedException()
        transaction()
    }

    /** Callback must be synchronous; do not launch an unfenced publication from it. */
    suspend fun publish(expected: SyncSession, publication: () -> Unit) = mutex.withLock {
        currentCoroutineContext().ensureActive()
        if (!isCurrent(expected)) throw SyncSessionChangedException()
        publication()
    }
}
