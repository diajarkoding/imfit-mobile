package com.diajarkoding.imfit.data.repository

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class CatalogRefreshGateTest {
    @Test fun forcedConcurrentWaitersJoinAndOneCancelledWaiterDoesNotCancelTheRead() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val gate = CatalogRefreshGate(owner)
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var calls = 0
            val callers = List(10) { async(start = CoroutineStart.UNDISPATCHED) {
                gate.refresh(force = true) { calls++; entered.complete(Unit); release.await(); true }
            } }
            entered.await()
            callers.first().cancelAndJoin()
            assertEquals(1, calls)
            release.complete(Unit)
            assertTrue(callers.drop(1).awaitAll().all { it })
        } finally { owner.cancel() }
    }

    @Test fun manualBypassesCooldownButFailedAutomaticBurstsStayBounded() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            var calls = 0
            val gate = CatalogRefreshGate(owner, { 100L })
            repeat(10) { assertFalse(gate.refresh(false) { calls++; false }) }
            assertEquals(1, calls)
            assertTrue(gate.refresh(true) { calls++; true })
            assertEquals(2, calls)
        } finally { owner.cancel() }
    }
}
