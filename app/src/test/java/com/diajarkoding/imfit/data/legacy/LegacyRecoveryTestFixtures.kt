package com.diajarkoding.imfit.data.legacy

import com.diajarkoding.imfit.data.remote.online.*
import java.util.UUID
import kotlinx.serialization.json.*

/** Real coordinator with validated durable state; Start must never read selected raw aggregates. */
internal fun testRecoveryCoordinator(
    reads: RemoteReadGateway,
    repository: OnlineRepository,
    inventory: LegacyInventoryReader,
    checkpoints: MemoryLegacyRecoveryCheckpoints = MemoryLegacyRecoveryCheckpoints(),
) = LegacyRecoveryCoordinator(
    reads, repository, inventory,
    LegacyAggregateReader { _, _, _ -> error("Start readiness must not scan selected aggregates") },
    checkpoints, OnlineMutationChanges(reads.auth),
)

internal class MemoryLegacyRecoveryCheckpoints : LegacyRecoveryCheckpointStore {
    val saved = mutableMapOf<String, LegacyRecoveryCheckpoint>()
    var failRead = false

    override suspend fun load(ownerId: String): LegacyRecoveryCheckpoint? {
        check(!failRead) { "Recovery checkpoint unavailable" }
        return saved[ownerId]?.also { it.validate(ownerId) }
    }

    override suspend fun save(checkpoint: LegacyRecoveryCheckpoint) {
        checkpoint.validate(checkpoint.ownerId)
        saved[checkpoint.ownerId] = checkpoint
    }
}

internal object LegacyReadinessFixtures {
    val ready = response()

    fun response(
        kind: RecoveryBlockerKind = RecoveryBlockerKind.ACTIVE_SESSION,
        count: Int = 0,
        hasMore: Boolean = false,
    ): String = onlineJson.encodeToString(StartRecoveryStatus.serializer(), StartRecoveryStatus(
        contractVersion = 1,
        checkedAt = OnlineFixtures.time,
        canCreate = count == 0 && !hasMore,
        blockers = List(count) { index -> StartRecoveryBlocker(
            kind = kind,
            resourceId = UUID(0, index.toLong() + 1).toString(),
            version = if (kind == RecoveryBlockerKind.LEGACY_SESSION) null else 2,
            sourceFingerprint = if (kind == RecoveryBlockerKind.LEGACY_SESSION) fingerprint("legacy-$index") else null,
            reason = if (kind == RecoveryBlockerKind.LEGACY_SESSION) RecoveryBlockerReason.LEGACY_REVIEW_REQUIRED
                else RecoveryBlockerReason.SESSION_REVIEW_REQUIRED,
        ) },
        hasMoreBlockers = hasMore,
    ))
}

/** A valid durable original, including the immutable RPC body, not an echoed coverage answer. */
internal fun testServerArchiveAction(state: LegacyRecoveryState): LegacyRecoveryAction {
    val requestId = UUID(0, 200).toString()
    val sessionId = UUID(0, 201).toString()
    val source = fingerprint("server-source")
    val call = OnlineRpc.resolveLegacySession(requestId, sessionId, source)
    return LegacyRecoveryAction(
        actionId = UUID(0, 202).toString(),
        ownerId = "A",
        sourceSchemaVersion = null,
        sourceKind = "SERVER_LEGACY_SESSION",
        sourceId = sessionId,
        sourceFingerprint = source,
        aggregateFingerprint = source,
        dependencyFingerprint = fingerprint("server-dependencies"),
        operation = call.name,
        requestId = requestId,
        requestJson = call.parameters,
        bodyDigest = fingerprint(call.parameters.toString()),
        idMapping = emptyMap(),
        reviewDecision = LegacyReviewDecision.KEEP_SERVER_ARCHIVED,
        state = state,
    )
}

internal fun testImportAction(
    inventory: LegacyInventory.Present,
    item: LegacyItem,
    state: LegacyRecoveryState,
): LegacyRecoveryAction {
    val requestId = UUID(0, 300).toString()
    val aggregate = inventory.aggregateFingerprint(item)
    val mapping = mapOf(item.id to item.id)
    val call = OnlineRpc.importLegacyWorkout(requestId, buildJsonObject {
        put("schema_version", inventory.schemaVersion); put("kind", "WORKOUT")
        put("id", item.id); put("fingerprint", aggregate); put("mapping_version", 1)
    }, buildJsonObject {
        put("id", item.id); put("user_id", inventory.ownerId); put("template_id", JsonNull)
        put("template_name", "Legacy"); put("date", "2026-10-28")
        put("start_time", OnlineFixtures.time); put("end_time", OnlineFixtures.time)
        put("created_at", OnlineFixtures.time); put("updated_at", OnlineFixtures.time)
        put("total_volume", "0"); put("total_sets", 0); put("total_reps", 0); put("deleted_at", JsonNull)
    }, JsonArray(emptyList()), JsonArray(emptyList()))
    val payloadDigest = fingerprint("server-canonical-payload")
    val receipt = if (state != LegacyRecoveryState.CONFIRMED) null else buildJsonObject {
        put("request_id", requestId); put("operation", call.name); put("workout_id", item.id)
        put("source_fingerprint", aggregate); put("payload_digest", payloadDigest); put("outcome", "IMPORTED")
        put("id_mapping", JsonObject(mapping.mapValues { JsonPrimitive(it.value) }))
        put("exercise_count", 0); put("set_count", 0); put("committed_at", OnlineFixtures.time)
    }
    return LegacyRecoveryAction(
        actionId = UUID(0, 301).toString(), ownerId = inventory.ownerId,
        sourceSchemaVersion = inventory.schemaVersion, sourceKind = item.kind.name, sourceId = item.id,
        sourceFingerprint = item.fingerprint, aggregateFingerprint = aggregate,
        dependencyFingerprint = inventory.dependencyFingerprint(), operation = call.name,
        requestId = requestId, requestJson = call.parameters, bodyDigest = fingerprint(call.parameters.toString()),
        idMapping = mapping, reviewDecision = LegacyReviewDecision.IMPORT_SELECTED_CREATE, state = state,
        validatedReceipt = receipt, serverPayloadDigest = payloadDigest.takeIf { receipt != null },
    )
}
