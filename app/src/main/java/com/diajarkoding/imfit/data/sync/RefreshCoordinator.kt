package com.diajarkoding.imfit.data.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Shared, service-owned read flights. One instance must serve full/subset/master entry points.
 * A caller leaving a screen cancels its await, never the shared job. Catalog has an independent
 * lane; other datasets share one bounded lane. No registry lock is held during network/Room work.
 *
 * Not yet connected to SyncRunner: SDK epoch linearization and revision-safe upload acknowledgement
 * are exit gates. This read registry is not an outbox drain/wakeup handshake, and must not be used
 * to satisfy a fresh-after-write demand without a separate mutation revision barrier.
 */
internal class RefreshCoordinator(
    parentScope: CoroutineScope,
    private val sessionGate: SessionCommitGate,
    private val monotonicMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val automaticCooldownMillis: Long = 30_000L,
) : AutoCloseable {
    init { require(automaticCooldownMillis >= 0L) }

    private data class Key(val session: SyncSession, val dataset: RefreshDataset)
    private class Flight {
        lateinit var deferred: Deferred<RefreshOutcome>
        var outcome: RefreshOutcome? = null // Access only under registryLock.
    }
    private data class Completed(val at: Long, val outcome: RefreshOutcome)

    private val owner = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + owner)
    private val registryLock = Any()
    private val flights = mutableMapOf<Key, Flight>()
    private val completed = mutableMapOf<Key, Completed>()
    private val catalogLane = Mutex()
    private val userLane = Mutex()
    private var starts = 0L
    private var joins = 0L
    private var terminals = 0L
    private var cooldownHits = 0L

    internal data class Counters(val started: Long, val joined: Long, val terminal: Long, val cooldownHits: Long, val active: Int)

    suspend fun refresh(
        session: SyncSession,
        datasets: Set<RefreshDataset>,
        origin: RefreshOrigin,
        read: suspend (RefreshDataset) -> RefreshOutcome,
    ): Map<RefreshDataset, RefreshOutcome> = coroutineScope {
        // Register every subset demand before yielding; otherwise a caller already overlapping a
        // held flight could be dispatched only after it completes and accidentally start another.
        datasets.associateWith { dataset -> async(start = CoroutineStart.UNDISPATCHED) {
            request(session, dataset, origin) { read(dataset) }
        } }
            .mapValues { (_, result) -> result.await() }
    }

    suspend fun request(
        session: SyncSession,
        dataset: RefreshDataset,
        origin: RefreshOrigin,
        read: suspend () -> RefreshOutcome,
    ): RefreshOutcome {
        currentCoroutineContext().ensureActive()
        val key = Key(session, dataset)
        val flight = synchronized(registryLock) {
            if (!owner.isActive) return RefreshOutcome.Cancelled
            if (!sessionGate.isCurrent(session)) return RefreshOutcome.AuthChanged
            // At most four terminal entries survive per current session; never reuse another epoch.
            completed.keys.removeAll { !sessionGate.isCurrent(it.session) }
            flights[key]?.also { joins++ } ?: run {
                if (origin == RefreshOrigin.AUTOMATIC) {
                    completed[key]?.let { recent ->
                        val elapsed = monotonicMillis() - recent.at
                        if (elapsed >= 0L && elapsed < automaticCooldownMillis) {
                            cooldownHits++
                            return recent.outcome
                        }
                    }
                }
                val created = Flight()
                created.deferred = scope.async(start = CoroutineStart.LAZY) {
                    val lane = if (dataset == RefreshDataset.CATALOG) catalogLane else userLane
                    val outcome = lane.withLock {
                        if (!sessionGate.isCurrent(session)) RefreshOutcome.AuthChanged
                        else read().let { if (sessionGate.isCurrent(session)) it else RefreshOutcome.AuthChanged }
                    }
                    synchronized(registryLock) { created.outcome = outcome }
                    outcome
                }
                flights[key] = created
                starts++
                // Runs even if cancellation happens before the lazy coroutine starts.
                created.deferred.invokeOnCompletion { failure ->
                    synchronized(registryLock) {
                        if (flights[key] === created) {
                            flights.remove(key)
                            terminals++
                            created.outcome?.let { outcome ->
                                if (failure == null && owner.isActive && sessionGate.isCurrent(session) &&
                                    outcome != RefreshOutcome.Cancelled && outcome != RefreshOutcome.AuthChanged) {
                                    completed[key] = Completed(monotonicMillis(), outcome)
                                }
                            }
                        }
                    }
                }
                created
            }
        }
        // Starting/awaiting outside registryLock also supports an unconfined/test dispatcher.
        flight.deferred.start()
        return try {
            flight.deferred.await().let { if (sessionGate.isCurrent(session)) it else RefreshOutcome.AuthChanged }
        } catch (cancelled: CancellationException) {
            // Distinguish a cancelled UI waiter from cancellation of the service-owned flight.
            currentCoroutineContext().ensureActive()
            RefreshOutcome.Cancelled
        }
    }

    /** Call only when an epoch has been invalidated, never for an individual UI waiter. */
    fun cancelInvalidSessions() {
        val invalid = synchronized(registryLock) {
            completed.keys.removeAll { !sessionGate.isCurrent(it.session) }
            flights.filterKeys { !sessionGate.isCurrent(it.session) }.values.map { it.deferred }
        }
        invalid.forEach { it.cancel() }
    }

    internal fun activeFlightCount(): Int = synchronized(registryLock) { flights.size }
    internal fun counters(): Counters = synchronized(registryLock) { Counters(starts, joins, terminals, cooldownHits, flights.size) }

    suspend fun shutdown() {
        close()
        owner.join()
    }

    override fun close() {
        owner.cancel()
        synchronized(registryLock) { completed.clear() }
    }
}
