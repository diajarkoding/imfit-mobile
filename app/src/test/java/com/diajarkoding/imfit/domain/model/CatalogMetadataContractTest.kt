package com.diajarkoding.imfit.domain.model

import com.diajarkoding.imfit.data.remote.dto.ExerciseDto
import com.diajarkoding.imfit.data.remote.dto.toDomain
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CatalogMetadataContractTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun serverMetadataOverridesFamiliarCatalogIdentity() {
        val exercise = json.decodeFromString<ExerciseDto>("""{
            "id":"ex_chest_1","name":"Timed variation","muscle_category_id":1,
            "modality":"ISOMETRIC","default_tracking_type":"DURATION",
            "exercise_tracking_modes":[{"tracking_type":"DURATION"}]
        }""").toDomain()
        assertEquals(TrackingType.DURATION, exercise.defaultTrackingType)
        assertEquals(ExerciseModality.ISOMETRIC, exercise.modality)
        assertEquals(listOf(TrackingType.DURATION), exercise.allowedTrackingTypes)
    }

    @Test fun defaultOutsideAllowedModesIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ExerciseDto("new", 1, "Invalid", defaultTrackingType = "DURATION",
                modality = "ISOMETRIC", allowedModes = listOf("WEIGHT_REPS")).toDomain()
        }
    }

    @Test fun missingOrUnknownMetadataDoesNotBecomeStrength() {
        assertThrows(IllegalArgumentException::class.java) { ExerciseDto("new", 1, "Missing").toDomain() }
        assertThrows(IllegalArgumentException::class.java) {
            ExerciseDto("new", 1, "Unknown", defaultTrackingType = "FUTURE_MODE",
                modality = "STRENGTH", allowedModes = listOf("FUTURE_MODE")).toDomain()
        }
    }
}
