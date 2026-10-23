package io.github.jan.supabase.auth

import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.exceptions.RestException
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Actual rebuilt SDK writers/headers, synthetic credentials and invalid host only. */
class SdkWriterFenceTest {
    private class Store : SessionManager {
        var stored: io.github.jan.supabase.auth.user.UserSession? = null
        var saves = 0
        var deletes = 0
        override suspend fun loadSession() = stored
        override suspend fun saveSession(session: io.github.jan.supabase.auth.user.UserSession) { saves++; stored = session }
        override suspend fun deleteSession() { deletes++; stored = null }
    }

    private class Fixture {
        val store = Store()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var delayResponse = false
        var delaySend = false
        var body = USER_A
        var status = HttpStatusCode.OK
        var requests = 0
        val headerOwners = mutableListOf<String>() // labels only, never token values in diagnostics
        private val sendBarrier = createClientPlugin("AuthProofSendBarrier") {
            onRequest { _, _ ->
                if (delaySend) { entered.complete(Unit); release.await() }
            }
        }
        val client = createSupabaseClient("https://writer-proof.invalid", "synthetic-key") {
            defaultLogLevel = LogLevel.NONE
            httpConfig { install(sendBarrier) }
            httpEngine = MockEngine { request ->
                check(request.url.host == "writer-proof.invalid")
                requests++
                headerOwners += when (request.headers[HttpHeaders.Authorization]) {
                    "Bearer synthetic-A" -> "A"
                    "Bearer synthetic-B" -> "B"
                    else -> "OTHER"
                }
                if (delayResponse) { entered.complete(Unit); release.await() }
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            install(Auth) { minimalSettings(sessionManager = store, autoSaveToStorage = true) }
        }
        val auth get() = client.auth
        suspend fun owner(owner: String) = auth.importSession(syntheticSession(owner), autoRefresh = false)
        suspend fun close() { release.complete(Unit); client.close() }
    }

    private fun scenario(test: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking<Unit> {
        withTimeout(15_000) {
            val fixture = Fixture()
            try { fixture.auth.awaitInitialization(); fixture.owner("A"); test(fixture) }
            finally { withContext(NonCancellable) { fixture.close() } }
        }
    }

    private fun staleResponse(response: String, action: suspend (Auth) -> Unit) = scenario { f ->
        f.body = response; f.delayResponse = true
        val old = async {
            try { action(f.auth); false } catch (_: StaleAuthOperationException) { true }
        }
        f.entered.await(); f.owner("B")
        val writes = f.store.saves
        f.release.complete(Unit)
        assertTrue("Old completion must be rejected", old.await())
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
        assertEquals("B", f.store.stored!!.user!!.id)
        assertEquals(writes, f.store.saves)
        assertEquals(0, f.store.deletes)
    }

    @Test fun delayedEmailLoginCannotReplaceSuccessor() = staleResponse(SESSION_A) {
        it.signInWith(Email) { email = "fixture@example.invalid"; password = "synthetic" }
    }

    @Test fun delayedEmailSignupCannotReplaceSuccessor() = staleResponse(SESSION_A) {
        it.signUpWith(Email) { email = "fixture@example.invalid"; password = "synthetic" }
    }

    @Test fun delayedAnonymousLoginCannotReplaceSuccessor() = staleResponse(SESSION_A) { it.signInAnonymously() }

    @Test fun delayedEmailOtpCannotReplaceSuccessor() = staleResponse(SESSION_A) {
        it.verifyEmailOtp(OtpType.Email.EMAIL, "fixture@example.invalid", "synthetic-code")
    }

    @Test fun delayedEmailHashOtpCannotReplaceSuccessor() = staleResponse(SESSION_A) {
        it.verifyEmailOtp(OtpType.Email.EMAIL, "synthetic-hash")
    }

    @Test fun delayedPhoneOtpCannotReplaceSuccessor() = staleResponse(SESSION_A) {
        it.verifyPhoneOtp(OtpType.Phone.SMS, "+10000000000", "synthetic-code")
    }

    @Test fun delayedUserUpdateCannotWriteOrPublishIntoSuccessor() = staleResponse(USER_A) {
        it.updateUser { email = "new@example.invalid" }
    }

    @Test fun delayedCurrentUserLookupCannotWriteOrPublishIntoSuccessor() = staleResponse(USER_A) {
        it.retrieveUserForCurrentSession()
    }

    @Test fun delayedReadOnlyCurrentUserLookupCannotDeliverOldUser() = staleResponse(USER_A) {
        it.retrieveUserForCurrentSession(updateSession = false)
    }

    @Test fun delayedIdentityUnlinkCannotTouchSuccessor() = staleResponse("{}") { it.unlinkIdentity("synthetic-identity") }

    @Test fun delayedLogoutCannotClearSuccessor() = staleResponse("{}") { it.signOut() }

    @Test fun delayedLogoutCannotClearSameOwnerReloginABA() = scenario { f ->
        f.body = "{}"; f.delayResponse = true
        val old = async { try { f.auth.signOut(); false } catch (_: StaleAuthOperationException) { true } }
        f.entered.await(); f.owner("B"); f.owner("A")
        val writes = f.store.saves
        val newest = f.auth.sessionAuthority.lease()
        f.release.complete(Unit)
        assertTrue(old.await())
        assertEquals(0, f.store.deletes)
        assertEquals(writes, f.store.saves)
        f.auth.sessionAuthority.commit(newest) { }
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
    }

    @Test fun successfulUserUpdatePersistsBeforePublicationAndPreservesIdentityLease() = scenario { f ->
        val lease = f.auth.sessionAuthority.lease()
        f.auth.updateUser { email = "new@example.invalid" }
        assertEquals("A", f.store.stored!!.user!!.id)
        assertEquals(2, f.store.saves)
        f.auth.sessionAuthority.commit(lease) { }
        assertEquals(listOf("A"), f.headerOwners)
    }

    @Test fun userUpdatePublicationWaitsForExistingLocalCommit() = scenario { f ->
        val lease = f.auth.sessionAuthority.lease()
        f.delayResponse = true
        val update = async { f.auth.updateUser { email = "new@example.invalid" } }
        f.entered.await()
        val held = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val commit = async { f.auth.sessionAuthority.commit(lease) { held.complete(Unit); finish.await() } }
        held.await(); f.release.complete(Unit)
        assertEquals(1, f.store.saves)
        finish.complete(Unit); commit.await(); update.await()
        assertEquals(2, f.store.saves)
    }

    @Test fun wrongOwnerUserResponseIsRejectedBeforeLocalWrite() = scenario { f ->
        f.body = """{"aud":"authenticated","id":"B"}"""
        try { f.auth.updateUser { email = "new@example.invalid" }; fail() }
        catch (_: IllegalStateException) { }
        assertEquals(1, f.store.saves)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
    }

    @Test fun payloadCreatedBeforeAccountChangeCannotBeSentWithSuccessorCredential() = scenario { f ->
        val built = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val old = async(Dispatchers.Default) {
            try {
                f.auth.updateUser {
                    email = "owner-A@example.invalid"
                    built.countDown()
                    check(finish.await(5, TimeUnit.SECONDS)) // test-only worker barrier, not UI
                }
                false
            } catch (_: StaleAuthOperationException) { true }
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { built.await(5, TimeUnit.SECONDS) })
            f.owner("B"); finish.countDown()
            assertTrue(old.await())
            assertEquals(0, f.requests)
        } finally { finish.countDown() }
    }

