package io.github.jan.supabase.auth

import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserSession
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.logging.LogLevel
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID
import kotlin.coroutines.ContinuationInterceptor
import kotlin.time.Duration.Companion.seconds

/** Exact durable-receipt recovery, synthetic HTTP; directory sync is explicitly simulated here. */
class SdkExpiredRecoveryTest {
    private class Fixture(val directory: File, dispatcher: CoroutineDispatcher) {
        val actual = FileAtomicSessionStore(directory, ProtocolTestFiles)
        var store: AtomicSessionStore = actual
        var requests = 0
        var pause = false
        var status = HttpStatusCode.OK
        var body = SESSION_A
        var transportFailure = false
        var usedOriginalRefresh = false
        var sentWithoutAccountHeader = false
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val clients = mutableListOf<io.github.jan.supabase.SupabaseClient>()
        val dispatcher = dispatcher
        val record get() = File(directory, "session.v1")

        suspend fun seed(expired: Boolean = true): StoreOutcome.VerifiedCommitted {
            val session = syntheticSession("A").let { if (expired) it.copy(expiresAt = Clock.System.now() - 60.seconds) else it }
            val prior = actual.readVerified()
            val expected = when (prior) {
                StoreOutcome.Absent -> null
                is StoreOutcome.VerifiedCommitted -> prior.ticket.head
                is StoreOutcome.Prepared -> prior.ticket.head
                else -> error("Unexpected synthetic store state")
            }
            val prepared = actual.prepare(expected, UUID.randomUUID().toString(), session) as StoreOutcome.Prepared
            return actual.commitIfCurrent(prepared.ticket) as StoreOutcome.VerifiedCommitted
        }

        fun client(startup: Boolean = false, save: Boolean = true) = createSupabaseClient("https://expired-recovery-proof.invalid", "synthetic-key") {
            defaultLogLevel = LogLevel.NONE
            httpEngine = MockEngine { request ->
                check(request.url.host == "expired-recovery-proof.invalid")
                check(request.url.parameters["grant_type"] == "refresh_token")
                requests++
                val payload = Json.parseToJsonElement((request.body as OutgoingContent.ByteArrayContent).bytes().decodeToString()).jsonObject
                usedOriginalRefresh = payload["refresh_token"]?.jsonPrimitive?.content == "refresh-A"
                sentWithoutAccountHeader = request.headers[HttpHeaders.Authorization] == null
                if (pause) { entered.complete(Unit); release.await() }
                if (transportFailure) throw java.io.IOException("synthetic offline transport")
                respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            install(Auth) {
                minimalSettings(sessionManager = store, autoLoadFromStorage = startup, autoSaveToStorage = save, alwaysAutoRefresh = startup)
                coroutineDispatcher = dispatcher
            }
        }.also { clients += it }
    }

    private fun scenario(test: suspend CoroutineScope.(Fixture) -> Unit) = runBlocking<Unit> {
        withTimeout(15_000) {
            val dir = proofDirectory()
            val f = Fixture(dir, coroutineContext[ContinuationInterceptor] as CoroutineDispatcher)
            try { test(f) }
            finally { f.release.complete(Unit); withContext(NonCancellable) { f.clients.forEach { it.close() } }; dir.deleteRecursively() }
        }
    }

    @Test fun expiredCommittedRecoveryRefreshesThenPersistsBeforePublishing() = scenario { f ->
        val old = f.seed()
        val client = f.client(); client.auth.awaitInitialization()
        assertTrue("Expired durable recovery must renew when explicitly enabled", client.auth.loadFromStorage(autoRefresh = true))
        assertEquals(1, f.requests)
        assertTrue(f.usedOriginalRefresh)
        assertTrue(f.sentWithoutAccountHeader)
        assertEquals("A", client.auth.currentUserOrNull()!!.id)
        val committed = f.actual.readCommitted() as StoreOutcome.VerifiedCommitted
        assertNotEquals(old.ticket, committed.ticket)
        assertEquals(client.auth.currentSessionOrNull(), committed.session)
        assertTrue(committed.session!!.expiresAt > Clock.System.now())
        assertEquals("A", client.auth.sessionAuthority.lease().ownerId)
        assertTrue(client.auth.isAutoRefreshRunning)
    }

