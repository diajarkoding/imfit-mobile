package com.diajarkoding.imfit.core.timer

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** T29/T31/T32/T33: pause-aware rest, actual outcomes recorded once, trigger-deadline recovery. */
class RestOutcomeAndPauseTest {
    private class Clock : MonotonicClock {
        var now = 10_000L
        var boot: Int? = 4
        override fun elapsedRealtime() = now
        override fun epochMillis() = 1_000_000L + now
        override fun bootIdentity() = boot
    }
    private class Store : RestTimerStore {
        var saved: RestTimerState? = null
        override suspend fun read() = saved
        override suspend fun write(state: RestTimerState) { saved = state }
    }
    private class Effects : RestTimerEffects {
        val outcomes = mutableListOf<RestTimerState>()
        var failOutcomes = 0
        var calls = 0
        override suspend fun validSession(state: RestTimerState) = true
        override fun userStopped(state: RestTimerState) = false
        override fun schedule(state: RestTimerState) = SchedulingMode.EXACT
        override fun cancelAlarm(state: RestTimerState) {}
        override fun showFinished(state: RestTimerState) = FinishedPresentation.POSTED
        override fun dismissFinished() {}
        override fun controlsAvailable() = true
        override fun startAudio() = true
        override fun stopAudio() {}
        override suspend fun recordRestOutcome(state: RestTimerState): Boolean {
            calls++
            if (failOutcomes > 0) { failOutcomes--; return false }
            outcomes += state
            return true
        }
    }
    private class F {
        val clock = Clock(); val store = Store(); val effects = Effects()
        val timer = RestTimerCoordinator(store, clock, effects)
        fun request(set: String = "set-1", durationMs: Long = 90_000) = RestTimerState(
            "", userId = "u", workoutId = "w", templateId = "t", exerciseId = "e", setId = set,
            exerciseName = "Plank", durationMs = durationMs, deadlineElapsedMs = 0, bootIdentity = null, startedAtEpochMs = 0)
    }

    @Test fun skipAfterThirtyFiveSecondsRecordsThirtyFiveNotNinety() = runBlocking {
        val f = F(); f.timer.start(f.request())
        f.clock.now += 35_000
        f.timer.skip()
        val outcome = f.effects.outcomes.single()
        assertEquals("SKIPPED", outcome.outcomeReason)
        assertEquals(35_000L, outcome.outcomeElapsedMs)
        assertEquals(90_000L, outcome.durationMs) // target untouched
        assertTrue(f.store.saved!!.outcomeRecorded)
        f.timer.reconcile(); f.timer.skip()
        assertEquals(1, f.effects.calls)
    }

    @Test fun expiryRecordsTheReachedDeadlineDurationOnce() = runBlocking {
        val f = F(); f.timer.start(f.request())
        f.clock.now += 91_000
        f.timer.reconcile(); f.timer.reconcile()
        val outcome = f.effects.outcomes.single()
        assertEquals("EXPIRED", outcome.outcomeReason)
        assertEquals(90_000L, outcome.outcomeElapsedMs)
    }

    @Test fun pauseFreezesRestAndExcludesPausedIntervalFromActualDuration() = runBlocking {
        val f = F(); f.timer.start(f.request())
        f.clock.now += 20_000
        f.timer.pause()
        assertEquals(TimerStatus.PAUSED, f.timer.state.value!!.status)
        f.clock.now += 1_000_000 // way past the original deadline
        f.timer.reconcile()
        assertEquals(TimerStatus.PAUSED, f.timer.state.value!!.status)
        assertEquals(0, f.effects.calls)
        f.timer.resume()
        assertEquals(70, f.timer.state.value!!.remainingSeconds(f.clock.now))
        f.clock.now += 10_000
        f.timer.skip()
        assertEquals(30_000L, f.effects.outcomes.single().outcomeElapsedMs) // 20 s + 10 s active
    }

    @Test fun pausedRestSurvivesRebootAndResumesWithNewBoot() = runBlocking {
        val f = F(); f.timer.start(f.request())
        f.clock.now += 30_000
        f.timer.pause()
        f.clock.boot = 5; f.clock.now = 500
        val recovered = RestTimerCoordinator(f.store, f.clock, f.effects)
        recovered.reconcile()
        assertEquals(TimerStatus.PAUSED, recovered.state.value!!.status)
        recovered.resume()
        assertEquals(5, recovered.state.value!!.bootIdentity)
        assertEquals(60, recovered.state.value!!.remainingSeconds(f.clock.now))
        assertEquals(0, f.effects.calls)
    }

    @Test fun cancelledReplacedUndoneOrRebootedRestRecordsNoOutcome() = runBlocking {
        val f = F()
        f.timer.start(f.request("a")); f.clock.now += 10_000
        f.timer.start(f.request("b")) // replaced by another completion
        f.timer.cancelForSet("b") // undo
        f.timer.start(f.request("c")); f.clock.now += 5_000
        f.timer.cancel("FINISH")
        f.timer.start(f.request("d")); f.clock.boot = 9
        f.timer.reconcile()
        assertEquals(TimerStatus.CANCELLED, f.timer.state.value!!.status)
        assertEquals(0, f.effects.calls)
    }

    @Test fun deadlineLongPastOnSameBootStillRecordsExpiredOutcomeOnce() = runBlocking {
        val f = F(); f.timer.start(f.request())
        f.clock.now += 20 * 60_000 // process was dead; alert window long gone
        f.timer.reconcile(); f.timer.reconcile()
        assertEquals(TimerStatus.CANCELLED, f.timer.state.value!!.status)
        val outcome = f.effects.outcomes.single()
        assertEquals("EXPIRED", outcome.outcomeReason)
        assertEquals(90_000L, outcome.outcomeElapsedMs)
    }

    @Test fun undeliveredOutcomeIsRetriedThenNeverRepeated() = runBlocking {
        val f = F(); f.effects.failOutcomes = 2
        f.timer.start(f.request()); f.clock.now += 91_000
        f.timer.reconcile() // fails
        assertFalse(f.store.saved!!.outcomeRecorded)
        f.timer.reconcile() // fails
        f.timer.reconcile() // delivered
        f.timer.reconcile(); f.timer.reconcile()
        assertEquals(1, f.effects.outcomes.size)
        assertEquals(3, f.effects.calls)
        assertTrue(f.store.saved!!.outcomeRecorded)
    }

    @Test fun triggerDeadlineIsHonoredNotRestartedAtNinety() = runBlocking {
        val f = F()
        // Completion committed at t=10_000 with a stored deadline of 100_000; app reopens at 40_000 (30 s in).
        f.clock.now = 40_000
        f.timer.start(f.request(), triggerDeadlineElapsedMs = 100_000, triggerBootIdentity = 4)
        assertEquals(60, f.timer.state.value!!.remainingSeconds(f.clock.now))
        assertEquals(TimerStatus.RUNNING, f.timer.state.value!!.status)
    }

    @Test fun triggerPastDeadlineExpiresOnceWithoutRestartAndOtherBootStartsNothing() = runBlocking {
        val f = F(); f.clock.now = 120_000
        f.timer.start(f.request(), triggerDeadlineElapsedMs = 100_000, triggerBootIdentity = 4)
        assertEquals(TimerStatus.EXPIRED, f.timer.state.value!!.status)
        assertEquals(90_000L, f.effects.outcomes.single().outcomeElapsedMs)
        val g = F()
        g.timer.start(g.request(), triggerDeadlineElapsedMs = 100_000, triggerBootIdentity = 3)
        assertNull(g.timer.state.value)
        assertEquals(0, g.effects.calls)
    }
}
