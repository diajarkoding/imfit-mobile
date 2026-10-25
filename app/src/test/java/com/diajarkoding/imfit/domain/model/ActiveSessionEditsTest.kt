package com.diajarkoding.imfit.domain.model

import org.junit.Assert.*
import org.junit.Test

class ActiveSessionEditsTest {
    private fun exercise(id: String, complete: Boolean = false) = ExerciseLog(
        Exercise(id, id, MuscleCategory.CHEST, ""), listOf(WorkoutSet(1, 20f, 8, complete)))
    private fun session() = WorkoutSession("session", "template", "Workout", 123L,
        listOf(exercise("a"), exercise("b", true), exercise("c")), currentExerciseIndex = 1, isPaused = true)

    @Test fun removingBeforeCurrentPreservesCompletedDataAndPauseState() {
        val result = session().removeIncompleteExercise("a")
        assertEquals(listOf("b", "c"), result.exerciseLogs.map { it.exercise.id })
        assertEquals("b", result.currentExercise?.exercise?.id)
        assertEquals(0, result.currentExerciseIndex)
        assertTrue(result.isPaused)
        assertEquals(1, result.totalCompletedSets)
    }

    @Test fun completedExerciseCannotBeRemoved() {
        assertEquals(session(), session().removeIncompleteExercise("b"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun repositoryValidationRejectsStaleDeletionAfterCompletion() {
        val current = session()
        validateActiveSessionEdit(current, current.copy(exerciseLogs = current.exerciseLogs.filterNot { it.exercise.id == "b" }))
    }

    @Test fun appendIsIdempotentAndDoesNotOverwriteExistingSets() {
        val current = session()
        val additions = listOf(exercise("b"), exercise("d"), exercise("d"))
        val result = current.appendExercises(additions).appendExercises(additions)
        assertEquals(listOf("a", "b", "c", "d"), result.exerciseLogs.map { it.exercise.id })
        assertTrue(result.exerciseLogs[1].sets.first().isCompleted)
        assertEquals(current.startTime, result.startTime)
    }

    @Test fun removingLastExerciseLeavesUsableEmptySession() {
        val current = session().copy(exerciseLogs = listOf(exercise("a")), currentExerciseIndex = 0)
        val result = current.removeIncompleteExercise("a")
        assertTrue(result.exerciseLogs.isEmpty())
        assertNull(result.currentExercise)
        assertEquals(0, result.currentExerciseIndex)
        assertFalse(result.isCompleted)
    }
}
