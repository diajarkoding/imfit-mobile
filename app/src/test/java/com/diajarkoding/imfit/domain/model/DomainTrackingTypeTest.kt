package com.diajarkoding.imfit.domain.model

import org.junit.Assert.*
import org.junit.Test

class DomainTrackingTypeTest {

    private val chestExercise = Exercise(
        id = "ex_chest_1",
        name = "Barbell Bench Press",
        muscleCategory = MuscleCategory.CHEST,
        description = "Flat bench press",
        defaultTrackingType = TrackingType.WEIGHT_REPS,
        modality = ExerciseModality.STRENGTH
    )

    private val pushUpExercise = Exercise(
        id = "ex_chest_4",
        name = "Push-Ups",
        muscleCategory = MuscleCategory.CHEST,
        description = "Bodyweight push-ups",
        defaultTrackingType = TrackingType.BODYWEIGHT_REPS,
        modality = ExerciseModality.CALISTHENICS,
        allowedTrackingTypes = listOf(TrackingType.BODYWEIGHT_REPS, TrackingType.REPS_ONLY)
    )

    private val assistedPullUpExercise = Exercise(
        id = "ex_back_17",
        name = "Assisted Pull-Up Machine",
        muscleCategory = MuscleCategory.BACK,
        description = "Machine assisted pull-up",
        defaultTrackingType = TrackingType.ASSISTED_REPS,
        modality = ExerciseModality.STRENGTH
    )

    private val jumpingJacksExercise = Exercise(
        id = "ex_cardio_10",
        name = "Jumping Jacks",
        muscleCategory = MuscleCategory.CARDIO,
        description = "Cardio jumping jacks",
        defaultTrackingType = TrackingType.REPS_ONLY,
        modality = ExerciseModality.CARDIO,
        allowedTrackingTypes = listOf(TrackingType.REPS_ONLY, TrackingType.DURATION)
    )

    private val plankExercise = Exercise(
        id = "ex_core_1",
        name = "Plank",
        muscleCategory = MuscleCategory.CORE,
        description = "Core plank hold",
        defaultTrackingType = TrackingType.DURATION,
        modality = ExerciseModality.ISOMETRIC
    )

    private val treadmillExercise = Exercise(
        id = "ex_cardio_1",
        name = "Treadmill Running",
        muscleCategory = MuscleCategory.CARDIO,
        description = "Treadmill running",
        defaultTrackingType = TrackingType.DISTANCE_DURATION,
        modality = ExerciseModality.CARDIO,
        allowedTrackingTypes = listOf(TrackingType.DISTANCE_DURATION, TrackingType.DURATION)
    )

    // A3-01: Domain WEIGHT_REPS: 40 kg x 10 = 400 kg volume
    @Test
    fun testWeightRepsMeasurementAndVolume() {
        val validMeasurement = SetMeasurement(weight = 40f, reps = 10)
        assertTrue(validMeasurement.isCompleteEligible(TrackingType.WEIGHT_REPS))

        val zeroWeight = SetMeasurement(weight = 0f, reps = 10)
        assertFalse(zeroWeight.isCompleteEligible(TrackingType.WEIGHT_REPS))

        val zeroReps = SetMeasurement(weight = 40f, reps = 0)
        assertFalse(zeroReps.isCompleteEligible(TrackingType.WEIGHT_REPS))

        val set = WorkoutSet(setNumber = 1, weight = 40f, reps = 10, isCompleted = true)
        assertEquals(400f, set.volume)

        val log = ExerciseLog(
            exercise = chestExercise,
            sets = listOf(set),
            trackingType = TrackingType.WEIGHT_REPS
        )
        assertEquals(400f, log.totalVolume)
        assertEquals(10, log.totalReps)
    }

