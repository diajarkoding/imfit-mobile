package com.diajarkoding.imfit.domain.model

import kotlinx.serialization.Serializable

/** Persisted with the active set, never scheduled as a rest alarm. */
@Serializable
data class ExecutionStopwatch(
    val elapsedMs: Long = 0,
    val anchorElapsedMs: Long? = null,
    val bootIdentity: Int? = null,
    val recoveryRequired: Boolean = false,
    /** True when a global Pause froze a running stopwatch, so Resume restarts it from the saved elapsed. */
    val pausedByWorkout: Boolean = false
) {
    val isRunning: Boolean get() = anchorElapsedMs != null

    private fun hasValidAnchor(nowElapsedMs: Long, currentBootIdentity: Int?): Boolean =
        anchorElapsedMs != null && bootIdentity != null && bootIdentity == currentBootIdentity &&
            nowElapsedMs >= anchorElapsedMs

    fun elapsedMillis(nowElapsedMs: Long, currentBootIdentity: Int?): Long =
        if (hasValidAnchor(nowElapsedMs, currentBootIdentity)) {
            elapsedMs + (nowElapsedMs - requireNotNull(anchorElapsedMs))
        } else elapsedMs

    fun checkpoint(nowElapsedMs: Long, currentBootIdentity: Int?): ExecutionStopwatch {
        if (!isRunning) return this
        // A new boot/unknown boot identity cannot validate a previous monotonic interval.
        // Keep the last durable measurement, pause, and ask the user to review it.
        if (!hasValidAnchor(nowElapsedMs, currentBootIdentity)) {
            return copy(anchorElapsedMs = null, recoveryRequired = true)
        }
        return copy(elapsedMs = elapsedMillis(nowElapsedMs, currentBootIdentity), anchorElapsedMs = nowElapsedMs)
    }

    fun start(nowElapsedMs: Long, currentBootIdentity: Int?): ExecutionStopwatch =
        if (currentBootIdentity == null) copy(anchorElapsedMs = null, recoveryRequired = true)
        else checkpoint(nowElapsedMs, currentBootIdentity).copy(
            anchorElapsedMs = nowElapsedMs, bootIdentity = currentBootIdentity, recoveryRequired = false)

    fun pause(nowElapsedMs: Long, currentBootIdentity: Int?): ExecutionStopwatch =
        checkpoint(nowElapsedMs, currentBootIdentity).copy(anchorElapsedMs = null, pausedByWorkout = false)

    /** Global Pause: freeze at the current elapsed and remember to restart on Resume. */
    fun pauseForWorkout(nowElapsedMs: Long, currentBootIdentity: Int?): ExecutionStopwatch =
        if (!isRunning) this else pause(nowElapsedMs, currentBootIdentity).copy(pausedByWorkout = true)

    /** Global Resume: continue from saved elapsed with a new anchor. No-op unless Pause froze it. */
    fun resumeFromWorkout(nowElapsedMs: Long, currentBootIdentity: Int?): ExecutionStopwatch =
        if (!pausedByWorkout) this else start(nowElapsedMs, currentBootIdentity).copy(pausedByWorkout = false)
}

fun WorkoutSet.checkpointExecutionStopwatch(nowElapsedMs: Long, bootIdentity: Int?): WorkoutSet {
    val stopwatch = executionStopwatch ?: return this
    if (isCompleted || !stopwatch.isRunning) return this
    val next = stopwatch.checkpoint(nowElapsedMs, bootIdentity)
    return copy(durationSeconds = (next.elapsedMs / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        executionStopwatch = next)
}

fun WorkoutSet.freezeExecutionStopwatch(nowElapsedMs: Long, bootIdentity: Int?): WorkoutSet {
    val stopwatch = executionStopwatch ?: return this
    if (isCompleted) return this
    val next = stopwatch.pause(nowElapsedMs, bootIdentity)
    return copy(durationSeconds = (next.elapsedMs / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        executionStopwatch = next)
}

fun WorkoutSet.toggleExecutionStopwatch(nowElapsedMs: Long, bootIdentity: Int?): WorkoutSet {
    if (isCompleted) return this
    val stopwatch = executionStopwatch ?: ExecutionStopwatch(elapsedMs = (durationSeconds ?: 0).toLong() * 1000)
    val next = if (stopwatch.isRunning) stopwatch.pause(nowElapsedMs, bootIdentity)
        else stopwatch.start(nowElapsedMs, bootIdentity)
    return copy(durationSeconds = (next.elapsedMs / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        executionStopwatch = next)
}
