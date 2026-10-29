package com.diajarkoding.imfit.data.legacy

import com.diajarkoding.imfit.data.remote.TemplateWriteReceipt
import com.diajarkoding.imfit.data.remote.TemplateWriteRequest
import com.diajarkoding.imfit.data.remote.online.*
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

sealed interface LegacySelection {
    data class ImportWorkout(val displayedInventory: LegacyInventory.Present, val item: LegacyItem) : LegacySelection
    data class ReplayTemplateIntent(val displayedInventory: LegacyInventory.Present, val item: LegacyItem) : LegacySelection
    data class ArchiveServerSession(val item: LegacyRecoveryItem) : LegacySelection
}

data class LegacyRecoveryReview(
    val identity: AuthIdentity,
    val inventory: LegacyInventory,
    val actions: List<LegacyRecoveryAction>,
    val localResolutions: List<LegacyLocalArchiveResolution>,
    val readiness: StartRecoveryStatus?,
    val remoteCurrent: SessionLookup?,
    val failures: List<String>,
)

sealed interface LegacyRecoveryResult {
    val action: LegacyRecoveryAction
    data class Confirmed(override val action: LegacyRecoveryAction) : LegacyRecoveryResult
    data class RetryOriginalRequired(override val action: LegacyRecoveryAction) : LegacyRecoveryResult
    data class Unresolved(override val action: LegacyRecoveryAction) : LegacyRecoveryResult
    data class Conflict(override val action: LegacyRecoveryAction) : LegacyRecoveryResult
    data class Blocked(override val action: LegacyRecoveryAction) : LegacyRecoveryResult
}

