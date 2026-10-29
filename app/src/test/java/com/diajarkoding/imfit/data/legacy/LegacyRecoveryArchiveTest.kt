package com.diajarkoding.imfit.data.legacy

import com.diajarkoding.imfit.data.remote.online.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.*
import java.util.UUID

class LegacyRecoveryArchiveTest {
    private val reads = ScriptedReadGateway()
    private fun item(kind: LegacyKind = LegacyKind.WORKOUT, payload: String = "original", disposition: LegacyDisposition = LegacyDisposition.PENDING_CREATE) =
        LegacyItem(kind, "id-$kind", disposition, fingerprint(payload))
    private fun local(vararg items: LegacyItem, version: Int = 9) = LegacyInventory.Present("A", version,
        fingerprint(items.joinToString() + version), items.toList(), emptyList())
    private var source: LegacyInventory = local(item())
    private val reader = LegacyInventoryReader { source }
    private val saved = mutableMapOf<String, LegacyArchiveReview>()
    private var failArchiveRead = false
    private val archive = object : LegacyArchiveStore {
        override suspend fun load(ownerId: String): LegacyArchiveReview? {
            check(!failArchiveRead) { "Archive is unreadable" }
            return saved[ownerId]
        }
        override suspend fun save(review: LegacyArchiveReview) { saved[review.ownerId] = review }
    }
    private val recovery = LegacyRecoveryArchive(reads.auth, reader, archive)
    private val checkpoints = object : LegacyCheckpointStore {
        override suspend fun load(ownerId: String): LegacyCheckpoint? = null
        override suspend fun record(checkpoint: LegacyCheckpoint) = Unit
    }
    private val recoveryCheckpoints = MemoryLegacyRecoveryCheckpoints()
    private val coordinator = testRecoveryCoordinator(reads, reads.repository, reader, recoveryCheckpoints)
    private suspend fun preflight(vararg targetedRequests: String): LegacyStartReport = coroutineScope {
        val job = async { LegacyStartPreflight(reads, reader, checkpoints, coordinator, archive = archive).inspect() }
        reads.api.requests.receive().also {
            assertEquals("get_start_recovery_status", it.rpc)
            it.reply(LegacyReadinessFixtures.ready)
        }
        for (requestId in targetedRequests) {
            reads.api.requests.receive().also {
                assertEquals("get_operation_status", it.rpc)
                assertEquals(requestId, it.body.getValue("p_request_id").jsonPrimitive.content)
                it.reply("""{"state":"NOT_FOUND","receipt":null}""")
            }
        }
        job.await().also { assertTrue(reads.api.requests.tryReceive().isFailure) }
    }

