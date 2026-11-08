package com.diajarkoding.imfit.domain.model

import org.junit.Assert.*
import org.junit.Test

class CompletedVolumeTest {
    @Test fun manuallyAddedSetUsesTargetDefaultsNotTheLastPerformance() {
        val log = ExerciseLog(Exercise("id", "Exercise", MuscleCategory.CHEST, ""),
            listOf(WorkoutSet(1, 50.0, 6, true)), inputDefaults = ExerciseInputDefaults(3, 12))
        val added = log.addDefaultSet()
        assertEquals(2, added.sets.last().setNumber)
        assertEquals(SetMeasurement(weightKg = 0.0, reps = 12), added.sets.last().measurement)
        assertFalse(added.sets.last().isCompleted)
        assertEquals(300f, added.totalVolume)
    }
    @Test fun prefilledValuesAreZeroUntilEachSetCompletes() {
        val log = ExerciseLog(Exercise("id", "Exercise", MuscleCategory.CHEST, ""),
            listOf(WorkoutSet(1, 40.0, 10), WorkoutSet(2, 45.0, 8)))
        assertEquals(0f, log.totalVolume)
        val first = log.copy(sets = listOf(log.sets[0].copy(isCompleted = true), log.sets[1]))
        assertEquals(400f, first.totalVolume)
        val finished = first.copy(sets = first.sets.map { it.copy(isCompleted = true) })
        assertEquals(760f, finished.totalVolume)
        assertEquals(760f, WorkoutSession("session", "template", "Workout", 0, listOf(finished)).totalVolume)
    }
}
