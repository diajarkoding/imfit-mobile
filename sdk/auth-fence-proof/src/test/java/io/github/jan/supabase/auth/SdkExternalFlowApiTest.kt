package io.github.jan.supabase.auth

import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.providers.builtin.SSO
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.ContinuationInterceptor

/** Proof-only API contract; actual SDK serialization, synthetic transport, no live state-echo claim. */
class SdkExternalFlowApiTest {
    private class Store : SessionManager {
        var value: UserSession? = null
        var saves = 0
        var deletes = 0
        var failSave = false
        override suspend fun loadSession() = value
        override suspend fun saveSession(session: UserSession) {
            saves++
            if (failSave) throw java.io.IOException("synthetic session persistence failure")
            value = session
        }
        override suspend fun deleteSession() { deletes++; value = null }
    }

    private data class Sent(val url: Url, val body: JsonObject, val headerOwner: String)

    private class Fixture(dispatcher: CoroutineDispatcher) {
        val store = Store()
        val verifier = MemoryCodeVerifierCache("legacy-preserved")
        var requests = 0
        val sent = mutableListOf<Sent>()
        var pause = false
        var failToken = false
        var failInitiation = false
        var replyOwner = "A"
        var signupSession = false
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = createSupabaseClient("https://external-api-proof.invalid", "synthetic-key") {
            defaultLogLevel = LogLevel.NONE
            httpEngine = MockEngine { request ->
                check(request.url.host == "external-api-proof.invalid")
                requests++
                val bytes = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
                val payload = bytes?.takeIf { it.isNotEmpty() }?.let { Json.parseToJsonElement(it).jsonObject } ?: buildJsonObject { }
                sent += Sent(request.url, payload, when (request.headers[HttpHeaders.Authorization]) {
                    "Bearer synthetic-A" -> "A"
                    "Bearer synthetic-B" -> "B"
                    null -> "NONE"
                    else -> "OTHER"
                })
                if (pause) { entered.complete(Unit); release.await() }
                if (failInitiation && !request.url.encodedPath.endsWith("/token")) {
                    return@MockEngine respond("{}", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                if (failToken && request.url.encodedPath.endsWith("/token")) {
                    return@MockEngine respond("{}", HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
                }
                val body = when {
                    request.url.encodedPath.endsWith("/token") -> SESSION_A
                    request.url.encodedPath.endsWith("/signup") && signupSession -> SESSION_A
                    request.url.encodedPath.endsWith("/user") || request.url.encodedPath.endsWith("/signup") -> USER_A
                    request.url.encodedPath.endsWith("/sso") -> """{"url":"https://provider.invalid/synthetic"}"""
                    else -> "{}"
                }
                respond(body.replace("\"id\":\"A\"", "\"id\":\"$replyOwner\""), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            install(Auth) {
                minimalSettings(sessionManager = store, autoSaveToStorage = true)
                coroutineDispatcher = dispatcher
                codeVerifierCache = verifier
                scheme = "imfit"; host = "auth"
                flowType = FlowType.PKCE
            }
        }
        val auth get() = client.auth as AuthImpl
        suspend fun owner(owner: String) = auth.importSession(syntheticSession(owner), autoRefresh = false)
        suspend fun oauth(): AuthExternalFlow {
            val flow = auth.beginExternalFlow()
            val prepared = auth.withExternalFlow(flow) { getOAuthUrl(Google) }
            assertSame(flow, prepared.flow)
            return flow
        }
    }

    private fun scenario(test: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking<Unit> {
        withTimeout(15_000) {
            val f = Fixture(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
            try { f.auth.awaitInitialization(); f.owner("A"); test(f) }
            finally { f.release.complete(Unit); withContext(NonCancellable) { f.client.close() } }
        }
    }

    private suspend fun missing(f: Fixture, action: suspend () -> Unit) {
        try { action(); fail("Missing external provenance must be rejected") }
        catch (rejected: UnverifiedAuthFlowException) { assertEquals(AuthFlowRejection.MISSING_PROVENANCE, rejected.reason) }
        assertEquals(0, f.requests)
        assertEquals("legacy-preserved", f.verifier.loadCodeVerifier())
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        assertEquals(1, f.store.saves)
        assertEquals(0, f.store.deletes)
    }

    private suspend fun rejected(reason: AuthFlowRejection, action: suspend () -> Unit) {
        try { action(); fail("Unverified external flow accepted") }
        catch (failure: UnverifiedAuthFlowException) { assertEquals(reason, failure.reason) }
    }

    private suspend fun stale(action: suspend () -> Unit) {
        try { action(); fail("Stale flow accepted") }
        catch (_: StaleAuthOperationException) { }
    }

    private fun assertRedirect(state: String, target: String) {
        val redirect = Url(target)
        assertEquals(listOf(state), redirect.parameters.getAll("state"))
        assertEquals("imfit", redirect.protocol.name)
        assertEquals("auth", redirect.host)
    }

    private fun fragment(flow: AuthExternalFlow) = "access_token=synthetic-A&refresh_token=refresh-A&expires_in=86400&token_type=bearer&state=${flow.state}"

    @Test fun rawPkceWithoutHandleCannotReadVerifierSendOrPublish() = scenario { f ->
        missing(f) { f.auth.exchangeCodeForSession("synthetic-code") }
    }

    @Test fun oauthUrlCannotCreateAnInaccessibleImplicitHandle() = scenario { f ->
        missing(f) { f.auth.getOAuthUrl(Google) }
    }

    @Test fun ssoInitiationWithoutHandleCannotPersistVerifierOrSend() = scenario { f ->
        missing(f) { f.auth.retrieveSSOUrl { domain = "fixture.invalid" } }
    }

    @Test fun recoveryInitiationWithoutHandleCannotPersistVerifierOrSend() = scenario { f ->
        missing(f) { f.auth.resetPasswordForEmail("fixture@example.invalid") }
    }

    @Test fun otpInitiationWithoutHandleCannotPersistVerifierOrSend() = scenario { f ->
        missing(f) { f.auth.signInWith(OTP) { email = "fixture@example.invalid" } }
    }

    @Test fun signupInitiationWithoutHandleCannotPersistVerifierOrSend() = scenario { f ->
        missing(f) { f.auth.signUpWith(Email) { email = "fixture@example.invalid"; password = "synthetic" } }
    }

    @Test fun linkInitiationWithoutHandleCannotSendOrOpenBrowser() = scenario { f ->
        missing(f) { f.auth.linkIdentity(Google) { automaticallyOpenUrl = false } }
    }

    @Test fun emailChangeWithoutHandleCannotSendOrPublish() = scenario { f ->
        missing(f) { f.auth.updateUser { email = "changed@example.invalid" } }
    }

    @Test fun explicitOAuthReturnsTheSameHandleAndExactlyBoundEncodedState() = scenario { f ->
        val flow = f.auth.beginExternalFlow()
        val prepared = f.auth.withExternalFlow(flow) { getOAuthUrl(Google) }
        val url = Url(prepared.result)
        assertSame(flow, prepared.flow)
        assertEquals(listOf(flow.state), url.parameters.getAll("state"))
        assertRedirect(flow.state, url.parameters["redirect_to"]!!)
        assertEquals(generateCodeChallenge(f.verifier.loadCodeVerifier()!!), url.parameters["code_challenge"])
        assertEquals(0, f.requests)
        assertEquals(1, f.store.saves)
    }

    @Test fun explicitRawExchangePublishesOnceAndRejectsReplayBeforeHttp() = scenario { f ->
        val flow = f.oauth()
        val session = f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state)
        assertSame(session, f.auth.currentSessionOrNull())
        assertSame(session, f.store.value)
        assertEquals(2, f.store.saves)
        assertEquals(1, f.requests)
        assertNull(f.verifier.loadCodeVerifier())
        stale { f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state) }
        assertEquals(1, f.requests)
    }

    @Test fun readOnlyExchangeConsumesExactlyItsVerifierWithoutPublishingOrRetiringLease() = scenario { f ->
        val lease = f.auth.sessionAuthority.lease()
        val flow = f.oauth()
        assertEquals("A", f.auth.exchangeCodeForSession("synthetic-code", false, flow, flow.state).user!!.id)
        assertEquals(1, f.store.saves)
        f.auth.sessionAuthority.commit(lease) { }
        assertNull(f.verifier.loadCodeVerifier())
        stale { f.auth.exchangeCodeForSession("synthetic-code", false, flow, flow.state) }
        assertEquals(1, f.requests)
    }

    @Test fun missingOrWrongCallbackStateCannotReadPublishOrConsume() = scenario { f ->
        val flow = f.oauth()
        val verifier = f.verifier.loadCodeVerifier()
        for (state in listOf(null, "other-state")) {
            rejected(AuthFlowRejection.STATE_MISMATCH) { f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = state) }
        }
        assertEquals(0, f.requests)
        assertEquals(verifier, f.verifier.loadCodeVerifier())
        assertEquals(1, f.store.saves)
    }

    @Test fun unpreparedHandleDoesNotAuthorizeExchangeOfCachedLegacyVerifier() = scenario { f ->
        val flow = f.auth.beginExternalFlow()
        rejected(AuthFlowRejection.NOT_PREPARED) { f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state) }
        assertEquals(0, f.requests)
        assertEquals("legacy-preserved", f.verifier.loadCodeVerifier())
    }

    @Test fun verifierMismatchIsRejectedBeforeHttpWithoutRepairDelete() = scenario { f ->
        val flow = f.oauth()
        f.verifier.saveCodeVerifier("successor-verifier")
        rejected(AuthFlowRejection.VERIFIER_MISMATCH) { f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state) }
        assertEquals(0, f.requests)
        assertEquals("successor-verifier", f.verifier.loadCodeVerifier())
    }

    @Test fun foreignAuthorityHandleCannotAuthorizeLocalExchange() = scenario { f ->
        val foreign = Fixture(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
        try {
            foreign.auth.awaitInitialization(); foreign.owner("A")
            val flow = foreign.oauth()
            stale { f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state) }
            assertEquals(0, f.requests)
            assertEquals(0, foreign.requests)
            assertEquals("legacy-preserved", f.verifier.loadCodeVerifier())
        } finally { foreign.client.close() }
    }

    @Test fun equivalentFieldsDoNotMakeAnUnregisteredHandleTrusted() = scenario { f ->
        val real = f.oauth()
        val forged = AuthExternalFlow(real.call, real.state, real.flowType)
        stale { f.auth.exchangeCodeForSession("synthetic-code", flow = forged, state = forged.state) }
        assertEquals(0, f.requests)
        assertEquals(1, f.store.saves)
    }

    @Test fun replacedFlowCannotConsumeNewFlowVerifierWithoutAccountSwitch() = scenario { f ->
        val old = f.oauth()
        f.oauth()
        val latest = f.verifier.loadCodeVerifier()
        stale { f.auth.exchangeCodeForSession("synthetic-code", flow = old, state = old.state) }
        assertEquals(latest, f.verifier.loadCodeVerifier())
        assertEquals(0, f.requests)
    }

    @Test fun sameOwnerAccountABACannotReuseTheInitiationEpoch() = scenario { f ->
        val old = f.oauth()
        f.owner("B"); f.owner("A")
        val current = f.auth.sessionAuthority.lease()
        stale { f.auth.exchangeCodeForSession("synthetic-code", flow = old, state = old.state) }
        f.auth.sessionAuthority.commit(current) { }
        assertEquals(3, f.store.saves)
        assertEquals(0, f.requests)
    }

    @Test fun successorDuringExchangeCannotReceiveOldSessionOrLoseVerifier() = scenario { f ->
        val flow = f.oauth(); f.pause = true
        val old = async { stale { f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state) } }
        f.entered.await(); f.owner("B"); f.verifier.saveCodeVerifier("successor-verifier")
        f.release.complete(Unit); old.await()
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
        assertEquals("successor-verifier", f.verifier.loadCodeVerifier())
        assertEquals(2, f.store.saves)
        assertEquals(0, f.sent.count { it.headerOwner == "B" })
    }

