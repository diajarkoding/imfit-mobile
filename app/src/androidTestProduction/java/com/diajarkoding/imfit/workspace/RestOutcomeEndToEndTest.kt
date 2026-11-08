package com.diajarkoding.imfit.workspace

import android.os.SystemClock
import android.util.Log
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.diajarkoding.imfit.core.timer.AndroidMonotonicClock
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.remote.ImfitAggregateRemoteDataSource
import com.diajarkoding.imfit.data.repository.ActiveSessionV3Gate
import com.diajarkoding.imfit.data.sync.SyncRunResult
import com.diajarkoding.imfit.data.sync.WorkoutAggregatePayloadCodec
import com.diajarkoding.imfit.di.LocalSessionEntryPoint
import com.diajarkoding.imfit.domain.model.*
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/**
 * Opt-in end-to-end proof for T35/T36/T37 against the REAL backend and the app's real Room/repository/sync code:
 * v3 routine → start → execution timer → complete → rest outcome → global pause/resume → Finish (Room only) →
 * nothing on the server yet → ONE aggregate upload → server stores rest outcome + durations → hydrate back into Room.
 *
 * Credentials are read from a file pushed to the device (never passed as instrumentation arguments):
 * /data/local/tmp/imfit_e2e.txt = email on line 1, password on line 2. Delete it afterwards.
 * Run with -e allowAuthenticatedWorkspaceMutation true. Creates only UUID-named fixtures; soft-deletes its routine.
 * The finished workout is immutable history for the test account; its IDs are emitted for scoped cleanup.
 */
