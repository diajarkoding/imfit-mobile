package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.core.network.NetworkMonitor
import com.diajarkoding.imfit.data.local.dao.ExerciseDao
import com.diajarkoding.imfit.data.local.dao.TemplateExerciseDao
import com.diajarkoding.imfit.data.local.dao.WorkoutTemplateDao
import io.github.jan.supabase.SupabaseClient
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WorkoutTemplateRepositoryTest {

    private lateinit var repository: WorkoutTemplateRepository
    private lateinit var supabaseClient: SupabaseClient
    private lateinit var workoutTemplateDao: WorkoutTemplateDao
    private lateinit var templateExerciseDao: TemplateExerciseDao
    private lateinit var exerciseDao: ExerciseDao
    private lateinit var networkMonitor: NetworkMonitor

    @Before
    fun setup() {
        supabaseClient = mockk(relaxed = true)
        workoutTemplateDao = mockk(relaxed = true)
        templateExerciseDao = mockk(relaxed = true)
        exerciseDao = mockk(relaxed = true)
        networkMonitor = mockk()
        
        repository = WorkoutTemplateRepository(
            supabaseClient = supabaseClient,
            workoutTemplateDao = workoutTemplateDao,
            templateExerciseDao = templateExerciseDao,
            exerciseDao = exerciseDao,
            networkMonitor = networkMonitor
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `getTemplates throws when userId is blank`() = runTest {
        repository.getTemplates("")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `getTemplateById throws when templateId is blank`() = runTest {
        repository.getTemplateById("")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `createTemplate throws when name is blank`() = runTest {
        every { networkMonitor.isOnline } returns true
        repository.createTemplate(
            userId = "test-user",
            name = "",
            exercises = emptyList()
        )
    }

    @Test(expected = IllegalArgumentException::class) 
    fun `createTemplate throws when name exceeds max length`() = runTest {
        every { networkMonitor.isOnline } returns true
        val longName = "a".repeat(101)
        repository.createTemplate(
            userId = "test-user",
            name = longName,
            exercises = emptyList()
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `createTemplate throws when userId is blank`() = runTest {
        every { networkMonitor.isOnline } returns true
        repository.createTemplate(
            userId = "",
            name = "Valid Name",
            exercises = emptyList()
        )
    }
}
