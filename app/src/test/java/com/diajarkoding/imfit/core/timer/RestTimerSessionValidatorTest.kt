package com.diajarkoding.imfit.core.timer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RestTimerSessionValidatorTest {
    private val state = RestTimerState("timer", userId = "owner", workoutId = "workout", templateId = "template",
        exerciseId = "exercise", setId = "set", exerciseName = "Exercise", durationMs = 120_000,
        deadlineElapsedMs = 120_000, bootIdentity = 1, startedAtEpochMs = 0)

    private fun validator(
        peek: suspend () -> String? = { "owner" },
        full: suspend () -> String? = { "owner" },
        sessionExists: suspend (String, String) -> Boolean = { _, _ -> true },
        timeoutMs: Long = 2500,
        measure: suspend (String, suspend () -> Boolean) -> Boolean = { _, block -> block() }
    ) = RestTimerSessionValidator(
        localUserId = full, peekUserId = peek, sessionExists = sessionExists,
        timeoutMs = timeoutMs, measure = measure)

    @Test fun validatesOnlyIdentityAndRechecksAuthAfterRoom() = runBlocking {
        var fullReads = 0
        var peekReads = 0
        val v = validator(
            peek = { peekReads++; "owner" },
            full = { fullReads++; "owner" },
            sessionExists = { user, workout ->
                assertEquals("owner", user); assertEquals("workout", workout); true
            })
        assertTrue(v.valid(state))
        assertEquals(1, peekReads)
        assertEquals(1, fullReads) // recheck after Room
    }

    @Test fun fastPathHitSkipsInitWait() = runBlocking {
        var fullReads = 0
        val v = validator(peek = { "owner" }, full = { fullReads++; "owner" })
        assertTrue(v.valid(state))
        assertEquals(1, fullReads) // only the post-Room recheck
    }

    @Test fun fastMissFallsBackToInitWait() = runBlocking {
        val v = validator(peek = { null }, full = { "owner" })
        assertTrue(v.valid(state))
    }

    @Test fun logoutOrAccountMismatchDoesNotReadSession() = runBlocking {
        for (user in listOf(null, "other")) {
            val v = validator(peek = { user }, full = { user },
                sessionExists = { _, _ -> fail("Wrong account must not query session"); true })
            assertFalse(v.valid(state))
        }
    }

    @Test fun accountSwitchWhileRoomSuspendsCannotValidateOldOwner() = runBlocking {
        var owner = "owner"
        val v = validator(peek = { owner }, full = { owner }, sessionExists = { _, _ -> owner = "other"; true })
        assertFalse(v.valid(state))
    }

    @Test fun absentSessionIsInvalidButReadFailurePropagates() = runBlocking {
        assertFalse(validator(sessionExists = { _, _ -> false }).valid(state))
        try {
            validator(sessionExists = { _, _ -> throw java.io.IOException("busy") }).valid(state)
            fail("Read failure must not become an invalid session")
        } catch (_: java.io.IOException) { }
    }

    @Test fun authAndRoomTimeoutsIdentifyTheirPhaseAndNeverReturnInvalid() = runBlocking {
        for (blockedStage in listOf("auth_fast_path", "auth_init_wait", "session_identity")) {
            var failedStage: String? = null
            val v = RestTimerSessionValidator(
                localUserId = { if (blockedStage == "auth_init_wait") awaitCancellation() else "owner" },
                peekUserId = { if (blockedStage == "auth_fast_path") awaitCancellation() else
                    if (blockedStage == "auth_init_wait") null else "owner" },
                sessionExists = { _, _ -> awaitCancellation() }, timeoutMs = 25,
                measure = { stage, block ->
                    try { block() } catch (e: TimeoutCancellationException) { failedStage = stage; throw e }
                })
            try {
                v.valid(state)
                fail("Timeout must remain retryable")
            } catch (_: TimeoutCancellationException) { }
            assertEquals(blockedStage, failedStage)
        }
    }

    @Test fun fastMissSnapshotsAuthStatusBeforeInitWait() = runBlocking {
        val snapshots = mutableListOf<String>()
        val v = RestTimerSessionValidator(
            localUserId = { "owner" },
            peekUserId = { null },
            authStatus = { "Initializing" },
            onStatus = { snapshots.add(it) },
            sessionExists = { _, _ -> true })
        assertTrue(v.valid(state))
        assertEquals(listOf("Initializing"), snapshots)
    }

    @Test fun ownerCancellationIsNotConvertedToInvalid() = runBlocking {
        try {
            validator(peek = { throw CancellationException("stopped") }).valid(state)
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }
}
