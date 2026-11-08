package com.diajarkoding.imfit.data.sync

import com.diajarkoding.imfit.data.local.entity.ExerciseLogEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutSetEntity
import com.diajarkoding.imfit.domain.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkoutAggregatePayloadCodecTest {
    private fun workout() = WorkoutLogEntity("workout", "owner", null, "Routine", 1000, 1000, 2000, 0f,
        totalSets = 1, totalReps = 8, totalDistanceMeters = 1234.0)
    private fun exercise() = ExerciseLogEntity("exercise-log", "workout", "exercise", "Bench", "CHEST", 0, 0f, 1, 8,
        inputDefaults = "3:8", totalDistanceMeters = 1234.0)
    private fun set() = WorkoutSetEntity("set", "exercise-log", "workout", "exercise", 1, 12.34f.toDouble(), 8, true,
        distanceMeters = 1234.0)

    @Test fun pendingLegacyAggregateRetainsEveryOldKeyAndNumericToken() {
        val encoded = WorkoutAggregatePayloadCodec.encode(workout(), listOf(exercise()), listOf(set()))
        assertEquals(Json.parseToJsonElement("""{
            "id":"workout","user_id":"owner","template_name":"Routine","date":"1970-01-01",
            "start_time":"1970-01-01T00:00:01Z","end_time":"1970-01-01T00:00:02Z",
            "total_volume":0.0,"total_sets":1,"total_reps":8,"total_duration_seconds":0,
            "total_distance_meters":1234,"additional_volume":0.0
        }"""), encoded.workout)
        assertEquals(Json.parseToJsonElement("""[{
            "id":"exercise-log","workout_log_id":"workout","exercise_id":"exercise",
            "exercise_name":"Bench","muscle_category":"CHEST","order_index":0,
            "total_volume":0.0,"total_sets":1,"total_reps":8,"input_defaults":"3:8",
            "total_duration_seconds":0,"total_distance_meters":1234,"additional_volume":0.0
        }]"""), encoded.exercises)
        assertEquals(Json.parseToJsonElement("""[{
            "id":"set","exercise_log_id":"exercise-log","workout_log_id":"workout","exercise_id":"exercise",
            "set_number":1,"weight":${12.34f.toDouble()},"reps":8,"is_completed":true,"distance_meters":1234
        }]"""), encoded.sets)
        assertEquals("1234", encoded.sets.single().jsonObject.getValue("distance_meters").toString())
    }

    @Test fun typedLegacyFieldsKeepOldLoadsAndPrimitiveDefaultsWithoutVersionKeys() {
        val old = set().copy(additionalWeight = 1.23f, assistanceWeight = 2.34f, durationSeconds = 60)
        val encoded = WorkoutAggregatePayloadCodec.encode(workout(), listOf(exercise().copy(
            trackingType = "BODYWEIGHT_REPS", modality = "CALISTHENICS", metricQuality = "VERIFIED",
            inputDefaults = "3:8:BODYWEIGHT_REPS:0:0"
        )), listOf(old))
        val child = encoded.exercises.single().jsonObject
        assertEquals(JsonPrimitive("BODYWEIGHT_REPS"), child["tracking_type"])
        assertEquals(JsonPrimitive("3:8:BODYWEIGHT_REPS:0:0"), child["input_defaults"])
        assertFalse(child.containsKey("measurement_version"))
        assertFalse(child.containsKey("recorded_load_volume"))
        val result = encoded.sets.single().jsonObject
        assertEquals(JsonPrimitive(1.23f.toDouble()), result["additional_weight"])
        assertEquals(JsonPrimitive(2.34f.toDouble()), result["assistance_weight"])
        assertFalse(result.containsKey("target_row_id"))
        assertFalse(result.containsKey("calories_kcal"))
    }

    @Test fun v3AggregateUsesNativeSnapshotAndNullableDecimalMeasurements() {
        val snapshot = snapshot()
        val w = workout().copy(measurementVersion = 3, totalReps = 0, totalDurationSeconds = 600,
            totalDistanceMeters = 1234.56, recordedLoadVolume = 0.0)
        val e = exercise().copy(measurementVersion = 3, totalReps = 0, totalDurationSeconds = 600,
            totalDistanceMeters = 1234.56, recordedLoadVolume = 0.0, trackingType = "DURATION",
            modality = "CARDIO", inputDefaults = snapshot.toStoredString())
        val s = set().copy(measurementVersion = 3, targetRowId = "row-1", weight = null, reps = null,
            durationSeconds = 600, distanceMeters = 1234.56, caloriesKcal = 89.12, speedKph = 7.89,
            lapReps = 0, inclinePercent = -2.5)
        val payload = WorkoutAggregatePayloadCodec.encode(w, listOf(e), listOf(s))
        val defaults = payload.exercises.single().jsonObject.getValue("input_defaults")
        assertTrue(defaults is JsonObject)
        assertEquals(snapshot.toSnapshotJson(), defaults)
        assertEquals(JsonPrimitive(1234.56), payload.workout["total_distance_meters"])
        val result = payload.sets.single().jsonObject
        assertEquals(JsonNull, result["weight"])
        assertEquals(JsonNull, result["reps"])
        assertEquals(JsonPrimitive(1234.56), result["distance_meters"])
        assertEquals(JsonPrimitive(89.12), result["calories_kcal"])
        assertEquals(JsonPrimitive(7.89), result["speed_kph"])
        assertEquals(JsonPrimitive(0), result["lap_reps"])
        assertEquals(JsonPrimitive(-2.5), result["incline_percent"])
        assertEquals(JsonPrimitive("row-1"), result["target_row_id"])
    }

    @Test fun v3UnrecordedDistanceIsNotReplacedByZero() {
        val e = exercise().copy(measurementVersion = 3, trackingType = "DURATION", modality = "CARDIO",
            totalReps = 0, totalDurationSeconds = 600, inputDefaults = snapshot().toStoredString(),
            totalDistanceMeters = null, recordedLoadVolume = 0.0)
        val s = set().copy(measurementVersion = 3, targetRowId = "row-1", weight = null, reps = null,
            durationSeconds = 600, distanceMeters = null)
        val payload = WorkoutAggregatePayloadCodec.encode(workout().copy(measurementVersion = 3,
            totalReps = 0, totalDurationSeconds = 600, totalDistanceMeters = null, recordedLoadVolume = 0.0), listOf(e), listOf(s))
        assertEquals(JsonNull, payload.workout["total_distance_meters"])
        assertEquals(JsonNull, payload.exercises.single().jsonObject["total_distance_meters"])
        assertEquals(JsonNull, payload.sets.single().jsonObject["distance_meters"])
    }

    @Test fun mixedVersionAggregateCannotFallThroughLegacyWriter() {
        try {
            WorkoutAggregatePayloadCodec.encode(workout(), listOf(exercise()), listOf(set().copy(measurementVersion = 3)))
            fail("Mixed versions were accepted")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun importerPerformanceEqualityIncludesEveryV3ResultAndSnapshotField() {
        val e = exercise().copy(measurementVersion = 3, inputDefaults = snapshot().toStoredString(),
            totalReps = 0, totalDurationSeconds = 600, recordedLoadVolume = 0.0, trackingType = "DURATION", modality = "CARDIO")
        val s = set().copy(measurementVersion = 3, targetRowId = "row-1", weight = null, reps = null,
            durationSeconds = 600, caloriesKcal = 12.34, speedKph = 5.67, lapReps = 2, inclinePercent = 1.25)
        assertTrue(sameWorkoutExercisePerformance(e, e.copy(updatedAt = 999), listOf(s), listOf(s.copy(updatedAt = 999))))
        val changed = listOf(s.copy(id = "other"), s.copy(targetRowId = "row-2"), s.copy(weight = 12.35),
            s.copy(reps = 0), s.copy(durationSeconds = 1), s.copy(distanceMeters = 1234.01),
            s.copy(caloriesKcal = 12.35), s.copy(speedKph = 5.68), s.copy(lapReps = 3),
            s.copy(inclinePercent = null), s.copy(measurementVersion = null), s.copy(isCompleted = false))
        changed.forEach { assertFalse(sameWorkoutExercisePerformance(e, e, listOf(s), listOf(it))) }
        val editedDefaults = snapshot().copy(setTargets = listOf(SetTarget("row-1", 1, durationSeconds = 601, restSeconds = 0)))
        assertFalse(sameWorkoutExercisePerformance(e, e.copy(inputDefaults = editedDefaults.toStoredString()), listOf(s), listOf(s)))
        assertFalse(sameWorkoutExercisePerformance(e, e.copy(recordedLoadVolume = 0.01), listOf(s), listOf(s)))
        assertFalse(sameWorkoutExercisePerformance(e, e.copy(totalDistanceMeters = null), listOf(s), listOf(s)))
    }

    private fun snapshot() = ExerciseInputDefaults(1, 0, "DURATION", schemaVersion = 3,
        setTargets = listOf(SetTarget("row-1", 1, durationSeconds = 600, restSeconds = 0)),
        modality = ExerciseModality.CARDIO, trackingProfile = TrackingProfile.EQUIPMENT_METRICS,
        supportedMetrics = CardioMetric.entries)

    private fun v3Fixture(): Triple<WorkoutLogEntity, ExerciseLogEntity, WorkoutSetEntity> {
        val w = workout().copy(measurementVersion = 3, totalReps = 0, totalDurationSeconds = 80,
            totalDistanceMeters = null, recordedLoadVolume = 0.0)
        val e = exercise().copy(measurementVersion = 3, totalReps = 0, totalDurationSeconds = 80,
            totalDistanceMeters = null, recordedLoadVolume = 0.0, trackingType = "DURATION",
            modality = "CARDIO", inputDefaults = snapshot().toStoredString())
        val s = set().copy(measurementVersion = 3, targetRowId = "row-1", weight = null, reps = null,
            durationSeconds = 80, distanceMeters = null)
        return Triple(w, e, s)
    }

    @Test fun v3RestOutcomeAndSessionDurationsAreFlatKeysSeparateFromTargetRest() {
        val (w, e, s) = v3Fixture()
        val payload = WorkoutAggregatePayloadCodec.encode(
            w.copy(pausedElapsedMs = 30_000, activeElapsedMs = 115_000), listOf(e),
            listOf(s.copy(restElapsedMs = 35_000, restEndReason = "SKIPPED")))
        assertEquals(JsonPrimitive(30_000L), payload.workout["paused_elapsed_ms"])
        assertEquals(JsonPrimitive(115_000L), payload.workout["active_elapsed_ms"])
        val result = payload.sets.single().jsonObject
        assertEquals(JsonPrimitive(35_000L), result["rest_elapsed_ms"])
        assertEquals(JsonPrimitive("SKIPPED"), result["rest_end_reason"])
        assertEquals(JsonPrimitive(80), result["duration_seconds"]) // actual set duration, not rest
        // The frozen target rest (60 in the snapshot fixture) is untouched.
        assertEquals(snapshot().toSnapshotJson(), payload.exercises.single().jsonObject.getValue("input_defaults"))
    }

    @Test fun v3PayloadWithoutOutcomeOmitsTheKeysSoOlderPayloadsAreUnchanged() {
        val (w, e, s) = v3Fixture()
        val payload = WorkoutAggregatePayloadCodec.encode(w, listOf(e), listOf(s))
        assertFalse(payload.workout.containsKey("paused_elapsed_ms"))
        assertFalse(payload.workout.containsKey("active_elapsed_ms"))
        assertFalse(payload.sets.single().jsonObject.containsKey("rest_elapsed_ms"))
        assertFalse(payload.sets.single().jsonObject.containsKey("rest_end_reason"))
    }

    @Test fun incompleteOrLegacyRestFieldsAreRefusedRatherThanSentOrDropped() {
        val (w, e, s) = v3Fixture()
        assertThrows(IllegalArgumentException::class.java) {
            WorkoutAggregatePayloadCodec.encode(w, listOf(e), listOf(s.copy(restElapsedMs = 1, restEndReason = null)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkoutAggregatePayloadCodec.encode(w.copy(activeElapsedMs = 5), listOf(e), listOf(s))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkoutAggregatePayloadCodec.encode(w, listOf(e), listOf(s.copy(restElapsedMs = -1, restEndReason = "EXPIRED")))
        }
        assertThrows(IllegalArgumentException::class.java) { // draft cannot carry an outcome
            WorkoutAggregatePayloadCodec.encode(w, listOf(e), listOf(s.copy(isCompleted = false, restElapsedMs = 1, restEndReason = "EXPIRED")))
        }
        assertThrows(IllegalArgumentException::class.java) { // legacy aggregate must never carry v3 fields
            WorkoutAggregatePayloadCodec.encode(workout().copy(pausedElapsedMs = 1, activeElapsedMs = 2), listOf(exercise()), listOf(set()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkoutAggregatePayloadCodec.encode(workout(), listOf(exercise()), listOf(set().copy(restElapsedMs = 1, restEndReason = "EXPIRED")))
        }
    }

    @Test fun importerEqualityNoticesAChangedRestOutcome() {
        val (_, e, s) = v3Fixture()
        val before = listOf(s.copy(restElapsedMs = 35_000, restEndReason = "SKIPPED"))
        assertTrue(sameWorkoutExercisePerformance(e, e, before, before))
        assertFalse(sameWorkoutExercisePerformance(e, e, before, listOf(s.copy(restElapsedMs = 90_000, restEndReason = "EXPIRED"))))
        assertFalse(sameWorkoutExercisePerformance(e, e, before, listOf(s)))
    }
}
