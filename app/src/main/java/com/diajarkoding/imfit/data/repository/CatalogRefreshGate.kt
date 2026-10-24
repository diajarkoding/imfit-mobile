package com.diajarkoding.imfit.data.repository

import kotlinx.coroutines.*

/** Repository-owned catalog flight: manual requests bypass cooldown, never an active flight. */
internal class CatalogRefreshGate(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val cooldownMillis: Long = 30_000,
) {
    private val lock = Any()
    private var active: Deferred<Boolean>? = null
    private var completed: Pair<Long, Boolean>? = null

    suspend fun refresh(force: Boolean, read: suspend () -> Boolean): Boolean {
        val flight = synchronized(lock) {
            active ?: run {
                completed?.let { (at, result) ->
                    if (!force && now() - at in 0 until cooldownMillis) return result
                }
                lateinit var created: Deferred<Boolean>
                created = scope.async(start = CoroutineStart.LAZY) {
                    try { read() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { false }
                }
                active = created
                created.invokeOnCompletion { failure ->
                    synchronized(lock) {
                        if (active === created) {
                            active = null
                            if (failure == null) {
                                @OptIn(ExperimentalCoroutinesApi::class)
                                val result = created.getCompleted()
                                completed = now() to result
                            }
                        }
                    }
                }
                created
            }
        }
        flight.start()
        return flight.await()
    }
}
