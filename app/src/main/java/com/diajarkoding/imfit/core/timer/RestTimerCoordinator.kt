package com.diajarkoding.imfit.core.timer

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class TimerStatus { RUNNING, EXPIRED, ACKNOWLEDGED, SKIPPED, CANCELLED }
@Serializable
enum class SchedulingMode { EXACT, BEST_EFFORT, UNAVAILABLE }

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
    fun showFinished(state: RestTimerState)
    fun dismissFinished()
    fun controlsAvailable(): Boolean
    fun controlsPending(): Boolean = false
    fun noteUserDismissed(timerId: String) {}
    fun clearDismissed() {}
    fun startAudio(): Boolean
    fun stopAudio()
    fun clearOrphans() {}
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

    private suspend fun load() {
        if (!loaded) {
            mutableState.value = store.read()
            if (mutableState.value == null) effects.clearOrphans()
            loaded = true
        }
    }

    private suspend fun persist(next: RestTimerState) {
        store.write(next) // Never publish success or start effects before this succeeds.
        mutableState.value = next
        mutableError.value = null
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
            throw e
        } catch (e: Exception) {
            // Stop effects even if a terminal write failed. Keep durable state for retry.
            effects.stopAudio()
            playingId = null
            pendingTerminal?.let { cleanup(it) }
            mutableError.value = "TIMER_STORAGE_OR_RECOVERY_FAILED"
        }
    }

    suspend fun start(request: RestTimerState) = guarded {
        val old = state.value
        if (old?.workoutId == request.workoutId && (old.setId == request.setId || request.setId in old.startedSetIds)) return@guarded
        if (!effects.validSession(request)) return@guarded
        val next = request.copy(
            timerId = UUID.randomUUID().toString(),
            startedAtEpochMs = clock.epochMillis(),
            deadlineElapsedMs = clock.elapsedRealtime() + request.durationMs.coerceIn(0, 86_400_000),
            bootIdentity = clock.bootIdentity(),
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
            throw e
        } catch (_: Exception) {
            effects.stopAudio()
            mutableError.value = "TIMER_STORAGE_OR_RECOVERY_FAILED"
            false
        }
    }

    private suspend fun reconcileLocked() {
        var current = state.value ?: run {
            effects.stopAudio()
            effects.dismissFinished()
            effects.clearOrphans()
            return
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
            current = current.copy(status = TimerStatus.EXPIRED)
            persist(current)
        }
        when (current.status) {
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
            }
            TimerStatus.EXPIRED -> {
                effects.cancelAlarm(current)
                scheduledIdentity = null
                effects.showFinished(current)
                if (!effects.controlsAvailable()) {
                    effects.stopAudio()
                    playingId = null
                    if (!effects.controlsPending() && !current.audioSuppressed) persist(current.copy(audioSuppressed = true))
                } else if (!current.audioSuppressed && playingId != current.timerId) {
                    if (effects.startAudio()) playingId = current.timerId
                    else persist(current.copy(audioSuppressed = true))
                }
            }
            else -> cleanup(current)
        }
    }

    private fun cleanup(current: RestTimerState) {
        effects.cancelAlarm(current)
        scheduledIdentity = null
        effects.stopAudio()
        playingId = null
        effects.dismissFinished()
    }

    suspend fun acknowledge(timerId: String, generation: Long? = null) = guarded {
        val current = state.value ?: return@guarded
        if (current.timerId != timerId) return@guarded
        if (generation != null && current.generation != generation) return@guarded
        // Mark first: a concurrent reconcile must never repost a card the user dismissed.
        effects.noteUserDismissed(timerId)
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

    suspend fun cancel(reason: String) = guarded {
        val current = state.value ?: return@guarded
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

    suspend fun expiry(timerId: String, generation: Long) = guarded {
        val current = state.value ?: return@guarded
        if (current.timerId == timerId && current.generation == generation) reconcileLocked()
    }

    suspend fun capabilitiesChanged() = guarded {
        scheduledIdentity = null
        reconcileLocked()
    }

    suspend fun silence(timerId: String? = null) = guarded {
        val current = state.value ?: return@guarded
        if (timerId != null && timerId != current.timerId) return@guarded
        effects.stopAudio()
        playingId = null
        if (current.status == TimerStatus.EXPIRED && !current.audioSuppressed) persist(current.copy(audioSuppressed = true))
    }

    /** Process-resource cleanup only: the system alarm and persisted timer survive. */
    fun releasePlayback() {
        effects.stopAudio()
        playingId = null
    }
}
