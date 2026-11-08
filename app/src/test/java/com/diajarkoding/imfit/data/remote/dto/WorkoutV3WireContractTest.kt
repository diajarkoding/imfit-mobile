package com.diajarkoding.imfit.data.remote.dto

import com.diajarkoding.imfit.domain.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class WorkoutV3WireContractTest {
    private val json = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = true }

    @Test fun v3SetSurvivesRealJsonDecodeDomainEncodeWithoutFloatOrIntegerLoss() {
        val payload = """{
            "id":"00000000-0000-4000-8000-000000000001","set_number":1,"measurement_version":3,
            "target_row_id":"row-1","weight":null,"reps":null,"duration_seconds":1800,
            "distance_meters":5000.25,"calories_kcal":230.45,"speed_kph":10.01,"lap_reps":0,
            "incline_percent":-2.75,"is_completed":true
        }"""
        val original = json.decodeFromString<WorkoutSetDto>(payload)
        val domain = original.toDomain("workout:exercise")
        val replay = json.decodeFromString<WorkoutSetDto>(json.encodeToString(domain.toDto()))
        assertEquals(original, replay)
        assertEquals("00000000-0000-4000-8000-000000000001", domain.id)
        assertNull(domain.weightKg)
        assertNull(domain.reps)
        assertEquals(5000.25, domain.distanceMeters!!, 0.0)
        assertEquals(230.45, domain.caloriesKcal!!, 0.0)
        assertEquals(-2.75, domain.inclinePercent!!, 0.0)
    }

    @Test fun v3StrengthWeightIsNotNarrowedToFloatAndZeroIsNotNull() {
        val original = json.decodeFromString<WorkoutSetDto>("""{
            "id":"00000000-0000-4000-8000-000000000002","set_number":1,"measurement_version":3,
            "target_row_id":"row-1","weight":12.34,"reps":8,"is_completed":true
        }""")
        assertEquals(12.34, original.toDomain().weightKg!!, 0.0)
        assertEquals(0.0, original.copy(weight = 0.0).toDomain().weightKg!!, 0.0)
        assertEquals(original, json.decodeFromString<WorkoutSetDto>(json.encodeToString(original.toDomain().toDto())))
    }

    @Test fun historyUsesFrozenNativeSnapshotNotMutableEmbeddedCatalog() {
        val snapshot = defaults().toSnapshotJson()
        val dto = ExerciseLogDto(id = "exercise-log", workoutLogId = "workout", exerciseId = "exercise",
            exerciseName = "Machine", muscleCategory = "CARDIO", trackingType = "DURATION", modality = "CARDIO",
            inputDefaults = snapshot, measurementVersion = 3, totalDistanceMeters = 5000.25,
            recordedLoadVolume = 0.0, workoutSets = listOf(WorkoutSetDto(id = "00000000-0000-4000-8000-000000000003", setNumber = 1,
                measurementVersion = 3, targetRowId = "row-1", durationSeconds = 1800, distanceMeters = 5000.25,
                caloriesKcal = 123.45, isCompleted = true)), exercises = catalogDto().copy(
                modality = "STRENGTH", defaultTrackingType = "WEIGHT_REPS", trackingProfile = null, supportedMetrics = emptyList()))
        val decoded = json.decodeFromString<ExerciseLogDto>(json.encodeToString(dto))
        val domain = decoded.toDomain()
        assertTrue(decoded.inputDefaults is JsonObject)
        assertEquals(defaults(), domain.inputDefaults)
        assertEquals(TrackingProfile.EQUIPMENT_METRICS, domain.exercise.trackingProfile)
        assertEquals(CardioMetric.entries, domain.exercise.supportedMetrics)
        assertEquals(TrackingType.DURATION, domain.trackingType)
        assertEquals(5000.25, domain.totalDistanceMeters!!, 0.0)
        assertEquals(123.45, domain.sets.single().caloriesKcal!!, 0.0)
    }

    @Test fun routineTargetsKeepOrderDifferentValuesAndRestZero() {
        val targets = listOf(SetTarget("row-1", 1, durationSeconds = 60, restSeconds = 0),
            SetTarget("row-2", 2, durationSeconds = 90, restSeconds = 75))
        val dto = TemplateExerciseDto(exerciseId = "exercise", sets = 2, reps = 0, restSeconds = 60,
            trackingType = "DURATION", measurementVersion = 3, setTargets = targets, exercises = catalogDto())
        val decoded = json.decodeFromString<TemplateExerciseDto>(json.encodeToString(dto))
        val domain = requireNotNull(decoded.toDomain())
        assertEquals(targets, domain.setTargets)
        assertEquals(2, domain.setCount)
        assertEquals(0, domain.setTargets!!.first().restSeconds)
        assertEquals(targets, domain.toDto("template", 0).setTargets)
        assertEquals(3, domain.measurementVersion)
    }

    @Test fun catalogProfileAndCapabilitiesReachBothDomainAndRoomCache() {
        val dto = json.decodeFromString<ExerciseDto>(json.encodeToString(catalogDto()))
        val domain = dto.toDomain()
        val entity = dto.toEntity()
        assertEquals(TrackingProfile.EQUIPMENT_METRICS, domain.trackingProfile)
        assertEquals(CardioMetric.entries, domain.supportedMetrics)
        assertEquals("equipment_metrics", entity.trackingProfile)
        assertEquals(CardioMetric.entries.map { it.wireValue }, entity.supportedMetrics)
    }

    @Test fun missingV3IdAndUnknownVersionAreRejectedWhileLegacyIdsAreStableAndScoped() {
        try { WorkoutSetDto(setNumber = 1, measurementVersion = 3, targetRowId = "row-1").toDomain(); fail() }
        catch (_: IllegalArgumentException) { }
        try { WorkoutSetDto(setNumber = 1, measurementVersion = 4).toDomain(); fail() }
        catch (_: IllegalArgumentException) { }
        val old = WorkoutSetDto(setNumber = 1, weight = 10.0, reps = 8)
        assertEquals(old.toDomain("workout-a:exercise").id, old.toDomain("workout-a:exercise").id)
        assertNotEquals(old.toDomain("workout-a:exercise").id, old.toDomain("workout-b:exercise").id)
        val legacy = ExerciseLogDto(exerciseId = "plank", exerciseName = "Plank", muscleCategory = "CORE",
            workoutSets = listOf(old)).toDomain()
        assertEquals(TrackingType.WEIGHT_REPS, legacy.trackingType)
        assertNull(legacy.measurementVersion)
        assertNull(legacy.inputDefaults)
    }

    @Test fun v3NullWorkoutDistanceAndDecimalRecordedLoadArePreserved() {
        val dto = json.decodeFromString<WorkoutLogDto>("""{
            "id":"workout","user_id":"owner","template_name":"Routine","date":"2026-11-06",
            "start_time":"2026-11-06T10:00:00Z","end_time":"2026-11-06T10:30:00Z",
            "measurement_version":3,"recorded_load_volume":148.08,"total_distance_meters":null
        }""")
        assertNull(dto.toDomain().totalDistanceMeters)
        assertEquals(148.08, dto.toDomain().recordedLoadVolume!!, 0.0)
        assertEquals(3, dto.toDomain().measurementVersion)
    }

    private fun defaults() = ExerciseInputDefaults(1, 0, "DURATION", schemaVersion = 3,
        setTargets = listOf(SetTarget("row-1", 1, durationSeconds = 1800, restSeconds = 0)),
        extraSetTargets = listOf(SetTarget("extra-1", 1, durationSeconds = 60, restSeconds = 60)),
        modality = ExerciseModality.CARDIO, trackingProfile = TrackingProfile.EQUIPMENT_METRICS,
        supportedMetrics = CardioMetric.entries)

    private fun catalogDto() = ExerciseDto("exercise", 7, "Machine", defaultTrackingType = "DURATION",
        modality = "CARDIO", allowedModes = listOf("DURATION"), trackingProfile = "equipment_metrics",
        supportedMetrics = CardioMetric.entries.map { it.wireValue })
}
