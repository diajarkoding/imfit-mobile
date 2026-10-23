package com.diajarkoding.imfit.b1

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.diajarkoding.imfit.b0.SyncSafetyFixture
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.local.entity.*
import com.diajarkoding.imfit.data.sync.AggregateSnapshotStore
import com.diajarkoding.imfit.data.sync.RemoteSnapshotImporter
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DurableAggregateRevisionTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext.also {
        check(it.packageName in setOf("com.diajarkoding.imfit.demo.modaldiagnostic", "com.diajarkoding.imfit.modaldiagnostic"))
        check(it.applicationContext.javaClass == android.app.Application::class.java)
    }

    private fun scenario(test: suspend (IMFITDatabase, AggregateSnapshotStore) -> Unit) = runBlocking<Unit> {
        withTimeout(30_000) {
            val db = Room.inMemoryDatabaseBuilder(context, IMFITDatabase::class.java)
                .addCallback(IMFITDatabase.REVISION_CALLBACK).build()
            try {
                seed(db)
                test(db, AggregateSnapshotStore(db))
            } finally { db.close() }
        }
    }

    private suspend fun seed(db: IMFITDatabase) {
        db.withTransaction {
            db.workoutLogDao().insertWorkoutLog(WorkoutLogEntity("w", "A", null, "Original", 1, 1, 2, 80f,
                totalSets = 1, totalReps = 8, pendingOperation = "CREATE", createdAt = 100, updatedAt = 100))
            db.exerciseLogDao().insertExerciseLog(ExerciseLogEntity("e", "w", "x", "Bench", "CHEST", 0,
                80f, 1, 8, pendingOperation = "CREATE", inputDefaults = "local provenance"))
            db.workoutSetDao().insertWorkoutSet(WorkoutSetEntity("s", "e", "w", "x", 1, 10f, 8, true,
                pendingOperation = "CREATE"))
            db.workoutTemplateDao().insertTemplate(WorkoutTemplateEntity("t", "A", "Routine", pendingOperation = "CREATE"))
            db.templateExerciseDao().insertTemplateExercise(TemplateExerciseEntity("t", "x", 0, 3, 8, 60,
                pendingOperation = "CREATE"))
        }
    }

    @Test fun unchangedExactRevisionAcknowledgesParentAndChildrenWithoutCreatingNewPending() = scenario { db, store ->
        val snapshot = store.workout("A", "w")!!
        assertTrue(store.acknowledge(snapshot))
        assertNull(store.workout("A", "w"))
        assertTrue(db.workoutLogDao().getPendingLogs("A").isEmpty())
        assertNull(db.exerciseLogDao().getExerciseLogsByWorkoutLogId("w").single().pendingOperation)
        assertNull(db.workoutSetDao().getSetById("s")!!.pendingOperation)
        assertFalse(store.acknowledge(snapshot))
        assertEquals("local provenance", db.exerciseLogDao().getExerciseLogsByWorkoutLogId("w").single().inputDefaults)
    }

    @Test fun templateChildOnlyReorderRemoveAndABARejectOldAck() = scenario { db, store ->
        val old = store.template("A", "t")!!
        val child = old.exercises.single()
        db.templateExerciseDao().updateTemplateExercise(child.copy(orderIndex = 9, reps = 12))
        assertFalse(store.acknowledge(old))
        db.templateExerciseDao().deleteExercisesByTemplate("t")
        db.templateExerciseDao().insertTemplateExercise(child)
        val latest = store.template("A", "t")!!
        assertEquals(old.parent, latest.parent)
        assertEquals(old.exercises, latest.exercises)
        assertTrue(latest.revision > old.revision)
        assertFalse(store.acknowledge(old))
        assertTrue(store.acknowledge(latest))
        assertNull(db.templateExerciseDao().getExercisesForTemplateList("t").single().pendingOperation)
    }

    @Test fun parentReplaceAndDeleteRecreateNeverReuseRevision() = scenario { db, store ->
        val old = store.workout("A", "w")!!
        db.openHelper.writableDatabase.execSQL("PRAGMA recursive_triggers = OFF")
        db.workoutLogDao().insertWorkoutLog(old.parent)
        assertTrue(store.workout("A", "w")!!.revision > old.revision)
        assertFalse(store.acknowledge(old))
        val replaced = store.workout("A", "w")!!
        db.workoutLogDao().hardDeleteLog("w")
        db.workoutLogDao().insertWorkoutLog(old.parent)
        assertFalse(store.acknowledge(replaced))
        assertTrue(store.workout("A", "w")!!.revision > replaced.revision)
    }

    @Test fun childReplaceReparentInvalidatesBothAggregatesWithRecursiveTriggersOff() = scenario { db, store ->
        val a = store.workout("A", "w")!!
        db.workoutLogDao().insertWorkoutLog(a.parent.copy(id = "w2"))
        val b = store.workout("A", "w2")!!
        db.openHelper.writableDatabase.execSQL("PRAGMA recursive_triggers = OFF")
        db.workoutSetDao().insertWorkoutSet(a.sets.single().copy(workoutLogId = "w2"))
        assertFalse(store.acknowledge(a))
        assertFalse(store.acknowledge(b))
        assertTrue(db.aggregateRevisionDao().revision("A", "workout", "w")!! > a.revision)
        assertTrue(db.aggregateRevisionDao().revision("A", "workout", "w2")!! > b.revision)
    }

    @Test fun ownerChangeAndBackInvalidatesOldAcknowledgement() = scenario { db, store ->
        val a = store.workout("A", "w")!!
        db.workoutLogDao().updateWorkoutLog(a.parent.copy(userId = "B"))
        assertFalse(store.acknowledge(a))
        db.workoutLogDao().updateWorkoutLog(a.parent)
        assertFalse(store.acknowledge(a))
    }

    @Test fun overflowRejectsWriteAndPreservesParentChildAndRevision() = scenario { db, store ->
        val old = store.workout("A", "w")!!
        db.openHelper.writableDatabase.execSQL("UPDATE aggregate_revisions SET revision = 9223372036854775807 WHERE aggregate_type = 'clock'")
        try {
            db.workoutSetDao().updateWorkoutSet(old.sets.single().copy(weight = 99f))
            fail("Overflow must abort the entire statement")
        } catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(10f, db.workoutSetDao().getSetById("s")!!.weight)
        assertEquals("CREATE", db.workoutLogDao().getWorkoutLogById("w")!!.pendingOperation)
        assertEquals(old.revision, db.aggregateRevisionDao().revision("A", "workout", "w"))
        assertEquals(Long.MAX_VALUE, db.aggregateRevisionDao().revision("", "clock", ""))
    }

    @Test fun existingOwnerPurgeDoesNotRetainOwnerMetadataOrReuseOldRevision() = scenario { db, store ->
        val old = store.workout("A", "w")!!
        db.deleteUserData("A")
        assertNull(db.aggregateRevisionDao().revision("A", "workout", "w"))
        assertNull(db.aggregateRevisionDao().revision("A", "template", "t"))
        seed(db)
        assertTrue(store.workout("A", "w")!!.revision > old.revision)
        assertFalse(store.acknowledge(old))
    }

    @Test fun ackFailureMidChildClearRollsBackParentFlagsAndAllRevisionChanges() = scenario { db, store ->
        val before = store.workout("A", "w")!!
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_ack BEFORE UPDATE ON workout_sets
            WHEN NEW.pending_operation IS NULL BEGIN SELECT RAISE(ABORT, 'Injected ack failure'); END""")
        try { store.acknowledge(before); fail("Expected injected failure") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(before, store.workout("A", "w"))
    }

    @Test fun cancellationAfterAckInsideTransactionRollsBackEveryFlagAndRevision() = scenario { db, store ->
        val before = store.workout("A", "w")!!
        val acknowledged = CompletableDeferred<Unit>()
        coroutineScope {
            val job = launch {
                db.withTransaction {
                    assertTrue(store.acknowledge(before))
                    acknowledged.complete(Unit)
                    awaitCancellation()
                }
            }
            acknowledged.await()
            job.cancelAndJoin()
        }
        assertEquals(before, store.workout("A", "w"))
    }

    @Test fun importerCannotOverwriteDirtyChildrenWhenParentLooksSynced() = scenario { db, store ->
        val before = store.workout("A", "w")!!
        assertTrue(store.acknowledge(before))
        val synced = db.workoutSetDao().getSetById("s")!!
        db.workoutSetDao().updateWorkoutSet(synced.copy(weight = 99f, pendingOperation = "UPDATE", syncStatus = "PENDING_SYNC"))
        val parent = db.workoutLogDao().getWorkoutLogById("w")!!
        RemoteSnapshotImporter(db).workout(parent.copy(updatedAt = 999), emptyList(), emptyList())
        assertEquals(99f, db.workoutSetDao().getSetById("s")!!.weight)
        assertEquals("UPDATE", db.workoutSetDao().getSetById("s")!!.pendingOperation)
    }

    @Test fun durableIdentitySurvivesDatabaseReopenBeforeOldAck() = runBlocking<Unit> {
        val name = "b1-revision-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, IMFITDatabase::class.java, name)
            .addCallback(IMFITDatabase.REVISION_CALLBACK).build()
        try {
            val first = open()
            val before = try {
                seed(first)
                val old = AggregateSnapshotStore(first).workout("A", "w")!!
                first.workoutLogDao().updateWorkoutLog(old.parent.copy(templateName = "Intermediate"))
                first.workoutLogDao().updateWorkoutLog(old.parent)
                old
            } finally { first.close() }
            val reopened = open()
            try {
                val store = AggregateSnapshotStore(reopened)
                assertFalse(store.acknowledge(before))
                assertTrue(store.acknowledge(store.workout("A", "w")!!))
            } finally { reopened.close() }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun failedRpcAndCancellationPreservePendingOnActualSyncManager() = runBlocking<Unit> {
        val fixture = SyncSafetyFixture.create()
        try {
            fixture.seedWorkout()
            val before = AggregateSnapshotStore(fixture.db).workout(SyncSafetyFixture.OWNER_A, SyncSafetyFixture.WORKOUT)!!
            fixture.remote.script = { com.diajarkoding.imfit.b0.ScriptedSyncRemote.Reply("{}", 500) }
            assertEquals(false, fixture.stage("syncPendingWorkoutLogs", SyncSafetyFixture.OWNER_A))
            assertEquals(before, AggregateSnapshotStore(fixture.db).workout(SyncSafetyFixture.OWNER_A, SyncSafetyFixture.WORKOUT))
            val entered = CompletableDeferred<Unit>()
            fixture.remote.script = { entered.complete(Unit); awaitCancellation() }
            coroutineScope {
                val upload = launch { fixture.stage("syncPendingWorkoutLogs", SyncSafetyFixture.OWNER_A) }
                entered.await()
                upload.cancelAndJoin()
            }
            assertEquals(before, AggregateSnapshotStore(fixture.db).workout(SyncSafetyFixture.OWNER_A, SyncSafetyFixture.WORKOUT))
        } finally { withContext(NonCancellable) { fixture.close() } }
    }
}
