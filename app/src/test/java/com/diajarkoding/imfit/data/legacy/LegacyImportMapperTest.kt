package com.diajarkoding.imfit.data.legacy

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

/** Behavioral mapping tests use actual SQLite text reads of exported 6/7/9 schemas. */
class LegacyImportMapperTest {
    private val owner = id(1)
    private val workout = id(2)
    private val template = id(3)
    private val benchLog = id(4)
    private val rowLog = id(5)
    private val benchSet = id(6)
    private val unfinishedSet = id(7)
    private val rowSet = id(8)
    private val epoch = 1735689600123L
    private fun id(n: Int) = "00000000-0000-0000-0000-${n.toString().padStart(12, '0')}"
    private fun fixture(version: Int = 9, work: LegacySqlInventoryTest.Fixture.() -> Unit) =
        LegacySqlInventoryTest().fixture(version) { seed(version); work() }

    private fun LegacySqlInventoryTest.Fixture.seed(version: Int) {
        insert("workout_templates", "id" to template, "user_id" to owner)
        insert("template_exercises", "template_id" to template, "exercise_id" to "bench")
        insert("exercises", "id" to "bench", "name" to "Bench")
        insert("exercises", "id" to "row", "name" to "Row")
        insert("workout_logs", "id" to workout, "user_id" to owner, "template_id" to template,
            "template_name" to "Original routine", "pending_operation" to "CREATE", "sync_status" to "PENDING_SYNC",
            "date" to -1L, "start_time" to epoch, "end_time" to epoch + 60_000,
            "total_volume" to "24.6921", "total_sets" to 2, "total_reps" to 5,
            "created_at" to epoch, "updated_at" to epoch + 1, "deleted_at" to epoch + 2)
        for ((logId, exerciseId, order) in listOf(Triple(benchLog, "bench", 1), Triple(rowLog, "row", 0))) {
            val values = mutableListOf<Pair<String, Any?>>( "id" to logId, "workout_log_id" to workout,
                "exercise_id" to exerciseId, "order_index" to order, "total_volume" to if (order == 1) "24.6912" else "0.0009",
                "total_sets" to 1, "total_reps" to if (order == 1) 2 else 3,
                "created_at" to epoch, "updated_at" to epoch + 1,
                "pending_operation" to "CREATE", "sync_status" to "PENDING_SYNC")
            if (version != 6) values += "input_defaults" to if (order == 1) "{\"sets\":3,\"reps\":10}" else null
            insert("exercise_logs", *values.toTypedArray())
        }
        for (set in listOf(
            listOf(benchSet, benchLog, "bench", "2", "12.3456", "2", "1"),
            listOf(unfinishedSet, benchLog, "bench", "5", "99.1234", "4", "0"),
            listOf(rowSet, rowLog, "row", "1", "0.0003", "3", "1"),
        )) {
            insert("workout_sets", "id" to set[0], "exercise_log_id" to set[1], "workout_log_id" to workout,
                "exercise_id" to set[2], "set_number" to set[3], "weight" to set[4], "reps" to set[5],
                "is_completed" to set[6], "created_at" to epoch, "updated_at" to epoch + 1)
        }
        // Never selected or exposed to owner A, even though its invalid metadata cannot be imported.
        log("private-B-workout", "private-B-owner")
    }
    private fun LegacySqlInventoryTest.Fixture.read(): LegacyAggregate {
        val inventory = scan(owner)
        val item = inventory.items.single { it.kind == LegacyKind.WORKOUT && it.id == workout }
        return aggregate(owner, item, inventory.evidence())
    }
    private fun expect(reason: LegacyImportFailure, run: () -> Unit) {
        try { run(); fail("Expected $reason") } catch (failure: LegacyImportException) { assertEquals(reason, failure.reason) }
    }

