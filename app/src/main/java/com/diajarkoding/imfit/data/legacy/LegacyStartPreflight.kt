package com.diajarkoding.imfit.data.legacy

import com.diajarkoding.imfit.data.remote.online.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.time.Clock

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
    private val recovery: LegacyRecoveryCoordinator,
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
        val covered = try { recovery.validatedCoverage(identity, local) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { reasons += LegacyStartReason.CHECKPOINT_UNAVAILABLE; emptySet() }
        val actions = try { recovery.pendingActions(identity) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { reasons += LegacyStartReason.CHECKPOINT_UNAVAILABLE; emptyList() }
        if (!reads.auth.isCurrent(identity)) throw StaleAuthException()
        if (local is LegacyInventory.Unknown) reasons += LegacyStartReason.LOCAL_UNAVAILABLE
        if (local is LegacyInventory.Present) {
            val historical = archived?.coveredItems(local).orEmpty()
            fun resolved(item: LegacyItem) = item in covered || item in historical
            if (local.schemaVersion != 9 && local.items.any { !resolved(it) }) {
                reasons += LegacyStartReason.SCHEMA_BRIDGE_REVIEW
            }
            if (local.items.any { it.disposition != LegacyDisposition.SYNCED_CACHE_CANDIDATE && !resolved(it) }) {
                reasons += LegacyStartReason.LEGACY_ITEMS_REVIEW
            }
        }
        var sessionId: String? = null
        val operations = linkedSetOf<String>()
        // Only dispatched unknown actions carry remote ambiguity. A definitively rejected
        // historical import may remain an owner-accessible archive after explicit review.
        operations += actions.filter { it.state == LegacyRecoveryState.SUBMITTED }.map { it.requestId }
        if (local is LegacyInventory.Present) {
            operations += local.items.filter { it !in covered }.mapNotNull { it.requestId }
        }
        try {
            val readiness = reads.readFor(identity, OnlineRpc.startRecoveryStatus())
            if (!readiness.canCreate || readiness.hasMoreBlockers || readiness.blockers.isNotEmpty()) {
                sessionId = readiness.blockers.firstOrNull()?.resourceId
                reasons += LegacyStartReason.REMOTE_SESSION_REVIEW
            }
            for (requestId in operations) {
                // Discovery is targeted evidence, never acknowledgement. Template lookup omits
                // the original payload; explicit exact replay remains the only confirmation.
                reads.readFor(identity, OnlineRpc.operationStatus(requestId))
            }
            if (operations.isNotEmpty()) reasons += LegacyStartReason.REMOTE_OPERATIONS_REVIEW
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { reasons += LegacyStartReason.REMOTE_UNAVAILABLE }
        currentCoroutineContext().ensureActive()
        if (!reads.auth.isCurrent(identity)) throw StaleAuthException()
        var report = LegacyStartReport(identity, decision(reasons), reasons, local, sessionId, operations.toList())
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

/** Re-inventories legacy evidence before registering the immutable create request. */
class LegacyGuardedSessionStart(private val preflight: LegacyStartPreflight, private val repository: OnlineRepository) {
    init { require(preflight.auth === repository.auth) }
    suspend fun create(call: RpcCall<SessionMutationReceipt>, onDispatch: () -> Unit = {}): GuardedLegacyStart {
        require(call.name == "create_active_session")
        val report = preflight.inspect()
        if (report.decision != LegacyStartDecision.READY) return GuardedLegacyStart.Blocked(report)
        if (!preflight.unchanged(report)) return GuardedLegacyStart.Blocked(report.copy(
            decision = LegacyStartDecision.UNKNOWN, reasons = setOf(LegacyStartReason.LOCAL_CHANGED)))
        return GuardedLegacyStart.Created(repository.execute(call, expectedIdentity = report.identity, onRegistered = onDispatch))
    }
}
