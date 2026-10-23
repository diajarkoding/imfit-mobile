package io.github.jan.supabase.auth

import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import org.junit.Assert.*
import org.junit.Test
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Rebuilt SDK only. Synthetic state echo is NOT evidence that a live provider echoes OAuth state. */
class SdkAwaitedWriterTest {
    private class Store : SessionManager {
        var session: UserSession? = null
        var saves = 0
        var deletes = 0
        var failSave = false
        override suspend fun loadSession() = session
        override suspend fun saveSession(session: UserSession) {
            saves++
            if (failSave) throw java.io.IOException("synthetic persistence failure")
            this.session = session
        }
        override suspend fun deleteSession() { deletes++; session = null }
    }

    private class Fixture(dispatcher: CoroutineDispatcher) {
        val store = Store()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var pause = false
        var ignoreCancellation = false
        var body = SESSION_A
        var status = HttpStatusCode.OK
        var requests = 0
        var firstServerFailure = false
        var firstNetworkFailure = false
        val client = createSupabaseClient("https://awaited-proof.invalid", "synthetic-key") {
            defaultLogLevel = LogLevel.NONE
            httpEngine = MockEngine { request ->
                check(request.url.host == "awaited-proof.invalid")
                requests++
                if (firstNetworkFailure && requests == 1) throw java.io.IOException("synthetic transport failure")
                if (pause) {
                    entered.complete(Unit)
                    if (ignoreCancellation) withContext(NonCancellable) { release.await() } else release.await()
                }
                if (firstServerFailure && requests == 1) respond("{}", HttpStatusCode.InternalServerError, JSON)
                else respond(body, status, JSON)
            }
            install(Auth) {
                minimalSettings(sessionManager = store, autoSaveToStorage = true)
                coroutineDispatcher = dispatcher
                retryDelay = 1.milliseconds
                flowType = FlowType.PKCE
            }
        }
        val auth get() = client.auth as AuthImpl
        suspend fun owner(owner: String) = auth.importSession(syntheticSession(owner), autoRefresh = false)
        fun due() = syntheticSession("A").copy(expiresIn = 100, expiresAt = Clock.System.now() + 10.seconds)
        suspend fun startDue(): Job {
            auth.importSession(due(), autoRefresh = true)
            return requireNotNull(auth.sessionJob)
        }
    }

