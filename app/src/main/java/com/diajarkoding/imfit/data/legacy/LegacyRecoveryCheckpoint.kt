package com.diajarkoding.imfit.data.legacy

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
enum class LegacyRecoveryState { AWAITING_REVIEW, PREPARED, SUBMITTED, CONFIRMED, CONFLICT, BLOCKED }

@Serializable
enum class LegacyReviewDecision { IMPORT_SELECTED_CREATE, REPLAY_ORIGINAL_TEMPLATE, KEEP_SERVER_ARCHIVED }

/** A durable legacy transition, never a runtime offline-write queue. Request fields are immutable. */
@Serializable
data class LegacyRecoveryAction(
    val actionId: String,
    val ownerId: String,
    val sourceSchemaVersion: Int?,
    val sourceKind: String,
    val sourceId: String,
    val sourceFingerprint: String,
    val aggregateFingerprint: String,
    val dependencyFingerprint: String,
    val operation: String,
    val requestId: String,
    val requestJson: JsonObject,
    val bodyDigest: String,
    val idMapping: Map<String, String>,
    val reviewDecision: LegacyReviewDecision,
    val state: LegacyRecoveryState,
    val validatedReceipt: JsonObject? = null,
    val serverPayloadDigest: String? = null,
    val failure: String? = null,
) {
    val resourceType: String get() = when (operation) {
        "import_legacy_workout" -> "WORKOUT"
        "save_template_revision" -> "TEMPLATE"
        "resolve_legacy_session" -> "SESSION"
        else -> error("Unsupported legacy operation")
    }
    val resourceId: String get() = when (operation) {
        "save_template_revision" -> requestJson.getValue("p_template_id").jsonPrimitive.content
        "import_legacy_workout" -> requestJson.getValue("p_workout").jsonObject.getValue("id").jsonPrimitive.content
        "resolve_legacy_session" -> requestJson.getValue("p_session_id").jsonPrimitive.content
        else -> error("Unsupported legacy operation")
    }
}

/** Explicit non-resumable archive consent. This is NOT a network receipt or synced state. */
@Serializable
data class LegacyLocalArchiveResolution(
    val ownerId: String,
    val sourceSchemaVersion: Int,
    val sourceId: String,
    val sourceFingerprint: String,
    val aggregateFingerprint: String,
    val dependencyFingerprint: String,
    val decision: String = "PRESERVE_NON_RESUMABLE_ARCHIVE",
)

@Serializable
data class LegacyRecoveryCheckpoint(
    val ownerId: String,
    val actions: List<LegacyRecoveryAction> = emptyList(),
    val localResolutions: List<LegacyLocalArchiveResolution> = emptyList(),
    val formatVersion: Int = 1,
)

/** Implementations must reject damaged/foreign state rather than replacing it with an empty file. */
interface LegacyRecoveryCheckpointStore {
    suspend fun load(ownerId: String): LegacyRecoveryCheckpoint?
    suspend fun save(checkpoint: LegacyRecoveryCheckpoint)
}

class LegacyRecoveryCheckpointException : IllegalStateException("Legacy recovery checkpoint requires review")

private val recoveryDigest = Regex("[0-9a-f]{64}")
private val recoverySchemas = setOf(6, 7, 9)

internal fun LegacyRecoveryCheckpoint.validate(expectedOwner: String) {
    require(ownerId.isNotBlank() && ownerId == expectedOwner && formatVersion == 1)
    require(actions.size + localResolutions.size <= 10_000)
    require(actions.map { it.actionId }.distinct().size == actions.size)
    require(actions.map { it.requestId }.distinct().size == actions.size)
    actions.forEach {
        require(it.ownerId == ownerId && it.sourceId.isNotBlank())
        java.util.UUID.fromString(it.actionId); java.util.UUID.fromString(it.requestId)
        require(it.sourceSchemaVersion == null || it.sourceSchemaVersion in recoverySchemas)
        listOf(it.sourceFingerprint, it.aggregateFingerprint, it.dependencyFingerprint, it.bodyDigest).forEach { hash ->
            require(hash.matches(recoveryDigest))
        }
        require(it.bodyDigest == fingerprint(it.requestJson.toString()))
        require(it.requestJson["p_request_id"]?.jsonPrimitive?.let { value -> value.isString && value.content == it.requestId } == true)
        require((it.state == LegacyRecoveryState.CONFIRMED) == (it.validatedReceipt != null))
        require(it.serverPayloadDigest == null || it.serverPayloadDigest.matches(recoveryDigest))
        require((it.operation == "import_legacy_workout" && it.state == LegacyRecoveryState.CONFIRMED) == (it.serverPayloadDigest != null))
        require(it.idMapping.all { (source, remote) -> source == remote })
        when (it.reviewDecision) {
            LegacyReviewDecision.IMPORT_SELECTED_CREATE -> require(it.operation == "import_legacy_workout" && it.sourceKind == "WORKOUT" && it.sourceSchemaVersion != null)
            LegacyReviewDecision.REPLAY_ORIGINAL_TEMPLATE -> require(it.operation == "save_template_revision" && it.sourceKind == "TEMPLATE_INTENT" && it.sourceSchemaVersion == 9)
            LegacyReviewDecision.KEEP_SERVER_ARCHIVED -> require(it.operation == "resolve_legacy_session" && it.sourceKind == "SERVER_LEGACY_SESSION" && it.sourceSchemaVersion == null)
        }
    }
    require(localResolutions.distinct().size == localResolutions.size)
    localResolutions.forEach {
        require(it.ownerId == ownerId && it.sourceSchemaVersion in recoverySchemas && it.sourceId.isNotBlank())
        require(it.decision == "PRESERVE_NON_RESUMABLE_ARCHIVE")
        listOf(it.sourceFingerprint, it.aggregateFingerprint, it.dependencyFingerprint).forEach { hash -> require(hash.matches(recoveryDigest)) }
    }
}
