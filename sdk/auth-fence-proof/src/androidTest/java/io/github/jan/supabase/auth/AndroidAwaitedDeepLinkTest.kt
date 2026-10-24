package io.github.jan.supabase.auth

import android.content.Intent
import android.net.Uri
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual Android Intent/Uri handling, no browser launch, credentials or real backend. */
class AndroidAwaitedDeepLinkTest {
    private class Fixture {
        var requests = 0
        var pause = false
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var body = """{"access_token":"synthetic-A","refresh_token":"refresh-A","token_type":"bearer","expires_in":86400,"user":{"aud":"authenticated","id":"A"}}"""
        val client = createSupabaseClient("https://deeplink-proof.invalid", "synthetic-key") {
            defaultLogLevel = LogLevel.NONE
            httpEngine = MockEngine { request ->
                check(request.url.host == "deeplink-proof.invalid")
                requests++
                if (pause) { entered.complete(Unit); release.await() }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            install(Auth) { minimalSettings(); scheme = "proof"; host = "callback"; flowType = FlowType.PKCE }
        }
        val auth get() = client.auth
        suspend fun flow(): AuthExternalFlow {
            val flow = auth.beginExternalFlow()
            auth.getOAuthUrl(Google, flow = flow)
            return flow
        }
        fun intent(flow: AuthExternalFlow, query: String = "state=${flow.state}&code=synthetic-code") =
            Intent(Intent.ACTION_VIEW, Uri.parse("proof://callback?$query"))
    }

    private fun scenario(block: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking<Unit> {
        withTimeout(15_000) {
            val f = Fixture()
            try { f.auth.awaitInitialization(); block(f) }
            finally { f.release.complete(Unit); withContext(NonCancellable) { f.client.close() } }
        }
    }

    @Test fun pkceHandlerAwaitsImportAndDeliversOnce() = scenario { f ->
        val flow = f.flow(); var delivered = 0
        f.client.handleDeeplinks(f.intent(flow), flow) { delivered++; assertSame(it, f.auth.currentSessionOrNull()) }
        assertEquals(1, delivered)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        try { f.client.handleDeeplinks(f.intent(flow), flow) { delivered++ }; fail() }
        catch (_: StaleAuthOperationException) { }
        assertEquals(1, delivered)
        assertEquals(1, f.requests)
    }

    @Test fun handlerDoesNotCompleteOrDeliverBeforeImportFinishes() = scenario { f ->
        val flow = f.flow(); f.pause = true; var delivered = 0
        val pending = async { f.client.handleDeeplinks(f.intent(flow), flow) { delivered++ } }
        f.entered.await(); assertFalse(pending.isCompleted); assertEquals(0, delivered)
        assertNull(f.auth.currentSessionOrNull())
        f.release.complete(Unit); pending.await()
        assertEquals(1, delivered)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
    }

    @Test fun missingOrDuplicateStateRejectsBeforeHttp() = scenario { f ->
        val flow = f.flow()
        for (query in listOf("code=synthetic-code", "code=synthetic-code&state=${flow.state}&state=${flow.state}")) {
            try { f.client.handleDeeplinks(f.intent(flow, query), flow) { fail() }; fail() }
            catch (_: IllegalStateException) { }
        }
        assertEquals(0, f.requests)
        assertNotNull(f.auth.codeVerifierCache.loadCodeVerifier())
    }

    @Test fun wrongHostOrSchemeDoesNotImportOrConsumeFlow() = scenario { f ->
        val flow = f.flow()
        for (url in listOf("other://callback", "proof://wrong-host")) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("$url?state=${flow.state}&code=synthetic-code"))
            f.client.handleDeeplinks(intent, flow) { fail() }
        }
        assertEquals(0, f.requests)
        assertNotNull(f.auth.codeVerifierCache.loadCodeVerifier())
    }

    @Test fun delayedAndroidCallbackCannotPublishSuccessorOrDeliverItAsOldResult() = scenario { f ->
        val flow = f.flow(); f.pause = true
        val pending = async {
            try { f.client.handleDeeplinks(f.intent(flow), flow) { fail() }; fail() }
            catch (_: StaleAuthOperationException) { }
        }
        f.entered.await(); f.auth.importSession(syntheticSession("B"), autoRefresh = false)
        f.release.complete(Unit); pending.await()
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
    }

    @Test fun implicitAndroidHandlerImportsBeforeCallback() = scenario { f ->
        f.auth.config.flowType = FlowType.IMPLICIT
        f.body = """{"aud":"authenticated","id":"A"}"""
        val flow = f.flow(); var delivered = 0
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("proof://callback#access_token=synthetic-A&refresh_token=refresh-A&expires_in=86400&token_type=bearer&state=${flow.state}"))
        f.client.handleDeeplinks(intent, flow) { delivered++; assertSame(it, f.auth.currentSessionOrNull()) }
        assertEquals(1, delivered)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
    }

    @Test fun missingBlankOrDuplicateCodeIsTypedUnverifiedBeforeHttp() = scenario { f ->
        val flow = f.flow(); var delivered = 0
        val verifier = f.auth.codeVerifierCache.loadCodeVerifier()
        for (query in listOf("state=${flow.state}", "state=${flow.state}&code=", "state=${flow.state}&code=first&code=second")) {
            try { f.client.handleDeeplinks(f.intent(flow, query), flow) { delivered++ }; fail() }
            catch (failure: UnverifiedAuthFlowException) {
                assertTrue(failure.reason in setOf(AuthFlowRejection.STATE_MISMATCH, AuthFlowRejection.NOT_PREPARED))
            }
        }
        assertEquals(0, delivered)
        assertEquals(0, f.requests)
        assertEquals(verifier, f.auth.codeVerifierCache.loadCodeVerifier())
        assertNull(f.auth.currentSessionOrNull())
    }

    @Test fun uriEncodedStateMustRoundTripExactlyWithItsRetainedHandle() = scenario { f ->
        val flow = f.flow(); var delivered = 0
        val uri = Uri.Builder().scheme("proof").authority("callback")
            .appendQueryParameter("state", flow.state).appendQueryParameter("code", "synthetic-code").build()
        assertEquals(listOf(flow.state), uri.getQueryParameters("state"))
        f.client.handleDeeplinks(Intent(Intent.ACTION_VIEW, uri), flow) { delivered++; assertSame(it, f.auth.currentSessionOrNull()) }
        assertEquals(1, delivered)
        assertEquals(1, f.requests)
    }
}
