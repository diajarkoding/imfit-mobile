package com.diajarkoding.imfit.data.remote.online

import java.time.Instant
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class DemoLegacyRecoveryTest {
    private val id = "00000000-0000-0000-0000-000000000001"
    private val request = "00000000-0000-0000-0000-000000000002"
    private val owner = "00000000-0000-0000-0000-000000000003"
    private val fingerprint = "a".repeat(64)
    @Test fun setRemovalRetainsNumberingAndStaleCasCannotCancel() {
        val now = Instant.parse("2026-10-29T00:00:00Z")
        val api = DemoSessionApi(clock = { now })
        val childId = "child"
        api.retainSession(owner, SessionSnapshot(id, 1, SessionStatus.ACTIVE, SessionStatus.ACTIVE, false,
            null, "Routine", 1, now.minusSeconds(60).toString(), null, 0, now.toString(), now.plusSeconds(60).toString(),
            1, childId, listOf(SessionExercise(childId, "exercise", "Lift", 0, 60, InputDefaults(3, 10),
                listOf(WorkoutSet("one", 1, "0", 10, false), WorkoutSet("two", 2, "0", 10, false), WorkoutSet("three", 3, "0", 10, false)))), null))
        val remove = OnlineRpc.updateSession(request, id, 1, SessionCommand.RemoveSet(childId, "two"))
        val removed = api.post(owner, remove.name, remove.parameters)
        assertEquals(200, removed.status)
        val snapshot = onlineJson.decodeFromString<SessionMutationReceipt>(removed.body).snapshot
        assertEquals(listOf(1, 3), snapshot.exercises.single().sets.map { it.setNumber })
        val stale = OnlineRpc.cancelSession("stale", id, 1)
        assertEquals(409, api.post(owner, stale.name, stale.parameters).status)
        val pause = OnlineRpc.updateSession("pause", id, 2, SessionCommand.Pause)
        assertEquals(200, api.post(owner, pause.name, pause.parameters).status)
        val cancel = OnlineRpc.cancelSession("cancel", id, 3)
        val terminal = onlineJson.decodeFromString<SessionMutationReceipt>(api.post(owner, cancel.name, cancel.parameters).body)
        assertEquals(SessionStatus.CANCELLED, terminal.status)
        assertNull(terminal.snapshot.pausedAt)
    }

    @Test fun expiredSessionRequiresExplicitAcknowledgementAndStaleCasDoesNotWrite() {
        val now = Instant.parse("2026-10-29T00:00:00Z")
        val api = DemoSessionApi(clock = { now })
        api.retainSession(owner, SessionSnapshot(id, 3, SessionStatus.PAUSED, SessionStatus.PAUSED, false,
            null, "Legacy routine", 1, now.minusSeconds(60).toString(), now.minusSeconds(10).toString(), 0,
            now.minusSeconds(60).toString(), now.minusSeconds(1).toString(), 1, null, emptyList(), null))
        val pause = OnlineRpc.updateSession(request, id, 3, SessionCommand.Resume)
        assertEquals(409, api.post(owner, pause.name, pause.parameters).status)
        val ack = OnlineRpc.updateSession(request, id, 3, SessionCommand.AcknowledgeExpiry)
        val response = api.post(owner, ack.name, ack.parameters)
        assertEquals(200, response.status)
        val receipt = onlineJson.decodeFromString<SessionMutationReceipt>(response.body)
        assertEquals(SessionStatus.EXPIRED, receipt.status)
        assertFalse(receipt.snapshot.reviewRequired)
        assertNull(receipt.snapshot.pausedAt)
        assertEquals(10000L, receipt.snapshot.totalPausedMs)
        val status = OnlineRpc.startRecoveryStatus()
        assertTrue(onlineJson.decodeFromJsonElement<StartRecoveryStatus>(api.read(owner, status.name, status.parameters)).canCreate)
    }

    @Test fun importDeduplicatesOriginalBodyAndRejectsChangedSource() {
        val api = DemoSessionApi()
        val source = buildJsonObject {
            put("schema_version", 9); put("kind", "WORKOUT"); put("id", id)
            put("fingerprint", fingerprint); put("mapping_version", 1)
        }
        val workout = buildJsonObject {
            put("id", id); put("user_id", owner); put("total_volume", "0.000")
            put("total_sets", 0); put("total_reps", 0)
            put("template_name", "Archive"); put("date", "2026-10-29")
            put("start_time", "2026-10-29T00:00:00Z"); put("end_time", "2026-10-29T00:00:01Z")
            put("created_at", "2026-10-29T00:00:00Z"); put("updated_at", "2026-10-29T00:00:01Z")
        }
        val call = OnlineRpc.importLegacyWorkout(request, source, workout, JsonArray(emptyList()), JsonArray(emptyList()))
        val first = api.post(owner, call.name, call.parameters)
        assertEquals(200, first.status)
        assertEquals(first, api.post(owner, call.name, call.parameters))
        val retry = OnlineRpc.importLegacyWorkout("00000000-0000-0000-0000-000000000004", source, workout,
            JsonArray(emptyList()), JsonArray(emptyList()))
        val second = api.post(owner, retry.name, retry.parameters)
        assertEquals(200, second.status)
        val receipt = onlineJson.decodeFromString<LegacyWorkoutImportReceipt>(second.body)
        assertEquals(id, receipt.workoutId)
        val changed = OnlineRpc.importLegacyWorkout("00000000-0000-0000-0000-000000000005",
            JsonObject(source + ("fingerprint" to JsonPrimitive("b".repeat(64)))), workout, JsonArray(emptyList()), JsonArray(emptyList()))
        assertEquals(409, api.post(owner, changed.name, changed.parameters).status)
        assertEquals(404, api.post("other-owner", retry.name, retry.parameters).status)
    }
    @Test fun archiveIsOwnerScopedReplayableAndRemovesOnlyReviewedBlocker() {
        val api = DemoSessionApi(clock = { Instant.parse("2026-10-29T00:00:00Z") })
        api.retainLegacy(owner, LegacyRecoveryItem(id, fingerprint, null,
            buildJsonObject { put("id", id); put("user_id", owner); put("version", JsonNull); put("schema_version", JsonNull) }))
        val status = OnlineRpc.startRecoveryStatus()
        fun readiness(who: String) = onlineJson.decodeFromJsonElement<StartRecoveryStatus>(api.read(who, status.name, status.parameters))
        assertFalse(readiness(owner).canCreate)
        assertTrue(readiness("other-owner").canCreate)
        val call = OnlineRpc.resolveLegacySession(request, id, fingerprint)
        val first = api.post(owner, call.name, call.parameters)
        assertEquals(first, api.post(owner, call.name, call.parameters))
        assertTrue(readiness(owner).canCreate)
        val page = OnlineRpc.legacyRecoveryPage()
        val retained = onlineJson.decodeFromJsonElement<RemotePage<LegacyRecoveryItem>>(api.read(owner, page.name, page.parameters))
        assertEquals(LegacyResolution.KEEP_ARCHIVED, retained.data.single().resolution)
        assertEquals(JsonPrimitive(owner), retained.data.single().rawSource["user_id"])
        val changed = OnlineRpc.resolveLegacySession(request, id, "b".repeat(64))
        assertNotEquals(first, api.post(owner, changed.name, changed.parameters))
    }
}
