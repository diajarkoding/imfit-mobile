package com.diajarkoding.imfit.core.timer

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test
import com.diajarkoding.imfit.domain.model.WorkoutSession

class RestTimerCoordinatorTest {
    private class Clock : MonotonicClock {
        var now = 1000L
        var boot: Int? = 4
        override fun elapsedRealtime() = now
        override fun epochMillis() = 1_000_000L + now
        override fun bootIdentity() = boot
    }
    private class Store : RestTimerStore {
        var saved: RestTimerState? = null
        var fail = false
        var failRead = false
        var cancelAfterWrite = false
        override suspend fun read(): RestTimerState? {
            if (failRead) throw java.io.IOException("read failed")
            return saved
        }
        override suspend fun write(state: RestTimerState) {
            if (fail) throw java.io.IOException("disk full")
            saved = state
            if (cancelAfterWrite) {
                cancelAfterWrite = false
                throw kotlinx.coroutines.CancellationException("cancelled after durable commit")
            }
        }
    }
    private class Effects : RestTimerEffects {
        var valid = true
        var stopped = false
        var controls = true
        var pendingControls = false
        var plays = 0
        var playing = false
        var posted = false
        var scheduled = false
        var schedules = 0
        var mode = SchedulingMode.EXACT
        var cleanupCount = 0
        val dismissed = mutableSetOf<String>()
        var clearedDismissed = 0
        var readiness: PlaybackReadiness? = null
        var token: PlaybackToken? = null
        var queuedToken: PlaybackToken? = null
        var failRender = false
        var renderedStatus: TimerStatus? = null
        override suspend fun validSession(state: RestTimerState) = valid
        override fun userStopped(state: RestTimerState) = stopped
        override fun schedule(state: RestTimerState): SchedulingMode {
            schedules++
            scheduled = mode != SchedulingMode.UNAVAILABLE
            return mode
        }
        override fun cancelAlarm(state: RestTimerState) { scheduled = false; cleanupCount++ }
        override fun showFinished(state: RestTimerState) { posted = true }
        override fun dismissFinished() { posted = false }
        override fun controlsAvailable() = controls
        override fun controlsPending() = pendingControls
        override fun startAudio(): Boolean { plays++; playing = true; return true }
        override fun stopAudio() { playing = false }
        override fun clearOrphans() { scheduled = false; cleanupCount++ }
        override fun noteUserDismissed(timerId: String) { dismissed.add(timerId) }
        override fun clearDismissed() { dismissed.clear(); clearedDismissed++ }
        override fun playbackReadiness(state: RestTimerState, token: PlaybackToken, alarmDelivery: Boolean): PlaybackReadiness {
            this.token = token
            return readiness ?: super<RestTimerEffects>.playbackReadiness(state, token, alarmDelivery)
        }
        override fun enqueuePlaybackCheck(token: PlaybackToken) { queuedToken = token }
        override fun renderWorkout(state: RestTimerState?, session: WorkoutSession?) {
            if (failRender) throw IllegalStateException("notification renderer failed")
            renderedStatus = state?.status
        }
    }
    private class Fixture {
        val clock = Clock()
        val store = Store()
        val effects = Effects()
        val timer = RestTimerCoordinator(store, clock, effects)
        fun request(set: String = "set-1") = RestTimerState(
            "", userId = "user-1", workoutId = "workout-1", templateId = "template-1",
            exerciseId = "exercise-1", setId = set, exerciseName = "Bench press", durationMs = 120_000,
            deadlineElapsedMs = 0, bootIdentity = null, startedAtEpochMs = 0
        )
        suspend fun start() = timer.start(request())
        suspend fun expire() { clock.now += 130_000; timer.reconcile() }
    }

