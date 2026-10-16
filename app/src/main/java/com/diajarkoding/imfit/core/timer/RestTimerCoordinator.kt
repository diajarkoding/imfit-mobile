package com.diajarkoding.imfit.core.timer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.UUID
import com.diajarkoding.imfit.domain.model.WorkoutSession

@Serializable
enum class TimerStatus { RUNNING, EXPIRED, ACKNOWLEDGED, SKIPPED, CANCELLED }
@Serializable
enum class SchedulingMode { EXACT, BEST_EFFORT, UNAVAILABLE }

data class PlaybackToken(val timerId: String, val generation: Long, val revision: Long)
enum class PlaybackReadiness { WAITING, READY, FAILED_PERMANENT }

enum class AlarmDeliveryResult { HANDLED, RETRY }
enum class FinishedPresentation { POSTED, BLOCKED, RETRY }

@Serializable
data class RestTimerState(
    val timerId: String,
    val generation: Long = 0,
    val userId: String,
    val workoutId: String,
    val templateId: String,
    val exerciseId: String,
    val setId: String,
    val exerciseName: String,
    val durationMs: Long,
    val deadlineElapsedMs: Long,
    val bootIdentity: Int?,
    val startedAtEpochMs: Long,
    val status: TimerStatus = TimerStatus.RUNNING,
    val schedulingMode: SchedulingMode = SchedulingMode.UNAVAILABLE,
    val reason: String? = null,
    val startedSetIds: List<String> = emptyList(),
    // Durable suppression prevents audio replay when controls disappear or playback fails.
    val audioSuppressed: Boolean = false,
    val schemaVersion: Int = 1
) {
    fun remainingSeconds(now: Long): Int =
        ((deadlineElapsedMs - now).coerceAtLeast(0L).let { (it + 999) / 1000 }).toInt()

    fun matchesExpired(timerId: String?, generation: Long): Boolean =
        this.timerId == timerId && this.generation == generation && status == TimerStatus.EXPIRED
}

interface MonotonicClock {
    fun elapsedRealtime(): Long
    fun epochMillis(): Long
    fun bootIdentity(): Int?
}
interface RestTimerStore {
    suspend fun read(): RestTimerState?
    suspend fun write(state: RestTimerState)
}
interface RestTimerEffects {
    suspend fun validSession(state: RestTimerState): Boolean
    fun userStopped(state: RestTimerState): Boolean
    fun schedule(state: RestTimerState): SchedulingMode
    fun cancelAlarm(state: RestTimerState)
    fun showFinished(state: RestTimerState): FinishedPresentation
    fun dismissFinished()
    fun controlsAvailable(): Boolean
    fun controlsPending(): Boolean = false
    fun noteUserDismissed(timerId: String) {}
    fun clearDismissed() {}
    fun startAudio(): Boolean
    fun stopAudio()
    fun clearOrphans() {}
    fun renderWorkout(state: RestTimerState?, session: WorkoutSession? = null) {}
    fun dismissWorkout() {}
    suspend fun workoutSessionIsCurrent(session: WorkoutSession): Boolean = true
    fun playbackReadiness(state: RestTimerState, token: PlaybackToken, alarmDelivery: Boolean): PlaybackReadiness =
        if (controlsAvailable()) PlaybackReadiness.READY
        else if (controlsPending()) PlaybackReadiness.WAITING else PlaybackReadiness.FAILED_PERMANENT
    fun cancelPlaybackChecks() {}
    fun enqueuePlaybackCheck(token: PlaybackToken) {}
    fun trace(event: String, state: RestTimerState?, detail: String = "") {}
}

