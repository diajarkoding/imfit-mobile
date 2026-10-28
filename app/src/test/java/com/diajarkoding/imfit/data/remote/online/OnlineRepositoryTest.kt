package com.diajarkoding.imfit.data.remote.online

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineRepositoryTest {
    private val auth = OnlineAuth().also { it.signIn("A", "token-A") }
    private val api = FakeOnlineApi()
    private val repository = OnlineRepository(api, auth)
    private val command get() = OnlineRpc.updateSession("request", "session", 1, SessionCommand.Pause)

    private suspend fun reason(block: suspend () -> Any?): RemoteError = try {
        block(); error("Expected OnlineFailure")
    } catch (failure: OnlineFailure) { failure.reason }

    @Test fun `response loss blocks new mutation until original receipt is discovered`() = runTest {
        val call = OnlineRpc.finishSession("request", "session", 1)
        val send = async { reason { repository.execute(call) } }
        val request = api.requests.receive()
        val committed = OnlineFixtures.receipt(operation = call.name, completed = true)
        request.loseResponse()
        assertEquals(RemoteError.UnknownOutcome, send.await())
        assertEquals(RemoteError.UnresolvedMutation, reason { repository.execute(OnlineRpc.cancelSession("other", "session", 1)) })
        assertTrue(repository.confirmedSessions.value.isEmpty())
        val discovery = async { repository.resolve(call) }
        api.requests.receive().also {
            assertEquals("get_operation_status", it.rpc)
            it.reply(OnlineFixtures.operation(committed, operation = call.name))
        }
        val result = discovery.await()!!
        assertEquals("workout", result.workoutId)
        assertEquals(SessionStatus.COMPLETED, repository.confirmedSessions.value["session"]!!.status)
        val retry = async { repository.execute(call) }
        api.requests.receive().also { assertEquals(request.body, it.body); it.reply(committed) }
        assertEquals(result, retry.await())
    }

    @Test fun `NOT_FOUND is not permission to mint a fresh mutation`() = runTest {
        val send = async { reason { repository.execute(command) } }
        api.requests.receive().loseResponse()
        assertEquals(RemoteError.UnknownOutcome, send.await())
        val discovery = async { repository.resolve(command) }
        api.requests.receive().reply("""{"state":"NOT_FOUND","receipt":null}""")
        assertNull(discovery.await())
        assertEquals(RemoteError.UnresolvedMutation, reason { repository.execute(OnlineRpc.updateSession("new", "session", 1, SessionCommand.Resume)) })
        val retry = async { repository.execute(command) }
        api.requests.receive().reply(OnlineFixtures.receipt())
        assertEquals(2L, retry.await().version)
    }

    @Test fun `same request different CAS command resource or RPC never dispatches`() = runTest {
        val send = async { repository.execute(command) }
        api.requests.receive().reply(OnlineFixtures.receipt())
        send.await()
        listOf(
            OnlineRpc.updateSession("request", "session", 2, SessionCommand.Pause),
            OnlineRpc.updateSession("request", "session", 1, SessionCommand.Resume),
            OnlineRpc.finishSession("request", "session", 1),
            OnlineRpc.updateSession("request", "different", 1, SessionCommand.Pause),
        ).forEach { assertEquals(RemoteError.IdempotencyMismatch, reason { repository.execute(it) }) }
        assertTrue(api.requests.tryReceive().isFailure)
    }

    @Test fun `receipt replay cannot regress confirmed session version`() = runTest {
        for ((call, version) in listOf(command to 2L, OnlineRpc.updateSession("next", "session", 2, SessionCommand.Resume) to 3L, command to 2L)) {
            val send = async { repository.execute(call) }
            api.requests.receive().reply(OnlineFixtures.receipt(call.mutation!!.requestId, version))
            assertEquals(version, send.await().version)
        }
        assertEquals(3L, repository.confirmedSessions.value["session"]!!.version)
    }

    @Test fun `two mutations serialize and retain caller CAS without guessing or retry`() = runTest {
        val first = async { repository.execute(command) }
        val firstRequest = api.requests.receive()
        val second = async { reason { repository.execute(OnlineRpc.updateSession("next", "session", 1, SessionCommand.Resume)) } }
        runCurrent()
        assertTrue(api.requests.tryReceive().isFailure)
        firstRequest.reply(OnlineFixtures.receipt())
        first.await()
        api.requests.receive().also {
            assertEquals(JsonPrimitive(1), it.body["p_expected_version"])
            it.reply("""{"code":"PT409","message":"VERSION_CONFLICT","details":"{\"current_version\":2}","hint":null}""", 409)
        }
        val conflict = second.await() as RemoteError.Conflict
        assertEquals("VERSION_CONFLICT", conflict.discriminator)
        assertEquals(2, conflict.details!!["current_version"]!!.jsonPrimitive.int)
        assertTrue(api.requests.tryReceive().isFailure)
    }

    @Test fun `A B A makes an old uncancellable request stale and clears PII immediately`() = runTest {
        api.ignoreCancellation = true
        val firstEpoch = auth.capture().identity
        val old = async { try { repository.execute(command); false } catch (_: StaleAuthException) { true } }
        val pending = api.requests.receive()
        auth.signIn("B", "token-B")
        auth.signIn("A", "new-token-A")
        assertNotEquals(firstEpoch, auth.capture().identity)
        pending.reply(OnlineFixtures.receipt())
        assertTrue(old.await())
        assertTrue(repository.confirmedSessions.value.isEmpty())
        val fresh = async { repository.execute(command) }
        api.requests.receive().also { assertEquals("new-token-A", it.credential.accessToken); it.reply(OnlineFixtures.receipt()) }
        fresh.await()
        auth.signOut()
        assertTrue(repository.confirmedSessions.value.isEmpty())
    }

    @Test fun `token refresh keeps epoch but in flight credential remains pinned`() = runTest {
        val id = auth.capture().identity
        val send = async { repository.execute(command) }
        val pending = api.requests.receive()
        auth.refreshToken(id, "refreshed-token")
        assertEquals(id, auth.capture().identity)
        assertEquals("token-A", pending.credential.accessToken)
        pending.reply(OnlineFixtures.receipt())
        send.await()
        assertThrows(StaleAuthException::class.java) { auth.refreshToken(AuthIdentity("B", id.epoch), "wrong") }
    }

    @Test fun `cancellation is rethrown and ambiguous command stays blocked`() = runTest {
        val send = launch { repository.execute(command) }
        api.requests.receive()
        send.cancelAndJoin()
        assertTrue(send.isCancelled)
        assertEquals(RemoteError.UnresolvedMutation, reason { repository.execute(OnlineRpc.finishSession("next", "session", 1)) })
        val retry = async { repository.execute(command) }
        api.requests.receive().reply(OnlineFixtures.receipt())
        retry.await()
    }

    @Test fun `malformed success receipt is unknown not saved or safely absent`() = runTest {
        val send = async { reason { repository.execute(command) } }
        api.requests.receive().reply(OnlineFixtures.receipt(request = "wrong-request"))
        assertEquals(RemoteError.UnknownOutcome, send.await())
        assertTrue(repository.confirmedSessions.value.isEmpty())
        assertEquals(RemoteError.UnresolvedMutation, reason { repository.execute(OnlineRpc.finishSession("next", "session", 1)) })
    }

    @Test fun `read failures cannot turn into empty session or data fallback`() = runTest {
        val network = async { reason { repository.execute(OnlineRpc.activeSession()) } }
        api.requests.receive().loseResponse()
        assertEquals(RemoteError.Network, network.await())
        val invalid = async { reason { repository.execute(OnlineRpc.activeSession()) } }
        api.requests.receive().reply("""{"found":false,"session":${OnlineFixtures.snapshot}}""")
        assertEquals(RemoteError.Protocol, invalid.await())
    }

    @Test fun `wrong discovery resource is rejected and unknown remains blocked`() = runTest {
        val send = async { reason { repository.execute(command) } }
        api.requests.receive().loseResponse(); send.await()
        val discovery = async { reason { repository.resolve(command) } }
        api.requests.receive().reply(OnlineFixtures.operation(OnlineFixtures.receipt()).replace("\"resource_id\":\"session\"", "\"resource_id\":\"other\""))
        assertEquals(RemoteError.Protocol, discovery.await())
        assertEquals(RemoteError.UnresolvedMutation, reason { repository.execute(OnlineRpc.finishSession("next", "session", 1)) })
    }

    @Test fun `concurrent reads may finish in reverse order without sharing credentials`() = runTest {
        val first = async { repository.execute(OnlineRpc.activeSession()) }
        val old = api.requests.receive()
        val second = async { repository.execute(OnlineRpc.activeSession("session")) }
        api.requests.receive().reply("""{"found":true,"session":${OnlineFixtures.snapshot}}""")
        assertTrue(second.await().found)
        old.reply("""{"found":false,"session":null}""")
        assertFalse(first.await().found)
    }

    @Test fun `gateway response is ambiguous for writes and never triggers automatic retry`() = runTest {
        val send = async { reason { repository.execute(command) } }
        api.requests.receive().reply("<html>gateway</html>", 404)
        assertEquals(RemoteError.UnknownOutcome, send.await())
        assertTrue(api.requests.tryReceive().isFailure)
        assertEquals(RemoteError.UnresolvedMutation, reason { repository.execute(OnlineRpc.finishSession("new", "session", 1)) })
    }

    @Test fun `discovery requires original in memory intent not a guessed reconstructed payload`() = runTest {
        assertEquals(RemoteError.IdempotencyMismatch, reason { repository.resolve(command) })
        assertTrue(api.requests.tryReceive().isFailure)
    }

    @Test fun `ambiguous create cannot bypass recovery with a different resource and request ID`() = runTest {
        val create = OnlineRpc.createSession("request", "session", "template", 1)
        val send = async { reason { repository.execute(create) } }
        api.requests.receive().loseResponse()
        assertEquals(RemoteError.UnknownOutcome, send.await())
        assertEquals(RemoteError.UnresolvedMutation, reason {
            repository.execute(OnlineRpc.createSession("new-request", "new-session", "template", 1))
        })
        assertTrue(api.requests.tryReceive().isFailure)
    }

    @Test fun `closing during uncancellable response prevents publication`() = runTest {
        api.ignoreCancellation = true
        val send = async { try { repository.execute(command); false } catch (_: CancellationException) { true } }
        val pending = api.requests.receive()
        repository.close()
        pending.reply(OnlineFixtures.receipt())
        assertTrue(send.await())
        assertTrue(repository.confirmedSessions.value.isEmpty())
    }
}