    @Test fun deadlineExpiresWithoutTicksAndCeilsDisplay() = runBlocking {
        val f = Fixture(); f.start()
        f.clock.now += 90_001
        assertEquals(30, f.timer.state.value!!.remainingSeconds(f.clock.now))
        f.clock.now += 40_000
        f.timer.reconcile()
        assertEquals(TimerStatus.EXPIRED, f.store.saved!!.status)
        assertEquals(1, f.effects.plays)
    }
    @Test fun concurrentExpiryAndRepeatedAcknowledgementNeverDuplicateAudio() = runBlocking {
        val f = Fixture(); f.start(); f.clock.now += 130_000
        val state = f.timer.state.value!!
        val ui = launch { f.timer.reconcile() }
        val alarm = launch { f.timer.expiry(state.timerId, state.generation) }
        ui.join(); alarm.join()
        val id = f.timer.state.value!!.timerId
        f.timer.acknowledge(id)
        val count = f.effects.cleanupCount
        f.timer.acknowledge(id)
        f.timer.reconcile()
        assertTrue(f.effects.cleanupCount > count)
        assertEquals(1, f.effects.plays)
        assertFalse(f.effects.playing)
        assertFalse(f.effects.posted)
    }
    @Test fun staleAlarmAndDismissDoNotAffectNewTimer() = runBlocking {
        val f = Fixture(); f.start()
        val old = f.timer.state.value!!
        f.timer.start(f.request("set-2"))
        val next = f.timer.state.value!!
        f.timer.expiry(old.timerId, old.generation)
        f.timer.acknowledge(old.timerId)
        f.timer.skip(old.timerId)
        f.timer.extend(old.timerId, old.generation)
        assertEquals(next, f.timer.state.value)
        assertTrue(f.effects.scheduled)
    }
    @Test fun duplicateStartIncludingEarlierSetKeepsDeadline() = runBlocking {
        val f = Fixture(); f.start()
        val first = f.timer.state.value!!
        f.clock.now += 5000
        f.timer.start(f.request())
        assertEquals(first.deadlineElapsedMs, f.timer.state.value!!.deadlineElapsedMs)
        f.timer.start(f.request("set-2"))
        val second = f.timer.state.value
        f.timer.start(f.request())
        assertEquals(second, f.timer.state.value)
    }
    @Test fun processRecoveryRestoresRunningAndExpiredEffects() = runBlocking {
        val f = Fixture(); f.start()
        val recovery = RestTimerCoordinator(f.store, f.clock, f.effects)
        recovery.reconcile()
        assertEquals(TimerStatus.RUNNING, recovery.state.value!!.status)
        f.clock.now += 130_000
        val recovery2 = RestTimerCoordinator(f.store, f.clock, f.effects)
        recovery2.reconcile()
        assertEquals(TimerStatus.EXPIRED, recovery2.state.value!!.status)
        assertTrue(f.effects.posted)
    }
    @Test fun rebootUnknownBootUserStopAndInvalidSessionAreSilentTerminal() = runBlocking {
        for (case in 0..3) {
            val f = Fixture(); f.start()
            when (case) {
                0 -> f.clock.boot = null
                1 -> f.clock.boot = 5
                2 -> f.effects.stopped = true
                3 -> f.effects.valid = false
            }
            f.clock.now += 130_000
            f.timer.reconcile()
            assertEquals(TimerStatus.CANCELLED, f.store.saved!!.status)
            assertEquals(0, f.effects.plays)
            assertFalse(f.effects.scheduled)
        }
    }
    @Test fun unavailableScheduleIsNotRetriedEveryTickAndCanRecover() = runBlocking {
        val f = Fixture(); f.effects.mode = SchedulingMode.UNAVAILABLE; f.start()
        repeat(5) { f.timer.reconcile() }
        assertEquals(SchedulingMode.UNAVAILABLE, f.store.saved!!.schedulingMode)
        assertEquals(1, f.effects.schedules)
        f.effects.mode = SchedulingMode.EXACT
        f.timer.capabilitiesChanged()
        assertEquals(SchedulingMode.EXACT, f.store.saved!!.schedulingMode)
    }
    @Test fun blockedOrLostControlsNeverReplayOnReturn() = runBlocking {
        for (blockedInitially in listOf(true, false)) {
            val f = Fixture(); f.start()
            f.effects.controls = !blockedInitially
            f.expire()
            f.effects.controls = false
            f.timer.reconcile()
            val starts = f.effects.plays
            f.effects.controls = true
            f.timer.reconcile()
            assertEquals(starts, f.effects.plays)
            assertTrue(f.store.saved!!.audioSuppressed)
            assertFalse(f.effects.playing)
        }
    }
    @Test fun diskFailureDoesNotStartEffectsAndStopStillStopsImmediately() = runBlocking {
        val f = Fixture(); f.store.fail = true; f.start()
        assertNull(f.timer.state.value)
        assertFalse(f.effects.scheduled)
        assertNotNull(f.timer.error.value)
        f.store.fail = false; f.start(); f.expire()
        f.store.fail = true
        f.timer.acknowledge(f.timer.state.value!!.timerId)
        assertFalse(f.effects.playing)
        assertFalse(f.effects.posted)
        f.store.fail = false
        f.timer.reconcile()
        assertEquals(TimerStatus.ACKNOWLEDGED, f.store.saved!!.status)
    }
    @Test fun teardownPreservesAlarmWhileExplicitCancelClearsEffects() = runBlocking {
        val f = Fixture(); f.start()
        f.timer.releasePlayback()
        assertTrue(f.effects.scheduled)
        assertEquals(TimerStatus.RUNNING, f.store.saved!!.status)
        f.timer.cancel("LOGOUT")
        assertFalse(f.effects.scheduled)
        assertEquals(TimerStatus.CANCELLED, f.store.saved!!.status)
    }
    @Test fun extensionRejectsStaleGenerationAndSkipIsTerminal() = runBlocking {
        val f = Fixture(); f.start()
        val old = f.timer.state.value!!
        f.timer.extend()
        assertEquals(old.deadlineElapsedMs + 30_000, f.timer.state.value!!.deadlineElapsedMs)
        f.clock.now = old.deadlineElapsedMs + 1
        f.timer.expiry(old.timerId, old.generation)
        assertEquals(TimerStatus.RUNNING, f.timer.state.value!!.status)
        f.timer.skip()
        f.clock.now += 60_000
        f.timer.reconcile()
        assertEquals(TimerStatus.SKIPPED, f.store.saved!!.status)
        assertEquals(0, f.effects.plays)
    }
    @Test fun pauseGuardIsSharedByScreenAndService() = runBlocking {
        val f = Fixture(); f.start()
        var paused = false
        assertFalse(f.timer.whenRestInactive { paused = true })
        assertFalse(paused)
        f.timer.skip()
        assertTrue(f.timer.whenRestInactive { paused = true })
        assertTrue(paused)
        val unreadable = Fixture()
        unreadable.store.failRead = true
        assertFalse(unreadable.timer.whenRestInactive { fail("Pause must not proceed on storage failure") })
        assertNotNull(unreadable.timer.error.value)
    }
    @Test fun terminalStateRecoveredAfterCrashCleansEffectsWithoutPlayback() = runBlocking {
        val f = Fixture(); f.start(); f.expire()
        f.store.saved = f.store.saved!!.copy(status = TimerStatus.ACKNOWLEDGED)
        val recovery = RestTimerCoordinator(f.store, f.clock, f.effects)
        recovery.reconcile()
        assertFalse(f.effects.posted)
        assertFalse(f.effects.playing)
        assertFalse(f.effects.scheduled)
        assertEquals(1, f.effects.plays)
    }
    @Test fun corruptOrMissingRecordCleansOrphanEffects() = runBlocking {
        val f = Fixture()
        f.effects.scheduled = true
        f.effects.playing = true
        f.effects.posted = true
        f.timer.reconcile()
        assertNull(f.timer.state.value)
        assertFalse(f.effects.scheduled)
        assertFalse(f.effects.playing)
        assertFalse(f.effects.posted)
    }
    @Test fun diskCodecRoundTripsIdentityAndSuppression() = runBlocking {
        val f = Fixture(); f.start(); f.expire(); f.timer.silence()
        val state = f.timer.state.value!!
        assertEquals(state, RestTimerStateCodec.decode(RestTimerStateCodec.encode(state)))
    }
    @Test fun diskCodecRejectsMalformedUnsupportedAndInvalidRecords() = runBlocking {
        val f = Fixture(); f.start()
        val state = f.timer.state.value!!
        for (encoded in listOf("not json", RestTimerStateCodec.encode(state.copy(schemaVersion = 999)),
            RestTimerStateCodec.encode(state.copy(durationMs = -1)))) {
            assertThrows(IllegalArgumentException::class.java) { RestTimerStateCodec.decode(encoded) }
        }
    }
    @Test fun alarmActivityAcceptsOnlyMatchingExpiredIdentity() = runBlocking {
        val f = Fixture(); f.start()
        val state = f.timer.state.value!!
        for (status in TimerStatus.entries) {
            assertEquals(status == TimerStatus.EXPIRED, state.copy(status = status).matchesExpired(state.timerId, state.generation))
        }
        assertFalse(state.copy(status = TimerStatus.EXPIRED).matchesExpired("old-timer", state.generation))
        assertFalse(state.copy(status = TimerStatus.EXPIRED).matchesExpired(state.timerId, state.generation + 1))
    }
    @Test fun staleGenerationAcknowledgementIsIgnored() = runBlocking {
        val f = Fixture(); f.start(); f.timer.extend()
        f.clock.now += 160_000
        f.timer.reconcile()
        val state = f.timer.state.value!!
        f.timer.acknowledge(state.timerId, state.generation - 1)
        assertEquals(TimerStatus.EXPIRED, f.timer.state.value!!.status)
        assertTrue(f.effects.playing)
        f.timer.acknowledge(state.timerId, state.generation)
        assertEquals(TimerStatus.ACKNOWLEDGED, f.timer.state.value!!.status)
    }
    @Test fun audioWaitsForPostedCardWithoutUsingForegroundActivityAsControl() = runBlocking {
        val f = Fixture(); f.start()
        f.effects.controls = false; f.effects.pendingControls = true
        f.expire()
        assertFalse(f.effects.playing)
        assertFalse(f.timer.state.value!!.audioSuppressed)
        f.effects.controls = true; f.effects.pendingControls = false
        f.timer.reconcile()
        assertTrue(f.effects.playing)
        f.effects.controls = false
        f.timer.reconcile()
        assertTrue(f.timer.state.value!!.audioSuppressed)
        assertFalse(f.effects.playing)
    }
    @Test fun userDismissalIsMarkedAndClearedOnNewTimer() = runBlocking {
        val f = Fixture(); f.start(); f.expire()
        val id = f.timer.state.value!!.timerId
        f.timer.noteDismissed(id)
        assertTrue(f.effects.dismissed.contains(id))
        f.timer.acknowledge(id)
        assertTrue(f.effects.dismissed.contains(id))
        assertEquals(TimerStatus.ACKNOWLEDGED, f.timer.state.value!!.status)
        f.timer.start(f.request("set-2"))
        assertEquals(2, f.effects.clearedDismissed)
        assertTrue(f.effects.dismissed.isEmpty())
        assertEquals(TimerStatus.RUNNING, f.timer.state.value!!.status)
    }