    @Test fun duplicateCallbackWhileRunningStartsOnlyOneHttpRequest() = scenario { f ->
        val flow = f.oauth(); f.pause = true
        var deliveries = 0
        val first = async { f.auth.importExternalCode(flow, flow.state, "synthetic-code") { deliveries++ } }
        f.entered.await()
        rejected(AuthFlowRejection.ALREADY_IN_FLIGHT) { f.auth.importExternalCode(flow, flow.state, "synthetic-code") { deliveries++ } }
        assertEquals(1, f.requests)
        f.release.complete(Unit); first.await()
        assertEquals(1, deliveries)
        assertEquals(2, f.store.saves)
    }

    @Test fun cancelledCallbackReleasesItsReservationWithoutDeletingVerifier() = scenario { f ->
        val flow = f.oauth(); f.pause = true
        val verifier = f.verifier.loadCodeVerifier()
        val first = async { f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state) }
        f.entered.await(); first.cancelAndJoin()
        assertEquals(verifier, f.verifier.loadCodeVerifier())
        assertEquals(1, f.store.saves)
        f.pause = false
        f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state)
        assertEquals(2, f.requests)
        assertEquals(2, f.store.saves)
    }

    @Test fun transportFailureDoesNotAutomaticallyRetryOrConsumeTheHandle() = scenario { f ->
        val flow = f.oauth(); f.failToken = true
        val verifier = f.verifier.loadCodeVerifier()
        try { f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state); fail() }
        catch (_: io.github.jan.supabase.exceptions.RestException) { }
        assertEquals(1, f.requests)
        assertEquals(verifier, f.verifier.loadCodeVerifier())
        assertEquals(1, f.store.saves)
        f.failToken = false
        f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state)
        assertEquals(2, f.requests)
    }

    @Test fun changingFlowTypeDoesNotReinterpretAnAlreadyPreparedHandle() = scenario { f ->
        val flow = f.oauth()
        val verifier = f.verifier.loadCodeVerifier()
        f.auth.config.flowType = FlowType.IMPLICIT
        rejected(AuthFlowRejection.FLOW_TYPE_MISMATCH) { f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state) }
        assertEquals(0, f.requests)
        assertEquals(verifier, f.verifier.loadCodeVerifier())
    }

    @Test fun implicitCallbackPublishesBeforeDeliveryAndCannotReplay() = scenario { f ->
        f.auth.config.flowType = FlowType.IMPLICIT
        val flow = f.oauth()
        var deliveries = 0
        f.auth.importExternalFragment(flow, fragment(flow)) {
            deliveries++; assertSame(it, f.auth.currentSessionOrNull()); assertSame(it, f.store.value)
        }
        stale { f.auth.importExternalFragment(flow, fragment(flow)) { deliveries++ } }
        assertEquals(1, deliveries)
        assertEquals(1, f.requests)
        assertEquals("legacy-preserved", f.verifier.loadCodeVerifier())
    }

    @Test fun implicitDuplicateStateDoesNotFetchUserOrConsumeAnything() = scenario { f ->
        f.auth.config.flowType = FlowType.IMPLICIT
        val flow = f.oauth()
        rejected(AuthFlowRejection.STATE_MISMATCH) { f.auth.importExternalFragment(flow, fragment(flow) + "&state=${flow.state}") { fail() } }
        assertEquals(0, f.requests)
        assertEquals(1, f.store.saves)
    }

    @Test fun pkceHandleCannotAuthorizeImplicitFragmentImport() = scenario { f ->
        val flow = f.oauth()
        rejected(AuthFlowRejection.FLOW_TYPE_MISMATCH) { f.auth.importExternalFragment(flow, fragment(flow)) { fail() } }
        assertEquals(0, f.requests)
        assertEquals(1, f.store.saves)
    }

    @Test fun blankCodeIsRejectedWithoutARequest() = scenario { f ->
        val flow = f.oauth()
        rejected(AuthFlowRejection.NOT_PREPARED) { f.auth.exchangeCodeForSession(" ", flow = flow, state = flow.state) }
        assertEquals(0, f.requests)
    }

    @Test fun explicitCoroutineContextDoesNotMakeLegacyRawCallbackTrusted() = scenario { f ->
        val flow = f.oauth()
        f.auth.withExternalFlow(flow) {
            rejected(AuthFlowRejection.MISSING_PROVENANCE) { exchangeCodeForSession("synthetic-code") }
        }
        assertEquals(0, f.requests)
        assertEquals(1, f.store.saves)
    }

    @Test fun malformedRedirectsAreRejectedBeforeVerifierPersistence() = scenario { f ->
        for (target in listOf("not-a-uri", "javascript:synthetic", "imfit://wrong", "imfit://auth#fragment")) {
            val flow = f.auth.beginExternalFlow()
            rejected(AuthFlowRejection.INVALID_REDIRECT) { f.auth.getOAuthUrl(Google, redirectUrl = target, flow = flow) }
        }
        assertEquals("legacy-preserved", f.verifier.loadCodeVerifier())
        assertEquals(0, f.requests)
    }

    @Test fun redirectStateCannotBeSilentlyOverwritten() = scenario { f ->
        val flow = f.auth.beginExternalFlow()
        rejected(AuthFlowRejection.STATE_MISMATCH) { f.auth.getOAuthUrl(Google, redirectUrl = "imfit://auth?state=foreign", flow = flow) }
        assertEquals("legacy-preserved", f.verifier.loadCodeVerifier())
        assertEquals(0, f.requests)
    }

    @Test fun oauthOptionsCannotOverrideBoundStateOrInjectAnotherQueryParameter() = scenario { f ->
        val flow = f.auth.beginExternalFlow()
        val url = Url(f.auth.getOAuthUrl(Google, flow = flow) {
            queryParams["state"] = "foreign&code=synthetic"
            queryParams["provider"] = "foreign"
            scopes += "email&code=synthetic"
        })
        assertEquals(listOf(flow.state), url.parameters.getAll("state"))
        assertEquals("google", url.parameters["provider"])
        assertNull(url.parameters["code"])
        assertEquals("email&code=synthetic", url.parameters["scopes"])
    }

    @Test fun replacedInitiationCannotReturnAResultLabelledAsItsOldHandle() = scenario { f ->
        val flow = f.auth.beginExternalFlow()
        stale { f.auth.withExternalFlow(flow) { beginExternalFlow(); "obsolete-result" } }
        assertEquals(0, f.requests)
    }

    @Test fun ssoInitiationReturnsTheHandleWhoseStateAndChallengeWereSent() = scenario { f ->
        val flow = f.auth.beginExternalFlow()
        val prepared = f.auth.withExternalFlow(flow) { retrieveSSOUrl { domain = "fixture.invalid" } }
        assertSame(flow, prepared.flow)
        assertEquals("https://provider.invalid/synthetic", prepared.result.url)
        assertRedirect(flow.state, f.sent.single().body.getValue("redirect_to").jsonPrimitive.content)
        assertEquals(generateCodeChallenge(f.verifier.loadCodeVerifier()!!), f.sent.single().body.getValue("code_challenge").jsonPrimitive.content)
        f.auth.exchangeCodeForSession("synthetic-code", flow = prepared.flow, state = flow.state)
        assertEquals(2, f.requests)
        assertEquals(2, f.store.saves)
    }

    @Test fun recoveryInitiationTransportsTheSameHandleAndStateInRedirect() = scenario { f ->
        val flow = f.auth.beginExternalFlow()
        val prepared = f.auth.withExternalFlow(flow) { resetPasswordForEmail("fixture@example.invalid") }
        assertSame(flow, prepared.flow)
        assertRedirect(flow.state, f.sent.single().url.parameters["redirect_to"]!!)
        assertNotNull(f.sent.single().body["code_challenge"])
        f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state)
        assertEquals(2, f.store.saves)
    }

    @Test fun otpInitiationTransportsTheSameHandleBeforeCallback() = scenario { f ->
        val flow = f.auth.beginExternalFlow()
        val prepared = f.auth.withExternalFlow(flow) { signInWith(OTP) { email = "fixture@example.invalid" } }
        assertSame(flow, prepared.flow)
        assertRedirect(flow.state, f.sent.single().url.parameters["redirect_to"]!!)
        assertEquals(1, f.store.saves)
        f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state)
        assertEquals(2, f.store.saves)
    }

    @Test fun signupWithoutImmediateSessionReturnsTheHandleForConfirmation() = scenario { f ->
        val flow = f.auth.beginExternalFlow()
        val prepared = f.auth.withExternalFlow(flow) { signUpWith(Email) { email = "fixture@example.invalid"; password = "synthetic" } }
        assertSame(flow, prepared.flow)
        assertEquals("A", prepared.result!!.id)
        assertRedirect(flow.state, f.sent.single().url.parameters["redirect_to"]!!)
        assertEquals(1, f.store.saves)
        f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state)
        assertEquals(2, f.store.saves)
    }

    @Test fun immediateSignupPublicationDoesNotReturnAStaleFalseFailure() = scenario { f ->
        f.signupSession = true
        val flow = f.auth.beginExternalFlow()
        val prepared = f.auth.withExternalFlow(flow) { signUpWith(Email) { email = "fixture@example.invalid"; password = "synthetic" } }
        assertSame(flow, prepared.flow)
        assertNull(prepared.result)
        assertEquals(2, f.store.saves)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        stale { f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state) }
        assertEquals(1, f.requests)
    }

    @Test fun identityLinkRetainsItsHandleAndRejectsAnotherOwnerAtCallback() = scenario { f ->
        val flow = f.auth.beginExternalFlow()
        val prepared = f.auth.withExternalFlow(flow) { linkIdentity(Google) { automaticallyOpenUrl = false } }
        assertSame(flow, prepared.flow)
        assertEquals(listOf(flow.state), Url(prepared.result!!).parameters.getAll("state"))
        assertEquals("A", f.sent.single().headerOwner)
        val verifier = f.verifier.loadCodeVerifier()
        f.replyOwner = "B"
        rejected(AuthFlowRejection.OWNER_MISMATCH) { f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state) }
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        assertEquals(1, f.store.saves)
        assertEquals(verifier, f.verifier.loadCodeVerifier())
    }

    @Test fun emailChangeContinuesOnlyFromItsOwnExactSameOwnerPublication() = scenario { f ->
        val lease = f.auth.sessionAuthority.lease()
        val flow = f.auth.beginExternalFlow()
        val prepared = f.auth.withExternalFlow(flow) { updateUser { email = "changed@example.invalid" } }
        assertSame(flow, prepared.flow)
        assertRedirect(flow.state, f.sent.single().url.parameters["redirect_to"]!!)
        assertEquals(2, f.store.saves)
        f.auth.sessionAuthority.commit(lease) { }
        f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state)
        assertEquals(3, f.store.saves)
        assertEquals(2, f.requests)
    }

    @Test fun emailChangeContinuationDoesNotRecaptureSuccessorOnLaterCallback() = scenario { f ->
        val flow = f.auth.beginExternalFlow()
        f.auth.withExternalFlow(flow) { updateUser { email = "changed@example.invalid" } }
        f.owner("B")
        stale { f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state) }
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
        assertEquals(1, f.requests)
        assertEquals(3, f.store.saves)
    }

    @Test fun ordinaryMetadataUpdateDoesNotCreateAHiddenExternalFlowOrChangeVerifier() = scenario { f ->
        f.auth.updateUser { data = buildJsonObject { put("name", "Synthetic") } }
        assertNull(f.sent.single().body["code_challenge"])
        assertEquals("legacy-preserved", f.verifier.loadCodeVerifier())
        assertEquals(2, f.store.saves)
    }

    @Test fun persistenceFailureAfterExchangeNeverDeliversSuccess() = scenario { f ->
        val flow = f.oauth(); f.store.failSave = true
        var delivered = 0
        try { f.auth.importExternalCode(flow, flow.state, "synthetic-code") { delivered++ }; fail() }
        catch (_: java.io.IOException) { }
        assertEquals(0, delivered)
        assertNull(f.auth.currentSessionOrNull())
        assertEquals(1, f.requests)
        assertEquals(0, f.store.deletes)
        try { f.auth.sessionAuthority.lease(); fail() } catch (_: AuthAuthorityUnavailableException) { }
    }

    @Test fun highLevelOAuthWithoutHandleCannotReachBrowserPlatformCode() = scenario { f ->
        missing(f) { f.auth.signInWith(Google) }
    }

    @Test fun highLevelSsoWithoutHandleCannotReachBrowserPlatformCode() = scenario { f ->
        missing(f) { f.auth.signInWith(SSO) { domain = "fixture.invalid" } }
    }

    @Test fun failedInitiationRetiresOnlyItsEphemeralHandleAndPreservesBytes() = scenario { f ->
        val flow = f.auth.beginExternalFlow(); f.failInitiation = true
        try { f.auth.withExternalFlow(flow) { resetPasswordForEmail("fixture@example.invalid") }; fail() }
        catch (_: io.github.jan.supabase.exceptions.RestException) { }
        val verifier = f.verifier.loadCodeVerifier()
        assertNotNull(verifier)
        stale { f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state) }
        assertEquals(verifier, f.verifier.loadCodeVerifier())
        assertEquals(1, f.requests)
        assertEquals(1, f.store.saves)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        assertEquals(0, f.store.deletes)
    }

    @Test fun cancelledInitiationCannotLaterAuthorizeACallbackFromItsCache() = scenario { f ->
        val flow = f.auth.beginExternalFlow(); f.pause = true
        val initiate = async { f.auth.withExternalFlow(flow) { signInWith(OTP) { email = "fixture@example.invalid" } } }
        f.entered.await(); initiate.cancelAndJoin()
        val verifier = f.verifier.loadCodeVerifier()
        stale { f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state) }
        assertEquals(verifier, f.verifier.loadCodeVerifier())
        assertEquals(1, f.requests)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        assertEquals(0, f.store.deletes)
    }

    @Test fun boundedPlatformEffectUsesThePreparedInitiatingContext() = scenario { f ->
        val flow = f.oauth()
        var launches = 0
        f.auth.withExternalFlow(flow) {
            f.auth.publishExternalAction { launches++; assertEquals("A", currentUserOrNull()!!.id) }
        }
        assertEquals(1, launches)
        assertEquals(0, f.requests)
    }

    @Test fun stalePlatformEffectCannotLaunchForASuccessor() = scenario { f ->
        val flow = f.oauth(); f.owner("B")
        var launches = 0
        withContext(flow.call + AuthExternalContext(flow)) {
            stale { f.auth.publishExternalAction { launches++ } }
        }
        assertEquals(0, launches)
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
    }

    @Test fun platformEffectWaitsForTheExactSharedLocalCommitFence() = scenario { f ->
        val flow = f.oauth()
        val lease = f.auth.sessionAuthority.lease()
        val held = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val commit = async { f.auth.sessionAuthority.commit(lease) { held.complete(Unit); release.await() } }
        held.await()
        var launched = false
        val launch = async(start = CoroutineStart.UNDISPATCHED) {
            f.auth.withExternalFlow(flow) { f.auth.publishExternalAction { launched = true } }
        }
        assertFalse(launched)
        assertFalse(launch.isCompleted)
        release.complete(Unit); commit.await(); launch.await()
        assertTrue(launched)
    }

    @Test fun emailChangeCannotInventAnOwnerForATokenOnlyIdentity() = scenario { f ->
        f.auth.importAuthToken("synthetic-A", retrieveUser = false, autoRefresh = false)
        val flow = f.auth.beginExternalFlow()
        rejected(AuthFlowRejection.OWNER_MISMATCH) { f.auth.withExternalFlow(flow) { updateUser { email = "changed@example.invalid" } } }
        assertEquals(0, f.requests)
        assertEquals(1, f.store.saves)
        assertNull(f.auth.currentUserOrNull())
        assertEquals("legacy-preserved", f.verifier.loadCodeVerifier())
    }

    @Test fun cancellationAfterOwnEmailPublicationDoesNotClaimAuthenticationRollback() = scenario { f ->
        val lease = f.auth.sessionAuthority.lease()
        val flow = f.auth.beginExternalFlow()
        try {
            f.auth.withExternalFlow(flow) {
                updateUser { email = "changed@example.invalid" }
                throw CancellationException("synthetic cancelled result delivery")
            }
            fail()
        } catch (_: CancellationException) { }
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        assertEquals(2, f.store.saves)
        assertEquals(0, f.store.deletes)
        f.auth.sessionAuthority.commit(lease) { }
        stale { f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state) }
        assertEquals(1, f.requests)
    }

    companion object {
        private const val USER_A = """{"aud":"authenticated","id":"A"}"""
        private const val SESSION_A = """{"access_token":"synthetic-A","refresh_token":"refresh-A","token_type":"bearer","expires_in":86400,"user":{"aud":"authenticated","id":"A"}}"""
    }
}
