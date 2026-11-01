package com.diajarkoding.imfit.domain.model

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ExecutionStopwatchTest {
    @Test fun coldProcessRecoveryUsesPersistedMonotonicAnchorWithoutResettingIt() {
        val original = ExecutionStopwatch(elapsedMs = 30_000).start(100_000, 7)
        val recovered = Json.decodeFromString<ExecutionStopwatch>(Json.encodeToString(original))
        assertEquals(45_000L, recovered.elapsedMillis(115_000, 7))
        val checkpoint = recovered.checkpoint(115_000, 7)
        assertEquals(48_000L, checkpoint.elapsedMillis(118_000, 7))
        assertEquals(115_000L, checkpoint.anchorElapsedMs)
    }

    @Test fun pauseResumeDoesNotCountPausedTimeOrDiscardSubseconds() {
        val paused = ExecutionStopwatch().start(10_000, 7).pause(11_500, 7)
        assertFalse(paused.isRunning)
        assertEquals(1500L, paused.elapsedMillis(90_000, 7))
        val resumed = paused.start(90_000, 7).pause(90_750, 7)
        assertEquals(2250L, resumed.elapsedMs)
        assertFalse(resumed.isRunning)
    }

    @Test fun rebootOrUnknownBootFreezesOnlyConfirmedTimeForManualReview() {
        val checkpoint = ExecutionStopwatch().start(10_000, 7).checkpoint(12_500, 7)
        for (boot in listOf(8, null)) {
            val recovered = checkpoint.checkpoint(500, boot)
            assertEquals(2500L, recovered.elapsedMs)
            assertFalse(recovered.isRunning)
            assertTrue(recovered.recoveryRequired)
            assertEquals(2500L, recovered.elapsedMillis(99_000, boot))
        }
        assertTrue(ExecutionStopwatch().start(1000, null).recoveryRequired)
    }

    @Test fun backwardsMonotonicValueCannotInventNegativeOrHugeDuration() {
        val recovered = ExecutionStopwatch(elapsedMs = 45_000).start(100_000, 7).checkpoint(100, 7)
        assertEquals(45_000L, recovered.elapsedMs)
        assertTrue(recovered.recoveryRequired)
        assertFalse(recovered.isRunning)
    }

    @Test fun finalCompletionFreezesElapsedMeasurementAndRejectsLateTimerChanges() {
        val running = WorkoutSet(1).toggleExecutionStopwatch(1000, 7)
        val completed = running.freezeExecutionStopwatch(46_500, 7).copy(isCompleted = true)
        assertEquals(45, completed.durationSeconds)
        assertFalse(completed.executionStopwatch!!.isRunning)
        assertEquals(completed, completed.toggleExecutionStopwatch(99_000, 7))
        assertEquals(completed, completed.checkpointExecutionStopwatch(99_000, 7))
    }
}
