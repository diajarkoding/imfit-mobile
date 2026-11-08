package com.diajarkoding.imfit.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.sync.SyncScheduler
import com.diajarkoding.imfit.domain.model.*
import com.diajarkoding.imfit.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** T26/T33/T34 on a real Room database: start snapshot, atomic completion + trigger, cold recovery, no tick writes. */
@RunWith(AndroidJUnit4::class)
class ActiveSessionSemiOfflineTest {
    private lateinit var database: IMFITDatabase
    private lateinit var repository: WorkoutRepositoryImpl
    private val user = User(UUID.randomUUID().toString(), "Test User", "test@imfit.com")
    private val boot = 7

    private fun newRepository() = WorkoutRepositoryImpl(
        authRepository = SemiOfflineAuthRepository(user), exerciseLogDao = database.exerciseLogDao(),
        workoutSetDao = database.workoutSetDao(), workoutLogDao = database.workoutLogDao(),
        activeSessionDao = database.activeSessionDao(), database = database, syncScheduler = SemiOfflineSyncScheduler())

    @Before fun setUp() {
        ActiveSessionV3Gate.enabled = true
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), IMFITDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = newRepository()
    }
    @After fun tearDown() { ActiveSessionV3Gate.enabled = false; database.close() }

    private val plank = Exercise("plank", "Plank", MuscleCategory.CORE, "d",
        defaultTrackingType = TrackingType.DURATION, modality = ExerciseModality.ISOMETRIC)

    private fun routine(rests: List<Int> = listOf(90, 0)) = WorkoutTemplate(
        id = "routine-1", userId = user.id, name = "Core", revision = 7, measurementVersion = 3,
        exercises = listOf(TemplateExercise(plank, trackingType = TrackingType.DURATION, measurementVersion = 3,
            setTargets = rests.mapIndexed { i, r -> SetTarget("row-${i + 1}", i + 1, durationSeconds = 60, restSeconds = r) })))

    @Test fun startSnapshotsTargetsRestRevisionAndStableIdsInRoom() = runBlocking {
        val session = repository.startWorkout(routine())
        assertEquals(3, session.schemaVersion)
        assertEquals(7L, session.templateRevision)
        val log = session.exerciseLogs.single()
        assertEquals(listOf("row-1", "row-2"), log.sets.map { it.targetRowId })
        assertEquals(2, log.sets.map { it.id }.distinct().size)
        assertTrue(log.sets.none { it.isCompleted })
        assertEquals(emptyList<SetTarget>(), log.inputDefaults!!.extraSetTargets)
        assertEquals(listOf(90, 0), log.inputDefaults!!.setTargets!!.map { it.restSeconds })
        // Cold read through a second repository instance returns the same identities.
        val cold = newRepository().getActiveSessionForTimer()!!
        assertEquals(session, cold)
    }

    @Test fun legacyRoutineStillStartsAsV2WithoutRevision() = runBlocking {
        val legacy = WorkoutTemplate("legacy", user.id, "Legacy",
            listOf(TemplateExercise(Exercise("bench", "Bench", MuscleCategory.CHEST, "d"), sets = 2, reps = 8)))
        val session = repository.startWorkout(legacy)
        assertEquals(2, session.schemaVersion)
        assertNull(session.templateRevision)
    }

    @Test fun v3RoutineIsRejectedWhileTheGateIsOff() = runBlocking {
        ActiveSessionV3Gate.enabled = false
        try { repository.startWorkout(routine()); fail("gate must reject v3") } catch (_: IllegalArgumentException) {}
        assertNull(repository.getActiveSessionForTimer())
    }

    @Test fun completionAndPendingRestTriggerCommitTogetherAndSurviveColdRecovery() = runBlocking {
        val session = repository.startWorkout(routine()).let { s ->
            repository.mutateActiveSession(s.id) { it.ensureRuntime(1_000, boot, it.startTime + 1_000) }!!
        }
        val a = session.exerciseLogs[0].sets[0].id
        var result: SetCompletionResult? = null
        repository.mutateActiveSession(session.id) { cur ->
            cur.completeSet("plank", a, 5_000, boot).also { result = it }.let { (it as? SetCompletionResult.Completed)?.session ?: cur }
        }
        val done = result as SetCompletionResult.Completed
        assertEquals(95_000L, done.trigger!!.deadlineElapsedMs)
        val cold = newRepository().getActiveSessionForTimer()!!
        assertTrue(cold.exerciseLogs[0].sets[0].isCompleted)
        assertEquals(done.trigger, cold.pendingRestTrigger) // same row: completed set + trigger are atomic
        assertEquals(session.id, cold.id)
        // Handling the trigger once clears it; a replay is a no-op and never creates a second session or ID.
        repository.mutateActiveSession(session.id) { it.clearRestTrigger(a) }
        repository.mutateActiveSession(session.id) { it.clearRestTrigger(a) }
        assertNull(newRepository().getActiveSessionForTimer()!!.pendingRestTrigger)
    }

    @Test fun restOutcomeIsPersistedOnceAndUndoDiscardsIt() = runBlocking {
        val s = repository.startWorkout(routine())
        val a = s.exerciseLogs[0].sets[0].id
        repository.mutateActiveSession(s.id) { (it.completeSet("plank", a, 1_000, boot) as SetCompletionResult.Completed).session }
        val outcome = RestOutcome(35_000, RestEndReason.SKIPPED)
        repository.mutateActiveSession(s.id) { it.withRestOutcome(a, outcome) }
        repository.mutateActiveSession(s.id) { it.withRestOutcome(a, RestOutcome(90_000, RestEndReason.EXPIRED)) }
        assertEquals(outcome, newRepository().getActiveSessionForTimer()!!.exerciseLogs[0].sets[0].restOutcome)
        repository.mutateActiveSession(s.id) { (it.undoSet("plank", a) as SetUndoResult.Undone).session }
        val after = newRepository().getActiveSessionForTimer()!!.exerciseLogs[0].sets[0]
        assertFalse(after.isCompleted); assertNull(after.restOutcome); assertEquals(60, after.durationSeconds)
    }

    @Test fun pauseResumeAndExecutionTimerPersistAsTransitionsAndRecoverOnSameBoot() = runBlocking {
        val s = repository.startWorkout(routine())
        val a = s.exerciseLogs[0].sets[0].id
        repository.mutateActiveSession(s.id) { it.ensureRuntime(0, boot, it.startTime) }
        repository.mutateActiveSession(s.id) { it.toggleExecutionTimer(a, 0, boot)!! }
        repository.mutateActiveSession(s.id) { it.pauseWorkout(60_000, boot, it.startTime + 60_000) }
        val paused = newRepository().getActiveSessionForTimer()!!
        assertTrue(paused.isPaused)
        assertEquals(a, paused.executionOwnerSetId())
        val resumed = repository.mutateActiveSession(s.id) { it.resumeWorkout(90_000, boot, it.startTime + 90_000) }!!
        val restored = newRepository().getActiveSessionForTimer()!!.reconcileAfterRestore(110_000, boot)
        assertEquals(80_000L, restored.exerciseLogs[0].sets[0].executionStopwatch!!.elapsedMillis(110_000, boot))
        assertEquals(30_000L, resumed.pausedElapsedMs(110_000, boot))
        assertFalse(restored.needsTimerReconciliation)
    }

    @Test fun finishCommitsCompletedOnlyWithRestOutcomeAndDurationsThenBuildsOneStablePayload() = runBlocking {
        val s = repository.startWorkout(routine())
        val a = s.exerciseLogs[0].sets[0].id
        val b = s.exerciseLogs[0].sets[1].id
        repository.mutateActiveSession(s.id) { it.ensureRuntime(0, boot, it.startTime) }
        repository.mutateActiveSession(s.id) { (it.completeSet("plank", a, 1_000, boot) as SetCompletionResult.Completed).session }
        repository.mutateActiveSession(s.id) { it.withRestOutcome(a, RestOutcome(35_000, RestEndReason.SKIPPED)) }
        repository.mutateActiveSession(s.id) { it.freezeForFinish(2_000, boot) } // set b stays a draft

        val log = repository.finishWorkout()!!
        assertEquals(s.id, log.id)
        assertNull(database.activeSessionDao().getSessionById(s.id)) // removed only because the commit succeeded
        val parent = database.workoutLogDao().getWorkoutLogById(s.id)!!
        assertEquals("PENDING_SYNC", parent.syncStatus); assertEquals("CREATE", parent.pendingOperation)
        assertEquals(3, parent.measurementVersion)
        assertNotNull(parent.pausedElapsedMs); assertNotNull(parent.activeElapsedMs)
        assertTrue(parent.pausedElapsedMs!! + parent.activeElapsedMs!! <= parent.endTime - parent.startTime)

        val sets = database.workoutSetDao().getSetsByWorkoutLogId(s.id)
        assertEquals(listOf(a), sets.map { it.id }) // draft b never reaches the final result
        assertEquals(35_000L, sets.single().restElapsedMs); assertEquals("SKIPPED", sets.single().restEndReason)

        // One aggregate payload, deterministic: a retry sends the identical object (same workout id, same content).
        val exercises = database.exerciseLogDao().getExerciseLogsByWorkoutLogId(s.id)
        val first = com.diajarkoding.imfit.data.sync.WorkoutAggregatePayloadCodec.encode(parent, exercises, sets)
        val retry = com.diajarkoding.imfit.data.sync.WorkoutAggregatePayloadCodec.encode(parent, exercises, sets)
        assertEquals(first, retry)
        assertEquals(s.id, first.workout["id"].toString().trim('"'))
        assertEquals(1, first.sets.size)
        val set = first.sets[0] as kotlinx.serialization.json.JsonObject
        assertEquals("35000", set["rest_elapsed_ms"].toString()); assertEquals("\"SKIPPED\"", set["rest_end_reason"].toString())
        assertTrue(first.workout.containsKey("paused_elapsed_ms") && first.workout.containsKey("active_elapsed_ms"))
        assertTrue(b !in sets.map { it.id })
    }

    @Test fun finishWithoutAnyCompletedSetKeepsTheV3SessionAndWritesNoHistory() = runBlocking {
        val s = repository.startWorkout(routine())
        assertNull(repository.finishWorkout())
        assertEquals(s.id, newRepository().getActiveSessionForTimer()!!.id)
        assertNull(database.workoutLogDao().getWorkoutLogById(s.id))
    }

    @Test fun tickDerivationNeverTouchesRoom() = runBlocking {
        val s = repository.startWorkout(routine())
        val a = s.exerciseLogs[0].sets[0].id
        repository.mutateActiveSession(s.id) { it.ensureRuntime(0, boot, it.startTime).toggleExecutionTimer(a, 0, boot)!! }
        val before = database.activeSessionDao().getSessionById(s.id)!!
        repeat(30) { tick ->
            val stored = repository.getActiveSessionForTimer()!!
            stored.withDisplayElapsed(1_000L * (tick + 1), boot).activeElapsedMs(1_000L * (tick + 1), boot)
        }
        val after = database.activeSessionDao().getSessionById(s.id)!!
        assertEquals(before.sessionDataJson, after.sessionDataJson)
        assertEquals(before.updatedAt, after.updatedAt)
    }
}

private class SemiOfflineSyncScheduler : SyncScheduler {
    val enqueuedUsers = mutableListOf<String>()

    override fun enqueue(userId: String?) {
        if (userId != null) enqueuedUsers += userId
    }

    override fun cancelForUser(userId: String) = Unit
}

private class SemiOfflineAuthRepository(private var user: User?) : AuthRepository {
    private val profileFlow = MutableStateFlow(UserProfileState.fromDirectUser(user))
    override val userProfileState: StateFlow<UserProfileState> = profileFlow

    override suspend fun refreshProfile(force: Boolean): Result<User?> {
        profileFlow.value = UserProfileState.fromDirectUser(user)
        return Result.success(user)
    }

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
        profileFlow.value = UserProfileState.unauthenticated()
    }
    override suspend fun getCurrentUser() = user
    override suspend fun isLoggedIn() = user != null
    override suspend fun updateProfile(user: User) = Result.success(user)
    override suspend fun getSignedAvatarUrl(storagePath: String?) = storagePath
}
