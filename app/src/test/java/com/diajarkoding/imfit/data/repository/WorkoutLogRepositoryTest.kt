package com.diajarkoding.imfit.data.repository

import com.diajarkoding.imfit.data.local.dao.ExerciseDao
import com.diajarkoding.imfit.data.local.dao.ExerciseLogDao
import com.diajarkoding.imfit.data.local.dao.WorkoutLogDao
import com.diajarkoding.imfit.data.local.dao.WorkoutSetDao
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import io.github.jan.supabase.SupabaseClient
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class WorkoutLogRepositoryTest {

    private lateinit var repository: WorkoutLogRepository
    private lateinit var supabaseClient: SupabaseClient
    private lateinit var database: IMFITDatabase
    private lateinit var workoutLogDao: WorkoutLogDao
    private lateinit var exerciseLogDao: ExerciseLogDao
    private lateinit var workoutSetDao: WorkoutSetDao
    private lateinit var exerciseDao: ExerciseDao

    @Before
    fun setup() {
        supabaseClient = mockk(relaxed = true)
        database = mockk(relaxed = true)
        workoutLogDao = mockk(relaxed = true)
        exerciseLogDao = mockk(relaxed = true)
        workoutSetDao = mockk(relaxed = true)
        exerciseDao = mockk(relaxed = true)
        
        repository = WorkoutLogRepository(
            supabaseClient = supabaseClient,
            database = database,
            workoutLogDao = workoutLogDao,
            exerciseLogDao = exerciseLogDao,
            workoutSetDao = workoutSetDao,
            exerciseDao = exerciseDao
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `getWorkoutLogs throws when userId is blank`() = runTest {
        repository.getWorkoutLogs("")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `getWorkoutLogById throws when logId is blank`() = runTest {
        repository.getWorkoutLogById("")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `getLastWorkoutLog throws when userId is blank`() = runTest {
        repository.getLastWorkoutLog("")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `getLastExerciseLog throws when exerciseId is blank`() = runTest {
        repository.getLastExerciseLog("")
    }
}
