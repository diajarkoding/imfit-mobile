package com.diajarkoding.imfit.data.remote.online

import com.diajarkoding.imfit.data.legacy.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineSessionStoreTest {
    private val reads = ScriptedReadGateway()
    private var inventory: suspend (String) -> LegacyInventory = { LegacyInventory.Absent(it) }
    private val checkpoints = object : LegacyCheckpointStore {
        override suspend fun load(ownerId: String): LegacyCheckpoint? = null
        override suspend fun record(checkpoint: LegacyCheckpoint) = Unit
    }
    private val preflight = LegacyStartPreflight(reads, LegacyInventoryReader { inventory(it) }, checkpoints)
    private var ids = 0
    private val store = OnlineSessionStore(reads, reads.repository, LegacyGuardedSessionStart(preflight, reads.repository)) {
        if (++ids == 2) "session" else "request-$ids"
    }
    private suspend fun load(version: Long = 2) = coroutineScope {
        val job = async { store.reload() }
        reads.api.requests.receive().reply("""{"found":true,"session":${OnlineFixtures.snapshot.replace("\"version\":2", "\"version\":$version")}}""")
        job.await()
    }
    private suspend fun preflightClear() {
        reads.api.requests.receive().also { assertEquals("get_active_session", it.rpc); it.reply("""{"found":false,"session":null}""") }
        reads.api.requests.receive().also {
            assertEquals("list_recent_operations", it.rpc)
            it.reply("""{"data":[],"page_info":{"next_cursor":null,"has_more":false,"limit":100}}""")
        }
    }
    private fun FakeOnlineApi.Request.receipt(version: Long, completed: Boolean = false) = reply(OnlineFixtures.receipt(
        body.getValue("p_request_id").jsonPrimitive.content, version, rpc, completed))

    @Test fun `cold memory is unknown and hot timer reads never dispatch requests`() = runTest {
        assertEquals(RemoteError.Network, (runCatching { store.peek() }.exceptionOrNull() as OnlineFailure).reason)
        load()
        repeat(10_000) { assertEquals("session", store.peek()!!.id) }
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `absence is authoritative only after explicit successful load`() = runTest {
        val result = async { store.reload() }
        reads.api.requests.receive().reply("""{"found":false,"session":null}""")
        assertNull(result.await()); assertNull(store.peek())
        assertTrue(store.state.value.loaded)
    }

    @Test fun `load failure never clears an existing confirmed session`() = runTest {
        load()
        val before = store.peek()
        val result = async { runCatching { store.reload() } }
        reads.api.requests.receive().loseResponse()
        assertTrue(result.await().isFailure)
        assertEquals(before, store.peek())
        assertFalse(store.state.value.busy)
    }

    @Test fun `start publishes only acknowledgement after legacy guard`() = runTest {
        val result = async { store.start("template", 9) }; preflightClear()
        val request = reads.api.requests.receive()
        assertEquals("create_active_session", request.rpc)
        assertNull(store.state.value.snapshot)
        assertTrue(store.state.value.busy)
        request.receipt(1)
        assertEquals(1, result.await().version)
        assertEquals(1, store.peek()!!.version)
    }

    @Test fun `legacy unknown blocks start without retaining a replay capable of bypassing preflight`() = runTest {
        inventory = { LegacyInventory.Unknown(it, LegacyReadFailure.CORRUPT) }
        val result = async { runCatching { store.start("template", 9) } }; preflightClear()
        assertTrue(result.await().exceptionOrNull() is LegacyStartBlocked)
        assertNull(store.state.value.pendingRequestId)
        assertTrue(runCatching { store.retry() }.isFailure)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `cancel during inventory cannot retain a create that bypasses preflight`() = runTest {
        inventory = { awaitCancellation() }
        val result = launch { store.start("template", 1) }; runCurrent(); result.cancelAndJoin()
        assertNull(store.state.value.pendingRequestId)
        assertFalse(store.state.value.busy)
        assertTrue(runCatching { store.retry() }.isFailure)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `unknown create retries original IDs and CAS without a second preflight or fresh create`() = runTest {
        val result = async { runCatching { store.start("template", 9) } }; preflightClear()
        val original = reads.api.requests.receive(); original.loseResponse()
        assertTrue(result.await().isFailure)
        assertNull(store.state.value.snapshot)
        val blocked = runCatching { store.start("another-template", 10) }.exceptionOrNull() as OnlineFailure
        assertEquals(RemoteError.UnresolvedMutation, blocked.reason)
        val retry = async { store.retry() }
        reads.api.requests.receive().also { assertEquals(original.body, it.body); assertEquals(original.rpc, it.rpc); it.receipt(1) }
        assertEquals("session", retry.await().sessionId)
        assertNull(store.state.value.pendingRequestId)
    }

    @Test fun `notification and UI commands serialize with increasing confirmed version`() = runTest {
        load()
        val first = async { store.command("session", SessionCommand.Resume) }
        val second = async { store.command("session", SessionCommand.Pause) }
        val request = reads.api.requests.receive()
        assertEquals(2, request.body.getValue("p_expected_version").jsonPrimitive.long)
        assertTrue(reads.api.requests.tryReceive().isFailure)
        request.receipt(3); assertEquals(3, first.await().version)
        reads.api.requests.receive().also {
            assertEquals(3, it.body.getValue("p_expected_version").jsonPrimitive.long)
            it.receipt(4)
        }
        assertEquals(4, second.await().version)
        assertEquals(4, store.peek()!!.version)
    }

    @Test fun `unknown command locks different writes but keeps confirmed snapshot`() = runTest {
        load()
        val result = async { runCatching { store.command("session", SessionCommand.Resume) } }
        val original = reads.api.requests.receive(); original.loseResponse()
        assertTrue(result.await().isFailure)
        assertEquals(2, store.peek()!!.version)
        assertEquals(RemoteError.UnknownOutcome, store.state.value.failure)
        assertTrue(runCatching { store.finish("session") }.isFailure)
        assertTrue(runCatching { store.cancel("session") }.isFailure)
        val retry = async { store.retry() }
        reads.api.requests.receive().also { assertEquals(original.body, it.body); it.receipt(3) }
        assertEquals(3, retry.await().version)
    }

    @Test fun `cancellation after dispatch retains exact request for explicit retry`() = runTest {
        load()
        val result = launch { store.command("session", SessionCommand.Resume) }
        val original = reads.api.requests.receive(); result.cancelAndJoin()
        assertEquals(RemoteError.UnknownOutcome, store.state.value.failure)
        val retry = async { store.retry() }
        reads.api.requests.receive().also { assertEquals(original.body, it.body); it.receipt(3) }
        retry.await(); assertNull(store.state.value.pendingRequestId)
    }

    @Test fun `version conflict keeps old confirmed state until explicit reload then permits a new command`() = runTest {
        load()
        val result = async { runCatching { store.command("session", SessionCommand.Resume) } }
        reads.api.requests.receive().reply("""{"code":"PT409","message":"VERSION_CONFLICT","details":null,"hint":null}""", 409)
        assertTrue(result.await().isFailure); assertNull(store.state.value.pendingRequestId)
        assertEquals(2, store.peek()!!.version)
        load(7)
        val next = async { store.command("session", SessionCommand.Resume) }
        reads.api.requests.receive().also { assertEquals(7, it.body.getValue("p_expected_version").jsonPrimitive.long); it.receipt(8) }
        assertEquals(8, next.await().version)
    }

    @Test fun `finish receipt makes repeated finish return same workout without a new mutation`() = runTest {
        load()
        val result = async { store.finish("session") }
        val original = reads.api.requests.receive()
        assertNotNull(store.peek()); original.receipt(3, completed = true)
        val receipt = result.await()
        assertEquals("workout", receipt.workoutId); assertNull(store.peek())
        assertEquals(receipt, store.finish("session"))
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `A B A delayed response cannot publish or replay old account request`() = runTest {
        load(); reads.api.ignoreCancellation = true
        val result = async { runCatching { store.command("session", SessionCommand.Resume) }.exceptionOrNull() }
        val request = reads.api.requests.receive()
        reads.auth.signIn("B", "token-B"); reads.auth.signIn("A", "new-A")
        request.receipt(3)
        assertTrue(result.await() is StaleAuthException)
        assertNull(store.state.value.snapshot); assertNull(store.state.value.pendingRequestId)
        assertFalse(store.state.value.loaded)
        assertTrue(runCatching { store.retry() }.isFailure)
    }

    @Test fun `new process store loads server snapshot not former memory or timer storage`() = runTest {
        load(8)
        val replacement = OnlineSessionStore(reads, reads.repository, LegacyGuardedSessionStart(preflight, reads.repository))
        assertFalse(replacement.state.value.loaded)
        val result = async { replacement.reload() }
        reads.api.requests.receive().reply("""{"found":true,"session":${OnlineFixtures.snapshot.replace("\"version\":2", "\"version\":9")}}""")
        assertEquals(9, result.await()!!.version)
        assertEquals(8, store.peek()!!.version)
        replacement.close()
    }
}
