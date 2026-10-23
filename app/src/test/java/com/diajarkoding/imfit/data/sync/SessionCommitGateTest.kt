package com.diajarkoding.imfit.data.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class SessionCommitGateTest {
    private fun scenario(test: suspend () -> Unit) = runBlocking<Unit> { withTimeout(10_000) { test() } }

    @Test fun sameOwnerReauthenticationAndABAInvalidateOldTokens() = scenario {
        val gate = SessionCommitGate()
        val a1 = gate.transition("A")!!
        val a2 = gate.transition("A")!!
        assertFalse(gate.isCurrent(a1))
        assertTrue(a2.epoch > a1.epoch)
        gate.transition("B")
        val a3 = gate.transition("A")!!
        assertTrue(a3.epoch > a2.epoch)
        var committed = false
        try { gate.commit(a1) { committed = true }; fail("Old epoch committed") }
        catch (_: SyncSessionChangedException) { }
        assertFalse(committed)
        gate.commit(a3) { committed = true }
        assertTrue(committed)
    }

    @Test fun transitionThatWinsBeforeCommitRejectsOldWorkAndPublication() = scenario {
        val gate = SessionCommitGate()
        val old = gate.transition("A")!!
        gate.transition("B")
        var publications = 0
        try { gate.commit(old) { fail("Transaction must not run") }; fail("Expected rejection") }
        catch (_: SyncSessionChangedException) { }
        try { gate.publish(old) { publications++ }; fail("Expected rejection") }
        catch (_: SyncSessionChangedException) { }
        assertEquals(0, publications)
    }

    @Test fun transitionWaitsForAlreadyFencedTransactionToFinish() = scenario {
        val gate = SessionCommitGate()
        val old = gate.transition("A")!!
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        kotlinx.coroutines.coroutineScope {
            val commit = async(start = CoroutineStart.UNDISPATCHED) {
                gate.commit(old) {
                    entered.complete(Unit)
                    release.await()
                    events += "commit"
                }
            }
            entered.await()
            val transition = async(start = CoroutineStart.UNDISPATCHED) {
                gate.transition("B").also { events += "transition" }
            }
            assertFalse(transition.isCompleted)
            assertSame(old, gate.session.value)
            release.complete(Unit)
            commit.await(); transition.await()
        }
        assertEquals(listOf("commit", "transition"), events)
        assertEquals("B", gate.session.value?.ownerId)
    }

    @Test fun cancelledCommitReleasesFenceWithoutPublishing() = scenario {
        val gate = SessionCommitGate()
        val old = gate.transition("A")!!
        val entered = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        kotlinx.coroutines.coroutineScope {
            val commit = launch(start = CoroutineStart.UNDISPATCHED) {
                gate.commit(old) { entered.complete(Unit); never.await(); fail("Cancelled work published") }
            }
            entered.await()
            commit.cancelAndJoin()
        }
        assertEquals("B", gate.transition("B")?.ownerId)
    }

    @Test fun exceptionReleasesFenceAndDoesNotChangeEpoch() = scenario {
        val gate = SessionCommitGate()
        val old = gate.transition("A")!!
        val failure = IllegalArgumentException("synthetic transaction failure")
        try { gate.commit(old) { throw failure }; fail("Expected failure") }
        catch (actual: IllegalArgumentException) { assertSame(failure, actual) }
        assertSame(old, gate.session.value)
        var published = false
        gate.publish(old) { published = true }
        assertTrue(published)
        gate.transition(null)
        assertNull(gate.session.value)
    }

    @Test fun tokenFromAnotherGateCannotAuthorizeCommitEvenWithSameOwnerAndEpoch() = scenario {
        val gate = SessionCommitGate()
        val current = gate.transition("A")!!
        val foreign = SessionCommitGate().transition("A")!!
        assertEquals(current.epoch, foreign.epoch)
        try { gate.commit(foreign) { fail("Foreign token accepted") }; fail("Expected rejection") }
        catch (_: SyncSessionChangedException) { }
    }
}