    @Test fun expiredStartupCompletesOnlyAfterExactRenewalAndFreshClientCanRecoverIt() = scenario { f ->
        f.seed()
        val client = f.client(startup = true)
        client.auth.awaitInitialization()
        assertEquals("A", client.auth.currentUserOrNull()?.id)
        assertEquals(1, f.requests)
        client.close()
        val bytes = f.record.readBytes()
        val next = f.client(); next.auth.awaitInitialization()
        assertTrue(next.auth.loadFromStorage(autoRefresh = false))
        assertEquals("A", next.auth.currentUserOrNull()!!.id)
        assertArrayEquals(bytes, f.record.readBytes())
        assertEquals(1, f.requests)
    }

    @Test fun expiredRecoveryWithRefreshDisabledPreservesBytesAndMakesNoRequest() = scenario { f ->
        f.seed(); val before = f.record.readBytes()
        val client = f.client(); client.auth.awaitInitialization()
        assertFalse(client.auth.loadFromStorage(autoRefresh = false))
        assertArrayEquals(before, f.record.readBytes())
        assertNull(client.auth.currentUserOrNull())
        assertEquals(0, f.requests)
    }

    @Test fun recoveryDoesNotRotateCredentialWhenPersistenceIsDisabled() = scenario { f ->
        f.seed(); val before = f.record.readBytes()
        val client = f.client(save = false); client.auth.awaitInitialization()
        assertFalse(client.auth.loadFromStorage(autoRefresh = true))
        assertArrayEquals(before, f.record.readBytes())
        assertEquals(0, f.requests)
        assertNull(client.auth.currentSessionOrNull())
    }

    @Test fun unexpiredRecoveryStillPublishesWithoutRefreshOrResave() = scenario { f ->
        f.seed(expired = false); val before = f.record.readBytes()
        val client = f.client(); client.auth.awaitInitialization()
        assertTrue(client.auth.loadFromStorage(autoRefresh = true))
        assertEquals("A", client.auth.currentUserOrNull()!!.id)
        assertArrayEquals(before, f.record.readBytes())
        assertEquals(0, f.requests)
    }

    private suspend fun unavailable(action: suspend () -> Unit) {
        try { action(); fail("Unverified recovery must not publish") }
        catch (_: AuthStorageUnavailableException) { }
    }

    private suspend fun stale(action: suspend () -> Unit) {
        try { action(); fail("Obsolete recovery must not recapture a successor") }
        catch (_: StaleAuthOperationException) { }
    }

    @Test fun successorAccountDuringRenewalCannotBeOverwrittenOrRetired() = scenario { f ->
        f.seed(); f.pause = true
        val client = f.client(); client.auth.awaitInitialization()
        val recovery = async { stale { client.auth.loadFromStorage(autoRefresh = true) } }
        f.entered.await()
        client.auth.importSession(syntheticSession("B"), autoRefresh = false)
        val lease = client.auth.sessionAuthority.lease(); val bytes = f.record.readBytes()
        f.release.complete(Unit); recovery.await()
        client.auth.sessionAuthority.commit(lease) { }
        assertEquals("B", client.auth.currentUserOrNull()!!.id)
        assertArrayEquals(bytes, f.record.readBytes())
        assertEquals(1, f.requests)
    }

    @Test fun independentlyChangedDurableHeadDuringRenewalCannotBeRebased() = scenario { f ->
        f.seed(); f.pause = true
        val client = f.client(); client.auth.awaitInitialization()
        val recovery = async { unavailable { client.auth.loadFromStorage(autoRefresh = true) } }
        f.entered.await(); f.actual.committed("B"); val bytes = f.record.readBytes()
        f.release.complete(Unit); recovery.await()
        assertArrayEquals(bytes, f.record.readBytes())
        assertNull(client.auth.currentUserOrNull())
        try { client.auth.sessionAuthority.lease(); fail() } catch (_: AuthAuthorityUnavailableException) { }
        assertEquals(1, f.requests)
    }

    @Test fun changedDurableHeadAfterReadIsRejectedBeforeRefreshRequest() = scenario { f ->
        f.seed()
        f.store = object : AtomicSessionStore by f.actual {
            override suspend fun readCommitted(): StoreOutcome {
                val captured = f.actual.readCommitted(); f.actual.committed("B"); return captured
            }
        }
        val client = f.client(); client.auth.awaitInitialization()
        unavailable { client.auth.loadFromStorage(autoRefresh = true) }
        assertEquals(0, f.requests)
        assertEquals("B", (f.actual.readCommitted() as StoreOutcome.VerifiedCommitted).session!!.user!!.id)
        assertNull(client.auth.currentUserOrNull())
    }

