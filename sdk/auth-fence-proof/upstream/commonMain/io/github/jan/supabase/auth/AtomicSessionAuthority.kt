package io.github.jan.supabase.auth

import io.github.jan.supabase.auth.user.UserSession
import io.ktor.util.AttributeKey
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** An identity is never reused, even if the owner/session contents return to their previous value. */
class AuthIdentity internal constructor(val ownerId: String?, val epoch: Long)

/** Opaque, authority-bound operation ticket; contains no access/refresh token. */
class AuthOperation internal constructor(
    internal val authority: AtomicSessionAuthority,
    internal val identity: AuthIdentity,
    internal val generation: Long,
)

/** A local commit lease survives eligible credential renewal, never an identity transition. */
class AuthCommitLease internal constructor(
    internal val authority: AtomicSessionAuthority,
    internal val identity: AuthIdentity,
) {
    val ownerId: String? get() = identity.ownerId
    val epoch: Long get() = identity.epoch
}

class StaleAuthOperationException : CancellationException("Authentication operation is stale")
class AuthAuthorityUnavailableException : IllegalStateException("Authentication authority requires recovery")

enum class AuthFlowRejection { MISSING_PROVENANCE, NOT_PREPARED, STATE_MISMATCH, FLOW_TYPE_MISMATCH, INVALID_REDIRECT, VERIFIER_MISMATCH, ALREADY_IN_FLIGHT, OWNER_MISMATCH }

/** Reasons only: never include callback codes, fragments, verifier values or credentials. */
class UnverifiedAuthFlowException(val reason: AuthFlowRejection) : IllegalStateException("External authentication flow rejected: ${reason.name}")

/** Immutable inputs captured before Auth work. Never log this context or its session. */
internal class AuthCallContext(
    val auth: AuthImpl,
    val operation: AuthOperation,
    val session: UserSession?,
) : AbstractCoroutineContextElement(Key) {
    internal var identityClearedByThisOperation = false
    companion object Key : CoroutineContext.Key<AuthCallContext>
}

internal val AuthRequestContext = AttributeKey<AuthCallContext>("imfit-auth-operation")

/** Proof-only flow provenance. Neither credentials nor this ticket may be reconstructed from storage. */
class AuthExternalFlow internal constructor(
    call: AuthCallContext,
    val state: String,
    internal val flowType: FlowType,
) {
    internal var call by atomic(call)
    internal var completedOperation: AuthOperation? by atomic(null)
    internal var verifier: String? = null
    internal var prepared = false
    internal var purpose: AuthFlowPurpose? = null
    internal var inFlight = false
}

internal enum class AuthFlowPurpose { OAUTH, SSO, LINK, RECOVERY, SIGNUP, OTP, EMAIL_UPDATE }

/** Explicit transport of the initiating handle and result. Not a credential/recovery envelope. */
class AuthExternalInitiation<out T> internal constructor(val flow: AuthExternalFlow, val result: T)

internal class AuthExternalContext(val flow: AuthExternalFlow?) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AuthExternalContext>
}

internal class AuthRefreshTask(val job: Job) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<AuthRefreshTask>
}

/**
 * SDK mutation and application local commit share this exact mutex. Never hold it over network.
 * This is a proof API, not proof that every SDK writer has been migrated to it.
 */
class AtomicSessionAuthority {
    private val mutex = Mutex()
    private var identity = AuthIdentity(null, 0)
    private var generation = 0L
    private var available = false
    private var closed = false
    // Compact proof of consecutive lifecycle-only control receipts for this exact identity.
    // Every other generation-changing path breaks the chain; normal validators never use it.
    private var lifecycleFloor: Long? = null
    internal val bootstrapOperation = AuthOperation(this, identity, generation)
    private val publishedOperation = MutableStateFlow(bootstrapOperation)

    /** Synchronous framework-event capture only; NEVER authorizes a write without awaited validation. */
    internal fun snapshotOperation(): AuthOperation = publishedOperation.value

