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
import com.diajarkoding.imfit.domain.model.RegisterResult
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.AuthRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import com.diajarkoding.imfit.domain.model.appendExercises
import com.diajarkoding.imfit.domain.model.removeIncompleteExercise
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity
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

    @Test fun observerSeesCommittedRenameWhileSyncRemainsPending() = runBlocking {
        val template = repository.createTemplate(user.id, "Before", emptyList())
        val observed = async(start = CoroutineStart.UNDISPATCHED) {
            repository.observeTemplate(template.id).first { it?.name == "After" }
        }
        repository.updateTemplate(template.id, "After", emptyList())
        assertEquals("After", withTimeout(5000) { observed.await() }?.name)
        assertEquals(SyncStatus.PENDING_SYNC.name, database.workoutTemplateDao().getTemplateById(template.id)?.syncStatus)
    }

    @Test fun sessionExerciseChangesPersistAndFinishedAggregateContainsOnlyRemainingExercises() = runBlocking {
        val template = sampleTemplate()
        val original = repository.startWorkout(template)
        val added = ExerciseLog(Exercise(UUID.randomUUID().toString(), "Added", MuscleCategory.BACK, ""),
            listOf(WorkoutSet(1, 25f, 8, true)))
        repository.mutateActiveSession(original.id) { it.appendExercises(listOf(added)).removeIncompleteExercise(template.exercises.first().exercise.id) }
        assertEquals(listOf(added), repository.getActiveSessionForTimer()?.exerciseLogs)
        assertEquals(listOf(added), requireNotNull(repository.finishWorkout()).exerciseLogs)
        val rows = database.exerciseLogDao().getExerciseLogsByWorkoutLogId(original.id)
        assertEquals(listOf(added.exercise.id), rows.map { it.exerciseId })
        assertTrue(rows.all { it.pendingOperation == PendingOperation.CREATE.name })
        repository.updateActiveSession(original)
        assertNull(database.activeSessionDao().getSessionById(original.id))
    }

    @Test fun asynchronousCancelDeletesPersistedRowAndRejectsLateWrites() = runBlocking {
        val session = repository.startWorkout(sampleTemplate())
        repository.requestCancelSession(session.id)
        assertNull(repository.getActiveSessionForTimer())
        repository.updateActiveSession(session)
        withTimeout(5000) { while (database.activeSessionDao().getSessionById(session.id) != null) delay(20) }
        assertNull(repository.getActiveSessionForTimer())
    }

    @Test fun latestHistoryUsesFinishTimeExcludesDeletedAndOtherUsersAndRefreshesFromRoom() = runBlocking {
        fun row(id: String, start: Long, end: Long, volume: Float) = WorkoutLogEntity(
            id = id, userId = user.id, templateId = null, templateName = id,
            date = start, startTime = start, endTime = end, totalVolume = volume)
        val latestFinished = row("long-session", 1000L, 5000L, 50f)
        val laterStarted = row("later-started", 2000L, 4000L, 10f)
        database.workoutLogDao().insertWorkoutLogs(listOf(latestFinished, laterStarted,
            row("deleted", 3000L, 6000L, 100f).copy(deletedAt = 1L),
            row("other-user", 3000L, 7000L, 200f).copy(userId = "other-user")))
        assertEquals(latestFinished.id, repository.getLastWorkoutLog(user.id)?.id)
        val observed = async(start = CoroutineStart.UNDISPATCHED) {
            repository.observeLastWorkoutLog(user.id).first { it?.id == "new-session" }
        }
        database.workoutLogDao().insertWorkoutLog(row("new-session", 6000L, 8000L, 75f))
        val latest = withTimeout(5000) { observed.await() }
        assertEquals(75f, requireNotNull(latest).totalVolume)
        assertEquals(3, repository.observeWorkoutLogs(user.id).first().size)
        database.workoutLogDao().insertWorkoutLog(row("new-session", 6000L, 8000L, 75f).copy(deletedAt = 2L))
        assertEquals(latestFinished.id, withTimeout(5000) {
            repository.observeLastWorkoutLog(user.id).first { it?.id == latestFinished.id }
        }?.id)
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

    override fun cancelForUser(userId: String) = Unit
}

private class FakeAuthRepository(private var user: User?) : AuthRepository {
    override suspend fun register(
        name: String,
        email: String,
        password: String,
        birthDate: String?,
        profilePhotoUri: String?
    ): Result<RegisterResult> = Result.failure(UnsupportedOperationException())

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
