package com.diajarkoding.imfit.data.remote.online

import com.diajarkoding.imfit.data.legacy.*
import com.diajarkoding.imfit.data.local.FakeUserDataSource
import com.diajarkoding.imfit.data.local.FakeWorkoutDataSource
import com.diajarkoding.imfit.data.repository.DemoAuthRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Demo transport contract: confirmed writes, terminal receipt recognition, idempotency and CAS. */
@OptIn(ExperimentalCoroutinesApi::class)
class DemoSessionLifecycleTest {
    init { Dispatchers.setMain(Dispatchers.Unconfined) }

    @After fun reset() {
        // Keep a permanent Main installed (no scheduler ownership) and stop this fixture's identity.
        FakeUserDataSource.logout()
    }

    @Test fun `demo create finishes and a new create is recognized after terminal receipts`() = runTest {
        FakeUserDataSource.login(DemoAuthRepository.DEMO_EMAIL, DemoAuthRepository.DEMO_PASSWORD).getOrThrow()
        val reads = DemoReadGateway(DemoAuthRepository())
        val inventory = LegacyInventoryReader { LegacyInventory.Absent(it) }
        val preflight = LegacyStartPreflight(reads, inventory,
            object : LegacyCheckpointStore {
                override suspend fun load(ownerId: String): LegacyCheckpoint? = null
                override suspend fun record(checkpoint: LegacyCheckpoint) = Unit
            }, testRecoveryCoordinator(reads, reads.repository, inventory))
        val store = OnlineSessionStore(reads, reads.repository, LegacyGuardedSessionStart(preflight, reads.repository), scope = backgroundScope)
        val workouts = com.diajarkoding.imfit.data.repository.OnlineWorkoutRepository(reads, store,
            TemplateEditBridge(reads) { _, _ -> })
        val template = FakeWorkoutDataSource.getTemplateById("template_1")!!

        val session = workouts.startWorkout(template)
        assertTrue(session.exerciseLogs.isNotEmpty())

        val child = store.state.value.snapshot!!.exercises.first()
        val set = child.sets.first()
        val key = SessionSetKey(child.id, set.id)
        store.editSet(session.id, key, "10", "8")
        // Complete flushes raw input before using the acknowledged next CAS version.
        val completed = store.command(session.id, SessionCommand.CompleteSet(child.id, set.id))
        assertTrue(completed.exercises.first().sets.first().isCompleted)
        assertEquals("10.00", completed.exercises.first().sets.first().weight)

        val workoutId = workouts.finishWorkout()!!
        val log = workouts.getWorkoutLogById(workoutId)
        assertEquals(session.id, workoutId)
        assertEquals(80f, log.exerciseLogs.sumOf { it.totalVolume.toDouble() }.toFloat(), 0.001f)
        assertNotNull(FakeWorkoutDataSource.getWorkoutLogById(workoutId))
        assertNull(workouts.getActiveSessionForTimer())

        // Authoritative readiness allows a fresh create despite historical terminal receipts.
        val next = workouts.startWorkout(template)
        assertTrue(next.id != session.id)
        assertTrue(reads.repository.confirmedSessions.value.containsKey(next.id))

        // The still-active replacement routes to recovery (LegacyStartBlocked), not a raw conflict.
        val conflict = kotlin.runCatching { workouts.startWorkout(template) }.exceptionOrNull()
        val blocked = conflict as? com.diajarkoding.imfit.data.remote.online.LegacyStartBlocked
        assertNotNull(blocked)
        assertTrue(blocked!!.report.decision == com.diajarkoding.imfit.data.legacy.LegacyStartDecision.RECOVERY_REQUIRED)
    }

    @Test fun `demo server rejects conflicting replays and wrong CAS versions`() = runTest {
        val api = DemoSessionApi()
        val owner = "user_1"
        val create = buildJsonObject {
            put("p_request_id", JsonPrimitive("req-1"))
            put("p_session_id", JsonPrimitive("demo-session"))
            put("p_template_id", JsonPrimitive("template_1"))
            put("p_expected_template_revision", JsonPrimitive(1L))
        }
        val first = api.post(owner, "create_active_session", create)
        assertEquals(200, first.status)
        assertEquals(200, api.post(owner, "create_active_session", create).status) // replay returns the receipt
        assertEquals(409, api.post(owner, "create_active_session", buildJsonObject {
            put("p_request_id", JsonPrimitive("req-2"))
            put("p_session_id", JsonPrimitive("demo-session"))
            put("p_template_id", JsonPrimitive("template_1"))
            put("p_expected_template_revision", JsonPrimitive(1L))
        }).status) // an ACTIVE session already exists for the owner

        val wrongVersion = buildJsonObject {
            put("p_request_id", JsonPrimitive("req-3"))
            put("p_session_id", JsonPrimitive("demo-session"))
            put("p_expected_version", JsonPrimitive(99L))
            put("p_command", buildJsonObject { put("type", JsonPrimitive("edit_set")); put("exercise_log_id", JsonPrimitive("x")); put("set_id", JsonPrimitive("y")); put("weight", JsonPrimitive("10.00")); put("reps", JsonPrimitive(10)) })
        }
        assertEquals(409, api.post(owner, "update_active_session", wrongVersion).status)
        assertTrue(api.read(owner, "get_operation_status", buildJsonObject {
            put("p_request_id", JsonPrimitive("req-3"))
        }).jsonObject.getValue("state")!!.jsonPrimitive.content == "NOT_FOUND")
        assertTrue(api.read(owner, "get_operation_status", buildJsonObject {
            put("p_request_id", JsonPrimitive("req-1"))
        }).jsonObject.getValue("state")!!.jsonPrimitive.content == "COMMITTED")
        // The receipt replay must reject a modified body with the same request id.
        assertEquals(409, api.post(owner, "create_active_session", buildJsonObject {
            put("p_request_id", JsonPrimitive("req-1"))
            put("p_session_id", JsonPrimitive("different-session"))
            put("p_template_id", JsonPrimitive("template_1"))
            put("p_expected_template_revision", JsonPrimitive(1L))
        }).status)
    }
}
