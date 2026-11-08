package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.domain.model.CardioMetric
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.ExerciseInputDefaults
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.ExerciseModality
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.RestTimerTrigger
import com.diajarkoding.imfit.domain.model.SetTarget
import com.diajarkoding.imfit.domain.model.TrackingProfile
import com.diajarkoding.imfit.domain.model.TrackingType
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutSet
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WorkoutSessionCodecTest {
    @Test fun v1MissingIdsAreDeterministicScopedAndNeverPromoted() {
        val raw = legacySession()
        val first = WorkoutSessionCodec.decode(raw)
        val second = WorkoutSessionCodec.decode(raw)
        assertEquals(1, first.schemaVersion)
        assertNull(first.exerciseLogs.first().measurementVersion)
        assertEquals(TrackingType.WEIGHT_REPS, first.exerciseLogs.first().trackingType)
        assertEquals("LEGACY_UNVERIFIED", first.exerciseLogs.first().metricQuality)
        assertEquals(12.5, first.exerciseLogs.first().sets.first().weightKg)
        val identities = first.exerciseLogs.flatMap { it.sets }.map { it.id }
        assertEquals(identities, second.exerciseLogs.flatMap { it.sets }.map { it.id })
        assertEquals(identities.size, identities.toSet().size)
        identities.forEach { assertEquals(it, UUID.fromString(it).toString()) }
        assertEquals(first, WorkoutSessionCodec.decode(WorkoutSessionCodec.encode(first)))
        // Decoding is read-only: callers retain exactly the original raw row on failure/recovery.
        assertFalse(raw.contains("measurementVersion"))
        assertFalse(raw.contains("targetRowId"))
    }

    @Test fun v2KeepsTypedLegacyTargetsStopwatchAndLegacyMeasurementMeaning() {
        val raw = """{
          "id":"session-v2","templateId":"routine","templateName":"Legacy duration","startTime":123,
          "schemaVersion":2,"isPaused":true,"totalPausedTimeMs":200,"lastPauseTime":300,
          "exerciseLogs":[{
            "exercise":{"id":"hold","name":"Hold","muscleCategory":"CORE","description":"",
              "defaultTrackingType":"DURATION","modality":"ISOMETRIC","allowedTrackingTypes":["DURATION"]},
            "trackingType":"DURATION","modality":"ISOMETRIC","metricQuality":"VERIFIED","restSeconds":0,
            "inputDefaults":{"sets":1,"reps":0,"trackingType":"DURATION","targetDurationSeconds":90},
            "sets":[{"setNumber":1,"weight":0,"reps":0,"durationSeconds":90,"isCompleted":true,
              "executionStopwatch":{"elapsedMs":90000,"bootIdentity":7,"recoveryRequired":true}}]
          }]
        }"""
        val session = WorkoutSessionCodec.decode(raw)
        val restored = WorkoutSessionCodec.decode(WorkoutSessionCodec.encode(session))
        assertEquals(session, restored)
        assertEquals(2, restored.schemaVersion)
        assertEquals(0, restored.exerciseLogs.single().restSeconds)
        assertEquals(90, restored.exerciseLogs.single().totalDurationSeconds)
        assertEquals("1:0:DURATION:90:0", restored.exerciseLogs.single().inputDefaults?.key)
        assertNull(restored.exerciseLogs.single().sets.single().measurementVersion)
        assertEquals(90000L, restored.exerciseLogs.single().sets.single().executionStopwatch?.elapsedMs)
        assertEquals(true, restored.exerciseLogs.single().sets.single().executionStopwatch?.recoveryRequired)
    }

    @Test fun newLegacyWriterPersistsItsExistingResultIdentityAndAdditionalAssistanceValues() {
        val set = WorkoutSet(1, weightKg = 12.5, reps = 8, additionalWeight = 2.5f,
            assistanceWeight = 7.75f, id = RESULT_A)
        val session = WorkoutSession("new-legacy", "routine", "Legacy", 123,
            listOf(ExerciseLog(Exercise("exercise", "Exercise", MuscleCategory.CHEST, ""), listOf(set))))
        assertEquals(2, session.schemaVersion)
        val restored = WorkoutSessionCodec.decode(WorkoutSessionCodec.encode(session))
        assertEquals(session, restored)
        assertEquals(RESULT_A, restored.exerciseLogs.single().sets.single().id)
        assertEquals(2.5f, restored.exerciseLogs.single().sets.single().additionalWeight)
        assertEquals(7.75f, restored.exerciseLogs.single().sets.single().assistanceWeight)
        assertNull(restored.exerciseLogs.single().sets.single().measurementVersion)
    }

    @Test fun v3RoundtripPreservesFrozenTargetsExtrasNullableMetricsDecimalsAndRuntimeTrigger() {
        val session = cardioSession()
        val encoded = WorkoutSessionCodec.encode(session)
        val restored = WorkoutSessionCodec.decode(encoded)
        assertEquals(session, restored)
        val log = restored.exerciseLogs.single()
        assertEquals(listOf("initial-a", "initial-b"), log.inputDefaults!!.setTargets!!.map { it.rowId })
        assertEquals(listOf("extra-a"), log.inputDefaults.extraSetTargets.map { it.rowId })
        assertNull(log.sets[0].weightKg)
        assertNull(log.sets[0].reps)
        assertNull(log.sets[0].distanceMeters)
        assertEquals(0.0, log.sets[1].distanceMeters)
        assertEquals(1234.56, log.sets[2].distanceMeters)
        assertEquals(-2.25, log.sets[2].inclinePercent)
        assertEquals(0, log.sets[1].lapReps)
        assertEquals(session.pendingRestTrigger, restored.pendingRestTrigger)
        val snapshot = Json.parseToJsonElement(encoded).jsonObject.getValue("exerciseLogs").jsonArray[0]
            .jsonObject.getValue("inputDefaults")
        assertTrue(snapshot is JsonObject)
        assertEquals(JsonPrimitive(3), snapshot.jsonObject["measurement_version"])
        assertFalse("sets" in snapshot.jsonObject)
    }

    @Test fun v3SnapshotOverridesDriftedOrMissingCatalogMetadata() {
        val session = cardioSession()
        val root = Json.parseToJsonElement(WorkoutSessionCodec.encode(session)).jsonObject
        val log = root.getValue("exerciseLogs").jsonArray.single().jsonObject
        val exercise = log.getValue("exercise").jsonObject
        val minimalExercise = JsonObject(exercise - setOf("defaultTrackingType", "allowedTrackingTypes",
            "modality", "trackingProfile", "supportedMetrics"))
        val driftedExercise = JsonObject(minimalExercise + mapOf(
            "defaultTrackingType" to JsonPrimitive("WEIGHT_REPS"),
            "allowedTrackingTypes" to JsonArray(listOf(JsonPrimitive("WEIGHT_REPS"))),
            "modality" to JsonPrimitive("STRENGTH")))
        for (metadata in listOf(minimalExercise, driftedExercise)) {
            val updatedLog = JsonObject(log + ("exercise" to metadata))
            val raw = JsonObject(root + ("exerciseLogs" to JsonArray(listOf(updatedLog)))).toString()
            assertEquals(session, WorkoutSessionCodec.decode(raw))
        }
    }

    @Test fun v3UnrecordedDistanceRemainsNullWhileExplicitZeroRemainsRecorded() {
        val session = cardioSession()
        val log = session.exerciseLogs.single()
        val unrecorded = session.copy(exerciseLogs = listOf(log.copy(sets = listOf(log.sets.first()))),
            pendingRestTrigger = null)
        val restored = WorkoutSessionCodec.decode(WorkoutSessionCodec.encode(unrecorded))
        assertNull(restored.totalDistanceMeters)
        assertNull(restored.exerciseLogs.single().totalDistanceMeters)
        val zero = unrecorded.copy(exerciseLogs = listOf(log.copy(sets = listOf(log.sets.first().copy(distanceMeters = 0.0)))))
        assertEquals(0.0, WorkoutSessionCodec.decode(WorkoutSessionCodec.encode(zero)).totalDistanceMeters)
    }

    @Test fun v3StrengthZeroLoadAndNullDraftValuesRoundtrip() {
        val exercise = Exercise("strength", "Press", MuscleCategory.CHEST, "")
        val targets = listOf(SetTarget("strength-row", 1, weightKg = 0.0, reps = 10, restSeconds = 0))
        val defaults = ExerciseInputDefaults(1, 0, TrackingType.WEIGHT_REPS.name, schemaVersion = 3,
            setTargets = targets, modality = ExerciseModality.STRENGTH)
        val set = WorkoutSet(setNumber = 1, weightKg = 0.0, reps = 10, isCompleted = true,
            id = RESULT_A, targetRowId = "strength-row", measurementVersion = 3)
        val session = WorkoutSession("strength-session", "routine", "Strength", 123,
            listOf(ExerciseLog(exercise, listOf(set), inputDefaults = defaults, measurementVersion = 3)), schemaVersion = 3)
        assertEquals(session, WorkoutSessionCodec.decode(WorkoutSessionCodec.encode(session)))
        val draft = session.copy(exerciseLogs = session.exerciseLogs.map { it.copy(sets = listOf(set.copy(
            isCompleted = false, weightKg = null, reps = null))) })
        assertEquals(draft, WorkoutSessionCodec.decode(WorkoutSessionCodec.encode(draft)))
    }

    @Test fun corruptionAndUnknownVersionsFailInsteadOfInventingAnEmptyStrengthSession() {
        listOf("", "{", "null", "[]", "{}", legacySession().replace("\"startTime\":123", "\"schemaVersion\":99,\"startTime\":123"))
            .forEach(::assertRecoveryFailure)
        val root = Json.parseToJsonElement(WorkoutSessionCodec.encode(cardioSession())).jsonObject
        assertRecoveryFailure(JsonObject(root + ("schemaVersion" to JsonPrimitive(4))).toString())
        assertRecoveryFailure(JsonObject(root + ("schemaVersion" to JsonPrimitive("3"))).toString())
        assertRecoveryFailure(JsonObject(root + ("schemaVersion" to JsonPrimitive(null as String?))).toString())
        assertRecoveryFailure(JsonObject(root + ("exerciseLogs" to JsonArray(emptyList()))).toString())
    }

    @Test fun v3MissingMetadataIdentityOrSnapshotVersionCannotFallBackToLegacy() {
        val raw = WorkoutSessionCodec.encode(cardioSession())
        val root = Json.parseToJsonElement(raw).jsonObject
        val originalLog = root.getValue("exerciseLogs").jsonArray.single().jsonObject
        fun withLog(log: JsonObject) = JsonObject(root + ("exerciseLogs" to JsonArray(listOf(log)))).toString()
        for (field in listOf("inputDefaults", "trackingType", "modality", "measurementVersion")) {
            assertRecoveryFailure(withLog(JsonObject(originalLog - field)))
        }
        val sets = originalLog.getValue("sets").jsonArray
        for (field in listOf("id", "targetRowId", "weight", "reps", "durationSeconds", "measurementVersion")) {
            val corrupted = JsonArray(listOf(JsonObject(sets[0].jsonObject - field)) + sets.drop(1))
            assertRecoveryFailure(withLog(JsonObject(originalLog + ("sets" to corrupted))))
        }
        val snapshot = originalLog.getValue("inputDefaults").jsonObject
        val futureSnapshot = JsonObject(snapshot + ("schema_version" to JsonPrimitive(4)))
        assertRecoveryFailure(withLog(JsonObject(originalLog + ("inputDefaults" to futureSnapshot))))
        assertRecoveryFailure(withLog(JsonObject(originalLog + ("inputDefaults" to JsonObject(snapshot - "extra_set_targets")))))
        assertEquals(raw, WorkoutSessionCodec.encode(cardioSession()))
    }

    @Test fun v3DuplicateIdsOrDanglingTargetAndPendingTriggerAreRejected() {
        val session = cardioSession()
        val log = session.exerciseLogs.single()
        val duplicate = log.copy(sets = log.sets.map { it.copy(id = RESULT_A) })
        assertInvalidWrite(session.copy(exerciseLogs = listOf(duplicate)))
        assertInvalidWrite(session.copy(exerciseLogs = listOf(log.copy(sets = emptyList()))))
        assertInvalidWrite(session.copy(exerciseLogs = listOf(log.copy(sets = listOf(log.sets[0], log.sets[1].copy(setNumber = 1))))))
        assertInvalidWrite(session.copy(exerciseLogs = listOf(log.copy(sets = listOf(log.sets[0], log.sets[1].copy(targetRowId = log.sets[0].targetRowId))))))
        assertInvalidWrite(session.copy(exerciseLogs = listOf(log.copy(sets = listOf(log.sets[0].copy(targetRowId = "missing"))))))
        assertInvalidWrite(session.copy(pendingRestTrigger = session.pendingRestTrigger!!.copy(deadlineElapsedMs = -1)))
        assertInvalidWrite(session.copy(schemaVersion = 2))
        assertNotEquals(log.sets[0].id, log.sets[1].id)
    }

    private fun assertInvalidWrite(session: WorkoutSession) {
        try {
            WorkoutSessionCodec.encode(session)
            fail("Invalid session should not replace its saved row")
        } catch (_: IllegalArgumentException) {
            // Existing raw Room row is not touched by this pure codec.
        }
    }

    private fun assertRecoveryFailure(raw: String) {
        try {
            WorkoutSessionCodec.decode(raw)
            fail("Corrupt/future session must report recovery failure")
        } catch (failure: SessionRecoveryException) {
        }
    }

    private fun legacySession() = """{
      "id":"legacy-session","templateId":"routine","templateName":"Legacy","startTime":123,
      "exerciseLogs":[
        {"exercise":{"id":"repeated","name":"Legacy","muscleCategory":"CHEST","description":""},
          "sets":[{"setNumber":1,"weight":12.5,"reps":8,"isCompleted":true},{"setNumber":2,"weight":0,"reps":0}]},
        {"exercise":{"id":"repeated","name":"Legacy","muscleCategory":"CHEST","description":""},
          "sets":[{"setNumber":1,"weight":12.5,"reps":8}]}
      ]
    }"""

    private fun cardioSession(): WorkoutSession {
        val metrics = listOf(CardioMetric.CALORIES, CardioMetric.DISTANCE, CardioMetric.SPEED, CardioMetric.LAPS, CardioMetric.INCLINE)
        val exercise = Exercise("cardio", "Treadmill", MuscleCategory.CARDIO, "Frozen description",
            defaultTrackingType = TrackingType.DURATION, modality = ExerciseModality.CARDIO,
            trackingProfile = TrackingProfile.EQUIPMENT_METRICS, supportedMetrics = metrics)
        val defaults = ExerciseInputDefaults(2, 0, TrackingType.DURATION.name, schemaVersion = 3,
            setTargets = listOf(SetTarget("initial-a", 1, durationSeconds = 60, restSeconds = 90),
                SetTarget("initial-b", 2, durationSeconds = 120, restSeconds = 0)),
            extraSetTargets = listOf(SetTarget("extra-a", 1, durationSeconds = 30, restSeconds = 60)),
            modality = ExerciseModality.CARDIO, trackingProfile = TrackingProfile.EQUIPMENT_METRICS, supportedMetrics = metrics)
        val sets = listOf(
            WorkoutSet(1, weightKg = null, reps = null, durationSeconds = 60, isCompleted = true,
                id = RESULT_A, targetRowId = "initial-a", measurementVersion = 3),
            WorkoutSet(2, weightKg = null, reps = null, durationSeconds = 120, isCompleted = true,
                id = RESULT_B, targetRowId = "initial-b", measurementVersion = 3, distanceMeters = 0.0,
                caloriesKcal = 0.0, speedKph = 0.0, lapReps = 0, inclinePercent = 0.0),
            WorkoutSet(3, weightKg = null, reps = null, durationSeconds = 30, isCompleted = true,
                id = RESULT_C, targetRowId = "extra-a", measurementVersion = 3, distanceMeters = 1234.56,
                caloriesKcal = 12.34, speedKph = 6.78, lapReps = 4, inclinePercent = -2.25))
        return WorkoutSession("session-v3", "routine", "Frozen workout", 123,
            listOf(ExerciseLog(exercise, sets, inputDefaults = defaults, trackingType = TrackingType.DURATION,
                modality = ExerciseModality.CARDIO, measurementVersion = 3)), schemaVersion = 3,
            pendingRestTrigger = RestTimerTrigger("session-v3", "cardio", RESULT_A, "initial-a", 90, 123456, "boot-id"))
    }

    companion object {
        private const val RESULT_A = "00000000-0000-4000-8000-000000000001"
        private const val RESULT_B = "00000000-0000-4000-8000-000000000002"
        private const val RESULT_C = "00000000-0000-4000-8000-000000000003"
    }
}
