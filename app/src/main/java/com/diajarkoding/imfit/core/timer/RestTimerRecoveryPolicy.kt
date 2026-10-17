package com.diajarkoding.imfit.core.timer

/** Product bounds, not Android delivery guarantees. Deadlines themselves are never shifted. */
object RestTimerRecoveryPolicy {
    const val MAX_ALERT_LATENESS_MS = 5 * 60_000L
    const val MAX_WORK_AGE_MS = 10 * 60_000L
    const val WORK_TIMEOUT_MS = 15_000L
    private const val MAX_ATTEMPTS = 12

    fun alertTooLate(state: RestTimerState, now: Long): Boolean =
        !state.presentationHandled && now > state.deadlineElapsedMs &&
            now - state.deadlineElapsedMs > MAX_ALERT_LATENESS_MS

    fun canRetry(attempt: Int, enqueuedAt: Long, now: Long): Boolean =
        // Safety bound also covers old persisted work lacking an enqueue timestamp or a reboot.
        attempt < MAX_ATTEMPTS && (enqueuedAt < 0 || now < enqueuedAt || now - enqueuedAt < MAX_WORK_AGE_MS)
}
