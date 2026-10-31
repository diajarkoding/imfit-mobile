package com.diajarkoding.imfit.presentation.ui.workout

import androidx.lifecycle.SavedStateHandle
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.WorkoutSession
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.RoutineError
import com.diajarkoding.imfit.domain.repository.RoutineRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class RoutineSessionCutoverTest {
    private val exercise = Exercise("bench", "Bench", MuscleCategory.CHEST, "")
    private val routine = WorkoutTemplate("routine", "owner", "Push", listOf(TemplateExercise(exercise)), revision = 7)

    @Test fun transportFailureStartsOnlyMatchingCanonicalMemorySnapshot() = runBlocking {
        val fixture = Fixture(routine)
        fixture.routines.response = Result.failure(RoutineError.Network("Offline"))
        val started = fixture.starter.start(routine.id, null)!!
        assertEquals(7L, started.lastKnownRevision)
        assertFalse(started.restored)
        assertEquals(routine, fixture.startedTemplate)
        assertEquals("stable-local-session", started.session.id)
    }

    @Test fun offlineWithoutSnapshotFailsExplicitlyAndCanRetry() = runBlocking {
        val fixture = Fixture(null)
        fixture.routines.response = Result.failure(RoutineError.Network("Offline"))
        assertTrue(runCatching { fixture.starter.start(routine.id, null) }.exceptionOrNull() is RoutineError.Network)
        assertNull(fixture.startedTemplate)
        fixture.routines.response = Result.success(routine)
        assertNotNull(fixture.starter.start(routine.id, null))
    }

    @Test fun serverSuccessIsCanonicalEvenWhenMemoryIsOlder() = runBlocking {
        val fixture = Fixture(routine)
        val canonical = routine.copy(name = "Latest", revision = 12, exercises = listOf(TemplateExercise(exercise, reps = 14)))
        fixture.routines.response = Result.success(canonical)
        val started = fixture.starter.start(routine.id, null)!!
        assertEquals(canonical, fixture.startedTemplate)
        assertNull(started.lastKnownRevision)
    }

    @Test fun authoritativeErrorsNeverStartCachedRoutine() = runBlocking {
        listOf(RoutineError.Unauthorized(), RoutineError.NotFound(), RoutineError.ValidationError("Invalid"),
            RoutineError.RevisionConflict()).forEach { error ->
            val fixture = Fixture(routine)
            fixture.routines.response = Result.failure(error)
            assertSame(error, runCatching { fixture.starter.start(routine.id, null) }.exceptionOrNull())
            assertNull(fixture.startedTemplate)
        }
    }

    @Test fun successfulMissingReadDoesNotUseMemory() = runBlocking {
        val fixture = Fixture(routine)
        fixture.routines.response = Result.success(null)
        assertTrue(runCatching { fixture.starter.start(routine.id, null) }.exceptionOrNull() is RoutineError.NotFound)
        assertNull(fixture.startedTemplate)
    }

    @Test fun resumeKeepsStableIdCompletedSetsAndIgnoresDeletedRoutine() = runBlocking {
        val fixture = Fixture(null)
        val original = WorkoutSession("original-local-id", routine.id, "Original name", 0,
            listOf(ExerciseLog(exercise, listOf(WorkoutSet(1, 40f, 8, true)))))
        fixture.active = original
        fixture.routines.response = Result.failure(RoutineError.NotFound())
        val restored = fixture.starter.start(routine.id, original.id)!!
        assertSame(original, restored.session)
        assertTrue(restored.restored)
        assertEquals(0, fixture.routines.reads)
        assertNull(fixture.startedTemplate)
    }

    @Test fun endedExpectedSessionDoesNotCreateAReplacement() = runBlocking {
        val fixture = Fixture(routine)
        assertNull(fixture.starter.start(routine.id, "ended-id"))
        assertEquals(0, fixture.routines.reads)
        assertNull(fixture.startedTemplate)
    }

    @Test fun accountSwitchDuringRemoteReadCannotStartOldOwnersSnapshot() = runBlocking {
        val fixture = Fixture(routine)
        fixture.routines.beforeResponse = { fixture.owner = "different-owner" }
        assertTrue(runCatching { fixture.starter.start(routine.id, null) }.exceptionOrNull() is RoutineError.Unauthorized)
        assertNull(fixture.startedTemplate)
    }

    @Test fun foreignOwnerSnapshotCannotBeUsedForOfflineStart() = runBlocking {
        val fixture = Fixture(routine.copy(userId = "other-owner"))
        fixture.routines.response = Result.failure(RoutineError.Network("Offline"))
        assertTrue(runCatching { fixture.starter.start(routine.id, null) }.exceptionOrNull() is RoutineError.Unauthorized)
        assertNull(fixture.startedTemplate)
    }

    @Test fun durationOnlyLegacyRoutineCannotStartAsSyntheticRepetitions() = runBlocking {
        val plank = Exercise("ex_core_1", "Renamed exercise", MuscleCategory.CORE, "")
        val legacy = routine.copy(exercises = listOf(TemplateExercise(plank, reps = 45)))
        val fixture = Fixture(legacy)
        assertTrue(runCatching { fixture.starter.start(routine.id, null) }.exceptionOrNull() is RoutineError.ValidationError)
        assertNull(fixture.startedTemplate)
    }


    @Test fun deleteRetryRestoresExactlyTheImmutableCommandAfterRecreation() = runBlocking {
        val firstHandle = SavedStateHandle()
        val command = RoutineDeleteCommand("owner", routine.id, routine.revision, routine.name,
            routine.exercises.map { it.id }, "stable-delete-request")
        command.persist(firstHandle)
        val repository = FakeRoutines(routine)
        assertTrue(command.execute(repository).exceptionOrNull() is RoutineError.Network)
        val recreated = SavedStateHandle(firstHandle.keys().associateWith { firstHandle.get<Any?>(it) })
        val replay = RoutineDeleteCommand.restore(recreated)!!
        assertEquals(command, replay)
        // A refreshed presentation must not rewrite the in-flight command payload.
        repository.snapshot = routine.copy(name = "New presentation", revision = 99, exercises = emptyList())
        repository.deleteResponse = Result.success(true)
        assertTrue(replay.execute(repository).getOrThrow())
        assertEquals(listOf(command, command), repository.deleteCommands)
        assertEquals(command, RoutineDeleteCommand.restore(recreated))
        RoutineDeleteCommand.clear(recreated)
        assertNull(RoutineDeleteCommand.restore(recreated))
    }

    private inner class Fixture(snapshot: WorkoutTemplate?) {
        var owner = "owner"
        var active: WorkoutSession? = null
        var startedTemplate: WorkoutTemplate? = null
        val routines = FakeRoutines(snapshot)
        private val auth = proxy<AuthRepository> { method, _ ->
            when (method) { "getLocalUserId" -> owner; else -> error("Unexpected auth access: $method") }
        }
        private val workouts = proxy<WorkoutRepository> { method, args ->
            when (method) {
                "getActiveSessionForTimer" -> active
                "startWorkout" -> {
                    val template = args[0] as WorkoutTemplate
                    startedTemplate = template
                    WorkoutSession("stable-local-session", template.id, template.name, 0,
                        template.exercises.map { ExerciseLog(it.exercise, listOf(WorkoutSet(1, 0f, it.reps))) })
                        .also { active = it }
                }
                else -> error("Unexpected repository access (including Room routine lookup): $method")
            }
        }
        val starter = RoutineSessionStarter(workouts, routines, auth)
    }

    private class FakeRoutines(var snapshot: WorkoutTemplate?) : RoutineRepository {
        var response: Result<WorkoutTemplate?> = Result.success(snapshot)
        var reads = 0
        var beforeResponse: () -> Unit = {}
        var deleteResponse: Result<Boolean> = Result.failure(RoutineError.Network("Response lost after commit"))
        val deleteCommands = mutableListOf<RoutineDeleteCommand>()
        override fun getCachedRoutine(userId: String, routineId: String) = snapshot
        override suspend fun getRoutineById(routineId: String): Result<WorkoutTemplate?> {
            reads++
            beforeResponse()
            return response
        }
        override suspend fun getRoutines(userId: String) = error("Unused")
        override suspend fun createRoutine(userId: String, name: String, requestId: String) = error("Unused")
        override suspend fun saveRoutine(routineId: String, name: String, exercises: List<TemplateExercise>,
            expectedRevision: Long?, requestId: String, baselineExerciseIds: List<String>) = error("Unused")
        override suspend fun deleteRoutine(routineId: String, expectedRevision: Long?, requestId: String,
            baselineExerciseIds: List<String>, name: String): Result<Boolean> {
            deleteCommands.add(RoutineDeleteCommand("owner", routineId, requireNotNull(expectedRevision), name,
                baselineExerciseIds, requestId))
            return deleteResponse
        }
    }

    private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            invoke(method.name, args ?: emptyArray())
        } as T
}
