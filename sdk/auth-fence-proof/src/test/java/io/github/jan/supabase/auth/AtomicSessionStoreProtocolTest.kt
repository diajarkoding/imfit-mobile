package io.github.jan.supabase.auth

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** G-STORE protocol/failpoint oracles; directory durability is explicitly SIMULATED here. */
class AtomicSessionStoreProtocolTest {
    private fun scenario(test: suspend CoroutineScope.(File) -> Unit) = runBlocking<Unit> {
        withTimeout(15_000) {
            val directory = proofDirectory()
            try { test(directory) } finally { directory.deleteRecursively() } // exclusively this test's UUID sandbox
        }
    }

    private fun store(directory: File, fault: (StorePoint) -> Unit = {}) =
        FileAtomicSessionStore(directory, ProtocolTestFiles, fault)

    @Test fun preparedNeverBecomesTrustedAfterFreshHandle() = scenario { dir ->
        val first = store(dir)
        val a = first.committed("A")
        val b = first.stage("B")
        assertNotEquals(a.ticket.head, b.head)
        assertTrue(store(dir).readCommitted() is StoreOutcome.Prepared)
        assertNull(store(dir).loadSession())
        assertTrue(first.resolve(a.ticket) is StoreOutcome.Conflict)
    }

    @Test fun exactCommittedReceiptRestoresAfterFreshHandle() = scenario { dir ->
        val committed = store(dir).committed("B")
        val restored = store(dir).readCommitted() as StoreOutcome.VerifiedCommitted
        assertEquals(committed.ticket, restored.ticket)
        assertEquals("B", restored.session!!.user!!.id)
        assertFalse(restored.toString().contains("synthetic"))
    }

    @Test fun staleCommitCannotOverwriteInterleavedPrepareOrAccountABA() = scenario { dir ->
        val first = store(dir)
        first.committed("A")
        val b = first.stage("B")
        val a = store(dir).stage("A")
        assertTrue(first.commitIfCurrent(b) is StoreOutcome.Conflict)
        assertTrue(first.resolve(b) is StoreOutcome.Conflict)
        val current = first.commitIfCurrent(a) as StoreOutcome.VerifiedCommitted
        assertTrue(current.ticket.head.generation > b.head.generation)
        assertEquals("A", current.session!!.user!!.id)
    }

    @Test fun duplicateCompletionAndReusedOperationAreRejected() = scenario { dir ->
        val first = store(dir)
        val ticket = first.stage()
        val committed = first.commitIfCurrent(ticket) as StoreOutcome.VerifiedCommitted
        assertTrue(first.commitIfCurrent(ticket) is StoreOutcome.Conflict)
        assertTrue(first.prepare(committed.ticket.head, ticket.head.operationId, syntheticSession("B")) is StoreOutcome.Conflict)
        assertTrue(first.resolve(ticket) is StoreOutcome.VerifiedCommitted)
    }

    @Test fun missingOrForeignPredecessorCannotWrite() = scenario { dir ->
        val first = store(dir)
        val initial = first.committed()
        val bytes = File(dir, "session.v1").readBytes()
        assertTrue(first.prepare(null, UUID.randomUUID().toString(), syntheticSession("B")) is StoreOutcome.Conflict)
        assertTrue(first.prepare(initial.ticket.head.copy(digest = "0".repeat(64)), UUID.randomUUID().toString(),
            syntheticSession("B")) is StoreOutcome.Conflict)
        assertArrayEquals(bytes, File(dir, "session.v1").readBytes())
    }

    @Test fun finalWriteThenThrowResolvesOnlyExactDurableRecord() = scenario { dir ->
        val first = store(dir)
        val ticket = first.stage("B")
        val failing = store(dir) { if (it == StorePoint.AFTER_COMMIT) error("synthetic receipt write-then-throw") }
        assertTrue(failing.commitIfCurrent(ticket) is StoreOutcome.Unavailable)
        val restored = store(dir).resolve(ticket) as StoreOutcome.VerifiedCommitted
        assertEquals("B", restored.session!!.user!!.id)
        assertTrue(store(dir).resolve(ticket.copy(binding = "0".repeat(64))) is StoreOutcome.Conflict)
        store(dir).committed("C")
        assertTrue(store(dir).resolve(ticket) is StoreOutcome.Conflict)
    }