    @Test fun alarmCommitsAndPostsBeforeHandingPlaybackToSupervisor() = runBlocking {
        val f = Fixture(); f.start()
        val state = f.timer.state.value!!
        f.clock.now = state.deadlineElapsedMs
        f.timer.expiry(state.timerId, state.generation, alarmDelivery = true)
        assertEquals(TimerStatus.EXPIRED, f.store.saved!!.status)
        assertTrue(f.effects.posted)
        assertEquals(TimerStatus.EXPIRED, f.effects.renderedStatus)
        assertEquals(0, f.effects.plays)
        f.timer.checkPlayback(requireNotNull(f.effects.queuedToken))
        assertEquals(1, f.effects.plays)
    }

    @Test fun stopDuringStartupRejectsLateReadyFailureAndPolling() = runBlocking {
        val f = Fixture(); f.start()
        f.effects.readiness = PlaybackReadiness.WAITING
        f.expire()
        val token = requireNotNull(f.effects.token)
        f.timer.acknowledge(token.timerId, token.generation)
        f.effects.readiness = PlaybackReadiness.READY
        f.timer.playbackServiceResult(token, true)
        f.timer.playbackServiceResult(token, false)
        f.timer.checkPlayback(token)
        assertEquals(TimerStatus.ACKNOWLEDGED, f.store.saved!!.status)
        assertFalse(f.effects.posted)
        assertEquals(0, f.effects.plays)
    }

