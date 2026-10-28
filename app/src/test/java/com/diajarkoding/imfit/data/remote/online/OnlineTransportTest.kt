package com.diajarkoding.imfit.data.remote.online

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class OnlineTransportTest {
    @Test fun `Ktor adapter preserves SQLSTATE details and explicitly pinned authorization`() = runTest {
        val engine = MockEngine { request ->
            assertEquals("https://example.invalid/rest/v1/rpc/update_active_session", request.url.toString())
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("Bearer captured-token", request.headers[HttpHeaders.Authorization])
            assertEquals("publishable-test", request.headers["apikey"])
            assertEquals(OnlineRpc.updateSession("request", "session", 1, SessionCommand.Pause).parameters,
                onlineJson.parseToJsonElement(request.body.toByteArray().decodeToString()))
            respond("""{"code":"PT409","message":"VERSION_CONFLICT","details":"{\"current_version\":2147483648}","hint":null}""",
                HttpStatusCode.Conflict, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        HttpClient(engine).use { client ->
            val transport = KtorOnlineTransport(client, "https://example.invalid", "publishable-test")
            val response = transport.post("update_active_session", OnlineRpc.updateSession("request", "session", 1, SessionCommand.Pause).parameters,
                RequestCredential(AuthIdentity("A", 1), "captured-token"))
            assertEquals(409, response.status)
            val error = mapRemoteError(response) as RemoteError.Conflict
            assertEquals("VERSION_CONFLICT", error.discriminator)
            assertEquals(2147483648L, error.details!!["current_version"]!!.jsonPrimitive.long)
        }
    }

    @Test fun `domain errors are not generic revision conflicts and engine errors never auto retry`() {
        fun error(status: Int, code: String, message: String) = mapRemoteError(RpcResponse(status,
            """{"code":"$code","message":"$message","details":null,"hint":null}"""))
        assertEquals(RemoteError.Validation("INVALID_CURSOR", null), error(400, "PT400", "INVALID_CURSOR"))
        listOf("IDEMPOTENCY_MISMATCH", "SESSION_REVIEW_REQUIRED", "STATE_CONFLICT", "SESSION_TERMINAL", "LEGACY_REVIEW_REQUIRED", "REVISION_CONFLICT").forEach {
            assertEquals(RemoteError.Conflict(it, null), error(409, "PT409", it))
        }
        assertEquals(RemoteError.NotFound, error(404, "PT404", "RESOURCE_NOT_FOUND"))
        assertEquals(RemoteError.Unauthenticated, error(401, "42501", "auth"))
        assertEquals(RemoteError.Forbidden, error(403, "42501", "auth"))
        assertEquals(RemoteError.ContractUnavailable, error(404, "PGRST202", "missing function"))
        assertEquals(RemoteError.Engine("40001"), error(500, "40001", "serialization failure"))
        assertEquals(RemoteError.Engine("40P01"), error(500, "40P01", "deadlock"))
        assertEquals(RemoteError.Protocol, mapRemoteError(RpcResponse(404, "gateway HTML, not an authoritative empty result")))
    }

    @Test fun `transport rejects plaintext remote URL and embedded credential URL`() {
        HttpClient(MockEngine { respond("{}") }).use { client ->
            assertThrows(IllegalArgumentException::class.java) { KtorOnlineTransport(client, "http://remote.invalid", "test") }
            assertThrows(IllegalArgumentException::class.java) { KtorOnlineTransport(client, "https://user:secret@example.invalid", "test") }
            KtorOnlineTransport(client, "http://127.0.0.1:54321", "test")
        }
    }
}
