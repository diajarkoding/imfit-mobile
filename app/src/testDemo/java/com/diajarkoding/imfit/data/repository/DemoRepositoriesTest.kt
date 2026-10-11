package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.sync.DemoSyncStateProvider
import com.diajarkoding.imfit.data.sync.SyncState
import com.diajarkoding.imfit.domain.model.MuscleCategory
import kotlinx.coroutines.runBlocking
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
}
