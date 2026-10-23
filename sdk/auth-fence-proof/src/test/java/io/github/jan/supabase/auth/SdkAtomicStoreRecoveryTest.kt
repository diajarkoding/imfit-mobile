package io.github.jan.supabase.auth

import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class SdkAtomicStoreRecoveryTest {
    private fun client(store: SessionManager) = createSupabaseClient("https://fence-proof.invalid", "synthetic-key") {
        defaultLogLevel = LogLevel.NONE
        httpEngine = MockEngine { error("Storage recovery must make zero HTTP requests") }
        install(Auth) { minimalSettings(sessionManager = store) }
    }

    private fun scenario(test: suspend CoroutineScope.(File) -> Unit) = runBlocking<Unit> {
        withTimeout(15_000) {
            val directory = proofDirectory()
            try { test(directory) } finally { directory.deleteRecursively() }
        }
    }

    @Test fun simulatedDirectoryDurabilityFreshSdkRestoresExactCommittedOwner() = scenario { dir ->
        val initial = client(FileAtomicSessionStore(dir, ProtocolTestFiles))
        try {
            initial.auth.awaitInitialization()
            initial.auth.config.autoSaveToStorage = true
            initial.auth.importSession(syntheticSession("B"), autoRefresh = false)
        } finally { initial.close() }
        val file = File(dir, "session.v1")
        val before = file.readBytes()
        val fresh = client(FileAtomicSessionStore(dir, ProtocolTestFiles))
        try {
            fresh.auth.awaitInitialization()
            fresh.auth.config.autoSaveToStorage = true
            assertTrue(fresh.auth.loadFromStorage(autoRefresh = false))
            assertEquals("B", fresh.auth.currentUserOrNull()!!.id)
            assertEquals("B", fresh.auth.sessionAuthority.lease().ownerId)
            assertArrayEquals("Recovery must not resave/migrate the committed record", before, file.readBytes())
        } finally { fresh.close() }
    }

    @Test fun actualPlatformDirectorySyncAndFreshSdkRecovery() = scenario { dir ->
        // Windows JVM cannot open/fsync a directory. Do not substitute a no-op and claim durability.
        val supported = try { HostDurableTestFiles.syncDirectory(dir); true } catch (_: Exception) { false }
        if (!supported) {
            val ticket = FileAtomicSessionStore(dir, ProtocolTestFiles).committed("B").ticket
            val strict = FileAtomicSessionStore(dir, HostDurableTestFiles)
            assertTrue(strict.readCommitted() is StoreOutcome.Unavailable)
            assertFalse(strict.publishIfCurrent(ticket) { fail("Unsupported durability published") })
        }
        assumeTrue("Actual directory sync unavailable on this host; Android verification remains required", supported)
        FileAtomicSessionStore(dir, HostDurableTestFiles).committed("B")
        val fresh = client(FileAtomicSessionStore(dir, HostDurableTestFiles))
        try {
            fresh.auth.awaitInitialization()
            assertTrue(fresh.auth.loadFromStorage(autoRefresh = false))
            assertEquals("B", fresh.auth.currentUserOrNull()!!.id)
        } finally { fresh.close() }
    }

    @Test fun simulatedFinalCommitExceptionFailsClosedThenExactCommittedRecoverySucceeds() = scenario { dir ->
        val failing = FileAtomicSessionStore(dir, ProtocolTestFiles) {
            if (it == StorePoint.AFTER_COMMIT) error("synthetic committed receipt exception")
        }
        val first = client(failing)
        try {
            first.auth.awaitInitialization()
            first.auth.config.autoSaveToStorage = true
            try { first.auth.importSession(syntheticSession("B"), autoRefresh = false); fail() }
            catch (_: AuthStorageUnavailableException) { }
            assertNull(first.auth.currentUserOrNull())
            try { first.auth.sessionAuthority.lease(); fail() } catch (_: AuthAuthorityUnavailableException) { }
        } finally { first.close() }
        val fresh = client(FileAtomicSessionStore(dir, ProtocolTestFiles))
        try {
            fresh.auth.awaitInitialization()
            assertTrue(fresh.auth.loadFromStorage(autoRefresh = false))
            assertEquals("B", fresh.auth.currentUserOrNull()!!.id)
        } finally { fresh.close() }
    }

    @Test fun stagedBOrLegacyCanNeverBecomeSdkRecovery() = scenario { dir ->
        val store = FileAtomicSessionStore(dir, ProtocolTestFiles)
        store.committed("A"); store.stage("B")
        val fresh = client(store)
        try {
            fresh.auth.awaitInitialization()
            assertFalse(fresh.auth.loadFromStorage(autoRefresh = false))
            assertNull(fresh.auth.currentUserOrNull())
        } finally { fresh.close() }
        val file = File(dir, "session.v1")
        val legacy = "raw-synthetic-legacy".toByteArray()
        file.writeBytes(legacy)
        val legacyClient = client(FileAtomicSessionStore(dir, ProtocolTestFiles))
        try {
            legacyClient.auth.awaitInitialization()
            assertFalse(legacyClient.auth.loadFromStorage(autoRefresh = false))
            assertNull(legacyClient.auth.currentUserOrNull())
            assertArrayEquals(legacy, file.readBytes())
        } finally { legacyClient.close() }
    }

    @Test fun sdkRestoreRechecksDurableHeadAfterReadBeforePublication() = scenario { dir ->
        val actual = FileAtomicSessionStore(dir, ProtocolTestFiles)
        actual.committed("A")
        val captured = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delayed = object : AtomicSessionStore by actual {
            override suspend fun readCommitted(): StoreOutcome {
                val value = actual.readCommitted()
                captured.complete(Unit); release.await()
                return value
            }
        }
        val fresh = client(delayed)
        try {
            fresh.auth.awaitInitialization()
            val restore = async {
                try { fresh.auth.loadFromStorage(autoRefresh = false); false }
                catch (_: AuthStorageUnavailableException) { true }
            }
            captured.await()
            actual.committed("B")
            release.complete(Unit)
            assertTrue(restore.await())
            assertNull(fresh.auth.currentUserOrNull())
        } finally { release.complete(Unit); fresh.close() }
    }

    @Test fun sdkCancelledAfterCommitCannotPublishButFreshClientCanRecover() = scenario { dir ->
        lateinit var writer: Job
        val store = FileAtomicSessionStore(dir, ProtocolTestFiles) {
            if (it == StorePoint.AFTER_COMMIT) writer.cancel()
        }
        val first = client(store)
        try {
            first.auth.awaitInitialization()
            first.auth.config.autoSaveToStorage = true
            writer = launch(start = CoroutineStart.LAZY) { first.auth.importSession(syntheticSession("B"), autoRefresh = false) }
            writer.start(); writer.join()
            assertTrue(writer.isCancelled)
            assertNull(first.auth.currentUserOrNull())
        } finally { first.close() }
        val fresh = client(FileAtomicSessionStore(dir, ProtocolTestFiles))
        try {
            fresh.auth.awaitInitialization()
            assertTrue(fresh.auth.loadFromStorage(autoRefresh = false))
            assertEquals("B", fresh.auth.currentUserOrNull()!!.id)
        } finally { fresh.close() }
    }

    @Test fun sdkShutdownRejectsDelayedRestoreAndKeepsDurableRecord() = scenario { dir ->
        val actual = FileAtomicSessionStore(dir, ProtocolTestFiles)
        val committed = actual.committed("B")
        val captured = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delayed = object : AtomicSessionStore by actual {
            override suspend fun readCommitted(): StoreOutcome {
                val result = actual.readCommitted()
                captured.complete(Unit); release.await()
                return result
            }
        }
        val original = client(delayed)
        try {
            original.auth.awaitInitialization()
            val restore = async {
                try { original.auth.loadFromStorage(autoRefresh = false); false }
                catch (_: StaleAuthOperationException) { true }
            }
            captured.await()
            original.close()
            release.complete(Unit)
            assertTrue(restore.await())
            assertNull(original.auth.currentUserOrNull())
            try { original.auth.sessionAuthority.lease(); fail() } catch (_: AuthAuthorityUnavailableException) { }
            try { original.auth.importSession(syntheticSession("A"), autoRefresh = false); fail() }
            catch (_: AuthAuthorityUnavailableException) { }
            assertEquals(committed.ticket, (actual.readCommitted() as StoreOutcome.VerifiedCommitted).ticket)
        } finally { release.complete(Unit); original.close() }
        val recreated = client(FileAtomicSessionStore(dir, ProtocolTestFiles))
        try {
            recreated.auth.awaitInitialization()
            assertTrue(recreated.auth.loadFromStorage(autoRefresh = false))
            assertEquals("B", recreated.auth.currentUserOrNull()!!.id)
        } finally { recreated.close() }
    }

    @Test fun cancelledCallerStillCompletesSdkShutdownWithoutDeletingStorage() = scenario { dir ->
        val store = FileAtomicSessionStore(dir, ProtocolTestFiles)
        val original = client(store)
        try {
            original.auth.awaitInitialization()
            original.auth.config.autoSaveToStorage = true
            original.auth.importSession(syntheticSession("B"), autoRefresh = false)
            val oldLease = original.auth.sessionAuthority.lease()
            val before = File(dir, "session.v1").readBytes()
            val closer = launch(start = CoroutineStart.UNDISPATCHED) {
                currentCoroutineContext().cancel()
                original.auth.close()
            }
            closer.join()
            assertTrue(closer.isCancelled)
            assertNull(original.auth.currentUserOrNull())
            try { original.auth.sessionAuthority.commit(oldLease) { fail("Closed SDK accepted old lease") }; fail() }
            catch (_: StaleAuthOperationException) { }
            assertArrayEquals(before, File(dir, "session.v1").readBytes())
        } finally { original.close() }
    }
}