    private class Held(val authority: AtomicSessionAuthority) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Held>
    }

    private suspend fun <T> locked(block: suspend () -> T): T {
        check(currentCoroutineContext()[Held]?.authority !== this) { "Session authority is not reentrant" }
        return mutex.withLock {
            currentCoroutineContext().ensureActive()
            withContext(Held(this)) { block() }
        }
    }

    suspend fun operation(): AuthOperation = locked {
        if (closed) throw AuthAuthorityUnavailableException()
        AuthOperation(this, identity, generation)
    }

    internal suspend fun validate(expected: AuthOperation) = locked {
        if (closed || expected.authority !== this || expected.identity !== identity || expected.generation != generation) {
            throw StaleAuthOperationException()
        }
    }

    /** Auxiliary local SDK state, same awaited fence, without inventing an identity transition. */
    internal suspend fun <T> local(expected: AuthOperation, work: suspend () -> T): T = locked {
        if (closed || expected.authority !== this || expected.identity !== identity || expected.generation != generation) {
            throw StaleAuthOperationException()
        }
        work()
    }

    /** Awaited local job/lifecycle control retires in-flight operations, not same-identity leases. */
    internal suspend fun control(
        expected: AuthOperation,
        onFailure: (Throwable) -> Unit = {},
        work: (AuthOperation) -> Unit,
    ): AuthOperation = changeControl(expected, false, onFailure) { _, next -> work(next) }

    internal suspend fun lifecycleControl(
        expected: AuthOperation,
        onFailure: (Throwable) -> Unit,
        work: (AuthOperation, AuthOperation) -> Unit,
    ): AuthOperation = changeControl(expected, true, onFailure, work)

    private suspend fun changeControl(
        expected: AuthOperation,
        lifecycle: Boolean,
        onFailure: (Throwable) -> Unit,
        work: (AuthOperation, AuthOperation) -> Unit,
    ): AuthOperation = locked {
        val followsReceipts = lifecycle && lifecycleFloor?.let { expected.generation in it..generation } == true
        if (closed || expected.authority !== this || expected.identity !== identity ||
            (expected.generation != generation && !followsReceipts)) {
            throw StaleAuthOperationException()
        }
        check(generation < Long.MAX_VALUE) { "Session generation exhausted" }
        val previous = AuthOperation(this, identity, generation)
        val next = AuthOperation(this, identity, generation + 1)
        try {
            work(previous, next)
            currentCoroutineContext().ensureActive()
            lifecycleFloor = if (lifecycle) lifecycleFloor ?: generation else null
            generation++
            publishedOperation.value = next
            next
        } catch (failure: Throwable) {
            poison(failure, onFailure)
            throw failure
        }
    }

    /** Pair immutable request inputs with their exact generation before any suspension/network. */
    suspend fun <T> capture(read: () -> T): Pair<AuthOperation, T> = locked {
        if (closed) throw AuthAuthorityUnavailableException()
        AuthOperation(this, identity, generation) to read()
    }

    suspend fun lease(): AuthCommitLease = locked {
        if (!available) throw AuthAuthorityUnavailableException()
        AuthCommitLease(this, identity)
    }

    /** Validate before local persistence/publication, under the same lock used by commit(). */
    suspend fun <T> mutate(
        expected: AuthOperation,
        ownerId: String?,
        identityTransition: Boolean,
        onFailure: (Throwable) -> Unit = {},
        guard: () -> Boolean = { true },
        onCommitted: (AuthOperation) -> Unit = {},
        publication: suspend () -> T,
    ): T = locked {
        if (closed || expected.authority !== this || expected.identity !== identity || expected.generation != generation) {
            throw StaleAuthOperationException()
        }
        if (!guard()) throw StaleAuthOperationException()
        require(ownerId == null || ownerId.isNotBlank())
        if (!identityTransition) check(available && identity.ownerId == ownerId) { "Renewal cannot change identity" }
        check(generation < Long.MAX_VALUE && identity.epoch < Long.MAX_VALUE) { "Session generation exhausted" }
        val next = if (identityTransition) AuthIdentity(ownerId, identity.epoch + 1) else identity
        try {
            val result = publication()
            currentCoroutineContext().ensureActive()
            identity = next
            lifecycleFloor = null
            generation++
            available = true
            val committed = AuthOperation(this, identity, generation)
            publishedOperation.value = committed
            onCommitted(committed)
            result
        } catch (failure: Throwable) {
            poison(failure, onFailure)
            throw failure
        }
    }

    private fun poison(failure: Throwable, onFailure: (Throwable) -> Unit) {
        lifecycleFloor = null
        // Saturate on exhaustion: never wrap/reuse a generation even if a post-commit hook failed.
        identity = AuthIdentity(null, if (identity.epoch < Long.MAX_VALUE) identity.epoch + 1 else Long.MAX_VALUE)
        if (generation < Long.MAX_VALUE) generation++
        available = false
        publishedOperation.value = AuthOperation(this, identity, generation)
        try { onFailure(failure) }
        catch (cleanupFailure: Throwable) { failure.addSuppressed(cleanupFailure) }
    }

    /** Bounded local transaction/publication only. SDK memory reads alone are not a lease. */
    suspend fun <T> commit(expected: AuthCommitLease, transaction: suspend () -> T): T = locked {
        if (!available || expected.authority !== this || expected.identity !== identity) {
            throw StaleAuthOperationException()
        }
        transaction()
    }

    /** Drain an in-flight local mutation, then permanently reject late work without touching storage. */
    internal suspend fun shutdown(publication: () -> Unit) = locked {
        closed = true
        lifecycleFloor = null
        available = false
        publishedOperation.value = AuthOperation(this, identity, generation)
        publication()
    }
}
