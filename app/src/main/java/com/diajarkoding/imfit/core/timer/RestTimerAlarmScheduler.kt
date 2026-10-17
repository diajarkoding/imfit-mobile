package com.diajarkoding.imfit.core.timer

/** Testable method selection. A successful API call is NOT proof of the system's stored window. */
class RestTimerAlarmScheduler(
    private val exactAllowed: () -> Boolean,
    private val exact: (RestTimerState) -> Unit,
    private val inexact: (RestTimerState) -> Unit,
    private val now: () -> Long,
    private val trace: (String, RestTimerState, String) -> Unit
) {
    fun schedule(state: RestTimerState): SchedulingMode {
        val scheduledAt = now()
        var requested = "UNKNOWN"
        var applied = "NONE"
        var allowed = false
        var fallback = "NONE"
        var exception = "NONE"
        val mode = try {
            allowed = exactAllowed()
            requested = if (allowed) "EXACT_ALLOW_IDLE" else "INEXACT_ALLOW_IDLE"
            if (allowed) {
                exact(state)
                applied = "EXACT_ALLOW_IDLE"
                SchedulingMode.EXACT
            } else {
                fallback = "EXACT_ACCESS_DENIED"
                inexact(state)
                applied = "INEXACT_ALLOW_IDLE"
                SchedulingMode.BEST_EFFORT
            }
        } catch (e: SecurityException) {
            exception = e.javaClass.simpleName
            fallback = "SECURITY_EXCEPTION"
            try {
                inexact(state)
                applied = "INEXACT_ALLOW_IDLE"
                SchedulingMode.BEST_EFFORT
            } catch (fallbackError: RuntimeException) {
                exception = fallbackError.javaClass.simpleName
                SchedulingMode.UNAVAILABLE
            }
        } catch (e: RuntimeException) {
            exception = e.javaClass.simpleName
            SchedulingMode.UNAVAILABLE
        }
        trace("alarm_scheduled", state, "source=scheduler requestedMethod=$requested appliedMethod=$applied " +
            "exactAllowed=$allowed fallbackReason=$fallback exceptionType=$exception mode=$mode " +
            "scheduledAtElapsedMs=$scheduledAt deadlineElapsedMs=${state.deadlineElapsedMs}")
        return mode
    }
}
