package com.diajarkoding.imfit.data.legacy

import com.diajarkoding.imfit.data.remote.online.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class LegacyStartPreflightTest {
    private val reads = ScriptedReadGateway()
    private val clock = Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC)
    private val checkpoints = MemoryCheckpoints()
    private var inspections = 0
    private var inspect: suspend (String) -> LegacyInventory = { LegacyInventory.Absent(it) }
    private val inventory = LegacyInventoryReader { owner -> inspections++; inspect(owner) }
    private fun preflight() = LegacyStartPreflight(reads, inventory, checkpoints, clock)
    private fun starter() = LegacyGuardedSessionStart(preflight(), reads.repository)
    private fun call() = OnlineRpc.createSession("create-request", "session", "template", 7)
    private fun local(vararg items: LegacyItem, version: Int = 9, owner: String = "A", evidence: String = fingerprint("source")) =
        LegacyInventory.Present(owner, version, evidence, items.toList(), emptyList())
    private fun item(kind: LegacyKind = LegacyKind.ACTIVE_SESSION, disposition: LegacyDisposition = LegacyDisposition.REVIEW, request: String? = null) =
        LegacyItem(kind, "item", disposition, fingerprint("payload"), requestId = request)
    private suspend fun emptyRemote() {
        reads.api.requests.receive().also { assertEquals("get_active_session", it.rpc); it.reply("""{"found":false,"session":null}""") }
        reads.api.requests.receive().also { assertEquals("list_recent_operations", it.rpc); it.reply("""{"data":[],"page_info":{"next_cursor":null,"has_more":false,"limit":100}}""") }
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

    @Test fun `guarded create rechecks inventory and sends original immutable request once`() = runTest {
        val original = call()
        val result = async { starter().create(original) }; emptyRemote()
        val create = reads.api.requests.receive()
        assertEquals("create_active_session", create.rpc); assertEquals(original.parameters, create.body)
        create.reply(OnlineFixtures.receipt("create-request", operation = "create_active_session"))
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
            inspect = { local(version = version) }
            val result = async { starter().create(call()) }; emptyRemote()
            assertTrue(LegacyStartReason.SCHEMA_BRIDGE_REVIEW in (result.await() as GuardedLegacyStart.Blocked).report.reasons)
        }
        inspect = { local(item(LegacyKind.WORKOUT, LegacyDisposition.SYNCED_CACHE_CANDIDATE)) }
        val result = async { preflight().inspect() }; emptyRemote()
        assertEquals(LegacyStartDecision.READY, result.await().decision)
        assertEquals(1, checkpoints.saved.getValue("A").itemCount) // Still present, never cleaned.
    }

    @Test fun `remote active or expired session requires recovery instead of overwrite`() = runTest {
        for (snapshot in listOf(OnlineFixtures.snapshot, OnlineFixtures.snapshot.replace("\"effective_status\":\"PAUSED\"", "\"effective_status\":\"EXPIRED\""))) {
            val result = async { starter().create(call()) }
            reads.api.requests.receive().reply("""{"found":true,"session":$snapshot}""")
            reads.api.requests.receive().reply("""{"data":[],"page_info":{"next_cursor":null,"has_more":false,"limit":100}}""")
            assertTrue(LegacyStartReason.REMOTE_SESSION_REVIEW in (result.await() as GuardedLegacyStart.Blocked).report.reasons)
            assertTrue(reads.api.requests.tryReceive().isFailure)
        }
    }

    @Test fun `network failure or malformed session response is Unknown not absence`() = runTest {
        for (malformed in listOf(false, true)) {
            val result = async { starter().create(call()) }
            reads.api.requests.receive().also { if (malformed) it.reply("{}") else it.loseResponse() }
            assertEquals(LegacyStartDecision.UNKNOWN, (result.await() as GuardedLegacyStart.Blocked).report.decision)
            assertTrue(reads.api.requests.tryReceive().isFailure)
        }
    }

    @Test fun `server legacy-review conflict is recovery required not an empty session`() = runTest {
        val result = async { starter().create(call()) }
        reads.api.requests.receive().reply("""{"code":"PT409","message":"LEGACY_REVIEW_REQUIRED","details":null,"hint":null}""", 409)
        assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, (result.await() as GuardedLegacyStart.Blocked).report.decision)
    }

    @Test fun `discovery with more pages blocks rather than assuming bounded page is complete`() = runTest {
        val result = async { starter().create(call()) }
        reads.api.requests.receive().reply("""{"found":false,"session":null}""")
        reads.api.requests.receive().reply("""{"data":[],"page_info":{"next_cursor":"next","has_more":true,"limit":100}}""")
        assertEquals(LegacyStartDecision.UNKNOWN, (result.await() as GuardedLegacyStart.Blocked).report.decision)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `terminal receipt discovery requires explicit review and does not create replacement`() = runTest {
        val result = async { starter().create(call()) }
        reads.api.requests.receive().reply("""{"found":false,"session":null}""")
        reads.api.requests.receive().reply("""{"data":[{"request_id":"finished","operation":"finish_active_session","resource_type":"SESSION","resource_id":"session","committed_at":"${OnlineFixtures.time}","committed_at_source":"COMMIT","result":{}}],"page_info":{"next_cursor":null,"has_more":false,"limit":100}}""")
        val report = (result.await() as GuardedLegacyStart.Blocked).report
        assertEquals(listOf("finished"), report.operationIds)
        assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, report.decision)
    }

    @Test fun `COMMITTED lookup never acknowledges legacy template intent automatically`() = runTest {
        inspect = { local(item(LegacyKind.TEMPLATE_INTENT, request = "original")) }
        val result = async { starter().create(call()) }; emptyRemote()
        reads.api.requests.receive().also {
            assertEquals("get_operation_status", it.rpc)
            it.reply(OnlineFixtures.operation(OnlineFixtures.receipt("original"), request = "original"))
        }
        assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, (result.await() as GuardedLegacyStart.Blocked).report.decision)
        assertEquals(1, checkpoints.saved.getValue("A").itemCount)
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

    @Test fun `local change before dispatch blocks create and preserves original request`() = runTest {
        inspect = { owner -> if (inspections == 1) LegacyInventory.Absent(owner) else local(item()) }
        val result = async { starter().create(call()) }; emptyRemote()
        val report = (result.await() as GuardedLegacyStart.Blocked).report
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
