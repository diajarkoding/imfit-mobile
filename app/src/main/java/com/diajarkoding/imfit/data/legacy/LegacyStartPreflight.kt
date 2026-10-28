package com.diajarkoding.imfit.data.legacy

import com.diajarkoding.imfit.data.remote.online.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Clock
import java.time.Duration

enum class LegacyStartReason {
    LOCAL_UNAVAILABLE, SCHEMA_BRIDGE_REVIEW, LEGACY_ITEMS_REVIEW, REMOTE_SESSION_REVIEW,
    REMOTE_OPERATIONS_REVIEW, REMOTE_UNAVAILABLE, CHECKPOINT_UNAVAILABLE, LOCAL_CHANGED,
}
enum class LegacyStartDecision { READY, RECOVERY_REQUIRED, UNKNOWN }
data class LegacyStartReport(
    val identity: AuthIdentity, val decision: LegacyStartDecision, val reasons: Set<LegacyStartReason>,
    val inventory: LegacyInventory, val remoteSessionId: String? = null,
    val operationIds: List<String> = emptyList(),
)

/** No cached Ready is reused. Reads/discovery only, then durable evidence; no automatic recovery. */
class LegacyStartPreflight(
    private val reads: RemoteReadGateway,
    private val inventory: LegacyInventoryReader,
    private val checkpoints: LegacyCheckpointStore,
    private val clock: Clock = Clock.systemUTC(),
    private val archive: LegacyArchiveStore = NoLegacyArchive,
) {
    internal val auth get() = reads.auth
    suspend fun inspect(): LegacyStartReport {
        val identity = reads.auth.capture().identity
        val inspected = try { inventory.inspect(identity.ownerId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { LegacyInventory.Unknown(identity.ownerId, LegacyReadFailure.UNAVAILABLE) }
        currentCoroutineContext().ensureActive()
        if (!reads.auth.isCurrent(identity)) throw StaleAuthException()
        val local = inspected.takeIf { it.ownerId == identity.ownerId }
            ?: LegacyInventory.Unknown(identity.ownerId, LegacyReadFailure.INTEGRITY)
        val reasons = linkedSetOf<LegacyStartReason>()
        val archived = try {
            archive.load(identity.ownerId)?.also { require(it.ownerId == identity.ownerId && it.formatVersion == 1) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { reasons += LegacyStartReason.CHECKPOINT_UNAVAILABLE; null }
        if (!reads.auth.isCurrent(identity)) throw StaleAuthException()
        if (local is LegacyInventory.Unknown) reasons += LegacyStartReason.LOCAL_UNAVAILABLE
        if (local is LegacyInventory.Present) {
            if (local.schemaVersion != 9 && (archived?.schemaVersion != local.schemaVersion || local.items.any { !archived.covers(it) })) reasons += LegacyStartReason.SCHEMA_BRIDGE_REVIEW
            if (local.items.any { it.disposition != LegacyDisposition.SYNCED_CACHE_CANDIDATE && archived?.covers(it) != true }) reasons += LegacyStartReason.LEGACY_ITEMS_REVIEW
        }
        var sessionId: String? = null
        val operations = mutableListOf<String>()
        try {
            val session = reads.readFor(identity, OnlineRpc.activeSession())
            if (session.found) {
                sessionId = session.session!!.id
                reasons += LegacyStartReason.REMOTE_SESSION_REVIEW
            }
            // A bounded, complete recent-operation window is NOT assumed when has_more=true.
            val until = clock.instant()
            val recent = reads.readFor(identity, OnlineRpc.recentOperations(until.minus(Duration.ofDays(90)).toString(), until.toString(), limit = 100))
            if (recent.pageInfo.hasMore || recent.pageInfo.nextCursor != null || recent.pageInfo.limit != 100) {
                reasons += LegacyStartReason.REMOTE_UNAVAILABLE
            }
            val sessionOperations = recent.data.filter { it.resourceType == "SESSION" }
            for ((session, receipts) in sessionOperations.groupBy { it.resourceId }) {
                // A historical update receipt is not itself an active session. Validate the tagged
                // receipt then consult canonical state; never treat arbitrary result JSON as proof.
                val valid = receipts.all { operation ->
                    val receipt = runCatching { onlineJson.decodeFromJsonElement(SessionMutationReceipt.serializer(), operation.result) }.getOrNull()
                    receipt != null && receipt.requestId == operation.requestId && receipt.operation == operation.operation &&
                        receipt.sessionId == session && receipt.snapshot.id == session && receipt.version == receipt.snapshot.version &&
                        receipt.status == receipt.snapshot.status && receipt.snapshot.schemaVersion == 1 && receipt.version >= 1 &&
                        receipt.operation in setOf("create_active_session", "update_active_session", "finish_active_session", "cancel_active_session")
                }
                if (!valid) { operations += receipts.map { it.requestId }; continue }
                val canonical = reads.readFor(identity, OnlineRpc.activeSession(session)).session
                val terminal = canonical != null && !canonical.reviewRequired && canonical.status == canonical.effectiveStatus &&
                    (canonical.status == SessionStatus.CANCELLED || (canonical.status == SessionStatus.COMPLETED && canonical.completedWorkoutId != null)) &&
                    canonical.version >= receipts.maxOf { onlineJson.decodeFromJsonElement(SessionMutationReceipt.serializer(), it.result).version }
                if (!terminal) operations += receipts.map { it.requestId }
            }
            if (operations.isNotEmpty()) reasons += LegacyStartReason.REMOTE_OPERATIONS_REVIEW
            if (local is LegacyInventory.Present) local.items.mapNotNull { it.requestId }.distinct().forEach { requestId ->
                // Even COMMITTED does not acknowledge/clean an old intent without payload/receipt review.
                reads.readFor(identity, OnlineRpc.operationStatus(requestId))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            val error = (failure as? OnlineFailure)?.reason
            if (error is RemoteError.Conflict && error.discriminator in setOf("LEGACY_REVIEW_REQUIRED", "SESSION_REVIEW_REQUIRED")) {
                reasons += LegacyStartReason.REMOTE_SESSION_REVIEW
            } else reasons += LegacyStartReason.REMOTE_UNAVAILABLE
        }
        currentCoroutineContext().ensureActive()
        if (!reads.auth.isCurrent(identity)) throw StaleAuthException()
        var report = LegacyStartReport(identity, decision(reasons), reasons, local, sessionId, operations)
        try {
            // Stored Ready is audit evidence only; the next call always inventories again.
            checkpoints.load(identity.ownerId)?.let {
                require(it.ownerId == identity.ownerId && it.formatVersion == 1 && it.epoch > 0 && it.itemCount >= 0)
                require(it.evidence.matches(Regex("[0-9a-f]{64}")))
                LegacyStartDecision.valueOf(it.outcome)
                java.time.Instant.parse(it.checkedAt)
            } // Corrupt/foreign checkpoint is fail-closed, not ignored.
            checkpoints.record(LegacyCheckpoint(identity.ownerId, identity.epoch, local.evidence(),
                (local as? LegacyInventory.Present)?.schemaVersion, report.decision.name,
                reasons.map { it.name }, (local as? LegacyInventory.Present)?.items?.size ?: 0, clock.instant().toString()))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            report = report.copy(decision = LegacyStartDecision.UNKNOWN, reasons = reasons + LegacyStartReason.CHECKPOINT_UNAVAILABLE)
        }
        if (!reads.auth.isCurrent(identity)) throw StaleAuthException()
        return report
    }

    internal suspend fun unchanged(report: LegacyStartReport): Boolean {
        if (!reads.auth.isCurrent(report.identity)) throw StaleAuthException()
        val current = try { inventory.inspect(report.identity.ownerId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { return false }
        currentCoroutineContext().ensureActive()
        if (!reads.auth.isCurrent(report.identity)) throw StaleAuthException()
        return current !is LegacyInventory.Unknown && current.ownerId == report.identity.ownerId && current.evidence() == report.inventory.evidence()
    }

    private fun decision(reasons: Set<LegacyStartReason>) = when {
        reasons.any { it in setOf(LegacyStartReason.LOCAL_UNAVAILABLE, LegacyStartReason.REMOTE_UNAVAILABLE, LegacyStartReason.CHECKPOINT_UNAVAILABLE) } -> LegacyStartDecision.UNKNOWN
        reasons.isNotEmpty() -> LegacyStartDecision.RECOVERY_REQUIRED
        else -> LegacyStartDecision.READY
    }
}

sealed interface GuardedLegacyStart {
    data class Blocked(val report: LegacyStartReport) : GuardedLegacyStart
    data class Created(val receipt: SessionMutationReceipt) : GuardedLegacyStart
}

/** F4 integration seam only; deliberately NOT bound to the existing local session manager yet. */
class LegacyGuardedSessionStart(private val preflight: LegacyStartPreflight, private val repository: OnlineRepository) {
    init { require(preflight.auth === repository.auth) }
    suspend fun create(call: RpcCall<SessionMutationReceipt>, onDispatch: () -> Unit = {}): GuardedLegacyStart {
        require(call.name == "create_active_session")
        val report = preflight.inspect()
        if (report.decision != LegacyStartDecision.READY) return GuardedLegacyStart.Blocked(report)
        if (!preflight.unchanged(report)) return GuardedLegacyStart.Blocked(report.copy(
            decision = LegacyStartDecision.UNKNOWN, reasons = setOf(LegacyStartReason.LOCAL_CHANGED)))
        onDispatch()
        return GuardedLegacyStart.Created(repository.execute(call, expectedIdentity = report.identity))
    }
}
