package com.diajarkoding.imfit.data.remote.online

import com.diajarkoding.imfit.domain.model.ProfileField
import com.diajarkoding.imfit.domain.model.ProfilePatch
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.*
import io.ktor.http.*
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ProfilePatchTransportTest {
    private val row = """{"id":"A","name":"Server name","email":"fixture@example.invalid","birth_date":null,"avatar_url":null}"""

    @Test fun `patch distinguishes omitted fields from explicit null and accepts returned server row`() = runTest {
        val auth = OnlineAuth().also { it.signIn("A", "synthetic-token") }
        val client = HttpClient(MockEngine { request ->
            if (request.url.parameters["limit"] != null && request.url.parameters["order"] == null) {
                return@MockEngine respond(
                    """{"code":"PGRST109","message":"A limited update requires ordering on a unique column"}""",
                    HttpStatusCode.BadRequest,
                )
            }
            assertEquals(HttpMethod.Patch, request.method)
            assertEquals("/rest/v1/profiles", request.url.encodedPath)
            assertEquals("eq.A", request.url.parameters["id"])
            assertEquals("id,name,email,birth_date,avatar_url", request.url.parameters["select"])
            assertEquals("return=representation", request.headers["Prefer"])
            assertEquals("Bearer synthetic-token", request.headers[HttpHeaders.Authorization])
            val body = onlineJson.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals(setOf("name", "avatar_url"), body.keys)
            assertEquals("Input name", body.getValue("name").jsonPrimitive.content)
            assertEquals(JsonNull, body["avatar_url"])
            respond("[$row]", HttpStatusCode.OK)
        })
        try {
            val result = KtorReadTables(client, "http://127.0.0.1:54321", "public-key", auth)
                .updateProfile(ProfilePatch(name = ProfileField.Value("Input name"), avatarPath = ProfileField.Value(null)))
            assertEquals("Server name", result.name)
            assertNull(result.avatarUrl)
        } finally { client.close() }
    }

    @Test fun `empty patch performs authoritative restricted GET without write`() = runTest {
        val auth = OnlineAuth().also { it.signIn("A", "token") }
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++
            assertEquals(HttpMethod.Get, request.method)
            assertNull(request.headers["Prefer"])
            assertEquals("eq.A", request.url.parameters["id"])
            respond("[$row]", HttpStatusCode.OK)
        })
        try {
            assertEquals("Server name", KtorReadTables(client, "http://localhost:54321", "key", auth).updateProfile(ProfilePatch()).name)
            assertEquals(1, calls)
        } finally { client.close() }
    }

    @Test fun `patch rejects missing wrong-owner duplicate and malformed returned rows`() = runTest {
        val invalid = listOf("[]", "[${row.replace("\"A\"", "\"B\"")}]", "[$row,$row]",
            "[{\"id\":\"A\",\"name\":\"Missing fields\"}]", "[${row.replace("Server name", " ")}]",
            "[${row.replace("\"birth_date\":null", "\"birth_date\":\"2026-02-30\"")}]")
        for (body in invalid) {
            val auth = OnlineAuth().also { it.signIn("A", "token") }
            val client = HttpClient(MockEngine { respond(body, HttpStatusCode.OK) })
            try {
                val error = runCatching { KtorReadTables(client, "http://localhost:54321", "key", auth)
                    .updateProfile(ProfilePatch(name = ProfileField.Value("Input"))) }.exceptionOrNull()
                assertTrue("Invalid response must not be profile success", error is OnlineFailure)
            } finally { client.close() }
        }
    }

    @Test fun `nullable birth date is sent as null while avatar remains omitted`() = runTest {
        val auth = OnlineAuth().also { it.signIn("A", "token") }
        val client = HttpClient(MockEngine { request ->
            val body = onlineJson.parseToJsonElement((request.body as TextContent).text).jsonObject
            assertEquals(buildJsonObject { put("birth_date", JsonNull) }, body)
            respond("[$row]", HttpStatusCode.OK)
        })
        try { KtorReadTables(client, "http://localhost:54321", "key", auth)
            .updateProfile(ProfilePatch(birthDate = ProfileField.Value(null))) }
        finally { client.close() }
    }

    @Test fun `account ABA and cancellation during PATCH cannot return profile success`() = runTest {
        for (cancel in listOf(false, true)) {
            val auth = OnlineAuth().also { it.signIn("A", "token") }
            val client = HttpClient(MockEngine {
                if (cancel) throw CancellationException("fixture cancellation")
                auth.signIn("B", "B"); auth.signIn("A", "new-A")
                respond("[$row]", HttpStatusCode.OK)
            })
            try {
                assertTrue(runCatching { KtorReadTables(client, "http://localhost:54321", "key", auth)
                    .updateProfile(ProfilePatch(name = ProfileField.Value("Input"))) }.exceptionOrNull() is CancellationException)
            } finally { client.close() }
        }
    }

    @Test fun `avatar writes accept only owner canonical path and never signed URL`() = runTest {
        val auth = OnlineAuth().also { it.signIn("A", "token") }
        var calls = 0
        val client = HttpClient(MockEngine { request ->
            calls++
            assertEquals("A/photo.jpg", onlineJson.parseToJsonElement((request.body as TextContent).text).jsonObject["avatar_url"]!!.jsonPrimitive.content)
            respond("[${row.replace("\"avatar_url\":null", "\"avatar_url\":\"A/photo.jpg\"")}]", HttpStatusCode.OK)
        })
        try {
            val tables = KtorReadTables(client, "http://localhost:54321", "key", auth)
            for (path in listOf("B/photo.jpg", "https://signed.invalid/photo?token=secret", "A/../photo.jpg")) {
                assertTrue(runCatching { tables.updateProfile(ProfilePatch(avatarPath = ProfileField.Value(path))) }.exceptionOrNull() is IllegalArgumentException)
            }
            assertEquals(0, calls)
            assertEquals("A/photo.jpg", tables.updateProfile(ProfilePatch(avatarPath = ProfileField.Value("A/photo.jpg"))).avatarUrl)
            assertEquals(1, calls)
        } finally { client.close() }
    }
}
