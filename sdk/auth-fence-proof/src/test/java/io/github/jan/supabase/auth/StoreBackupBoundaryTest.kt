package io.github.jan.supabase.auth

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Synthetic backup-skew witnesses only; NOT actual Room backup/privacy/normal-app recovery proof. */
class StoreBackupBoundaryTest {
    private fun scenario(test: suspend (File) -> Unit) = runBlocking<Unit> {
        withTimeout(10_000) {
            val directory = proofDirectory()
            try { test(directory) } finally { directory.deleteRecursively() }
        }
    }

    @Test fun metadataOnlyRollbackIsRejectedWhenExactCurrentWitnessIsRequired() = scenario { dir ->
        val store = FileAtomicSessionStore(dir, ProtocolTestFiles)
        val a = store.committed("A")
        store.committed("B")
        // Restored metadata expects A's exact operation; current session file contains B.
        assertTrue(store.resolve(a.ticket) is StoreOutcome.Conflict)
    }

    @Test fun sessionOnlyRollbackIsRejectedWhenIndependentWitnessRemainsCurrent() = scenario { dir ->
        val store = FileAtomicSessionStore(dir, ProtocolTestFiles)
        store.committed("A")
        val old = File(dir, "session.v1").readBytes()
        val b = store.committed("B")
        File(dir, "session.v1").writeBytes(old)
        // External current B witness was not rolled back with the session file.
        assertTrue(store.resolve(b.ticket) is StoreOutcome.Conflict)
        assertArrayEquals(old, File(dir, "session.v1").readBytes())
    }

    @Test fun externalDatabaseOnlyRollbackIsNotDetectableByStandaloneReceipt_CHARACTERIZATION_ONLY() = scenario { dir ->
        val store = FileAtomicSessionStore(dir, ProtocolTestFiles)
        store.committed("B")
        val fixtureDatabaseOwner = File(dir, "synthetic-database-owner.txt")
        fixtureDatabaseOwner.writeText("A")
        val candidate = store.readCommitted() as StoreOutcome.VerifiedCommitted
        assertEquals("B", candidate.session!!.user!!.id)
        assertNotEquals(fixtureDatabaseOwner.readText(), candidate.session!!.user!!.id)
        // No Room/context input exists in AtomicSessionStore. Its receipt cannot validate external
        // DB ownership/rollback. App integration MUST have a separately approved recovery policy.
        // This passing characterization is NOT G-STORE10/app safety PASS.
    }
}
