package com.diajarkoding.imfit.data.remote.online

import com.diajarkoding.imfit.data.remote.TemplateWriteRequest
import com.diajarkoding.imfit.data.remote.TemplateWriteReceipt
import com.diajarkoding.imfit.data.remote.templateWriteExercises
import com.diajarkoding.imfit.domain.model.TemplateExercise
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

fun interface LegacyTemplateGuard { suspend fun check(owner: String, templateId: String?) }
data class TemplateChange(val identity: AuthIdentity, val templateId: String)

/** Minimum G3 interop: CAS comes from the displayed snapshot, never an imported Room revision. */
@Singleton
class TemplateEditBridge @Inject constructor(private val reads: RemoteReadGateway, private val legacy: LegacyTemplateGuard) {
    private val mutex = Mutex()
    private val intents = mutableMapOf<String, TemplateWriteRequest>()
    private var intentIdentity: AuthIdentity? = null
    private val mutableChanges = MutableSharedFlow<TemplateChange>(extraBufferCapacity = 8)
    val changes = mutableChanges.asSharedFlow()
    private val ownerListener = reads.auth.onIdentityChanged { intents.clear(); intentIdentity = null }

    suspend fun save(source: TemplateDetail?, name: String, exercises: List<TemplateExercise>, deleted: Boolean = false): TemplateWriteReceipt = mutex.withLock {
        val identity = reads.auth.capture().identity
        legacy.check(identity.ownerId, source?.id)
        if (!reads.auth.isCurrent(identity)) throw StaleAuthException()
        val children = templateWriteExercises(exercises)
        val key = source?.id ?: "create:${name.trim()}"
        val request = reads.auth.withCurrent(identity) {
            if (intentIdentity != identity) { intents.clear(); intentIdentity = identity }
            val old = intents[key]
            if (old != null && (!old.sameIntent(name.trim(), children, deleted) || old.expectedRevision != source?.revision)) {
                throw OnlineFailure(RemoteError.IdempotencyMismatch)
            }
            if (intents.any { it.key != key }) throw OnlineFailure(RemoteError.UnresolvedMutation)
            old ?: TemplateWriteRequest(UUID.randomUUID().toString(), source?.id ?: UUID.randomUUID().toString(),
                source?.revision, name.trim(), children,
                if (deleted) emptyList() else source?.exercises.orEmpty().map { it.exerciseId }.filterNot { id -> children.any { it.exerciseId == id } },
                deleted, identity.ownerId).also { intents[key] = it }
        }
        val receipt = try { reads.saveTemplate(request) }
        catch (failure: OnlineFailure) {
            if (failure.reason != RemoteError.UnknownOutcome && reads.auth.isCurrent(identity)) reads.auth.withCurrent(identity) { intents.remove(key) }
            throw failure
        }
        if (!reads.auth.isCurrent(identity)) throw StaleAuthException()
        if (receipt.requestId != request.requestId || receipt.templateId != request.templateId || receipt.exerciseCount != children.size ||
            receipt.revision <= (request.expectedRevision ?: -1)) throw OnlineFailure(RemoteError.UnknownOutcome)
        reads.auth.withCurrent(identity) { intents.remove(key) }
        mutableChanges.emit(TemplateChange(identity, receipt.templateId))
        receipt
    }
}
