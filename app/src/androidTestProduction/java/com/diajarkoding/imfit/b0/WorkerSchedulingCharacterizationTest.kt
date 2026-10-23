package com.diajarkoding.imfit.b0

import android.content.Context
import androidx.concurrent.futures.CallbackToFutureAdapter
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.diajarkoding.imfit.data.sync.WorkManagerSyncScheduler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Real production scheduler + real WorkManager test DB; worker body is a held test future, no backend. */
@RunWith(AndroidJUnit4::class)
class WorkerSchedulingCharacterizationTest {
    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var scheduler: WorkManagerSyncScheduler
    private val started = Channel<UUID>(Channel.UNLIMITED)
    private val completions = ConcurrentHashMap<UUID, CallbackToFutureAdapter.Completer<ListenableWorker.Result>>()
    private val owner = "b0-owner-${UUID.randomUUID()}"
    private val workName get() = "imfit-sync-$owner"

    @Before fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "com.diajarkoding.imfit.modaldiagnostic")
        check(context.applicationContext.javaClass == android.app.Application::class.java)
        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker {
                check(workerClassName == "com.diajarkoding.imfit.data.sync.SyncWorker")
                return object : ListenableWorker(appContext, workerParameters) {
                    override fun startWork() = CallbackToFutureAdapter.getFuture<ListenableWorker.Result> { completer ->
                        completions[id] = completer
                        started.trySend(id)
                        "B0-held-worker"
                    }
                }
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
        workManager = WorkManager.getInstance(context)
        scheduler = WorkManagerSyncScheduler(context)
    }

    @After fun close() {
        completions.values.forEach { it.set(ListenableWorker.Result.success()) }
        if (::workManager.isInitialized) workManager.cancelAllWork().result.get(10, java.util.concurrent.TimeUnit.SECONDS)
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private suspend fun state(predicate: (List<WorkInfo>) -> Boolean) =
        withTimeout(10_000) { workManager.getWorkInfosForUniqueWorkFlow(workName).first(predicate) }

    @Test fun appendOrReplaceQueuesBurstInsteadOfCoalescingIt() = runBlocking<Unit> {
        repeat(3) { scheduler.enqueue(owner) }
        val queued = state { it.size == 3 && it.count { row -> row.state == WorkInfo.State.BLOCKED } == 2 }
        assertEquals(3, queued.size)
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        repeat(3) {
            val eligible = state { rows -> rows.any { it.state == WorkInfo.State.ENQUEUED } }
                .single { it.state == WorkInfo.State.ENQUEUED }
            driver.setAllConstraintsMet(eligible.id)
            val id = withTimeout(10_000) { started.receive() }
            assertEquals(eligible.id, id)
            completions.getValue(id).set(ListenableWorker.Result.success())
        }
        assertEquals(3, state { rows -> rows.size == 3 && rows.all { it.state == WorkInfo.State.SUCCEEDED } }.size)
        android.util.Log.i("IMFITB0", evidence("B09-scheduler-burst", BaselineClassification.EXPECTED_UNSAFE_BEHAVIOR,
            "enqueues=3;workRequests=3;coalesced=false;workerBody=heldFake;remoteRequests=0"))
    }

    @Test fun enqueueWhilePreviousWorkerIsRunningRetainsSuccessor() = runBlocking<Unit> {
        scheduler.enqueue(owner)
        val first = state { it.size == 1 }.single()
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        driver.setAllConstraintsMet(first.id)
        assertEquals(first.id, withTimeout(10_000) { started.receive() })
        scheduler.enqueue(owner)
        assertEquals(1, state { it.size == 2 && it.any { row -> row.state == WorkInfo.State.BLOCKED } }
            .count { it.state == WorkInfo.State.BLOCKED })
        completions.getValue(first.id).set(ListenableWorker.Result.success())
        val next = state { it.size == 2 && it.any { row -> row.id != first.id && row.state == WorkInfo.State.ENQUEUED } }
            .single { it.id != first.id }
        driver.setAllConstraintsMet(next.id)
        assertEquals(next.id, withTimeout(10_000) { started.receive() })
        completions.getValue(next.id).set(ListenableWorker.Result.success())
        state { it.size == 2 && it.all { row -> row.state == WorkInfo.State.SUCCEEDED } }
        android.util.Log.i("IMFITB0", evidence("B09-successor", BaselineClassification.BASELINE_PASS,
            "enqueueDuringRunningWorkerPreservesSuccessor=true;realPendingDrainAndOsDeath=NOT_TESTED;remoteRequests=0"))
    }
}