    @Test fun prepareWriteFailpointsNeverTrustStagedB() = scenario { dir ->
        for (point in listOf(StorePoint.BEFORE_PREPARE, StorePoint.BEFORE_STAGE_WRITE, StorePoint.AFTER_STAGE_SYNC,
            StorePoint.AFTER_REPLACE, StorePoint.AFTER_DIRECTORY_SYNC, StorePoint.BEFORE_READBACK)) {
            val sandbox = File(dir, point.name).also { check(it.mkdir()) }
            val failing = store(sandbox) { if (it == point) error("synthetic prepare failpoint") }
            assertTrue(failing.prepare(null, UUID.randomUUID().toString(), syntheticSession("B")) is StoreOutcome.Unavailable)
            assertFalse("$point must not recover staged B", store(sandbox).readCommitted() is StoreOutcome.VerifiedCommitted)
            assertNull(store(sandbox).loadSession())
        }
    }

    @Test fun commitFailpointsResolveFromRecordNotException() = scenario { dir ->
        for (point in listOf(StorePoint.BEFORE_COMMIT, StorePoint.BEFORE_STAGE_WRITE, StorePoint.AFTER_STAGE_SYNC,
            StorePoint.AFTER_REPLACE, StorePoint.AFTER_DIRECTORY_SYNC, StorePoint.BEFORE_READBACK, StorePoint.AFTER_COMMIT)) {
            val sandbox = File(dir, point.name).also { check(it.mkdir()) }
            val ticket = store(sandbox).stage("B")
            val failing = store(sandbox) { if (it == point) error("synthetic commit failpoint") }
            assertTrue(failing.commitIfCurrent(ticket) is StoreOutcome.Unavailable)
            val resolved = store(sandbox).resolve(ticket)
            if (point in listOf(StorePoint.BEFORE_COMMIT, StorePoint.BEFORE_STAGE_WRITE, StorePoint.AFTER_STAGE_SYNC)) {
                assertTrue(resolved is StoreOutcome.Prepared)
            } else assertEquals("B", (resolved as StoreOutcome.VerifiedCommitted).session!!.user!!.id)
        }
    }

    @Test fun unresolvableReadbackOrSyncCannotPublish() = scenario { dir ->
        val committed = store(dir).committed()
        val unreadable = store(dir) { if (it == StorePoint.BEFORE_READ) error("synthetic read error") }
        assertTrue(unreadable.readVerified() is StoreOutcome.Unavailable)
        var publications = 0
        assertFalse(unreadable.publishIfCurrent(committed.ticket) { publications++ })
        val unsupported = FileAtomicSessionStore(dir, object : AtomicStoreFiles {
            override fun replace(stage: File, destination: File) = error("must not replace")
            override fun syncDirectory(directory: File) = throw UnsupportedOperationException()
        })
        assertEquals(StoreOutcome.Unavailable(StoreFailure.UNSUPPORTED), unsupported.readVerified())
        assertFalse(unsupported.publishIfCurrent(committed.ticket) { publications++ })
        assertEquals(0, publications)
    }

    @Test fun readbackMismatchPreservesCorruptBytesAndReturnsUnavailable() = scenario { dir ->
        val ticket = store(dir).stage()
        val failing = store(dir) {
            if (it == StorePoint.BEFORE_READBACK) File(dir, "session.v1").appendBytes(byteArrayOf(1))
        }
        assertEquals(StoreOutcome.Unavailable(StoreFailure.INVALID), failing.commitIfCurrent(ticket))
        val bytes = File(dir, "session.v1").readBytes()
        assertTrue(store(dir).readVerified() is StoreOutcome.Unavailable)
        assertArrayEquals(bytes, File(dir, "session.v1").readBytes())
    }

