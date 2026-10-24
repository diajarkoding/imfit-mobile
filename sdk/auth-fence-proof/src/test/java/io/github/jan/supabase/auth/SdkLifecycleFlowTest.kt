package io.github.jan.supabase.auth

import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Clock
import org.junit.Assert.*
import org.junit.Test
import io.github.jan.supabase.auth.status.SessionSource
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Duration.Companion.seconds

/** Lifecycle-only receipt continuity; no real browser/provider or normal application binding. */
class SdkLifecycleFlowTest {
    private class Store : SessionManager {
        var value: UserSession? = null
        var saves = 0
        var loads = 0
        var deletes = 0
        override suspend fun loadSession(): UserSession? { loads++; return value }
        override suspend fun saveSession(session: UserSession) { saves++; value = session }
        override suspend fun deleteSession() { deletes++; value = null }
    }

    private class Fixture(dispatcher: CoroutineDispatcher) {
        val store = Store()
        val verifier = MemoryCodeVerifierCache("synthetic-existing")
        var requests = 0
        var pause = false
        var firstNetworkFailure = false
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = createSupabaseClient("https://lifecycle-flow-proof.invalid", "synthetic-key") {
            defaultLogLevel = LogLevel.NONE
            httpEngine = MockEngine { request ->
                check(request.url.host == "lifecycle-flow-proof.invalid")
                requests++
                if (firstNetworkFailure && requests == 1) throw java.io.IOException("synthetic offline transport")
                if (pause) { entered.complete(Unit); release.await() }
                val body = if (request.url.encodedPath.endsWith("/user")) """{"aud":"authenticated","id":"A"}""" else SESSION_A
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            install(Auth) {
                minimalSettings(sessionManager = store, autoSaveToStorage = true)
                coroutineDispatcher = dispatcher
                codeVerifierCache = verifier
                flowType = FlowType.PKCE
                scheme = "proof"; host = "callback"
            }
        }
        val auth get() = client.auth as AuthImpl
        suspend fun owner(owner: String) = auth.importSession(syntheticSession(owner), autoRefresh = false)
        suspend fun flow(): AuthExternalFlow = auth.beginExternalFlow().also { auth.getOAuthUrl(Google, flow = it) }
    }

    private fun scenario(test: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking<Unit> {
        withTimeout(15_000) {
            val f = Fixture(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
            try { f.auth.awaitInitialization(); f.owner("A"); test(f) }
            finally { f.release.complete(Unit); withContext(NonCancellable) { f.client.close() } }
        }
    }

    private suspend fun stale(action: suspend () -> Unit) {
        try { action(); fail("Obsolete context must be rejected") }
        catch (_: StaleAuthOperationException) { }
    }

    @Test fun stopPreservesEligibleInMemoryAuthenticationAndLease() = scenario { f ->
        val session = f.auth.currentSessionOrNull(); val lease = f.auth.sessionAuthority.lease()
        f.auth.lifecycleBackground(f.auth.sessionAuthority.snapshotOperation())
        assertSame(session, f.auth.currentSessionOrNull())
        assertTrue(f.auth.sessionStatus.value is SessionStatus.Authenticated)
        f.auth.sessionAuthority.commit(lease) { }
        assertFalse(f.auth.isAutoRefreshRunning)
        assertEquals(1, f.store.saves); assertEquals(0, f.store.loads); assertEquals(0, f.store.deletes)
    }

    @Test fun retainedHandleSurvivesOnlyExactStopStartReceiptsBeforeCallback() = scenario { f ->
        val flow = f.flow(); val verifier = f.verifier.loadCodeVerifier()
        val original = flow.call.operation; val event = f.auth.sessionAuthority.snapshotOperation()
        f.auth.config.alwaysAutoRefresh = true
        f.auth.lifecycleBackground(event); f.auth.lifecycleForeground(f.auth.sessionAuthority.snapshotOperation())
        assertEquals(verifier, f.verifier.loadCodeVerifier())
        stale { f.auth.sessionAuthority.validate(original) }
        assertEquals("A", f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state).user!!.id)
        assertEquals(1, f.requests); assertEquals(2, f.store.saves); assertNull(f.verifier.loadCodeVerifier())
    }

    @Test fun queuedStartFromSameOriginalEventFollowsOnlyItsControlLineage() = scenario { f ->
        f.auth.config.alwaysAutoRefresh = true
        f.auth.startAutoRefreshForCurrentSession()
        val task = f.auth.sessionJob; val event = f.auth.sessionAuthority.snapshotOperation()
        f.auth.lifecycleBackground(event); f.auth.lifecycleForeground(event)
        assertTrue(task!!.isCancelled)
        assertTrue(f.auth.isAutoRefreshRunning)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        assertEquals(0, f.store.loads); assertEquals(1, f.store.saves)
    }

    @Test fun rapidStopStartStopWithSharedCaptureFinishesPausedWithoutLosingIdentity() = scenario { f ->
        f.auth.config.alwaysAutoRefresh = true
        val flow = f.flow(); val event = f.auth.sessionAuthority.snapshotOperation()
        f.auth.lifecycleBackground(event); f.auth.lifecycleForeground(event); f.auth.lifecycleBackground(event)
        assertFalse(f.auth.isAutoRefreshRunning)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        assertEquals("A", f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state).user!!.id)
        assertFalse("Callback publication while paused must not restart background refresh", f.auth.isAutoRefreshRunning)
    }

