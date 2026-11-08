package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.domain.model.*
import org.junit.Assert.*
import org.junit.Test

class ActiveSessionRuntimeTest {
    private val boot = 7
    private val exercise = Exercise("plank", "Plank", MuscleCategory.CORE, "d",
        defaultTrackingType = TrackingType.DURATION, modality = ExerciseModality.ISOMETRIC)

    private fun session(rests: List<Int> = listOf(90, 0)): WorkoutSession {
        val targets = rests.mapIndexed { i, r -> SetTarget("r${i + 1}", i + 1, durationSeconds = 90, restSeconds = r) }
        val defaults = ExerciseInputDefaults(targets.size, 0, TrackingType.DURATION.name, schemaVersion = 3,
            setTargets = targets, modality = ExerciseModality.ISOMETRIC)
        val sets = targets.mapIndexed { i, t ->
            WorkoutSet(i + 1, weightKg = null, reps = null, durationSeconds = 90, id = "00000000-0000-4000-8000-00000000000${i + 1}",
                targetRowId = t.rowId, measurementVersion = 3)
        }
        return WorkoutSession("s1", "routine", "W", 0L,
            listOf(ExerciseLog(exercise, sets, inputDefaults = defaults, trackingType = TrackingType.DURATION,
                modality = ExerciseModality.ISOMETRIC, measurementVersion = 3)),
            schemaVersion = 3, templateRevision = 7)
            .ensureRuntime(0, boot, 0L)
    }
    private val a = "00000000-0000-4000-8000-000000000001"
    private val b = "00000000-0000-4000-8000-000000000002"

    @Test fun activeSixtyPlusPauseThirtyPlusTwentyIsEightySecondsNotOneTenTen() {
        var s = session().toggleExecutionTimer(a, 0, boot)!!
        s = s.pauseWorkout(60_000, boot, 60_000)
        assertEquals(60, s.exerciseLogs[0].sets[0].durationSeconds)
        // Time does not accumulate while paused.
        assertEquals(60_000L, s.withDisplayElapsed(89_000, boot).let { it.exerciseLogs[0].sets[0].executionStopwatch!!.elapsedMs })
        s = s.resumeWorkout(90_000, boot, 90_000)
        val done = s.completeSet("plank", a, 110_000, boot) as SetCompletionResult.Completed
        assertEquals(80, done.session.exerciseLogs[0].sets[0].durationSeconds)
        assertEquals(30_000L, done.session.pausedElapsedMs(110_000, boot))
        assertEquals(80_000L, done.session.activeElapsedMs(110_000, boot))
    }

    @Test fun sessionActiveTimeIncludesRestButNotPause() {
        var s = session().toggleExecutionTimer(a, 0, boot)!!
        s = s.pauseWorkout(60_000, boot, 60_000).resumeWorkout(90_000, boot, 90_000)
        val done = s.completeSet("plank", a, 110_000, boot) as SetCompletionResult.Completed
        // 80 s set + 35 s rest (skipped) = 115 s active; 30 s paused; gross 145 s.
        assertEquals(115_000L, done.session.activeElapsedMs(145_000, boot))
        assertEquals(30_000L, done.session.pausedElapsedMs(145_000, boot))
    }

    @Test fun onlyOneExecutionTimerIsActiveAndBelongsToOneSet() {
        val s = session().toggleExecutionTimer(a, 0, boot)!!
        assertEquals(a, s.executionOwnerSetId())
        assertNull(s.toggleExecutionTimer(b, 1_000, boot))
        val stopped = s.toggleExecutionTimer(a, 5_000, boot)!!
        assertNull(stopped.executionOwnerSetId())
        assertNotNull(stopped.toggleExecutionTimer(b, 6_000, boot))
        assertNull(s.pauseWorkout(1, boot, 1).toggleExecutionTimer(a, 2, boot))
    }

    @Test fun globalPauseFreezesRunningTimerAndResumeRestartsIt() {
        var s = session().toggleExecutionTimer(a, 0, boot)!!
        s = s.pauseWorkout(10_000, boot, 10_000)
        assertEquals(a, s.executionOwnerSetId()) // ownership survives pause
        s = s.resumeWorkout(50_000, boot, 50_000)
        assertTrue(s.exerciseLogs[0].sets[0].executionStopwatch!!.isRunning)
        assertEquals(15_000L, s.exerciseLogs[0].sets[0].executionStopwatch!!.elapsedMillis(55_000, boot))
    }