    @Test fun cancelledRefreshRetainsExpiredBytesAndPublishesNothing() = scenario { f ->
        f.seed(); val bytes = f.record.readBytes(); f.pause = true
        val client = f.client(); client.auth.awaitInitialization()
        val recovery = async { client.auth.loadFromStorage(autoRefresh = true) }
        f.entered.await(); recovery.cancelAndJoin()
        assertArrayEquals(bytes, f.record.readBytes())
        assertNull(client.auth.currentUserOrNull())
        assertFalse(client.auth.isAutoRefreshRunning)
        assertEquals(1, f.requests)
    }

    @Test fun wrongOwnerRefreshCannotChangeOrDeleteExpiredRecord() = scenario { f ->
        f.seed(); val bytes = f.record.readBytes(); f.body = SESSION_A.replace("\"id\":\"A\"", "\"id\":\"B\"")
        val client = f.client(); client.auth.awaitInitialization()
        try { client.auth.loadFromStorage(autoRefresh = true); fail() }
        catch (failure: IllegalStateException) { assertEquals("Recovery refresh changed owner", failure.message) }
        assertArrayEquals(bytes, f.record.readBytes())
        assertNull(client.auth.currentUserOrNull())
        assertEquals(1, f.requests)
    }

    @Test fun stillExpiredReplyCannotBecomeATrustedRecoveryOrRetryLoop() = scenario { f ->
        f.seed(); val bytes = f.record.readBytes(); f.body = SESSION_A.replace("86400", "-60")
        val client = f.client(); client.auth.awaitInitialization()
        try { client.auth.loadFromStorage(autoRefresh = true); fail() }
        catch (failure: IllegalStateException) { assertEquals("Recovery refresh is already expired", failure.message) }
        assertArrayEquals(bytes, f.record.readBytes())
        assertNull(client.auth.currentUserOrNull())
        assertEquals(1, f.requests)
    }

    @Test fun rejectedRefreshDoesNotDeleteRecordOrClearAnExistingAccount() = scenario { f ->
        f.seed(); f.status = HttpStatusCode.BadRequest; f.body = """{"error_code":"session_not_found","msg":"synthetic rejection"}"""
        val client = f.client(); client.auth.awaitInitialization()
        client.auth.config.autoSaveToStorage = false
        client.auth.importSession(syntheticSession("B"), autoRefresh = false)
        client.auth.config.autoSaveToStorage = true
        val lease = client.auth.sessionAuthority.lease(); val bytes = f.record.readBytes()
        try { client.auth.loadFromStorage(autoRefresh = true); fail() }
        catch (_: io.github.jan.supabase.exceptions.RestException) { }
        assertArrayEquals(bytes, f.record.readBytes())
        assertEquals("B", client.auth.currentUserOrNull()!!.id)
        client.auth.sessionAuthority.commit(lease) { }
        assertTrue(f.sentWithoutAccountHeader)
        assertEquals(1, f.requests)
    }

    @Test fun startupNetworkFailureCompletesWithoutPurgingOrAutomaticRetry() = scenario { f ->
        f.seed(); val bytes = f.record.readBytes(); f.transportFailure = true
        val client = f.client(startup = true)
        client.auth.awaitInitialization()
        assertTrue(client.auth.sessionStatus.value is SessionStatus.NotAuthenticated)
        assertNull(client.auth.currentUserOrNull())
        assertArrayEquals(bytes, f.record.readBytes())
        assertEquals(1, f.requests)
        assertFalse(client.auth.isAutoRefreshRunning)
    }

    @Test fun startupServerFailureCompletesWithoutRetryOrTrustedExpiredSession() = scenario { f ->
        f.seed(); val bytes = f.record.readBytes(); f.status = HttpStatusCode.ServiceUnavailable; f.body = "{}"
        val client = f.client(startup = true); client.auth.awaitInitialization()
        assertTrue(client.auth.sessionStatus.value is SessionStatus.NotAuthenticated)
        assertArrayEquals(bytes, f.record.readBytes())
        assertEquals(1, f.requests)
    }

    @Test fun startupReadExceptionCannotLeaveInitializationPending() = scenario { f ->
        f.seed(); val bytes = f.record.readBytes()
        f.store = object : AtomicSessionStore by f.actual {
            override suspend fun readCommitted(): StoreOutcome = throw java.io.IOException("synthetic read failure")
        }
        val client = f.client(startup = true); client.auth.awaitInitialization()
        assertTrue(client.auth.sessionStatus.value is SessionStatus.NotAuthenticated)
        assertArrayEquals(bytes, f.record.readBytes())
        assertEquals(0, f.requests)
    }