    @Test fun readinessTimeoutIsNotExtendedByReconcileOrLateReady() = runBlocking {
        val f = Fixture(); f.start()
        f.effects.readiness = PlaybackReadiness.WAITING
        f.expire()
        val token = requireNotNull(f.effects.token)
        repeat(4) { f.clock.now += 1000; f.timer.reconcile() }
        assertFalse(f.store.saved!!.audioSuppressed)
        f.clock.now += 1000
        f.timer.checkPlayback(token)
        f.effects.readiness = PlaybackReadiness.READY
        f.timer.playbackServiceResult(token, true)
        f.timer.reconcile()
        assertTrue(f.store.saved!!.audioSuppressed)
        assertEquals(TimerStatus.EXPIRED, f.store.saved!!.status)
        assertEquals(0, f.effects.plays)
    }

    @Test fun replacementInvalidatesReadinessAndRenderFailureDoesNotCancelExpiry() = runBlocking {
        val f = Fixture(); f.start()
        f.effects.readiness = PlaybackReadiness.WAITING
        f.expire()
        val old = requireNotNull(f.effects.token)
        f.timer.start(f.request("set-2"))
        val replacement = f.timer.state.value!!
        f.effects.readiness = PlaybackReadiness.READY
        f.timer.playbackServiceResult(old, true)
        assertEquals(replacement, f.timer.state.value)
        assertEquals(0, f.effects.plays)
        f.effects.failRender = true
        f.clock.now = replacement.deadlineElapsedMs
        f.timer.expiry(replacement.timerId, replacement.generation)
        assertEquals(TimerStatus.EXPIRED, f.store.saved!!.status)
        assertTrue(f.effects.posted)
        assertEquals(1, f.effects.plays)
    }