    @Test fun completeCreatesTriggerFromTargetRestAndIsIdempotent() {
        val s = session().toggleExecutionTimer(a, 0, boot)!!
        val done = s.completeSet("plank", a, 20_000, boot) as SetCompletionResult.Completed
        assertEquals(90, done.restSeconds)
        assertEquals(20_000L + 90_000L, done.trigger!!.deadlineElapsedMs)
        assertEquals("r1", done.trigger!!.targetRowId)
        assertTrue(done.session.exerciseLogs[0].sets[0].isCompleted)
        assertEquals(20, done.session.exerciseLogs[0].sets[0].durationSeconds)
        assertEquals(SetCompletionResult.AlreadyCompleted, done.session.completeSet("plank", a, 30_000, boot))
        assertEquals(done.trigger, done.session.pendingRestTrigger)
    }

    @Test fun restZeroCreatesNoTriggerAndSupersedesPreviousOne() {
        val first = (session().completeSet("plank", a, 1_000, boot) as SetCompletionResult.Completed).session
        assertNotNull(first.pendingRestTrigger)
        val second = first.completeSet("plank", b, 2_000, boot) as SetCompletionResult.Completed
        assertEquals(0, second.restSeconds)
        assertNull(second.trigger)
        assertNull(second.session.pendingRestTrigger)
    }

    @Test fun invalidDurationStaysDraft() {
        val s = session()
        val blank = s.copy(exerciseLogs = s.exerciseLogs.map { l -> l.copy(sets = l.sets.map { it.copy(durationSeconds = null) }) })
        assertEquals(SetCompletionResult.Invalid, blank.completeSet("plank", a, 1, boot))
    }

    @Test fun pausedSessionLocksCompletionAndUndo() {
        val paused = session().pauseWorkout(1, boot, 1)
        assertEquals(SetCompletionResult.Locked, paused.completeSet("plank", a, 2, boot))
        val done = (session().completeSet("plank", a, 1, boot) as SetCompletionResult.Completed).session.pauseWorkout(2, boot, 2)
        assertEquals(SetUndoResult.Locked, done.undoSet("plank", a))
    }

    @Test fun undoReturnsToDraftKeepsInputsAndDiscardsRestOutcomeAndTrigger() {
        val done = (session().completeSet("plank", a, 20_000, boot) as SetCompletionResult.Completed).session
            .withRestOutcome(a, RestOutcome(35_000, RestEndReason.SKIPPED))
        val undone = done.undoSet("plank", a) as SetUndoResult.Undone
        val set = undone.session.exerciseLogs[0].sets[0]
        assertFalse(set.isCompleted)
        assertNull(set.restOutcome)
        assertEquals(90, set.durationSeconds)
        assertTrue(undone.clearedTrigger)
        assertNull(undone.session.pendingRestTrigger)
        assertEquals(SetUndoResult.NotCompleted, undone.session.undoSet("plank", a))
    }

    @Test fun restOutcomeIsWrittenOnceAndOnlyForCompletedSets() {
        val draft = session()
        assertSame(draft, draft.withRestOutcome(a, RestOutcome(1, RestEndReason.EXPIRED)))
        val done = (draft.completeSet("plank", a, 1_000, boot) as SetCompletionResult.Completed).session
        val first = done.withRestOutcome(a, RestOutcome(35_000, RestEndReason.SKIPPED))
        assertEquals(35_000L, first.exerciseLogs[0].sets[0].restOutcome!!.elapsedMs)
        assertSame(first, first.withRestOutcome(a, RestOutcome(90_000, RestEndReason.EXPIRED)))
        assertEquals(60, 60.also { assertEquals(90, first.exerciseLogs[0].restSecondsForSet(a)) }) // target untouched
        assertSame(done, done.withRestOutcome(a, RestOutcome(-1, RestEndReason.EXPIRED)))
    }

