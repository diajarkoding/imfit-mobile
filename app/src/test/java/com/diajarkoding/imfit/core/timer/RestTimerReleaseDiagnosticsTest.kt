package com.diajarkoding.imfit.core.timer

import org.junit.Assert.*
import org.junit.Test

class RestTimerReleaseDiagnosticsTest {
    @Test fun scheduleEvidenceRetainsMethodFallbackAndMonotonicTimesWithoutAccountIds() {
        val safe = RestTimerReleaseDiagnostics.detail("alarm_scheduled",
            "source=scheduler requestedMethod=EXACT_ALLOW_IDLE appliedMethod=INEXACT_ALLOW_IDLE " +
                "exactAllowed=true fallbackReason=SECURITY_EXCEPTION exceptionType=SecurityException " +
                "scheduledAtElapsedMs=1000 deadlineElapsedMs=121000 mode=BEST_EFFORT userId=private")
        assertNotNull(safe)
        assertTrue(safe!!.contains("appliedMethod=INEXACT_ALLOW_IDLE"))
        assertTrue(safe.contains("fallbackReason=SECURITY_EXCEPTION"))
        assertTrue(safe.contains("scheduledAtElapsedMs=1000 deadlineElapsedMs=121000"))
        assertFalse(safe.contains("private"))
    }

    @Test fun opaqueCorrelationIsStableAcrossCallersAndDoesNotExposeTheTimerId() {
        val id = RestTimerCorrelation.instanceId("private-timer-id")
        assertEquals(12, id.length)
        assertEquals(id, RestTimerCorrelation.instanceId("private-timer-id"))
        assertNotEquals(id, RestTimerCorrelation.instanceId("another-timer-id"))
        assertFalse(id.contains("private"))
    }

    @Test fun releaseAllowlistDropsIdentifiersNamesAndMessages() {
        val safe = RestTimerReleaseDiagnostics.detail("operation_failed",
            "stage=session_identity durationMs=2500 exception=TimeoutCancellationException " +
                "receivedTimer=private-timer userId=private-user workoutId=private-workout exerciseName=Private message=secret")
        assertEquals("stage=session_identity durationMs=2500 exception=TimeoutCancellationException", safe)
        assertEquals("result=RETRY attempt=4", RestTimerReleaseDiagnostics.detail("recovery_worker_result",
            "receivedTimer=private-timer receivedGeneration=3 result=RETRY attempt=4"))
        assertNull(RestTimerReleaseDiagnostics.detail("workout_rendered", "notificationId=1001"))
        assertEquals("notificationId=1002 success=false exception=IllegalStateException",
            RestTimerReleaseDiagnostics.detail("notify_result",
                "notificationId=1002 success=false exception=IllegalStateException timerId=private-timer"))
    }

    @Test fun repeatedFailureIsThrottledButDifferentPhaseAndWorkerOutcomeAreNot() {
        assertTrue(RestTimerReleaseDiagnostics.shouldLog("operation_failed", "stage=test_phase", 1000))
        assertFalse(RestTimerReleaseDiagnostics.shouldLog("operation_failed", "stage=test_phase", 2000))
        assertTrue(RestTimerReleaseDiagnostics.shouldLog("operation_failed", "stage=another_test_phase", 2000))
        assertTrue(RestTimerReleaseDiagnostics.shouldLog("operation_failed", "stage=test_phase", 31_000))
        repeat(3) { assertTrue(RestTimerReleaseDiagnostics.shouldLog("recovery_worker_result", "result=RETRY", 1000)) }
    }
}
