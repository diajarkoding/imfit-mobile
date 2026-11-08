package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.domain.model.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RoutinePayloadContractTest {
    private val exercise = Exercise("exercise", "Machine", MuscleCategory.CARDIO, description = "",
        defaultTrackingType = TrackingType.DURATION, modality = ExerciseModality.CARDIO,
        allowedTrackingTypes = listOf(TrackingType.DURATION), trackingProfile = TrackingProfile.EQUIPMENT_METRICS,
        supportedMetrics = listOf(CardioMetric.DISTANCE, CardioMetric.SPEED))

    @Test fun currentScalarCommandStaysLegacyDespiteNewCatalogMetadata() {
        val target = TemplateExercise(exercise, sets = 1, reps = null, targetDurationSeconds = 1800)
        assertNull(explicitRoutineVersion(listOf(target)))
        assertEquals(Json.parseToJsonElement("""[{
            "exercise_id":"exercise","order_index":0,"sets":1,"reps":null,
            "tracking_type":"DURATION","target_duration_seconds":1800
        }]"""), routineExercisePayload(listOf(target)))
    }

    @Test fun restoredLegacyRestZeroKeepsItsOriginalWireField() {
        val target = TemplateExercise(exercise, sets = 1, reps = null, targetDurationSeconds = 1800,
            legacyRestSeconds = 0)
        val encoded = routineExercisePayload(listOf(target)).single().jsonObject
        assertEquals(JsonPrimitive(0), encoded["rest_seconds"])
        assertFalse(encoded.containsKey("measurement_version"))
        assertFalse(encoded.containsKey("set_targets"))
    }

    @Test fun explicitV3CommandSendsOnlyCanonicalTargetsNotScalarReadMetadata() {
        val rows = listOf(SetTarget("row-1", 1, durationSeconds = 60, restSeconds = 0),
            SetTarget("row-2", 2, durationSeconds = 90, restSeconds = 75))
        val target = TemplateExercise(exercise, sets = 17, reps = 99, restSeconds = 600,
            targetDurationSeconds = 999, targetDistanceMeters = 999, legacyRestSeconds = 99,
            setTargets = rows, measurementVersion = 3)
        assertEquals(3, explicitRoutineVersion(listOf(target)))
        val encoded = routineExercisePayload(listOf(target)).single().jsonObject
        assertEquals(setOf("exercise_id", "order_index", "tracking_type", "measurement_version", "set_targets"), encoded.keys)
        val targets = encoded.getValue("set_targets").jsonArray
        assertEquals(listOf("row-1", "row-2"), targets.map { it.jsonObject.getValue("row_id").jsonPrimitive.content })
        assertEquals(JsonPrimitive(0), targets.first().jsonObject["rest_seconds"])
        assertEquals(JsonPrimitive(90), targets.last().jsonObject["duration_seconds"])
        assertEquals(JsonNull, targets.first().jsonObject["weight"])
        assertEquals(JsonNull, targets.first().jsonObject["reps"])
    }

    @Test fun unmarkedTargetsOrMixedScalarTargetsCannotImplicitlyActivateV3() {
        val rows = listOf(SetTarget("row-1", 1, durationSeconds = 60))
        val legacy = TemplateExercise(exercise, targetDurationSeconds = 60)
        val v3 = legacy.copy(setTargets = rows, measurementVersion = 3)
        assertRejected { routineExercisePayload(listOf(legacy.copy(setTargets = rows))) }
        assertRejected { routineExercisePayload(listOf(legacy.copy(measurementVersion = 3))) }
        assertRejected { routineExercisePayload(listOf(v3, legacy)) }
        assertRejected { routineExercisePayload(listOf(legacy.copy(measurementVersion = 4))) }
    }

    private fun assertRejected(block: () -> Unit) {
        try { block(); fail("Invalid versioned command was accepted") }
        catch (_: IllegalArgumentException) { }
    }
}
