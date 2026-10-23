package com.diajarkoding.imfit.data.sync

import com.diajarkoding.imfit.b0.SuspendProbe
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RefreshCoordinatorTest {
    // Coroutine stacktrace recovery can copy standard exceptions. The marker prevents copying,
    // so the identity assertion below measures the shared failure rather than recovery wrappers.
    private class AdapterFailure(val marker: Any) : IllegalStateException("synthetic adapter bug")
    private class Fixture {
        val gate = SessionCommitGate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        var now = 0L
        val coordinator = RefreshCoordinator(scope, gate, { now }, 1_000)
        suspend fun close() { coordinator.shutdown(); scope.cancel() }
    }

    private fun scenario(test: suspend Fixture.() -> Unit) = runBlocking<Unit> {
        withTimeout(10_000) {
            val fixture = Fixture()
            try { fixture.test() }
            finally { withContext(NonCancellable) { fixture.close() } }
        }
    }

    @Test fun tenOverlappingCallersShareOneFlightAndExactResultObject() = scenario {
        val session = gate.transition("A")!!
        val probe = SuspendProbe()
        val outcome = RefreshOutcome.PartialSuccess(setOf(RefreshIssue.CONFLICT_SKIPPED))
        var calls = 0
        coroutineScope {
            val callers = List(10) { async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.request(session, RefreshDataset.HISTORY, RefreshOrigin.MANUAL) {
                    calls++; probe.pause(); outcome
                }
            } }
            probe.awaitReached()
            assertEquals(1, calls)
            assertEquals(9L, coordinator.counters().joined)
            probe.release()
            callers.awaitAll().forEach { assertSame(outcome, it) }
        }
        assertEquals(RefreshCoordinator.Counters(1, 9, 1, 0, 0), coordinator.counters())
    }

    @Test fun fullAndMasterDemandsShareCatalogAndInitialReadIsNotRepeated() = scenario {
        val session = gate.transition("A")!!
        val probe = SuspendProbe()
        val calls = mutableMapOf<RefreshDataset, Int>()
        coroutineScope {
            val full = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.refresh(session, RefreshDataset.entries.toSet(), RefreshOrigin.MANUAL) { dataset ->
                    calls[dataset] = (calls[dataset] ?: 0) + 1
                    if (dataset == RefreshDataset.CATALOG) probe.pause()
                    RefreshOutcome.Success
                }
            }
            probe.awaitReached()
            val master = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.request(session, RefreshDataset.CATALOG, RefreshOrigin.MANUAL) {
                    fail("Master must join full catalog demand"); RefreshOutcome.NetworkFailure
                }
            }
            probe.release()
            assertEquals(RefreshOutcome.Success, master.await())
            assertEquals(RefreshDataset.entries.toSet(), full.await().keys)
        }
        assertEquals(RefreshDataset.entries.associateWith { 1 }, calls)
        assertEquals(0, coordinator.activeFlightCount())
    }

    @Test fun subsetDemandsShareOnlyOverlappingDatasets() = scenario {
        val session = gate.transition("A")!!
        val probe = SuspendProbe()
        val calls = mutableMapOf<RefreshDataset, Int>()
        val read: suspend (RefreshDataset) -> RefreshOutcome = { dataset ->
            calls[dataset] = (calls[dataset] ?: 0) + 1
            if (dataset == RefreshDataset.HISTORY) probe.pause()
            RefreshOutcome.Success
        }
        coroutineScope {
            val home = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.refresh(session, setOf(RefreshDataset.HISTORY, RefreshDataset.PROFILE, RefreshDataset.TEMPLATES), RefreshOrigin.MANUAL, read)
            }
            probe.awaitReached()
            val progress = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.refresh(session, setOf(RefreshDataset.HISTORY, RefreshDataset.PROFILE), RefreshOrigin.MANUAL, read)
            }
            probe.release()
            home.await(); progress.await()
        }
        assertEquals(mapOf(RefreshDataset.HISTORY to 1, RefreshDataset.PROFILE to 1, RefreshDataset.TEMPLATES to 1), calls)
    }

    @Test fun cancellingOneWaiterDoesNotCancelSharedRead() = scenario {
        val session = gate.transition("A")!!
        val probe = SuspendProbe()
        coroutineScope {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.request(session, RefreshDataset.CATALOG, RefreshOrigin.MANUAL) { probe.pause(); RefreshOutcome.Success }
            }
            probe.awaitReached()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.request(session, RefreshDataset.CATALOG, RefreshOrigin.MANUAL) { fail("Duplicate read"); RefreshOutcome.Success }
            }
            first.cancelAndJoin()
            assertEquals(1, coordinator.activeFlightCount())
            assertFalse(second.isCompleted)
            probe.release()
            assertEquals(RefreshOutcome.Success, second.await())
        }
        assertEquals(0, coordinator.activeFlightCount())
    }

    @Test fun serviceCancellationFinalizesFlightsAndAllRemainingWaiters() = scenario {
        val session = gate.transition("A")!!
        val probe = SuspendProbe()
        coroutineScope {
            val caller = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.request(session, RefreshDataset.HISTORY, RefreshOrigin.MANUAL) { probe.pause(); RefreshOutcome.Success }
            }
            probe.awaitReached()
            coordinator.shutdown()
            assertEquals(RefreshOutcome.Cancelled, caller.await())
        }
        assertEquals(0, coordinator.activeFlightCount())
        assertEquals(RefreshOutcome.Cancelled,
            coordinator.request(session, RefreshDataset.HISTORY, RefreshOrigin.MANUAL) { fail("Closed service started work"); RefreshOutcome.Success })
    }

    @Test fun failedAutomaticBurstsAreBoundedAndManualRefreshBypassesCooldown() = scenario {
        val session = gate.transition("A")!!
        var calls = 0
        repeat(10) {
            assertEquals(RefreshOutcome.NetworkFailure,
                coordinator.request(session, RefreshDataset.CATALOG, RefreshOrigin.AUTOMATIC) { calls++; RefreshOutcome.NetworkFailure })
        }
        assertEquals(1, calls)
        assertEquals(9L, coordinator.counters().cooldownHits)
        coordinator.request(session, RefreshDataset.CATALOG, RefreshOrigin.MANUAL) { calls++; RefreshOutcome.Success }
        assertEquals(2, calls)
        now = 1_000
        coordinator.request(session, RefreshDataset.CATALOG, RefreshOrigin.AUTOMATIC) { calls++; RefreshOutcome.Success }
        assertEquals(3, calls)
    }

    @Test fun manualDemandStillJoinsRunningAutomaticFlight() = scenario {
        val session = gate.transition("A")!!
        val probe = SuspendProbe()
        coroutineScope {
            val automatic = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.request(session, RefreshDataset.CATALOG, RefreshOrigin.AUTOMATIC) { probe.pause(); RefreshOutcome.Success }
            }
            probe.awaitReached()
            val manual = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.request(session, RefreshDataset.CATALOG, RefreshOrigin.MANUAL) { fail("Manual bypassed active flight"); RefreshOutcome.Success }
            }
            assertEquals(1L, coordinator.counters().joined)
            probe.release()
            assertEquals(automatic.await(), manual.await())
        }
    }

    @Test fun catalogRemainsIndependentWhileMutableDatasetsAreSerialized() = scenario {
        val session = gate.transition("A")!!
        val history = SuspendProbe()
        var profileStarted = false
        coroutineScope {
            val a = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.request(session, RefreshDataset.HISTORY, RefreshOrigin.MANUAL) { history.pause(); RefreshOutcome.Success }
            }
            history.awaitReached()
            val b = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.request(session, RefreshDataset.PROFILE, RefreshOrigin.MANUAL) { profileStarted = true; RefreshOutcome.Success }
            }
            assertFalse(profileStarted)
            assertEquals(RefreshOutcome.Success,
                coordinator.request(session, RefreshDataset.CATALOG, RefreshOrigin.MANUAL) { RefreshOutcome.Success })
            assertFalse(profileStarted)
            history.release(); a.await(); b.await()
        }
        assertTrue(profileStarted)
    }

    @Test fun epochABARejectsOldResultAndCannotJoinNewSessionFlight() = scenario {
        val old = gate.transition("A")!!
        val probe = SuspendProbe()
        var calls = 0
        coroutineScope {
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.request(old, RefreshDataset.CATALOG, RefreshOrigin.MANUAL) { calls++; probe.pause(); RefreshOutcome.Success }
            }
            probe.awaitReached()
            gate.transition("B")
            val current = gate.transition("A")!!
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.request(current, RefreshDataset.CATALOG, RefreshOrigin.MANUAL) { calls++; RefreshOutcome.Success }
            }
            probe.release()
            assertEquals(RefreshOutcome.AuthChanged, first.await())
            assertEquals(RefreshOutcome.Success, second.await())
        }
        assertEquals(2, calls)
        assertEquals(0L, coordinator.counters().joined)
        assertEquals(RefreshOutcome.AuthChanged,
            coordinator.request(old, RefreshDataset.PROFILE, RefreshOrigin.MANUAL) { fail("Stale session started read"); RefreshOutcome.Success })
    }

    @Test fun cancelledInvalidEpochDoesNotFinalizeOrCancelSuccessor() = scenario {
        val old = gate.transition("A")!!
        val oldProbe = SuspendProbe()
        val newProbe = SuspendProbe()
        coroutineScope {
            val a = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.request(old, RefreshDataset.HISTORY, RefreshOrigin.MANUAL) { oldProbe.pause(); RefreshOutcome.Success }
            }
            oldProbe.awaitReached()
            val current = gate.transition("B")!!
            val b = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.request(current, RefreshDataset.HISTORY, RefreshOrigin.MANUAL) { newProbe.pause(); RefreshOutcome.Success }
            }
            coordinator.cancelInvalidSessions()
            newProbe.awaitReached()
            assertEquals(RefreshOutcome.Cancelled, a.await())
            assertEquals(1, coordinator.activeFlightCount())
            newProbe.release()
            assertEquals(RefreshOutcome.Success, b.await())
        }
        assertEquals(0, coordinator.activeFlightCount())
    }

    @Test fun queuedOldEpochDoesNotSendAfterTransition() = scenario {
        val old = gate.transition("A")!!
        val probe = SuspendProbe()
        coroutineScope {
            val history = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.request(old, RefreshDataset.HISTORY, RefreshOrigin.MANUAL) { probe.pause(); RefreshOutcome.Success }
            }
            probe.awaitReached()
            val profile = async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.request(old, RefreshDataset.PROFILE, RefreshOrigin.MANUAL) { fail("Queued stale read sent"); RefreshOutcome.Success }
            }
            gate.transition("B")
            probe.release()
            assertEquals(RefreshOutcome.AuthChanged, history.await())
            assertEquals(RefreshOutcome.AuthChanged, profile.await())
        }
    }

    @Test fun unexpectedExceptionIsSharedNotConvertedToSuccessAndRegistryIsReleased() = scenario {
        val session = gate.transition("A")!!
        val probe = SuspendProbe()
        val failure = AdapterFailure(Any())
        supervisorScope {
            val callers = List(2) { async(start = CoroutineStart.UNDISPATCHED) {
                coordinator.request(session, RefreshDataset.CATALOG, RefreshOrigin.MANUAL) { probe.pause(); throw failure }
            } }
            probe.awaitReached(); probe.release()
            callers.forEach { caller ->
                try { caller.await(); fail("Unexpected exception was swallowed") }
                catch (actual: AdapterFailure) { assertSame(failure, actual) }
            }
        }
        assertEquals(0, coordinator.activeFlightCount())
        assertEquals(RefreshOutcome.Success,
            coordinator.request(session, RefreshDataset.CATALOG, RefreshOrigin.MANUAL) { RefreshOutcome.Success })
    }

    @Test fun parentServiceCancellationBeforeFirstRequestDoesNotRegisterLazyFlight() = scenario {
        val session = gate.transition("A")!!
        scope.cancel()
        assertEquals(RefreshOutcome.Cancelled,
            coordinator.request(session, RefreshDataset.CATALOG, RefreshOrigin.MANUAL) { fail("Cancelled owner sent read"); RefreshOutcome.Success })
        assertEquals(0, coordinator.activeFlightCount())
    }

    @Test fun terminalOutcomesHaveExplicitRetryEligibility() {
        assertTrue(RefreshOutcome.Offline.isRetryable)
        assertTrue(RefreshOutcome.NetworkFailure.isRetryable)
        assertTrue(RefreshOutcome.ServerFailure(500).isRetryable)
        assertTrue(RefreshOutcome.ServerFailure(429).isRetryable)
        assertFalse(RefreshOutcome.ServerFailure(400).isRetryable)
        assertFalse(RefreshOutcome.AuthenticationRequired.isRetryable)
        assertFalse(RefreshOutcome.AuthChanged.isRetryable)
        assertFalse(RefreshOutcome.Cancelled.isRetryable)
        assertFalse(RefreshOutcome.Success.isRetryable)
        assertFalse(RefreshOutcome.PartialSuccess(setOf(RefreshIssue.SNAPSHOT_UNVERIFIED)).isRetryable)
        assertFalse(RefreshOutcome.Blocked(RefreshIssue.PENDING_BLOCKED).isRetryable)
        try { RefreshOutcome.ServerFailure(401); fail("401 must not be classified as server failure") }
        catch (_: IllegalArgumentException) { }
        try { RefreshOutcome.PartialSuccess(emptySet()); fail("Partial outcome requires a reason") }
        catch (_: IllegalArgumentException) { }
    }
}