    @Test fun oldNormalOperationsRemainStrictDespiteLifecycleEventContinuity() = scenario { f ->
        val event = f.auth.sessionAuthority.snapshotOperation(); val lease = f.auth.sessionAuthority.lease()
        f.auth.lifecycleBackground(event)
        stale { f.auth.sessionAuthority.validate(event) }
        withContext(AuthCallContext(f.auth, event, f.auth.currentSessionOrNull())) { stale { f.auth.retrieveUser("synthetic-A") } }
        f.auth.sessionAuthority.commit(lease) { }
        assertEquals(0, f.requests)
    }

    @Test fun accountSwitchBreaksQueuedEventAndHandleLineage() = scenario { f ->
        val flow = f.flow(); val event = f.auth.sessionAuthority.snapshotOperation()
        f.auth.lifecycleBackground(event); f.owner("B")
        val lease = f.auth.sessionAuthority.lease()
        stale { f.auth.lifecycleForeground(event) }
        stale { f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state) }
        f.auth.sessionAuthority.commit(lease) { }
        assertEquals("B", f.auth.currentUserOrNull()!!.id); assertEquals(0, f.requests)
    }

    @Test fun manualJobControlDoesNotGrantLifecycleFlowContinuity() = scenario { f ->
        val flow = f.flow(); val event = f.auth.sessionAuthority.snapshotOperation()
        f.auth.lifecycleBackground(event); f.auth.stopAutoRefreshForCurrentSession()
        stale { f.auth.lifecycleForeground(event) }
        stale { f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state) }
        assertEquals(0, f.requests)
    }

    @Test fun callbackAlreadyInFlightIsNeverReboundAcrossLifecycleControl() = scenario { f ->
        val flow = f.flow(); val verifier = f.verifier.loadCodeVerifier(); f.pause = true
        val callback = async { stale { f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state) } }
        f.entered.await(); f.auth.lifecycleBackground(f.auth.sessionAuthority.snapshotOperation())
        f.release.complete(Unit); callback.await()
        assertEquals(verifier, f.verifier.loadCodeVerifier()); assertEquals(1, f.store.saves)
        assertEquals(1, f.requests)
    }

    @Test fun sameOwnerRenewalBreaksTheOldLifecycleLineageAndHandle() = scenario { f ->
        val flow = f.flow(); val old = f.auth.sessionAuthority.snapshotOperation()
        f.auth.lifecycleBackground(old)
        val session = f.auth.currentSessionOrNull()!!
        f.auth.importSession(session.copy(accessToken = "synthetic-renewed"), false, SessionSource.Refresh(session))
        val lease = f.auth.sessionAuthority.lease()
        stale { f.auth.lifecycleForeground(old) }
        stale { f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state) }
        f.auth.sessionAuthority.commit(lease) { }
        assertEquals(0, f.requests); assertEquals(2, f.store.saves)
    }

    @Test fun accountABACannotResurrectAFormerLifecycleLineage() = scenario { f ->
        val flow = f.flow(); val event = f.auth.sessionAuthority.snapshotOperation()
        f.auth.lifecycleBackground(event); f.owner("B"); f.owner("A")
        stale { f.auth.lifecycleForeground(event) }
        stale { f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state) }
        assertEquals("A", f.auth.currentUserOrNull()!!.id); assertEquals(0, f.requests)
    }

    @Test fun foreignAuthorityCannotUseTheLifecycleReceiptRange() = scenario { f ->
        val foreign = AtomicSessionAuthority().operation()
        f.auth.lifecycleBackground(f.auth.sessionAuthority.snapshotOperation())
        stale { f.auth.lifecycleForeground(foreign) }
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
    }

    @Test fun replacementFlowDoesNotInheritTheOldHandleThroughLaterControls() = scenario { f ->
        val old = f.flow(); val event = f.auth.sessionAuthority.snapshotOperation()
        f.auth.lifecycleBackground(event)
        val replacement = f.flow()
        f.auth.lifecycleForeground(f.auth.sessionAuthority.snapshotOperation())
        stale { f.auth.exchangeCodeForSession("synthetic-code", flow = old, state = old.state) }
        f.auth.exchangeCodeForSession("synthetic-code", flow = replacement, state = replacement.state)
        assertEquals(1, f.requests)
    }

    @Test fun callbackPublicationBreaksAnyQueuedPreLoginLifecycleEvents() = scenario { f ->
        val flow = f.flow(); val event = f.auth.sessionAuthority.snapshotOperation()
        f.auth.lifecycleBackground(event)
        f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state)
        val lease = f.auth.sessionAuthority.lease()
        stale { f.auth.lifecycleForeground(event) }
        f.auth.sessionAuthority.commit(lease) { }
        assertEquals(2, f.store.saves)
    }

    @Test fun stopStartControlsWaitForTheSameAwaitedCommitFence() = scenario { f ->
        val held = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val lease = f.auth.sessionAuthority.lease(); val event = f.auth.sessionAuthority.snapshotOperation()
        val commit = async { f.auth.sessionAuthority.commit(lease) { held.complete(Unit); release.await() } }
        held.await()
        val stop = async(start = CoroutineStart.UNDISPATCHED) { f.auth.lifecycleBackground(event) }
        assertFalse(stop.isCompleted)
        release.complete(Unit); commit.await(); stop.await()
        f.auth.lifecycleForeground(event)
        f.auth.sessionAuthority.commit(lease) { }
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
    }

    @Test fun cancelledWaitingControlDoesNotAdvanceLineageOrRebindTheHandle() = scenario { f ->
        val flow = f.flow(); val original = flow.call
        val held = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val commit = async { f.auth.sessionAuthority.commit(f.auth.sessionAuthority.lease()) { held.complete(Unit); release.await() } }
        held.await()
        val stop = async { f.auth.lifecycleBackground(original.operation) }
        stop.cancelAndJoin(); release.complete(Unit); commit.await()
        assertSame(original, flow.call)
        f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state)
        assertEquals(1, f.requests)
    }

    @Test fun cancelledInFlightCallbackCannotBeRetriedViaLifecycleRebinding() = scenario { f ->
        val flow = f.flow(); f.pause = true
        val callback = async { f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state) }
        f.entered.await()
        f.auth.lifecycleBackground(f.auth.sessionAuthority.snapshotOperation())
        callback.cancelAndJoin()
        stale { f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state) }
        assertEquals(1, f.requests); assertEquals(1, f.store.saves)
    }

    @Test fun implicitCallbackCanUseTheSameHandleAfterACompletedControlRoundtrip() = scenario { f ->
        f.auth.config.flowType = FlowType.IMPLICIT
        val flow = f.flow(); val event = f.auth.sessionAuthority.snapshotOperation()
        f.auth.lifecycleBackground(event); f.auth.lifecycleForeground(event)
        val fragment = "access_token=synthetic-A&refresh_token=synthetic-A&token_type=bearer&expires_in=86400&state=${flow.state}"
        var delivered = 0
        // Lookup endpoint returns a UserInfo, not the token endpoint's session response.
        f.auth.importExternalFragment(flow, fragment) { delivered++; assertSame(it, f.auth.currentSessionOrNull()) }
        assertEquals(1, delivered)
    }

    @Test fun closedAuthorityRejectsQueuedEventsAndRetainedHandleWithoutStoragePurge() = scenario { f ->
        val flow = f.flow(); val event = f.auth.sessionAuthority.snapshotOperation()
        f.auth.lifecycleBackground(event); f.client.close()
        stale { f.auth.lifecycleForeground(event) }
        stale { f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state) }
        assertNull(f.auth.currentSessionOrNull()); assertEquals(0, f.store.deletes); assertEquals(0, f.requests)
    }

    @Test fun noSessionForegroundDoesNotInventLegacyRestoreOrNewLogin() = scenario { f ->
        f.auth.clearSession()
        val event = f.auth.sessionAuthority.snapshotOperation()
        f.auth.lifecycleBackground(event); f.auth.lifecycleForeground(event)
        assertTrue(f.auth.sessionStatus.value is SessionStatus.NotAuthenticated)
        assertNull(f.auth.currentSessionOrNull()); assertEquals(0, f.store.loads); assertEquals(0, f.requests)
    }

    @Test fun bootstrapStopDoesNotInvalidateInitializationAndSuppressesLaterScheduling() = runBlocking<Unit> {
        withTimeout(15_000) {
            val f = Fixture(coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
            try {
                val bootstrap = f.auth.sessionAuthority.snapshotOperation()
                f.auth.lifecycleBackground(bootstrap)
                f.auth.awaitInitialization()
                f.auth.importSession(syntheticSession("A"), autoRefresh = true)
                assertFalse(f.auth.isAutoRefreshRunning)
                f.auth.lifecycleForeground(f.auth.sessionAuthority.snapshotOperation())
                f.auth.config.alwaysAutoRefresh = true
                f.auth.lifecycleForeground(f.auth.sessionAuthority.snapshotOperation())
                assertTrue(f.auth.isAutoRefreshRunning)
            } finally { f.client.close() }
        }
    }

    @Test fun stoppedTransientRefreshFailureResumesOnlyItsExactRetainedCredential() = scenario { f ->
        f.auth.config.alwaysAutoRefresh = true
        f.auth.config.retryDelay = 30.seconds
        f.firstNetworkFailure = true
        val due = syntheticSession("A").copy(expiresIn = 100, expiresAt = Clock.System.now() + 10.seconds)
        f.auth.importSession(due, autoRefresh = true)
        f.auth.sessionStatus.first { it is SessionStatus.RefreshFailure }
        val event = f.auth.sessionAuthority.snapshotOperation()
        val lease = f.auth.sessionAuthority.lease()
        f.auth.lifecycleBackground(event)
        f.auth.lifecycleForeground(event)
        requireNotNull(f.auth.sessionJob).join()
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        f.auth.sessionAuthority.commit(lease) { }
        assertEquals(2, f.requests); assertEquals(0, f.store.loads); assertEquals(0, f.store.deletes)
    }

    companion object {
        private const val SESSION_A = """{"access_token":"synthetic-A","refresh_token":"synthetic-refresh-A","token_type":"bearer","expires_in":86400,"user":{"aud":"authenticated","id":"A"}}"""
    }
}
