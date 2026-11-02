package com.diajarkoding.imfit.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.local.entity.ActiveSessionEntity
import com.diajarkoding.imfit.data.sync.SyncScheduler
import com.diajarkoding.imfit.domain.model.*
import com.diajarkoding.imfit.domain.repository.AuthRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy

/** Actual Room + production repository path, isolated from accounts/network and the application DB. */
@RunWith(AndroidJUnit4::class)
class MultimodalityPersistenceGateTest {
    private lateinit var db: IMFITDatabase
    private val user = User("phase5-local-owner", "Release gate", "gate@example.invalid")
    private val auth = Proxy.newProxyInstance(AuthRepository::class.java.classLoader,
        arrayOf(AuthRepository::class.java)) { _, method, _ ->
        when (method.name) {
            "getLocalUserId", "peekLocalUserId" -> user.id
            "getUserProfileState" -> MutableStateFlow(UserProfileState.fromDirectUser(user))
            else -> error("Unexpected auth call: ${method.name}")
        }
    } as AuthRepository
    private val scheduler = object : SyncScheduler {
        override fun enqueue(userId: String?) = Unit
        override fun cancelForUser(userId: String) = Unit
    }

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            IMFITDatabase::class.java).allowMainThreadQueries().build()
    }
    @After fun tearDown() { db.close() }

    private fun repository() = WorkoutRepositoryImpl(auth, db.exerciseLogDao(), db.workoutSetDao(),
        db.workoutLogDao(), db.activeSessionDao(), db, scheduler)

    @Test fun sixModeMeasurementsAndSnapshotModesSurviveColdRepositoryRead() = runBlocking {
        val types = TrackingType.entries
        val exercises = types.map { type ->
            TemplateExercise(Exercise("gate-${type.name}", type.name, MuscleCategory.CHEST, "",
                defaultTrackingType = type), sets = 1, reps = if (type in listOf(
                    TrackingType.DURATION, TrackingType.DISTANCE_DURATION)) null else 10,
                trackingType = type,
                targetDurationSeconds = if (type in listOf(TrackingType.DURATION, TrackingType.DISTANCE_DURATION)) 45 else null,
                targetDistanceMeters = if (type == TrackingType.DISTANCE_DURATION) 1000 else null)
        }
        val repo = repository()
        val session = repo.startWorkout(WorkoutTemplate("gate-template", user.id, "Mixed", exercises))
        val expected = session.copy(exerciseLogs = session.exerciseLogs.map { log ->
            log.copy(sets = listOf(when (log.trackingType) {
                TrackingType.WEIGHT_REPS -> WorkoutSet(1, 40f, 10, true)
                TrackingType.BODYWEIGHT_REPS -> WorkoutSet(1, reps = 8, additionalWeight = 10f, isCompleted = true)
                TrackingType.ASSISTED_REPS -> WorkoutSet(1, reps = 8, assistanceWeight = 20f, isCompleted = true)
                TrackingType.REPS_ONLY -> WorkoutSet(1, reps = 30, isCompleted = true)
                TrackingType.DURATION -> WorkoutSet(1, durationSeconds = 90, isCompleted = true)
                TrackingType.DISTANCE_DURATION -> WorkoutSet(1, durationSeconds = 1800, distanceMeters = 5000, isCompleted = true)
            }))
        })
        repo.updateActiveSession(expected)
        db.exerciseDao().insertExercises(exercises.map { row ->
            com.diajarkoding.imfit.data.local.entity.ExerciseEntity(row.id, "Changed catalog", 1, "")
        })
        val restored = requireNotNull(repository().getActiveSessionForTimer())
        assertEquals(session.id, restored.id)
        val mismatches = expected.exerciseLogs.zip(restored.exerciseLogs).mapNotNull { (before, after) ->
            if (before.trackingType == after.trackingType && before.sets == after.sets) null
            else "${before.trackingType}: restored mode=${after.trackingType}, sets=${after.sets}"
        }
        assertEquals("Room recovery must preserve every typed measurement and frozen mode", emptyList<String>(), mismatches)
        assertEquals(expected.exerciseLogs.map { it.exercise }, restored.exerciseLogs.map { it.exercise })
        val finished = requireNotNull(repository().finishWorkout())
        val history = requireNotNull(repository().getWorkoutLogById(finished.id))
        assertEquals(expected.exerciseLogs.map { it.trackingType }, history.exerciseLogs.map { it.trackingType })
        assertEquals(expected.exerciseLogs.map { it.sets }, history.exerciseLogs.map { it.sets })
        val next = repository().startWorkout(WorkoutTemplate("gate-template", user.id, "Mixed", exercises))
        assertEquals(expected.exerciseLogs.map { it.sets.single().measurement },
            next.exerciseLogs.map { it.sets.single().measurement })
        assertTrue(next.exerciseLogs.all { !it.sets.single().isCompleted })
        val changedMode = exercises.first().inputDefaults().copy(trackingType = TrackingType.DURATION.name)
        assertTrue(repository().getPrefillForExercise(exercises.first().id, user.id, changedMode).isEmpty())
    }

    @Test fun durationFinishPersistsActualTimeInsteadOfDroppingItBeforeHistoryCommit() = runBlocking {
        val repo = repository()
        val plank = Exercise("ex_core_1", "Plank", MuscleCategory.CORE, "", defaultTrackingType = TrackingType.DURATION)
        val session = repo.startWorkout(WorkoutTemplate("plank-template", user.id, "Plank",
            listOf(TemplateExercise(plank, sets = 1, reps = null, targetDurationSeconds = 45))))
        repo.updateActiveSession(session.copy(exerciseLogs = session.exerciseLogs.map {
            it.copy(sets = listOf(WorkoutSet(1, durationSeconds = 90, isCompleted = true)))
        }))
        val finished = requireNotNull(repo.finishWorkout())
        val stored = db.workoutSetDao().getSetsByWorkoutLogId(finished.id).single()
        assertEquals("Actual plank duration must reach history", 90, stored.durationSeconds)
    }

    @Test fun legacySessionCanFinishExactlyOnceWithItsOriginalIdentity() = runBlocking {
        seedLegacySession()
        val repo = repository()
        val active = requireNotNull(repo.getActiveSessionForTimer())
        assertEquals("legacy-session", active.id)
        assertEquals(WorkoutSet(1, 40f, 10, true), active.exerciseLogs.single().sets.single())
        val finished = requireNotNull(repo.finishWorkout())
        assertEquals("legacy-session", finished.id)
        assertEquals(400f, finished.totalVolume)
        assertNull(repo.finishWorkout())
        assertEquals(1, db.workoutLogDao().getPendingLogs(user.id).size)
        assertEquals("PENDING_SYNC", db.workoutLogDao().getWorkoutLogById(finished.id)?.syncStatus)
    }

    @Test fun finishingLegacySessionDoesNotUpgradeUnverifiedHistoryQuality() = runBlocking {
        seedLegacySession()
        val finished = requireNotNull(repository().finishWorkout())
        val history = requireNotNull(db.workoutLogDao().getWorkoutLogById(finished.id))
        assertEquals("LEGACY_UNVERIFIED", history.dataQuality)
        assertEquals("LEGACY_UNVERIFIED", db.exerciseLogDao()
            .getExerciseLogsByWorkoutLogId(finished.id).single().metricQuality)
    }

    @Test fun persistedStopwatchAnchorSurvivesRepositoryRestartAndFreezesSafelyAfterReboot() = runBlocking {
        val repo = repository()
        val plank = Exercise("ex_core_1", "Plank", MuscleCategory.CORE, "", defaultTrackingType = TrackingType.DURATION)
        val session = repo.startWorkout(WorkoutTemplate("timer-template", user.id, "Plank",
            listOf(TemplateExercise(plank, sets = 1, reps = null, targetDurationSeconds = 45))))
        repo.updateActiveSession(session.copy(exerciseLogs = session.exerciseLogs.map { log ->
            log.copy(sets = listOf(WorkoutSet(1).toggleExecutionStopwatch(1000, 7)))
        }))
        val cold = repository()
        val recovered = requireNotNull(cold.getActiveSessionForTimer())
        val running = recovered.exerciseLogs.single().sets.single()
        assertTrue(running.executionStopwatch!!.isRunning)
        assertEquals(5000L, running.executionStopwatch!!.elapsedMillis(6000, 7))
        val checkpoint = running.checkpointExecutionStopwatch(6000, 7)
        cold.updateActiveSession(recovered.copy(exerciseLogs = recovered.exerciseLogs.map { it.copy(sets = listOf(checkpoint)) }))
        val afterBoot = requireNotNull(repository().getActiveSessionForTimer()).exerciseLogs.single().sets.single()
            .checkpointExecutionStopwatch(100, 8)
        assertEquals(5, afterBoot.durationSeconds)
        assertFalse(afterBoot.executionStopwatch!!.isRunning)
        assertTrue(afterBoot.executionStopwatch!!.recoveryRequired)
    }

    @Test fun remoteHistoryImportPreservesTypedValuesAndQualityWhenReadBackFromRoom() = runBlocking {
        val remote = com.diajarkoding.imfit.data.remote.dto.WorkoutLogDto(
            id = "remote-mixed", userId = user.id, templateName = "Remote mixed",
            date = "2026-11-01", startTime = "2026-11-01T10:00:00Z", endTime = "2026-11-01T10:30:00Z",
            totalSets = 3, totalReps = 16, totalDurationSeconds = 1800, totalDistanceMeters = 5000,
            additionalVolume = 80.0, dataQuality = "VERIFIED",
            exerciseLogs = listOf(
                com.diajarkoding.imfit.data.remote.dto.ExerciseLogDto(
                    id = "cardio-log", exerciseId = "ex_cardio_1", exerciseName = "Treadmill", muscleCategory = "CARDIO",
                    trackingType = "DISTANCE_DURATION", modality = "CARDIO", metricQuality = "VERIFIED",
                    totalSets = 1, totalDurationSeconds = 1800, totalDistanceMeters = 5000,
                    workoutSets = listOf(com.diajarkoding.imfit.data.remote.dto.WorkoutSetDto(
                        id = "cardio-set", setNumber = 1, isCompleted = true, durationSeconds = 1800, distanceMeters = 5000))),
                com.diajarkoding.imfit.data.remote.dto.ExerciseLogDto(
                    id = "body-log", exerciseId = "ex_chest_4", exerciseName = "Push-Up", muscleCategory = "CHEST", orderIndex = 1,
                    trackingType = "BODYWEIGHT_REPS", modality = "CALISTHENICS", metricQuality = "VERIFIED",
                    totalSets = 1, totalReps = 8, additionalVolume = 80.0,
                    workoutSets = listOf(com.diajarkoding.imfit.data.remote.dto.WorkoutSetDto(
                        id = "body-set", setNumber = 1, reps = 8, isCompleted = true, additionalWeight = 10.0))),
                com.diajarkoding.imfit.data.remote.dto.ExerciseLogDto(
                    id = "assist-log", exerciseId = "ex_back_17", exerciseName = "Assisted Pull-Up", muscleCategory = "BACK", orderIndex = 2,
                    trackingType = "ASSISTED_REPS", modality = "STRENGTH", metricQuality = "VERIFIED",
                    totalSets = 1, totalReps = 8,
                    workoutSets = listOf(com.diajarkoding.imfit.data.remote.dto.WorkoutSetDto(
                        id = "assist-set", setNumber = 1, reps = 8, isCompleted = true, assistanceWeight = 20.0)))
            ))
        val repo = WorkoutRepositoryImpl(auth, db.exerciseLogDao(), db.workoutSetDao(),
            db.workoutLogDao(), db.activeSessionDao(), db, scheduler,
            remoteDataSource = object : com.diajarkoding.imfit.data.remote.WorkoutRemoteDataSource {
                override suspend fun fetchLatestCompletedWorkoutLog(userId: String) = remote
            })
        repo.fetchRemoteLastWorkoutLog(user.id)
        val history = requireNotNull(repository().getWorkoutLogById(remote.id))
        assertEquals("VERIFIED", history.dataQuality)
        assertEquals(1800, history.totalDurationSeconds)
        assertEquals(5000, history.totalDistanceMeters)
        assertEquals(80f, history.additionalVolume)
        val logs = history.exerciseLogs.associateBy { it.exercise.id }
        assertEquals(TrackingType.DISTANCE_DURATION, logs.getValue("ex_cardio_1").trackingType)
        assertEquals(5000, logs.getValue("ex_cardio_1").sets.single().distanceMeters)
        assertEquals(1800, logs.getValue("ex_cardio_1").sets.single().durationSeconds)
        assertEquals(10f, logs.getValue("ex_chest_4").sets.single().additionalWeight)
        assertEquals(20f, logs.getValue("ex_back_17").sets.single().assistanceWeight)
        assertTrue(history.exerciseLogs.all { it.metricQuality == "VERIFIED" })
    }

    @Test fun runtimeRestOverrideSurvivesColdReadWithoutRewritingRoutineTargets() = runBlocking {
        val repo = repository()
        val exercise = Exercise("rest-fixture", "Press", MuscleCategory.CHEST, "")
        val template = WorkoutTemplate("rest-template", user.id, "Runtime rest",
            listOf(TemplateExercise(exercise, restSeconds = 120)))
        val session = repo.startWorkout(template)
        repo.updateSessionRestOverride(90)
        val cold = repository()
        assertEquals(90, cold.getSessionRestOverride())
        assertEquals(session.id, cold.getActiveSessionForTimer()?.id)
        assertEquals(60, cold.getActiveSessionForTimer()?.exerciseLogs?.single()?.restSeconds)
        assertEquals(120, template.exercises.single().restSeconds)
    }

    private suspend fun seedLegacySession() {
        db.activeSessionDao().insertSession(ActiveSessionEntity(
            id = "legacy-session", userId = user.id, templateId = "legacy-template",
            templateName = "Legacy", startTime = 1000,
            sessionDataJson = """{"id":"legacy-session","templateId":"legacy-template","templateName":"Legacy","startTime":1000,"exerciseLogs":[{"exercise":{"id":"ex_chest_1","name":"Bench","muscleCategory":"CHEST","description":""},"sets":[{"setNumber":1,"weight":40.0,"reps":10,"isCompleted":true}],"restSeconds":60}]}"""))
    }
}
