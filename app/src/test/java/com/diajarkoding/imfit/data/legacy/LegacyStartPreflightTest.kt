package com.diajarkoding.imfit.data.legacy

import com.diajarkoding.imfit.data.remote.online.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class LegacyStartPreflightTest {
    private val reads = ScriptedReadGateway()
    private val clock = Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC)
    private val checkpoints = MemoryCheckpoints()
    private var inspections = 0
    private var inspect: suspend (String) -> LegacyInventory = { LegacyInventory.Absent(it) }
    private val inventory = LegacyInventoryReader { owner -> inspections++; inspect(owner) }
    private val recoveryCheckpoints = MemoryLegacyRecoveryCheckpoints()
    private val recovery = testRecoveryCoordinator(reads, reads.repository, inventory, recoveryCheckpoints)
    private fun preflight() = LegacyStartPreflight(reads, inventory, checkpoints, recovery, clock)
    private fun starter() = LegacyGuardedSessionStart(preflight(), reads.repository)
    private fun call() = OnlineRpc.createSession("create-request", "session", "template", 7)
    private fun local(vararg items: LegacyItem, version: Int = 9, owner: String = "A", evidence: String = fingerprint("source")) =
        LegacyInventory.Present(owner, version, evidence, items.toList(), emptyList())
    private fun item(kind: LegacyKind = LegacyKind.ACTIVE_SESSION, disposition: LegacyDisposition = LegacyDisposition.REVIEW, request: String? = null) =
        LegacyItem(kind, "item", disposition, fingerprint("payload"), requestId = request)
    private suspend fun emptyRemote() {
        reads.api.requests.receive().also {
            assertEquals("get_start_recovery_status", it.rpc)
            it.reply(LegacyReadinessFixtures.ready)
        }
    }
    private class MemoryCheckpoints : LegacyCheckpointStore {
        val saved = mutableMapOf<String, LegacyCheckpoint>()
        var failRead = false
        var failWrite = false
        var afterWrite: () -> Unit = {}
        override suspend fun load(ownerId: String): LegacyCheckpoint? { check(!failRead); return saved[ownerId] }
        override suspend fun record(checkpoint: LegacyCheckpoint) { check(!failWrite); saved[checkpoint.ownerId] = checkpoint; afterWrite() }
    }

    @Test fun `fresh install needs authoritative remote absence and records owner evidence`() = runTest {
        val result = async { preflight().inspect() }; emptyRemote()
        assertEquals(LegacyStartDecision.READY, result.await().decision)
        assertEquals(setOf("A"), checkpoints.saved.keys)
        assertEquals("READY", checkpoints.saved.getValue("A").outcome)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `guarded create rechecks local inventory before creating a session`() = runTest {
        val result = async { starter().create(call()) }; emptyRemote()
        reads.api.requests.receive().also {
            assertEquals("create_active_session", it.rpc)
            it.reply(OnlineFixtures.receipt("create-request", operation = "create_active_session"))
        }
        assertTrue(result.await() is GuardedLegacyStart.Created)
        assertEquals(2, inspections)
    }

    @Test fun `active legacy pending logs profile review and unknown intents all block create`() = runTest {
        for (kind in listOf(LegacyKind.ACTIVE_SESSION, LegacyKind.WORKOUT, LegacyKind.PROFILE, LegacyKind.TEMPLATE_INTENT)) {
            inspect = { local(item(kind)) }
            val result = async { starter().create(call()) }; emptyRemote()
            val report = (result.await() as GuardedLegacyStart.Blocked).report
            assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, report.decision)
            assertTrue(LegacyStartReason.LEGACY_ITEMS_REVIEW in report.reasons)
            assertTrue(reads.api.requests.tryReceive().isFailure)
        }
    }

    @Test fun `read failures unknown schemas and wrong owner never become fresh install`() = runTest {
        for (failure in LegacyReadFailure.entries) {
            inspect = { LegacyInventory.Unknown(it, failure) }
            val result = async { starter().create(call()) }; emptyRemote()
            assertEquals(LegacyStartDecision.UNKNOWN, (result.await() as GuardedLegacyStart.Blocked).report.decision)
        }
        inspect = { local(owner = "B") }
        val wrong = async { starter().create(call()) }; emptyRemote()
        assertEquals("A", (wrong.await() as GuardedLegacyStart.Blocked).report.inventory.ownerId)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `known 6 and 7 remain bridge review and synced candidates do not acknowledge data`() = runTest {
        for (version in listOf(6, 7)) {
            inspect = { local(item(LegacyKind.WORKOUT), version = version) }
            val result = async { starter().create(call()) }; emptyRemote()
            assertTrue(LegacyStartReason.SCHEMA_BRIDGE_REVIEW in (result.await() as GuardedLegacyStart.Blocked).report.reasons)
        }
        inspect = { local(item(LegacyKind.WORKOUT, LegacyDisposition.SYNCED_CACHE_CANDIDATE)) }
        val result = async { preflight().inspect() }; emptyRemote()
        assertEquals(LegacyStartDecision.READY, result.await().decision)
        assertEquals(1, checkpoints.saved.getValue("A").itemCount) // Still present, never cleaned.
    }

    @Test fun `remote active expired and legacy blockers require recovery instead of overwrite`() = runTest {
        for (kind in RecoveryBlockerKind.entries) {
            val result = async { starter().create(call()) }
            reads.api.requests.receive().also {
                assertEquals("get_start_recovery_status", it.rpc)
                it.reply(LegacyReadinessFixtures.response(kind, count = 1))
            }
            val report = (result.await() as GuardedLegacyStart.Blocked).report
            assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, report.decision)
            assertEquals(UUID(0, 1).toString(), report.remoteSessionId)
            assertTrue(LegacyStartReason.REMOTE_SESSION_REVIEW in report.reasons)
            assertTrue(reads.api.requests.tryReceive().isFailure)
        }
    }

    @Test fun `network failure and malformed readiness are Unknown not absence`() = runTest {
        val malformed = listOf(
            "{}",
            LegacyReadinessFixtures.ready.replace(OnlineFixtures.time, "not-a-timestamp"),
            LegacyReadinessFixtures.ready.replace("\"contract_version\":1", "\"contract_version\":2"),
            LegacyReadinessFixtures.response(count = 1).replace("\"can_create\":false", "\"can_create\":true"),
            LegacyReadinessFixtures.response(count = 1).replace(UUID(0, 1).toString(), "not-a-uuid"),
            LegacyReadinessFixtures.ready.replace("\"has_more_blockers\":false", "\"has_more_blockers\":true"),
        )
        for (response in listOf<String?>(null) + malformed) {
            val result = async { starter().create(call()) }
            reads.api.requests.receive().also {
                assertEquals("get_start_recovery_status", it.rpc)
                if (response == null) it.loseResponse() else it.reply(response)
            }
            val report = (result.await() as GuardedLegacyStart.Blocked).report
            assertEquals(LegacyStartDecision.UNKNOWN, report.decision)
            assertTrue(LegacyStartReason.REMOTE_UNAVAILABLE in report.reasons)
            assertTrue(reads.api.requests.tryReceive().isFailure)
        }
    }

    @Test fun `hidden blockers beyond the preview remain recovery required`() = runTest {
        val result = async { starter().create(call()) }
        reads.api.requests.receive().also {
            assertEquals("get_start_recovery_status", it.rpc)
            it.reply(LegacyReadinessFixtures.response(count = 20, hasMore = true))
        }
        val report = (result.await() as GuardedLegacyStart.Blocked).report
        assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, report.decision)
        assertTrue(LegacyStartReason.REMOTE_SESSION_REVIEW in report.reasons)
        assertTrue(report.operationIds.isEmpty())
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `101 and 1000 historical terminal receipts need no receipt discovery to start`() = runTest {
        for (count in listOf(101, 1_000)) {
            repeat(count) { index ->
                val requestId = UUID(count.toLong(), index.toLong() + 1).toString()
                val sessionId = UUID(count.toLong() + 1, index.toLong() + 1).toString()
                val committed = async {
                    reads.repository.execute(OnlineRpc.finishSession(requestId, sessionId, 2))
                }
                reads.api.requests.receive().also {
                    assertEquals("finish_active_session", it.rpc)
                    it.reply(OnlineFixtures.receipt(requestId, 3, "finish_active_session", completed = true)
                        .replace("\"session\"", "\"$sessionId\"")
                        .replace("\"workout\"", "\"${UUID(count.toLong() + 2, index.toLong() + 1)}\""))
                }
                assertEquals(SessionStatus.COMPLETED, committed.await().status)
            }
            val createRequest = UUID(count.toLong(), 0).toString()
            val result = async { starter().create(OnlineRpc.createSession(createRequest, "session", "template", 7)) }; emptyRemote()
            reads.api.requests.receive().also {
                // Any full receipt listing or active-session scan fails here, rather than being
                // supplied a fake larger window that hides the consumer regression.
                assertEquals("create_active_session", it.rpc)
                it.reply(OnlineFixtures.receipt(createRequest, operation = "create_active_session"))
            }
            assertTrue(result.await() is GuardedLegacyStart.Created)
            assertEquals("READY", checkpoints.saved.getValue("A").outcome)
            assertTrue(reads.api.requests.tryReceive().isFailure)
        }
    }

    @Test fun `targeted COMMITTED lookup never acknowledges legacy template intent automatically`() = runTest {
        val original = UUID(0, 90).toString()
        inspect = { local(item(LegacyKind.TEMPLATE_INTENT, request = original)) }
        val result = async { starter().create(call()) }; emptyRemote()
        reads.api.requests.receive().also {
            assertEquals("get_operation_status", it.rpc)
            assertEquals(original, it.body.getValue("p_request_id").jsonPrimitive.content)
            it.reply("""{"state":"COMMITTED","receipt":{"request_id":"$original",
                "operation":"save_template_revision","resource_type":"TEMPLATE",
                "resource_id":"${UUID(0, 91)}","committed_at":"${OnlineFixtures.time}",
                "committed_at_source":"COMMIT","result":{"request_id":"$original",
                "template_id":"${UUID(0, 91)}","revision":2,"exercise_count":0,
                "server_updated_at":"${OnlineFixtures.time}"}}}""")
        }
        val report = (result.await() as GuardedLegacyStart.Blocked).report
        assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, report.decision)
        assertEquals(listOf(original), report.operationIds)
        assertTrue(LegacyStartReason.REMOTE_OPERATIONS_REVIEW in report.reasons)
        assertFalse(LegacyStartReason.REMOTE_UNAVAILABLE in report.reasons)
        assertTrue(recoveryCheckpoints.saved.isEmpty())
        assertEquals(1, checkpoints.saved.getValue("A").itemCount)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `old Ready checkpoint cannot bypass restored pending data`() = runTest {
        val first = async { preflight().inspect() }; emptyRemote(); assertEquals(LegacyStartDecision.READY, first.await().decision)
        inspect = { local(item(), evidence = fingerprint("restored")) }
        val second = async { starter().create(call()) }; emptyRemote()
        assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, (second.await() as GuardedLegacyStart.Blocked).report.decision)
        assertEquals(2, inspections)
    }

    @Test fun `checkpoint read write and foreign owner failures all block create`() = runTest {
        checkpoints.failRead = true
        val first = async { starter().create(call()) }; emptyRemote()
        assertEquals(LegacyStartDecision.UNKNOWN, (first.await() as GuardedLegacyStart.Blocked).report.decision)
        checkpoints.failRead = false; checkpoints.failWrite = true
        val second = async { starter().create(call()) }; emptyRemote()
        assertEquals(LegacyStartDecision.UNKNOWN, (second.await() as GuardedLegacyStart.Blocked).report.decision)
        checkpoints.failWrite = false
        checkpoints.saved["A"] = LegacyCheckpoint("B", 1, fingerprint("wrong"), 9, "READY", emptyList(), 0, clock.instant().toString())
        val third = async { starter().create(call()) }; emptyRemote()
        assertEquals(LegacyStartDecision.UNKNOWN, (third.await() as GuardedLegacyStart.Blocked).report.decision)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `damaged foreign and unsupported recovery checkpoints cannot release Start`() = runTest {
        recoveryCheckpoints.failRead = true
        val unreadable = async { starter().create(call()) }; emptyRemote()
        assertEquals(LegacyStartDecision.UNKNOWN, (unreadable.await() as GuardedLegacyStart.Blocked).report.decision)
        recoveryCheckpoints.failRead = false
        for (damaged in listOf(
            LegacyRecoveryCheckpoint("B"),
            LegacyRecoveryCheckpoint("A", formatVersion = 2),
        )) {
            recoveryCheckpoints.saved["A"] = damaged
            val result = async { starter().create(call()) }; emptyRemote()
            val report = (result.await() as GuardedLegacyStart.Blocked).report
            assertEquals(LegacyStartDecision.UNKNOWN, report.decision)
            assertTrue(LegacyStartReason.CHECKPOINT_UNAVAILABLE in report.reasons)
        }
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `submitted recovery remains ambiguous when its local source is missing`() = runTest {
        val action = testServerArchiveAction(LegacyRecoveryState.SUBMITTED)
        recoveryCheckpoints.save(LegacyRecoveryCheckpoint("A", actions = listOf(action)))
        val result = async { starter().create(call()) }; emptyRemote()
        reads.api.requests.receive().also {
            assertEquals("get_operation_status", it.rpc)
            assertEquals(action.requestId, it.body.getValue("p_request_id").jsonPrimitive.content)
            it.reply("""{"state":"NOT_FOUND","receipt":null}""")
        }
        val report = (result.await() as GuardedLegacyStart.Blocked).report
        assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, report.decision)
        assertEquals(listOf(action.requestId), report.operationIds)
        assertEquals(LegacyRecoveryState.SUBMITTED, recoveryCheckpoints.saved.getValue("A").actions.single().state)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `never submitted and definitively rejected actions do not trigger receipt discovery`() = runTest {
        for (state in listOf(LegacyRecoveryState.PREPARED, LegacyRecoveryState.BLOCKED, LegacyRecoveryState.CONFLICT)) {
            recoveryCheckpoints.save(LegacyRecoveryCheckpoint("A", actions = listOf(testServerArchiveAction(state))))
            val result = async { preflight().inspect() }; emptyRemote()
            assertEquals(LegacyStartDecision.READY, result.await().decision)
            assertTrue(reads.api.requests.tryReceive().isFailure)
        }
    }

    @Test fun `response after A B A cannot publish Ready or register create`() = runTest {
        reads.api.ignoreCancellation = true
        val result = async { runCatching { starter().create(call()) }.exceptionOrNull() }
        val request = reads.api.requests.receive()
        assertEquals("get_start_recovery_status", request.rpc)
        reads.auth.signIn("B", "B"); reads.auth.signIn("A", "new A")
        request.reply(LegacyReadinessFixtures.ready)
        assertTrue(result.await() is StaleAuthException)
        assertTrue(checkpoints.saved.isEmpty())
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `cancelling an in flight readiness read cannot record Ready or create`() = runTest {
        val result = async { starter().create(call()) }
        assertEquals("get_start_recovery_status", reads.api.requests.receive().rpc)
        result.cancel()
        result.join()
        assertTrue(result.isCancelled)
        assertTrue(checkpoints.saved.isEmpty())
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `local change before dispatch blocks create and preserves original request`() = runTest {
        inspect = { owner -> if (inspections == 1) LegacyInventory.Absent(owner) else local(item()) }
        val result = async { starter().create(call()) }; emptyRemote()
        val report = (result.await() as GuardedLegacyStart.Blocked).report
        assertEquals(setOf(LegacyStartReason.LOCAL_CHANGED), report.reasons)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `second inventory detects dependency changes despite an unchanged file fingerprint`() = runTest {
        val original = local(item(LegacyKind.WORKOUT, LegacyDisposition.SYNCED_CACHE_CANDIDATE)).copy(
            dependencies = listOf(LegacyDependency("TEMPLATE", "template", true, fingerprint("revision one"))))
        inspect = {
            if (inspections == 1) original else original.copy(
                dependencies = listOf(LegacyDependency("TEMPLATE", "template", true, fingerprint("revision two"))))
        }
        val result = async { starter().create(call()) }; emptyRemote()
        val report = (result.await() as GuardedLegacyStart.Blocked).report
        assertEquals(LegacyStartDecision.UNKNOWN, report.decision)
        assertEquals(setOf(LegacyStartReason.LOCAL_CHANGED), report.reasons)
        assertEquals(2, inspections)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `second inventory failure is not permission to create`() = runTest {
        inspect = { owner ->
            if (inspections == 1) LegacyInventory.Absent(owner)
            else throw IllegalStateException("source became unreadable")
        }
        val result = async { starter().create(call()) }; emptyRemote()
        val report = (result.await() as GuardedLegacyStart.Blocked).report
        assertEquals(LegacyStartDecision.UNKNOWN, report.decision)
        assertEquals(setOf(LegacyStartReason.LOCAL_CHANGED), report.reasons)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `A B A while inventory ignores cancellation cannot publish old Ready or send RPC`() = runTest {
        val gate = CompletableDeferred<Unit>()
        inspect = { owner -> withContext(NonCancellable) { gate.await() }; LegacyInventory.Absent(owner) }
        val result = async { runCatching { starter().create(call()) }.exceptionOrNull() }
        testScheduler.runCurrent()
        reads.auth.signOut(); reads.auth.signIn("B", "B"); reads.auth.signIn("A", "new-A"); gate.complete(Unit)
        assertTrue(result.await() is StaleAuthException)
        assertTrue(checkpoints.saved.isEmpty()); assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `cancellation stays cancellation and never records Ready`() = runTest {
        inspect = { throw CancellationException("fixture") }
        val failure = runCatching { starter().create(call()) }.exceptionOrNull()
        assertTrue(failure is CancellationException)
        assertTrue(checkpoints.saved.isEmpty()); assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `account change while recording checkpoint cannot dispatch create as new owner`() = runTest {
        checkpoints.afterWrite = { reads.auth.signIn("B", "B") }
        val result = async { runCatching { starter().create(call()) }.exceptionOrNull() }; emptyRemote()
        assertTrue(result.await() is StaleAuthException)
        assertEquals(setOf("A"), checkpoints.saved.keys)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `repository expected epoch fence rejects A B A before sending original write`() = runTest {
        val old = reads.auth.capture().identity
        reads.auth.signIn("B", "B"); reads.auth.signIn("A", "new-A")
        val failure = runCatching { reads.repository.execute(call(), expectedIdentity = old) }.exceptionOrNull()
        assertTrue(failure is StaleAuthException); assertTrue(reads.api.requests.tryReceive().isFailure)
    }
}
