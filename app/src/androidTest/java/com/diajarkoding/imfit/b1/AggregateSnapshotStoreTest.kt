package com.diajarkoding.imfit.b1

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.diajarkoding.imfit.b0.BlockingProbe
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.local.entity.ExerciseLogEntity
import com.diajarkoding.imfit.data.local.entity.TemplateExerciseEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutLogEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutSetEntity
import com.diajarkoding.imfit.data.local.entity.WorkoutTemplateEntity
import com.diajarkoding.imfit.data.sync.AggregateSnapshotStore
import com.diajarkoding.imfit.data.sync.SessionCommitGate
import com.diajarkoding.imfit.data.sync.SyncSessionChangedException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/** Real Room7 tests, no SDK/network. No production application or normal app data is opened. */
@RunWith(AndroidJUnit4::class)
class AggregateSnapshotStoreTest {
    private class Fixture {
        val context = InstrumentationRegistry.getInstrumentation().targetContext.also {
            check(it.packageName in setOf("com.diajarkoding.imfit.demo.modaldiagnostic", "com.diajarkoding.imfit.modaldiagnostic"))
            check(it.applicationContext.javaClass == android.app.Application::class.java)
        }
        val afterParentRead = AtomicReference<BlockingProbe?>()
        val db = Room.inMemoryDatabaseBuilder(context, IMFITDatabase::class.java)
            .setQueryCallback({ sql, _ ->
                if (sql.startsWith("SELECT * FROM workout_logs WHERE id", ignoreCase = true)) {
                    afterParentRead.getAndSet(null)?.pause()
                }
            }, Executor { it.run() }).build()
        val store = AggregateSnapshotStore(db)

        suspend fun seedWorkout() {
            db.withTransaction {
                db.workoutLogDao().insertWorkoutLog(WorkoutLogEntity("workout", "A", null, "Old", 1, 1, 2, 80f,
                    totalSets = 1, totalReps = 8, pendingOperation = "CREATE", createdAt = 100, updatedAt = 100))
                db.exerciseLogDao().insertExerciseLog(ExerciseLogEntity("log", "workout", "exercise", "Fixture", "CHEST", 0,
                    80f, 1, 8, pendingOperation = "CREATE", createdAt = 100, updatedAt = 100))
                db.workoutSetDao().insertWorkoutSet(WorkoutSetEntity("set", "log", "workout", "exercise", 1, 10f, 8, true,
                    pendingOperation = "CREATE", createdAt = 100, updatedAt = 100))
            }
        }

        fun close() { afterParentRead.getAndSet(null)?.release(); db.close() }
    }

    private fun scenario(test: suspend Fixture.() -> Unit) = runBlocking<Unit> {
        withTimeout(30_000) {
            val fixture = Fixture()
            try { fixture.test() }
            finally { withContext(NonCancellable) { fixture.close() } }
        }
    }

    @Test fun capturesOwnerScopedParentChildrenAndPendingWithoutChangingFlags() = scenario {
        seedWorkout()
        val snapshot = store.workout("A", "workout")!!
        assertEquals("A", snapshot.parent.userId)
        assertEquals("CREATE", snapshot.parent.pendingOperation)
        assertEquals(listOf("log"), snapshot.exercises.map { it.id })
        assertEquals(listOf(10f), snapshot.sets.map { it.weight })
        assertEquals(snapshot, store.workout("A", "workout"))
        assertNull(store.workout("B", "workout"))
        assertNull(store.workout("A", "missing"))
        db.workoutLogDao().markAsSynced("workout")
        assertNull(store.workout("A", "workout"))
    }

    @Test fun concurrentMutationCannotMixOldParentWithNewChildren() = scenario {
        seedWorkout()
        val probe = BlockingProbe()
        afterParentRead.set(probe)
        try {
            coroutineScope {
                val reader = async { store.workout("A", "workout")!! }
                withContext(Dispatchers.IO) { probe.awaitReached() }
                val writer = async(start = CoroutineStart.UNDISPATCHED) {
                    db.withTransaction {
                        val parent = db.workoutLogDao().getWorkoutLogById("workout")!!
                        db.workoutLogDao().updateWorkoutLog(parent.copy(templateName = "New", updatedAt = 200))
                        val set = db.workoutSetDao().getSetById("set")!!
                        db.workoutSetDao().updateWorkoutSet(set.copy(weight = 99f, updatedAt = 200))
                    }
                }
                assertFalse(writer.isCompleted)
                probe.release()
                val before = reader.await()
                writer.await()
                assertEquals("Old", before.parent.templateName)
                assertEquals(10f, before.sets.single().weight)
                val after = store.workout("A", "workout")!!
                assertEquals("New", after.parent.templateName)
                assertEquals(99f, after.sets.single().weight)
            }
        } finally { probe.release() }
    }