/** All mutations and effects are serialized. No tick counter is persisted. */
class RestTimerCoordinator(
    private val store: RestTimerStore,
    private val clock: MonotonicClock,
    private val effects: RestTimerEffects
) {
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow<RestTimerState?>(null)
    val state = mutableState.asStateFlow()
    private val mutableError = MutableStateFlow<String?>(null)
    val error = mutableError.asStateFlow()
    private var loaded = false
    private var playingId: String? = null
    private var scheduledIdentity: Pair<String, Long>? = null
    private var pendingTerminal: RestTimerState? = null
    private var revision = 0L
    private var playbackToken: PlaybackToken? = null
    private var playbackRequestedAtMs = 0L
    private var playbackFromAlarm = false

    private fun invalidatePlayback() {
        revision++
        playbackToken = null
        playbackFromAlarm = false
        effects.cancelPlaybackChecks()
        effects.stopAudio()
        playingId = null
    }

    private suspend fun load() {
        if (!loaded) {
            mutableState.value = store.read()
            if (mutableState.value == null) effects.clearOrphans()
            loaded = true
        }
    }

    private suspend fun persist(next: RestTimerState) {
        store.write(next) // Never publish success or start effects before this succeeds.
        val previous = state.value
        if (previous == null || previous.timerId != next.timerId || previous.generation != next.generation ||
            previous.status != next.status || previous.audioSuppressed != next.audioSuppressed) invalidatePlayback()
        mutableState.value = next
        mutableError.value = null
        effects.trace("state_committed", next, "revision=$revision previous=${previous?.status}")
    }

    private suspend fun guarded(block: suspend () -> Unit) = mutex.withLock {
        try {
            load()
            pendingTerminal?.let {
                persist(it)
                cleanup(it)
                pendingTerminal = null
            }
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A DataStore update can commit just before cancellation is observed. Reload its
            // authority before any later alarm/extend uses the in-memory identity again.
            loaded = false
            invalidatePlayback()
            throw e
        } catch (e: Exception) {
            // Stop effects even if a terminal write failed. Keep durable state for retry.
            invalidatePlayback()
            pendingTerminal?.let { cleanup(it) }
            mutableError.value = "TIMER_STORAGE_OR_RECOVERY_FAILED"
            effects.trace("recovery_failed", state.value, "exception=${e.javaClass.simpleName}")
        }
    }

    suspend fun start(request: RestTimerState) = guarded {
        effects.trace("start_requested", request)
        val old = state.value
        if (old?.workoutId == request.workoutId && (old.setId == request.setId || request.setId in old.startedSetIds)) return@guarded
        if (!effects.validSession(request)) return@guarded
        val next = request.copy(
            timerId = UUID.randomUUID().toString(),
            startedAtEpochMs = clock.epochMillis(),
            deadlineElapsedMs = clock.elapsedRealtime() + request.durationMs.coerceIn(0, 86_400_000),
            bootIdentity = clock.bootIdentity(),
            status = TimerStatus.RUNNING,
            generation = 0,
            audioSuppressed = false,
            reason = null,
            schedulingMode = SchedulingMode.UNAVAILABLE,
            startedSetIds = (old?.takeIf { it.workoutId == request.workoutId }?.startedSetIds.orEmpty() + request.setId).distinct()
        )
        persist(next)
        old?.let(effects::cancelAlarm)
        scheduledIdentity = null
        effects.clearDismissed()
        effects.stopAudio()
        playingId = null
        effects.dismissFinished()
        reconcileLocked()
    }

    suspend fun reconcile() = guarded { reconcileLocked() }
    /** Read persisted identity without turning a stale activity intent into an expiry trigger. */
    suspend fun restore() = guarded { }

    suspend fun whenRestInactive(block: suspend () -> Unit): Boolean = mutex.withLock {
        try {
            load()
            reconcileLocked()
            if (state.value?.status == TimerStatus.RUNNING) false
            else { block(); true }
        } catch (e: kotlinx.coroutines.CancellationException) {
            loaded = false
            invalidatePlayback()
            throw e
        } catch (_: Exception) {
            effects.stopAudio()
            mutableError.value = "TIMER_STORAGE_OR_RECOVERY_FAILED"
            false
        }
    }

    private suspend fun reconcileLocked(alarmDelivery: Boolean = false): Boolean {
        effects.trace("reconcile_enter", state.value, "source=${if (alarmDelivery) "alarm" else "reconcile"}")
        var current = state.value ?: run {
            effects.stopAudio()
            effects.dismissFinished()
            effects.clearOrphans()
            return true
        }
        if (current.status == TimerStatus.RUNNING || current.status == TimerStatus.EXPIRED) {
            val reason = when {
                current.bootIdentity == null || current.bootIdentity != clock.bootIdentity() -> "BOOT_UNVERIFIED"
                effects.userStopped(current) -> "USER_STOPPED"
                !effects.validSession(current) -> "SESSION_INVALID"
                else -> null
            }
            if (reason != null) {
                current = current.copy(status = TimerStatus.CANCELLED, reason = reason)
                terminal(current)
            }
        }
        if (current.status == TimerStatus.RUNNING && clock.elapsedRealtime() >= current.deadlineElapsedMs) {
            effects.trace("expiry_decided", current)
            current = current.copy(status = TimerStatus.EXPIRED)
            persist(current)
            effects.trace("expired_committed", current)
        }
        renderLocked(current)
        return when (current.status) {
            TimerStatus.RUNNING -> {
                effects.stopAudio()
                playingId = null
                effects.dismissFinished()
                val identity = current.timerId to current.generation
                if (scheduledIdentity != identity) {
                    val mode = effects.schedule(current)
                    persist(current.copy(schedulingMode = mode))
                    // UNAVAILABLE is retried only after an explicit capability refresh.
                    scheduledIdentity = identity
                }
                true
            }
            TimerStatus.EXPIRED -> {
                effects.cancelAlarm(current)
                scheduledIdentity = null
                val presentation = try {
                    effects.showFinished(current)
                } catch (e: Exception) {
                    effects.trace("notify_failed", current, "exception=${e.javaClass.simpleName}")
                    FinishedPresentation.RETRY
                }
                // A transient post failure must not durably suppress playback before recovery.
                if (presentation == FinishedPresentation.RETRY) return false
                if (alarmDelivery && !current.audioSuppressed) {
                    playbackFromAlarm = true
                    effects.enqueuePlaybackCheck(ensurePlaybackToken(current))
                } else updatePlaybackLocked(current)
                true
            }
            else -> { cleanup(current); true }
        }
    }

    private fun cleanup(current: RestTimerState) {
        effects.cancelAlarm(current)
        scheduledIdentity = null
        invalidatePlayback()
        effects.dismissFinished()
        renderLocked(current)
    }

    private fun renderLocked(current: RestTimerState?, session: WorkoutSession? = null) {
        try {
            effects.renderWorkout(current, session)
        } catch (e: Exception) {
            effects.trace("render_failed", current, "exception=${e.javaClass.simpleName}")
        }
    }

    /** Receiver and ticker use the same lock and latest rest state for notification decisions. */
    suspend fun renderWorkout(session: WorkoutSession) = guarded {
        if (state.value?.let { it.workoutId != session.id && it.status == TimerStatus.RUNNING } == true) return@guarded
        // Session input was read outside this lock; verify it again rather than posting that snapshot.
        if (effects.workoutSessionIsCurrent(session)) renderLocked(state.value)
    }

    private fun ensurePlaybackToken(current: RestTimerState): PlaybackToken =
        playbackToken ?: PlaybackToken(current.timerId, current.generation, revision).also {
            playbackToken = it
            playbackRequestedAtMs = clock.elapsedRealtime()
        }

    private suspend fun updatePlaybackLocked(current: RestTimerState) {
        if (current.audioSuppressed || pendingTerminal != null) return
        val token = ensurePlaybackToken(current)
        if (playingId == null && clock.elapsedRealtime() - playbackRequestedAtMs >= 5000) {
            suppressPlayback(current, "READINESS_TIMEOUT")
            return
        }
        when (effects.playbackReadiness(current, token, playbackFromAlarm)) {
            PlaybackReadiness.WAITING -> effects.trace("playback_waiting", current, "revision=${token.revision}")
            PlaybackReadiness.READY -> if (playingId != current.timerId) {
                effects.trace("playback_ready", current, "revision=${token.revision}")
                val started = try { effects.startAudio() } catch (e: Exception) {
                    effects.trace("audio_start_failed", current, "exception=${e.javaClass.simpleName}")
                    false
                }
                if (started) playingId = current.timerId
                else suppressPlayback(current, "AUDIO_START_FAILED")
            }
            PlaybackReadiness.FAILED_PERMANENT -> suppressPlayback(current, "READINESS_FAILED")
        }
    }

    private suspend fun suppressPlayback(current: RestTimerState, reason: String) {
        invalidatePlayback() // Honor failure immediately, even when the suppression write fails.
        effects.trace("playback_failed", current, "reason=$reason")
        pendingTerminal = current.copy(audioSuppressed = true, reason = reason)
        persist(pendingTerminal!!)
        pendingTerminal = null
    }

    private fun matchesPlayback(token: PlaybackToken): Boolean = playbackToken == token && pendingTerminal == null &&
        state.value?.let { it.matchesExpired(token.timerId, token.generation) && !it.audioSuppressed &&
            it.bootIdentity != null && it.bootIdentity == clock.bootIdentity() } == true

    /** Application-owned supervisor polls readiness; never prolongs a receiver's lifetime. */
    suspend fun checkPlayback(token: PlaybackToken) = guarded {
        if (!matchesPlayback(token)) return@guarded
        val current = state.value ?: return@guarded
        if (playingId == null && clock.elapsedRealtime() - playbackRequestedAtMs >= 5000) {
            suppressPlayback(current, "READINESS_TIMEOUT")
            return@guarded
        }
        if (effects.userStopped(current) || !effects.validSession(current)) {
            terminal(current.copy(status = TimerStatus.CANCELLED, reason = "SESSION_INVALID"))
        } else updatePlaybackLocked(current)
    }

    suspend fun playbackServiceResult(token: PlaybackToken, ready: Boolean) = guarded {
        if (!matchesPlayback(token)) return@guarded
        val current = state.value!!
        if (!ready) suppressPlayback(current, "FGS_START_OR_PROMOTION_FAILED")
        else if (effects.userStopped(current) || !effects.validSession(current)) {
            terminal(current.copy(status = TimerStatus.CANCELLED, reason = "SESSION_INVALID"))
        } else updatePlaybackLocked(current)
    }

    suspend fun interruptPlayback(token: PlaybackToken) = guarded {
        if (matchesPlayback(token)) suppressPlayback(state.value!!, "PLAYBACK_INTERRUPTED")
    }

    suspend fun acknowledge(timerId: String, generation: Long? = null) = guarded {
        val current = state.value ?: return@guarded
        if (current.timerId != timerId) return@guarded
        if (generation != null && current.generation != generation) return@guarded
        // Mark first: a concurrent reconcile must never repost a card the user dismissed.
        effects.noteUserDismissed(timerId)
        effects.trace("acknowledge", current)
        if (current.status == TimerStatus.EXPIRED) terminal(current.copy(status = TimerStatus.ACKNOWLEDGED))
        if (state.value?.status == TimerStatus.ACKNOWLEDGED) cleanup(current)
    }

    /** Optimistic user-dismissal mark ahead of acknowledge; never changes timer state. */
    suspend fun noteDismissed(timerId: String) = guarded { effects.noteUserDismissed(timerId) }

    suspend fun skip(timerId: String? = null) = guarded {
        val current = state.value ?: return@guarded
        if (timerId != null && timerId != current.timerId) return@guarded
        if (current.status == TimerStatus.RUNNING) terminal(current.copy(status = TimerStatus.SKIPPED))
        reconcileLocked()
    }

    suspend fun cancel(reason: String, endWorkout: Boolean = false) = guarded {
        if (endWorkout) effects.dismissWorkout()
        val current = state.value ?: run {
            invalidatePlayback()
            effects.dismissFinished()
            effects.clearOrphans()
            return@guarded
        }
        terminal(current.copy(status = TimerStatus.CANCELLED, reason = reason))
        cleanup(current)
    }

    private suspend fun terminal(next: RestTimerState) {
        pendingTerminal = next
        // Even on disk failure, honor Stop immediately and retry persistence on reconcile.
        cleanup(next)
        persist(next)
        pendingTerminal = null
    }

    suspend fun extend(timerId: String? = null, generation: Long? = null) = guarded {
        val current = state.value ?: return@guarded
        if (timerId != null && timerId != current.timerId) return@guarded
        if (generation != null && generation != current.generation) return@guarded
        if (current.status != TimerStatus.RUNNING || clock.elapsedRealtime() >= current.deadlineElapsedMs) {
            reconcileLocked()
            return@guarded
        }
        persist(current.copy(deadlineElapsedMs = current.deadlineElapsedMs + 30_000, generation = current.generation + 1))
        effects.cancelAlarm(current)
        scheduledIdentity = null
        reconcileLocked()
    }

    suspend fun expiry(timerId: String, generation: Long, alarmDelivery: Boolean = false): AlarmDeliveryResult {
        // guarded records ordinary read/write failures rather than throwing them. Do not
        // mistake that for a successful delivery and discard the durable recovery work.
        var result = AlarmDeliveryResult.RETRY
        guarded {
            val current = state.value ?: run {
                reconcileLocked()
                result = AlarmDeliveryResult.HANDLED
                return@guarded
            }
            if (current.timerId == timerId && current.generation == generation) {
                // A delivered early alarm is consumed by the system; re-arm instead of caching that schedule.
                if (current.status == TimerStatus.RUNNING && clock.elapsedRealtime() < current.deadlineElapsedMs) scheduledIdentity = null
                result = if (reconcileLocked(alarmDelivery)) AlarmDeliveryResult.HANDLED else AlarmDeliveryResult.RETRY
            } else {
                effects.trace("stale_alarm_ignored", current, "receivedTimer=$timerId receivedGeneration=$generation")
                // A process/caller can die after an extend commit but before replacing its system
                // alarm. Repair only the authoritative schedule; this event never expires it.
                val identity = current.timerId to current.generation
                if (current.timerId == timerId && current.generation > generation && current.status == TimerStatus.RUNNING &&
                    scheduledIdentity != identity && current.bootIdentity != null && current.bootIdentity == clock.bootIdentity() &&
                    effects.validSession(current)) {
                    effects.cancelAlarm(current.copy(generation = generation))
                    val mode = effects.schedule(current)
                    persist(current.copy(schedulingMode = mode))
                    scheduledIdentity = identity
                    effects.trace("alarm_schedule_recovered", state.value)
                }
                result = AlarmDeliveryResult.HANDLED
            }
        }
        return result
    }

    suspend fun capabilitiesChanged() = guarded {
        scheduledIdentity = null
        reconcileLocked()
    }

    suspend fun silence(timerId: String? = null) = guarded {
        val current = state.value ?: return@guarded
        if (timerId != null && timerId != current.timerId) return@guarded
        if (current.status == TimerStatus.EXPIRED && !current.audioSuppressed) suppressPlayback(current, "USER_SILENCED")
    }

    /** Process-resource cleanup only: the system alarm and persisted timer survive. */
    suspend fun releasePlayback() = guarded {
        invalidatePlayback()
    }
}