    @Test fun failedStopWriteStillInvalidatesStartupUntilTerminalRetryCommits() = runBlocking {
        val f = Fixture(); f.start()
        f.effects.readiness = PlaybackReadiness.WAITING
        f.expire()
        val token = requireNotNull(f.effects.token)
        f.store.fail = true
        f.timer.acknowledge(token.timerId, token.generation)
        f.effects.readiness = PlaybackReadiness.READY
        f.timer.playbackServiceResult(token, true)
        assertEquals(0, f.effects.plays)
        assertFalse(f.effects.posted)
        f.store.fail = false
        f.timer.checkPlayback(token)
        assertEquals(TimerStatus.ACKNOWLEDGED, f.store.saved!!.status)
        assertEquals(0, f.effects.plays)
    }

    @Test fun cancellationAfterExtensionCommitReloadsGenerationBeforeOldAlarm() = runBlocking {
        val f = Fixture(); f.start()
        val old = f.timer.state.value!!
        f.store.cancelAfterWrite = true
        try {
            f.timer.extend()
            fail("Cancellation must propagate")
        } catch (_: kotlinx.coroutines.CancellationException) {
            // The durable update succeeded, even though the caller did not observe success.
        }
        f.clock.now = old.deadlineElapsedMs + 1
        f.timer.expiry(old.timerId, old.generation)
        assertEquals(old.generation + 1, f.timer.state.value!!.generation)
        assertEquals(old.deadlineElapsedMs + 30_000, f.timer.state.value!!.deadlineElapsedMs)
        assertEquals(TimerStatus.RUNNING, f.store.saved!!.status)
        assertEquals(2, f.effects.schedules)
        assertTrue(f.effects.scheduled)
        assertEquals(0, f.effects.plays)
    }
}
