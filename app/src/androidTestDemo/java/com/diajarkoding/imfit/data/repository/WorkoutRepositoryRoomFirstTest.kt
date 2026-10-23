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
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.appendExercises
import com.diajarkoding.imfit.domain.model.removeIncompleteExercise
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            .addCallback(IMFITDatabase.REVISION_CALLBACK)
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

    @Test fun zeroCompletedSetsDoesNotWriteHistoryOrDestroyTheActiveSession() = runBlocking {
        val session = repository.startWorkout(sampleTemplate())
        assertEquals(0f, session.totalVolume)
        assertNull(repository.finishWorkout())
        assertEquals(session.id, repository.getActiveSessionForTimer()?.id)
        assertTrue(repository.getWorkoutLogs(user.id).isEmpty())
        assertTrue(scheduler.enqueuedUsers.isEmpty())
    }

    @Test fun cleanEditorReceivesUpdatesButRemoteRefreshCannotOverwriteAnUnsavedDraft() = runBlocking {
        val sample = sampleTemplate()
        sample.exercises.forEach { item -> database.exerciseDao().insertExercise(
            com.diajarkoding.imfit.data.local.entity.ExerciseEntity(item.id, item.name, item.muscleCategory.ordinal + 1, item.description)) }
        val template = repository.createTemplate(user.id, "Initial", sample.exercises)
        val catalog = ExerciseRepositoryImpl(database.exerciseDao(), object : com.diajarkoding.imfit.data.sync.SyncRunner {
            override suspend fun run(expectedUserId: String?) = com.diajarkoding.imfit.data.sync.SyncRunResult.SUCCESS
        })
        val vm = withContext(Dispatchers.Main) { com.diajarkoding.imfit.presentation.ui.workout.EditWorkoutViewModel(
            repository, catalog, androidx.lifecycle.SavedStateHandle()).also { it.initialize(template.id) } }
        try {
            withTimeout(5000) { vm.state.first { it.hasLoaded } }
            repository.updateTemplate(template.id, "Remote clean", template.exercises)
            assertEquals("Remote clean", withTimeout(5000) { vm.state.first { it.workoutName == "Remote clean" } }.workoutName)
            withContext(Dispatchers.Main) { vm.updateWorkoutName("Unsaved local draft") }
            repository.updateTemplate(template.id, "Another remote", emptyList())
            // Let the source observer process the revision without relying on a fixed sleep.
            withTimeout(5000) { repository.observeTemplate(template.id).first { it?.name == "Another remote" } }
            withContext(Dispatchers.Main) {
                assertEquals("Unsaved local draft", vm.state.value.workoutName)
                assertEquals(template.exercises, vm.state.value.exercises)
            }
        } finally { withContext(Dispatchers.Main) { vm.viewModelScope.cancel() } }
    }

    @Test fun finishEliminatesZeroCompletedExercisesAndUnfinishedSetsFromEveryLocalPayload() = runBlocking {
        val base = sampleTemplate()
        val untouched = base.exercises.first().copy(exercise = base.exercises.first().exercise.copy(id = "untouched"))
        val session = repository.startWorkout(base.copy(exercises = base.exercises + untouched))
        repository.mutateActiveSession(session.id) { current -> current.copy(exerciseLogs = current.exerciseLogs.mapIndexed { index, log ->
            if (index == 0) log.copy(sets = listOf(WorkoutSet(1, 40f, 10, true), WorkoutSet(2, 999f, 99, false)))
            else log.copy(sets = log.sets.map { it.copy(weight = 999f) })
        }) }
        val result = requireNotNull(repository.finishWorkout())
        assertEquals(400f, result.totalVolume)
        assertEquals(1, result.exerciseLogs.size)
        assertEquals(1, result.exerciseLogs.single().sets.size)
        val parent = requireNotNull(database.workoutLogDao().getWorkoutLogById(session.id))
        assertEquals(400f, parent.totalVolume); assertEquals(1, parent.totalSets); assertEquals(10, parent.totalReps)
        assertEquals(1, database.exerciseLogDao().getExerciseLogsByWorkoutLogId(session.id).size)
        assertTrue(database.workoutSetDao().getSetsByWorkoutLogId(session.id).all { it.isCompleted })
        assertEquals(1, database.workoutSetDao().getSetsByWorkoutLogId(session.id).size)
        database.exerciseDao().insertExercise(com.diajarkoding.imfit.data.local.entity.ExerciseEntity(
            base.exercises.first().id, "Renamed master", MuscleCategory.BACK.ordinal + 1, "Changed"))
        val history = requireNotNull(repository.getWorkoutLogById(session.id)).exerciseLogs.single().exercise
        assertEquals("Bench Press", history.name)
        assertEquals(MuscleCategory.CHEST, history.muscleCategory)
    }

    @Test fun prefillSurvivesColdRepositoryAndRenameButChangedExerciseTargetsUseDefaults() = runBlocking {
        val original = sampleTemplate()
        val other = original.exercises.single().copy(exercise = original.exercises.single().exercise.copy(id = "other-exercise"))
        val template = original.copy(exercises = original.exercises + other)
        val first = repository.startWorkout(template)
        repository.mutateActiveSession(first.id) { current -> current.copy(exerciseLogs = current.exerciseLogs.map { log ->
            log.copy(sets = log.sets.map { it.copy(weight = 40f + it.setNumber, reps = 5 + it.setNumber, isCompleted = true) })
        }) }
        repository.finishWorkout()
        repository = WorkoutRepositoryImpl(FakeAuthRepository(user), database.exerciseLogDao(), database.workoutSetDao(),
            database.exerciseDao(), database.workoutLogDao(), database.workoutTemplateDao(), database.templateExerciseDao(),
            database.activeSessionDao(), database, scheduler)
        val renamed = repository.startWorkout(template.copy(name = "Renamed"))
        assertEquals(41f, renamed.exerciseLogs.first().sets.first().weight)
        assertEquals(6, renamed.exerciseLogs.first().sets.first().reps)
        assertTrue(renamed.exerciseLogs.flatMap { it.sets }.none { it.isCompleted })
        assertEquals(0f, renamed.totalVolume)
        assertTrue(repository.getPrefillForExercise(template.exercises.first().id, "another-user",
            com.diajarkoding.imfit.domain.model.ExerciseInputDefaults(3, 8)).isEmpty())
        assertEquals(renamed.exerciseLogs, repository.getActiveSessionForTimer()?.exerciseLogs)
        repository.cancelWorkout()
        val changed = repository.startWorkout(template.copy(exercises = listOf(template.exercises.first().copy(sets = 4, reps = 12), other)))
        assertEquals(4, changed.exerciseLogs.first().sets.size)
        assertTrue(changed.exerciseLogs.first().sets.all { it.weight == 0f && it.reps == 12 })
        assertEquals(41f, changed.exerciseLogs.last().sets.first().weight)
        val row = database.exerciseLogDao().getExerciseLogsByWorkoutLogId(first.id).first()
        database.exerciseLogDao().updateExerciseLog(row.copy(inputDefaults = null))
        repository.cancelWorkout()
        val legacy = repository.startWorkout(template)
        assertTrue(legacy.exerciseLogs.first().sets.all { it.weight == 0f && it.reps == original.exercises.first().reps })
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
        assertFalse(repository.isActiveSessionForTimer(user.id, session.id))
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

    @Test
    fun timerIdentityChecksOwnerAndLatestRoomSessionNotCachedSession() = runBlocking {
        val session = repository.startWorkout(sampleTemplate())
        assertTrue(repository.isActiveSessionForTimer(user.id, session.id))
        assertFalse(repository.isActiveSessionForTimer("another-owner", session.id))
        assertFalse(repository.isActiveSessionForTimer(user.id, "another-session"))
        val replacement = repository.startWorkout(sampleTemplate())
        assertFalse(repository.isActiveSessionForTimer(user.id, session.id))
        assertTrue(repository.isActiveSessionForTimer(user.id, replacement.id))
        database.activeSessionDao().deleteSessionById(replacement.id)
        assertFalse(repository.isActiveSessionForTimer(user.id, replacement.id))
    }

    @Test
    fun timerIdentityDoesNotDecodeFullSessionJson() = runBlocking {
        val session = repository.startWorkout(sampleTemplate())
        val entity = requireNotNull(database.activeSessionDao().getSessionById(session.id))
        database.activeSessionDao().updateSession(entity.copy(sessionDataJson = "invalid-json"))
        assertTrue(repository.isActiveSessionForTimer(user.id, session.id))
        // Rendering data is broken, but local identity validation still permits timer expiry.
        try {
            repository.getActiveSessionForTimer()
            org.junit.Assert.fail("Full rendering recovery must still report corrupt JSON")
        } catch (_: kotlinx.serialization.SerializationException) { }
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
