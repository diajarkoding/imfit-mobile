package com.diajarkoding.imfit.b1

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diajarkoding.imfit.b0.SuspendProbe
import com.diajarkoding.imfit.b0.SyncSafetyFixture
import com.diajarkoding.imfit.b0.SyncSafetyFixture.Companion.OWNER_A
import com.diajarkoding.imfit.b0.SyncSafetyFixture.Companion.WORKOUT
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Desired S06 acceptance, not EXPECTED_UNSAFE_BEHAVIOR characterization. These assertions must
 * remain red until the real SyncManager acknowledgement is revision-safe. No expected-failure,
 * ignore, or schema workaround is used to turn the Phase B1 gate green.
 */
@RunWith(AndroidJUnit4::class)
class SyncRevisionGateTest {
    private fun scenario(test: suspend SyncSafetyFixture.() -> Unit) = runBlocking<Unit> {
        withTimeout(30_000) {
            val fixture = SyncSafetyFixture.create()
            try { fixture.test() }
            finally { withContext(NonCancellable) { fixture.close() } }
        }
    }

    @Test fun sameMillisecondParentEditMustRemainPendingAfterOldRemoteAcknowledgement() = scenario {
        val before = seedWorkout()
        val acknowledgement = SuspendProbe()
        val reply = remote.script
        remote.script = { request ->
            reply(request).also {
                if (request.url.encodedPath.endsWith("rpc/upsert_workout_aggregate")) acknowledgement.pause()
            }
        }
        coroutineScope {
            val upload = async { stage("syncPendingWorkoutLogs", OWNER_A) }
            acknowledgement.awaitReached()
            db.workoutLogDao().updateWorkoutLog(before.copy(templateName = "New local revision"))
            acknowledgement.release()
            upload.await()
        }
        assertEquals("Old ack must not clear a newer same-ms write", "CREATE",
            db.workoutLogDao().getWorkoutLogById(WORKOUT)!!.pendingOperation)
    }

    @Test fun childOnlyEditMustRemainPendingAfterOldRemoteAcknowledgement() = scenario {
        seedWorkout()
        val acknowledgement = SuspendProbe()
        val reply = remote.script
        remote.script = { request ->
            reply(request).also {
                if (request.url.encodedPath.endsWith("rpc/upsert_workout_aggregate")) acknowledgement.pause()
            }
        }
        coroutineScope {
            val upload = async { stage("syncPendingWorkoutLogs", OWNER_A) }
            acknowledgement.awaitReached()
            val set = db.workoutSetDao().getSetsByWorkoutLogId(WORKOUT).single()
            db.workoutSetDao().updateWorkoutSet(set.copy(weight = 99f))
            acknowledgement.release()
            upload.await()
        }
        assertEquals("Old ack must not mark a newer child as uploaded", "CREATE",
            db.workoutSetDao().getSetsByWorkoutLogId(WORKOUT).single().pendingOperation)
    }

    @Test fun operationAndContentABAMustNotAcceptAcknowledgementOfOldRevision() = scenario {
        val before = seedWorkout()
        val acknowledgement = SuspendProbe()
        val reply = remote.script
        remote.script = { request ->
            reply(request).also {
                if (request.url.encodedPath.endsWith("rpc/upsert_workout_aggregate")) acknowledgement.pause()
            }
        }
        coroutineScope {
            val upload = async { stage("syncPendingWorkoutLogs", OWNER_A) }
            acknowledgement.awaitReached()
            db.withTransaction { db.workoutLogDao().updateWorkoutLog(before.copy(templateName = "Intermediate", pendingOperation = "UPDATE")) }
            db.withTransaction { db.workoutLogDao().updateWorkoutLog(before) }
            acknowledgement.release()
            upload.await()
        }
        assertEquals("Full-state equality is not an exact-revision acknowledgement", "CREATE",
            db.workoutLogDao().getWorkoutLogById(WORKOUT)!!.pendingOperation)
    }
}