@RunWith(AndroidJUnit4::class)
class RestOutcomeEndToEndTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val graph get() = EntryPointAccessors.fromApplication(instrumentation.targetContext, LocalSessionEntryPoint::class.java)

    @Test
    fun finishLocallyThenOneBatchUploadRoundTripsRestOutcomeAndSessionDurations() = runBlocking(Dispatchers.IO) {
        assumeTrue(arguments.getString("allowAuthenticatedWorkspaceMutation") == "true")
        withTimeout(240_000) {
            val ports = graph
            val client = ports.supabase()
            client.auth.awaitInitialization()
            if (client.auth.currentUserOrNull() == null) {
                val lines = File("/data/local/tmp/imfit_e2e.txt").readLines()
                client.auth.signInWith(Email) { email = lines[0].trim(); password = lines[1].trim() }
            }
            val owner = requireNotNull(client.auth.currentUserOrNull()?.id) { "Sign-in failed" }
            val database = ports.database()
            check(database.activeSessionDao().getAnyActiveSession() == null) { "Refusing to replace an existing local session" }
            check(database.workoutLogDao().getPendingLogs(owner).isEmpty()) { "Sync existing pending workouts before running" }
            val boot = AndroidMonotonicClock(instrumentation.targetContext).bootIdentity()
            assumeTrue("Boot identity unavailable on this device", boot != null)

            // An isometric DURATION exercise from the real catalog.
            val catalog = ports.catalog()
            var plank: Exercise? = null
            var cursor: String? = null
            for (page in 1..20) {
                val result = catalog.fetchCatalogPage(cursor = cursor, limit = 50).getOrThrow()
                plank = result.exercises.firstOrNull { it.defaultTrackingType == TrackingType.DURATION && it.modality == ExerciseModality.ISOMETRIC }
                cursor = result.nextCursor
                if (plank != null || cursor == null) break
            }
            val exercise = requireNotNull(plank) { "No isometric DURATION exercise found in the catalog" }

            ActiveSessionV3Gate.enabled = true
            val routines = ports.routines()
            val workouts = ports.workouts()
            val fixtureName = "E2E Rest Outcome ${UUID.randomUUID()}"
            var routine = routines.createRoutine(owner, fixtureName).getOrThrow()
            var workoutId: String? = null
            var routineDeleted = false
            try {
                val targets = listOf(
                    SetTarget("e2e-r1", 1, durationSeconds = 60, restSeconds = 90),
                    SetTarget("e2e-r2", 2, durationSeconds = 30, restSeconds = 0),
                    SetTarget("e2e-r3", 3, durationSeconds = 45, restSeconds = 60)
                )
                val v3 = TemplateExercise(exercise, sets = 3, reps = null, trackingType = TrackingType.DURATION,
                    setTargets = targets, measurementVersion = 3)
                routine = routines.saveRoutine(routine.id, fixtureName, listOf(v3), routine.revision, baselineExerciseIds = emptyList()).getOrThrow()
                val reloaded = requireNotNull(routines.getRoutineById(routine.id).getOrThrow())
                assertEquals(3, reloaded.measurementVersion)
                assertEquals(listOf(90, 0, 60), reloaded.exercises.single().setTargets!!.map { it.restSeconds })

                val session = workouts.startWorkout(reloaded)
                assertEquals(3, session.schemaVersion)
                assertEquals(reloaded.revision, session.templateRevision)
                val log = session.exerciseLogs.single()
                val (a, b, c) = log.sets.map { it.id }
                workouts.mutateActiveSession(session.id) { it.ensureRuntime(SystemClock.elapsedRealtime(), boot, System.currentTimeMillis()) }

                // Set A: real execution timer ~3 s, then Complete → actual duration (not the 60 s target) + rest trigger of 90 s.
                workouts.mutateActiveSession(session.id) { it.toggleExecutionTimer(a, SystemClock.elapsedRealtime(), boot)!! }
                delay(3_000)
                var completedA: SetCompletionResult.Completed? = null
                workouts.mutateActiveSession(session.id) { cur ->
                    val r = cur.completeSet(log.exercise.id, a, SystemClock.elapsedRealtime(), boot)
                    completedA = r as? SetCompletionResult.Completed
                    completedA?.session ?: cur
                }
                assertEquals(90, completedA!!.restSeconds)
                assertNotNull(completedA!!.trigger)
                // Rest is skipped after 2 s: the coordinator records exactly this outcome once.
                delay(2_000)
                workouts.mutateActiveSession(session.id) { it.withRestOutcome(a, RestOutcome(2_000, RestEndReason.SKIPPED)) }

                // Global Pause for ~2 s, then Resume.
                workouts.mutateActiveSession(session.id) { it.pauseWorkout(SystemClock.elapsedRealtime(), boot, System.currentTimeMillis()) }
                delay(2_000)
                workouts.mutateActiveSession(session.id) { it.resumeWorkout(SystemClock.elapsedRealtime(), boot, System.currentTimeMillis()) }

                // Set B (rest target 0): completes with the draft/target duration, no rest trigger. Set C stays a draft.
                var completedB: SetCompletionResult.Completed? = null
                workouts.mutateActiveSession(session.id) { cur ->
                    val r = cur.completeSet(log.exercise.id, b, SystemClock.elapsedRealtime(), boot)
                    completedB = r as? SetCompletionResult.Completed
                    completedB?.session ?: cur
                }
                assertNull("Rest 0 must not create a trigger", completedB!!.trigger)

                // ---- Finish: Room only. Nothing may reach the server yet. ----
                workouts.mutateActiveSession(session.id) { it.freezeForFinish(SystemClock.elapsedRealtime(), boot) }
                val finished = requireNotNull(workouts.finishWorkout()) { "Finish failed" }
                workoutId = finished.id
                emit(routine.id, finished.id, "finished_locally")
                assertEquals(session.id, finished.id)
                assertNull(database.activeSessionDao().getSessionById(session.id))
                val local = requireNotNull(database.workoutLogDao().getWorkoutLogById(finished.id))
                assertEquals("PENDING_SYNC", local.syncStatus)
                assertEquals(3, local.measurementVersion)
                val pausedMs = requireNotNull(local.pausedElapsedMs); val activeMs = requireNotNull(local.activeElapsedMs)
                assertTrue("paused ≈ 2 s but was $pausedMs", pausedMs in 1_500..4_500)
                assertTrue("active includes set + rest (≥ 5 s) but was $activeMs", activeMs >= 5_000)
                assertTrue(pausedMs + activeMs <= local.endTime - local.startTime)
                val localSets = database.workoutSetDao().getSetsByWorkoutLogId(finished.id).sortedBy { it.setNumber }
                assertEquals("Draft set C must not be a final result", listOf(a, b), localSets.map { it.id })
                assertTrue("Actual duration, not the 60 s target: ${localSets[0].durationSeconds}", localSets[0].durationSeconds in 2..5)
                assertEquals(30, localSets[1].durationSeconds)
                assertEquals(2_000L, localSets[0].restElapsedMs); assertEquals("SKIPPED", localSets[0].restEndReason)
                assertNull(localSets[1].restElapsedMs)
                assertTrue(c !in localSets.map { it.id })

                val remote = ImfitAggregateRemoteDataSource(client)
                assertNull("Finish must not upload anything", findRemoteAggregate(remote, finished.id))

                // ---- ONE batch upload ----
                assertEquals(SyncRunResult.SUCCESS, ports.sync().run(owner))
                val synced = requireNotNull(database.workoutLogDao().getWorkoutLogById(finished.id))
                assertEquals("SYNCED", synced.syncStatus); assertNull(synced.pendingOperation)
                emit(routine.id, finished.id, "uploaded")

                val aggregate = requireNotNull(findRemoteAggregate(remote, finished.id)) { "Workout missing on the server" }
                val serverWorkout = aggregate.getValue("workout").jsonObject
                assertEquals(pausedMs, serverWorkout.getValue("paused_elapsed_ms").jsonPrimitive.long)
                assertEquals(activeMs, serverWorkout.getValue("active_elapsed_ms").jsonPrimitive.long)
                val serverSets = aggregate.getValue("sets").jsonArray.map { it.jsonObject }.sortedBy { it.getValue("set_number").jsonPrimitive.int }
                assertEquals(2, serverSets.size)
                assertEquals(2_000L, serverSets[0].getValue("rest_elapsed_ms").jsonPrimitive.long)
                assertEquals("SKIPPED", serverSets[0].getValue("rest_end_reason").jsonPrimitive.content)
                assertTrue(serverSets[1].getValue("rest_elapsed_ms") is JsonNull)
                // The target rest inside the frozen snapshot is untouched by the actual outcome.
                val snapshot = aggregate.getValue("exercises").jsonArray.single().jsonObject.getValue("input_defaults").jsonObject
                assertEquals(listOf(90, 0, 60), snapshot.getValue("set_targets").jsonArray.map { it.jsonObject.getValue("rest_seconds").jsonPrimitive.int })

                // A retry sends the identical payload and gets the identical receipt (immutable replay).
                val payload = WorkoutAggregatePayloadCodec.encode(synced,
                    database.exerciseLogDao().getExerciseLogsByWorkoutLogId(finished.id), database.workoutSetDao().getSetsByWorkoutLogId(finished.id))
                val first = remote.upsertWorkout(payload.workout, payload.exercises, payload.sets)
                val replay = remote.upsertWorkout(payload.workout, payload.exercises, payload.sets)
                assertEquals(first, replay)
                assertEquals(finished.id, first.workoutId); assertEquals(2, first.setCount)

                // ---- Server → Room round trip through the production importer ----
                removeLocalAggregate(database, finished.id)
                assertEquals(SyncRunResult.SUCCESS, ports.sync().run(owner))
                val restored = requireNotNull(workouts.getWorkoutLogById(finished.id))
                assertEquals(pausedMs, restored.pausedElapsedMs); assertEquals(activeMs, restored.activeElapsedMs)
                val restoredSets = restored.exerciseLogs.single().sets.sortedBy { it.setNumber }
                assertEquals(listOf(a, b), restoredSets.map { it.id })
                assertEquals(RestOutcome(2_000, RestEndReason.SKIPPED), restoredSets[0].restOutcome)
                assertNull(restoredSets[1].restOutcome)
                assertEquals(localSets.map { it.durationSeconds }, restoredSets.map { it.durationSeconds })
                emit(routine.id, finished.id, "verified_round_trip")

                assertTrue(routines.deleteRoutine(routine.id, routine.revision,
                    baselineExerciseIds = routine.exercises.map { it.id }, name = routine.name).getOrThrow())
                routineDeleted = true
            } finally {
                withContext(NonCancellable) {
                    withTimeout(30_000) {
                        ActiveSessionV3Gate.enabled = false
                        if (workouts.getActiveSessionForTimer() != null) workouts.cancelWorkout()
                        if (!routineDeleted) {
                            routines.getRoutineById(routine.id).getOrNull()?.let {
                                routines.deleteRoutine(it.id, it.revision, baselineExerciseIds = it.exercises.map { e -> e.id }, name = it.name)
                            }
                        }
                        emit(routine.id, workoutId, "fixture_ids")
                    }
                }
            }
        }
    }

    private fun emit(routineId: String, workoutId: String?, phase: String) {
        Log.i("IMFITE2E", "phase=$phase routine_id=$routineId workout_id=${workoutId ?: "none"}")
    }

    private suspend fun removeLocalAggregate(database: IMFITDatabase, id: String) = database.withTransaction {
        database.workoutSetDao().deleteSetsByWorkoutLog(id)
        database.exerciseLogDao().deleteExerciseLogsByWorkout(id)
        database.workoutLogDao().hardDeleteLog(id)
    }

    private suspend fun findRemoteAggregate(remote: ImfitAggregateRemoteDataSource, id: String): JsonObject? {
        var cursor: String? = null
        do {
            val page = remote.getWorkoutPage(cursor)
            page.getValue("items").jsonArray.map { it.jsonObject }
                .singleOrNull { it.getValue("workout").jsonObject.getValue("id").jsonPrimitive.content == id }?.let { return it }
            cursor = page["next_cursor"]?.jsonPrimitive?.contentOrNull
        } while (cursor != null)
        return null
    }
}
