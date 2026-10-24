package io.github.jan.supabase.auth

import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.ContinuationInterceptor

/** Actual proof SDK entrypoints; invalid transport and synthetic credentials, no normal app. */
class SdkTokenAndOtpFenceTest {
    private class Store : SessionManager {
        var stored: UserSession? = null
        var saves = 0
        var deletes = 0
        override suspend fun loadSession() = stored
        override suspend fun saveSession(session: UserSession) { saves++; stored = session }
        override suspend fun deleteSession() { deletes++; stored = null }
    }

    private class Verifier : CodeVerifierCache {
        var value: String? = null
        var pauseSave = false
        var failSave = false
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        override suspend fun saveCodeVerifier(codeVerifier: String) {
            if (pauseSave) { entered.complete(Unit); release.await() }
            if (failSave) throw java.io.IOException("synthetic verifier storage failure")
            value = codeVerifier
        }
        override suspend fun loadCodeVerifier() = value
        override suspend fun deleteCodeVerifier() { value = null }
    }

    private class Fixture(dispatcher: CoroutineDispatcher) {
        val store = Store()
        val verifier = Verifier()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var pauseReply = false
        var cancelReply = false
        var failReply = false
        var requests = 0
        val headerOwners = mutableListOf<String>() // Synthetic labels, never credential values.
        val client = createSupabaseClient("https://token-otp-proof.invalid", "synthetic-key") {
            defaultLogLevel = LogLevel.NONE
            httpEngine = MockEngine { request ->
                check(request.url.host == "token-otp-proof.invalid")
                requests++
                headerOwners += when (request.headers[HttpHeaders.Authorization]) {
                    "Bearer synthetic-A" -> "A"
                    "Bearer synthetic-B" -> "B"
                    else -> "OTHER"
                }
                if (pauseReply) { entered.complete(Unit); release.await() }
                if (cancelReply) throw CancellationException("synthetic transport cancellation")
                if (failReply) throw java.io.IOException("synthetic transport failure")
                val body = when {
                    request.url.encodedPath.endsWith("/user") || request.url.encodedPath.endsWith("/signup") -> USER_A
                    request.url.encodedPath.endsWith("/token") -> SESSION_A
                    else -> "{}"
                }
                respond(body,
                    HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            install(Auth) {
                minimalSettings(sessionManager = store, autoSaveToStorage = true)
                coroutineDispatcher = dispatcher
                codeVerifierCache = verifier
                flowType = FlowType.PKCE
                scheme = "imfit"; host = "auth"
            }
        }
        val auth get() = client.auth as AuthImpl
        suspend fun owner(owner: String) = auth.importSession(syntheticSession(owner), autoRefresh = false)
        suspend fun <T> external(action: suspend Auth.() -> T): T = auth.withExternalFlow(auth.beginExternalFlow(), action).result
        suspend fun otp() = external { signInWith(OTP) { email = "fixture@example.invalid" } }
        suspend fun close() {
            release.complete(Unit); verifier.release.complete(Unit)
            client.close()
        }
    }

    private fun scenario(test: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking<Unit> {
        withTimeout(15_000) {
            val f = Fixture(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
            try { f.auth.awaitInitialization(); f.owner("A"); test(f) }
            finally { withContext(NonCancellable) { f.close() } }
        }
    }

    private suspend fun stale(block: suspend () -> Unit) {
        try { block(); fail("Obsolete writer must be rejected") }
        catch (_: StaleAuthOperationException) { }
    }

    @Test fun delayedTokenUserLookupCannotFallbackAndPublishIntoSuccessor() = scenario { f ->
        f.pauseReply = true
        val old = async { stale { f.auth.importAuthToken("synthetic-A", "refresh-A", retrieveUser = true, autoRefresh = false) } }
        f.entered.await(); f.owner("B"); f.release.complete(Unit); old.await()
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
        assertEquals("B", f.store.stored!!.user!!.id)
        assertEquals(2, f.store.saves)
        assertEquals(0, f.store.deletes)
        assertEquals(listOf("A"), f.headerOwners)
    }

    @Test fun sameOwnerABADuringTokenLookupCannotReplaceNewIdentity() = scenario { f ->
        f.pauseReply = true
        val old = async { stale { f.auth.importAuthToken("synthetic-A", "refresh-A", retrieveUser = true, autoRefresh = false) } }
        f.entered.await(); f.owner("B"); f.owner("A")
        val latest = f.auth.sessionAuthority.lease()
        f.release.complete(Unit); old.await()
        f.auth.sessionAuthority.commit(latest) { }
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        assertEquals(3, f.store.saves)
        assertEquals(0, f.store.deletes)
    }

    @Test fun tokenLookupCancellationCannotBecomeSuccessfulUnverifiedImport() = scenario { f ->
        f.cancelReply = true
        var cancelled = false
        try { f.auth.importAuthToken("synthetic-A", "refresh-A", retrieveUser = true, autoRefresh = false) }
        catch (_: CancellationException) { cancelled = true }
        assertTrue("Cancellation must propagate, not become a fallback import", cancelled)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        assertEquals(1, f.store.saves)
        assertEquals(0, f.store.deletes)
    }

    @Test fun currentTokenLookupPublishesItsUserAndRetiresPriorIdentityLease() = scenario { f ->
        val old = f.auth.sessionAuthority.lease()
        f.auth.importAuthToken("synthetic-A", "refresh-A", retrieveUser = true, autoRefresh = false)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        assertEquals(1, f.store.saves) // Token-only import expiresIn=0 retains upstream no-save semantics.
        stale { f.auth.sessionAuthority.commit(old) { fail("Old lease accepted") } }
        assertEquals(listOf("A"), f.headerOwners)
    }

    @Test fun tokenImportWithoutLookupDoesNotStartNetworkOrInventUser() = scenario { f ->
        f.auth.importAuthToken("synthetic-A", retrieveUser = false, autoRefresh = false)
        assertEquals(0, f.requests)
        assertNull(f.auth.currentUserOrNull())
        assertNotNull(f.auth.currentSessionOrNull())
        assertNull(f.auth.sessionAuthority.lease().ownerId)
        assertEquals(1, f.store.saves)
    }

    @Test fun currentLookupTransportFailurePreservesTokenOnlyCompatibilityWithoutDeletingStorage() = scenario { f ->
        f.failReply = true
        f.auth.importAuthToken("synthetic-A", retrieveUser = true, autoRefresh = false)
        assertNull(f.auth.currentUserOrNull())
        assertNotNull(f.auth.currentSessionOrNull())
        assertEquals(1, f.store.saves)
        assertEquals(0, f.store.deletes)
        assertEquals(1, f.requests)
    }

    @Test fun staleOtpInvocationCannotOverwriteSuccessorVerifierBeforeNetworkValidation() = scenario { f ->
        val (operation, session) = f.auth.sessionAuthority.capture { f.auth.currentSessionOrNull() }
        f.owner("B"); f.verifier.value = "successor-verifier"
        withContext(AuthCallContext(f.auth, operation, session)) { stale { f.otp() } }
        assertEquals("successor-verifier", f.verifier.value)
        assertEquals(0, f.requests)
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
    }

    @Test fun directStaleOtpProviderCannotMutateVerifierBeforeApiCall() = scenario { f ->
        val (operation, session) = f.auth.sessionAuthority.capture { f.auth.currentSessionOrNull() }
        f.owner("B"); f.verifier.value = "successor-verifier"
        withContext(AuthCallContext(f.auth, operation, session)) {
            stale { OTP.login(f.client, {}, null) { email = "fixture@example.invalid" } }
        }
        assertEquals("successor-verifier", f.verifier.value)
        assertEquals(0, f.requests)
        assertEquals(2, f.store.saves)
    }

    @Test fun otpVerifierWriteAndIdentityTransitionUseTheSameAwaitedFence() = scenario { f ->
        f.verifier.pauseSave = true
        val old = async {
            try { f.otp() } catch (_: StaleAuthOperationException) { /* B won after the local write. */ }
        }
        f.verifier.entered.await()
        val transition = async(start = CoroutineStart.UNDISPATCHED) {
            f.owner("B")
            f.verifier.value = "successor-verifier"
        }
        try { assertFalse("Transition must wait for the fenced verifier write", transition.isCompleted) }
        finally { f.verifier.release.complete(Unit) }
        old.await(); transition.await()
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
        assertEquals("successor-verifier", f.verifier.value)
        assertFalse(f.headerOwners.contains("B"))
        assertEquals(2, f.store.saves)
    }

    @Test fun delayedOtpResponseCannotDeliverSuccessForAnotherOwner() = scenario { f ->
        f.pauseReply = true
        val old = async { stale { f.otp() } }
        f.entered.await(); f.owner("B"); f.verifier.value = "successor-verifier"
        f.release.complete(Unit); old.await()
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
        assertEquals("successor-verifier", f.verifier.value)
        assertEquals(2, f.store.saves)
        assertEquals(listOf("A"), f.headerOwners)
    }

    @Test fun currentOtpCompletesWithoutPublishingSessionAndImplicitDoesNotWriteVerifier() = scenario { f ->
        f.otp()
        assertNotNull(f.verifier.value)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        assertEquals(1, f.store.saves)
        assertEquals(1, f.requests)
        f.auth.config.flowType = FlowType.IMPLICIT
        f.verifier.value = "unchanged-verifier"
        f.otp()
        assertEquals("unchanged-verifier", f.verifier.value)
        assertEquals(2, f.requests)
    }

    @Test fun otpVerifierStorageFailureDoesNotSendOrPublishOrDeleteCurrentSession() = scenario { f ->
        f.verifier.failSave = true
        var failed = false
        try { f.otp() } catch (_: java.io.IOException) { failed = true }
        assertTrue(failed)
        assertEquals(0, f.requests)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        assertEquals(1, f.store.saves)
        assertEquals(0, f.store.deletes)
    }

    @Test fun delayedTokenLookupTransportFailureCannotFallbackIntoSuccessor() = scenario { f ->
        f.pauseReply = true; f.failReply = true
        val old = async { stale { f.auth.importAuthToken("synthetic-A", retrieveUser = true, autoRefresh = false) } }
        f.entered.await(); f.owner("B"); f.release.complete(Unit); old.await()
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
        assertEquals(2, f.store.saves)
        assertEquals(0, f.store.deletes)
    }

    @Test fun directStaleSignupCannotOverwriteSuccessorVerifier() = scenario { f ->
        val (operation, session) = f.auth.sessionAuthority.capture { f.auth.currentSessionOrNull() }
        f.owner("B"); f.verifier.value = "successor-verifier"
        withContext(AuthCallContext(f.auth, operation, session)) {
            stale { Email.signUp(f.client, {}, null) { email = "fixture@example.invalid"; password = "synthetic" } }
        }
        assertEquals("successor-verifier", f.verifier.value)
        assertEquals(0, f.requests)
        assertEquals(2, f.store.saves)
    }

    @Test fun directStaleLoginRejectsBeforeEvaluatingProviderConfiguration() = scenario { f ->
        val (operation, session) = f.auth.sessionAuthority.capture { f.auth.currentSessionOrNull() }
        f.owner("B")
        var configurations = 0
        withContext(AuthCallContext(f.auth, operation, session)) {
            stale { Email.login(f.client, {}, null) { configurations++; email = "fixture@example.invalid"; password = "synthetic" } }
        }
        assertEquals(0, configurations)
        assertEquals(0, f.requests)
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
    }

    @Test fun signupVerifierWriteUsesTheSameFenceAsIdentityTransition() = scenario { f ->
        f.verifier.pauseSave = true
        val signup = async {
            try { f.external { signUpWith(Email) { email = "fixture@example.invalid"; password = "synthetic" } } }
            catch (_: StaleAuthOperationException) { /* B won after the fenced write. */ }
        }
        f.verifier.entered.await()
        val successor = async(start = CoroutineStart.UNDISPATCHED) {
            f.owner("B"); f.verifier.value = "successor-verifier"
        }
        try { assertFalse("Signup verifier storage must hold the identity fence", successor.isCompleted) }
        finally { f.verifier.release.complete(Unit) }
        signup.await(); successor.await()
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
        assertEquals("successor-verifier", f.verifier.value)
        assertFalse(f.headerOwners.contains("B"))
        assertEquals(2, f.store.saves)
    }

    @Test fun directDelayedSignupCannotReturnUserToSuccessor() = scenario { f ->
        f.pauseReply = true
        val signup = async {
            stale { f.external { Email.signUp(f.client, {}, null) { email = "fixture@example.invalid"; password = "synthetic" } } }
        }
        f.entered.await(); f.owner("B"); f.release.complete(Unit); signup.await()
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
        assertEquals(2, f.store.saves)
        assertEquals(0, f.store.deletes)
    }

    @Test fun currentDirectSignupReturnsUserWithoutPublishingAnUnconfirmedSession() = scenario { f ->
        val result = f.external { Email.signUp(f.client, { fail("User-only response must not publish session") }, "imfit://auth") {
            email = "fixture@example.invalid"; password = "synthetic"
        } }
        assertEquals("A", result!!.id)
        assertNotNull(f.verifier.value)
        assertEquals(1, f.store.saves)
        assertEquals(1, f.requests)
    }

    @Test fun directLoginCallbackInheritsTheOriginalOperationForSessionPublication() = scenario { f ->
        val before = f.auth.sessionAuthority.lease()
        var callbacks = 0
        Email.login(f.client, { session ->
            assertNotNull(currentCoroutineContext()[AuthCallContext])
            f.auth.importSession(session, autoRefresh = false)
            callbacks++
        }, null) { email = "fixture@example.invalid"; password = "synthetic" }
        assertEquals(1, callbacks)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        assertEquals(2, f.store.saves)
        stale { f.auth.sessionAuthority.commit(before) { fail("Prior lease accepted") } }
    }

    @Test fun signupVerifierFailureNeverStartsHttpOrPublishesIdentity() = scenario { f ->
        f.verifier.failSave = true
        var failed = false
        try { f.external { signUpWith(Email) { email = "fixture@example.invalid"; password = "synthetic" } } }
        catch (_: java.io.IOException) { failed = true }
        assertTrue(failed)
        assertEquals(0, f.requests)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        assertEquals(1, f.store.saves)
        assertEquals(0, f.store.deletes)
    }

    companion object {
        private const val USER_A = """{"aud":"authenticated","id":"A"}"""
        private const val SESSION_A = """{"access_token":"synthetic-A","refresh_token":"refresh-A","token_type":"bearer","expires_in":86400,"user":{"aud":"authenticated","id":"A"}}"""
    }
}