    @Test fun sameBootRecoveryKeepsClocksRunningFromStoredAnchors() {
        val s = session().toggleExecutionTimer(a, 1_000, boot)!!
        val restored = s.reconcileAfterRestore(31_000, boot)
        assertFalse(restored.needsTimerReconciliation)
        assertEquals(30_000L, restored.exerciseLogs[0].sets[0].executionStopwatch!!.elapsedMs)
        assertEquals(31_000L, restored.activeElapsedMs(31_000, boot))
    }

    @Test fun rebootKeepsLastValidElapsedWithoutInventingTimeAndRequiresReconciliation() {
        val s = session().toggleExecutionTimer(a, 1_000, boot)!!.pauseWorkout(21_000, boot, 21_000)
            .resumeWorkout(22_000, boot, 22_000) // running again, durable elapsed 20 s
        val restored = s.reconcileAfterRestore(500, boot + 1)
        assertTrue(restored.needsTimerReconciliation)
        val sw = restored.exerciseLogs[0].sets[0].executionStopwatch!!
        assertFalse(sw.isRunning)
        assertEquals(20_000L, sw.elapsedMs)
        // Whole-session clock keeps counting only from the new anchor.
        assertEquals(restored.sessionStopwatch!!.elapsedMs + 3_000, restored.activeElapsedMs(3_500, boot + 1))
    }

    @Test fun displayElapsedIsInMemoryOnly() {
        val s = session().toggleExecutionTimer(a, 0, boot)!!
        val shown = s.withDisplayElapsed(42_000, boot)
        assertEquals(42, shown.exerciseLogs[0].sets[0].durationSeconds)
        assertEquals(0L, s.exerciseLogs[0].sets[0].executionStopwatch!!.elapsedMs)
    }

    @Test fun runtimeAndOutcomeSurviveCodecRoundTrip() {
        val done = (session().toggleExecutionTimer(a, 0, boot)!!.completeSet("plank", a, 20_000, boot) as SetCompletionResult.Completed).session
            .withRestOutcome(a, RestOutcome(35_000, RestEndReason.SKIPPED))
        val decoded = WorkoutSessionCodec.decode(WorkoutSessionCodec.encode(done))
        assertEquals(done, decoded)
        assertEquals(7L, decoded.templateRevision)
    }

    @Test fun legacySessionRejectsRestOutcome() {
        val v3 = session()
        val legacyLog = v3.exerciseLogs[0].let { l ->
            l.copy(measurementVersion = null, inputDefaults = null,
                sets = l.sets.map { it.copy(measurementVersion = null, targetRowId = null, isCompleted = true,
                    restOutcome = RestOutcome(1, RestEndReason.EXPIRED)) })
        }
        try {
            WorkoutSessionCodec.encode(v3.copy(schemaVersion = 2, templateRevision = null, exerciseLogs = listOf(legacyLog)))
            fail("legacy session must not carry a rest outcome")
        } catch (_: IllegalArgumentException) {}
    }

    @Test fun finishFreezesClocksAndReportsPausedAndActiveIncludingRest() {
        var s = session().toggleExecutionTimer(a, 0, boot)!!
        s = s.pauseWorkout(60_000, boot, 60_000).resumeWorkout(90_000, boot, 90_000)
        s = (s.completeSet("plank", a, 110_000, boot) as SetCompletionResult.Completed).session
        // 35 s of rest follows; finish at 145 s.
        val frozen = s.freezeForFinish(145_000, boot)
        assertEquals(FinishedSessionDurations(30_000, 115_000), frozen.finishedDurations(endTimeMs = 145_000))
        assertEquals(frozen.sessionStopwatch, frozen.freezeForFinish(999_000, boot).sessionStopwatch) // idempotent
    }

    @Test fun finishWhilePausedCountsThePauseUpToFinishAndNotAsActive() {
        val s = session().pauseWorkout(40_000, boot, 40_000).freezeForFinish(100_000, boot)
        assertEquals(FinishedSessionDurations(60_000, 40_000), s.finishedDurations(endTimeMs = 100_000))
    }