    @Test fun captureRejectsMalformedGraphWithoutClearingAnyPendingFlags() = scenario {
        seedWorkout()
        val set = db.workoutSetDao().getSetById("set")!!
        db.workoutSetDao().updateWorkoutSet(set.copy(exerciseLogId = "missing-log"))
        try { store.workout("A", "workout"); fail("Invalid graph captured") }
        catch (_: IllegalStateException) { }
        assertEquals("CREATE", db.workoutLogDao().getWorkoutLogById("workout")!!.pendingOperation)
        assertEquals("CREATE", db.workoutSetDao().getSetById("set")!!.pendingOperation)
    }

    @Test fun unknownPendingOperationIsNotImplicitlySuccessful() = scenario {
        seedWorkout()
        val parent = db.workoutLogDao().getWorkoutLogById("workout")!!
        db.workoutLogDao().updateWorkoutLog(parent.copy(pendingOperation = "UNKNOWN"))
        try { store.workout("A", "workout"); fail("Unknown operation accepted") }
        catch (_: IllegalStateException) { }
        assertEquals("UNKNOWN", db.workoutLogDao().getWorkoutLogById("workout")!!.pendingOperation)
    }

    @Test fun capturesPendingTemplateTombstoneAndDeterministicChildOrder() = scenario {
        db.workoutTemplateDao().insertTemplate(WorkoutTemplateEntity("routine", "A", "Deleted", isDeleted = true,
            pendingOperation = "DELETE", createdAt = 100, updatedAt = 100))
        db.templateExerciseDao().insertTemplateExercises(listOf("z", "a").map {
            TemplateExerciseEntity("routine", it, 0, 3, 8, 60, pendingOperation = "UPDATE", createdAt = 100, updatedAt = 100)
        })
        val snapshot = store.template("A", "routine")!!
        assertTrue(snapshot.parent.isDeleted)
        assertEquals("DELETE", snapshot.parent.pendingOperation)
        assertEquals(listOf("a", "z"), snapshot.exercises.map { it.exerciseId })
        assertNull(store.template("B", "routine"))
        assertEquals(snapshot, store.template("A", "routine"))
    }

    @Test fun sessionFenceHoldsThroughActualRoomTransactionAndRejectsOldPublication() = scenario {
        seedWorkout()
        val gate = SessionCommitGate()
        val old = gate.transition("A")!!
        val probe = BlockingProbe()
        afterParentRead.set(probe)
        try {
            coroutineScope {
                val capture = async { gate.commit(old) { store.workout(old.ownerId, "workout") } }
                withContext(Dispatchers.IO) { probe.awaitReached() }
                val transition = async(start = CoroutineStart.UNDISPATCHED) { gate.transition("B") }
                assertFalse(transition.isCompleted)
                assertSame(old, gate.session.value)
                probe.release()
                assertNotNull(capture.await())
                transition.await()
            }
            try { gate.publish(old) { fail("Old context published") }; fail("Expected rejection") }
            catch (_: SyncSessionChangedException) { }
        } finally { probe.release() }
    }

    @Test fun cancelledFencedRoomWriteRollsBackAndReleasesSessionGate() = scenario {
        seedWorkout()
        val gate = SessionCommitGate()
        val session = gate.transition("A")!!
        val written = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        coroutineScope {
            val writer = launch {
                gate.commit(session) {
                    db.withTransaction {
                        val parent = db.workoutLogDao().getWorkoutLogById("workout")!!
                        db.workoutLogDao().updateWorkoutLog(parent.copy(templateName = "Must roll back"))
                        written.complete(Unit)
                        release.await()
                    }
                }
            }
            written.await()
            writer.cancelAndJoin()
        }
        assertEquals("Old", db.workoutLogDao().getWorkoutLogById("workout")!!.templateName)
        assertEquals("CREATE", db.workoutLogDao().getWorkoutLogById("workout")!!.pendingOperation)
        assertEquals("B", gate.transition("B")!!.ownerId)
    }

    /** BLOCKER proof only, NOT a safe-ack acceptance test. No acknowledgement is performed. */
    @Test fun schema7BlockerFullStateEqualityCannotDetectSameMillisecondOperationABA() = scenario {
        seedWorkout()
        val before = store.workout("A", "workout")!!
        db.withTransaction {
            db.workoutLogDao().updateWorkoutLog(before.parent.copy(templateName = "Intermediate", pendingOperation = "UPDATE"))
            db.workoutSetDao().updateWorkoutSet(before.sets.single().copy(weight = 99f))
        }
        db.withTransaction {
            db.workoutLogDao().updateWorkoutLog(before.parent)
            db.workoutSetDao().updateWorkoutSet(before.sets.single())
        }
        val after = store.workout("A", "workout")!!
        assertEquals(before, after)
        assertEquals(100L, after.parent.updatedAt)
        assertEquals("CREATE", after.parent.pendingOperation)
        // No durable mutation identity exists to reject an ack of 'before' after these writes.
        assertEquals(7, db.openHelper.readableDatabase.version)
    }
}
