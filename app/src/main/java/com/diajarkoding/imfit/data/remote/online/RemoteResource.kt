package com.diajarkoding.imfit.data.remote.online

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class RemoteValue<T>(
    val key: String? = null, val identity: AuthIdentity? = null, val data: T? = null,
    val loading: Boolean = false, val stale: Boolean = false, val error: RemoteError? = null,
)

/** In-memory query-scoped detail/aggregate state; refresh failure is never authoritative empty. */
class RemoteResource<T>(private val auth: OnlineAuth, private val scope: CoroutineScope) : AutoCloseable {
    private val mutable = MutableStateFlow(RemoteValue<T>())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var job: Job? = null
    private val subscription = auth.onIdentityChanged {
        generation++
        job?.cancel()
        mutable.value = RemoteValue()
    }
    fun load(key: String, force: Boolean = false, request: suspend () -> T) {
        val credential = try { auth.capture() } catch (_: OnlineFailure) {
            mutable.value = RemoteValue(key = key, error = RemoteError.Unauthenticated); return
        }
        auth.withCurrent(credential.identity) {
            val old = state.value
            if (!force && old.key == key && old.identity == credential.identity) return@withCurrent
            val ticket = ++generation
            job?.cancel()
            val retained = old.data.takeIf { old.key == key && old.identity == credential.identity }
            mutable.value = RemoteValue(key, credential.identity, retained, loading = true, stale = retained != null)
            job = scope.launch {
                try {
                    val result = request()
                    currentCoroutineContext().ensureActive()
                    auth.withCurrent(credential.identity) {
                        if (ticket == generation) mutable.value = RemoteValue(key, credential.identity, result)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (auth.isCurrent(credential.identity)) auth.withCurrent(credential.identity) {
                        if (ticket == generation) mutable.value = RemoteValue(key, credential.identity, retained,
                            stale = retained != null, error = (failure as? OnlineFailure)?.reason ?: RemoteError.Network)
                    }
                }
            }
        }
    }
    override fun close() = auth.synchronizedState {
        generation++; job?.cancel(); subscription.close(); mutable.value = RemoteValue()
    }
}