    @Test fun actualDelayedSendKeepsCapturedAuthorizationAndNeverUsesBForA() = scenario { f ->
        f.delaySend = true
        val old = async { try { f.auth.updateUser { email = "owner-A@example.invalid" }; false } catch (_: StaleAuthOperationException) { true } }
        f.entered.await(); f.owner("B"); f.release.complete(Unit)
        assertTrue(old.await())
        assertEquals(listOf("A"), f.headerOwners)
        assertEquals(0, f.headerOwners.count { it == "B" })
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
    }

    @Test fun preparedSdkRequestPinsHeaderAcrossLaterAccountSwitch() = scenario { f ->
        val statement = (f.auth as AuthImpl).api.prepareRequest("user") { }
        f.owner("B")
        statement.execute { }
        assertEquals(listOf("A"), f.headerOwners)
    }

    @Test fun staleSessionMissingErrorCannotClearSuccessor() = scenario { f ->
        f.delayResponse = true; f.status = HttpStatusCode.Unauthorized
        f.body = """{"error_code":"session_not_found","msg":"synthetic missing session"}"""
        val old = async { try { f.auth.retrieveUserForCurrentSession(); false } catch (_: RestException) { true } }
        f.entered.await(); f.owner("B"); f.release.complete(Unit)
        assertTrue(old.await())
        assertEquals(0, f.store.deletes)
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
    }

    @Test fun currentSessionMissingLogoutClearsExactlyOnceAndCompletes() = scenario { f ->
        f.status = HttpStatusCode.Unauthorized
        f.body = """{"error_code":"session_not_found","msg":"synthetic missing session"}"""
        f.auth.signOut()
        assertEquals(1, f.store.deletes)
        assertNull(f.auth.currentUserOrNull())
    }

