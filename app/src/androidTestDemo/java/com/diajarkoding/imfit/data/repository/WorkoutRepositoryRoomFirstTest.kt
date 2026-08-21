package com.diajarkoding.imfit.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.local.sync.PendingOperation
import com.diajarkoding.imfit.data.local.sync.SyncStatus
import com.diajarkoding.imfit.data.sync.SyncScheduler
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.User
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.AuthRepository
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WorkoutRepositoryRoomFirstTest {
    private lateinit var database: IMFITDatabase
    private lateinit var scheduler: RecordingSyncScheduler
    private lateinit var repository: WorkoutRepositoryImpl
    private val user = User(UUID.randomUUID().toString(), "Test User", "test@imfit.com")

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, IMFITDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        scheduler = RecordingSyncScheduler()
        repository = WorkoutRepositoryImpl(
            authRepository = FakeAuthRepository(user),
            exerciseLogDao = database.exerciseLogDao(),
            workoutSetDao = database.workoutSetDao(),
            exerciseDao = database.exerciseDao(),
            workoutLogDao = database.workoutLogDao(),
            workoutTemplateDao = database.workoutTemplateDao(),
            templateExerciseDao = database.templateExerciseDao(),
            activeSessionDao = database.activeSessionDao(),
            database = database,
            syncScheduler = scheduler
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun finishWorkoutCommitsCompletePendingAggregateBeforeDeletingSession() = runBlocking {
        val template = sampleTemplate()
        val session = repository.startWorkout(template)
        val completed = session.copy(
            exerciseLogs = session.exerciseLogs.map { log ->
                log.copy(sets = log.sets.map { it.copy(weight = 50f, reps = 8, isCompleted = true) })
            }
        )
        repository.updateActiveSession(completed)

        val result = repository.finishWorkout()

        assertNotNull(result)
        assertEquals(session.id, result?.id)
        assertNull(database.activeSessionDao().getSessionById(session.id))

        val parent = database.workoutLogDao().getWorkoutLogById(session.id)
        assertEquals(SyncStatus.PENDING_SYNC.name, parent?.syncStatus)
        assertEquals(PendingOperation.CREATE.name, parent?.pendingOperation)

        val exerciseRows = database.exerciseLogDao().getExerciseLogsByWorkoutLogId(session.id)
        val setRows = database.workoutSetDao().getSetsByWorkoutLogId(session.id)
        assertEquals(completed.exerciseLogs.size, exerciseRows.size)
        assertEquals(completed.exerciseLogs.sumOf { it.sets.size }, setRows.size)
        assertTrue(exerciseRows.all { it.pendingOperation == PendingOperation.CREATE.name })
        assertTrue(setRows.all { it.pendingOperation == PendingOperation.CREATE.name })
        assertEquals(listOf(user.id), scheduler.enqueuedUsers)
    }

    @Test
    fun templateCreatedThenEditedRemainsQueuedAsCreate() = runBlocking {
        val template = repository.createTemplate(
            userId = user.id,
            name = "Initial",
            exercises = sampleTemplate().exercises
        )

        repository.updateTemplate(template.id, "Edited", template.exercises)

        val entity = database.workoutTemplateDao().getTemplateById(template.id)
        assertEquals("Edited", entity?.name)
        assertEquals(PendingOperation.CREATE.name, entity?.pendingOperation)
        assertEquals(2, scheduler.enqueuedUsers.size)
    }

    private fun sampleTemplate() = WorkoutTemplate(
        id = UUID.randomUUID().toString(),
        userId = user.id,
        name = "Push Day",
        exercises = listOf(
            TemplateExercise(
                exercise = Exercise(
                    id = UUID.randomUUID().toString(),
                    name = "Bench Press",
                    muscleCategory = MuscleCategory.CHEST,
                    description = "Test exercise"
                ),
                sets = 3,
                reps = 8,
                restSeconds = 60
            )
        )
    )
}

private class RecordingSyncScheduler : SyncScheduler {
    val enqueuedUsers = mutableListOf<String>()

    override fun enqueue(userId: String?) {
        if (userId != null) enqueuedUsers += userId
    }

    override fun cancel(userId: String) = Unit
}

private class FakeAuthRepository(private var user: User?) : AuthRepository {
    override suspend fun register(
        name: String,
        email: String,
        password: String,
        birthDate: String?,
        profilePhotoUri: String?
    ) = Result.failure<User>(UnsupportedOperationException())

    override suspend fun login(email: String, password: String) =
        Result.failure<User>(UnsupportedOperationException())

    override suspend fun logout() {
        user = null
    }

    override suspend fun getCurrentUser() = user
    override suspend fun isLoggedIn() = user != null
    override suspend fun updateProfile(user: User) = Result.success(user)
    override suspend fun getSignedAvatarUrl(storagePath: String?) = storagePath
}
