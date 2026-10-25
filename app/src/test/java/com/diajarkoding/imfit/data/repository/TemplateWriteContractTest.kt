package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.remote.*
import com.diajarkoding.imfit.domain.model.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class TemplateWriteContractTest {
    private val exercise = TemplateExercise(Exercise("squat", "Squat", MuscleCategory.LEGS, ""), 3, 10, 60)
    private val request = TemplateWriteRequest("request", "template", null, "Routine", templateWriteExercises(listOf(exercise)), emptyList(), ownerId = "owner")
    @Test fun nullableCreateRevisionAndRequestIdentitySurviveDurableRoundTrip() {
        val json = Json { encodeDefaults = true; explicitNulls = true }
        val encoded = json.encodeToString(request)
        assertEquals(JsonNull, json.parseToJsonElement(encoded).jsonObject["p_expected_revision"])
        assertEquals(JsonPrimitive("owner"), json.parseToJsonElement(encoded).jsonObject["p_owner_id"])
        assertEquals(request, json.decodeFromString<TemplateWriteRequest>(encoded))
    }
    @Test fun sameSaveCanReplayButChangedDraftCannotReuseAmbiguousRequest() {
        assertTrue(request.sameIntent("Routine", request.exercises, false))
        assertFalse(request.sameIntent("Changed", request.exercises, false))
        assertFalse(request.sameIntent("Routine", request.exercises.map { it.copy(reps = 20) }, false))
        assertFalse(request.sameIntent("Routine", request.exercises, true))
    }
    @Test(expected = IllegalArgumentException::class)
    fun duplicateExercisesAreRejectedBeforeAnyWrite() { templateWriteExercises(listOf(exercise, exercise)) }
    @Test(expected = IllegalArgumentException::class)
    fun invalidTargetsAreRejectedBeforeAnyWrite() { templateWriteExercises(listOf(exercise.copy(sets = 0))) }
    @Test fun removalIsExplicitAndSoftDeleteIsNotAnImplicitChildPurge() {
        val edit = request.copy(expectedRevision = 4, removedExerciseIds = listOf("bench"))
        assertEquals(listOf("bench"), edit.removedExerciseIds)
        assertEquals(edit.exercises, edit.copy(isDeleted = true).exercises)
    }
}