    @Test fun `historical acknowledgement permits new start but source remains pending and accessible`() = runTest {
        assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, preflight().decision)
        val original = source as LegacyInventory.Present
        val review = recovery.keepHistory(reads.auth.capture().identity, original)
        assertEquals(setOf(original.items.single()), review.coveredItems(original))
        assertEquals(original, source)
        assertEquals(LegacyDisposition.PENDING_CREATE, (source as LegacyInventory.Present).items.single().disposition)
        assertEquals(LegacyStartDecision.READY, preflight().decision)
        assertEquals(original, recovery.review().first)
    }

    @Test fun `archiving history never resolves an active draft or uncertain intent`() = runTest {
        val requestId = UUID(0, 50).toString()
        val historical = item()
        val active = item(LegacyKind.ACTIVE_SESSION)
        val intent = item(LegacyKind.TEMPLATE_INTENT).copy(requestId = requestId)
        source = local(historical, active, intent)
        val displayed = source as LegacyInventory.Present
        val review = recovery.keepHistory(reads.auth.capture().identity, displayed)
        assertEquals(setOf(historical), review.coveredItems(displayed))
        assertEquals(1, saved.getValue("A").entries.size)
        assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, preflight(requestId).decision)
        assertEquals(displayed, source)
        assertTrue(recoveryCheckpoints.saved.isEmpty())
    }

    @Test fun `restored changed payload cannot reuse an old review`() = runTest {
        recovery.keepHistory(reads.auth.capture().identity, source as LegacyInventory.Present)
        source = local(item(payload = "changed restored original"))
        assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, preflight().decision)
    }

    @Test fun `changed inventory cannot confirm the previously displayed list`() = runTest {
        val displayed = source as LegacyInventory.Present
        source = local(item(payload = "new"))
        assertTrue(runCatching { recovery.keepHistory(reads.auth.capture().identity, displayed) }.isFailure)
        assertTrue(saved.isEmpty())
    }

    @Test fun `schema 6 and 7 read only bridge requires explicit matching history review`() = runTest {
        for (version in listOf(6, 7)) {
            saved.clear(); source = local(item(), version = version)
            assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, preflight().decision)
            recovery.keepHistory(reads.auth.capture().identity, source as LegacyInventory.Present)
            assertEquals(LegacyStartDecision.READY, preflight().decision)
        }
    }

    @Test fun `unknown schema and read failure cannot be archived as empty`() = runTest {
        val displayed = source as LegacyInventory.Present
        source = LegacyInventory.Unknown("A", LegacyReadFailure.UNSUPPORTED_SCHEMA)
        assertTrue(runCatching { recovery.keepHistory(reads.auth.capture().identity, displayed) }.isFailure)
        assertEquals(LegacyStartDecision.UNKNOWN, preflight().decision)
        assertTrue(saved.isEmpty())
    }

    @Test fun `corrupt item cannot receive an archive acknowledgement`() = runTest {
        source = local(item(disposition = LegacyDisposition.PROBLEM))
        assertTrue(runCatching { recovery.keepHistory(reads.auth.capture().identity, source as LegacyInventory.Present) }.isFailure)
        assertTrue(saved.isEmpty())
    }

    @Test fun `account A B A cannot confirm under previous login epoch`() = runTest {
        val original = reads.auth.capture().identity
        reads.auth.signIn("B", "B"); reads.auth.signIn("A", "new A")
        val failure = runCatching { recovery.keepHistory(original, source as LegacyInventory.Present) }.exceptionOrNull()
        assertTrue(failure is StaleAuthException); assertTrue(saved.isEmpty())
    }

    @Test fun `owner B cannot load or acknowledge A records`() = runTest {
        reads.auth.signIn("B", "B")
        assertTrue(runCatching { recovery.review() }.isFailure)
        assertTrue(runCatching { recovery.keepHistory(reads.auth.capture().identity, source as LegacyInventory.Present) }.isFailure)
        assertTrue(saved.isEmpty())
    }

    @Test fun `foreign archive cannot release preflight`() = runTest {
        saved["A"] = LegacyArchiveReview("B", 9, listOf(LegacyArchiveEntry(item().kind.name, item().id, item().fingerprint)))
        assertEquals(LegacyStartDecision.UNKNOWN, preflight().decision)
    }

    @Test fun `review without whole inventory evidence remains readable but cannot release Start`() = runTest {
        val historical = item()
        saved["A"] = LegacyArchiveReview("A", 9,
            listOf(LegacyArchiveEntry(historical.kind.name, historical.id, historical.fingerprint)))
        assertNotNull(recovery.review().second)
        assertTrue(saved.getValue("A").coveredItems(source as LegacyInventory.Present).isEmpty())
        assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, preflight().decision)
    }

    @Test fun `changed exercise or set invalidates history even when parent and file fingerprint match`() = runTest {
        val workout = item()
        val exercise = item(LegacyKind.EXERCISE_LOG).copy(parentId = workout.id)
        val set = item(LegacyKind.SET).copy(parentId = exercise.id)
        val original = local(workout, exercise, set)
        source = original
        val review = recovery.keepHistory(reads.auth.capture().identity, original)
        assertEquals(LegacyStartDecision.READY, preflight().decision)
        for (changed in listOf(
            original.copy(items = listOf(workout, exercise.copy(fingerprint = fingerprint("changed exercise")), set)),
            original.copy(items = listOf(workout, exercise, set.copy(fingerprint = fingerprint("changed set")))),
            original.copy(items = listOf(workout, exercise, set.copy(parentId = "other-exercise"))),
            original.copy(items = listOf(workout, exercise)),
        )) {
            source = changed
            assertEquals(original.fingerprint, changed.fingerprint)
            assertTrue(review.coveredItems(changed).isEmpty())
            assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, preflight().decision)
        }
        assertEquals(original.items.single { it.kind == LegacyKind.WORKOUT }, workout)
        assertEquals(review, saved.getValue("A"))
    }

    @Test fun `dependency payload availability and membership changes invalidate history`() = runTest {
        val dependency = LegacyDependency("TEMPLATE", "template", true, fingerprint("template revision A"))
        val original = local(item()).copy(dependencies = listOf(dependency))
        source = original
        val review = recovery.keepHistory(reads.auth.capture().identity, original)
        assertEquals(LegacyStartDecision.READY, preflight().decision)
        for (dependencies in listOf(
            listOf(dependency.copy(fingerprint = fingerprint("template revision B"))),
            listOf(dependency.copy(available = false)),
            listOf(dependency.copy(id = "different-template")),
            emptyList(),
        )) {
            val changed = original.copy(dependencies = dependencies)
            source = changed
            assertEquals(original.fingerprint, changed.fingerprint)
            assertTrue(review.coveredItems(changed).isEmpty())
            assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, preflight().decision)
        }
        assertEquals(review, saved.getValue("A"))
    }

    @Test fun `archive store failure cannot overwrite a previous review or allow Start`() = runTest {
        val original = source as LegacyInventory.Present
        val review = recovery.keepHistory(reads.auth.capture().identity, original)
        failArchiveRead = true
        assertTrue(runCatching { recovery.keepHistory(reads.auth.capture().identity, original) }.isFailure)
        assertEquals(review, saved.getValue("A"))
        assertEquals(LegacyStartDecision.UNKNOWN, preflight().decision)
    }

    @Test fun `history reviewed by A allows A while B remains pending and untouched`() = runTest {
        val originalA = source as LegacyInventory.Present
        val reviewA = recovery.keepHistory(reads.auth.capture().identity, originalA)
        assertEquals(LegacyStartDecision.READY, preflight().decision)
        val pendingB = local(item(payload = "owner B pending")).copy(ownerId = "B")
        reads.auth.signIn("B", "token B")
        source = pendingB
        assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, preflight().decision)
        assertEquals(pendingB, recovery.review().first)
        assertEquals(setOf("A"), saved.keys)
        assertTrue(reviewA.coveredItems(pendingB).isEmpty())
        reads.auth.signIn("A", "new token A")
        source = originalA
        assertEquals(LegacyStartDecision.READY, preflight().decision)
        assertEquals(originalA, source)
        assertEquals(reviewA, saved.getValue("A"))
    }

    @Test fun `never dispatched or definitively rejected import can remain historical without upload`() = runTest {
        val owner = UUID(0, 400).toString()
        reads.auth.signIn(owner, "owner token")
        val historical = item().copy(id = UUID(0, 401).toString())
        val original = local(historical).copy(ownerId = owner)
        source = original
        recovery.keepHistory(reads.auth.capture().identity, original)
        for (state in listOf(LegacyRecoveryState.PREPARED, LegacyRecoveryState.BLOCKED, LegacyRecoveryState.CONFLICT)) {
            recoveryCheckpoints.save(LegacyRecoveryCheckpoint(owner,
                actions = listOf(testImportAction(original, historical, state))))
            val report = preflight()
            assertEquals(LegacyStartDecision.READY, report.decision)
            assertTrue(report.operationIds.isEmpty())
            assertEquals(original, source)
        }
    }

    @Test fun `generic history acknowledgement never releases a submitted unknown import`() = runTest {
        val owner = UUID(0, 410).toString()
        reads.auth.signIn(owner, "owner token")
        val historical = item().copy(id = UUID(0, 411).toString())
        val original = local(historical).copy(ownerId = owner)
        source = original
        recovery.keepHistory(reads.auth.capture().identity, original)
        val action = testImportAction(original, historical, LegacyRecoveryState.SUBMITTED)
        recoveryCheckpoints.save(LegacyRecoveryCheckpoint(owner, actions = listOf(action)))
        val report = preflight(action.requestId)
        assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, report.decision)
        assertEquals(listOf(action.requestId), report.operationIds)
        assertEquals(LegacyRecoveryState.SUBMITTED, recoveryCheckpoints.saved.getValue(owner).actions.single().state)
        assertEquals(original, source)
    }

    @Test fun `confirmed import covers selected workout but not unrelated pending history`() = runTest {
        val owner = UUID(0, 420).toString()
        reads.auth.signIn(owner, "owner token")
        val selected = item().copy(id = UUID(0, 421).toString())
        val unrelated = item(payload = "unrelated").copy(id = UUID(0, 422).toString())
        val original = local(selected, unrelated).copy(ownerId = owner)
        source = original
        recoveryCheckpoints.save(LegacyRecoveryCheckpoint(owner,
            actions = listOf(testImportAction(original, selected, LegacyRecoveryState.CONFIRMED))))
        assertEquals(setOf(selected), coordinator.validatedCoverage(reads.auth.capture().identity, original))
        assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, preflight().decision)
        source = local(selected).copy(ownerId = owner)
        assertEquals(LegacyStartDecision.READY, preflight().decision)
        source = (source as LegacyInventory.Present).copy(
            dependencies = listOf(LegacyDependency("TEMPLATE", "new dependency", true, fingerprint("new dependency"))))
        assertEquals(LegacyStartDecision.RECOVERY_REQUIRED, preflight().decision)
    }
}
