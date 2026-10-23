package io.github.jan.supabase.auth

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

/** Actual Android POSIX rename/file fsync/directory fsync. Not an OS-kill or power-loss proof. */
class AndroidAtomicStoreDeviceTest {
    private fun scenario(test: suspend CoroutineScope.(File) -> Unit) = runBlocking<Unit> {
        withTimeout(20_000) {
            val directory = proofDirectory()
            try { test(directory) } finally { directory.deleteRecursively() }
        }
    }

    @Test fun androidDefaultAdapterCommitsAndFreshSdkRestoresWithoutResave() = scenario { dir ->
        val original = FileAtomicSessionStore(dir).committed("B")
        val before = File(dir, "session.v1").readBytes()
        val client = createSupabaseClient("https://fence-proof.invalid", "synthetic-key") {
            defaultLogLevel = LogLevel.NONE
            httpEngine = MockEngine { error("Android storage fixture must make zero HTTP requests") }
            install(Auth) { minimalSettings(sessionManager = FileAtomicSessionStore(dir)) }
        }
        try {
            client.auth.awaitInitialization()
            client.auth.config.autoSaveToStorage = true
            assertTrue(client.auth.loadFromStorage(autoRefresh = false))
            assertEquals("B", client.auth.currentUserOrNull()!!.id)
            assertArrayEquals(before, File(dir, "session.v1").readBytes())
            assertEquals(original.ticket, (FileAtomicSessionStore(dir).readCommitted() as StoreOutcome.VerifiedCommitted).ticket)
        } finally { client.close() }
    }

    @Test fun androidActualPrepareAndFinalCommitFailpointsResolveExactReceipt() = scenario { dir ->
        val points = listOf(StorePoint.BEFORE_STAGE_WRITE, StorePoint.AFTER_STAGE_SYNC, StorePoint.AFTER_REPLACE,
            StorePoint.AFTER_DIRECTORY_SYNC, StorePoint.BEFORE_READBACK)
        for (point in points) {
            val sandbox = File(dir, "prepare-$point").also { check(it.mkdir()) }
            val failing = FileAtomicSessionStore(sandbox, AndroidAtomicStoreFiles) {
                if (it == point) error("synthetic Android prepare fault")
            }
            assertTrue(failing.prepare(null, UUID.randomUUID().toString(), syntheticSession("B")) is StoreOutcome.Unavailable)
            assertFalse(FileAtomicSessionStore(sandbox).readCommitted() is StoreOutcome.VerifiedCommitted)
        }
        for (point in points + StorePoint.AFTER_COMMIT) {
            val sandbox = File(dir, "commit-$point").also { check(it.mkdir()) }
            val ticket = FileAtomicSessionStore(sandbox).stage("B")
            val failing = FileAtomicSessionStore(sandbox, AndroidAtomicStoreFiles) {
                if (it == point) error("synthetic Android commit fault")
            }
            assertTrue(failing.commitIfCurrent(ticket) is StoreOutcome.Unavailable)
            val resolved = FileAtomicSessionStore(sandbox).resolve(ticket)
            if (point in listOf(StorePoint.BEFORE_STAGE_WRITE, StorePoint.AFTER_STAGE_SYNC)) {
                assertTrue(resolved is StoreOutcome.Prepared)
            } else assertEquals("B", (resolved as StoreOutcome.VerifiedCommitted).session!!.user!!.id)
        }
    }

    @Test fun androidActualTwoHandlesHaveOneCASWinner() = scenario { dir ->
        val initial = FileAtomicSessionStore(dir).committed("A")
        val start = CompletableDeferred<Unit>()
        val attempts = (1..10).map {
            async(Dispatchers.Default) {
                start.await()
                FileAtomicSessionStore(dir).prepare(initial.ticket.head, UUID.randomUUID().toString(), syntheticSession("B"))
            }
        }
        start.complete(Unit)
        val results = attempts.awaitAll()
        assertEquals(1, results.count { it is StoreOutcome.Prepared })
        assertEquals(9, results.count { it is StoreOutcome.Conflict })
        val ticket = results.filterIsInstance<StoreOutcome.Prepared>().single().ticket
        assertTrue(FileAtomicSessionStore(dir).commitIfCurrent(ticket) is StoreOutcome.VerifiedCommitted)
    }
}
