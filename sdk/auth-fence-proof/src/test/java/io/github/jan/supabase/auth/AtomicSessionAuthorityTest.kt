package io.github.jan.supabase.auth

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AtomicSessionAuthorityTest {
    private fun scenario(test: suspend CoroutineScope.() -> Unit) = runBlocking<Unit> {
        withTimeout(10_000, test)
    }

    private suspend fun AtomicSessionAuthority.login(owner: String): AuthCommitLease {
        mutate(operation(), owner, true) { }
        return lease()
    }

    @Test fun accountABAAndSameOwnerReloginNeverReviveOldLease() = scenario {
        val gate = AtomicSessionAuthority()
        val a = gate.login("A")
        gate.login("B")
        val again = gate.login("A")
        assertTrue(again.epoch > a.epoch)
        try { gate.commit(a) { fail("Old epoch committed") }; fail() }
        catch (_: StaleAuthOperationException) { }
        val newest = gate.login("A")
        assertTrue(newest.epoch > again.epoch)
        try { gate.commit(again) { fail() }; fail() }
        catch (_: StaleAuthOperationException) { }
    }

    @Test fun commitFirstMakesTransitionWaitUntilActualLocalCommitCompletes() = scenario {
        val gate = AtomicSessionAuthority()
        val a = gate.login("A")
        val ticket = gate.operation()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val writer = async {
            gate.commit(a) { entered.complete(Unit); release.await(); events += "commitA" }
        }
        entered.await()
        val transition = async(start = CoroutineStart.UNDISPATCHED) {
            gate.mutate(ticket, "B", true) { events += "publishB" }
        }
        assertFalse(transition.isCompleted)
        release.complete(Unit)
        writer.await(); transition.await()
        assertEquals(listOf("commitA", "publishB"), events)
    }

    @Test fun transitionFirstRejectsOldTransactionBeforeItStarts() = scenario {
        val gate = AtomicSessionAuthority()
        val a = gate.login("A")
        gate.login("B")
        var started = false
        try { gate.commit(a) { started = true }; fail() }
        catch (_: StaleAuthOperationException) { }
        assertFalse(started)
    }

    @Test fun staleOperationCannotTouchPersistenceOrPublication() = scenario {
        val gate = AtomicSessionAuthority()
        gate.login("A")
        val oldResponse = gate.operation()
        gate.login("B")
        var writes = 0
        try { gate.mutate(oldResponse, "A", false) { writes++ }; fail() }
        catch (_: StaleAuthOperationException) { }
        assertEquals(0, writes)
        assertEquals("B", gate.lease().ownerId)
    }

    @Test fun renewalInvalidatesOlderOperationsButNotSameIdentityLocalLease() = scenario {
        val gate = AtomicSessionAuthority()
        val a = gate.login("A")
        val oldCredential = gate.operation()
        gate.mutate(oldCredential, "A", false) { }
        gate.commit(a) { }
        try { gate.mutate(oldCredential, "A", false) { fail() }; fail() }
        catch (_: StaleAuthOperationException) { }
        assertEquals(a.epoch, gate.lease().epoch)
    }

    @Test fun foreignStampsAndLeasesAreRejected() = scenario {
        val a = AtomicSessionAuthority()
        val b = AtomicSessionAuthority()
        val lease = a.login("A")
        b.login("A")
        try { b.commit(lease) { fail() }; fail() }
        catch (_: StaleAuthOperationException) { }
        try { b.mutate(a.operation(), "A", true) { fail() }; fail() }
        catch (_: StaleAuthOperationException) { }
    }

    @Test fun storageFailurePoisonsOldLeaseAndAllowsOnlyFreshRecoveryOperation() = scenario {
        val gate = AtomicSessionAuthority()
        val a = gate.login("A")
        try { gate.mutate(gate.operation(), "B", true) { error("Injected storage failure") }; fail() }
        catch (_: IllegalStateException) { }
        try { gate.commit(a) { fail() }; fail() }
        catch (_: StaleAuthOperationException) { }
        try { gate.lease(); fail() }
        catch (_: AuthAuthorityUnavailableException) { }
        assertEquals("B", gate.login("B").ownerId)
    }

    @Test fun waiterCancellationCannotCancelHeldTransactionOrLeakLock() = scenario {
        val gate = AtomicSessionAuthority()
        val a = gate.login("A")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writer = launch { gate.commit(a) { entered.complete(Unit); release.await() } }
        entered.await()
        val waiter = launch(start = CoroutineStart.UNDISPATCHED) { gate.login("B") }
        waiter.cancelAndJoin()
        release.complete(Unit); writer.join()
        assertEquals("A", gate.lease().ownerId)
        assertEquals("C", gate.login("C").ownerId)
    }

    @Test fun cancelledMutationCannotLeaveAnEligibleOldLease() = scenario {
        val gate = AtomicSessionAuthority()
        val a = gate.login("A")
        val entered = CompletableDeferred<Unit>()
        val writer = launch {
            gate.mutate(gate.operation(), "B", true) { entered.complete(Unit); awaitCancellation() }
        }
        entered.await(); writer.cancelAndJoin()
        try { gate.commit(a) { fail() }; fail() }
        catch (_: StaleAuthOperationException) { }
        assertEquals("C", gate.login("C").ownerId)
    }

    @Test fun nestedFenceEntryFailsRatherThanDeadlocking() = scenario {
        val gate = AtomicSessionAuthority()
        val a = gate.login("A")
        try { gate.commit(a) { gate.operation() }; fail() }
        catch (failure: IllegalStateException) { assertTrue(failure.message!!.contains("not reentrant")) }
        gate.commit(a) { }
    }
}