    @Test fun startupRefreshFailureCannotClearANewerLogin() = scenario { f ->
        f.seed(); f.pause = true; f.status = HttpStatusCode.BadRequest; f.body = "{}"
        val client = f.client(startup = true)
        f.entered.await()
        val startup = requireNotNull((client.auth as AuthImpl).authScope.coroutineContext[Job]).children.single()
        client.auth.importSession(syntheticSession("B"), autoRefresh = false)
        val lease = client.auth.sessionAuthority.lease(); val bytes = f.record.readBytes()
        f.release.complete(Unit)
        startup.join() // Await the actual captured startup job, not an arbitrary scheduler delay.
        client.auth.awaitInitialization()
        client.auth.sessionAuthority.commit(lease) { }
        assertEquals("B", client.auth.currentUserOrNull()!!.id)
        assertArrayEquals(bytes, f.record.readBytes())
        assertEquals(1, f.requests)
    }

    @Test fun inheritedOldRestoreContextCannotRecaptureCurrentAccount() = scenario { f ->
        f.seed(expired = false)
        val client = f.client(); client.auth.awaitInitialization()
        val auth = client.auth as AuthImpl
        val old = auth.sessionAuthority.operation()
        auth.importSession(syntheticSession("B"), autoRefresh = false)
        val bytes = f.record.readBytes(); val lease = auth.sessionAuthority.lease()
        withContext(AuthCallContext(auth, old, null)) { stale { auth.loadFromStorage(autoRefresh = true) } }
        assertEquals("B", auth.currentUserOrNull()!!.id)
        assertArrayEquals(bytes, f.record.readBytes())
        auth.sessionAuthority.commit(lease) { }
        assertEquals(0, f.requests)
    }

    @Test fun shutdownDuringRefreshRejectsLatePublicationAndKeepsBytes() = scenario { f ->
        f.seed(); f.pause = true; val bytes = f.record.readBytes()
        val client = f.client(); client.auth.awaitInitialization()
        val recovery = async { stale { client.auth.loadFromStorage(autoRefresh = true) } }
        f.entered.await(); client.close(); f.release.complete(Unit); recovery.await()
        assertArrayEquals(bytes, f.record.readBytes())
        assertNull(client.auth.currentUserOrNull())
        assertFalse(client.auth.isAutoRefreshRunning)
    }

    @Test fun failureAfterDurableCommitIsNotMistakenForMemorySuccessOrRollback() = scenario { f ->
        f.seed()
        f.store = FileAtomicSessionStore(f.directory, ProtocolTestFiles) { if (it == StorePoint.AFTER_COMMIT) error("synthetic ambiguous commit") }
        val client = f.client(); client.auth.awaitInitialization()
        unavailable { client.auth.loadFromStorage(autoRefresh = true) }
        assertNull(client.auth.currentUserOrNull())
        assertFalse(client.auth.isAutoRefreshRunning)
        try { client.auth.sessionAuthority.lease(); fail() } catch (_: AuthAuthorityUnavailableException) { }
        val committed = f.actual.readCommitted() as StoreOutcome.VerifiedCommitted
        assertTrue(committed.session!!.expiresAt > Clock.System.now())
        f.store = f.actual
        val next = f.client(); next.auth.awaitInitialization()
        assertTrue(next.auth.loadFromStorage(autoRefresh = false))
        assertEquals("A", next.auth.currentUserOrNull()!!.id)
        assertEquals(1, f.requests)
    }

    @Test fun unknownOrLegacyStorageNeverStartsCredentialRefresh() = scenario { f ->
        f.record.writeText("raw-synthetic-legacy"); val bytes = f.record.readBytes()
        val client = f.client(startup = true); client.auth.awaitInitialization()
        assertNull(client.auth.currentUserOrNull())
        assertArrayEquals(bytes, f.record.readBytes())
        assertEquals(0, f.requests)
    }