    private fun scenario(test: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking<Unit> {
        withTimeout(15_000) {
            val f = Fixture(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
            try { f.auth.awaitInitialization(); f.owner("A"); test(f) }
            finally { f.release.complete(Unit); withContext(NonCancellable) { f.client.close() } }
        }
    }

    private suspend fun stale(block: suspend () -> Unit) {
        try { block(); fail("Expected obsolete operation to be rejected") }
        catch (_: StaleAuthOperationException) { }
    }

    @Test fun stopIsAwaitedAndRetiresPriorOperationsButNotIdentityLease() = scenario { f ->
        f.auth.startAutoRefreshForCurrentSession()
        val task = requireNotNull(f.auth.sessionJob)
        val old = f.auth.sessionAuthority.operation()
        val lease = f.auth.sessionAuthority.lease()
        f.auth.stopAutoRefreshForCurrentSession()
        assertFalse(f.auth.isAutoRefreshRunning)
        assertTrue(task.isCancelled)
        stale { f.auth.sessionAuthority.validate(old) }
        f.auth.sessionAuthority.commit(lease) { }
        assertEquals(1, f.store.saves)
    }

    @Test fun jobControlsWaitForTheSameLocalCommitFence() = scenario { f ->
        val held = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val lease = f.auth.sessionAuthority.lease()
        val commit = async { f.auth.sessionAuthority.commit(lease) { held.complete(Unit); release.await() } }
        held.await()
        val stop = async { f.auth.stopAutoRefreshForCurrentSession() }
        yield(); assertFalse(stop.isCompleted)
        release.complete(Unit); commit.await(); stop.await()
    }

    @Test fun lateRefreshReplyCannotResurrectAAfterB() = scenario { f ->
        f.pause = true; f.ignoreCancellation = true
        val task = f.startDue()
        f.entered.await(); f.owner("B"); f.release.complete(Unit); task.join()
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
        assertEquals("B", f.store.session!!.user!!.id)
        assertEquals(3, f.store.saves)
        assertEquals(0, f.store.deletes)
    }

    @Test fun lateRefreshErrorCannotClearBOrRetryAsB() = scenario { f ->
        f.pause = true; f.ignoreCancellation = true; f.status = HttpStatusCode.BadRequest; f.body = "{}"
        val task = f.startDue()
        f.entered.await(); f.owner("B"); f.release.complete(Unit); task.join()
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
        assertEquals(0, f.store.deletes)
        assertEquals(1, f.requests)
    }

    @Test fun scheduledRefreshPublishesRenewalWithoutCancellingItsOwnCommit() = scenario { f ->
        val lease = f.auth.sessionAuthority.lease()
        // Import is an identity transition, so take the new lease after scheduling, before its task runs.
        val task = f.startDue()
        val current = f.auth.sessionAuthority.lease()
        task.join()
        assertEquals(3, f.store.saves)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        assertNotSame(task, f.auth.sessionJob)
        assertTrue(f.auth.isAutoRefreshRunning)
        f.auth.sessionAuthority.commit(current) { }
        stale { f.auth.sessionAuthority.commit(lease) { } }
    }

    @Test fun transientServerErrorRetriesOnlyItsOriginalSession() = scenario { f ->
        f.firstServerFailure = true
        val task = f.startDue()
        task.join()
        assertEquals(2, f.requests)
        assertEquals(3, f.store.saves)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
    }

    @Test fun successorDuringRetryDelayStopsOldRetry() = scenario { f ->
        f.auth.config.retryDelay = 30.seconds
        f.firstServerFailure = true
        val task = f.startDue()
        f.auth.sessionStatus.first { it is SessionStatus.RefreshFailure }
        f.owner("B"); task.join()
        assertEquals(1, f.requests)
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
        assertEquals(0, f.store.deletes)
    }

    @Test fun transportFailureRetriesButPersistenceFailureNeverDoes() = scenario { f ->
        f.firstNetworkFailure = true
        val task = f.startDue(); task.join()
        assertEquals(2, f.requests)
        assertEquals(3, f.store.saves)
        // A second immediate task gets a valid response, but saving that response fails.
        f.auth.importSession(f.due(), autoRefresh = true)
        val failedTask = requireNotNull(f.auth.sessionJob)
        f.store.failSave = true
        failedTask.join()
        assertEquals(3, f.requests)
        assertEquals(5, f.store.saves)
        assertNull(f.auth.currentSessionOrNull())
        try { f.auth.sessionAuthority.lease(); fail() } catch (_: AuthAuthorityUnavailableException) { }
    }

    @Test fun terminalCurrentRefreshErrorClearsExactlyItsOwnIdentity() = scenario { f ->
        f.status = HttpStatusCode.BadRequest; f.body = "{}"
        val task = f.startDue()
        task.join()
        assertNull(f.auth.currentUserOrNull())
        assertEquals(1, f.store.deletes)
        assertFalse(f.auth.isAutoRefreshRunning)
    }

    @Test fun cancellationOfScheduledRefreshDoesNotBecomeFailureOrDelete() = scenario { f ->
        f.pause = true
        val task = f.startDue(); f.entered.await()
        f.auth.stopAutoRefreshForCurrentSession(); task.join()
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        assertTrue(f.auth.sessionStatus.value is SessionStatus.Authenticated)
        assertEquals(0, f.store.deletes)
        assertEquals(2, f.store.saves)
    }

    @Test fun expiredAttemptedLoginDoesNotClearCurrentBOnRefreshError() = scenario { f ->
        f.owner("B"); f.status = HttpStatusCode.BadRequest; f.body = "{}"
        try {
            f.auth.importSession(syntheticSession("A").copy(expiresAt = Clock.System.now() - 1.seconds), true)
            fail()
        } catch (_: io.github.jan.supabase.exceptions.RestException) { }
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
        assertEquals(0, f.store.deletes)
        assertEquals(2, f.store.saves)
    }

    @Test fun expiredImportCapturesBeforeRefreshAndCannotOverwriteSuccessor() = scenario { f ->
        f.pause = true
        val old = async { stale { f.auth.importSession(syntheticSession("A").copy(expiresAt = Clock.System.now() - 1.seconds), true) } }
        f.entered.await(); f.owner("B"); f.release.complete(Unit); old.await()
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
        assertEquals(2, f.store.saves)
    }

    @Test fun manualRefreshRejectsWrongOwnerBeforePersistence() = scenario { f ->
        f.body = SESSION_A.replace("\"id\":\"A\"", "\"id\":\"B\"")
        try { f.auth.refreshCurrentSession(); fail() } catch (_: IllegalStateException) { }
        assertEquals(1, f.store.saves)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
    }

    @Test fun staleLifecycleStopCannotResetSuccessor() = scenario { f ->
        val event = f.auth.sessionAuthority.snapshotOperation()
        f.owner("B")
        stale { f.auth.lifecycleBackground(event) }
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
        assertEquals(0, f.store.deletes)
    }

    @Test fun staleLifecycleStartCannotRestoreOrClearSuccessor() = scenario { f ->
        val event = f.auth.sessionAuthority.snapshotOperation()
        f.owner("B")
        stale { f.auth.lifecycleForeground(event) }
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
        assertEquals(2, f.store.saves)
    }

    @Test fun pkceVerifierIsPresentWhenAwaitedUrlReturns() = scenario { f ->
        val flow = f.auth.beginExternalFlow()
        val url = f.auth.getOAuthUrl(Google, flow = flow)
        assertTrue(url.contains("state=${flow.state}"))
        val verifier = requireNotNull(f.auth.codeVerifierCache.loadCodeVerifier())
        assertTrue(url.contains("code_challenge=${generateCodeChallenge(verifier)}"))
        assertEquals(0, f.requests)
    }

    @Test fun staleUrlPreparationCannotReplaceSuccessorVerifier() = scenario { f ->
        val flow = f.auth.beginExternalFlow()
        f.owner("B"); f.auth.codeVerifierCache.saveCodeVerifier("synthetic-successor")
        stale { f.auth.getOAuthUrl(Google, flow = flow) }
        assertEquals("synthetic-successor", f.auth.codeVerifierCache.loadCodeVerifier())
        assertEquals(0, f.requests)
    }

    @Test fun sameFlowCannotRegenerateVerifierBehindAnAlreadyIssuedUrl() = scenario { f ->
        val flow = f.auth.beginExternalFlow(); f.auth.getOAuthUrl(Google, flow = flow)
        val verifier = f.auth.codeVerifierCache.loadCodeVerifier()
        try { f.auth.getOAuthUrl(Google, flow = flow); fail() } catch (_: IllegalStateException) { }
        assertEquals(verifier, f.auth.codeVerifierCache.loadCodeVerifier())
    }

    @Test fun profilePublicationRebindsScheduledTaskToItsNewGeneration() = scenario { f ->
        f.auth.startAutoRefreshForCurrentSession()
        val old = requireNotNull(f.auth.sessionJob)
        val lease = f.auth.sessionAuthority.lease()
        f.body = USER_A
        f.auth.updateUser { email = "synthetic@example.invalid" }
        assertTrue(old.isCancelled)
        assertTrue(f.auth.isAutoRefreshRunning)
        assertNotSame(old, f.auth.sessionJob)
        f.auth.sessionAuthority.commit(lease) { }
        assertEquals(2, f.store.saves)
    }

    private fun staleResult(body: String, action: suspend (Auth) -> Unit) = scenario { f ->
        f.body = body; f.pause = true
        val old = async { stale { action(f.auth) } }
        f.entered.await(); f.owner("B"); f.release.complete(Unit); old.await()
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
        assertEquals(2, f.store.saves)
        assertEquals(0, f.store.deletes)
    }

    @Test fun delayedTotpEnrollmentCannotDeliverToSuccessor() = staleResult("""{"id":"factor","totp":{"secret":"synthetic","qr_code":"synthetic-svg","uri":"synthetic-uri"}}""") {
        it.mfa.enroll(io.github.jan.supabase.auth.mfa.FactorType.TOTP)
    }

    @Test fun delayedPhoneEnrollmentCannotDeliverToSuccessor() = staleResult("""{"id":"factor","phone":"+10000000000"}""") {
        it.mfa.enroll(io.github.jan.supabase.auth.mfa.FactorType.Phone) { phone = "+10000000000" }
    }

    @Test fun delayedChallengeCannotDeliverToSuccessor() = staleResult("""{"id":"challenge","type":"totp"}""") {
        it.mfa.createChallenge("factor")
    }

    @Test fun delayedUnenrollCannotReportSuccessForSuccessor() = staleResult("{}") { it.mfa.unenroll("factor") }

    @Test fun delayedExplicitUserReadCannotDeliverToSuccessor() = staleResult(USER_A) { it.retrieveUser("synthetic-A") }

    @Test fun delayedExplicitRefreshCannotDeliverToSuccessor() = staleResult(SESSION_A) { it.refreshSession("refresh-A") }

    @Test fun delayedSsoUrlCannotDeliverToSuccessor() = staleResult("""{"url":"https://synthetic.invalid"}""") { it.retrieveSSOUrl { domain = "synthetic.invalid" } }

    @Test fun delayedPasswordResetCannotReportSuccessForSuccessor() = staleResult("{}") { it.resetPasswordForEmail("synthetic@example.invalid") }

    @Test fun delayedReauthenticationCannotReportSuccessForSuccessor() = staleResult("{}") { it.reauthenticate() }

    @Test fun delayedResendCannotReportSuccessForSuccessor() = staleResult("{}") { it.resendEmail(OtpType.Email.SIGNUP, "synthetic@example.invalid") }

    @Test fun currentEnrollmentAndChallengeCompleteWithoutChangingIdentity() = scenario { f ->
        val lease = f.auth.sessionAuthority.lease()
        f.body = """{"id":"factor","phone":"+10000000000"}"""
        assertEquals("factor", f.auth.mfa.enroll(io.github.jan.supabase.auth.mfa.FactorType.Phone) { phone = "+10000000000" }.id)
        f.body = """{"id":"challenge","type":"totp"}"""
        assertEquals("challenge", f.auth.mfa.createChallenge("factor").id)
        f.auth.mfa.unenroll("factor")
        f.auth.sessionAuthority.commit(lease) { }
        assertEquals(1, f.store.saves)
        assertEquals(3, f.requests)
    }

    @Test fun pkceCallbackPublishesBeforeExactlyOnceDelivery() = scenario { f ->
        val flow = f.auth.beginExternalFlow(); f.auth.getOAuthUrl(Google, flow = flow)
        var delivered = 0
        f.auth.importExternalCode(flow, flow.state, "synthetic-code") { session ->
            delivered++
            assertSame(session, f.auth.currentSessionOrNull())
            assertSame(session, f.store.session)
        }
        stale { f.auth.importExternalCode(flow, flow.state, "synthetic-code") { delivered++ } }
        assertEquals(1, delivered)
        assertEquals(1, f.requests)
        assertEquals(2, f.store.saves)
        assertNull(f.auth.codeVerifierCache.loadCodeVerifier())
    }

    @Test fun missingOrWrongStateCannotExchangeOrConsumeVerifier() = scenario { f ->
        val flow = f.auth.beginExternalFlow(); f.auth.getOAuthUrl(Google, flow = flow)
        val verifier = f.auth.codeVerifierCache.loadCodeVerifier()
        for (state in listOf(null, "wrong-state")) {
            try { f.auth.importExternalCode(flow, state, "synthetic-code") { fail() }; fail() }
            catch (_: IllegalStateException) { }
        }
        assertEquals(0, f.requests)
        assertEquals(verifier, f.auth.codeVerifierCache.loadCodeVerifier())
        assertEquals(1, f.store.saves)
    }

    @Test fun delayedPkceCallbackCannotDeleteOrPublishIntoSuccessor() = scenario { f ->
        val flow = f.auth.beginExternalFlow(); f.auth.getOAuthUrl(Google, flow = flow)
        f.pause = true
        val old = async { stale { f.auth.importExternalCode(flow, flow.state, "synthetic-code") { fail() } } }
        f.entered.await(); f.owner("B"); f.auth.codeVerifierCache.saveCodeVerifier("successor")
        f.release.complete(Unit); old.await()
        assertEquals("successor", f.auth.codeVerifierCache.loadCodeVerifier())
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
        assertEquals(2, f.store.saves)
    }

    @Test fun replacingFlowDuringExchangeRejectsOldFlowEvenWithoutAccountChange() = scenario { f ->
        val oldFlow = f.auth.beginExternalFlow(); f.auth.getOAuthUrl(Google, flow = oldFlow)
        f.pause = true
        val old = async { stale { f.auth.importExternalCode(oldFlow, oldFlow.state, "synthetic-code") { fail() } } }
        f.entered.await()
        val newFlow = f.auth.beginExternalFlow(); f.auth.getOAuthUrl(Google, flow = newFlow)
        val verifier = f.auth.codeVerifierCache.loadCodeVerifier()
        f.release.complete(Unit); old.await()
        assertEquals(verifier, f.auth.codeVerifierCache.loadCodeVerifier())
        assertEquals(1, f.store.saves)
    }

    @Test fun foreignClientFlowCannotImportOrConsumeLocalVerifier() = scenario { f ->
        val foreign = Fixture(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
        try {
            foreign.auth.awaitInitialization()
            val flow = foreign.auth.beginExternalFlow()
            stale { f.auth.importExternalCode(flow, flow.state, "synthetic-code") { fail() } }
            assertEquals(0, f.requests)
            assertEquals(1, f.store.saves)
        } finally { foreign.client.close() }
    }

    private fun fragment(flow: AuthExternalFlow) = "access_token=synthetic-A&refresh_token=refresh-A&expires_in=86400&token_type=bearer&state=${flow.state}"

    @Test fun implicitCallbackPublishesBeforeDeliveryAndCannotReplay() = scenario { f ->
        f.auth.config.flowType = FlowType.IMPLICIT; f.body = USER_A
        val flow = f.auth.beginExternalFlow(); f.auth.getOAuthUrl(Google, flow = flow)
        var delivered = 0
        f.auth.parseFragmentAndImportSession(fragment(flow), flow) {
            delivered++; assertSame(it, f.auth.currentSessionOrNull()); assertSame(it, f.store.session)
        }
        stale { f.auth.parseFragmentAndImportSession(fragment(flow), flow) { delivered++ } }
        assertEquals(1, delivered)
        assertEquals(1, f.requests)
    }

    @Test fun implicitMissingOrDuplicateStateCannotFetchUserOrPublish() = scenario { f ->
        f.auth.config.flowType = FlowType.IMPLICIT
        val flow = f.auth.beginExternalFlow()
        for (bad in listOf(fragment(flow).substringBefore("&state="), fragment(flow) + "&state=${flow.state}")) {
            try { f.auth.parseFragmentAndImportSession(bad, flow) { fail() }; fail() }
            catch (_: IllegalStateException) { }
        }
        assertEquals(0, f.requests)
        assertEquals(1, f.store.saves)
    }

    @Test fun delayedImplicitUserReplyCannotDeliverBeforeOrAfterSuccessor() = scenario { f ->
        f.auth.config.flowType = FlowType.IMPLICIT; f.body = USER_A; f.pause = true
        val flow = f.auth.beginExternalFlow()
        val old = async { stale { f.auth.parseFragmentAndImportSession(fragment(flow), flow) { fail() } } }
        f.entered.await(); f.owner("B"); f.release.complete(Unit); old.await()
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
        assertEquals(2, f.store.saves)
    }

    companion object {
        private val JSON = headersOf(HttpHeaders.ContentType, "application/json")
        private const val USER_A = """{"aud":"authenticated","id":"A"}"""
        private const val SESSION_A = """{"access_token":"synthetic-A","refresh_token":"refresh-A","token_type":"bearer","expires_in":86400,"user":{"aud":"authenticated","id":"A"}}"""
    }
}