    @Test fun currentSessionMissingLookupAwaitsCleanupAndInvalidatesOldLease() = scenario { f ->
        val lease = f.auth.sessionAuthority.lease()
        f.status = HttpStatusCode.Unauthorized
        f.body = """{"error_code":"session_not_found","msg":"synthetic missing session"}"""
        try { f.auth.retrieveUserForCurrentSession(); fail() } catch (_: RestException) { }
        assertEquals(1, f.store.deletes)
        assertNull(f.auth.currentUserOrNull())
        try { f.auth.sessionAuthority.commit(lease) { fail("Missing-session request retained old lease") }; fail() }
        catch (_: StaleAuthOperationException) { }
    }

    @Test fun explicitDifferentJwtMissingSessionCannotClearCurrentIdentity() = scenario { f ->
        f.status = HttpStatusCode.Unauthorized
        f.body = """{"error_code":"session_not_found","msg":"synthetic external missing session"}"""
        try { f.auth.retrieveUser("synthetic-other-request-token"); fail() } catch (_: RestException) { }
        assertEquals(0, f.store.deletes)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
    }

    @Test fun delayedMfaVerificationCannotPublishIntoSuccessor() = staleResponse(SESSION_A) {
        it.mfa.verifyChallenge("synthetic-factor", "synthetic-challenge", "synthetic-code")
    }

    @Test fun delayedReadOnlyMfaVerificationCannotDeliverOldSession() = staleResponse(SESSION_A) {
        it.mfa.verifyChallenge("synthetic-factor", "synthetic-challenge", "synthetic-code", saveSession = false)
    }

    @Test fun compositeMfaCapturesTicketBeforeCreatingChallenge() = staleResponse("""{"id":"synthetic-challenge","type":"totp"}""") {
        it.mfa.createChallengeAndVerify("synthetic-factor", "synthetic-code")
    }

    @Test fun successfulMfaVerificationUsesCurrentOwnerAndPersists() = scenario { f ->
        f.body = SESSION_A
        f.auth.mfa.verifyChallenge("synthetic-factor", "synthetic-challenge", "synthetic-code")
        assertEquals(2, f.store.saves)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        assertEquals(listOf("A"), f.headerOwners)
    }

    @Test fun mfaResponseWithAnotherOwnerCannotPersistOrPublish() = scenario { f ->
        f.body = SESSION_A.replace("\"id\":\"A\"", "\"id\":\"B\"")
        try { f.auth.mfa.verifyChallenge("factor", "challenge", "code"); fail() }
        catch (_: IllegalStateException) { }
        assertEquals(1, f.store.saves)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
    }

    @Test fun stalePkceExchangeCannotDeleteSuccessorVerifierOrPublish() = scenario { f ->
        f.auth.codeVerifierCache.saveCodeVerifier("synthetic-initial-verifier")
        f.body = SESSION_A; f.delayResponse = true
        val old = async { try { f.auth.exchangeCodeForSession("synthetic-code"); false } catch (_: StaleAuthOperationException) { true } }
        f.entered.await(); f.owner("B")
        f.auth.codeVerifierCache.saveCodeVerifier("synthetic-successor-verifier")
        f.release.complete(Unit)
        assertTrue(old.await())
        assertEquals("synthetic-successor-verifier", f.auth.codeVerifierCache.loadCodeVerifier())
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
        assertEquals(2, f.store.saves)
    }

    @Test fun staleReadOnlyPkceExchangeCannotDeleteVerifierOrDeliverSession() = scenario { f ->
        f.auth.codeVerifierCache.saveCodeVerifier("synthetic-initial-verifier")
        f.body = SESSION_A; f.delayResponse = true
        val old = async { try { f.auth.exchangeCodeForSession("synthetic-code", saveSession = false); false } catch (_: StaleAuthOperationException) { true } }
        f.entered.await(); f.owner("B")
        f.auth.codeVerifierCache.saveCodeVerifier("synthetic-successor-verifier")
        f.release.complete(Unit)
        assertTrue(old.await())
        assertEquals("synthetic-successor-verifier", f.auth.codeVerifierCache.loadCodeVerifier())
        assertEquals(2, f.store.saves)
    }

    @Test fun successfulReadOnlyPkceExchangeConsumesOnlyCapturedVerifier() = scenario { f ->
        f.auth.codeVerifierCache.saveCodeVerifier("synthetic-initial-verifier")
        f.body = SESSION_A
        val lease = f.auth.sessionAuthority.lease()
        assertEquals("A", f.auth.exchangeCodeForSession("synthetic-code", saveSession = false).user!!.id)
        assertNull(f.auth.codeVerifierCache.loadCodeVerifier())
        assertEquals(1, f.store.saves)
        f.auth.sessionAuthority.commit(lease) { }
    }

    companion object {
        private const val USER_A = """{"aud":"authenticated","id":"A"}"""
        private const val SESSION_A = """{"access_token":"synthetic-A","refresh_token":"refresh-A","token_type":"bearer","expires_in":86400,"user":{"aud":"authenticated","id":"A"}}"""
    }
}
