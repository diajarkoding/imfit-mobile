package com.diajarkoding.imfit.data.remote.online

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed interface OnlineMutationChange {
    val identity: AuthIdentity
}

data class WorkoutCommitted(
    override val identity: AuthIdentity,
    val requestId: String,
    val workoutId: String,
    val startedAt: String,
) : OnlineMutationChange

data class ProfileChanged(override val identity: AuthIdentity) : OnlineMutationChange

/** Login-scoped, memory-only invalidation. New consumers perform their own initial read. */
class OnlineMutationChanges(private val auth: OnlineAuth) {
    // No replay: an event is not authority or a durable recovery queue. Slow active consumers
    // must not silently lose a confirmed mutation; storage grows only for undelivered events.
    private val mutableEvents = MutableSharedFlow<OnlineMutationChange>(extraBufferCapacity = Int.MAX_VALUE)
    val events = mutableEvents.asSharedFlow()
    private val requests = mutableSetOf<String>()
    private val ownerListener = auth.onIdentityChanged { requests.clear() }

    /** Publish only after validating a receipt/returned row against the captured identity. */
    fun publish(event: OnlineMutationChange): Boolean = auth.synchronizedState {
        if (!auth.isCurrent(event.identity)) return@synchronizedState false
        if (event is WorkoutCommitted && !requests.add(event.requestId)) return@synchronizedState false
        check(mutableEvents.tryEmit(event))
        true
    }
}

/** Server queries group timestamps by the query timezone, with an exclusive end date. */
internal fun DateWindow.containsWorkout(startedAt: String): Boolean {
    val date = Instant.parse(startedAt).atZone(ZoneId.of(timezone)).toLocalDate()
    return date >= LocalDate.parse(startDate) && date < LocalDate.parse(endDate)
}