    @Test fun durationsAreNotInventedWhenClocksWereNotFrozenOrSessionIsLegacy() {
        assertNull(session().finishedDurations(endTimeMs = 100_000)) // session clock still running
        assertNull(session().copy(schemaVersion = 2).finishedDurations(endTimeMs = 100_000))
        assertNull(session().copy(sessionStopwatch = null).freezeForFinish(1, boot).finishedDurations(100_000))
    }

    @Test fun wallClockSkewIsClampedSoTheServerContractPausedPlusActiveWithinGrossHolds() {
        val s = session().pauseWorkout(60_000, boot, 60_000).resumeWorkout(90_000, boot, 90_000).freezeForFinish(145_000, boot)
        // User moved the wall clock back: gross span is only 100 s although 145 s elapsed monotonically.
        val d = s.finishedDurations(endTimeMs = 100_000)!!
        assertEquals(30_000L, d.pausedElapsedMs)
        assertEquals(70_000L, d.activeElapsedMs)
        assertTrue(d.pausedElapsedMs + d.activeElapsedMs <= 100_000)
        val tiny = s.finishedDurations(endTimeMs = 10_000)!!
        assertEquals(10_000L, tiny.pausedElapsedMs); assertEquals(0L, tiny.activeElapsedMs)
    }

    @Test fun addV3SetCreatesFreshIdentityAndExtraTargetWithRest60AndKeepsRoutineTargets() {
        val log = session().exerciseLogs[0]
        val added = log.addV3Set("00000000-0000-4000-8000-0000000000aa", "extra-1")
        assertEquals(3, added.sets.size)
        val new = added.sets.last()
        assertEquals(3, new.setNumber); assertEquals("extra-1", new.targetRowId); assertEquals(90, new.durationSeconds) // first target default
        assertFalse(new.isCompleted)
        assertEquals(listOf("r1", "r2"), added.inputDefaults!!.setTargets!!.map { it.rowId }) // routine untouched
        assertEquals(listOf(SetTarget("extra-1", 1, durationSeconds = 90, restSeconds = 60)), added.inputDefaults!!.extraSetTargets)
        assertEquals(60, added.restSecondsForSet(new.id))
        assertEquals(log.sets, added.sets.take(2)) // other results unchanged
        added.inputDefaults!!.requireV3Snapshot()
        WorkoutSessionCodec.encode(session().copy(exerciseLogs = listOf(added))) // still a valid persisted v3 session
    }

    @Test fun removeDraftSetOnV3RenumbersWithoutChangingOtherIdsAndDropsOnlyUnreferencedExtraTargets() {
        val withExtra = session().exerciseLogs[0].addV3Set("00000000-0000-4000-8000-0000000000aa", "extra-1")
        val removedInitial = withExtra.removeDraftSet(0)
        assertEquals(listOf(b, "00000000-0000-4000-8000-0000000000aa"), removedInitial.sets.map { it.id })
        assertEquals(listOf(1, 2), removedInitial.sets.map { it.setNumber })
        assertEquals(listOf("r2", "extra-1"), removedInitial.sets.map { it.targetRowId })
        assertEquals(listOf("r1", "r2"), removedInitial.inputDefaults!!.setTargets!!.map { it.rowId }) // initial target kept as evidence
        val removedExtra = withExtra.removeDraftSet(2)
        assertTrue(removedExtra.inputDefaults!!.extraSetTargets.isEmpty())
        assertEquals(listOf(1, 2), removedExtra.sets.map { it.setNumber })
        WorkoutSessionCodec.encode(session().copy(exerciseLogs = listOf(removedInitial)))
    }

    @Test fun removeRefusesCompletedLastAndUnknownSetsAndAddStopsAtTwenty() {
        val log = session().exerciseLogs[0]
        val done = (session().completeSet("plank", a, 1_000, boot) as SetCompletionResult.Completed).session.exerciseLogs[0]
        assertSame(done, done.removeDraftSet(0))
        assertSame(log, log.removeDraftSet(9))
        val single = log.copy(sets = log.sets.take(1))
        assertSame(single, single.removeDraftSet(0))
        var full = log
        repeat(18) { full = full.addV3Set() }
        assertEquals(20, full.sets.size)
        assertThrows(IllegalArgumentException::class.java) { full.addV3Set() }
    }
}

