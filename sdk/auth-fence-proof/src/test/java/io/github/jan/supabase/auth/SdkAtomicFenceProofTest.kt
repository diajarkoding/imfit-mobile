package io.github.jan.supabase.auth

import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual rebuilt Auth3.0.3 implementation, not a clone of its session algorithm. */
class SdkAtomicFenceProofTest {
    private class Store : SessionManager {
        var stored: UserSession? = null
        var save: suspend (UserSession) -> Unit = { stored = it }
        var load: suspend () -> UserSession? = { stored }
        override suspend fun saveSession(session: UserSession) = save(session)
        override suspend fun loadSession() = load()
        override suspend fun deleteSession() { stored = null }
    }

    private class Fixture(val store: Store = Store()) {
        var requests = 0
        var response: suspend () -> String = { error("Unexpected transport request") }
        val client = createSupabaseClient("https://fence-proof.invalid", "fixture-not-a-credential") {
            defaultLogLevel = LogLevel.NONE
            httpEngine = MockEngine { request ->
                check(request.url.host == "fence-proof.invalid")
                requests++
                respond(response(), headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            install(Auth) { minimalSettings(sessionManager = store) }
        }
        val auth get() = client.auth
        val gate get() = auth.sessionAuthority
        suspend fun ready() { auth.awaitInitialization() }
        suspend fun owner(id: String, token: String = "synthetic-$id") {
            auth.importSession(session(id, token), autoRefresh = false)
        }
        suspend fun close() { client.close() }
    }

    private fun scenario(test: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking<Unit> {
        withTimeout(15_000) {
            val fixture = Fixture()
            try { fixture.ready(); test(fixture) }
            finally { withContext(NonCancellable) { fixture.close() } }
        }
    }

    @Test fun realSdkImportsInvalidateOldLeaseAfterAccountABAAndSameOwnerLogin() = scenario { f ->
        f.owner("A")
        val first = f.gate.lease()
        f.owner("B"); f.owner("A")
        try { f.gate.commit(first) { fail("Old SDK epoch committed") }; fail() }
        catch (_: StaleAuthOperationException) { }
        val second = f.gate.lease()
        f.owner("A")
        try { f.gate.commit(second) { fail() }; fail() }
        catch (_: StaleAuthOperationException) { }
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        assertEquals(0, f.requests)
    }

    @Test fun realSdkPublicationWaitsForHeldLocalCommit() = scenario { f ->
        f.owner("A")
        val a = f.gate.lease()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = async { f.gate.commit(a) { entered.complete(Unit); release.await() } }
        entered.await()
        val transition = async(start = CoroutineStart.UNDISPATCHED) { f.owner("B") }
        assertFalse(transition.isCompleted)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        release.complete(Unit)
        writer.await(); transition.await()
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
    }

    @Test fun realSdkClearRejectsOldLeaseBeforeAnyLaterLocalCommit() = scenario { f ->
        f.owner("A")
        val a = f.gate.lease()
        f.auth.clearSession()
        assertNull(f.auth.currentUserOrNull())
        try { f.gate.commit(a) { fail() }; fail() }
        catch (_: StaleAuthOperationException) { }
    }

    @Test fun delayedRestoreCannotResurrectPreviousOwnerAfterNewLogin() = scenario { f ->
        f.owner("A")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.store.load = { entered.complete(Unit); release.await(); session("A", "stored-old-A") }
        val restore = async {
            try { f.auth.loadFromStorage(autoRefresh = false); false }
            catch (_: StaleAuthOperationException) { true }
        }
        entered.await(); f.owner("B"); release.complete(Unit)
        assertTrue(restore.await())
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
    }

    @Test fun staleRefreshMustNotReplaceCredentialAfterSdkAccountABA() = scenario { f ->
        f.owner("A", "original-A")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        f.response = {
            entered.complete(Unit); release.await()
            """{"access_token":"stale-refresh-A","refresh_token":"synthetic-refresh","token_type":"bearer","expires_in":3600,"user":{"aud":"authenticated","id":"A"}}"""
        }
        val refresh = async {
            try { f.auth.refreshCurrentSession() }
            catch (_: StaleAuthOperationException) { }
        }
        entered.await(); f.owner("B"); f.owner("A", "new-login-A"); release.complete(Unit)
        refresh.await()
        assertEquals("Old refresh must not replace new login credentials", "new-login-A", f.auth.currentAccessTokenOrNull())
        assertEquals(1, f.requests)
    }

    @Test fun partialStorageWriteFailureMustNotLeaveOldSdkIdentityPublishedAsValid() = scenario { f ->
        f.owner("A")
        f.auth.config.autoSaveToStorage = true
        f.store.save = { value -> f.store.stored = value; error("Injected write-then-fail") }
        try { f.owner("B"); fail("Expected persistence error") }
        catch (_: IllegalStateException) { }
        try { f.gate.lease(); fail("Poisoned authority must not authorize local commits") }
        catch (_: AuthAuthorityUnavailableException) { }
        assertNull("Old SDK identity must not remain externally valid after ambiguous persistence", f.auth.currentUserOrNull())
    }

    private companion object {
        fun session(owner: String, token: String) = UserSession(token, "synthetic-refresh-$owner",
            expiresIn = 86_400, tokenType = "bearer", user = UserInfo(aud = "authenticated", id = owner))
    }
}
