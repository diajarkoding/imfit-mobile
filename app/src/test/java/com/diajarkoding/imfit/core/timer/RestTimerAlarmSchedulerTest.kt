package com.diajarkoding.imfit.core.timer

import org.junit.Assert.*
import org.junit.Test

class RestTimerAlarmSchedulerTest {
    private val state = RestTimerState("rest", generation = 2, userId = "owner", workoutId = "workout",
        templateId = "template", exerciseId = "exercise", setId = "set", exerciseName = "Exercise",
        durationMs = 120_000, deadlineElapsedMs = 125_000, bootIdentity = 1, startedAtEpochMs = 0)

    private class Fixture {
        var allowed = true
        var exactFailure: RuntimeException? = null
        var inexactFailure: RuntimeException? = null
        val exactRequests = mutableListOf<RestTimerState>()
        val inexactRequests = mutableListOf<RestTimerState>()
        var detail = ""
        val scheduler = RestTimerAlarmScheduler({ allowed }, {
            exactRequests.add(it); exactFailure?.let { error -> throw error }
        }, {
            inexactRequests.add(it); inexactFailure?.let { error -> throw error }
        }, { 5_000 }, { _, _, fields -> detail = fields })
    }

    @Test fun exactUsesUnchangedDeadlineAndGenerationAndLogsAppliedMethod() {
        val f = Fixture()
        assertEquals(SchedulingMode.EXACT, f.scheduler.schedule(state))
        assertEquals(listOf(state), f.exactRequests)
        assertTrue(f.inexactRequests.isEmpty())
        assertTrue(f.detail.contains("requestedMethod=EXACT_ALLOW_IDLE appliedMethod=EXACT_ALLOW_IDLE"))
        assertTrue(f.detail.contains("fallbackReason=NONE"))
        assertTrue(f.detail.contains("deadlineElapsedMs=125000"))
    }

    @Test fun securityExceptionFallsBackWithSameIdentityAndLogsReasonNotMessage() {
        val f = Fixture(); f.exactFailure = SecurityException("private message")
        assertEquals(SchedulingMode.BEST_EFFORT, f.scheduler.schedule(state))
        assertEquals(listOf(state), f.exactRequests)
        assertEquals(listOf(state), f.inexactRequests)
        assertTrue(f.detail.contains("appliedMethod=INEXACT_ALLOW_IDLE"))
        assertTrue(f.detail.contains("fallbackReason=SECURITY_EXCEPTION exceptionType=SecurityException"))
        assertFalse(f.detail.contains("private message"))
    }

    @Test fun deniedExactAccessCallsOnlyInexact() {
        val f = Fixture(); f.allowed = false
        assertEquals(SchedulingMode.BEST_EFFORT, f.scheduler.schedule(state))
        assertTrue(f.exactRequests.isEmpty())
        assertEquals(listOf(state), f.inexactRequests)
        assertTrue(f.detail.contains("exactAllowed=false fallbackReason=EXACT_ACCESS_DENIED"))
    }

    @Test fun failedFallbackIsUnavailableAndNeverLoggedAsApplied() {
        val f = Fixture(); f.exactFailure = SecurityException(); f.inexactFailure = IllegalStateException()
        assertEquals(SchedulingMode.UNAVAILABLE, f.scheduler.schedule(state))
        assertTrue(f.detail.contains("appliedMethod=NONE"))
        assertTrue(f.detail.contains("exceptionType=IllegalStateException"))
    }

    @Test fun ordinaryRuntimeFailurePreservesExistingNoFallbackPolicy() {
        val f = Fixture(); f.exactFailure = IllegalStateException()
        assertEquals(SchedulingMode.UNAVAILABLE, f.scheduler.schedule(state))
        assertTrue(f.inexactRequests.isEmpty())
        assertTrue(f.detail.contains("appliedMethod=NONE"))
    }
}
