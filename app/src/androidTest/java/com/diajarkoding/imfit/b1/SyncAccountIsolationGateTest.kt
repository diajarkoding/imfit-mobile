package com.diajarkoding.imfit.b1

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diajarkoding.imfit.b0.SuspendProbe
import com.diajarkoding.imfit.b0.SyncSafetyFixture
import com.diajarkoding.imfit.b0.SyncSafetyFixture.Companion.OWNER_A
import com.diajarkoding.imfit.b0.SyncSafetyFixture.Companion.OWNER_B
import com.diajarkoding.imfit.b0.SyncSafetyFixture.Companion.WORKOUT
import kotlinx.coroutines.*
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Desired production S03 gate. Do not normalize a failure as expected unsafe acceptance. */
@RunWith(AndroidJUnit4::class)
class SyncAccountIsolationGateTest {
    @Test fun oldUploadMustNotAcknowledgeAfterSdkAccountABATransition() = runBlocking<Unit> {
        withTimeout(30_000) {
            val fixture = SyncSafetyFixture.create()
            try {
                fixture.seedWorkout()
                val acknowledgement = SuspendProbe()
                val reply = fixture.remote.script
                fixture.remote.script = { request ->
                    reply(request).also {
                        if (request.url.encodedPath.endsWith("rpc/upsert_workout_aggregate")) acknowledgement.pause()
                    }
                }
                coroutineScope {
                    val upload = async { fixture.stage("syncPendingWorkoutLogs", OWNER_A) }
                    acknowledgement.awaitReached()
                    fixture.owner(OWNER_B)
                    fixture.owner(OWNER_A)
                    acknowledgement.release()
                    upload.await()
                }
                assertEquals("An unchanged local revision does not authorize an old SDK epoch", "CREATE",
                    fixture.db.workoutLogDao().getWorkoutLogById(WORKOUT)!!.pendingOperation)
            } finally { withContext(NonCancellable) { fixture.close() } }
        }
    }
}
