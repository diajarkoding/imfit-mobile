package com.diajarkoding.imfit.data.remote.online

import com.diajarkoding.imfit.data.legacy.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.coroutines.flow.collect
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
    private val inventoryReader = LegacyInventoryReader { inventory(it) }
    private val preflight = LegacyStartPreflight(reads, inventoryReader, checkpoints,
        testRecoveryCoordinator(reads, reads.repository, inventoryReader))
    private var ids = 0
    private var store = OnlineSessionStore(reads, reads.repository, LegacyGuardedSessionStart(preflight, reads.repository)) {
        if (++ids == 2) "session" else "request-$ids"
    }
    private suspend fun load(version: Long = 2, snapshot: String = OnlineFixtures.snapshot) = coroutineScope {
        val job = async { store.reload() }
        reads.api.requests.receive().reply("""{"found":true,"session":${snapshot.replace("\"version\":2", "\"version\":$version")}}""")
        job.await()
    }
    private suspend fun preflightClear() {
        reads.api.requests.receive().also {
            assertEquals("get_start_recovery_status", it.rpc)
            it.reply(LegacyReadinessFixtures.ready)
        }
    }
    private fun FakeOnlineApi.Request.receipt(version: Long, completed: Boolean = false) = reply(OnlineFixtures.receipt(
        body.getValue("p_request_id").jsonPrimitive.content, version, rpc, completed))

    private fun useScope(scope: CoroutineScope, changes: OnlineMutationChanges? = null) {
        store.close()
        store = OnlineSessionStore(reads, reads.repository, LegacyGuardedSessionStart(preflight, reads.repository), scope = scope,
            changes = changes, newId = { "request-${++ids}" })
    }

    private val draftSnapshot get() = OnlineFixtures.snapshot.replace("\"is_completed\":true", "\"is_completed\":false")
    private val key = SessionSetKey("child", "set")
    private fun FakeOnlineApi.Request.edited(version: Long, weight: String, reps: Int = 10, completed: Boolean = false) {
        val receipt = onlineJson.decodeFromString<SessionMutationReceipt>(
            OnlineFixtures.receipt(body.getValue("p_request_id").jsonPrimitive.content, version, rpc))
        val snapshot = receipt.snapshot.copy(exercises = receipt.snapshot.exercises.map { child ->
            child.copy(sets = child.sets.map { it.copy(weight = weight, reps = reps, isCompleted = completed) })
        })
        reply(onlineJson.encodeToString(SessionMutationReceipt.serializer(), receipt.copy(snapshot = snapshot)))
    }

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
    @Test fun `receipt A acknowledges only revision A while typed revision B remains dirty`() = runTest {
        useScope(backgroundScope)
        load(snapshot = draftSnapshot)
        store.editSet("session", key, "12,50", "8")
        assertEquals(SessionSavePhase.DIRTY, store.state.value.savePhase)
        assertEquals("10.00", store.state.value.snapshot!!.exercises.single().sets.single().weight)
        val flush = async { store.flushEdits("session") }
        val request = reads.api.requests.receive()
        assertEquals(SessionSavePhase.SAVING, store.state.value.savePhase)
        assertEquals("12.50", request.body.getValue("p_command").jsonObject.getValue("weight").jsonPrimitive.content)
        store.editSet("session", key, "17,25", "9")
        request.edited(3, "12.50", 8)
        flush.await()
        assertEquals("12.50", store.state.value.snapshot!!.exercises.single().sets.single().weight)
        assertEquals("17,25", store.state.value.drafts.getValue(key).weightText)
        assertEquals(SessionSavePhase.DIRTY, store.state.value.savePhase)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `complete before debounce flushes edit first then uses acknowledged CAS`() = runTest {
        useScope(backgroundScope)
        load(snapshot = draftSnapshot)
        store.editSet("session", key, "25", "8")
        val complete = async { store.command("session", SessionCommand.CompleteSet("child", "set")) }
        val edit = reads.api.requests.receive()
        assertEquals("edit_set", edit.body.getValue("p_command").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(2L, edit.body.getValue("p_expected_version").jsonPrimitive.long)
        assertTrue(reads.api.requests.tryReceive().isFailure)
        edit.edited(3, "25.00", 8)
        val next = reads.api.requests.receive()
        assertEquals("complete_set", next.body.getValue("p_command").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(3L, next.body.getValue("p_expected_version").jsonPrimitive.long)
        next.edited(4, "25.00", 8, completed = true)
        assertTrue(complete.await().exercises.single().sets.single().isCompleted)
        assertEquals(SessionSavePhase.CONFIRMED, store.state.value.savePhase)
    }

    @Test fun `application debounce waits 400ms and incomplete text remains dirty without dispatch`() = runTest {
        useScope(backgroundScope)
        load(snapshot = draftSnapshot)
        store.editSet("session", key, "15", "8")
        advanceTimeBy(399); runCurrent()
        assertTrue(reads.api.requests.tryReceive().isFailure)
        advanceTimeBy(1); runCurrent()
        reads.api.requests.receive().edited(3, "15.00", 8)
        runCurrent()
        assertTrue(store.state.value.drafts.isEmpty())
        store.editSet("session", key, "", "8")
        advanceTimeBy(400); runCurrent()
        assertEquals("", store.state.value.drafts.getValue(key).weightText)
        assertEquals(SessionSavePhase.DIRTY, store.state.value.savePhase)
        assertTrue(store.state.value.failure is RemoteError.Validation)
        assertTrue(reads.api.requests.tryReceive().isFailure)
        val complete = runCatching { store.command("session", SessionCommand.CompleteSet("child", "set")) }
        assertTrue(complete.isFailure)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `owner registration gate never manufactures a pending session request`() = runTest {
        load()
        val templateCall = OnlineRpc.saveTemplate(com.diajarkoding.imfit.data.remote.TemplateWriteRequest(
            "template-unknown", "template", null, "Routine", emptyList(), emptyList(), ownerId = "A"))
        val template = async { runCatching { reads.repository.execute(templateCall) } }
        reads.api.requests.receive().loseResponse()
        assertTrue(template.await().isFailure)
        assertTrue(runCatching { store.command("session", SessionCommand.Resume) }.isFailure)
        assertNull(store.state.value.pendingRequestId)
        assertTrue(runCatching { store.retry() }.isFailure)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `NOT FOUND retains original pending and old receipt never downgrades targeted read`() = runTest {
        load()
        val command = async { runCatching { store.command("session", SessionCommand.Resume) } }
        val original = reads.api.requests.receive()
        original.loseResponse(); command.await()
        val requestId = original.body.getValue("p_request_id").jsonPrimitive.content
        val missing = async { store.resolvePending() }
        reads.api.requests.receive().also {
            assertEquals("get_operation_status", it.rpc)
            assertEquals(requestId, it.body.getValue("p_request_id").jsonPrimitive.content)
            it.reply("""{"state":"NOT_FOUND","receipt":null}""")
        }
        assertNull(missing.await())
        assertEquals(requestId, store.state.value.pendingRequestId)
        val reload = async { store.reload("session") }
        reads.api.requests.receive().also {
            assertEquals("session", it.body.getValue("p_session_id").jsonPrimitive.content)
            it.reply("""{"found":true,"session":${OnlineFixtures.snapshot.replace("\"version\":2", "\"version\":8")}}""")
        }
        reload.await()
        assertEquals(requestId, store.state.value.pendingRequestId)
        val resolve = async { store.resolvePending() }
        reads.api.requests.receive().reply(OnlineFixtures.operation(OnlineFixtures.receipt(requestId, 3), requestId))
        assertEquals(3L, resolve.await()!!.version)
        assertEquals(8L, store.state.value.snapshot!!.version)
        assertNull(store.state.value.pendingRequestId)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `CAS conflict requires authoritative reload and explicit reapply of preserved text`() = runTest {
        useScope(backgroundScope)
        load(snapshot = draftSnapshot)
        store.editSet("session", key, "25", "8")
        val flush = async { runCatching { store.flushEdits("session") } }
        reads.api.requests.receive().reply("""{"code":"PT409","message":"VERSION_CONFLICT","details":null,"hint":null}""", 409)
        assertTrue(flush.await().isFailure)
        assertEquals(SessionSavePhase.CONFLICT, store.state.value.savePhase)
        assertTrue(runCatching { store.reapplyDrafts("session") }.isFailure)
        assertTrue(runCatching { store.command("session", SessionCommand.Resume) }.isFailure)
        load(7, draftSnapshot)
        assertEquals(SessionSavePhase.CONFLICT, store.state.value.savePhase)
        val reapply = async { store.reapplyDrafts("session") }
        reads.api.requests.receive().also {
            assertEquals(7L, it.body.getValue("p_expected_version").jsonPrimitive.long)
            it.edited(8, "25.00", 8)
        }
        reapply.await()
        assertEquals(SessionSavePhase.CONFIRMED, store.state.value.savePhase)
    }

    @Test fun `expiry acknowledgement reloads targeted expired review without flushing drafts`() = runTest {
        useScope(backgroundScope)
        load(snapshot = draftSnapshot)
        store.editSet("session", key, "15", "8")
        val expired = onlineJson.decodeFromString<SessionSnapshot>(draftSnapshot).copy(
            status = SessionStatus.EXPIRED, effectiveStatus = SessionStatus.EXPIRED, reviewRequired = true, version = 5)
        val acknowledge = async { store.acknowledgeExpiry("session") }
        reads.api.requests.receive().also {
            assertEquals("get_active_session", it.rpc)
            assertEquals("session", it.body.getValue("p_session_id").jsonPrimitive.content)
            it.reply(onlineJson.encodeToString(SessionLookup.serializer(), SessionLookup(true, expired)))
        }
        val request = reads.api.requests.receive()
        assertEquals("acknowledge_expiry", request.body.getValue("p_command").jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals(5L, request.body.getValue("p_expected_version").jsonPrimitive.long)
        assertTrue(store.state.value.drafts.isNotEmpty())
        val receipt = SessionMutationReceipt(request.body.getValue("p_request_id").jsonPrimitive.content,
            request.rpc, "session", 6, SessionStatus.EXPIRED, OnlineFixtures.time,
            expired.copy(version = 6, reviewRequired = false))
        request.reply(onlineJson.encodeToString(SessionMutationReceipt.serializer(), receipt))
        assertEquals(SessionStatus.EXPIRED, acknowledge.await().effectiveStatus)
        assertTrue(store.state.value.drafts.isEmpty())
        assertNull(store.peek())
    }

    @Test fun `offline reconnect is read only and account switch cancels delayed raw input`() = runTest {
        useScope(backgroundScope)
        load(snapshot = draftSnapshot)
        store.editSet("session", key, "15", "8")
        store.updateConnectivity(false)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(SessionSavePhase.OFFLINE_BLOCKED, store.state.value.savePhase)
        assertTrue(store.state.value.drafts.isNotEmpty())
        store.updateConnectivity(true)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(store.state.value.requiresVerification)
        assertTrue(reads.api.requests.tryReceive().isFailure)
        reads.auth.signIn("B", "token-B")
        assertTrue(store.state.value.drafts.isEmpty())
        assertFalse(store.state.value.loaded)
        assertTrue(runCatching { store.peek() }.isFailure)
        advanceTimeBy(1_000); runCurrent()
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }
    @Test fun `out of bounds and incomplete raw input never dispatch or turn into zero`() = runTest {
        useScope(backgroundScope)
        load(snapshot = draftSnapshot)
        for ((weight, reps) in listOf("1.234" to "8", "1000.01" to "8", "-1" to "8",
            "." to "8", "1" to "", "1" to "1001", "1" to "2147483648")) {
            store.editSet("session", key, weight, reps)
            val failure = runCatching { store.flushEdits("session") }.exceptionOrNull() as OnlineFailure
            assertTrue(failure.reason is RemoteError.Validation)
            assertEquals(weight, store.state.value.drafts.getValue(key).weightText)
            assertEquals(reps, store.state.value.drafts.getValue(key).repsText)
            assertEquals("10.00", store.state.value.snapshot!!.exercises.single().sets.single().weight)
            assertTrue(reads.api.requests.tryReceive().isFailure)
        }
    }

    @Test fun `simultaneous cold reloads coalesce and review snapshot is not timer authority`() = runTest {
        val first = async { store.reload("session") }
        val second = async { store.reload("session") }
        val review = onlineJson.decodeFromString<SessionSnapshot>(OnlineFixtures.snapshot).copy(
            effectiveStatus = SessionStatus.EXPIRED, reviewRequired = true)
        reads.api.requests.receive().reply(onlineJson.encodeToString(SessionLookup.serializer(), SessionLookup(true, review)))
        assertEquals(first.await(), second.await())
        repeat(10_000) { assertNull(store.peek()) }
        assertTrue(reads.api.requests.tryReceive().isFailure)
        assertEquals(SessionSavePhase.CONFLICT, store.state.value.savePhase)
    }

    @Test fun `resolved finish publishes one scoped commit event and repeated finish does not refetch`() = runTest {
        val changes = OnlineMutationChanges(reads.auth)
        useScope(backgroundScope, changes)
        val observed = mutableListOf<OnlineMutationChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { changes.events.collect { observed += it } }
        load()
        val finish = async { runCatching { store.finish("session") } }
        val original = reads.api.requests.receive()
        original.loseResponse(); assertTrue(finish.await().isFailure)
        assertTrue(observed.isEmpty())
        val requestId = original.body.getValue("p_request_id").jsonPrimitive.content
        val resolve = async { store.resolvePending() }
        reads.api.requests.receive().reply(OnlineFixtures.operation(
            OnlineFixtures.receipt(requestId, 3, "finish_active_session", completed = true), requestId, "finish_active_session"))
        val receipt = resolve.await()!!
        assertEquals("workout", receipt.workoutId)
        assertEquals(listOf(WorkoutCommitted(reads.auth.capture().identity, requestId, "workout", OnlineFixtures.time)), observed)
        assertEquals(receipt, store.finish("session"))
        assertEquals(1, observed.size)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `flush follows confirmed set order and stops at first failed receipt`() = runTest {
        useScope(backgroundScope)
        val source = onlineJson.decodeFromString<SessionSnapshot>(draftSnapshot)
        val child = source.exercises.single()
        val second = child.sets.single().copy(id = "set-9", setNumber = 9)
        val snapshot = source.copy(exercises = listOf(child.copy(sets = child.sets + second)))
        load(snapshot = onlineJson.encodeToString(SessionSnapshot.serializer(), snapshot))
        store.editSet("session", SessionSetKey("child", "set-9"), "25", "8")
        store.editSet("session", key, "20", "8")
        val pause = async { runCatching { store.command("session", SessionCommand.Pause) } }
        val first = reads.api.requests.receive()
        assertEquals("set", first.body.getValue("p_command").jsonObject.getValue("set_id").jsonPrimitive.content)
        first.reply("""{"code":"PT409","message":"VERSION_CONFLICT","details":null,"hint":null}""", 409)
        assertTrue(pause.await().isFailure)
        assertEquals(2, store.state.value.drafts.size)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `cancel never flushes discarded raw text and unknown cancel retains it until receipt`() = runTest {
        useScope(backgroundScope)
        load(snapshot = draftSnapshot)
        store.editSet("session", key, "", "8")
        val cancel = async { runCatching { store.cancel("session") } }
        val original = reads.api.requests.receive()
        assertEquals("cancel_active_session", original.rpc)
        assertTrue(store.state.value.drafts.isNotEmpty())
        original.loseResponse(); assertTrue(cancel.await().isFailure)
        assertTrue(store.state.value.drafts.isNotEmpty())
        val retry = async { store.retry() }
        val replay = reads.api.requests.receive()
        assertEquals(original.body, replay.body)
        val requestId = replay.body.getValue("p_request_id").jsonPrimitive.content
        val terminal = onlineJson.decodeFromString<SessionSnapshot>(draftSnapshot).copy(
            version = 3, status = SessionStatus.CANCELLED, effectiveStatus = SessionStatus.CANCELLED, pausedAt = null)
        replay.reply(onlineJson.encodeToString(SessionMutationReceipt.serializer(), SessionMutationReceipt(
            requestId, replay.rpc, "session", 3, SessionStatus.CANCELLED, OnlineFixtures.time, terminal)))
        retry.await()
        assertTrue(store.state.value.drafts.isEmpty())
        assertNull(store.peek())
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `old session receipt cannot replace a subsequently observed active session`() = runTest {
        load()
        val command = async { runCatching { store.command("session", SessionCommand.Resume) } }
        val original = reads.api.requests.receive()
        original.loseResponse(); command.await()
        val replacement = onlineJson.decodeFromString<SessionSnapshot>(OnlineFixtures.snapshot).copy(id = "replacement", version = 1)
        load(snapshot = onlineJson.encodeToString(SessionSnapshot.serializer(), replacement))
        val requestId = original.body.getValue("p_request_id").jsonPrimitive.content
        val resolve = async { store.resolvePending() }
        reads.api.requests.receive().reply(OnlineFixtures.operation(OnlineFixtures.receipt(requestId, 3), requestId))
        resolve.await()
        assertNull(store.state.value.pendingRequestId)
        assertEquals("replacement", store.peek()!!.id)
        assertEquals(1L, store.peek()!!.version)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }
}