    // A3-02: Domain BODYWEIGHT_REPS: Push-up 0 kg valid, volume = 0, reps = 15
    @Test
    fun testBodyweightRepsMeasurementAndAdditionalLoad() {
        val unweighted = SetMeasurement(reps = 15, additionalWeight = 0f)
        assertTrue(unweighted.isCompleteEligible(TrackingType.BODYWEIGHT_REPS))

        val zeroReps = SetMeasurement(reps = 0, additionalWeight = 0f)
        assertFalse(zeroReps.isCompleteEligible(TrackingType.BODYWEIGHT_REPS))

        val unweightedSet = WorkoutSet(setNumber = 1, weight = 0f, reps = 15, isCompleted = true, additionalWeight = 0f)
        val weightedSet = WorkoutSet(setNumber = 2, weight = 0f, reps = 10, isCompleted = true, additionalWeight = 15f)

        val log = ExerciseLog(
            exercise = pushUpExercise,
            sets = listOf(unweightedSet, weightedSet),
            trackingType = TrackingType.BODYWEIGHT_REPS
        )
        // External load volume must be 0 for bodyweight
        assertEquals(0f, log.totalVolume)
        // Additional load volume = 15 kg * 10 reps = 150 kg
        assertEquals(150f, log.additionalVolume)
        assertEquals(25, log.totalReps)
        assertEquals(2, log.completedSets)
    }

    // A3-03: Domain ASSISTED_REPS: Assistance does not add to lifted volume
    @Test
    fun testAssistedRepsDoesNotCountAsLiftedVolume() {
        val measurement = SetMeasurement(reps = 10, assistanceWeight = 20f)
        assertTrue(measurement.isCompleteEligible(TrackingType.ASSISTED_REPS))

        val set = WorkoutSet(setNumber = 1, weight = 0f, reps = 10, isCompleted = true, assistanceWeight = 20f)
        val log = ExerciseLog(
            exercise = assistedPullUpExercise,
            sets = listOf(set),
            trackingType = TrackingType.ASSISTED_REPS
        )
        assertEquals(0f, log.totalVolume)
        assertEquals(0f, log.additionalVolume)
        assertEquals(10, log.totalReps)
    }

    // A3-04: Domain REPS_ONLY: Does not require weight
    @Test
    fun testRepsOnlyMeasurement() {
        val measurement = SetMeasurement(reps = 30)
        assertTrue(measurement.isCompleteEligible(TrackingType.REPS_ONLY))

        val zeroReps = SetMeasurement(reps = 0)
        assertFalse(zeroReps.isCompleteEligible(TrackingType.REPS_ONLY))

        val set = WorkoutSet(setNumber = 1, reps = 30, isCompleted = true)
        val log = ExerciseLog(
            exercise = jumpingJacksExercise,
            sets = listOf(set),
            trackingType = TrackingType.REPS_ONLY
        )
        assertEquals(0f, log.totalVolume)
        assertEquals(30, log.totalReps)
    }

    // A3-05: Domain DURATION: Plank 45 seconds valid
    @Test
    fun testDurationMeasurement() {
        val measurement = SetMeasurement(durationSeconds = 45)
        assertTrue(measurement.isCompleteEligible(TrackingType.DURATION))

        val zeroDuration = SetMeasurement(durationSeconds = 0)
        assertFalse(zeroDuration.isCompleteEligible(TrackingType.DURATION))

        val set = WorkoutSet(setNumber = 1, isCompleted = true, durationSeconds = 45)
        val log = ExerciseLog(
            exercise = plankExercise,
            sets = listOf(set),
            trackingType = TrackingType.DURATION
        )
        assertEquals(0f, log.totalVolume)
        assertEquals(0, log.totalReps)
        assertEquals(45, log.totalDurationSeconds)
    }

    // A3-06: Domain DISTANCE_DURATION: 5000 m / 1800 s valid
    @Test
    fun testDistanceDurationMeasurement() {
        val measurement = SetMeasurement(distanceMeters = 5000, durationSeconds = 1800)
        assertTrue(measurement.isCompleteEligible(TrackingType.DISTANCE_DURATION))

        val zeroDistance = SetMeasurement(distanceMeters = 0, durationSeconds = 1800)
        assertFalse(zeroDistance.isCompleteEligible(TrackingType.DISTANCE_DURATION))

        val zeroDuration = SetMeasurement(distanceMeters = 5000, durationSeconds = 0)
        assertFalse(zeroDuration.isCompleteEligible(TrackingType.DISTANCE_DURATION))

        val set = WorkoutSet(setNumber = 1, isCompleted = true, distanceMeters = 5000, durationSeconds = 1800)
        val log = ExerciseLog(
            exercise = treadmillExercise,
            sets = listOf(set),
            trackingType = TrackingType.DISTANCE_DURATION
        )
        assertEquals(0f, log.totalVolume)
        assertEquals(0, log.totalReps)
        assertEquals(5000, log.totalDistanceMeters)
        assertEquals(1800, log.totalDurationSeconds)
    }

