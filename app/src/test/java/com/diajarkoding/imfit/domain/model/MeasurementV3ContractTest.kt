package com.diajarkoding.imfit.domain.model

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MeasurementV3ContractTest {
    private fun strength() = ExerciseInputDefaults(2, 0, "WEIGHT_REPS", schemaVersion = 3,
        setTargets = listOf(SetTarget("first", 1, 0.0, 12, restSeconds = 0), SetTarget("second", 2, 37.5, 8, restSeconds = 90)),
        modality = ExerciseModality.STRENGTH)

    @Test fun nativeSnapshotPreservesOrderedTargetsAndIndependentZeroValues() {
        val source = strength()
        val wire = source.toSnapshotJson().jsonObject
        assertEquals(JsonNull, wire["tracking_profile"])
        assertFalse(wire.containsKey("sets"))
        assertFalse(wire.containsKey("reps"))
        val restored = ExerciseInputDefaults.fromStoredString(source.toStoredString())
        assertEquals(source, restored)
        assertEquals(listOf("first", "second"), restored.setTargets!!.map { it.rowId })
        assertEquals(listOf(0, 90), restored.setTargets.map { it.restSeconds })
        assertNotEquals(source.key, source.copy(setTargets = source.setTargets!!.reversed().mapIndexed { i, t -> t.copy(setNumber = i + 1) }).key)
        val extra = source.copy(extraSetTargets = listOf(SetTarget("third", 1, 5.25, 6, restSeconds = 60)))
        assertEquals(extra, ExerciseInputDefaults.fromSnapshotJson(extra.toSnapshotJson()))
        assertEquals(source.key, extra.key)
    }

    @Test fun incompleteFutureAndCrossListDuplicateSnapshotsAreRejected() {
        val valid = strength().toSnapshotJson().jsonObject
        rejects { ExerciseInputDefaults.fromSnapshotJson(JsonObject(valid - "extra_set_targets")) }
        rejects { ExerciseInputDefaults.fromSnapshotJson(JsonObject(valid + ("schema_version" to JsonPrimitive(4)))) }
        rejects { ExerciseInputDefaults.fromSnapshotJson(JsonObject(valid + ("schema_version" to JsonPrimitive("3")))) }
        rejects { strength().copy(extraSetTargets = listOf(SetTarget("first", 1, 10.0, 8))).toSnapshotJson() }
        val rows = valid.getValue("set_targets").jsonArray
        val wrongWeight = JsonObject(rows[0].jsonObject + ("weight" to JsonPrimitive("0")))
        rejects { ExerciseInputDefaults.fromSnapshotJson(JsonObject(valid + ("set_targets" to JsonArray(listOf(wrongWeight, rows[1]))))) }
        rejects { ExerciseInputDefaults.fromStoredString("{corrupt") }
    }

    @Test fun legacyExpansionIsStableScopedAndNeverInventsUnknownWeightOrChangesRestZero() {
        val rows = LegacyTargetAdapter.expand("routine-a:pull-up", 4, 11, null, null, 0, TrackingType.BODYWEIGHT_REPS)
        assertEquals(rows, LegacyTargetAdapter.expand("routine-a:pull-up", 4, 11, null, null, 0, TrackingType.BODYWEIGHT_REPS))
        assertEquals(listOf(1, 2, 3, 4), rows.map { it.setNumber })
        assertTrue(rows.all { it.weightKg == null && it.reps == 11 && it.restSeconds == 0 })
        assertNotEquals(rows.map { it.rowId }, LegacyTargetAdapter.expand("routine-b:pull-up", 4, 11, null, null, 0, TrackingType.BODYWEIGHT_REPS).map { it.rowId })
        val scalar = ExerciseInputDefaults.fromStoredString("4:11")
        assertNull(scalar.schemaVersion)
        assertNull(scalar.setTargets)
        assertEquals("4:11", scalar.toStoredString())
        val camel = ExerciseInputDefaults.fromSnapshotJson(Json.parseToJsonElement("""{"sets":3,"reps":0,"trackingType":"DISTANCE_DURATION","targetDurationSeconds":90,"targetDistanceMeters":500}"""))
        val snake = ExerciseInputDefaults.fromSnapshotJson(Json.parseToJsonElement("""{"sets":3,"reps":0,"tracking_type":"DISTANCE_DURATION","target_duration_seconds":90,"target_distance_meters":500}"""))
        assertEquals(camel, snake)
        assertEquals("3:0:DISTANCE_DURATION:90:500", camel.key)
    }

    @Test fun v3CompletionUsesFrozenCapabilitiesAndPreservesNullVersusZero() {
        val zero = SetMeasurement(weightKg = 0.0, reps = 1, measurementVersion = 3)
        assertTrue(zero.isCompleteEligible(TrackingType.WEIGHT_REPS, ExerciseModality.STRENGTH, null, emptyList()))
        assertFalse(zero.copy(weightKg = null).isCompleteEligible(TrackingType.WEIGHT_REPS, ExerciseModality.STRENGTH, null, emptyList()))
        val duration = SetMeasurement(durationSeconds = 30, measurementVersion = 3)
        fun cardio(value: SetMeasurement) = value.isCompleteEligible(TrackingType.DURATION, ExerciseModality.CARDIO,
            TrackingProfile.EQUIPMENT_METRICS, listOf(CardioMetric.CALORIES, CardioMetric.DISTANCE, CardioMetric.INCLINE))
        assertTrue(cardio(duration))
        assertTrue(cardio(duration.copy(caloriesKcal = 0.0, distanceMeters = 9_999_999_999.99, inclinePercent = -2.5)))
        assertFalse(cardio(duration.copy(speedKph = 10.0)))
        assertFalse(cardio(duration.copy(distanceMeters = Double.NaN)))
        assertFalse(cardio(duration.copy(caloriesKcal = 0.001)))
        assertFalse(cardio(duration.copy(inclinePercent = -1000.0)))
        assertFalse(cardio(duration.copy(weightKg = 0.0)))
        assertFalse(cardio(duration.copy(additionalWeight = 1f)))
    }

    @Test fun optionalDistanceAggregatesAndRecordedLoadStayVersioned() {
        val cardio = Exercise("cardio", "Bike", MuscleCategory.CARDIO, "", defaultTrackingType = TrackingType.DURATION,
            modality = ExerciseModality.CARDIO, trackingProfile = TrackingProfile.EQUIPMENT_METRICS, supportedMetrics = listOf(CardioMetric.DISTANCE))
        val empty = ExerciseLog(cardio, listOf(WorkoutSet(1, null, null, true, durationSeconds = 60, measurementVersion = 3)), measurementVersion = 3)
        assertNull(empty.totalDistanceMeters)
        val zero = empty.copy(sets = listOf(empty.sets[0].copy(distanceMeters = 0.0)))
        assertEquals(0.0, zero.totalDistanceMeters!!, 0.0)
        val strength = ExerciseLog(Exercise("strength", "Bench", MuscleCategory.CHEST, ""),
            listOf(WorkoutSet(1, 37.5, 8, true, measurementVersion = 3), WorkoutSet(2, 999.0, 99, false, measurementVersion = 3)), measurementVersion = 3)
        assertEquals(300.0, strength.recordedLoadVolume, 0.0)
        assertEquals(0f, strength.totalVolume)
        assertEquals(300f, strength.copy(measurementVersion = null).totalVolume)
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Invalid contract was accepted") } catch (_: IllegalArgumentException) { }
    }
}
