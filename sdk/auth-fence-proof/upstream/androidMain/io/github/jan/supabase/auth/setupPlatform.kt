package io.github.jan.supabase.auth

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.startup.Initializer
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.logging.e
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.Lifecycle

private var appContext: Context? = null

internal class SupabaseInitializer : Initializer<Context> {
    override fun create(context: Context): Context = context.applicationContext.also { appContext = it }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()

}

internal fun applicationContext(): Context = appContext ?: error("Application context not initialized")

@SupabaseInternal
actual fun Auth.setupPlatform() {
    addLifecycleCallbacks(this)
}

private fun addLifecycleCallbacks(gotrue: Auth) {
    if(!gotrue.config.enableLifecycleCallbacks) return
    (gotrue as AuthImpl).observeLifecycle(ProcessLifecycleOwner.get().lifecycle)
}

internal sealed interface AuthLifecycleSignal {
    class Control(val original: AuthOperation, val foreground: Boolean) : AuthLifecycleSignal
    class Barrier(val completion: CompletableDeferred<Unit>) : AuthLifecycleSignal
}

internal class AuthLifecycleBinding(
    val job: Job,
    private val events: Channel<AuthLifecycleSignal>,
    private val ready: CompletableDeferred<Unit>,
) {
    /** Await already queued callbacks without timing guesses or authorizing any mutation. */
    suspend fun awaitIdle() {
        val completion = CompletableDeferred<Unit>()
        val closed = job.invokeOnCompletion { completion.cancel() }
        try { ready.await(); events.send(AuthLifecycleSignal.Barrier(completion)); completion.await() }
        finally { closed.dispose() }
    }
}

/** One ordered mailbox and one owned observer per SDK; no current-job prefilter loses START. */
internal fun AuthImpl.observeLifecycle(lifecycle: Lifecycle): AuthLifecycleBinding {
    check(platformLifecycleJob == null) { "Lifecycle observer already registered" }
    val events = Channel<AuthLifecycleSignal>(Channel.UNLIMITED)
    val ready = CompletableDeferred<Unit>()
    val job = authScope.launch(Dispatchers.Main) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                events.trySend(AuthLifecycleSignal.Control(sessionAuthority.snapshotOperation(), true))
            }
            override fun onStop(owner: LifecycleOwner) {
                events.trySend(AuthLifecycleSignal.Control(sessionAuthority.snapshotOperation(), false))
            }
        }
        try {
            // Bootstrap owns initialization. Register against a fresh lifecycle state afterwards,
            // rather than carrying a pre-restore event across its new identity publication.
            awaitInitialization()
            lifecycle.addObserver(observer)
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                events.trySend(AuthLifecycleSignal.Control(sessionAuthority.snapshotOperation(), false))
            }
            ready.complete(Unit)
            for (event in events) {
                if (event is AuthLifecycleSignal.Barrier) { event.completion.complete(Unit); continue }
                event as AuthLifecycleSignal.Control
                try {
                    if (event.foreground) lifecycleForeground(event.original) else lifecycleBackground(event.original)
                } catch (_: StaleAuthOperationException) { /* No rebasing across a credential/account mutation. */ }
                  catch (cancelled: CancellationException) { throw cancelled }
                  catch (_: Exception) { Auth.logger.e { "Lifecycle control unavailable; no successor is modified" } }
            }
        } finally {
            events.cancel()
            withContext(NonCancellable + Dispatchers.Main.immediate) { lifecycle.removeObserver(observer) }
        }
    }
    platformLifecycleJob = job
    job.invokeOnCompletion { ready.cancel(); events.cancel() }
    return AuthLifecycleBinding(job, events, ready)
}