    // Target validation tests
    @Test
    fun testExerciseTargetValidation() {
        val repsTarget = ExerciseTarget(sets = 3, targetReps = 10, restSeconds = 60)
        assertTrue(repsTarget.isValidFor(TrackingType.WEIGHT_REPS))
        assertTrue(repsTarget.isValidFor(TrackingType.BODYWEIGHT_REPS))
        assertFalse(repsTarget.isValidFor(TrackingType.DURATION)) // missing targetDurationSeconds

        val durationTarget = ExerciseTarget(sets = 3, targetDurationSeconds = 45, restSeconds = 60)
        assertTrue(durationTarget.isValidFor(TrackingType.DURATION))
        assertFalse(durationTarget.isValidFor(TrackingType.WEIGHT_REPS)) // missing targetReps

        val distanceTarget = ExerciseTarget(sets = 1, targetDurationSeconds = 1800, targetDistanceMeters = 5000, restSeconds = 60)
        assertTrue(distanceTarget.isValidFor(TrackingType.DISTANCE_DURATION))
    }

    // WorkoutSession & WorkoutLog multi-modality aggregates
    @Test
    fun testWorkoutSessionAndLogMultiModalityAggregates() {
        val benchLog = ExerciseLog(
            exercise = chestExercise,
            sets = listOf(WorkoutSet(1, weight = 50f, reps = 10, isCompleted = true)),
            trackingType = TrackingType.WEIGHT_REPS
        )
        val pushUpLog = ExerciseLog(
            exercise = pushUpExercise,
            sets = listOf(WorkoutSet(1, weight = 0f, reps = 15, isCompleted = true, additionalWeight = 10f)),
            trackingType = TrackingType.BODYWEIGHT_REPS
        )
        val plankLog = ExerciseLog(
            exercise = plankExercise,
            sets = listOf(WorkoutSet(1, isCompleted = true, durationSeconds = 60)),
            trackingType = TrackingType.DURATION
        )
        val treadmillLog = ExerciseLog(
            exercise = treadmillExercise,
            sets = listOf(WorkoutSet(1, isCompleted = true, distanceMeters = 3000, durationSeconds = 1200)),
            trackingType = TrackingType.DISTANCE_DURATION
        )

        val session = WorkoutSession(
            id = "sess_1",
            templateId = "tmpl_1",
            templateName = "Full Mix",
            startTime = 1000L,
            exerciseLogs = listOf(benchLog, pushUpLog, plankLog, treadmillLog)
        )

        // Session aggregates
        assertEquals(500f, session.totalVolume) // 50 * 10
        assertEquals(150f, session.additionalVolume) // 10 * 15
        assertEquals(25, session.totalReps) // 10 + 15
        assertEquals(1260, session.totalDurationSeconds) // 60 + 1200
        assertEquals(3000, session.totalDistanceMeters) // 3000
        assertEquals(4, session.totalCompletedSets)

        val workoutLog = WorkoutLog(
            id = "wkt_1",
            userId = "user_1",
            templateName = session.templateName,
            date = 1000L,
            startTime = 1000L,
            endTime = 1000L + 1800000L,
            totalVolume = session.totalVolume,
            exerciseLogs = session.exerciseLogs
        )
        assertEquals(500f, workoutLog.totalVolume)
        assertEquals(150f, workoutLog.additionalVolume)
        assertEquals(25, workoutLog.totalReps)
        assertEquals(1260, workoutLog.totalDurationSeconds)
        assertEquals(3000, workoutLog.totalDistanceMeters)
        assertEquals("VERIFIED", workoutLog.dataQuality)
    }
}
