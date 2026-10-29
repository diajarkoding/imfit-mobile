package com.diajarkoding.imfit.data.remote.online

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class G3ReadTablesTest {
    @Test fun `profile direct read is owner pinned restricted and bounded`() = runTest {
        val auth = OnlineAuth().also { it.signIn("A", "synthetic-token") }
        val client = HttpClient(MockEngine { request ->
            assertEquals("/rest/v1/profiles", request.url.encodedPath)
            assertEquals("eq.A", request.url.parameters["id"])
            assertEquals("id,name,email,birth_date,avatar_url", request.url.parameters["select"])
            assertEquals("Bearer synthetic-token", request.headers[HttpHeaders.Authorization])
            respond("""[{"id":"A","name":"Fixture","email":"fixture@example.invalid","birth_date":null,"avatar_url":null}]""", HttpStatusCode.OK)
        })
        try { assertEquals("Fixture", KtorReadTables(client, "http://127.0.0.1:54321", "public-key", auth).profile().name) }
        finally { client.close() }
    }

    @Test fun `direct profile rejects other owner and missing profile is error not empty identity`() = runTest {
        for (body in listOf("[]", """[{"id":"B","name":"Other","email":"fixture@example.invalid","birth_date":null,"avatar_url":null}]""")) {
            val auth = OnlineAuth().also { it.signIn("A", "synthetic-token") }
            val client = HttpClient(MockEngine { respond(body, HttpStatusCode.OK) })
            try {
                val failure = runCatching { KtorReadTables(client, "http://localhost:54321", "key", auth).profile() }.exceptionOrNull() as OnlineFailure
                assertEquals(if (body == "[]") RemoteError.NotFound else RemoteError.Protocol, failure.reason)
            } finally { client.close() }
        }
    }

    @Test fun `account ABA during direct response never publishes old owner data`() = runTest {
        val auth = OnlineAuth().also { it.signIn("A", "synthetic-token") }
        val client = HttpClient(MockEngine {
            auth.signIn("B", "B"); auth.signIn("A", "A-new")
            respond("""[{"id":"A","name":"Old","email":"fixture@example.invalid","birth_date":null,"avatar_url":null}]""", HttpStatusCode.OK)
        })
        try { assertTrue(runCatching { KtorReadTables(client, "http://localhost:54321", "key", auth).profile() }.exceptionOrNull() is StaleAuthException) }
        finally { client.close() }
    }

    @Test fun `detail exercise lookup never widens malformed input and rejects unexpected rows`() = runTest {
        val auth = OnlineAuth().also { it.signIn("A", "synthetic-token") }
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++
            assertEquals("in.(squat)", request.url.parameters["id"])
            assertEquals("eq.true", request.url.parameters["is_active"])
            assertEquals("100", request.url.parameters["limit"])
            respond("""[{"id":"not-requested","muscle_category_id":1,"name":"Fixture","description":"Synthetic","is_active":true}]""", HttpStatusCode.OK)
        })
        try {
            val tables = KtorReadTables(client, "http://localhost:54321", "key", auth)
            assertTrue(tables.exercises(emptyList()).isEmpty())
            assertTrue(runCatching { tables.exercises(listOf("bad),or=(id.neq.empty")) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(0, calls)
            assertEquals(RemoteError.Protocol, (runCatching { tables.exercises(listOf("squat")) }.exceptionOrNull() as OnlineFailure).reason)
        } finally { client.close() }
    }
}
