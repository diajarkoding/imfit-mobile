package com.diajarkoding.imfit.b0

import android.util.Log
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import com.diajarkoding.imfit.b0.BaselineClassification.*
import com.diajarkoding.imfit.b0.SyncSafetyFixture.Companion.OWNER_A
import com.diajarkoding.imfit.b0.SyncSafetyFixture.Companion.OWNER_B
import com.diajarkoding.imfit.b0.SyncSafetyFixture.Companion.TEMPLATE
import com.diajarkoding.imfit.b0.SyncSafetyFixture.Companion.WORKOUT
import com.diajarkoding.imfit.core.network.NetworkMonitor
import com.diajarkoding.imfit.data.local.database.IMFITDatabase
import com.diajarkoding.imfit.data.local.entity.*
import com.diajarkoding.imfit.data.repository.ExerciseRepositoryImpl
import com.diajarkoding.imfit.data.sync.*
import io.github.jan.supabase.auth.auth
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SyncSafetyCharacterizationTest {
    private fun scenario(block: suspend SyncSafetyFixture.() -> Unit) = runBlocking {
        withTimeout(30_000) {
            val fixture = SyncSafetyFixture.create()
            try { fixture.block() }
            finally { withContext(NonCancellable) { fixture.close() } }
        }
    }
    private fun record(id: String, classification: BaselineClassification, facts: String) {
        Log.i("IMFITB0", evidence(id, classification, facts))
    }
    private fun SyncSafetyFixture.requireOnlineForPublicRun() {
        assumeTrue("BLOCKED_ENVIRONMENT: public run reads actual validated connectivity", NetworkMonitor(context).isOnline)
    }
    private fun catalog(offset: Int, count: Int): String = buildJsonArray {
        repeat(count) { index -> add(buildJsonObject {
            put("id", "exercise_${offset + index}"); put("name", "Fixture"); put("muscle_category_id", 1)
        }) }
    }.toString()
    private fun remoteTemplate(name: String = "Remote routine") = """{"id":"$TEMPLATE","user_id":"$OWNER_A","name":"$name","is_deleted":false,"created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-02T00:00:00Z"}"""

    @Test fun expectedUnsafeTenConcurrentRunsAreSerializedNotShared() = scenario {
        requireOnlineForPublicRun()
        val probe = SuspendProbe()
        val base = remote.script
        remote.script = { request ->
            if (request.url.encodedPath.endsWith("/exercises") && remote.ledger.count("GET /rest/v1/exercises") == 1) probe.pause()
            base(request)
        }
        coroutineScope {
            val first = async(start = CoroutineStart.UNDISPATCHED) { manager.run(OWNER_A) }
            probe.awaitReached()
            val rest = List(9) { async(start = CoroutineStart.UNDISPATCHED) { manager.run(OWNER_A) } }
            probe.release()
            assertEquals(List(10) { SyncRunResult.SUCCESS }, listOf(first.await()) + rest.awaitAll())
        }
        assertEquals(10, remote.ledger.count("GET /rest/v1/exercises"))
        assertEquals(0, remote.ledger.joined)
        assertEquals(1, remote.ledger.maxConcurrency)
        record("B01", EXPECTED_UNSAFE_BEHAVIOR, "runs=10;catalogRequests=10;joined=0;maxTransportConcurrency=1;serializedDuplicates=true")
    }

    @Test fun expectedUnsafeFullAndMasterEntryPointsOverlapCatalog() = scenario {
        requireOnlineForPublicRun()
        val full = SuspendProbe(); val master = SuspendProbe()
        val base = remote.script
        remote.script = { request ->
            if (request.url.encodedPath.endsWith("/exercises")) {
                if (remote.ledger.count("GET /rest/v1/exercises") == 1) full.pause() else master.pause()
            }
            base(request)
        }
        coroutineScope {
            val a = async { manager.syncAll() } // The exact public target of reconnect, not an OS callback simulation.
            full.awaitReached()
            val b = async { manager.refreshMasterData() }
            master.awaitReached()
            full.release(); master.release(); a.await(); b.await()
        }
        assertEquals(2, remote.ledger.count("GET /rest/v1/exercises"))
        assertEquals(2, remote.ledger.maxConcurrency)
        record("B01-cross-entry", EXPECTED_UNSAFE_BEHAVIOR, "catalogRequests=2;maxTransportConcurrency=2;osReconnectDelivery=NOT_TESTED")
    }

    @Test fun expectedUnsafeInitialSyncDownloadsCatalogAndProfileTwice() = scenario {
        requireOnlineForPublicRun()
        preferences.setInitialSyncCompleted(OWNER_A, false)
        preferences.setLastSyncTimestamp(OWNER_A, 0L)
        assertEquals(SyncRunResult.SUCCESS, manager.run(OWNER_A))
        assertEquals(2, remote.ledger.count("GET /rest/v1/exercises"))
        assertEquals(2, remote.ledger.count("GET /rest/v1/profiles"))
        record("B01-initial", EXPECTED_UNSAFE_BEHAVIOR, "initialCatalogRequests=2;initialProfileRequests=2;singleRun=true")
    }

    @Test fun successfulRepositoryCooldownSkipsRatherThanSharesResults() = scenario {
        requireOnlineForPublicRun()
        val probe = SuspendProbe(); val base = remote.script
        remote.script = { request -> if (request.url.encodedPath.endsWith("/exercises")) probe.pause(); base(request) }
        val repository = ExerciseRepositoryImpl(db.exerciseDao(), manager)
        val results = coroutineScope {
            val first = async(start = CoroutineStart.UNDISPATCHED) { repository.refreshExercises() }
            probe.awaitReached()
            val rest = List(9) { async(start = CoroutineStart.UNDISPATCHED) { repository.refreshExercises() } }
            probe.release(); listOf(first.await()) + rest.awaitAll()
        }
        assertEquals(1, remote.ledger.count("GET /rest/v1/exercises"))
        assertEquals(1, results.count { it }); assertEquals(9, results.count { !it })
        record("B02", BASELINE_PASS, "successCooldownRequests=1;trueResults=1;skippedFalseResults=9;sharedOutcome=false;clockInjection=NOT_TESTED")
    }

    @Test fun expectedUnsafeFailedRefreshHasNoSuccessCooldown() = scenario {
        requireOnlineForPublicRun()
        val repository = ExerciseRepositoryImpl(db.exerciseDao(), manager)
        remote.script = { ScriptedSyncRemote.Reply("""{"message":"fixture failure","code":"FIXTURE"}""", 500) }
        coroutineScope { List(10) { async(start = CoroutineStart.UNDISPATCHED) { repository.refreshExercises() } }.awaitAll() }
        assertEquals(10, remote.ledger.count("GET /rest/v1/exercises"))
        record("B02-failure", EXPECTED_UNSAFE_BEHAVIOR, "failedCatalogRequests=10;joined=0;failureCooldown=false")
    }

    @Test fun expectedUnsafeIdentityCanChangeAfterCheckBeforeRoomTransaction() = scenario {
        remote.script = { ScriptedSyncRemote.Reply(catalog(0, 1)) }
        val probe = transactions.arm()
        coroutineScope {
            val read = async { stage("pullExercises", OWNER_A) }
            withContext(Dispatchers.IO) { probe.awaitReached() }
            owner(OWNER_B, SessionEvent.DEEP_LINK)
            probe.release(); read.await()
        }
        assertEquals(OWNER_B, client.auth.currentUserOrNull()?.id)
        assertNotNull(db.exerciseDao().getExerciseById("exercise_0"))
        record("B03", EXPECTED_UNSAFE_BEHAVIOR, "oldReadCommittedAfterOwnerTransition=true;transactionFence=false;catalogIsGlobal=true")
    }

    @Test fun expectedUnsafeABAIsInvisibleToCurrentUserIdCheck() = scenario {
        remote.script = { ScriptedSyncRemote.Reply(catalog(0, 1)) }
        val capturedEpoch = session.identity.epoch
        val probe = transactions.arm()
        coroutineScope {
            val read = async { stage("pullExercises", OWNER_A) }
            withContext(Dispatchers.IO) { probe.awaitReached() }
            owner(OWNER_B, SessionEvent.LOGIN); owner(OWNER_A, SessionEvent.RESTORE)
            probe.release(); read.await()
        }
        assertTrue(session.identity.epoch > capturedEpoch)
        assertNotNull(db.exerciseDao().getExerciseById("exercise_0"))
        record("B03-ABA", EXPECTED_UNSAFE_BEHAVIOR, "sameOwnerDifferentEpochAccepted=true;oldCatalogCommitted=true;serverSessionRestore=NOT_TESTED")
    }

    @Test fun expectedUnsafeOwnerTransitionInsideRoomTransactionDoesNotAbortCommit() = scenario {
        remote.script = { ScriptedSyncRemote.Reply(catalog(0, 1)) }
        val probe = BlockingProbe(); insideTransaction.set(probe)
        coroutineScope {
            val read = async { stage("pullExercises", OWNER_A) }
            withContext(Dispatchers.IO) { probe.awaitReached() }
            owner(OWNER_B, SessionEvent.LOGIN)
            probe.release(); read.await()
        }
        assertNotNull(db.exerciseDao().getExerciseById("exercise_0"))
        record("B03-inside", EXPECTED_UNSAFE_BEHAVIOR, "transitionInsideRoomWrite=true;oldCatalogCommitNotAborted=true;notProofOfPrivateDataLeak=true")
    }

    @Test fun expectedUnsafeCurrentUserObserverRemainsBoundToOldOwner() = scenario {
        db.userDao().insertUser(UserEntity(OWNER_A, "First", "fixture@example.invalid"))
        db.userDao().insertUser(UserEntity(OWNER_B, "Second", "fixture@example.invalid"))
        val auth = authRepository()
        coroutineScope {
            val emitted = Channel<String>(Channel.UNLIMITED)
            val observer = launch { auth.observeCurrentUser().collect { it?.let { user -> emitted.send(user.id) } } }
            assertEquals(OWNER_A, withTimeout(5_000) { emitted.receive() })
            owner(OWNER_B, SessionEvent.LOGIN)
            db.userDao().insertUser(UserEntity(OWNER_A, "Changed", "fixture@example.invalid"))
            assertEquals(OWNER_A, withTimeout(5_000) { emitted.receive() })
            observer.cancelAndJoin()
        }
        record("B04", EXPECTED_UNSAFE_BEHAVIOR, "roomUserObserverEmitsOldOwnerAfterSdkSwitch=true;actualScreenDelivery=NOT_TESTED")
    }

    @Test fun expectedUnsafeParentAndChildrenCanComeFromDifferentRevisions() = scenario {
        val original = seedWorkout()
        val captured = SuspendProbe()
        afterPendingLogs = { captured.pause() }
        coroutineScope {
            val upload = async { stage("syncPendingWorkoutLogs", OWNER_A) }
            captured.awaitReached()
            db.workoutLogDao().updateWorkoutLog(original.copy(templateName = "New parent", updatedAt = 200))
            db.workoutSetDao().updateWorkoutSet(db.workoutSetDao().getSetsByWorkoutLogId(WORKOUT).single().copy(weight = 99f))
            captured.release(); upload.await()
        }
        val payload = Json.parseToJsonElement(remote.capturedBodies("POST /rest/v1/rpc/upsert_workout_aggregate").single()).jsonObject
        assertEquals("Captured parent", payload.getValue("p_workout").jsonObject.getValue("template_name").jsonPrimitive.content)
        assertEquals(99f, payload.getValue("p_sets").jsonArray.single().jsonObject.getValue("weight").jsonPrimitive.float)
        assertEquals("CREATE", db.workoutLogDao().getWorkoutLogById(WORKOUT)?.pendingOperation)
        record("B05-capture", EXPECTED_UNSAFE_BEHAVIOR, "mixedParentChildRevisionsSent=true;newerTimestampAckProtected=true;rpcRequests=1")
    }

    @Test fun expectedUnsafeSameMillisecondAcknowledgementClearsNewPendingWrite() = scenario {
        val original = seedWorkout()
        val ack = SuspendProbe()
        val base = remote.script
        // Hold the remote acknowledgement BEFORE production acquires the Room transaction.
        // Holding a DAO probe inside that transaction would deadlock the competing test write.
        remote.script = { request ->
            val reply = base(request)
            if (request.url.encodedPath.endsWith("rpc/upsert_workout_aggregate")) ack.pause()
            reply
        }
        coroutineScope {
            val upload = async { stage("syncPendingWorkoutLogs", OWNER_A) }
            ack.awaitReached()
            val clock = ControlledClock(wallMs = original.updatedAt)
            db.workoutLogDao().updateWorkoutLog(original.copy(templateName = "Concurrent edit", updatedAt = clock.wallMs))
            db.workoutSetDao().updateWorkoutSet(db.workoutSetDao().getSetsByWorkoutLogId(WORKOUT).single().copy(weight = 99f))
            ack.release(); upload.await()
        }
        assertNull(db.workoutLogDao().getWorkoutLogById(WORKOUT)?.pendingOperation)
        assertEquals("SYNCED", db.workoutSetDao().getSetsByWorkoutLogId(WORKOUT).single().syncStatus)
        assertEquals(99f, db.workoutSetDao().getSetsByWorkoutLogId(WORKOUT).single().weight)
        record("B05-ack", EXPECTED_UNSAFE_BEHAVIOR, "sameTimestampAndOperationClearedNewPending=true;childWeightStillLocal=true")
    }

    @Test fun expectedUnsafePendingRowsAreDeletedBeforeSdkSignOutFailure() = scenario {
        seedTemplate()
        var deletedBeforeRequest = false
        remote.script = { request ->
            check(request.url.encodedPath == "/auth/v1/logout")
            deletedBeforeRequest = db.workoutTemplateDao().getPendingSyncTemplates(OWNER_A).isEmpty()
            ScriptedSyncRemote.Reply("""{"message":"fixture failure","code":500}""", 500)
        }
        var failed = false
        try { authRepository().logout() } catch (_: Exception) { failed = true }
        assertTrue(deletedBeforeRequest)
        assertTrue(failed)
        assertTrue(db.workoutTemplateDao().getPendingSyncTemplates(OWNER_A).isEmpty())
        record("B06", EXPECTED_UNSAFE_BEHAVIOR, "pendingDeletedBeforeSdkRequest=true;signOutFailed=true;syntheticEmptyTimerOnly=true")
    }

    @Test fun catalogPageThreeFailureDoesNotCommitFirstTwoPages() = scenario {
        db.exerciseDao().insertExercise(ExerciseEntity("cached", "Cached", 1, ""))
        remote.script = { request ->
            val offset = request.url.parameters["offset"]!!.toInt()
            if (offset == 1000) ScriptedSyncRemote.Reply("""{"message":"fixture failure","code":"FIXTURE"}""", 500)
            else ScriptedSyncRemote.Reply(catalog(offset, 500))
        }
        var failed = false
        try { stage("pullExercises", OWNER_A) } catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertEquals(listOf("cached"), db.exerciseDao().getAllActiveExercisesList().map { it.id })
        assertEquals(3, remote.ledger.count("GET /rest/v1/exercises"))
        record("B07-page3", BASELINE_PASS, "remoteRowsBeforeFailure=1000;requests=3;partialCatalogCommit=false;cachedRows=1")
    }

    @Test fun expectedUnsafeShortServerPageDeactivatesMissingCachedRecords() = scenario {
        db.exerciseDao().insertExercise(ExerciseEntity("missing", "Cached", 1, ""))
        remote.script = { ScriptedSyncRemote.Reply(catalog(0, 2)) } // Simulates server cap below requested500.
        stage("pullExercises", OWNER_A)
        assertFalse(db.exerciseDao().getExerciseById("missing")!!.isActive)
        assertEquals(1, remote.ledger.count("GET /rest/v1/exercises"))
        record("B07-cap", EXPECTED_UNSAFE_BEHAVIOR, "shortPageAssumedComplete=true;missingCachedExerciseDeactivated=true;requests=1")
    }

    @Test fun expectedUnsafeDuplicateRemoteIdsAreAcceptedAsReplacement() = scenario {
        remote.script = { ScriptedSyncRemote.Reply("""[{"id":"duplicate","name":"First","muscle_category_id":1},{"id":"duplicate","name":"Last","muscle_category_id":1}]""") }
        stage("pullExercises", OWNER_A)
        assertEquals("Last", db.exerciseDao().getExerciseById("duplicate")!!.name)
        record("B07-duplicate", EXPECTED_UNSAFE_BEHAVIOR, "duplicateSnapshotAccepted=true;lastRecordWins=true")
    }

    @Test fun expectedUnsafeUnchangedParentRecheckAcceptsEmptyChildWindow() = scenario {
        seedTemplate()
        db.workoutTemplateDao().markAsSynced(TEMPLATE, "SYNCED")
        remote.script = { request ->
            ScriptedSyncRemote.Reply(if (request.url.encodedPath.endsWith("/workout_templates")) "[${remoteTemplate()}]" else "[]")
        }
        stage("pullTemplates", OWNER_A)
        assertEquals("Remote routine", db.workoutTemplateDao().getTemplateById(TEMPLATE)?.name)
        assertTrue(db.templateExerciseDao().getExercisesForTemplateList(TEMPLATE).isEmpty())
        assertEquals(2, remote.ledger.count("GET /rest/v1/workout_templates"))
        record("B07-child-window", EXPECTED_UNSAFE_BEHAVIOR, "unchangedParentAcceptedEmptyChildren=true;cachedChildRemoved=true;fakeRemoteOnly=true")
    }

    @Test fun changedRemoteParentRecheckRejectsAggregateReplacement() = scenario {
        val parent = seedTemplate()
        db.workoutTemplateDao().markAsSynced(TEMPLATE, "SYNCED")
        remote.script = { request ->
            val changed = remote.ledger.count("GET /rest/v1/workout_templates") > 1
            ScriptedSyncRemote.Reply(if (request.url.encodedPath.endsWith("/workout_templates"))
                "[${remoteTemplate(if (changed) "Changed concurrently" else "Original remote")}]" else "[]")
        }
        var failed = false
        try { stage("pullTemplates", OWNER_A) } catch (_: Exception) { failed = true }
        assertTrue(failed)
        assertEquals(parent.name, db.workoutTemplateDao().getTemplateById(TEMPLATE)?.name)
        assertEquals(1, db.templateExerciseDao().getExercisesForTemplateList(TEMPLATE).size)
        record("B07-parent-check", BASELINE_PASS, "parentMismatchRejected=true;cachedAggregateUnchanged=true")
    }

    @Test fun expectedUnsafePrivateAggregateCommitsForOldOwnerAfterTransition() = scenario {
        remote.script = { request -> ScriptedSyncRemote.Reply(
            if (request.url.encodedPath.endsWith("/workout_templates")) "[${remoteTemplate()}]" else "[]") }
        val probe = transactions.arm()
        coroutineScope {
            val read = async { stage("pullTemplates", OWNER_A) }
            withContext(Dispatchers.IO) { probe.awaitReached() }
            owner(OWNER_B, SessionEvent.LOGIN)
            probe.release(); read.await()
        }
        assertEquals(OWNER_A, db.workoutTemplateDao().getTemplateById(TEMPLATE)?.userId)
        assertEquals(OWNER_B, client.auth.currentUserOrNull()?.id)
        record("B03-private", EXPECTED_UNSAFE_BEHAVIOR, "oldOwnerAggregateCommittedAfterTransition=true;rowStillPartitionedToOriginalOwner=true;actualUiLeak=NOT_TESTED")
    }

    @Test fun expectedUnsafeLatePendingWriteCanCoexistWithSuccessRunResult() = scenario {
        requireOnlineForPublicRun()
        val probe = SuspendProbe(); var scans = 0
        afterPendingLogs = { if (++scans == 1) probe.pause() }
        coroutineScope {
            val read = async { manager.run(OWNER_A) }
            probe.awaitReached()
            seedWorkout() // Direct DAO fixture; intentionally does NOT emulate repository enqueue.
            probe.release()
            assertEquals(SyncRunResult.SUCCESS, read.await())
        }
        assertEquals(1, db.workoutLogDao().getPendingLogs(OWNER_A).size)
        assertEquals(1, manager.syncState.value.pendingCount)
        assertEquals(0, remote.ledger.count("POST /rest/v1/rpc/upsert_workout_aggregate"))
        record("B09-late-scan", EXPECTED_UNSAFE_BEHAVIOR, "lateWriteNotInScan=true;runResultSuccess=true;pendingCount=1;repositoryWakeupAndActualWorkerChain=NOT_TESTED")
    }

    @Test fun expectedUnsafeTemplateCreateUpdateDeleteDispatchRemainsEnabled() = scenario {
        val expected = listOf(
            "POST /rest/v1/workout_templates", "DELETE /rest/v1/template_exercises", "POST /rest/v1/template_exercises")
        seedTemplate("CREATE")
        stage("syncPendingTemplates", OWNER_A)
        assertEquals(expected, remote.ledger.snapshot().map { it.endpoint })
        remote.ledger.clear()
        seedTemplate("UPDATE")
        stage("syncPendingTemplates", OWNER_A)
        assertEquals(listOf("PATCH /rest/v1/workout_templates", "DELETE /rest/v1/template_exercises", "POST /rest/v1/template_exercises"), remote.ledger.snapshot().map { it.endpoint })
        remote.ledger.clear()
        seedTemplate("DELETE", false)
        stage("syncPendingTemplates", OWNER_A)
        assertEquals(listOf("PATCH /rest/v1/workout_templates"), remote.ledger.snapshot().map { it.endpoint })
        record("B08-dispatch", EXPECTED_UNSAFE_BEHAVIOR, "create=parent,deleteChildren,insertChild;update=parent,deleteChildren,insertChild;delete=parentTombstone;denylistEnabled=false")
    }

    @Test fun expectedUnsafeEmptyTemplateCreateStillDeletesRemoteChildren() = scenario {
        seedTemplate("CREATE", false)
        stage("syncPendingTemplates", OWNER_A)
        assertEquals(listOf("POST /rest/v1/workout_templates", "DELETE /rest/v1/template_exercises"), remote.ledger.snapshot().map { it.endpoint })
        record("B08-empty", EXPECTED_UNSAFE_BEHAVIOR, "emptyCreateSentChildDelete=true;requests=2")
    }

    @Test fun failedTemplateInsertKeepsPendingAndRetryRepeatsDestructiveSequence() = scenario {
        seedTemplate()
        val base = remote.script
        remote.script = { request ->
            if (request.method.value == "POST" && request.url.encodedPath.endsWith("/template_exercises"))
                ScriptedSyncRemote.Reply("""{"message":"fixture failure","code":"FIXTURE"}""", 500)
            else base(request)
        }
        repeat(2) { assertEquals(false, stage("syncPendingTemplates", OWNER_A)) }
        assertEquals("CREATE", db.workoutTemplateDao().getTemplateById(TEMPLATE)?.pendingOperation)
        assertEquals("SYNC_FAILED", db.workoutTemplateDao().getTemplateById(TEMPLATE)?.syncStatus)
        assertEquals(2, remote.ledger.count("DELETE /rest/v1/template_exercises"))
        record("B08-retry", EXPECTED_UNSAFE_BEHAVIOR, "pendingPreserved=true;failedAttempts=2;childDeletes=2;remoteAtomicity=false")
    }

    @Test fun workerMapsExistingResultsWithoutClaimingDurableWakeupProof() = scenario {
        for (result in SyncRunResult.entries) {
            val factory = object : WorkerFactory() {
                override fun createWorker(appContext: android.content.Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    SyncWorker(appContext, workerParameters, object : SyncRunner {
                        override suspend fun run(expectedUserId: String?) = result
                    })
            }
            val worker = TestListenableWorkerBuilder<SyncWorker>(context).setWorkerFactory(factory).build()
            assertEquals(if (result == SyncRunResult.RETRYABLE_FAILURE) ListenableWorker.Result.retry() else ListenableWorker.Result.success(), worker.doWork())
        }
        record("B09-mapping", BASELINE_PASS, "success=success;noAuth=success;retryable=retry;actualQueueLostWakeup=NOT_TESTED")
    }

    @Test fun pendingSurvivesPersistentRoomReopenWhileCoverageIsUnknown() = scenario {
        val name = "b0-recovery-${UUID.randomUUID()}"
        val original = seedTemplate()
        preferences.setInitialSyncCompleted(OWNER_A, false)
        val persistent = Room.databaseBuilder(context, IMFITDatabase::class.java, name)
            .addCallback(IMFITDatabase.REVISION_CALLBACK).build()
        try {
            persistent.workoutTemplateDao().insertTemplate(original)
        } finally { persistent.close() }
        val reopened = Room.databaseBuilder(context, IMFITDatabase::class.java, name)
            .addCallback(IMFITDatabase.REVISION_CALLBACK).build()
        try {
            assertEquals("CREATE", reopened.workoutTemplateDao().getPendingSyncTemplates(OWNER_A).single().pendingOperation)
            assertEquals(original.name, reopened.workoutTemplateDao().getTemplateById(TEMPLATE)?.name)
            assertFalse(preferences.isInitialSyncCompleted(OWNER_A))
        } finally { reopened.close() }
        record("B09-B13-reopen", BASELINE_PASS, "pendingSurvivesDbReopen=true;cachedRowsWithUnknownCoverage=true;osProcessDeathAndRenderer=NOT_TESTED")
    }

    @Test fun expectedUnsafeCancellationLeavesFullSyncStatusSyncingButReleasesMutex() = scenario {
        requireOnlineForPublicRun()
        val probe = SuspendProbe(); val base = remote.script
        remote.script = { request -> if (request.url.encodedPath.endsWith("/exercises")) probe.pause(); base(request) }
        coroutineScope {
            val run = async { manager.run(OWNER_A) }
            probe.awaitReached(); run.cancelAndJoin(); probe.release()
        }
        assertEquals(SyncState.SyncStatus.SYNCING, manager.syncState.value.status)
        assertEquals(0, remote.ledger.active())
        assertTrue(remote.ledger.snapshot().any { it.terminal == "CANCELLED" })
        remote.script = base
        assertEquals(SyncRunResult.SUCCESS, manager.run(OWNER_A))
        record("B10-cancel", EXPECTED_UNSAFE_BEHAVIOR, "cancelledTransportActive=0;statusStuckSyncing=true;mutexReleasedAndNextRunSucceeds=true")
    }

    @Test fun httpAuthServerTimeoutFailuresPreserveCachedData() = scenario {
        requireOnlineForPublicRun()
        db.exerciseDao().insertExercise(ExerciseEntity("cached", "Cached", 1, ""))
        for (status in listOf(401, 403, 500)) {
            remote.script = { ScriptedSyncRemote.Reply("""{"message":"fixture failure","code":"FIXTURE"}""", status) }
            assertEquals(SyncRunResult.RETRYABLE_FAILURE, manager.refreshMasterData())
        }
        remote.script = { throw java.net.SocketTimeoutException("fixture timeout") }
        assertEquals(SyncRunResult.RETRYABLE_FAILURE, manager.refreshMasterData())
        assertEquals(listOf("cached"), db.exerciseDao().getAllActiveExercisesList().map { it.id })
        assertEquals(listOf("HTTP_401", "HTTP_403", "HTTP_500", "FAILED"), remote.ledger.snapshot().map { it.terminal })
        record("B10-http", BASELINE_PASS, "401,403,500,timeoutKeepCache=true;allMappedToRetryable=true;typedCauseDistinction=false")
    }

    @Test fun importerParticipatesInOuterTransactionRollback() = scenario {
        seedTemplate()
        db.workoutTemplateDao().markAsSynced(TEMPLATE, "SYNCED")
        val parent = db.workoutTemplateDao().getTemplateById(TEMPLATE)!!
        try {
            db.withTransaction {
                RemoteSnapshotImporter(db).template(parent.copy(name = "Would commit", updatedAt = 200), emptyList())
                throw IllegalStateException("fixture crash after nested import")
            }
        } catch (_: IllegalStateException) { }
        assertEquals(parent.name, db.workoutTemplateDao().getTemplateById(TEMPLATE)?.name)
        assertEquals(1, db.templateExerciseDao().getExercisesForTemplateList(TEMPLATE).size)
        assertTrue(remote.ledger.snapshot().isEmpty())
        record("B07-rollback", BASELINE_PASS, "nestedImporterParentAndChildrenRollback=true;remoteRequests=0")
    }

    @Test fun localRepositoryReadDoesNotFetchSupabase() = scenario {
        db.exerciseDao().insertExercise(ExerciseEntity("cached", "Cached", 1, ""))
        val repository = ExerciseRepositoryImpl(db.exerciseDao(), manager)
        assertEquals(1, repository.getAllExercises().size)
        assertTrue(repository.searchExercises("no-results").isEmpty())
        assertEquals(0, remote.ledger.snapshot().size)
        record("B14", BASELINE_PASS, "localRepositoryRequests=0;actualPagingAppend=NOT_TESTED")
    }
}
