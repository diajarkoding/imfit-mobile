package com.diajarkoding.imfit.data.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.local.entity.*
import com.diajarkoding.imfit.data.local.sync.SyncStatus
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class RemoteSnapshotImporterTest {
    private lateinit var db: IMFITDatabase
    private lateinit var importer: RemoteSnapshotImporter
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), IMFITDatabase::class.java)
            .addCallback(IMFITDatabase.REVISION_CALLBACK).build()
        importer = RemoteSnapshotImporter(db)
    }
    @After fun close() { db.close() }
    private fun parent(time: Long = 1) = WorkoutLogEntity("log", "user", null, "Workout", 1, 1, 2, 80f,
        syncStatus = SyncStatus.SYNCED.name, updatedAt = time)
    private fun exercise() = ExerciseLogEntity("exercise-log", "log", "exercise", "Bench", "CHEST", 0, 80f, 1, 8,
        syncStatus = SyncStatus.SYNCED.name)
    private fun set() = WorkoutSetEntity("set", "exercise-log", "log", "exercise", 1, 10f, 8, true,
        syncStatus = SyncStatus.SYNCED.name)

    @Test fun existingChildrenUpdateAndRemovedChildrenDisappearAtomically() = runBlocking {
        importer.workout(parent(), listOf(exercise()), listOf(set()))
        importer.workout(parent(2).copy(totalVolume = 160f), listOf(exercise().copy(totalVolume = 160f)), listOf(set().copy(weight = 20f)))
        assertEquals(20f, db.workoutSetDao().getSetsByWorkoutLogId("log").single().weight)
        assertEquals(160f, db.workoutLogDao().getWorkoutLogById("log")!!.totalVolume)
        importer.workout(parent(3).copy(deletedAt = 3), emptyList(), emptyList())
        assertTrue(db.exerciseLogDao().getExerciseLogsByWorkoutLogId("log").isEmpty())
        assertTrue(db.workoutSetDao().getSetsByWorkoutLogId("log").isEmpty())
        assertEquals(3L, db.workoutLogDao().getWorkoutLogById("log")!!.deletedAt)
    }
    @Test fun pendingLocalAndNewerLocalAggregatesAreNeverOverwritten() = runBlocking {
        importer.workout(parent(), listOf(exercise()), listOf(set()))
        db.workoutLogDao().updateWorkoutLog(parent().copy(syncStatus = SyncStatus.PENDING_SYNC.name))
        importer.workout(parent(50), listOf(exercise()), listOf(set().copy(weight = 99f)))
        assertEquals(10f, db.workoutSetDao().getSetsByWorkoutLogId("log").single().weight)
        db.workoutLogDao().updateWorkoutLog(parent(100))
        importer.workout(parent(50), emptyList(), emptyList())
        assertEquals(1, db.exerciseLogDao().getExerciseLogsByWorkoutLogId("log").size)
    }
    @Test fun invalidRemoteGraphCannotPartiallyReplaceLocalHistory() = runBlocking {
        importer.workout(parent(), listOf(exercise()), listOf(set()))
        try { importer.workout(parent(2), listOf(exercise()), listOf(set().copy(exerciseLogId = "wrong"))); fail() }
        catch (_: IllegalArgumentException) { }
        assertEquals(1L, db.workoutLogDao().getWorkoutLogById("log")!!.updatedAt)
        assertEquals("set", db.workoutSetDao().getSetsByWorkoutLogId("log").single().id)
    }
    @Test fun templateReplaceRespectsPendingEditsAndOwner() = runBlocking {
        val template = WorkoutTemplateEntity("template", "user", "Before", syncStatus = SyncStatus.SYNCED.name, updatedAt = 1)
        val child = TemplateExerciseEntity("template", "exercise", 0, 3, 8, 60, syncStatus = SyncStatus.SYNCED.name)
        importer.template(template, listOf(child))
        importer.template(template.copy(name = "After", updatedAt = 2), listOf(child.copy(reps = 12)))
        assertEquals(12, db.templateExerciseDao().getExercisesForTemplateList("template").single().reps)
        db.workoutTemplateDao().updateTemplate(template.copy(name = "Local", syncStatus = SyncStatus.PENDING_SYNC.name))
        importer.template(template.copy(name = "Remote", updatedAt = 3), emptyList())
        assertEquals("Local", db.workoutTemplateDao().getTemplateById("template")!!.name)
        assertEquals(1, db.templateExerciseDao().getExercisesForTemplateList("template").size)
    }
}