    @Test fun malformedTornAndFutureRecordsAreNeverRepairedOrDeleted() = scenario { dir ->
        store(dir).committed()
        val file = File(dir, "session.v1")
        val valid = file.readBytes()
        val cases = listOf(valid.copyOf(9), valid + byteArrayOf(0), valid.copyOf().also { it[it.lastIndex] = '!'.code.toByte() },
            valid.copyOf().also { ByteBuffer.wrap(it).putInt(4, 2) },
            valid.copyOf().also { ByteBuffer.wrap(it).putInt(8, Int.MAX_VALUE) })
        for (bytes in cases) {
            file.writeBytes(bytes)
            assertTrue(store(dir).readVerified() is StoreOutcome.Unavailable)
            assertTrue(store(dir).prepare(null, UUID.randomUUID().toString(), syntheticSession("B")) is StoreOutcome.Unavailable)
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun legacyRawCorruptAndEmptyBytesRemainUnverifiedAndUnchanged() = scenario { dir ->
        val file = File(dir, "session.v1")
        for (bytes in listOf("{\"access_token\":\"synthetic-legacy\"}".toByteArray(), byteArrayOf(1, 2), byteArrayOf())) {
            file.writeBytes(bytes)
            assertTrue(store(dir).readVerified() is StoreOutcome.LegacyUnverified)
            assertTrue(store(dir).prepare(null, UUID.randomUUID().toString(), syntheticSession("B")) is StoreOutcome.LegacyUnverified)
            assertNull(store(dir).loadSession())
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun oversizePayloadAndEnvelopeAreBoundedWithoutMutation() = scenario { dir ->
        val first = store(dir)
        assertEquals(StoreOutcome.Unavailable(StoreFailure.OVERSIZE), first.prepare(null, UUID.randomUUID().toString(),
            syntheticSession("A").copy(accessToken = "x".repeat(FileAtomicSessionStore.MAX_SESSION))))
        assertFalse(File(dir, "session.v1").exists())
        val file = File(dir, "session.v1")
        file.writeBytes(ByteArray(FileAtomicSessionStore.MAX_RECORD + 1))
        assertEquals(StoreOutcome.Unavailable(StoreFailure.OVERSIZE), first.readVerified())
        assertEquals((FileAtomicSessionStore.MAX_RECORD + 1).toLong(), file.length())
    }

    @Test fun invalidOwnerOrOperationCannotCreateRecord() = scenario { dir ->
        val first = store(dir)
        assertEquals(StoreOutcome.Unavailable(StoreFailure.INVALID), first.prepare(null, "not-a-uuid", syntheticSession("A")))
        assertEquals(StoreOutcome.Unavailable(StoreFailure.INVALID), first.prepare(null, UUID.randomUUID().toString(), syntheticSession(" ")))
        assertFalse(File(dir, "session.v1").exists())
    }

    @Test fun generationOverflowFailsClosedAndPreservesBytes() = scenario { dir ->
        val first = store(dir)
        first.stage()
        val file = File(dir, "session.v1")
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes)
        buffer.position(8)
        repeat(3) { val size = buffer.int; buffer.position(buffer.position() + size) }
        buffer.putLong(Long.MAX_VALUE)
        file.writeBytes(bytes)
        val prepared = first.readVerified() as StoreOutcome.Prepared
        assertEquals(StoreOutcome.Unavailable(StoreFailure.OVERFLOW), first.prepare(prepared.ticket.head,
            UUID.randomUUID().toString(), syntheticSession("B")))
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test fun clearUsesCommittedTombstoneAndNeverRevivesOldTicket() = scenario { dir ->
        val first = store(dir)
        val a = first.committed("A")
        first.deleteSession()
        val cleared = store(dir).readCommitted() as StoreOutcome.VerifiedCommitted
        assertNull(cleared.session)
        assertTrue(File(dir, "session.v1").exists())
        assertTrue(first.resolve(a.ticket) is StoreOutcome.Conflict)
        val next = first.committed("A")
        assertTrue(next.ticket.head.generation > cleared.ticket.head.generation)
        assertEquals(a.ticket.head.incarnation, next.ticket.head.incarnation)
    }

    @Test fun twoHandlesRaceExactlyOneCASWinner() = scenario { dir ->
        val initial = store(dir).committed("A")
        val start = CompletableDeferred<Unit>()
        val jobs = (1..10).map {
            async(Dispatchers.Default) {
                start.await()
                store(dir).prepare(initial.ticket.head, UUID.randomUUID().toString(), syntheticSession("B"))
            }
        }
        start.complete(Unit)
        val outcomes = jobs.awaitAll()
        assertEquals(1, outcomes.count { it is StoreOutcome.Prepared })
        assertEquals(9, outcomes.count { it is StoreOutcome.Conflict })
        assertTrue(store(dir).commitIfCurrent((outcomes.filterIsInstance<StoreOutcome.Prepared>().single()).ticket) is StoreOutcome.VerifiedCommitted)
    }

    @Test fun cancelledPrepareLeavesOnlyUntrustedStageAndNoLockLeak() = scenario { dir ->
        val reached = CompletableDeferred<Unit>()
        lateinit var writer: Job
        writer = launch(start = CoroutineStart.LAZY) {
            store(dir) {
                if (it == StorePoint.AFTER_REPLACE) { reached.complete(Unit); writer.cancel() }
            }.stage("B")
        }
        writer.start(); reached.await(); writer.join()
        assertTrue(writer.isCancelled)
        assertTrue(store(dir).readCommitted() is StoreOutcome.Prepared)
        assertEquals("C", store(dir).committed("C").session!!.user!!.id)
    }

    @Test fun cancelledCommittedWriteStaysRecoverableButDoesNotPublish() = scenario { dir ->
        val ticket = store(dir).stage("B")
        lateinit var writer: Job
        writer = launch(start = CoroutineStart.LAZY) {
            store(dir) { if (it == StorePoint.AFTER_COMMIT) writer.cancel() }.commitIfCurrent(ticket)
        }
        writer.start(); writer.join()
        assertTrue(writer.isCancelled)
        assertEquals("B", (store(dir).resolve(ticket) as StoreOutcome.VerifiedCommitted).session!!.user!!.id)
    }

    @Test fun cancelledWaiterCannotLeakSharedOrOSLock() = scenario { dir ->
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holding = async(Dispatchers.Default) {
            store(dir) { if (it == StorePoint.BEFORE_PREPARE) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }.stage("A")
        }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(5, TimeUnit.SECONDS) })
            val waiting = CompletableDeferred<Unit>()
            val waiter = launch { store(dir) { if (it == StorePoint.BEFORE_LOCK) waiting.complete(Unit) }.readVerified() }
            waiting.await()
            waiter.cancel()
            waiter.join() // must terminate while holder still owns the process and file locks
            release.countDown()
            holding.await()
            assertEquals("B", store(dir).committed("B").session!!.user!!.id)
        } finally { release.countDown() }
    }

    @Test fun publicationRechecksExactHeadAndCancellation() = scenario { dir ->
        val a = store(dir).committed("A")
        store(dir).stage("B")
        var publications = 0
        assertFalse(store(dir).publishIfCurrent(a.ticket) { publications++ })
        val b = store(dir).committed("B")
        lateinit var writer: Job
        writer = launch(start = CoroutineStart.LAZY) {
            store(dir) { if (it == StorePoint.BEFORE_PUBLICATION) writer.cancel() }
                .publishIfCurrent(b.ticket) { publications++ }
        }
        writer.start(); writer.join()
        assertTrue(writer.isCancelled)
        assertEquals(0, publications)
        assertTrue(store(dir).publishIfCurrent(b.ticket) { publications++ })
        assertEquals(1, publications)
    }

    @Test fun selfConsistentCompleteRollbackIsAnExplicitUndetectableLimit() = scenario { dir ->
        val first = store(dir)
        first.committed("A")
        val oldRecord = File(dir, "session.v1").readBytes()
        first.committed("B")
        File(dir, "session.v1").writeBytes(oldRecord)
        // A self-contained receipt cannot detect a complete rollback. This is NOT a safety PASS for backup policy.
        assertEquals("A", (store(dir).readCommitted() as StoreOutcome.VerifiedCommitted).session!!.user!!.id)
    }

    @Test fun sessionReceiptSkewCannotBeRecovered() = scenario { dir ->
        store(dir).committed("A")
        val file = File(dir, "session.v1")
        val a = file.readBytes()
        store(dir).committed("B")
        val b = file.readBytes()
        System.arraycopy(a, a.size - 64, b, b.size - 64, 64)
        file.writeBytes(b)
        assertEquals(StoreOutcome.Unavailable(StoreFailure.INVALID), store(dir).readCommitted())
        assertArrayEquals(b, file.readBytes())
    }

    @Test fun physicalClearRecreationCannotRevivePriorIncarnationTicket() = scenario { dir ->
        val first = store(dir)
        val old = first.stage("A")
        check(File(dir, "session.v1").delete()) // synthetic external clear, not a supported store operation
        val fresh = store(dir).committed("A")
        assertNotEquals(old.head.incarnation, fresh.ticket.head.incarnation)
        assertTrue(first.commitIfCurrent(old) is StoreOutcome.Conflict)
        assertTrue(first.resolve(old) is StoreOutcome.Conflict)
    }

    @Test fun absentDirectoryCannotBeCreatedAsAnImplicitCapability() = scenario { dir ->
        val missing = File(dir, "absent")
        assertEquals(StoreOutcome.Unavailable(StoreFailure.UNSUPPORTED), store(missing).readVerified())
        assertFalse(missing.exists())
    }
}
