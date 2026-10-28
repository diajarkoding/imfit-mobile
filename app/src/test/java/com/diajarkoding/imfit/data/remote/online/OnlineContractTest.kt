package com.diajarkoding.imfit.data.remote.online

import com.diajarkoding.imfit.data.remote.TemplateWriteRequest
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OnlineContractTest {
    private val window = DateWindow("2026-10-01", "2026-11-01", "Asia/Jakarta")

    @Test fun `all sixteen typed calls emit exact SQL parameter names`() {
        val calls = listOf(
            OnlineRpc.saveTemplate(TemplateWriteRequest("r", "t", null, "Name", emptyList(), emptyList(), ownerId = "owner")) to
                "request_id template_id expected_revision name exercises removed_exercise_ids is_deleted owner_id",
            OnlineRpc.history(window) to "start_date end_date timezone cursor limit",
            OnlineRpc.catalog() to "search category_id cursor limit",
            OnlineRpc.templates() to "cursor limit",
            OnlineRpc.workoutDetail("id") to "workout_id",
            OnlineRpc.templateDetail("id") to "template_id",
            OnlineRpc.progress(window) to "start_date end_date timezone",
            OnlineRpc.dateMarkers(window) to "start_date end_date timezone",
            OnlineRpc.prefill("squat", InputDefaults(3, 10)) to "exercise_id input_defaults",
            OnlineRpc.createSession("r", "s", "t", 2147483648) to "request_id session_id template_id expected_template_revision",
            OnlineRpc.activeSession() to "session_id",
            OnlineRpc.updateSession("r", "s", 2147483648, SessionCommand.Pause) to "request_id session_id expected_version command",
            OnlineRpc.cancelSession("r", "s", 1) to "request_id session_id expected_version",
            OnlineRpc.finishSession("r", "s", 1) to "request_id session_id expected_version",
            OnlineRpc.operationStatus("r") to "request_id",
            OnlineRpc.recentOperations("2026-10-01T00:00:00Z", "2026-11-01T00:00:00Z") to "from until cursor limit",
        )
        assertEquals(16, calls.map { it.first.name }.distinct().size)
        calls.forEach { (call, params) -> assertEquals(call.name, params.split(' ').map { "p_$it" }.toSet(), call.parameters.keys) }
        assertEquals(JsonNull, calls[0].first.parameters["p_expected_revision"])
        assertEquals(JsonNull, OnlineRpc.activeSession().parameters["p_session_id"])
        assertEquals(JsonPrimitive(2147483648L), calls[9].first.parameters["p_expected_template_revision"])
    }

    @Test fun `all typed commands have exact discriminators and no Kotlin class names`() {
        val commands = listOf(
            SessionCommand.EditSet("e", "s", "5", 10), SessionCommand.CompleteSet("e", "s"),
            SessionCommand.UncompleteSet("e", "s"), SessionCommand.AddSet("e", "s"), SessionCommand.RemoveSet("e", "s"),
            SessionCommand.AddExercise("e", "squat"), SessionCommand.RemoveExercise("e"),
            SessionCommand.SetRestSeconds("e", 90), SessionCommand.SelectExercise("e"),
            SessionCommand.Pause, SessionCommand.Resume, SessionCommand.AcknowledgeExpiry,
        )
        val types = commands.map { OnlineRpc.updateSession("r", "s", 1, it).parameters.getValue("p_command").jsonObject.getValue("type").jsonPrimitive.content }
        assertEquals(listOf("edit_set", "complete_set", "uncomplete_set", "add_set", "remove_set", "add_exercise", "remove_exercise", "set_rest_seconds", "select_exercise", "pause", "resume", "acknowledge_expiry"), types)
        assertEquals(buildJsonObject { put("type", "pause") }, OnlineRpc.updateSession("r", "s", 1, SessionCommand.Pause).parameters["p_command"])
    }

    @Test fun `new decimal input is exact canonical and rejects excess precision without rounding`() {
        val bodies = listOf("5", "5.0", "5.00").map { OnlineRpc.updateSession("r", "s", 1, SessionCommand.EditSet("e", "s", it, 10)).parameters }
        assertEquals(1, bodies.distinct().size)
        assertEquals(JsonPrimitive("5.00"), bodies[0].getValue("p_command").jsonObject["weight"])
        listOf("5.001", "-1", "NaN", "Infinity", "1e2", "1000.01", ".5", "").forEach {
            assertThrows(IllegalArgumentException::class.java) { SessionCommand.EditSet("e", "s", it, 10) }
        }
    }

    @Test fun `summary preserves legacy decimals microseconds and bigint without float`() {
        val decoded = onlineJson.decodeFromString<WorkoutSummary>(OnlineFixtures.summary)
        assertEquals("123456789.123456789", decoded.totalVolume)
        assertEquals(2147483648L, decoded.durationSeconds)
        assertEquals(OnlineFixtures.time, decoded.startTime)
        assertEquals(onlineJson.parseToJsonElement(OnlineFixtures.summary), onlineJson.encodeToJsonElement(decoded))
    }

    @Test fun `required nullable fields and counters cannot silently disappear or become zero`() {
        val obj = onlineJson.parseToJsonElement(OnlineFixtures.summary).jsonObject
        listOf("id", "template_id", "total_volume", "total_sets", "end_time").forEach { field ->
            assertThrows(SerializationException::class.java) {
                onlineJson.decodeFromString<WorkoutSummary>(JsonObject(obj - field).toString())
            }
        }
        assertThrows(SerializationException::class.java) {
            onlineJson.decodeFromString<WorkoutSummary>(JsonObject(obj + ("total_volume" to JsonPrimitive(1.5))).toString())
        }
    }

    @Test fun `session snapshot ordered children stable numbering and additive fields decode`() {
        val snapshot = onlineJson.decodeFromString<SessionSnapshot>(OnlineFixtures.snapshot)
        assertEquals(2147483648L, snapshot.templateRevision)
        assertEquals(5, snapshot.exercises.single().sets.single().setNumber)
        assertNull(snapshot.templateId)
        assertEquals(SessionStatus.PAUSED, snapshot.effectiveStatus)
        assertThrows(SerializationException::class.java) {
            onlineJson.decodeFromString<SessionSnapshot>(OnlineFixtures.snapshot.replace("PAUSED", "UNKNOWN"))
        }
    }

    @Test fun `all response DTO families decode bounded legitimate empty and nested responses`() {
        assertTrue(onlineJson.decodeFromString<RemotePage<WorkoutSummary>>(OnlineFixtures.emptyPage).data.isEmpty())
        assertFalse(onlineJson.decodeFromString<SessionLookup>("""{"found":false,"session":null}""").found)
        assertEquals(OperationState.NOT_FOUND, onlineJson.decodeFromString<OperationLookup>("""{"state":"NOT_FOUND","receipt":null}""").state)
        assertEquals(0L, onlineJson.decodeFromString<ProgressSummary>("""{"start_date":"2026-10-01","end_date":"2026-11-01","timezone":"UTC","workout_count":0,"total_volume":"0","total_sets":0,"total_reps":0,"duration_seconds":0}""").workoutCount)
        assertTrue(onlineJson.decodeFromString<DateMarkers>("""{"start_date":"2026-10-01","end_date":"2026-11-01","timezone":"UTC","dates":[]}""").dates.isEmpty())
        assertEquals(PrefillSource.DEFAULTS, onlineJson.decodeFromString<ExercisePrefill>("""{"source":"DEFAULTS","source_workout_id":null,"input_defaults":{"sets":3,"reps":10},"sets":[{"set_number":1,"weight":"0.00","reps":10}]}""").source)
        assertEquals(3L, onlineJson.decodeFromString<TemplateDetail>("""{"id":"t","name":"Routine","revision":3,"updated_at":"${OnlineFixtures.time}","exercises":[{"id":"e","exercise_id":"squat","order_index":0,"sets":3,"reps":10,"rest_seconds":60}]}""").revision)
        assertEquals(3L, onlineJson.decodeFromString<TemplateSummary>("""{"id":"t","name":"Routine","revision":3,"updated_at":"${OnlineFixtures.time}","exercise_count":1}""").revision)
        assertNull(onlineJson.decodeFromString<ExerciseSummary>("""{"id":"squat","muscle_category_id":1,"name":"Squat","image_url":null,"difficulty":null,"equipment":null}""").imageUrl)
        val detail = OnlineFixtures.summary.dropLast(1) + """, "exercise_logs":[{"id":"e","exercise_id":"squat","exercise_name":"Squat","muscle_category":"Legs","order_index":0,"input_defaults":null,"total_volume":"0.1234","total_sets":1,"total_reps":10,"sets":[{"id":"s","set_number":5,"weight":"0.1234","reps":10,"is_completed":true}]}]}"""
        assertEquals("0.1234", onlineJson.decodeFromString<WorkoutDetail>(detail).exerciseLogs.single().sets.single().weight)
    }

    @Test fun `query bounds and normalization are explicit`() {
        assertEquals("squat", normalizeSearch(" SQUAT "))
        assertThrows(IllegalArgumentException::class.java) { OnlineRpc.catalog("x".repeat(101)) }
        assertThrows(IllegalArgumentException::class.java) { OnlineRpc.templates(limit = 101) }
        assertThrows(IllegalArgumentException::class.java) { DateWindow("2026-10-01", "2026-10-01", "UTC") }
        assertThrows(IllegalArgumentException::class.java) { DateWindow("2026-10-01", "2026-11-01", "PST") }
        assertThrows(IllegalArgumentException::class.java) { OnlineRpc.recentOperations("2026-01-01T00:00:00Z", "2026-10-01T00:00:00Z") }
    }
}
