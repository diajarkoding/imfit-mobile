package com.diajarkoding.imfit.core.timer

import com.diajarkoding.imfit.domain.model.WorkoutSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout

/** One serialized service iteration, extracted so session read/backoff can be fault-tested. */
class RestTimerTickerIteration(
    private val readSession: suspend () -> WorkoutSession?,
    private val reconcile: suspend () -> Unit,
    private val render: suspend (WorkoutSession) -> Unit,
    private val absent: suspend () -> Unit,
    private val now: () -> Long,
    val retry: RestTimerRetry = RestTimerRetry(),
    private val readTimeoutMs: Long = 2500,
    private val failure: (String, Exception, Long) -> Unit = { _, _, _ -> },
    private val recovered: () -> Unit = {}
) {
    suspend fun tick(): Boolean {
        // Expiry is independent of rendering-session availability. A full-session read
        // failure/backoff must never skip the authoritative deadline check.
        try {
            reconcile()
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            failure("expiry_reconcile", e, 0L)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure("expiry_reconcile", e, 0L)
        }
        if (!retry.available(now())) return true
        var stage = "session_read"
        try {
            val session = withTimeout(readTimeoutMs) { readSession() }
            if (session == null) { absent(); return false }
            stage = "workout_render"
            render(session)
            if (retry.failures > 0) recovered()
            retry.reset()
        } catch (e: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            failure(stage, e, retry.failed(now()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure(stage, e, retry.failed(now()))
        }
        return true
    }
}