/** Selected, explicit, owner-bound legacy transitions. No automatic import, retry, or source writes. */
class LegacyRecoveryCoordinator(
    private val reads: RemoteReadGateway,
    private val repository: OnlineRepository,
    private val inventory: LegacyInventoryReader,
    private val aggregates: LegacyAggregateReader,
    private val checkpoints: LegacyRecoveryCheckpointStore,
    private val changes: OnlineMutationChanges,
) {
    private val mutex = Mutex()
    private val auth get() = reads.auth
    private val json = Json { encodeDefaults = true; explicitNulls = true }

    suspend fun review(): LegacyRecoveryReview = mutex.withLock {
        val identity = auth.capture().identity
        val failures = mutableListOf<String>()
        val local = inventory.inspect(identity.ownerId)
        require(local.ownerId == identity.ownerId)
        val checkpoint = try { load(identity) } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failures += "CHECKPOINT_BLOCKED"; null }
        val readiness = try { reads.readFor(identity, OnlineRpc.startRecoveryStatus()) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failures += "READINESS_UNKNOWN"; null }
        // The generic lookup rejects legacy rows; target a visible versioned blocker instead.
        val versioned = readiness?.blockers?.firstOrNull { it.kind != RecoveryBlockerKind.LEGACY_SESSION }
        val legacyOnly = versioned == null && readiness?.blockers?.any { it.kind == RecoveryBlockerKind.LEGACY_SESSION } == true
        val remoteCurrent = if (legacyOnly) null else try {
            reads.readFor(identity, OnlineRpc.activeSession(versioned?.resourceId))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failures += "REMOTE_SESSION_UNKNOWN"; null }
        current(identity)
        LegacyRecoveryReview(identity, local, checkpoint?.actions.orEmpty(), checkpoint?.localResolutions.orEmpty(), readiness, remoteCurrent, failures)
    }

    /** Paging is an explicit screen action; readiness never downloads historical receipt/session pages. */
    suspend fun legacyPage(identity: AuthIdentity, cursor: String? = null, limit: Int = 20): RemotePage<LegacyRecoveryItem> {
        current(identity)
        return reads.readFor(identity, OnlineRpc.legacyRecoveryPage(cursor, limit)).also { page ->
            require(page.data.all { it.rawSource["user_id"]?.jsonPrimitive?.content == identity.ownerId })
            current(identity)
        }
    }

    suspend fun prepare(identity: AuthIdentity, selection: LegacySelection): LegacyRecoveryAction = mutex.withLock {
        current(identity)
        val checkpoint = load(identity)
        require(checkpoint.actions.none { it.state == LegacyRecoveryState.SUBMITTED }) { "Resolve the original submitted action first" }
        val action = when (selection) {
            is LegacySelection.ImportWorkout -> {
                require(selection.item.kind == LegacyKind.WORKOUT && selection.item.disposition == LegacyDisposition.PENDING_CREATE)
                val aggregate = selected(identity, selection.displayedInventory, selection.item)
                val mapped = LegacyImportMapper.map(aggregate)
                require(mapped.workout["user_id"]?.jsonPrimitive?.content == identity.ownerId)
                val call = OnlineRpc.importLegacyWorkout(newId(), mapped.source, mapped.workout, mapped.exercises, mapped.sets)
                action(identity, aggregate, call, mapped.idMapping, LegacyReviewDecision.IMPORT_SELECTED_CREATE)
            }
            is LegacySelection.ReplayTemplateIntent -> {
                require(selection.item.kind == LegacyKind.TEMPLATE_INTENT && selection.item.disposition != LegacyDisposition.PROBLEM)
                val aggregate = selected(identity, selection.displayedInventory, selection.item)
                require(aggregate.inventory.schemaVersion == 9)
                val row = aggregate.records.getValue(LegacyKind.TEMPLATE_INTENT).single()
                val request = json.decodeFromString<TemplateWriteRequest>(requireNotNull(row["payload"]))
                require(request.ownerId == identity.ownerId && request.requestId == selection.item.requestId)
                require(request.templateId == row["template_id"])
                action(identity, aggregate, OnlineRpc.saveTemplate(request), mapOf(request.templateId to request.templateId), LegacyReviewDecision.REPLAY_ORIGINAL_TEMPLATE)
            }
            is LegacySelection.ArchiveServerSession -> {
                val item = selection.item
                require(item.rawSource["user_id"]?.jsonPrimitive?.content == identity.ownerId)
                val status = reads.readFor(identity, OnlineRpc.startRecoveryStatus())
                // A selected page may include archived rows. For unresolved selection revalidate the
                // displayed fingerprint at dispatch using the server's CAS, including hidden blockers.
                require(item.resolution == null && (!status.canCreate || status.hasMoreBlockers))
                status.blockers.firstOrNull { it.resourceId == item.sessionId }?.let { blocker ->
                    require(blocker.kind == RecoveryBlockerKind.LEGACY_SESSION && blocker.sourceFingerprint == item.sourceFingerprint)
                }
                val call = OnlineRpc.resolveLegacySession(newId(), item.sessionId, item.sourceFingerprint)
                LegacyRecoveryAction(newId(), identity.ownerId, null, "SERVER_LEGACY_SESSION", item.sessionId,
                    item.sourceFingerprint, item.sourceFingerprint, fingerprint("server-dependencies"), call.name,
                    requireNotNull(call.mutation).requestId, call.parameters, fingerprint(call.parameters.toString()), emptyMap(),
                    LegacyReviewDecision.KEEP_SERVER_ARCHIVED, LegacyRecoveryState.PREPARED)
            }
        }
        val existing = checkpoint.actions.lastOrNull { it.sourceKind == action.sourceKind && it.sourceId == action.sourceId &&
            it.sourceFingerprint == action.sourceFingerprint && it.aggregateFingerprint == action.aggregateFingerprint &&
            it.dependencyFingerprint == action.dependencyFingerprint && it.reviewDecision == action.reviewDecision }
        if (existing != null) return@withLock existing
        current(identity)
        checkpoints.save(checkpoint.copy(actions = checkpoint.actions + action))
        current(identity)
        action
    }

    /** Explicit retry always dispatches the durable original. SUBMITTED is persisted before transport. */
    suspend fun submit(identity: AuthIdentity, actionId: String): LegacyRecoveryResult = mutex.withLock {
        current(identity)
        val checkpoint = load(identity)
        val action = checkpoint.actions.single { it.actionId == actionId }
        require(checkpoint.actions.none { it.actionId != actionId && it.state == LegacyRecoveryState.SUBMITTED })
        when (action.state) {
            LegacyRecoveryState.CONFIRMED -> return@withLock LegacyRecoveryResult.Confirmed(action)
            LegacyRecoveryState.CONFLICT -> return@withLock LegacyRecoveryResult.Conflict(action)
            LegacyRecoveryState.BLOCKED, LegacyRecoveryState.AWAITING_REVIEW -> return@withLock LegacyRecoveryResult.Blocked(action)
            else -> Unit
        }
        if (action.state == LegacyRecoveryState.PREPARED && !sourceUnchanged(identity, action)) {
            val blocked = action.copy(state = LegacyRecoveryState.BLOCKED, failure = "LEGACY_SOURCE_CHANGED")
            persist(identity, checkpoint, blocked)
            return@withLock LegacyRecoveryResult.Blocked(blocked)
        }
        // A persisted ambiguous action must first be looked up, not silently retried by submit.
        if (action.state == LegacyRecoveryState.SUBMITTED) return@withLock resolveLocked(identity, checkpoint, action, retryIfAbsent = true)
        dispatch(identity, checkpoint, action)
    }

    /** Targeted status then exact original replay; never invokes the runtime ledger's resolve bypass. */
    suspend fun resolve(identity: AuthIdentity, actionId: String): LegacyRecoveryResult = mutex.withLock {
        current(identity)
        val checkpoint = load(identity)
        val action = checkpoint.actions.single { it.actionId == actionId }
        resolveLocked(identity, checkpoint, action, retryIfAbsent = false)
    }

    suspend fun abandonLocalActive(identity: AuthIdentity, displayedInventory: LegacyInventory.Present, item: LegacyItem): LegacyLocalArchiveResolution = mutex.withLock {
        current(identity)
        require(item.kind == LegacyKind.ACTIVE_SESSION && item.disposition != LegacyDisposition.PROBLEM)
        val checkpoint = load(identity)
        require(checkpoint.actions.none { it.state == LegacyRecoveryState.SUBMITTED })
        val aggregate = selected(identity, displayedInventory, item)
        val resolution = LegacyLocalArchiveResolution(identity.ownerId, aggregate.inventory.schemaVersion, item.id,
            item.fingerprint, aggregate.aggregateFingerprint, aggregate.dependencyFingerprint)
        if (resolution !in checkpoint.localResolutions) {
            current(identity)
            checkpoints.save(checkpoint.copy(localResolutions = checkpoint.localResolutions + resolution))
        }
        current(identity)
        resolution
    }

    suspend fun pendingActions(identity: AuthIdentity): List<LegacyRecoveryAction> = mutex.withLock {
        load(identity).actions.filter { it.state != LegacyRecoveryState.CONFIRMED }
    }

    /** One fresh caller inventory; pure fingerprint checks, never one database scan per action. */
    suspend fun validatedCoverage(identity: AuthIdentity, inventory: LegacyInventory): Set<LegacyItem> = mutex.withLock {
        current(identity)
        require(inventory.ownerId == identity.ownerId)
        val checkpoint = load(identity)
        if (inventory !is LegacyInventory.Present) return@withLock emptySet()
        val dependency = inventory.dependencyFingerprint()
        val covered = mutableSetOf<LegacyItem>()
        checkpoint.actions.filter { it.state == LegacyRecoveryState.CONFIRMED && it.sourceSchemaVersion == inventory.schemaVersion }.forEach { action ->
            val item = inventory.items.singleOrNull { it.kind.name == action.sourceKind && it.id == action.sourceId } ?: return@forEach
            if (item.fingerprint == action.sourceFingerprint && inventory.aggregateFingerprint(item) == action.aggregateFingerprint && dependency == action.dependencyFingerprint) {
                // Imported aggregate covers its selected descendants, not unrelated owner records.
                covered += descendants(inventory, item)
            }
        }
        checkpoint.localResolutions.filter { it.sourceSchemaVersion == inventory.schemaVersion && it.dependencyFingerprint == dependency }.forEach { resolution ->
            inventory.items.singleOrNull { it.kind == LegacyKind.ACTIVE_SESSION && it.id == resolution.sourceId && it.fingerprint == resolution.sourceFingerprint }
                ?.takeIf { inventory.aggregateFingerprint(it) == resolution.aggregateFingerprint }?.let(covered::add)
        }
        current(identity)
        covered
    }

    private suspend fun resolveLocked(identity: AuthIdentity, checkpoint: LegacyRecoveryCheckpoint, action: LegacyRecoveryAction, retryIfAbsent: Boolean): LegacyRecoveryResult {
        if (action.state in setOf(LegacyRecoveryState.BLOCKED, LegacyRecoveryState.CONFLICT, LegacyRecoveryState.AWAITING_REVIEW)) {
            return if (action.state == LegacyRecoveryState.CONFLICT) LegacyRecoveryResult.Conflict(action) else LegacyRecoveryResult.Blocked(action)
        }
        val lookup = try { reads.readFor(identity, OnlineRpc.operationStatus(action.requestId)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { current(identity); return LegacyRecoveryResult.Unresolved(action) }
        if (lookup.state == OperationState.NOT_FOUND) {
            // Expired receipt retention is not evidence that an already-confirmed operation vanished.
            if (action.state == LegacyRecoveryState.CONFIRMED) return LegacyRecoveryResult.Confirmed(action)
            if (retryIfAbsent && action.state == LegacyRecoveryState.PREPARED && !sourceUnchanged(identity, action)) {
                val blocked = action.copy(state = LegacyRecoveryState.BLOCKED, failure = "LEGACY_SOURCE_CHANGED")
                persist(identity, checkpoint, blocked)
                return LegacyRecoveryResult.Blocked(blocked)
            }
            return if (retryIfAbsent) dispatch(identity, checkpoint, action) else LegacyRecoveryResult.RetryOriginalRequired(action)
        }
        val envelope = requireNotNull(lookup.receipt)
        val original = original(action)
        val mutation = requireNotNull(original.mutation)
        if (envelope.operation != action.operation || envelope.resourceType != mutation.resourceType || envelope.resourceId != mutation.resourceId) {
            return LegacyRecoveryResult.Unresolved(action)
        }
        try { validateReceipt(action, envelope.result) }
        catch (_: Exception) { return LegacyRecoveryResult.Unresolved(action) }
        // COMMITTED lookup lacks original payload proof. Replay the *same* immutable request.
        return dispatch(identity, checkpoint, action, envelope.result)
    }

    private suspend fun dispatch(identity: AuthIdentity, checkpoint: LegacyRecoveryCheckpoint, action: LegacyRecoveryAction, lookupResult: JsonObject? = null): LegacyRecoveryResult {
        val call = original(action)
        val submitted = if (action.state == LegacyRecoveryState.CONFIRMED) action else action.copy(state = LegacyRecoveryState.SUBMITTED, failure = null)
        persist(identity, checkpoint, submitted)
        val receipt = try { repository.execute(call, identity) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: OnlineFailure) {
            current(identity)
            if (failure.reason == RemoteError.UnknownOutcome || failure.reason == RemoteError.Network || failure.reason == RemoteError.UnresolvedMutation) return LegacyRecoveryResult.Unresolved(submitted)
            val conflicted = submitted.copy(state = if (failure.reason is RemoteError.Conflict) LegacyRecoveryState.CONFLICT else LegacyRecoveryState.BLOCKED,
                failure = when (val reason = failure.reason) { is RemoteError.Conflict -> reason.discriminator; else -> "REMOTE_REVIEW_REQUIRED" })
            if (action.state == LegacyRecoveryState.CONFIRMED) return LegacyRecoveryResult.Unresolved(action)
            persist(identity, checkpoint, conflicted)
            return if (conflicted.state == LegacyRecoveryState.CONFLICT) LegacyRecoveryResult.Conflict(conflicted) else LegacyRecoveryResult.Blocked(conflicted)
        }
        current(identity)
        try {
            validateReceipt(action, receipt)
            require(lookupResult == null || lookupResult == receipt)
        } catch (_: Exception) { return LegacyRecoveryResult.Unresolved(submitted) }
        val confirmed = submitted.copy(state = LegacyRecoveryState.CONFIRMED, validatedReceipt = receipt,
            serverPayloadDigest = receipt["payload_digest"]?.jsonPrimitive?.content, failure = null)
        persist(identity, checkpoint, confirmed)
        if (action.operation == "import_legacy_workout") {
            changes.publish(WorkoutCommitted(identity, action.requestId, action.resourceId,
                action.requestJson.getValue("p_workout").jsonObject.getValue("start_time").jsonPrimitive.content))
        }
        current(identity)
        return LegacyRecoveryResult.Confirmed(confirmed)
    }

    private suspend fun selected(identity: AuthIdentity, displayed: LegacyInventory.Present, item: LegacyItem): LegacyAggregate {
        require(displayed.ownerId == identity.ownerId && item in displayed.items)
        current(identity)
        val aggregate = aggregates.read(identity.ownerId, item, displayed.evidence())
        current(identity)
        require(aggregate.inventory.ownerId == identity.ownerId && aggregate.inventory.evidence() == displayed.evidence())
        require(aggregate.item == item && aggregate.aggregateFingerprint == aggregate.inventory.aggregateFingerprint(item))
        require(aggregate.dependencyFingerprint == aggregate.inventory.dependencyFingerprint())
        return aggregate
    }

    private fun <T> action(identity: AuthIdentity, aggregate: LegacyAggregate, call: RpcCall<T>, mapping: Map<String, String>, decision: LegacyReviewDecision) =
        LegacyRecoveryAction(newId(), identity.ownerId, aggregate.inventory.schemaVersion, aggregate.item.kind.name, aggregate.item.id,
            aggregate.item.fingerprint, aggregate.aggregateFingerprint, aggregate.dependencyFingerprint, call.name,
            requireNotNull(call.mutation).requestId, call.parameters, fingerprint(call.parameters.toString()), mapping,
            decision, LegacyRecoveryState.PREPARED)

    private suspend fun sourceUnchanged(identity: AuthIdentity, action: LegacyRecoveryAction): Boolean {
        if (action.sourceSchemaVersion == null) return true // Server fingerprint CAS is authoritative.
        val fresh = inventory.inspect(identity.ownerId)
        current(identity)
        if (fresh !is LegacyInventory.Present || fresh.schemaVersion != action.sourceSchemaVersion) return false
        val item = fresh.items.singleOrNull { it.kind.name == action.sourceKind && it.id == action.sourceId } ?: return false
        return item.fingerprint == action.sourceFingerprint && fresh.aggregateFingerprint(item) == action.aggregateFingerprint && fresh.dependencyFingerprint() == action.dependencyFingerprint
    }

    private fun original(action: LegacyRecoveryAction): RpcCall<JsonObject> {
        require(action.bodyDigest == fingerprint(action.requestJson.toString()))
        val body = action.requestJson
        val typed = when (action.operation) {
            "import_legacy_workout" -> asJson(OnlineRpc.importLegacyWorkout(action.requestId, body.getValue("p_source").jsonObject,
                body.getValue("p_workout").jsonObject, body.getValue("p_exercises").jsonArray, body.getValue("p_sets").jsonArray))
            "save_template_revision" -> {
                val request = json.decodeFromJsonElement<TemplateWriteRequest>(body)
                require(request.ownerId == action.ownerId)
                asJson(OnlineRpc.saveTemplate(request)) { receipt ->
                    require(receipt.exerciseCount == request.exercises.size)
                    java.time.OffsetDateTime.parse(receipt.serverUpdatedAt)
                }
            }
            "resolve_legacy_session" -> asJson(OnlineRpc.resolveLegacySession(action.requestId,
                body.getValue("p_session_id").jsonPrimitive.content, body.getValue("p_expected_source_fingerprint").jsonPrimitive.content,
                body.getValue("p_resolution").jsonPrimitive.content))
            else -> error("Unsupported legacy operation")
        }
        require(typed.parameters == body && typed.mutation?.requestId == action.requestId)
        require(typed.mutation?.resourceId == action.resourceId && typed.mutation?.resourceType == action.resourceType)
        when (action.operation) {
            "import_legacy_workout" -> {
                val source = body.getValue("p_source").jsonObject
                require(source.getValue("schema_version").jsonPrimitive.int == action.sourceSchemaVersion)
                require(source.getValue("id").jsonPrimitive.content == UUID.fromString(action.sourceId).toString())
                require(source.getValue("fingerprint").jsonPrimitive.content == action.aggregateFingerprint)
                require(body.getValue("p_workout").jsonObject.getValue("user_id").jsonPrimitive.content == action.ownerId)
                val ids = listOf(action.resourceId) + body.getValue("p_exercises").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content } +
                    body.getValue("p_sets").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content }
                require(action.idMapping == ids.associateWith { it })
            }
            "save_template_revision" -> {
                val templateId = body.getValue("p_template_id").jsonPrimitive.content
                require(action.idMapping == mapOf(templateId to templateId))
            }
            "resolve_legacy_session" -> require(body.getValue("p_expected_source_fingerprint").jsonPrimitive.content == action.sourceFingerprint &&
                action.resourceId == action.sourceId && action.aggregateFingerprint == action.sourceFingerprint && action.idMapping.isEmpty())
        }
        return typed
    }

    private fun <T> asJson(call: RpcCall<T>, extraValidation: (T) -> Unit = {}): RpcCall<JsonObject> =
        RpcCall(call.name, call.parameters, JsonObject.serializer(), call.mutation) { result ->
            val decoded = onlineJson.decodeFromJsonElement(call.serializer, result)
            call.validate(decoded)
            extraValidation(decoded)
        }

    private fun validateReceipt(action: LegacyRecoveryAction, receipt: JsonObject) {
        original(action).validate(receipt)
        require(receipt["request_id"]?.jsonPrimitive?.content == action.requestId)
        if (action.validatedReceipt != null) require(action.validatedReceipt == receipt)
        when (action.operation) {
            "import_legacy_workout" -> {
                val value = onlineJson.decodeFromJsonElement<LegacyWorkoutImportReceipt>(receipt)
                require(value.sourceFingerprint == action.aggregateFingerprint)
                require(value.idMapping == JsonObject(action.idMapping.mapValues { JsonPrimitive(it.value) }))
                require(action.serverPayloadDigest == null || action.serverPayloadDigest == value.payloadDigest)
                require(action.requestJson.getValue("p_workout").jsonObject["user_id"]?.jsonPrimitive?.content == action.ownerId)
            }
            "save_template_revision" -> {
                val value = onlineJson.decodeFromJsonElement<TemplateWriteReceipt>(receipt)
                require(action.idMapping == mapOf(value.templateId to value.templateId))
            }
            "resolve_legacy_session" -> require(onlineJson.decodeFromJsonElement<LegacySessionResolutionReceipt>(receipt).sourceFingerprint == action.sourceFingerprint)
        }
    }

    private suspend fun load(identity: AuthIdentity): LegacyRecoveryCheckpoint {
        current(identity)
        val checkpoint = checkpoints.load(identity.ownerId) ?: LegacyRecoveryCheckpoint(identity.ownerId)
        checkpoint.validate(identity.ownerId)
        checkpoint.actions.forEach { action ->
            original(action)
            action.validatedReceipt?.let { validateReceipt(action, it) }
        }
        current(identity)
        return checkpoint
    }

    private suspend fun persist(identity: AuthIdentity, checkpoint: LegacyRecoveryCheckpoint, action: LegacyRecoveryAction) {
        current(identity)
        checkpoints.save(checkpoint.copy(actions = checkpoint.actions.map { if (it.actionId == action.actionId) action else it }))
        current(identity)
    }

    private fun current(identity: AuthIdentity) { if (!auth.isCurrent(identity) || !repository.auth.isCurrent(identity)) throw StaleAuthException() }
    private fun newId(): String = UUID.randomUUID().toString()
    private fun descendants(inventory: LegacyInventory.Present, item: LegacyItem): Set<LegacyItem> {
        if (item.kind != LegacyKind.WORKOUT) return setOf(item)
        val exercises = inventory.items.filter { it.kind == LegacyKind.EXERCISE_LOG && it.parentId == item.id }
        return (listOf(item) + exercises + inventory.items.filter { it.kind == LegacyKind.SET && (it.parentId == item.id || exercises.any { exercise -> exercise.id == it.parentId }) }).toSet()
    }
}
