package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.sync.DemoSyncStateProvider
import com.diajarkoding.imfit.data.sync.SyncState
import com.diajarkoding.imfit.domain.model.MuscleCategory
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import com.diajarkoding.imfit.domain.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DemoRepositoriesTest {

    @Test
    fun authStartsLoggedInAndSupportsLogoutAndLogin() = runBlocking {
        val repository = DemoAuthRepository()

        assertTrue(repository.isLoggedIn())
        assertEquals(DemoAuthRepository.DEMO_EMAIL, repository.getCurrentUser()?.email)

        repository.logout()
        assertFalse(repository.isLoggedIn())

        val result = repository.login(
            DemoAuthRepository.DEMO_EMAIL,
            DemoAuthRepository.DEMO_PASSWORD
        )
        assertTrue(result.isSuccess)
    }

    @Test
    fun exerciseRepositoryFiltersAndSearchesInMemoryData() = runBlocking {
        val repository = DemoExerciseRepository()

        val chestExercises = repository.getExercisesByCategory(MuscleCategory.CHEST)
        val searchResults = repository.searchExercises("bench")

        assertTrue(chestExercises.isNotEmpty())
        assertTrue(chestExercises.all { it.muscleCategory == MuscleCategory.CHEST })
        assertTrue(searchResults.any { it.name.contains("bench", ignoreCase = true) })
    }

    @Test
    fun workoutRepositorySupportsTemplateAndSessionFlowInMemory() = runBlocking {
        DemoAuthRepository()
        val exercises = DemoExerciseRepository().getAllExercises().take(2)
        val repository = DemoWorkoutRepository()
        repository.cancelWorkout()

        val template = repository.createTemplate(
            userId = "user_1",
            name = "UI Test Workout",
            exercises = exercises.map { com.diajarkoding.imfit.domain.model.TemplateExercise(it) }
        )
        assertNotNull(repository.getTemplateById(template.id))

        val session = repository.startWorkout(template)
        repository.updateSessionRestOverride(90)
        assertEquals(session.id, repository.getActiveSession()?.id)
        assertEquals(90, repository.getSessionRestOverride())

        val completedSession = session.copy(
            exerciseLogs = session.exerciseLogs.map { log ->
                log.copy(sets = log.sets.map { it.copy(weight = 20f, reps = 10, isCompleted = true) })
            }
        )
        repository.updateActiveSession(completedSession)

        val log = repository.finishWorkout()
        assertNotNull(log)
        assertNull(repository.getActiveSession())
        assertTrue(repository.deleteTemplate(template.id))
    }

    @Test
    fun demoSyncProviderNeverStartsSynchronization() {
        val provider = DemoSyncStateProvider()

        assertEquals(SyncState.SyncStatus.IDLE, provider.syncState.value.status)
        assertEquals(0, provider.syncState.value.pendingCount)
    }

    @Test fun templateObservationUpdatesWithoutSyncOrReopeningRepository() = runBlocking {
        val repository = DemoWorkoutRepository()
        val template = repository.createTemplate("user_1", "Before", emptyList())
        val observed = async(start = CoroutineStart.UNDISPATCHED) {
            repository.observeTemplate(template.id).first { it?.name == "After" }
        }
        repository.updateTemplate(template.id, "After", emptyList())
        assertEquals("After", withTimeout(2000) { observed.await() }?.name)
        assertTrue(repository.deleteTemplate(template.id))
    }

    @Test fun concurrentSessionEditsAndCancellationCannotLoseOrResurrectData() = runBlocking {
        val repository = DemoWorkoutRepository()
        repository.cancelWorkout()
        val exercise = DemoExerciseRepository().getAllExercises().first()
        val template = repository.createTemplate("user_1", "Atomic", listOf(TemplateExercise(exercise)))
        val session = repository.startWorkout(template)
        coroutineScope {
            (1..20).map { async(Dispatchers.Default) {
                repository.mutateActiveSession(session.id) { current -> current.copy(totalPausedTimeMs = current.totalPausedTimeMs + 1) }
            } }.awaitAll()
        }
        assertEquals(20L, repository.getActiveSession()?.totalPausedTimeMs)
        repository.requestCancelSession(session.id)
        assertNull(repository.getActiveSession())
        repository.updateActiveSession(session)
        assertNull(repository.getActiveSession())
        val replacement = repository.startWorkout(template)
        assertEquals(replacement.id, repository.getActiveSession()?.id)
        assertNull(repository.mutateActiveSession(session.id) { it })
        repository.cancelWorkout()
        assertTrue(repository.deleteTemplate(template.id))
    }

    @Test fun finishingSessionPublishesOnlyLatestSessionToHomeAndAllLogsToProgress() = runBlocking {
        val auth = DemoAuthRepository()
        val userId = requireNotNull(auth.getCurrentUser()).id
        val repository = DemoWorkoutRepository()
        repository.cancelWorkout()
        val name = "Latest history ${System.nanoTime()}"
        val exercise = DemoExerciseRepository().getAllExercises().first()
        val template = repository.createTemplate(userId, name, listOf(TemplateExercise(exercise, sets = 1)))
        val previousLogs = repository.getWorkoutLogs(userId)
        val home = async(start = CoroutineStart.UNDISPATCHED) {
            repository.observeLastWorkoutLog(userId).first { it?.templateName == name }
        }
        val progress = async(start = CoroutineStart.UNDISPATCHED) {
            repository.observeWorkoutLogs(userId).first { logs -> logs.any { it.templateName == name } }
        }
        try {
            val session = repository.startWorkout(template)
            repository.mutateActiveSession(session.id) { current -> current.copy(exerciseLogs = current.exerciseLogs.map { log ->
                log.copy(sets = log.sets.map { it.copy(weight = 20f, reps = 8, isCompleted = true) })
            }) }
            withTimeout(2000) {
                val previousEnd = previousLogs.maxOfOrNull { it.endTime } ?: Long.MIN_VALUE
                while (System.currentTimeMillis() <= previousEnd) delay(1)
            }
            val finished = requireNotNull(repository.finishWorkout())
            val latest = withTimeout(2000) { home.await() }
            assertEquals(finished.id, latest?.id)
            assertEquals(160f, requireNotNull(latest).totalVolume)
            assertEquals(previousLogs.size + 1, withTimeout(2000) { progress.await() }.size)
        } finally {
            home.cancel(); progress.cancel()
            repository.cancelWorkout()
            repository.deleteTemplate(template.id)
        }
    }
}
