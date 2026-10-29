package com.diajarkoding.imfit.data.legacy

import android.util.AtomicFile
import com.diajarkoding.imfit.data.remote.online.AuthIdentity
import com.diajarkoding.imfit.data.remote.online.OnlineAuth
import com.diajarkoding.imfit.data.remote.online.StaleAuthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class LegacyArchiveEntry(val kind: String, val id: String, val fingerprint: String)

/** Acknowledges keeping historical data in recovery, NOT upload/sync/cleanup or active draft discard.
 * Matching is per immutable source item; restored/changed rows require a new review. Original data
 * stays in its original file and is listed from a fresh owner inventory whenever recovery is opened.
 */
@Serializable
data class LegacyArchiveReview(
    val ownerId: String, val schemaVersion: Int, val entries: List<LegacyArchiveEntry>,
    val formatVersion: Int = 1, val evidence: String? = null,
) {
    fun coveredItems(inventory: LegacyInventory.Present): Set<LegacyItem> {
        if (ownerId != inventory.ownerId || schemaVersion != inventory.schemaVersion ||
            evidence != inventory.evidence()) return emptySet()
        val recorded = entries.associate { "${it.kind}:${it.id}" to it.fingerprint }
        return inventory.items.filterTo(linkedSetOf()) {
            it.kind in historicalKinds && recorded["${it.kind}:${it.id}"] == it.fingerprint
        }
    }
    companion object {
        val historicalKinds = setOf(LegacyKind.PROFILE, LegacyKind.TEMPLATE, LegacyKind.TEMPLATE_EXERCISE,
            LegacyKind.WORKOUT, LegacyKind.EXERCISE_LOG, LegacyKind.SET)
    }
}

interface LegacyArchiveStore {
    suspend fun load(ownerId: String): LegacyArchiveReview?
    suspend fun save(review: LegacyArchiveReview)
}

/** Safe default used by callers that have not installed the recovery UX yet. */
object NoLegacyArchive : LegacyArchiveStore {
    override suspend fun load(ownerId: String): LegacyArchiveReview? = null
    override suspend fun save(review: LegacyArchiveReview): Unit = error("Archive not installed")
}

class FileLegacyArchiveStore(private val directory: File) : LegacyArchiveStore {
    private val mutex = Mutex()
    private fun file(owner: String) = AtomicFile(File(directory, "${fingerprint(owner)}.json"))
    override suspend fun load(ownerId: String): LegacyArchiveReview? = mutex.withLock { withContext(Dispatchers.IO) {
        val file = file(ownerId)
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return@withContext null
        require(file.baseFile.length() <= 4_194_304 && File(file.baseFile.path + ".bak").length() <= 4_194_304)
        Json.decodeFromString<LegacyArchiveReview>(file.readFully().toString(Charsets.UTF_8)).also {
            require(it.ownerId == ownerId && it.formatVersion == 1 && it.schemaVersion in setOf(6, 7, 9))
            require(it.entries.size <= 10_000)
            require(it.entries.all { row -> LegacyKind.valueOf(row.kind) in LegacyArchiveReview.historicalKinds && row.fingerprint.matches(Regex("[0-9a-f]{64}")) })
            require(it.evidence == null || it.evidence.matches(Regex("[0-9a-f]{64}")))
            require(it.entries.map { row -> row.kind to row.id }.distinct().size == it.entries.size)
        }
    } }
    override suspend fun save(review: LegacyArchiveReview) = mutex.withLock { withContext(Dispatchers.IO) {
        require(review.ownerId.isNotBlank() && review.schemaVersion in setOf(6, 7, 9))
        check(directory.isDirectory || directory.mkdirs())
        val file = file(review.ownerId)
        val bytes = Json.encodeToString(review).toByteArray(Charsets.UTF_8)
        require(bytes.size <= 4_194_304)
        val output = file.startWrite()
        try { output.write(bytes); output.fd.sync(); file.finishWrite(output) }
        catch (failure: Throwable) { file.failWrite(output); throw failure }
    } }
}

class LegacyRecoveryArchive(
    private val auth: OnlineAuth,
    private val reader: LegacyInventoryReader,
    private val store: LegacyArchiveStore,
) {
    suspend fun review(): Pair<LegacyInventory, LegacyArchiveReview?> {
        val identity = auth.capture().identity
        val inventory = reader.inspect(identity.ownerId)
        val archive = store.load(identity.ownerId)
        if (!auth.isCurrent(identity)) throw StaleAuthException()
        require(inventory.ownerId == identity.ownerId)
        require(archive == null || archive.ownerId == identity.ownerId)
        return inventory to archive
    }

    /** Must be called only from explicit confirmation for this displayed inventory and login. */
    suspend fun keepHistory(identity: AuthIdentity, displayed: LegacyInventory.Present): LegacyArchiveReview {
        require(displayed.ownerId == identity.ownerId)
        if (!auth.isCurrent(identity)) throw StaleAuthException()
        val current = reader.inspect(identity.ownerId)
        if (!auth.isCurrent(identity)) throw StaleAuthException()
        require(current is LegacyInventory.Present && current.evidence() == displayed.evidence()) { "Legacy source changed; review again" }
        require(current.items.none { it.disposition == LegacyDisposition.PROBLEM }) { "Corrupt records require support, not archive acknowledgement" }
        // Whole displayed historical inventory consent is also aggregate/dependency-bound.
        // Older sidecars remain readable but are not authority for changed/restored sources.
        store.load(identity.ownerId)
        val review = LegacyArchiveReview(identity.ownerId, current.schemaVersion,
            current.items.filter { it.kind in LegacyArchiveReview.historicalKinds }.map {
                LegacyArchiveEntry(it.kind.name, it.id, it.fingerprint)
            }, evidence = current.evidence())
        store.save(review)
        if (!auth.isCurrent(identity)) throw StaleAuthException()
        return review
    }
}
