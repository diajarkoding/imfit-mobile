package com.diajarkoding.imfit.data.remote.online

import org.junit.Assert.*
import org.junit.Test

class SessionDomainAdapterTest {
    private val snapshot = onlineJson.decodeFromString<SessionSnapshot>(OnlineFixtures.snapshot)
    private val current = snapshot.toDomain()
    private fun mutable() = snapshot.copy(exercises = snapshot.exercises.map { e -> e.copy(sets = e.sets.map { it.copy(isCompleted = false) }) })

    @Test fun `pause and resume use typed commands not client pause anchors`() {
        val resume = current.copy(isPaused = false, totalPausedTimeMs = Long.MAX_VALUE, lastPauseTime = 1)
        assertEquals(SessionCommand.Resume, snapshot.commandFor(resume) { "unused" })
    }

    @Test fun `weight uses remote child and set identity not a positional composite ID`() {
        val snapshot = mutable()
        val current = snapshot.toDomain()
        val next = current.copy(exerciseLogs = current.exerciseLogs.map { it.copy(sets = it.sets.map { it.copy(weight = 15.25f, reps = 12) }) })
        assertEquals(SessionCommand.EditSet("child", "set", "15.25", 12), snapshot.commandFor(next) { "unused" })
    }

    @Test fun `complete and uncomplete never carry whole caller snapshot`() {
        val uncomplete = current.copy(exerciseLogs = current.exerciseLogs.map { it.copy(sets = it.sets.map { it.copy(isCompleted = false) }) })
        assertEquals(SessionCommand.UncompleteSet("child", "set"), snapshot.commandFor(uncomplete) { "unused" })
        val pending = mutable(); val model = pending.toDomain()
        assertEquals(SessionCommand.CompleteSet("child", "set"), pending.commandFor(model.copy(exerciseLogs = current.exerciseLogs)) { "unused" })
    }

    @Test fun `added set only creates command identity and server chooses defaults`() {
        val pending = mutable(); val model = pending.toDomain()
        val next = model.copy(exerciseLogs = model.exerciseLogs.map { it.copy(sets = it.sets + it.sets.single().copy(setNumber = 6)) })
        assertEquals(SessionCommand.AddSet("child", "generated"), pending.commandFor(next) { "generated" })
    }

    @Test fun `snapshot with multiple changed intents fails instead of partial upload`() {
        val pending = mutable(); val model = pending.toDomain()
        val next = model.copy(exerciseLogs = model.exerciseLogs.map { it.copy(restSeconds = 30, sets = it.sets.map { it.copy(weight = 20f) }) })
        assertTrue(runCatching { pending.commandFor(next) { "unused" } }.isFailure)
    }

    @Test fun `client anchors and completed weights cannot be overwritten`() {
        assertTrue(runCatching { snapshot.commandFor(current.copy(startTime = 1)) { "unused" } }.isFailure)
        val edited = current.copy(exerciseLogs = current.exerciseLogs.map { it.copy(sets = it.sets.map { it.copy(weight = 25f) }) })
        assertTrue(runCatching { snapshot.commandFor(edited) { "unused" } }.isFailure)
    }

    @Test fun `duplicate exercise and set identity cannot produce ambiguous domain projection`() {
        assertTrue(runCatching { snapshot.copy(exercises = snapshot.exercises + snapshot.exercises).toDomain() }.isFailure)
        assertTrue(runCatching { snapshot.copy(exercises = snapshot.exercises.map { it.copy(sets = it.sets + it.sets) }).toDomain() }.isFailure)
    }
}
