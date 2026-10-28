package com.diajarkoding.imfit.data.remote.online

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject

/** RPC-only foundation. No DAO, importer, disk cache, fabricated data or automatic writer fallback. */
class OnlineRepository(private val transport: OnlineTransport, val auth: OnlineAuth) : AutoCloseable {
    private data class Resource(val identity: AuthIdentity, val type: String, val id: String)
    private data class Pending(val name: String, val body: JsonObject, val requestId: String)
    private data class RequestKey(val identity: AuthIdentity, val request: String)
    private val mutex = Mutex()
    // In memory only, retained for this login lifetime. No durable offline retry queue.
    private var ledgerIdentity: AuthIdentity? = null
    private val intents = mutableMapOf<RequestKey, Pending>()
    private val unresolved = mutableMapOf<Resource, Pending>()
    private val confirmedMutable = MutableStateFlow<Map<String, SessionSnapshot>>(emptyMap())
    private val closed = AtomicBoolean(false)
    val confirmedSessions = confirmedMutable.asStateFlow()
    private val authSubscription = auth.onIdentityChanged { identity -> resetFor(identity) }
    override fun close() = auth.synchronizedState {
        closed.set(true)
        authSubscription.close()
        intents.clear()
        unresolved.clear()
        confirmedMutable.value = emptyMap()
    }

    suspend fun <T> execute(call: RpcCall<T>, expectedIdentity: AuthIdentity? = null): T {
        val credential = auth.capture()
        if (expectedIdentity != null && credential.identity != expectedIdentity) throw StaleAuthException()
        return if (call.mutation == null) request(call, credential) else mutex.withLock {
            val mutation = call.mutation
            val resource = Resource(credential.identity, mutation.resourceType, mutation.resourceId)
            val key = RequestKey(credential.identity, mutation.requestId)
            val pending = Pending(call.name, call.parameters, mutation.requestId)
            auth.withCurrent(credential.identity) {
                checkCurrent(credential)
                resetFor(credential.identity)
                if (intents[key]?.let { it != pending } == true) throw OnlineFailure(RemoteError.IdempotencyMismatch)
                // Conservative owner-wide gate also prevents ambiguous creates from being retried
                // with a fresh resource ID (which would bypass a resource-only gate).
                if (unresolved.values.any { it != pending }) throw OnlineFailure(RemoteError.UnresolvedMutation)
                intents[key] = pending
                // Mark before dispatch: cancellation after server commit also leaves outcome ambiguous.
                unresolved[resource] = pending
            }
            try {
                val result = request(call, credential)
                auth.withCurrent(credential.identity) {
                    checkCurrent(credential)
                    unresolved.remove(resource)
                    publish(result, credential)
                }
                result
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: OnlineFailure) {
                auth.synchronizedState {
                    if (failure.reason != RemoteError.UnknownOutcome && ledgerIdentity == credential.identity) unresolved.remove(resource)
                }
                throw failure
            }
        }
    }

    /** NOT_FOUND never releases the resource. Retry the original call; never mint a new ID. */
    suspend fun <T> resolve(original: RpcCall<T>): T? = mutex.withLock {
        val credential = auth.capture()
        val mutation = requireNotNull(original.mutation)
        val resource = Resource(credential.identity, mutation.resourceType, mutation.resourceId)
        val pending = Pending(original.name, original.parameters, mutation.requestId)
        // Discovery without the original in-memory intent is a read, not proof of a guessed payload.
        auth.withCurrent(credential.identity) {
            checkCurrent(credential)
            resetFor(credential.identity)
            if (intents[RequestKey(credential.identity, mutation.requestId)] != pending) {
                throw OnlineFailure(RemoteError.IdempotencyMismatch)
            }
        }
        val lookup = request(OnlineRpc.operationStatus(mutation.requestId), credential)
        if (lookup.state == OperationState.NOT_FOUND) return@withLock null
        val receipt = requireNotNull(lookup.receipt)
        if (receipt.requestId != mutation.requestId || receipt.operation != original.name ||
            receipt.resourceId != mutation.resourceId || receipt.resourceType != mutation.resourceType) {
            throw OnlineFailure(RemoteError.Protocol)
        }
        val result = decode(original, receipt.result.toString())
        auth.withCurrent(credential.identity) {
            checkCurrent(credential)
            if (unresolved[resource] == pending) unresolved.remove(resource)
            publish(result, credential)
        }
        result
    }

    private suspend fun <T> request(call: RpcCall<T>, credential: RequestCredential): T {
        checkCurrent(credential)
        val response = try {
            transport.post(call.name, call.parameters, credential)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            checkCurrent(credential)
            throw OnlineFailure(if (call.mutation != null) RemoteError.UnknownOutcome else RemoteError.Network)
        }
        currentCoroutineContext().ensureActive()
        checkCurrent(credential)
        if (response.status !in 200..299) {
            val error = if (call.mutation != null && (response.status >= 500 || response.status in setOf(408, 429))) {
                RemoteError.UnknownOutcome
            } else mapRemoteError(response).let {
                if (call.mutation != null && it == RemoteError.Protocol) RemoteError.UnknownOutcome else it
            }
            throw OnlineFailure(error)
        }
        return try {
            decode(call, response.body)
        } catch (failure: OnlineFailure) {
            // A malformed success receipt can still correspond to a committed mutation.
            if (call.mutation != null) throw OnlineFailure(RemoteError.UnknownOutcome)
            throw failure
        }
    }

    private fun <T> decode(call: RpcCall<T>, body: String): T = try {
        onlineJson.decodeFromString(call.serializer, body).also(call.validate)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        throw OnlineFailure(RemoteError.Protocol)
    }

    private fun resetFor(identity: AuthIdentity?) {
        if (ledgerIdentity != identity) {
            ledgerIdentity = identity
            intents.clear()
            unresolved.clear()
            confirmedMutable.value = emptyMap()
        }
    }
    private fun publish(result: Any?, credential: RequestCredential) {
        auth.withCurrent(credential.identity) {
            checkCurrent(credential)
            if (result is SessionMutationReceipt) {
                val current = confirmedMutable.value[result.sessionId]
                if (current == null || result.version > current.version) {
                    confirmedMutable.value = confirmedMutable.value + (result.sessionId to result.snapshot)
                }
            }
        }
    }
    private fun checkCurrent(credential: RequestCredential) {
        if (closed.get()) throw CancellationException("Repository closed")
        if (!auth.isCurrent(credential.identity)) throw StaleAuthException()
    }
}
