package io.github.jan.supabase.auth

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Actual Android processes, Binder barriers and self-SIGKILL; not reboot/power-loss evidence. */
class AndroidStoreProcessTest {
    private class Probe(context: Context, type: Class<out StoreProbeService>, private val directory: File) : AutoCloseable {
        private val context = context.applicationContext
        private val connected = CompletableDeferred<Messenger>()
        val disconnected = CompletableDeferred<Unit>()
        private val requests = ConcurrentHashMap<Int, CompletableDeferred<Bundle>>()
        private val ids = AtomicInteger()
        private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
            requests.remove(message.arg1)?.complete(message.data)
            true
        })
        private val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                connected.complete(Messenger(requireNotNull(service)))
            }
            override fun onServiceDisconnected(name: ComponentName?) { disconnected.complete(Unit) }
            override fun onBindingDied(name: ComponentName?) { disconnected.complete(Unit) }
        }
        private var bound = false

        init {
            bound = this.context.bindService(Intent(this.context, type), connection, Context.BIND_AUTO_CREATE)
            check(bound) { "Cannot bind private diagnostic process" }
        }

        suspend fun call(command: Int): Bundle {
            val target = connected.await()
            val id = ids.incrementAndGet()
            val pending = CompletableDeferred<Bundle>()
            requests[id] = pending
            try {
                target.send(Message.obtain(null, command).apply {
                    arg1 = id; replyTo = replies
                    data = Bundle().apply { putString("directory", directory.path); putInt("callerPid", Process.myPid()) }
                })
                return pending.await().also { check(it.getString("status") != "ERROR") { "Remote probe failed: ${it.getString("failureType")}" } }
            } finally { requests.remove(id) }
        }

        override fun close() {
            if (bound) { context.unbindService(connection); bound = false }
            requests.values.forEach { it.cancel() }
            requests.clear()
        }
    }

    private fun scenario(test: suspend CoroutineScope.(File, Context) -> Unit) = runBlocking<Unit> {
        withTimeout(30_000) {
            val directory = proofDirectory()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            try { test(directory, context) } finally { directory.deleteRecursively() }
        }
    }

    private suspend fun assertSdkRecovery(directory: File, expected: String?) {
        val client = createSupabaseClient("https://fence-proof.invalid", "synthetic-process-key") {
            defaultLogLevel = LogLevel.NONE
            httpEngine = MockEngine { error("Process recovery must make zero HTTP requests") }
            install(Auth) { minimalSettings(sessionManager = FileAtomicSessionStore(directory)) }
        }
        try {
            client.auth.awaitInitialization()
            assertEquals(expected != null, client.auth.loadFromStorage(autoRefresh = false))
            assertEquals(expected, client.auth.currentUserOrNull()?.id)
        } finally { client.close() }
    }

    @Test fun twoActualAndroidProcessesHaveExactlyOneCASWinner() = scenario { dir, context ->
        FileAtomicSessionStore(dir).committed("A")
        Probe(context, StoreProbeServiceA::class.java, dir).use { a ->
            Probe(context, StoreProbeServiceB::class.java, dir).use { b ->
                val first = a.call(ProbeCommand.SNAPSHOT)
                val second = b.call(ProbeCommand.SNAPSHOT)
                assertEquals("READY", first.getString("status")); assertEquals("READY", second.getString("status"))
                assertNotEquals(Process.myPid(), first.getInt("pid"))
                assertNotEquals(Process.myPid(), second.getInt("pid"))
                assertNotEquals(first.getInt("pid"), second.getInt("pid"))
                val jobs = listOf(a, b).map { probe -> async { probe to probe.call(ProbeCommand.PREPARE_B).getString("status") } }
                val results = jobs.awaitAll()
                assertEquals(1, results.count { it.second == "PREPARED" })
                assertEquals(1, results.count { it.second == "CONFLICT" })
                val committed = results.single { it.second == "PREPARED" }.first.call(ProbeCommand.COMMIT)
                assertEquals("B", committed.getString("owner"))
            }
        }
        assertSdkRecovery(dir, "B")
    }

    @Test fun killedPreparedWriterCannotRestoreStagedBOrFallBackToA() = scenario { dir, context ->
        FileAtomicSessionStore(dir).committed("A")
        var killedIncarnation: String? = null
        Probe(context, StoreProbeServiceA::class.java, dir).use { writer ->
            val result = writer.call(ProbeCommand.PREPARE_AND_DIE)
            assertEquals("PREPARED", result.getString("status"))
            assertNotEquals(Process.myPid(), result.getInt("pid"))
            killedIncarnation = result.getString("incarnation")
            writer.disconnected.await() // actual OS process death, not object/client recreation
        }
        assertTrue(FileAtomicSessionStore(dir).readCommitted() is StoreOutcome.Prepared)
        assertSdkRecovery(dir, null)
        Probe(context, StoreProbeServiceA::class.java, dir).use { recreated ->
            assertNotEquals(killedIncarnation, recreated.call(ProbeCommand.SNAPSHOT).getString("incarnation"))
        }
    }

    @Test fun killedCommittedWriterRestoresExactReceiptInFreshSdk() = scenario { dir, context ->
        FileAtomicSessionStore(dir).committed("A")
        Probe(context, StoreProbeServiceA::class.java, dir).use { writer ->
            val result = writer.call(ProbeCommand.COMMIT_AND_DIE)
            assertEquals("COMMITTED", result.getString("status"))
            assertNotEquals(Process.myPid(), result.getInt("pid"))
            writer.disconnected.await()
        }
        val bytes = File(dir, "session.v1").readBytes()
        assertSdkRecovery(dir, "B")
        assertArrayEquals(bytes, File(dir, "session.v1").readBytes())
    }

    @Test fun osReleasesDeadProcessLockAndBlockedReaderCompletes() = scenario { dir, context ->
        FileAtomicSessionStore(dir).committed("A")
        Probe(context, StoreProbeServiceA::class.java, dir).use { holder ->
            assertEquals("LOCKED", holder.call(ProbeCommand.HOLD_LOCK).getString("status"))
            val waiting = CompletableDeferred<Unit>()
            val reader = async {
                FileAtomicSessionStore(dir, AndroidAtomicStoreFiles) {
                    if (it == StorePoint.WAITING_FILE_LOCK) waiting.complete(Unit)
                }.readCommitted()
            }
            waiting.await()
            assertFalse(reader.isCompleted)
            assertEquals("DYING", holder.call(ProbeCommand.DIE).getString("status"))
            holder.disconnected.await()
            assertEquals("A", (reader.await() as StoreOutcome.VerifiedCommitted).session!!.user!!.id)
        }
        assertEquals("B", FileAtomicSessionStore(dir).committed("B").session!!.user!!.id)
    }

    @Test fun crossProcessLockWaitIsCancellableWithoutLeakingAnyLock() = scenario { dir, context ->
        FileAtomicSessionStore(dir).committed("A")
        Probe(context, StoreProbeServiceA::class.java, dir).use { holder ->
            assertEquals("LOCKED", holder.call(ProbeCommand.HOLD_LOCK).getString("status"))
            val waiting = CompletableDeferred<Unit>()
            val reader = launch {
                FileAtomicSessionStore(dir, AndroidAtomicStoreFiles) {
                    if (it == StorePoint.WAITING_FILE_LOCK) waiting.complete(Unit)
                }.readCommitted()
            }
            waiting.await(); reader.cancelAndJoin()
            assertTrue(reader.isCancelled)
            assertEquals("RELEASED", holder.call(ProbeCommand.RELEASE_LOCK).getString("status"))
            assertEquals("B", FileAtomicSessionStore(dir).committed("B").session!!.user!!.id)
        }
    }
}
