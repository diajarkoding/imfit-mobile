package com.diajarkoding.imfit.core.timer

import com.diajarkoding.imfit.domain.model.WorkoutSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.junit.Assert.*
import org.junit.Test

class RestTimerTickerIterationTest {
    private val session = WorkoutSession("workout", "template", "Offline", 0, emptyList())

    @Test fun sessionReadFailureAndBackoffMustNotGateExpiryReconcile() = runBlocking {
        var reads = 0
        var reconciles = 0
        var now = 1000L
        val ticker = RestTimerTickerIteration(
            readSession = { reads++; throw java.io.IOException("JSON/storage unavailable") },
            reconcile = { reconciles++ }, render = {}, absent = {}, now = { now })
        assertTrue(ticker.tick())
        now += 100 // Inside read backoff: the independent expiry check must still run.
        assertTrue(ticker.tick())
        assertEquals(1, reads)
        assertEquals(2, reconciles)
    }

    @Test fun timeoutIsRetryableAndNextIterationStillChecksExpiry() = runBlocking {
        var reconciles = 0
        val failures = mutableListOf<String>()
        val ticker = RestTimerTickerIteration({ awaitCancellation() }, { reconciles++ }, {}, {}, { 1000 },
            readTimeoutMs = 25, failure = { stage, _, _ -> failures.add(stage) })
        assertTrue(ticker.tick())
        assertTrue(ticker.tick())
        assertEquals(listOf("session_read"), failures)
        assertEquals(2, reconciles)
    }

    @Test fun successfulReadRendersAndResetsBackoff() = runBlocking {
        var failing = true
        var now = 1000L
        var rendered = 0
        var recovered = 0
        val ticker = RestTimerTickerIteration({ if (failing) throw java.io.IOException() else session }, {},
            { rendered++ }, {}, { now }, recovered = { recovered++ })
        ticker.tick()
        failing = false; now += 1000
        ticker.tick()
        assertEquals(1, rendered)
        assertEquals(1, recovered)
        assertEquals(0, ticker.retry.failures)
    }

    @Test fun absentSessionRequestsCleanupAndStopsLoop() = runBlocking {
        var absent = false
        val ticker = RestTimerTickerIteration({ null }, {}, {}, { absent = true }, { 0 })
        assertFalse(ticker.tick())
        assertTrue(absent)
    }

    @Test fun ownerCancellationPropagatesWithoutBecomingReadBackoff() = runBlocking {
        val ticker = RestTimerTickerIteration({ throw CancellationException("owner stopped") }, {}, {}, {}, { 0 })
        try { ticker.tick(); fail("Cancellation must propagate") } catch (_: CancellationException) { }
        assertEquals(0, ticker.retry.failures)
    }

    @Test fun nestedReconcileTimeoutDoesNotKillTicker() = runBlocking {
        var reconciles = 0
        val failures = mutableListOf<String>()
        val ticker = RestTimerTickerIteration({ session }, {
            reconciles++
            withTimeout(25) { awaitCancellation() }
        }, {}, {}, { 0 }, failure = { stage, _, _ -> failures.add(stage) })
        assertTrue(ticker.tick())
        assertTrue(ticker.tick())
        assertEquals(2, reconciles)
        assertEquals(listOf("expiry_reconcile", "expiry_reconcile"), failures)
    }

    @Test fun ownerTimeoutDuringReadIsNotSwallowedAsRetry() = runBlocking {
        val failures = mutableListOf<String>()
        val ticker = RestTimerTickerIteration({ awaitCancellation() }, {}, {}, {}, { 0 },
            readTimeoutMs = 1000, failure = { stage, _, _ -> failures.add(stage) })
        try {
            withTimeout(25) { ticker.tick() }
            fail("Owner timeout must propagate")
        } catch (_: TimeoutCancellationException) { }
        assertTrue(failures.isEmpty())
        assertEquals(0, ticker.retry.failures)
    }
}
