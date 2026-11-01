package com.diajarkoding.imfit.domain.model

import com.diajarkoding.imfit.data.remote.dto.ExerciseLogDto
import com.diajarkoding.imfit.data.remote.dto.toDomain
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class WorkoutHistoryTrackingContractTest {
    @Test fun legacyPlankHistoryIsNotReinterpretedUsingNewCatalogDuration() {
        val history = Json.decodeFromString<ExerciseLogDto>("""{
            "exercise_id":"ex_core_1","exercise_name":"Plank","muscle_category":"CORE",
            "workout_sets":[{"set_number":1,"weight":40.0,"reps":10,"is_completed":true}]
        }""").toDomain()
        assertEquals(TrackingType.WEIGHT_REPS, history.trackingType)
        assertEquals("LEGACY_UNVERIFIED", history.metricQuality)
        assertEquals(400f, history.totalVolume)
        assertEquals(10, history.totalReps)
        assertEquals(0, history.totalDurationSeconds)
    }

    @Test fun typedDurationHistoryKeepsItsSnapshotMeaningIndependentlyOfCatalog() {
        val history = Json.decodeFromString<ExerciseLogDto>("""{
            "exercise_id":"future-catalog-id","exercise_name":"Hold","muscle_category":"CORE",
            "tracking_type":"DURATION","modality":"ISOMETRIC","metric_quality":"VERIFIED",
            "workout_sets":[{"set_number":1,"duration_seconds":90,"is_completed":true}]
        }""").toDomain()
        assertEquals(90, history.totalDurationSeconds)
        assertEquals(0, history.totalReps)
        assertEquals(0f, history.totalVolume)
        assertEquals("VERIFIED", history.metricQuality)
    }
}
