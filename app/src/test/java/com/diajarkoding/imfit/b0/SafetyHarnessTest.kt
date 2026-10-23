package com.diajarkoding.imfit.b0

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

class SafetyHarnessTest {
    @Test fun probeOrdersConcurrentWorkWithoutSleep() = runBlocking {
        val probe = SuspendProbe()
        val events = mutableListOf<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { events += "capture"; probe.pause(); events += "send" }
        probe.awaitReached()
        assertEquals(listOf("capture"), events)
        probe.release(); job.join()
        assertEquals(listOf("capture", "send"), events)
    }

    @Test fun cancellationIsBoundedAndProbeCanBeReleased() = runBlocking {
        val probe = SuspendProbe()
        val job = launch(start = CoroutineStart.UNDISPATCHED) { probe.pause() }
        probe.awaitReached(); job.cancelAndJoin(); probe.release()
        assertTrue(job.isCancelled)
    }

    @Test fun ledgerCountsConcurrentTerminalOutcomesAndExplicitJoins() {
        val ledger = RequestLedger()
        val first = ledger.start("GET /catalog")
        val second = ledger.start("GET /catalog")
        ledger.joined()
        ledger.finish(first, "HTTP_200"); ledger.finish(second, "CANCELLED")
        assertEquals(2, ledger.count("GET /catalog"))
        assertEquals(2, ledger.maxConcurrency)
        assertEquals(1, ledger.joined)
        assertEquals(0, ledger.active())
        assertEquals(listOf("HTTP_200", "CANCELLED"), ledger.snapshot().map { it.terminal })
    }

    @Test fun ledgerRejectsDoubleCompletion() {
        val ledger = RequestLedger()
        val id = ledger.start("GET /catalog"); ledger.finish(id, "HTTP_200")
        assertThrows(IllegalStateException::class.java) { ledger.finish(id, "CANCELLED") }
    }

    @Test fun sessionEpochDistinguishesABAAndSdkEventKinds() {
        val session = ControlledSession()
        val first = session.transition("A", SessionEvent.LOGIN)
        session.transition(null, SessionEvent.LOGOUT)
        session.transition("B", SessionEvent.DEEP_LINK)
        val last = session.transition("A", SessionEvent.RESTORE)
        assertEquals(first.owner, last.owner)
        assertNotEquals(first.epoch, last.epoch)
        assertNull(session.transition(null, SessionEvent.EXPIRY).owner)
    }

    @Test fun clockCanHoldWallTimestampForSameMillisecondMutation() {
        val clock = ControlledClock(wallMs = 1234)
        val revisions = listOf(clock.wallMs, clock.wallMs)
        assertEquals(revisions[0], revisions[1])
        clock.advance(30_000)
        assertEquals(30_000, clock.monotonicMs)
        assertEquals(31_234, clock.wallMs)
    }

    @Test fun transactionExecutorProbeOrdersRoomRunnable() {
        TransactionProbeExecutor().use { executor ->
            val probe = executor.arm()
            val done = java.util.concurrent.CountDownLatch(1)
            executor.execute { done.countDown() }
            probe.awaitReached(); assertEquals(1, done.count)
            probe.release(); assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS))
        }
    }

    @Test fun expectedUnsafeBackupEnabledWithoutActiveExclusions() {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val manifest = factory.newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
        val app = manifest.getElementsByTagName("application").item(0) as org.w3c.dom.Element
        assertEquals("true", app.getAttributeNS("http://schemas.android.com/apk/res/android", "allowBackup"))
        listOf("backup_rules.xml", "data_extraction_rules.xml").forEach { name ->
            val xml = factory.newDocumentBuilder().parse(File("src/main/res/xml/$name"))
            assertEquals(0, xml.getElementsByTagName("exclude").length)
        }
        evidence("B11", BaselineClassification.EXPECTED_UNSAFE_BEHAVIOR, "backupEnabled=true;activeExclusions=0;sourceOnly=true")
    }
}
