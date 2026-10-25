package com.diajarkoding.imfit.domain.model

import org.junit.Assert.*
import org.junit.Test

class CompletedVolumeTest {
    @Test fun manuallyAddedSetUsesTargetDefaultsNotTheLastPerformance() {
        val log = ExerciseLog(Exercise("id", "Exercise", MuscleCategory.CHEST, ""),
            listOf(WorkoutSet(1, 50f, 6, true)), inputDefaults = ExerciseInputDefaults(3, 12))
        val added = log.addDefaultSet()
        assertEquals(WorkoutSet(2, 0f, 12, false), added.sets.last())
        assertEquals(300f, added.totalVolume)
    }
    @Test fun prefilledValuesAreZeroUntilEachSetCompletes() {
        val log = ExerciseLog(Exercise("id", "Exercise", MuscleCategory.CHEST, ""),
            listOf(WorkoutSet(1, 40f, 10), WorkoutSet(2, 45f, 8)))
        assertEquals(0f, log.totalVolume)
        val first = log.copy(sets = listOf(log.sets[0].copy(isCompleted = true), log.sets[1]))
        assertEquals(400f, first.totalVolume)
        val finished = first.copy(sets = first.sets.map { it.copy(isCompleted = true) })
        assertEquals(760f, finished.totalVolume)
        assertEquals(760f, WorkoutSession("session", "template", "Workout", 0, listOf(finished)).totalVolume)
        assertEquals(760f, finished.copy(sets = finished.sets.map { it.copy(isCompleted = true) }).totalVolume)
    }
}