    @Test fun cancelledAfterRenewalCommitCannotPublishButFreshClientRecoversExactCommit() = scenario { f ->
        f.seed()
        lateinit var renewal: Job
        f.store = FileAtomicSessionStore(f.directory, ProtocolTestFiles) { if (it == StorePoint.AFTER_COMMIT) renewal.cancel() }
        val client = f.client(); client.auth.awaitInitialization()
        renewal = launch(start = CoroutineStart.LAZY) { client.auth.loadFromStorage(autoRefresh = true) }
        renewal.start(); renewal.join()
        assertTrue(renewal.isCancelled)
        assertNull(client.auth.currentUserOrNull())
        assertFalse(client.auth.isAutoRefreshRunning)
        val current = f.actual.readCommitted() as StoreOutcome.VerifiedCommitted
        assertTrue(current.session!!.expiresAt > Clock.System.now())
        f.store = f.actual
        val fresh = f.client(); fresh.auth.awaitInitialization()
        assertTrue(fresh.auth.loadFromStorage(autoRefresh = false))
        assertEquals("A", fresh.auth.currentUserOrNull()!!.id)
        assertEquals(1, f.requests)
    }

    @Test fun externalCommitBetweenPrepareAndFinalCasCannotBeOverwritten() = scenario { f ->
        f.seed()
        f.store = object : AtomicSessionStore by f.actual {
            override suspend fun verifyPrepared(ticket: StoreTicket): StoreOutcome {
                val verified = f.actual.verifyPrepared(ticket)
                f.actual.committed("B")
                return verified
            }
        }
        val client = f.client(); client.auth.awaitInitialization()
        unavailable { client.auth.loadFromStorage(autoRefresh = true) }
        assertNull(client.auth.currentSessionOrNull())
        assertEquals("B", (f.actual.readCommitted() as StoreOutcome.VerifiedCommitted).session!!.user!!.id)
        assertEquals(1, f.requests)
    }

    @Test fun externalCommitBeforeFinalPublicationCannotPublishRenewedOldAccount() = scenario { f ->
        f.seed()
        f.store = object : AtomicSessionStore by f.actual {
            override suspend fun commitIfCurrent(ticket: StoreTicket): StoreOutcome {
                val committed = f.actual.commitIfCurrent(ticket)
                f.actual.committed("B")
                return committed
            }
        }
        val client = f.client(); client.auth.awaitInitialization()
        unavailable { client.auth.loadFromStorage(autoRefresh = true) }
        assertNull(client.auth.currentSessionOrNull())
        assertEquals("B", (f.actual.readCommitted() as StoreOutcome.VerifiedCommitted).session!!.user!!.id)
        assertEquals(1, f.requests)
    }

    @Test fun delayedStartupReadFailureCannotResetNewerLogin() = scenario { f ->
        f.seed()
        f.store = object : AtomicSessionStore by f.actual {
            override suspend fun readCommitted(): StoreOutcome {
                f.entered.complete(Unit); f.release.await()
                throw java.io.IOException("synthetic delayed read failure")
            }
        }
        val client = f.client(startup = true)
        f.entered.await()
        val startup = requireNotNull((client.auth as AuthImpl).authScope.coroutineContext[Job]).children.single()
        client.auth.importSession(syntheticSession("B"), autoRefresh = false)
        val lease = client.auth.sessionAuthority.lease(); val bytes = f.record.readBytes()
        f.release.complete(Unit); startup.join(); client.auth.awaitInitialization()
        client.auth.sessionAuthority.commit(lease) { }
        assertEquals("B", client.auth.currentUserOrNull()!!.id)
        assertArrayEquals(bytes, f.record.readBytes())
        assertEquals(0, f.requests)
    }

    @Test fun preparedOrUnavailableStoreDoesNotBecomeACredentialRefreshSource() = scenario { f ->
        f.seed(); f.actual.stage("B"); val bytes = f.record.readBytes()
        val client = f.client(startup = true); client.auth.awaitInitialization()
        assertNull(client.auth.currentUserOrNull())
        assertArrayEquals(bytes, f.record.readBytes())
        f.store = object : AtomicSessionStore by f.actual {
            override suspend fun readCommitted() = StoreOutcome.Unavailable(StoreFailure.IO)
        }
        val next = f.client(startup = true); next.auth.awaitInitialization()
        assertNull(next.auth.currentUserOrNull())
        assertArrayEquals(bytes, f.record.readBytes())
        assertEquals(0, f.requests)
    }

    companion object {
        private const val SESSION_A = """{"access_token":"synthetic-renewed-A","refresh_token":"synthetic-renewed-refresh-A","token_type":"bearer","expires_in":86400,"user":{"aud":"authenticated","id":"A"}}"""
    }
}