    @Test fun `6 7 9 preserve complete aggregate UUIDs exact decimals dates flags and unknown provenance`() {
        for (version in listOf(6, 7, 9)) fixture(version) {
            val aggregate = read()
            val mapped = LegacyImportMapper.map(aggregate)
            assertEquals(version, mapped.source.getValue("schema_version").jsonPrimitive.int)
            assertEquals(aggregate.aggregateFingerprint, mapped.source.getValue("fingerprint").jsonPrimitive.content)
            assertNotEquals(aggregate.item.fingerprint, aggregate.aggregateFingerprint)
            assertEquals("24.6921", mapped.workout.getValue("total_volume").jsonPrimitive.content)
            assertEquals("1969-12-31", mapped.workout.getValue("date").jsonPrimitive.content)
            assertEquals("2025-01-01T00:00:00.123Z", mapped.workout.getValue("start_time").jsonPrimitive.content)
            assertEquals("2025-01-01T00:00:00.124Z", mapped.workout.getValue("updated_at").jsonPrimitive.content)
            assertEquals("2025-01-01T00:00:00.125Z", mapped.workout.getValue("deleted_at").jsonPrimitive.content)
            assertEquals(2, mapped.exerciseCount); assertEquals(3, mapped.setCount)
            assertEquals(listOf(rowLog, benchLog), mapped.exercises.map { it.jsonObject.getValue("id").jsonPrimitive.content })
            assertEquals(listOf(benchSet, unfinishedSet, rowSet), mapped.sets.map { it.jsonObject.getValue("id").jsonPrimitive.content })
            val unfinished = mapped.sets.single { it.jsonObject.getValue("id").jsonPrimitive.content == unfinishedSet }.jsonObject
            assertEquals("99.1234", unfinished.getValue("weight").jsonPrimitive.content)
            assertFalse(unfinished.getValue("is_completed").jsonPrimitive.boolean)
            assertEquals(setOf(workout, benchLog, rowLog, benchSet, unfinishedSet, rowSet), mapped.idMapping.keys)
            assertTrue(mapped.idMapping.all { it.key == it.value })
            assertEquals(JsonNull, mapped.exercises.first().jsonObject.getValue("input_defaults"))
            assertEquals(if (version == 6) JsonNull else Json.parseToJsonElement("{\"sets\":3,\"reps\":10}"),
                mapped.exercises.last().jsonObject.getValue("input_defaults"))
            assertEquals(setOf("id", "user_id", "template_id", "template_name", "date", "start_time", "end_time",
                "total_volume", "total_sets", "total_reps", "deleted_at", "created_at", "updated_at"), mapped.workout.keys)
            assertEquals(setOf("id", "workout_log_id", "exercise_id", "exercise_name", "muscle_category", "order_index",
                "total_volume", "total_sets", "total_reps", "input_defaults", "created_at", "updated_at"), mapped.exercises.first().jsonObject.keys)
            assertEquals(setOf("id", "exercise_log_id", "workout_log_id", "exercise_id", "set_number", "weight", "reps",
                "is_completed", "created_at", "updated_at"), unfinished.keys)
            assertTrue(aggregate.records.values.flatten().all { it.containsKey("sync_status") })
            assertFalse(aggregate.toString().contains("private-B"))
        }
    }

