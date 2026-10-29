package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.legacy.*
import com.diajarkoding.imfit.data.remote.online.*
import com.diajarkoding.imfit.domain.model.ExerciseInputDefaults
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Runtime facade contract: memory-peek timer reads, receipt-driven terminal writes, no legacy bulk reads. */
class OnlineWorkoutRepositoryTest {
    private val reads = ScriptedReadGateway()
    private val inventory = LegacyInventoryReader { LegacyInventory.Absent(it) }
    private val preflight = LegacyStartPreflight(reads, inventory,
        object : LegacyCheckpointStore {
            override suspend fun load(ownerId: String): LegacyCheckpoint? = null
            override suspend fun record(checkpoint: LegacyCheckpoint) = Unit
        }, testRecoveryCoordinator(reads, reads.repository, inventory))
    private val store = OnlineSessionStore(reads, reads.repository, LegacyGuardedSessionStart(preflight, reads.repository))
    private val repo = OnlineWorkoutRepository(reads, store, TemplateEditBridge(reads) { _, _ -> })

    private suspend fun load(version: Long = 2, rest: Int = 60): WorkoutSession? = coroutineScope {
        val job = async { repo.getActiveSession() }
        reads.api.requests.receive().also { assertEquals("get_active_session", it.rpc)
            it.reply("""{"found":true,"session":${
                OnlineFixtures.snapshot.replace("\"version\":2", "\"version\":$version")
                    .replace("\"rest_seconds\":60", "\"rest_seconds\":$rest")}}""") }
        job.await()
    }

    private fun FakeOnlineApi.Request.receipt(version: Long, operation: String, status: String, rest: Int = 60) = reply(buildJsonObject {
        val session = body.getValue("p_session_id")!!.jsonPrimitive.content
        put("request_id", body.getValue("p_request_id")!!.jsonPrimitive.content)
        put("session_id", session); put("version", JsonPrimitive(version))
        put("operation", operation); put("status", status); put("committed_at", OnlineFixtures.time)
        val snap = onlineJson.parseToJsonElement(OnlineFixtures.snapshot.replace("\"rest_seconds\":60", "\"rest_seconds\":$rest"))
            .jsonObject.toMutableMap()
        snap["id"] = JsonPrimitive(session)
        snap["version"] = JsonPrimitive(version); snap["status"] = JsonPrimitive(status)
        snap["effective_status"] = JsonPrimitive(status)
        if (operation == "finish_active_session") snap["completed_workout_id"] = JsonPrimitive("workout")
        put("snapshot", JsonObject(snap))
        if (operation == "finish_active_session") put("workout_id", JsonPrimitive("workout"))
    }.toString())

    private suspend fun fails(call: suspend () -> Any?) = assertTrue(runCatching { call() }.isFailure)
    private var minted = ""

    private val workoutDetail = """{"id":"workout","template_id":null,"template_name":"Routine",
        "start_time":"${OnlineFixtures.time}","end_time":"${OnlineFixtures.time}","duration_seconds":60,
        "total_volume":"125.50","total_sets":1,"total_reps":10,
        "exercise_logs":[{"id":"log","exercise_id":"squat","exercise_name":"Squat","muscle_category":"NOT_A_CATEGORY",
          "order_index":0,"input_defaults":{"sets":3,"reps":10},"total_volume":"125.50","total_sets":1,"total_reps":10,
          "sets":[{"id":"set","set_number":5,"weight":"10.00","reps":10,"is_completed":true}]}]}"""

