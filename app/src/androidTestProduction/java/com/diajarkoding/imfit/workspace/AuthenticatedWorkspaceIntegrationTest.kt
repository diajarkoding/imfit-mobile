package com.diajarkoding.imfit.workspace

import android.os.Bundle
import android.util.Log
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.remote.ImfitAggregateRemoteDataSource
import com.diajarkoding.imfit.data.sync.SyncRunResult
import com.diajarkoding.imfit.di.LocalSessionEntryPoint
import com.diajarkoding.imfit.domain.model.ExercisePrescriptionCapability
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.prescriptionCapability
import com.diajarkoding.imfit.domain.repository.ExerciseHydrationResult
import dagger.hilt.android.EntryPointAccessors
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * Opt-in real production auth/HTTP/Room/repository integration. Never imports a
 * synthetic session or logs credentials. Run on a signed-in, idle emulator with
 * -e allowAuthenticatedWorkspaceMutation true. The test creates only UUID-named
 * fixtures, soft-deletes its routine, and emits exact IDs for privileged teardown
 * of immutable history/receipts. No authenticated history deletion API is added
 * solely for testing. Run cleanupLocalFixture after that scoped server cleanup.
 */
@RunWith(AndroidJUnit4::class)
class AuthenticatedWorkspaceIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val graph get() = EntryPointAccessors.fromApplication(
        instrumentation.targetContext, LocalSessionEntryPoint::class.java
    )

    @Test
    fun createSaveStartFinishPersistReplayAndRestoreHistory() = runBlocking(Dispatchers.IO) {
        assumeTrue(arguments.getString("allowAuthenticatedWorkspaceMutation") == "true")
        withTimeout(120_000) {
            val ports = graph
            val client = ports.supabase()
            client.auth.awaitInitialization()
            val owner = requireNotNull(client.auth.currentUserOrNull()?.id) { "Sign in before running this opt-in test" }
            val database = ports.database()
            check(database.activeSessionDao().getAnyActiveSession() == null) { "Refusing to replace an existing local session" }
            check(database.workoutLogDao().getPendingLogs(owner).isEmpty()) { "Sync existing pending workouts before running" }
            check(database.workoutTemplateDao().getAllTemplatesByUserIncludingDeleted(owner).isEmpty()) {
                "Routine Room mirror must be empty before integration"
            }
            val catalog = ports.catalog()
            val firstPage = catalog.fetchCatalogPage(limit = 10).getOrThrow()
            val exercise = firstPage.exercises.first { it.prescriptionCapability == ExercisePrescriptionCapability.REPS_LOAD }
            val secondExercise = firstPage.exercises.first {
                it.id != exercise.id && it.prescriptionCapability == ExercisePrescriptionCapability.REPS_LOAD
            }
            val hydrated = catalog.getExercisesByIds(listOf(secondExercise.id, exercise.id))
            check(hydrated is ExerciseHydrationResult.Success)
            assertEquals(listOf(secondExercise.id, exercise.id), hydrated.exercises.map { it.id })
            val missingId = "workspace-missing-${UUID.randomUUID()}"
            val mixed = catalog.getExercisesByIds(listOf(exercise.id, missingId))
            check(mixed is ExerciseHydrationResult.Missing)
            assertEquals(listOf(exercise.id), mixed.foundExercises.map { it.id })
            assertEquals(listOf(missingId), mixed.missingIds)

            val routines = ports.routines()
            val workouts = ports.workouts()
            val fixtureName = "Workspace Integration ${UUID.randomUUID()}"
            val createRequest = UUID.randomUUID().toString()
            var routine = routines.createRoutine(owner, fixtureName, requestId = createRequest).getOrThrow()
            var sessionId: String? = null
            var workoutId: String? = null
            var routineDeleted = false
            emitFixture(routine.id, null, "created")
            try {
                val replayedCreate = routines.createRoutine(owner, fixtureName, requestId = createRequest).getOrThrow()
                assertEquals(routine.id, replayedCreate.id)
                assertEquals(routine.revision, replayedCreate.revision)
                val saveRequest = UUID.randomUUID().toString()
                val prescription = listOf(
                    TemplateExercise(exercise, sets = 3, reps = 8, restSeconds = 30),
                    TemplateExercise(secondExercise, sets = 1, reps = 5, restSeconds = 15)
                )
                val emptyRevision = routine.revision
                routine = routines.saveRoutine(routine.id, fixtureName, prescription, emptyRevision,
                    requestId = saveRequest, baselineExerciseIds = emptyList()).getOrThrow()
                val replayedSave = routines.saveRoutine(routine.id, fixtureName, prescription, emptyRevision,
                    requestId = saveRequest, baselineExerciseIds = emptyList()).getOrThrow()
                assertEquals(routine.revision, replayedSave.revision)
                assertTrue("Same request ID with a different payload must fail",
                    routines.saveRoutine(routine.id, "$fixtureName changed", prescription, emptyRevision,
                        requestId = saveRequest, baselineExerciseIds = emptyList()).isFailure)
                val unchanged = requireNotNull(routines.getRoutineById(routine.id).getOrThrow())
                assertEquals(fixtureName, unchanged.name)
                assertEquals(routine.revision, unchanged.revision)
                routine = routines.saveRoutine(routine.id, fixtureName, prescription.take(1), routine.revision,
                    baselineExerciseIds = prescription.map { it.id }).getOrThrow()
                assertEquals(listOf(exercise.id), routine.exercises.map { it.id })
                assertTrue(database.workoutTemplateDao().getAllTemplatesByUserIncludingDeleted(owner).isEmpty())
                val reloaded = requireNotNull(routines.getRoutineById(routine.id).getOrThrow())
                assertEquals(routine.revision, reloaded.revision)
                assertEquals(3, reloaded.exercises.single().sets)
                assertEquals(8, reloaded.exercises.single().reps)
                assertEquals(30, reloaded.exercises.single().restSeconds)
                assertEquals(exercise.id, reloaded.exercises.single().id)

                val started = workouts.startWorkout(reloaded)
                sessionId = started.id
                assertEquals(started.id, database.activeSessionDao().getActiveSession(owner)?.id)
                assertEquals(3, workouts.getActiveSessionForTimer()?.exerciseLogs?.single()?.sets?.size)
                val repeatedStart = workouts.startWorkout(reloaded)
                assertEquals("Repeated start must resume the same local session", started.id, repeatedStart.id)
                assertEquals(started.exerciseLogs, repeatedStart.exerciseLogs)
                routine = routines.saveRoutine(routine.id, "$fixtureName edited",
                    reloaded.exercises.map { it.copy(sets = 4, reps = 12) }, routine.revision,
                    baselineExerciseIds = reloaded.exercises.map { it.id }).getOrThrow()
                assertEquals(4, routine.exercises.single().sets)
                assertEquals(12, routine.exercises.single().reps)
                val unchangedSession = requireNotNull(workouts.getActiveSessionForTimer())
                assertEquals(fixtureName, unchangedSession.templateName)
                assertEquals(3, unchangedSession.exerciseLogs.single().sets.size)
                assertEquals(8, unchangedSession.exerciseLogs.single().inputDefaults?.reps)
                assertEquals(started.exerciseLogs.single().sets, unchangedSession.exerciseLogs.single().sets)
                val edited = requireNotNull(workouts.mutateActiveSession(started.id) { current ->
                    current.copy(exerciseLogs = current.exerciseLogs.map { log ->
                        log.copy(sets = log.sets.mapIndexed { index, set ->
                            set.copy(weight = if (index == 0) 20f else 25f, reps = if (index == 0) 8 else 6, isCompleted = index < 2)
                        })
                    })
                })
                assertEquals(310f, edited.totalVolume, 0f)
                val finished = requireNotNull(workouts.finishWorkout()) { "Real Room completion failed" }
                workoutId = finished.id
                emitFixture(routine.id, finished.id, "finished")
                assertEquals(started.id, finished.id)
                assertNull(workouts.getActiveSessionForTimer())
                assertNull(database.activeSessionDao().getSessionById(started.id))
                assertNull("Duplicate finish must not create another log", workouts.finishWorkout())
                val local = requireNotNull(database.workoutLogDao().getWorkoutLogById(finished.id))
                assertEquals(2, local.totalSets)
                assertEquals(14, local.totalReps)
                assertEquals(310f, local.totalVolume, 0f)
                val snapshot = snapshot(database, finished.id)
                val remote = ImfitAggregateRemoteDataSource(client)
                val receipt = remote.upsertWorkout(snapshot.workout, snapshot.exercises, snapshot.sets)
                val replay = remote.upsertWorkout(snapshot.workout,
                    JsonArray(snapshot.exercises.reversed()), JsonArray(snapshot.sets.reversed()))
                assertEquals("Exact replay must return the original receipt", receipt, replay)
                assertEquals(finished.id, receipt.workoutId)
                assertEquals(1, receipt.exerciseCount)
                assertEquals(2, receipt.setCount)
                expectRejected("IDEMPOTENCY_MISMATCH") {
                    remote.upsertWorkout(JsonObject(snapshot.workout + ("total_volume" to JsonPrimitive(311))),
                        snapshot.exercises, snapshot.sets)
                }
                expectRejected("owner") {
                    remote.upsertWorkout(JsonObject(snapshot.workout + ("user_id" to JsonPrimitive(UUID.randomUUID().toString()))),
                        snapshot.exercises, snapshot.sets)
                }
                assertEquals(SyncRunResult.SUCCESS, ports.sync().run(owner))
                val synced = requireNotNull(database.workoutLogDao().getWorkoutLogById(finished.id))
                assertNull(synced.pendingOperation)
                assertEquals("SYNCED", synced.syncStatus)
                val remoteAggregate = requireNotNull(findRemoteAggregate(remote, finished.id))
                val remoteHistory = remoteAggregate.getValue("workout").jsonObject
                assertEquals(owner, remoteHistory.getValue("user_id").jsonPrimitive.content)
                assertEquals(310.0, remoteHistory.getValue("total_volume").jsonPrimitive.double, 0.001)
                assertEquals(2, remoteHistory.getValue("total_sets").jsonPrimitive.int)
                assertEquals(14, remoteHistory.getValue("total_reps").jsonPrimitive.int)
                val remoteExercises = remoteAggregate.getValue("exercises").jsonArray
                val remoteSets = remoteAggregate.getValue("sets").jsonArray
                assertEquals(1, remoteExercises.size)
                assertEquals(2, remoteSets.size)
                assertEquals(snapshot.exercises.single().jsonObject["input_defaults"], remoteExercises.single().jsonObject["input_defaults"])
                assertEquals(310.0, remoteSets.sumOf {
                    it.jsonObject.getValue("weight").jsonPrimitive.double * it.jsonObject.getValue("reps").jsonPrimitive.int
                }, 0.001)

                // Remove only this fixture's local aggregate, then exercise the
                // production sync importer and history repository, not DTO-only reads.
                removeLocalAggregate(database, finished.id)
                assertEquals(SyncRunResult.SUCCESS, ports.sync().run(owner))
                val restored = requireNotNull(workouts.getWorkoutLogById(finished.id))
                assertEquals(310f, restored.totalVolume, 0f)
                assertEquals(exercise.id, restored.exerciseLogs.single().exercise.id)
                assertEquals(2, restored.exerciseLogs.single().sets.size)
                assertEquals(14, restored.exerciseLogs.single().sets.sumOf { it.reps })
                assertEquals(listOf(20f, 25f), restored.exerciseLogs.single().sets.map { it.weight })
                assertEquals(snapshot.exercises.single().jsonObject["input_defaults"]?.jsonPrimitive?.contentOrNull,
                    database.exerciseLogDao().getExerciseLogsByWorkoutLogId(finished.id).single().inputDefaults)
                assertTrue(workouts.getWorkoutLogs(owner).any { it.id == finished.id })
                assertEquals(fixtureName, restored.templateName)
                assertTrue(routines.deleteRoutine(routine.id, routine.revision,
                    baselineExerciseIds = routine.exercises.map { it.id }, name = routine.name).getOrThrow())
                routineDeleted = true
                assertTrue(routines.getRoutines(owner).getOrThrow().none { it.id == routine.id })
                assertEquals(fixtureName, workouts.getWorkoutLogById(finished.id)?.templateName)
                assertNotNull(findRemoteAggregate(remote, finished.id))
                val beforeHomeExercises = database.exerciseLogDao().getExerciseLogsByWorkoutLogId(finished.id)
                val beforeHomeSets = database.workoutSetDao().getSetsByWorkoutLogId(finished.id)
                assertTrue("Home history must come from the real remote read", workouts.fetchRemoteLastWorkoutLog(owner).isFromRemote)
                assertEquals(beforeHomeExercises.map { it.copy(createdAt = 0, updatedAt = 0) },
                    database.exerciseLogDao().getExerciseLogsByWorkoutLogId(finished.id).map { it.copy(createdAt = 0, updatedAt = 0) })
                assertEquals(beforeHomeSets.map { it.copy(createdAt = 0, updatedAt = 0) },
                    database.workoutSetDao().getSetsByWorkoutLogId(finished.id).map { it.copy(createdAt = 0, updatedAt = 0) })
                assertTrue(database.workoutTemplateDao().getAllTemplatesByUserIncludingDeleted(owner).isEmpty())
                emitFixture(routine.id, finished.id, "verified_history_receipt_replay")
            } finally {
                withContext(NonCancellable) {
                    withTimeout(30_000) {
                        try {
                            if (workouts.getActiveSessionForTimer()?.id == sessionId) workouts.cancelWorkout()
                            if (!routineDeleted) {
                                val latest = routines.getRoutineById(routine.id).getOrThrow()
                                if (latest != null) {
                                    routines.deleteRoutine(latest.id, latest.revision,
                                        baselineExerciseIds = latest.exercises.map { it.id }, name = latest.name).getOrThrow()
                                }
                            }
                        } finally {
                            workoutId?.let { removeLocalAggregate(database, it) }
                            emitFixture(routine.id, workoutId, "scoped_server_cleanup_required")
                        }
                    }
                }
            }
        }
    }

    /** After administrator cleanup of emitted IDs, remove only the local fixture. */
    @Test
    fun cleanupLocalFixture() = runBlocking(Dispatchers.IO) {
        assumeTrue(arguments.getString("allowAuthenticatedWorkspaceMutation") == "true")
        val id = arguments.getString("workspaceFixtureWorkoutId") ?: return@runBlocking
        UUID.fromString(id)
        val ports = graph
        ports.supabase().auth.awaitInitialization()
        val owner = requireNotNull(ports.supabase().auth.currentUserOrNull()?.id)
        val local = ports.database().workoutLogDao().getWorkoutLogById(id)
        if (local != null) {
            check(local.userId == owner && local.templateName.startsWith("Workspace Integration "))
            check(findRemoteAggregate(ImfitAggregateRemoteDataSource(ports.supabase()), id) == null) {
                "Scoped server cleanup must run first"
            }
            removeLocalAggregate(ports.database(), id)
        }
    }

    private fun emitFixture(routineId: String, workoutId: String?, phase: String) {
        val evidence = "phase=$phase routine_id=$routineId workout_id=${workoutId ?: "none"}"
        Log.i("IMFITWorkspaceTest", evidence)
        instrumentation.sendStatus(0, Bundle().apply { putString("workspace_fixture", evidence) })
    }

    private suspend fun expectRejected(message: String, block: suspend () -> Unit) {
        val error = try { block(); null }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { e }
        assertNotNull("Expected rejected request", error)
        assertTrue("Expected rejection category $message", error?.message?.contains(message, ignoreCase = true) == true)
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
            val items = page.getValue("items").jsonArray
            items.map { it.jsonObject }.singleOrNull {
                it.getValue("workout").jsonObject.getValue("id").jsonPrimitive.content == id
            }?.let { return it }
            val next = page["next_cursor"]?.jsonPrimitive?.contentOrNull
            check(next == null || (cursor == null || next > cursor)) { "Invalid history cursor" }
            cursor = next
        } while (cursor != null)
        return null
    }

    private data class Snapshot(val workout: JsonObject, val exercises: JsonArray, val sets: JsonArray)

    /** Match the production sync wire contract, including optional provenance. */
    private suspend fun snapshot(database: IMFITDatabase, id: String): Snapshot {
        val log = requireNotNull(database.workoutLogDao().getWorkoutLogById(id))
        val start = Instant.ofEpochMilli(log.startTime).atOffset(ZoneOffset.UTC)
        val end = Instant.ofEpochMilli(log.endTime).atOffset(ZoneOffset.UTC)
        return Snapshot(buildJsonObject {
            put("id", log.id); put("user_id", log.userId)
            log.templateId?.let { put("template_id", it) }
            put("template_name", log.templateName); put("date", start.toLocalDate().toString())
            put("start_time", start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            put("end_time", end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            put("total_volume", log.totalVolume.toDouble()); put("total_sets", log.totalSets); put("total_reps", log.totalReps)
        }, JsonArray(database.exerciseLogDao().getExerciseLogsByWorkoutLogId(id).map { exercise ->
            buildJsonObject {
                put("id", exercise.id); put("workout_log_id", id); put("exercise_id", exercise.exerciseId)
                put("exercise_name", exercise.exerciseName); put("muscle_category", exercise.muscleCategory)
                put("order_index", exercise.orderIndex); put("total_volume", exercise.totalVolume.toDouble())
                put("total_sets", exercise.totalSets); put("total_reps", exercise.totalReps)
                exercise.inputDefaults?.let { put("input_defaults", it) }
            }
        }), JsonArray(database.workoutSetDao().getSetsByWorkoutLogId(id).map { set ->
            buildJsonObject {
                put("id", set.id); put("exercise_log_id", set.exerciseLogId); put("workout_log_id", id)
                put("exercise_id", set.exerciseId); put("set_number", set.setNumber)
                put("weight", set.weight.toDouble()); put("reps", set.reps); put("is_completed", set.isCompleted)
            }
        }))
    }
}
