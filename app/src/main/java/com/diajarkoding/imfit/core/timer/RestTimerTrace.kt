package com.diajarkoding.imfit.core.timer

import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.diajarkoding.imfit.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

/** Boundary events only; elapsed time is authoritative for latency measurements. */
object RestTimerTrace {
    /** Stable across process recovery, without logging persisted timer/account identifiers. */
    fun instanceId(timerId: String): String = RestTimerCorrelation.instanceId(timerId)

    fun event(event: String, state: RestTimerState? = null, detail: String = "") {
        val now = SystemClock.elapsedRealtime()
        if (!BuildConfig.DEBUG) {
            val safe = RestTimerReleaseDiagnostics.detail(event, detail) ?: return
            if (!RestTimerReleaseDiagnostics.shouldLog(event, safe, now)) return
            // No user/workout/timer IDs, names, payloads, or exception messages in release logs.
            Log.i("IMFITRest", "event=$event wallMs=${System.currentTimeMillis()} api=${android.os.Build.VERSION.SDK_INT} " +
                "pid=${Process.myPid()} now=$now uptimeMs=${SystemClock.uptimeMillis()} " +
                "restInstanceId=${state?.let { instanceId(it.timerId) }} status=${state?.status} generation=${state?.generation} " +
                "deadlineElapsedMs=${state?.deadlineElapsedMs} " +
                "latenessMs=${state?.let { (now - it.deadlineElapsedMs).coerceAtLeast(0) }} $safe")
            return
        }
        Log.d("IMFITRest", "event=$event wallMs=${System.currentTimeMillis()} api=${android.os.Build.VERSION.SDK_INT} pid=${Process.myPid()} now=$now " +
            "uptimeMs=${SystemClock.uptimeMillis()} restInstanceId=${state?.let { instanceId(it.timerId) }} deadlineElapsedMs=${state?.deadlineElapsedMs} " +
            "timer=${state?.timerId} generation=${state?.generation} status=${state?.status} " +
            "boot=${state?.bootIdentity} deadline=${state?.deadlineElapsedMs} " +
            "remainingMs=${state?.let { (it.deadlineElapsedMs - now).coerceAtLeast(0) }} " +
            "latenessMs=${state?.let { (now - it.deadlineElapsedMs).coerceAtLeast(0) }} $detail")
    }

    suspend fun <T> measured(stage: String, state: RestTimerState? = null, block: suspend () -> T): T {
        val started = SystemClock.elapsedRealtime()
        try {
            return block().also {
                event("operation_result", state, "stage=$stage durationMs=${SystemClock.elapsedRealtime() - started}")
            }
        } catch (e: Exception) {
            if (e !is CancellationException || e is TimeoutCancellationException) {
                event("operation_failed", state,
                    "stage=$stage durationMs=${SystemClock.elapsedRealtime() - started} exception=${e.javaClass.simpleName}")
            }
            throw e
        }
    }
}

/** Explicit allowlists prevent accidentally promoting debug identifiers or free text to release. */
internal object RestTimerReleaseDiagnostics {
    private val failures = setOf("operation_failed", "recovery_failed", "notify_failed", "notify_blocked", "ticker_backoff")
    private val lastFailure = mutableMapOf<String, Long>()
    private val events = setOf("receiver_received", "receiver_timeout", "receiver_failed", "receiver_result",
        "terminal_decided", "recovery_enqueued", "recovery_enqueue_failed", "recovery_cancel_failed", "recovery_worker_result",
        "recovery_worker_timeout", "recovery_worker_failed", "recovery_exhausted", "recovery_failed",
        "operation_failed", "delivery_window_expired", "expired_committed", "notify_result", "notify_failed", "notify_blocked",
        "auth_status_snapshot",
        "alarm_scheduled", "alarm_cancelled", "rest_started", "rest_extended", "expiry_requested", "expiry_lock_acquired",
        "receiver_enter", "receiver_rejected", "activity_resumed", "activity_paused", "service_created", "service_start",
        "fgs_promoted", "fgs_promotion_failed", "ui_fgs_start_failed", "service_destroyed", "ticker_started",
        "ticker_heartbeat", "ticker_cancelled", "ticker_completed", "ticker_gap", "ticker_due", "ticker_backoff",
        "ticker_recovery_failed", "ticker_reconcile_failed", "session_read_recovered")
    private val keys = setOf("stage", "durationMs", "exception", "attempt", "result", "budgetMs",
        "notificationId", "notificationsEnabled", "success", "retryMs", "reason", "restInstanceId", "generation",
        "source", "requestedMethod", "appliedMethod", "exactAllowed", "fallbackReason", "exceptionType", "mode",
        "scheduledAtElapsedMs", "deadlineElapsedMs", "receivedDeadlineElapsedMs", "gapElapsedMs", "gapUptimeMs",
        "readBackoff", "running", "type", "action", "authStatus")
    private val field = Regex("([A-Za-z]+)=([A-Za-z0-9_.-]+)")

    fun detail(event: String, detail: String): String? {
        if (event !in events) return null
        return field.findAll(detail).filter { it.groupValues[1] in keys }.joinToString(" ") { it.value }
    }

    @Synchronized
    fun shouldLog(event: String, safeDetail: String, now: Long): Boolean {
        if (event !in failures) return true
        val stage = field.findAll(safeDetail).firstOrNull { it.groupValues[1] == "stage" }?.groupValues?.get(2)
        val key = "$event:$stage"
        val last = lastFailure[key]
        if (last != null && now >= last && now - last < 30_000) return false
        lastFailure[key] = now
        return true
    }
}