    @Test fun `rest override reads come from confirmed memory with no per-read requests`() = runTest {
        load()
        assertEquals(60, repo.getSessionRestOverride())
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `terminal completion returns receipt identity without depending on a detail fetch`() = runTest {
        load()
        val finished = async { repo.finishWorkout() }
        reads.api.requests.receive().also { assertEquals("finish_active_session", it.rpc); it.receipt(3, it.rpc, "COMPLETED") }
        assertEquals("workout", finished.await())
        assertNull(repo.getActiveSessionForTimer())
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `cancel sends one acknowledgement and then the session is terminal`() = runTest {
        load()
        val cancelled = async { runCatching { repo.cancelWorkout() } }
        reads.api.requests.receive().also { assertEquals("cancel_active_session", it.rpc); it.receipt(3, it.rpc, "CANCELLED") }
        assertTrue(cancelled.await().isSuccess)
        assertNull(repo.getActiveSessionForTimer())
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `cold cancel confirms the remote session before the terminal receipt`() = runTest {
        val cancelled = async { runCatching { repo.cancelWorkout() } }
        reads.api.requests.receive().also { request ->
            assertEquals("get_active_session", request.rpc)
            request.reply("""{"found":true,"session":${OnlineFixtures.snapshot}}""")
            reads.api.requests.receive().also {
                assertEquals("cancel_active_session", it.rpc); it.receipt(3, "cancel_active_session", "CANCELLED")
            }
        }
        assertTrue(cancelled.await().isSuccess)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `cold cancel without a remote session is a silent no-op`() = runTest {
        val cancelled = async { repo.cancelWorkout() }
        reads.api.requests.receive().also { request ->
            assertEquals("get_active_session", request.rpc)
            request.reply("""{"found":false,"session":null}""")
        }
        cancelled.await()
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `cancel after a terminal confirmation is a no-op`() = runTest {
        load()
        val finished = async { repo.finishWorkout() }
        reads.api.requests.receive().also { assertEquals("finish_active_session", it.rpc); it.receipt(3, it.rpc, "COMPLETED") }
        assertNotNull(finished.await())
        // The in-memory projection is terminal: cancelling again must not dispatch anything.
        repo.cancelWorkout()
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `rest override fans out one command per differing child and stops when uniform`() = runTest {
        load()
        fails { repo.updateSessionRestOverride(601) }
        assertTrue(reads.api.requests.tryReceive().isFailure)
        val fanout = async { repo.updateSessionRestOverride(90) }
        reads.api.requests.receive().also {
            assertEquals("update_active_session", it.rpc)
            assertEquals(2L, it.body.getValue("p_expected_version")!!.jsonPrimitive.long)
            it.receipt(3, "update_active_session", "PAUSED", rest = 90)
        }
        fanout.await()
        assertTrue(reads.api.requests.tryReceive().isFailure)
        async { repo.updateSessionRestOverride(90) }.await()
        assertTrue(reads.api.requests.tryReceive().isFailure)
        assertEquals(90, repo.getSessionRestOverride())
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `timer identity answers come from memory and gate by owner`() = runTest {
        load()
        assertTrue(repo.isActiveSessionForTimer("A", "session"))
        assertFalse(repo.isActiveSessionForTimer("B", "session"))
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `prefill reads defaults for the logged owner only`() = runTest {
        load()
        fails { repo.getPrefillForExercise("squat", "someone-else", ExerciseInputDefaults(3, 10)) }
        val prefill = async { repo.getPrefillForExercise("squat", "A", ExerciseInputDefaults(3, 10)) }
        reads.api.requests.receive().also {
            assertEquals("get_exercise_prefill", it.rpc)
            assertEquals("squat", it.body.getValue("p_exercise_id")!!.jsonPrimitive.content)
            it.reply("""{"source":"HISTORY","source_workout_id":"workout","input_defaults":{"sets":3,"reps":10},
                "sets":[{"set_number":1,"weight":"45.00","reps":8},{"set_number":2,"weight":"50.00","reps":8}]}""")
        }
        assertEquals(mapOf(1 to (45f to 8), 2 to (50f to 8)), prefill.await())
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `start requires an owned template and dispatches create through the guarded bridge`() = runTest {
        fails { repo.startWorkout(WorkoutTemplate("t", "B", "Routine", emptyList<TemplateExercise>(), 0L)) }
        assertTrue(reads.api.requests.tryReceive().isFailure)
        val started = async { runCatching { repo.startWorkout(WorkoutTemplate("template", "A", "Routine", emptyList<TemplateExercise>(), 0L)) } }
        reads.api.requests.receive().also {
            assertEquals("get_template_detail", it.rpc)
            assertEquals("template", it.body.getValue("p_template_id")!!.jsonPrimitive.content)
            it.reply("""{"id":"template","name":"Routine","revision":1,"updated_at":"${OnlineFixtures.time}","exercises":[]}""")
        }
        reads.api.requests.receive().also { assertEquals("get_start_recovery_status", it.rpc)
            it.reply(LegacyReadinessFixtures.ready) }
        reads.api.requests.receive().also { assertEquals("create_active_session", it.rpc); it.receipt(1, it.rpc, "ACTIVE")
            minted = it.body.getValue("p_session_id")!!.jsonPrimitive.content }
        val session = started.await().getOrThrow()
        assertEquals(minted, session.id)
        assertEquals("Routine", session.templateName)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }
}
