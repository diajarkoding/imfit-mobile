package com.diajarkoding.imfit.core.timer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

interface RestTimerRecoveryQueue {
    /** Return only after the recovery request is durably enqueued. */
    suspend fun enqueue(timerId: String, generation: Long)
    fun cancel(timerId: String, generation: Long)
}

/** The system alarm is one-shot. Arm recovery before any suspendable session/store read. */
class RestTimerAlarmDelivery(
    private val timer: RestTimerCoordinator,
    private val recovery: RestTimerRecoveryQueue,
    private val timeoutMs: Long = 4000,
    private val trace: (String, String) -> Unit = { _, _ -> }
) {
    suspend fun receive(timerId: String, generation: Long): AlarmDeliveryResult {
        var result = AlarmDeliveryResult.RETRY
        val identity = "receivedTimer=$timerId receivedGeneration=$generation"
        try {
            withTimeout(timeoutMs) {
                try {
                    recovery.enqueue(timerId, generation)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Still attempt the primary delivery if WorkManager itself is unavailable.
                    trace("recovery_enqueue_failed", "$identity exception=${e.javaClass.simpleName}")
                }
                result = timer.expiry(timerId, generation, alarmDelivery = true)
            }
        } catch (e: TimeoutCancellationException) {
            trace("receiver_timeout", identity)
        } catch (e: CancellationException) {
            throw e // Leave the durable request in place on process/owner cancellation.
        } catch (e: Exception) {
            trace("receiver_failed", "$identity exception=${e.javaClass.simpleName}")
        }
        if (result == AlarmDeliveryResult.HANDLED) {
            try {
                recovery.cancel(timerId, generation)
            } catch (e: Exception) {
                // A redundant worker validates the identity and is safe; do not fail the receiver.
                trace("recovery_cancel_failed", "$identity exception=${e.javaClass.simpleName}")
            }
        }
        trace("receiver_result", "$identity result=$result")
        return result
    }
}
