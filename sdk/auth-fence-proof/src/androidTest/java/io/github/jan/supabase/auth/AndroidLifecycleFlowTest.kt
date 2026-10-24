package io.github.jan.supabase.auth

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Actual Android LifecycleRegistry + the same observer registered on ProcessLifecycleOwner. */
class AndroidLifecycleFlowTest {
    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        init { registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE) }
    }

    private class Fixture {
        var requests = 0
        var pauseSaveB = false
        val saveEntered = CompletableDeferred<Unit>()
        val releaseSave = CompletableDeferred<Unit>()
        val client = createSupabaseClient("https://android-lifecycle-proof.invalid", "synthetic-key") {
            defaultLogLevel = LogLevel.NONE
            httpEngine = MockEngine { request ->
                check(request.url.host == "android-lifecycle-proof.invalid")
                requests++
                respond("""{"access_token":"synthetic-A","refresh_token":"synthetic-refresh-A","token_type":"bearer","expires_in":86400,"user":{"aud":"authenticated","id":"A"}}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
            install(Auth) {
                minimalSettings(autoSaveToStorage = true, sessionManager = object : SessionManager {
                    override suspend fun loadSession() = null
                    override suspend fun saveSession(session: io.github.jan.supabase.auth.user.UserSession) {
                        if (pauseSaveB && session.user?.id == "B") { saveEntered.complete(Unit); releaseSave.await() }
                    }
                    override suspend fun deleteSession() = Unit
                })
                flowType = FlowType.PKCE
                scheme = "proof"; host = "callback"
            }
        }
        val auth get() = client.auth as AuthImpl
    }

    private fun scenario(test: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking<Unit> {
        withTimeout(15_000) {
            val f = Fixture()
            try { f.auth.awaitInitialization(); f.auth.importSession(syntheticSession("A"), autoRefresh = false); test(f) }
            finally { f.releaseSave.complete(Unit); withContext(NonCancellable) { f.client.close() } }
        }
    }

    @Test fun rapidRegistryStopStartPreservesFlowAndDoesNotLoseQueuedStart() = scenario { f ->
        f.auth.config.alwaysAutoRefresh = true
        f.auth.startAutoRefreshForCurrentSession()
        val flow = f.auth.beginExternalFlow(); f.auth.getOAuthUrl(Google, flow = flow)
        val (owner, binding) = withContext(Dispatchers.Main) {
            val owner = Owner()
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner to f.auth.observeLifecycle(owner.lifecycle)
        }
        binding.awaitIdle()
        withContext(Dispatchers.Main) {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        binding.awaitIdle()
        assertTrue(f.auth.isAutoRefreshRunning)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        f.auth.exchangeCodeForSession("synthetic-code", flow = flow, state = flow.state)
        assertEquals(1, f.requests)
    }

    @Test fun rapidRegistryStopStartStopEndsPausedWithAuthenticationIntact() = scenario { f ->
        f.auth.config.alwaysAutoRefresh = true
        val (owner, binding) = withContext(Dispatchers.Main) {
            val owner = Owner(); owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner to f.auth.observeLifecycle(owner.lifecycle)
        }
        binding.awaitIdle()
        withContext(Dispatchers.Main) {
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        binding.awaitIdle()
        assertFalse(f.auth.isAutoRefreshRunning)
        assertTrue(f.auth.sessionStatus.value is SessionStatus.Authenticated)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
    }

    @Test fun closeRemovesOnlyItsOwnedRegistryObserverBeforeReturning() = scenario { f ->
        val (owner, binding) = withContext(Dispatchers.Main) {
            val owner = Owner(); owner to f.auth.observeLifecycle(owner.lifecycle)
        }
        binding.awaitIdle()
        assertEquals(1, withContext(Dispatchers.Main) { owner.registry.observerCount })
        f.client.close()
        assertTrue(binding.job.isCompleted)
        assertEquals(0, withContext(Dispatchers.Main) { owner.registry.observerCount })
        withContext(Dispatchers.Main) { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START) }
        assertNull(f.auth.currentSessionOrNull()); assertEquals(0, f.requests)
    }

    @Test fun actualProcessLifecycleOwnerRegistrationIsRemovedOnClose() = scenario { f ->
        val (registry, before, binding) = withContext(Dispatchers.Main) {
            val registry = ProcessLifecycleOwner.get().lifecycle as LifecycleRegistry
            Triple(registry, registry.observerCount, f.auth.observeLifecycle(registry))
        }
        binding.awaitIdle()
        assertEquals(before + 1, withContext(Dispatchers.Main) { registry.observerCount })
        f.client.close()
        assertEquals(before, withContext(Dispatchers.Main) { registry.observerCount })
        assertTrue(binding.job.isCompleted)
    }

    @Test fun registrationInCreatedStateAppliesAFreshPausedObservation() = scenario { f ->
        f.auth.config.alwaysAutoRefresh = true
        f.auth.startAutoRefreshForCurrentSession()
        val (owner, binding) = withContext(Dispatchers.Main) {
            val owner = Owner(); owner to f.auth.observeLifecycle(owner.lifecycle)
        }
        binding.awaitIdle()
        assertFalse(f.auth.isAutoRefreshRunning)
        assertEquals("A", f.auth.currentUserOrNull()!!.id)
        f.auth.importSession(syntheticSession("A"), autoRefresh = true)
        assertFalse(f.auth.isAutoRefreshRunning)
        withContext(Dispatchers.Main) { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START) }
        binding.awaitIdle()
        assertTrue(f.auth.isAutoRefreshRunning)
    }

    @Test fun queuedRegistryEventCannotAuthorizeAccountSuccessor() = scenario { f ->
        val (owner, binding) = withContext(Dispatchers.Main) {
            val owner = Owner(); owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            owner to f.auth.observeLifecycle(owner.lifecycle)
        }
        binding.awaitIdle()
        f.pauseSaveB = true
        val login = async { f.auth.importSession(syntheticSession("B"), autoRefresh = false) }
        f.saveEntered.await() // B is already inside its actual SDK publication fence.
        withContext(Dispatchers.Main) { owner.registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP) }
        f.releaseSave.complete(Unit); login.await(); binding.awaitIdle()
        assertEquals("B", f.auth.currentUserOrNull()!!.id)
        assertEquals(0, f.requests)
    }
}