    @Test fun `UTC source date does not follow device timezone and missing epoch never defaults to now`() = fixture {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            assertEquals("1969-12-31", LegacyImportMapper.map(read()).workout.getValue("date").jsonPrimitive.content)
        } finally { TimeZone.setDefault(previous) }
        sql("UPDATE workout_logs SET start_time='not an epoch' WHERE id='$workout'")
        expect(LegacyImportFailure.INVALID_SOURCE) { LegacyImportMapper.map(read()) }
    }

    @Test fun `invalid UUIDs are blocked without replacement IDs`() = fixture {
        sql("UPDATE workout_sets SET id='not-a-uuid' WHERE id='$benchSet'")
        expect(LegacyImportFailure.INVALID_SOURCE) { LegacyImportMapper.map(read()) }
    }

    @Test fun `parent and per-exercise totals mismatch require review not rounding`() = fixture {
        sql("UPDATE workout_logs SET total_volume=24.69 WHERE id='$workout'")
        expect(LegacyImportFailure.REVIEW_REQUIRED) { LegacyImportMapper.map(read()) }
        sql("UPDATE workout_logs SET total_volume=24.6921 WHERE id='$workout'")
        sql("UPDATE exercise_logs SET total_reps=99 WHERE id='$benchLog'")
        expect(LegacyImportFailure.REVIEW_REQUIRED) { LegacyImportMapper.map(read()) }
    }

    @Test fun `UPDATE DELETE and unknown pending operations cannot masquerade as CREATE`() = fixture {
        for (operation in listOf("UPDATE", "DELETE", "FUTURE")) {
            sql("UPDATE workout_logs SET pending_operation='$operation' WHERE id='$workout'")
            expect(LegacyImportFailure.REVIEW_REQUIRED) { LegacyImportMapper.map(read()) }
        }
        sql("UPDATE workout_logs SET pending_operation='CREATE' WHERE id='$workout'")
        sql("UPDATE workout_sets SET pending_operation='UPDATE' WHERE id='$unfinishedSet'")
        expect(LegacyImportFailure.REVIEW_REQUIRED) { LegacyImportMapper.map(read()) }
    }

    @Test fun `missing foreign template and catalog dependencies require review`() = fixture {
        sql("UPDATE workout_templates SET user_id='other-owner' WHERE id='$template'")
        expect(LegacyImportFailure.REVIEW_REQUIRED) { LegacyImportMapper.map(read()) }
        sql("UPDATE workout_templates SET user_id='$owner' WHERE id='$template'")
        sql("DELETE FROM exercises WHERE id='bench'")
        expect(LegacyImportFailure.REVIEW_REQUIRED) { LegacyImportMapper.map(read()) }
    }

    @Test fun `unrepresentable defaults and out of bounds set values block without inference`() = fixture {
        sql("UPDATE exercise_logs SET input_defaults='{\"sets\":0,\"reps\":10}' WHERE id='$benchLog'")
        expect(LegacyImportFailure.INVALID_SOURCE) { LegacyImportMapper.map(read()) }
        sql("UPDATE exercise_logs SET input_defaults=NULL WHERE id='$benchLog'")
        sql("UPDATE workout_sets SET weight=1000.0001 WHERE id='$unfinishedSet'")
        expect(LegacyImportFailure.INVALID_SOURCE) { LegacyImportMapper.map(read()) }
    }

    @Test fun `duplicate child order set identity and wrong parentage are rejected`() = fixture {
        sql("UPDATE exercise_logs SET order_index=0 WHERE id='$benchLog'")
        expect(LegacyImportFailure.INVALID_SOURCE) { LegacyImportMapper.map(read()) }
        sql("UPDATE exercise_logs SET order_index=1 WHERE id='$benchLog'")
        sql("UPDATE workout_sets SET set_number=2 WHERE id='$unfinishedSet'")
        expect(LegacyImportFailure.INVALID_SOURCE) { LegacyImportMapper.map(read()) }
        sql("UPDATE workout_sets SET exercise_id='row' WHERE id='$unfinishedSet'")
        expect(LegacyReadFailure.INTEGRITY) { scan(owner) }
    }

    @Test fun `aggregate and dependency metadata helpers match one snapshot and detect all raw changes`() = fixture {
        sql("PRAGMA query_only=ON")
        val a = read()
        assertEquals(a.inventory.aggregateFingerprint(a.item), a.aggregateFingerprint)
        assertEquals(a.inventory.dependencyFingerprint(), a.dependencyFingerprint)
        assertEquals(6, a.records.values.sumOf { it.size })
        assertEquals("1", a.dependencies.getValue("TEMPLATE_EXERCISE").single().getValue("sets"))
        assertTrue(a.inventory.dependencies.filter { it.available }.all { it.fingerprint?.length == 64 })
        sql("PRAGMA query_only=OFF")
        sql("UPDATE workout_sets SET updated_at=updated_at+1 WHERE id='$unfinishedSet'")
        val b = read()
        assertNotEquals(a.aggregateFingerprint, b.aggregateFingerprint)
        assertEquals(a.dependencyFingerprint, b.dependencyFingerprint)
        sql("UPDATE exercises SET description='dependency changed' WHERE id='bench'")
        val c = read()
        assertEquals(b.aggregateFingerprint, c.aggregateFingerprint)
        assertNotEquals(b.dependencyFingerprint, c.dependencyFingerprint)
        sql("UPDATE template_exercises SET rest_seconds=rest_seconds+1 WHERE template_id='$template'")
        assertNotEquals(c.dependencyFingerprint, read().dependencyFingerprint)
        try { aggregate(owner, a.item, a.inventory.evidence()); fail("Stale evidence must block") }
        catch (failure: LegacyAggregateReadException) { assertEquals(LegacyReadFailure.CHANGED_DURING_READ, failure.failure) }
    }

    @Test fun `selection includes descendants only even with another owned aggregate`() = fixture {
        log("other-owned", owner)
        val selected = read()
        assertTrue(selected.inventory.items.any { it.id == "other-owned" })
        assertFalse(selected.records.values.flatten().any { it["id"]?.contains("other-owned") == true })
        assertEquals(setOf(benchLog, rowLog), selected.records.getValue(LegacyKind.EXERCISE_LOG).map { it.getValue("id") }.toSet())
        val exerciseItem = selected.inventory.items.single { it.id == benchLog }
        val exerciseAggregate = aggregate(owner, exerciseItem, selected.inventory.evidence())
        assertEquals(setOf(benchSet, unfinishedSet), exerciseAggregate.records.getValue(LegacyKind.SET).map { it.getValue("id") }.toSet())
        assertFalse(exerciseAggregate.records.containsKey(LegacyKind.WORKOUT))
        assertEquals(2, LegacyImportMapper.map(selected).exerciseCount)
    }

    @Test fun `intent and active JSON are retained raw and can never map as completed workouts`() = fixture {
        val payload = """{"p_owner_id":"$owner","p_template_id":"$template","p_request_id":"${id(9)}","p_name":"Original intent","p_exercises":[{"exercise_id":"intent-only"}]}"""
        val draft = """{"id":"${id(10)}","templateId":"$template","startTime":1,"exerciseLogs":[]}"""
        insert("exercises", "id" to "intent-only", "description" to "Original intent dependency")
        insert("template_write_intents", "key" to "original-intent", "user_id" to owner, "template_id" to template, "payload" to payload)
        insert("active_sessions", "id" to id(10), "user_id" to owner, "template_id" to template, "session_data_json" to draft)
        val inventory = scan(owner)
        for ((kind, field, raw) in listOf(
            Triple(LegacyKind.TEMPLATE_INTENT, "payload", payload),
            Triple(LegacyKind.ACTIVE_SESSION, "session_data_json", draft),
        )) {
            val item = inventory.items.single { it.kind == kind }
            val selected = aggregate(owner, item, inventory.evidence())
            assertEquals(raw, selected.records.getValue(kind).single().getValue(field))
            assertEquals(1, selected.records.values.sumOf { it.size })
            assertEquals("Original intent dependency", selected.dependencies.getValue("EXERCISE").single { it["id"] == "intent-only" }.getValue("description"))
            expect(LegacyImportFailure.INVALID_SOURCE) { LegacyImportMapper.map(selected) }
        }
    }

    @Test fun `unknown schema never produces a partial selected aggregate`() = fixture {
        val inventory = scan(owner)
        val item = inventory.items.single { it.kind == LegacyKind.WORKOUT }
        for (version in listOf(4, 8)) {
            sql("PRAGMA user_version=$version")
            expect(LegacyReadFailure.UNSUPPORTED_SCHEMA) { aggregate(owner, item, inventory.evidence()) }
        }
    }
}
