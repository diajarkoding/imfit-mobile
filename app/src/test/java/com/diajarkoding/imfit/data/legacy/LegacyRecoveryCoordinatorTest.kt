package com.diajarkoding.imfit.data.legacy

import com.diajarkoding.imfit.data.remote.TemplateWriteRequest
import com.diajarkoding.imfit.data.remote.online.*
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class LegacyRecoveryCoordinatorTest {
    private val owner = "00000000-0000-0000-0000-000000000001"
    private val workoutId = "00000000-0000-0000-0000-000000000002"
    private val templateId = "00000000-0000-0000-0000-000000000003"
    private val originalRequest = "00000000-0000-0000-0000-000000000004"
    private val now = "2026-10-29T00:00:00Z"
    private val reads = ScriptedReadGateway().also { it.auth.signIn(owner, "synthetic") }
    private val changes = OnlineMutationChanges(reads.auth)
    private val stored = mutableMapOf<String, LegacyRecoveryCheckpoint>()
    private val writes = mutableListOf<LegacyRecoveryCheckpoint>()
    private var failConfirmationWrite = false
    private var sourcePresent = true
    private val store = object : LegacyRecoveryCheckpointStore {
        override suspend fun load(ownerId: String) = stored[ownerId]
        override suspend fun save(checkpoint: LegacyRecoveryCheckpoint) {
            checkpoint.validate(checkpoint.ownerId)
            if (failConfirmationWrite && checkpoint.actions.any { it.state == LegacyRecoveryState.CONFIRMED }) {
                throw LegacyRecoveryCheckpointException()
            }
            stored[checkpoint.ownerId] = checkpoint
            writes += checkpoint
        }
    }
    private var row: Map<String, String?> = mapOf(
        "id" to workoutId, "user_id" to owner, "template_id" to null, "template_name" to "Synthetic",
        "date" to "1793232000000", "start_time" to "1793232000000", "end_time" to "1793232060000",
        "total_volume" to "0", "total_sets" to "0", "total_reps" to "0", "deleted_at" to null,
        "created_at" to "1793232000000", "updated_at" to "1793232060000",
        "sync_status" to "PENDING_SYNC", "pending_operation" to "CREATE",
    )
    private var kind = LegacyKind.WORKOUT
    private var disposition = LegacyDisposition.PENDING_CREATE
    private fun item() = LegacyItem(kind, if (kind == LegacyKind.TEMPLATE_INTENT) "intent-key" else workoutId,
        disposition, legacyRowFingerprint(row), requestId = if (kind == LegacyKind.TEMPLATE_INTENT) originalRequest else null)
    private fun local(ownerId: String = owner) = if (ownerId == owner) LegacyInventory.Present(owner, 9,
        fingerprint(row.toString()), if (sourcePresent) listOf(item()) else emptyList(), emptyList()) else LegacyInventory.Present(ownerId, 9, fingerprint("other"), emptyList(), emptyList())
    private val inventory = LegacyInventoryReader { local(it) }
    private val aggregateReader = LegacyAggregateReader { ownerId, selected, evidence ->
        val fresh = local(ownerId)
        require(fresh.evidence() == evidence && selected == item())
        LegacyAggregate(fresh, selected, mapOf(kind to listOf(row)),
            mapOf("TEMPLATE" to emptyList(), "TEMPLATE_EXERCISE" to emptyList(), "EXERCISE" to emptyList()),
            fresh.aggregateFingerprint(selected), fresh.dependencyFingerprint())
    }
    private fun coordinator() = LegacyRecoveryCoordinator(reads, reads.repository, inventory, aggregateReader, store, changes)
    private fun identity() = reads.auth.capture().identity
    private suspend fun prepare() = coordinator().prepare(identity(), LegacySelection.ImportWorkout(local(), item()))
    private fun receipt(action: LegacyRecoveryAction, digest: String = "b".repeat(64), count: Int = 0): String = buildJsonObject {
        put("request_id", action.requestId); put("operation", action.operation); put("workout_id", workoutId)
        put("source_fingerprint", action.aggregateFingerprint); put("payload_digest", digest); put("outcome", "IMPORTED")
        put("id_mapping", buildJsonObject { put(workoutId, workoutId) }); put("exercise_count", 0); put("set_count", count); put("committed_at", now)
    }.toString()
    private fun lookup(action: LegacyRecoveryAction, result: String, resourceType: String = "WORKOUT", resourceId: String = workoutId) = buildJsonObject {
        put("state", "COMMITTED"); put("receipt", buildJsonObject {
            put("request_id", action.requestId); put("operation", action.operation); put("resource_type", resourceType)
            put("resource_id", resourceId); put("committed_at", now); put("committed_at_source", "COMMIT")
            put("result", Json.parseToJsonElement(result))
        })
    }.toString()

    @Test fun `crash after PREPARED uses the same body IDs and writes SUBMITTED before dispatch`() = runTest {
        val prepared = prepare()
        assertEquals(LegacyRecoveryState.PREPARED, stored.getValue(owner).actions.single().state)
        val restarted = coordinator()
        val send = async { restarted.submit(identity(), prepared.actionId) }
        reads.api.requests.receive().also {
            assertEquals(prepared.requestJson, it.body)
            assertEquals(LegacyRecoveryState.SUBMITTED, stored.getValue(owner).actions.single().state)
            it.reply(receipt(prepared))
        }
        assertTrue(send.await() is LegacyRecoveryResult.Confirmed)
        assertEquals(listOf(LegacyRecoveryState.PREPARED, LegacyRecoveryState.SUBMITTED, LegacyRecoveryState.CONFIRMED), writes.map { it.actions.single().state })
        assertEquals(setOf(item()), restarted.validatedCoverage(identity(), local()))
    }

    @Test fun `response loss resolves targeted status and exact original replay across coordinator restart`() = runTest {
        val prepared = prepare()
        val send = async { coordinator().submit(identity(), prepared.actionId) }
        reads.api.requests.receive().loseResponse()
        assertTrue(send.await() is LegacyRecoveryResult.Unresolved)
        val resolving = async { coordinator().resolve(identity(), prepared.actionId) }
        reads.api.requests.receive().also { assertEquals("get_operation_status", it.rpc); it.reply(lookup(prepared, receipt(prepared))) }
        reads.api.requests.receive().also { assertEquals(prepared.requestJson, it.body); it.reply(receipt(prepared)) }
        assertTrue(resolving.await() is LegacyRecoveryResult.Confirmed)
        assertEquals(1, stored.getValue(owner).actions.size)
    }

    @Test fun `crash after SUBMITTED with NOT_FOUND requires explicit original retry`() = runTest {
        val prepared = prepare()
        stored[owner] = stored.getValue(owner).copy(actions = listOf(prepared.copy(state = LegacyRecoveryState.SUBMITTED)))
        val resolving = async { coordinator().resolve(identity(), prepared.actionId) }
        reads.api.requests.receive().reply("""{"state":"NOT_FOUND","receipt":null}""")
        assertTrue(resolving.await() is LegacyRecoveryResult.RetryOriginalRequired)
        assertTrue(reads.api.requests.tryReceive().isFailure)
        val retry = async { coordinator().submit(identity(), prepared.actionId) }
        reads.api.requests.receive().reply("""{"state":"NOT_FOUND","receipt":null}""")
        reads.api.requests.receive().also { assertEquals(prepared.requestJson, it.body); it.reply(receipt(prepared)) }
        assertTrue(retry.await() is LegacyRecoveryResult.Confirmed)
    }

    @Test fun `source change before dispatch blocks without importing old snapshot`() = runTest {
        val prepared = prepare()
        row = row + ("template_name" to "Changed")
        assertTrue(coordinator().submit(identity(), prepared.actionId) is LegacyRecoveryResult.Blocked)
        assertTrue(reads.api.requests.tryReceive().isFailure)
        assertTrue(coordinator().validatedCoverage(identity(), local()).isEmpty())
    }

    @Test fun `source change after dispatch must resolve the original before new review`() = runTest {
        val prepared = prepare()
        val send = async { coordinator().submit(identity(), prepared.actionId) }
        reads.api.requests.receive().loseResponse()
        send.await()
        row = row + ("template_name" to "Changed")
        assertTrue(runCatching { prepare() }.isFailure)
        val resolving = async { coordinator().resolve(identity(), prepared.actionId) }
        reads.api.requests.receive().reply(lookup(prepared, receipt(prepared)))
        reads.api.requests.receive().also { assertEquals(prepared.requestJson, it.body); it.reply(receipt(prepared)) }
        assertTrue(resolving.await() is LegacyRecoveryResult.Confirmed)
        assertTrue(coordinator().validatedCoverage(identity(), local()).isEmpty())
    }

    @Test fun `definitive conflict is durable and never interpreted as archive or synced`() = runTest {
        val prepared = prepare()
        val send = async { coordinator().submit(identity(), prepared.actionId) }
        reads.api.requests.receive().reply("""{"code":"PT409","message":"LEGACY_IMPORT_CONFLICT","details":null,"hint":null}""", 409)
        assertTrue(send.await() is LegacyRecoveryResult.Conflict)
        assertEquals(LegacyRecoveryState.CONFLICT, coordinator().pendingActions(identity()).single().state)
        assertTrue(coordinator().validatedCoverage(identity(), local()).isEmpty())
        assertTrue(coordinator().submit(identity(), prepared.actionId) is LegacyRecoveryResult.Conflict)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @Test fun `malformed success does not confirm and server digest is not the Android body digest`() = runTest {
        val prepared = prepare()
        val send = async { coordinator().submit(identity(), prepared.actionId) }
        reads.api.requests.receive().reply(receipt(prepared, count = 1))
        assertTrue(send.await() is LegacyRecoveryResult.Unresolved)
        assertNull(stored.getValue(owner).actions.single().validatedReceipt)
        val resolving = async { coordinator().resolve(identity(), prepared.actionId) }
        reads.api.requests.receive().reply(lookup(prepared, receipt(prepared)))
        reads.api.requests.receive().reply(receipt(prepared))
        val confirmed = (resolving.await() as LegacyRecoveryResult.Confirmed).action
        assertEquals("b".repeat(64), confirmed.serverPayloadDigest)
        assertNotEquals(confirmed.bodyDigest, confirmed.serverPayloadDigest)
        val replay = async { coordinator().resolve(identity(), prepared.actionId) }
        reads.api.requests.receive().reply(lookup(prepared, receipt(prepared, "c".repeat(64))))
        assertTrue(replay.await() is LegacyRecoveryResult.Unresolved)
        assertEquals("b".repeat(64), stored.getValue(owner).actions.single().serverPayloadDigest)
    }

    @Test fun `account switch cannot publish old success but same owner new login can resolve`() = runTest {
        val prepared = prepare()
        val oldIdentity = identity()
        reads.api.ignoreCancellation = true
        val send = async { runCatching { coordinator().submit(oldIdentity, prepared.actionId) } }
        val pending = reads.api.requests.receive()
        reads.auth.signIn("other-owner", "synthetic-B")
        pending.reply(receipt(prepared))
        assertTrue(send.await().exceptionOrNull() is StaleAuthException)
        assertTrue(coordinator().pendingActions(identity()).isEmpty())
        assertTrue(stored.getValue(owner).actions.single().state == LegacyRecoveryState.SUBMITTED)
        reads.auth.signIn(owner, "synthetic-new-A")
        val resolving = async { coordinator().resolve(identity(), prepared.actionId) }
        reads.api.requests.receive().reply(lookup(prepared, receipt(prepared)))
        reads.api.requests.receive().also { assertEquals(prepared.requestJson, it.body); it.reply(receipt(prepared)) }
        assertTrue(resolving.await() is LegacyRecoveryResult.Confirmed)
    }

    @Test fun `template intent discovery replays full original request without new IDs`() = runTest {
        kind = LegacyKind.TEMPLATE_INTENT
        disposition = LegacyDisposition.REVIEW
        val request = TemplateWriteRequest(originalRequest, templateId, 5, "Original", emptyList(), emptyList(), false, owner)
        row = mapOf("key" to "intent-key", "user_id" to owner, "template_id" to templateId, "payload" to onlineJson.encodeToString(request))
        val prepared = coordinator().prepare(identity(), LegacySelection.ReplayTemplateIntent(local(), item()))
        val result = """{"request_id":"$originalRequest","template_id":"$templateId","revision":6,"exercise_count":0,"server_updated_at":"$now"}"""
        val resolving = async { coordinator().resolve(identity(), prepared.actionId) }
        reads.api.requests.receive().reply(lookup(prepared, result, "TEMPLATE", templateId))
        reads.api.requests.receive().also {
            assertEquals("save_template_revision", it.rpc)
            assertEquals(OnlineRpc.saveTemplate(request).parameters, it.body)
            it.reply(result)
        }
        assertTrue(resolving.await() is LegacyRecoveryResult.Confirmed)
        assertEquals(setOf(item()), coordinator().validatedCoverage(identity(), local()))
    }

    @Test fun `local abandonment is fingerprint bound separate consent preserving changed source reviews`() = runTest {
        kind = LegacyKind.ACTIVE_SESSION
        disposition = LegacyDisposition.REVIEW
        val original = row
        val first = coordinator().abandonLocalActive(identity(), local(), item())
        assertEquals("PRESERVE_NON_RESUMABLE_ARCHIVE", first.decision)
        assertEquals(original, row)
        assertTrue(stored.getValue(owner).actions.isEmpty())
        assertEquals(setOf(item()), coordinator().validatedCoverage(identity(), local()))
        row = row + ("session_data" to "changed-local-json")
        assertTrue(coordinator().validatedCoverage(identity(), local()).isEmpty())
        coordinator().abandonLocalActive(identity(), local(), item())
        assertEquals(2, stored.getValue(owner).localResolutions.size)
        assertEquals(setOf(item()), coordinator().validatedCoverage(identity(), local()))
        kind = LegacyKind.TEMPLATE_INTENT
        assertTrue(runCatching { coordinator().abandonLocalActive(identity(), local(), item()) }.isFailure)
    }

    @Test fun `foreign malformed unsupported and excessive checkpoints block rather than overwrite`() = runTest {
        val prepared = prepare()
        val original = stored.getValue(owner)
        for (bad in listOf(original.copy(ownerId = "foreign"), original.copy(formatVersion = 2),
            original.copy(actions = listOf(prepared.copy(bodyDigest = "0".repeat(64)))),
            original.copy(actions = List(10_001) { prepared.copy(actionId = UUIDForTest(it), requestId = UUIDForTest(it + 20_000)) }))) {
            stored[owner] = bad
            assertTrue(runCatching { coordinator().validatedCoverage(identity(), local()) }.isFailure)
            assertEquals(bad, stored.getValue(owner))
        }
    }

    @Test fun `response received before checkpoint failure remains resolvable original work`() = runTest {
        val prepared = prepare()
        failConfirmationWrite = true
        val send = async { runCatching { coordinator().submit(identity(), prepared.actionId) } }
        reads.api.requests.receive().reply(receipt(prepared))
        assertTrue(send.await().exceptionOrNull() is LegacyRecoveryCheckpointException)
        assertEquals(LegacyRecoveryState.SUBMITTED, stored.getValue(owner).actions.single().state)
        failConfirmationWrite = false
        val resolving = async { coordinator().resolve(identity(), prepared.actionId) }
        reads.api.requests.receive().reply(lookup(prepared, receipt(prepared)))
        reads.api.requests.receive().also { assertEquals(prepared.requestJson, it.body); it.reply(receipt(prepared)) }
        assertTrue(resolving.await() is LegacyRecoveryResult.Confirmed)
    }

    @Test fun `durable pending work survives disappearing source and remains owner scoped`() = runTest {
        val prepared = prepare()
        sourcePresent = false
        assertEquals(prepared, coordinator().pendingActions(identity()).single())
        assertTrue(coordinator().validatedCoverage(identity(), local()).isEmpty())
        assertTrue(coordinator().submit(identity(), prepared.actionId) is LegacyRecoveryResult.Blocked)
        reads.auth.signIn("other-owner", "synthetic-B")
        assertTrue(coordinator().pendingActions(identity()).isEmpty())
        assertEquals(owner, stored.getValue(owner).actions.single().ownerId)
    }

    @Test fun `server archive uses selected fingerprint CAS and validated receipt not local consent`() = runTest {
        val hash = "a".repeat(64)
        val selected = LegacyRecoveryItem(workoutId, hash, null, buildJsonObject {
            put("id", workoutId); put("user_id", owner); put("version", JsonNull); put("schema_version", JsonNull)
        })
        val preparing = async { coordinator().prepare(identity(), LegacySelection.ArchiveServerSession(selected)) }
        reads.api.requests.receive().also {
            assertEquals("get_start_recovery_status", it.rpc)
            it.reply("""{"contract_version":1,"checked_at":"$now","can_create":false,"blockers":[{"kind":"LEGACY_SESSION","resource_id":"$workoutId","version":null,"source_fingerprint":"$hash","reason":"LEGACY_REVIEW_REQUIRED"}],"has_more_blockers":false}""")
        }
        val prepared = preparing.await()
        val send = async { coordinator().submit(identity(), prepared.actionId) }
        reads.api.requests.receive().also {
            assertEquals("resolve_legacy_session", it.rpc)
            assertEquals(JsonPrimitive(hash), it.body["p_expected_source_fingerprint"])
            it.reply("""{"request_id":"${prepared.requestId}","operation":"resolve_legacy_session","session_id":"$workoutId","source_fingerprint":"$hash","resolution":"KEEP_ARCHIVED","committed_at":"$now"}""")
        }
        assertTrue(send.await() is LegacyRecoveryResult.Confirmed)
        assertTrue(stored.getValue(owner).localResolutions.isEmpty())
        assertTrue(coordinator().validatedCoverage(identity(), local()).isEmpty())
    }

    @Test fun `review targets versioned blocker alongside legacy without paging old data`() = runTest {
        val hash = "a".repeat(64)
        val reviewing = async { coordinator().review() }
        reads.api.requests.receive().also {
            assertEquals("get_start_recovery_status", it.rpc)
            it.reply("""{"contract_version":1,"checked_at":"$now","can_create":false,"blockers":[{"kind":"LEGACY_SESSION","resource_id":"$workoutId","version":null,"source_fingerprint":"$hash","reason":"LEGACY_REVIEW_REQUIRED"},{"kind":"ACTIVE_SESSION","resource_id":"$templateId","version":2,"source_fingerprint":null,"reason":"SESSION_REVIEW_REQUIRED"}],"has_more_blockers":false}""")
        }
        reads.api.requests.receive().also {
            assertEquals("get_active_session", it.rpc)
            assertEquals(JsonPrimitive(templateId), it.body["p_session_id"])
            val snapshot = JsonObject(Json.parseToJsonElement(OnlineFixtures.snapshot).jsonObject + ("id" to JsonPrimitive(templateId)))
            it.reply(buildJsonObject { put("found", true); put("session", snapshot) }.toString())
        }
        assertEquals(templateId, reviewing.await().remoteCurrent!!.session!!.id)
        assertTrue(reads.api.requests.tryReceive().isFailure)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test fun `only validated durable import receipts publish and replay deduplicates`() = runTest {
        val events = mutableListOf<OnlineMutationChange>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { changes.events.collect { events += it } }
        val prepared = prepare()
        val send = async { coordinator().submit(identity(), prepared.actionId) }
        reads.api.requests.receive().reply(receipt(prepared, count = 1))
        assertTrue(send.await() is LegacyRecoveryResult.Unresolved)
        assertTrue(events.isEmpty())
        val resolving = async { coordinator().resolve(identity(), prepared.actionId) }
        reads.api.requests.receive().reply(lookup(prepared, receipt(prepared)))
        reads.api.requests.receive().reply(receipt(prepared))
        assertTrue(resolving.await() is LegacyRecoveryResult.Confirmed)
        assertEquals(prepared.requestId, (events.single() as WorkoutCommitted).requestId)
        val replay = async { coordinator().resolve(identity(), prepared.actionId) }
        reads.api.requests.receive().reply(lookup(prepared, receipt(prepared)))
        reads.api.requests.receive().reply(receipt(prepared))
        assertTrue(replay.await() is LegacyRecoveryResult.Confirmed)
        assertEquals(1, events.size)
    }

    @Test fun `A B A before response cannot confirm under the old epoch`() = runTest {
        val prepared = prepare()
        val oldIdentity = identity()
        reads.api.ignoreCancellation = true
        val send = async { runCatching { coordinator().submit(oldIdentity, prepared.actionId) } }
        val pending = reads.api.requests.receive()
        reads.auth.signIn("other-owner", "synthetic-B")
        reads.auth.signIn(owner, "synthetic-new-A")
        assertNotEquals(oldIdentity, identity())
        pending.reply(receipt(prepared))
        assertTrue(send.await().exceptionOrNull() is StaleAuthException)
        assertEquals(LegacyRecoveryState.SUBMITTED, stored.getValue(owner).actions.single().state)
        assertTrue(coordinator().validatedCoverage(identity(), local()).isEmpty())
    }

    private fun UUIDForTest(value: Int) = "00000000-0000-0000-0000-${value.toString().padStart(12, '0')}"
}
