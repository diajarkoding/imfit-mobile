package com.diajarkoding.imfit.domain.model

/**
 * Pure runtime transitions for the active session (PLAN §17). Clocks are injected:
 * `now` is `SystemClock.elapsedRealtime()`, `boot` the boot identity, `wall` epoch millis (display only).
 * Every function here corresponds to a meaningful transition that the caller persists once;
 * nothing in this file is meant to run, or write to Room, on a per-second tick.
 */

private fun WorkoutSession.isV3(): Boolean = schemaVersion == 3

private fun Long.toSeconds(): Int = (this / 1000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

private fun WorkoutSession.mapSets(transform: (WorkoutSet) -> WorkoutSet): WorkoutSession =
    copy(exerciseLogs = exerciseLogs.map { log -> log.copy(sets = log.sets.map(transform)) })

/** Re-anchor a session-level clock after a reboot without counting the unprovable interval. */
private fun ExecutionStopwatch.reanchoredAfterRestore(now: Long, boot: Int?, shouldRun: Boolean): ExecutionStopwatch {
    val checkpointed = checkpoint(now, boot)
    return if (shouldRun && boot != null && checkpointed.recoveryRequired && !checkpointed.isRunning)
        checkpointed.copy(anchorElapsedMs = now, bootIdentity = boot)
    else checkpointed
}

/** First open of a v3 session: start the whole-session clock from wall time since start. */
fun WorkoutSession.ensureRuntime(now: Long, boot: Int?, wall: Long): WorkoutSession {
    if (!isV3() || sessionStopwatch != null) return this
    val gross = (wall - startTime).coerceAtLeast(0)
    val active = (gross - totalPausedTimeMs - (if (isPaused && lastPauseTime != null) wall - lastPauseTime else 0L)).coerceAtLeast(0)
    val session = ExecutionStopwatch(elapsedMs = active)
    val pause = ExecutionStopwatch(elapsedMs = totalPausedTimeMs)
    return copy(
        sessionStopwatch = if (isPaused) session else session.start(now, boot),
        pauseStopwatch = if (isPaused) pause.start(now, boot) else pause
    )
}

/** Whole-session active time: excludes Pause, includes rest and transitions. */
fun WorkoutSession.activeElapsedMs(now: Long, boot: Int?): Long =
    sessionStopwatch?.elapsedMillis(now, boot) ?: actualElapsedMs

/** Total time spent paused (monotonic for v3). */
fun WorkoutSession.pausedElapsedMs(now: Long, boot: Int?): Long =
    pauseStopwatch?.elapsedMillis(now, boot)
        ?: (totalPausedTimeMs + if (isPaused && lastPauseTime != null) System.currentTimeMillis() - lastPauseTime else 0L)

val WorkoutSession.needsTimerReconciliation: Boolean
    get() = sessionStopwatch?.recoveryRequired == true || pauseStopwatch?.recoveryRequired == true ||
        exerciseLogs.any { log -> log.sets.any { !it.isCompleted && it.executionStopwatch?.recoveryRequired == true } }

/** The single set that owns the session's execution timer (running, or frozen by global Pause). */
fun WorkoutSession.executionOwnerSetId(): String? =
    exerciseLogs.asSequence().flatMap { it.sets.asSequence() }.firstOrNull { set ->
        !set.isCompleted && set.executionStopwatch?.let { it.isRunning || it.pausedByWorkout } == true
    }?.id

fun WorkoutSession.pauseWorkout(now: Long, boot: Int?, wall: Long): WorkoutSession {
    if (isPaused) return this
    val paused = copy(isPaused = true, lastPauseTime = wall)
    if (!isV3()) return paused.mapSets { it.freezeExecutionStopwatch(now, boot) }
    return paused.copy(
        sessionStopwatch = sessionStopwatch?.pause(now, boot),
        pauseStopwatch = pauseStopwatch?.start(now, boot)
    ).mapSets { set ->
        val sw = set.executionStopwatch
        if (set.isCompleted || sw == null || !sw.isRunning) set else {
            val next = sw.pauseForWorkout(now, boot)
            set.copy(durationSeconds = next.elapsedMs.toSeconds(), executionStopwatch = next)
        }
    }
}

fun WorkoutSession.resumeWorkout(now: Long, boot: Int?, wall: Long): WorkoutSession {
    if (!isPaused) return this
    val pausedWall = (wall - (lastPauseTime ?: wall)).coerceAtLeast(0)
    val resumed = copy(isPaused = false, lastPauseTime = null, totalPausedTimeMs = totalPausedTimeMs + pausedWall)
    if (!isV3()) return resumed
    return resumed.copy(
        sessionStopwatch = sessionStopwatch?.start(now, boot),
        pauseStopwatch = pauseStopwatch?.pause(now, boot)
    ).mapSets { set ->
        val sw = set.executionStopwatch
        if (set.isCompleted || sw == null) set else set.copy(executionStopwatch = sw.resumeFromWorkout(now, boot))
    }
}

/**
 * Start/stop the execution timer of one v3 set. At most one timer is active per session: starting a
 * different set while another owns the timer returns null (rejected). Paused sessions reject too.
 */
fun WorkoutSession.toggleExecutionTimer(setId: String, now: Long, boot: Int?): WorkoutSession? {
    if (!isV3() || isPaused) return null
    val owner = executionOwnerSetId()
    if (owner != null && owner != setId) return null
    var found = false
    val next = mapSets { set ->
        if (set.id != setId || set.isCompleted) set else {
            found = true
            val sw = set.executionStopwatch ?: ExecutionStopwatch()
            val updated = if (sw.isRunning) sw.pause(now, boot) else sw.start(now, boot)
            set.copy(durationSeconds = updated.elapsedMs.toSeconds(), executionStopwatch = updated)
        }
    }
    return if (found) next else null
}

sealed interface SetCompletionResult {
    data class Completed(val session: WorkoutSession, val restSeconds: Int, val trigger: RestTimerTrigger?) : SetCompletionResult
    data object AlreadyCompleted : SetCompletionResult
    data object Invalid : SetCompletionResult
    data object Locked : SetCompletionResult
    data object NotFound : SetCompletionResult
}

/**
 * v3 completion. Validates against the frozen snapshot, freezes this set's stopwatch (actual active
 * duration, never the target), flips `is_completed`, and records the pending rest trigger in the same
 * value so the caller commits both in one Room transaction. Re-completing is a no-op.
 */
fun WorkoutSession.completeSet(exerciseId: String, setId: String, now: Long, boot: Int?): SetCompletionResult {
    if (!isV3()) return SetCompletionResult.Invalid
    val log = exerciseLogs.firstOrNull { it.exercise.id == exerciseId } ?: return SetCompletionResult.NotFound
    val set = log.sets.firstOrNull { it.id == setId } ?: return SetCompletionResult.NotFound
    if (set.isCompleted) return SetCompletionResult.AlreadyCompleted
    if (isPaused) return SetCompletionResult.Locked
    val frozen = set.freezeExecutionStopwatch(now, boot)
    val config = log.inputDefaults ?: return SetCompletionResult.Invalid
    if (!frozen.measurement.isCompleteEligible(log.trackingType, log.modality, config.trackingProfile, config.supportedMetrics))
        return SetCompletionResult.Invalid
    val restSeconds = log.restSecondsForSet(setId)
    val trigger = if (restSeconds > 0 && boot != null) RestTimerTrigger(
        sessionId = id, exerciseId = exerciseId, setId = setId, targetRowId = requireNotNull(set.targetRowId),
        restSeconds = restSeconds, deadlineElapsedMs = now + restSeconds * 1000L, bootId = boot.toString()
    ) else null
    val next = copy(
        exerciseLogs = exerciseLogs.map { l ->
            if (l.exercise.id != exerciseId) l else l.copy(sets = l.sets.map {
                if (it.id == setId) frozen.copy(isCompleted = true, restOutcome = null) else it
            })
        },
        // One rest per session: this completion supersedes any earlier trigger (null when rest is 0).
        pendingRestTrigger = trigger
    )
    return SetCompletionResult.Completed(next, restSeconds, trigger)
}

sealed interface SetUndoResult {
    data class Undone(val session: WorkoutSession, val clearedTrigger: Boolean) : SetUndoResult
    data object NotCompleted : SetUndoResult
    data object Locked : SetUndoResult
    data object NotFound : SetUndoResult
}

/** Undo → DRAFT. Inputs and the frozen duration are kept for correction; the rest outcome is discarded. */
fun WorkoutSession.undoSet(exerciseId: String, setId: String): SetUndoResult {
    val log = exerciseLogs.firstOrNull { it.exercise.id == exerciseId } ?: return SetUndoResult.NotFound
    val set = log.sets.firstOrNull { it.id == setId } ?: return SetUndoResult.NotFound
    if (!set.isCompleted) return SetUndoResult.NotCompleted
    if (isPaused) return SetUndoResult.Locked
    val clear = pendingRestTrigger?.setId == setId
    return SetUndoResult.Undone(copy(
        exerciseLogs = exerciseLogs.map { l ->
            if (l.exercise.id != exerciseId) l else l.copy(sets = l.sets.map {
                if (it.id == setId) it.copy(isCompleted = false, restOutcome = null) else it
            })
        },
        pendingRestTrigger = if (clear) null else pendingRestTrigger
    ), clear)
}

/**
 * Record the actual rest outcome once. No-op unless the set is a completed v3 result without an
 * outcome yet, so replays (recovery, duplicate events) never create a second outcome.
 */
fun WorkoutSession.withRestOutcome(setId: String, outcome: RestOutcome): WorkoutSession {
    if (!isV3() || !outcome.isValid()) return this
    val target = exerciseLogs.asSequence().flatMap { it.sets.asSequence() }.firstOrNull { it.id == setId }
    if (target == null || !target.isCompleted || target.restOutcome != null) return this
    return mapSets { if (it.id == setId) it.copy(restOutcome = outcome) else it }
}

/** Clear a processed trigger (idempotent). */
fun WorkoutSession.clearRestTrigger(setId: String): WorkoutSession =
    if (pendingRestTrigger?.setId == setId) copy(pendingRestTrigger = null) else this

/**
 * Process death / reopen. Same boot: re-anchor from stored monotonic anchors (nothing resets).
 * Different or unknown boot: keep the last durable elapsed, never invent time, and flag reconciliation.
 * Persist once on restore, not per tick.
 */
fun WorkoutSession.reconcileAfterRestore(now: Long, boot: Int?): WorkoutSession {
    if (!isV3()) return this
    return copy(
        sessionStopwatch = sessionStopwatch?.reanchoredAfterRestore(now, boot, shouldRun = !isPaused),
        pauseStopwatch = pauseStopwatch?.reanchoredAfterRestore(now, boot, shouldRun = isPaused)
    ).mapSets { it.checkpointExecutionStopwatch(now, boot) }
}

/**
 * Finish (T35): freeze the whole-session clocks so the finished aggregate can read stable elapsed values
 * without a clock. Idempotent. Rest still running is handled by the timer, which records no outcome for it.
 */
fun WorkoutSession.freezeForFinish(now: Long, boot: Int?): WorkoutSession {
    if (!isV3()) return this
    return copy(sessionStopwatch = sessionStopwatch?.pause(now, boot), pauseStopwatch = pauseStopwatch?.pause(now, boot))
}

/** Paused/active durations of a finished v3 session, or null when they cannot be proven (never invented). */
data class FinishedSessionDurations(val pausedElapsedMs: Long, val activeElapsedMs: Long)

/**
 * Both clocks must exist and be frozen. The server requires `paused + active <= gross + 1 s`; the gross span is
 * wall-clock based, so a user clock change could otherwise make a valid workout unrepeatable. Clamp rather than
 * lose the workout: paused first, then active takes what remains.
 */
fun WorkoutSession.finishedDurations(endTimeMs: Long): FinishedSessionDurations? {
    if (!isV3()) return null
    val active = sessionStopwatch?.takeIf { !it.isRunning } ?: return null
    val paused = pauseStopwatch?.takeIf { !it.isRunning } ?: return null
    val gross = (endTimeMs - startTime).coerceAtLeast(0)
    val pausedMs = paused.elapsedMs.coerceIn(0, gross)
    val activeMs = active.elapsedMs.coerceIn(0, gross - pausedMs)
    return FinishedSessionDurations(pausedMs, activeMs)
}

/** In-memory display only: derive running durations from anchors. Never persisted. */
fun WorkoutSession.withDisplayElapsed(now: Long, boot: Int?): WorkoutSession = mapSets { set ->
    val sw = set.executionStopwatch
    if (set.isCompleted || sw == null || !sw.isRunning) set
    else set.copy(durationSeconds = sw.elapsedMillis(now, boot).toSeconds())
}
