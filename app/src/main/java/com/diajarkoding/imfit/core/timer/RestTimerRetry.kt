package com.diajarkoding.imfit.core.timer

/** One retry lane per operation. A render failure never backs off expiry or session reads. */
class RestTimerRetry {
    private var retryAt = 0L
    private var delayMs = 1000L
    var failures = 0
        private set

    fun available(now: Long) = now >= retryAt
    fun failed(now: Long): Long {
        failures++
        val delay = delayMs
        retryAt = now + delay
        delayMs = (delayMs * 2).coerceAtMost(30_000)
        return delay
    }
    fun reset() {
        retryAt = 0L
        delayMs = 1000L
        failures = 0
    }
}
