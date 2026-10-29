package com.diajarkoding.imfit.data.legacy

import java.security.MessageDigest

enum class LegacyKind { PROFILE, TEMPLATE, TEMPLATE_EXERCISE, WORKOUT, EXERCISE_LOG, SET, ACTIVE_SESSION, TEMPLATE_INTENT }
enum class LegacyDisposition { SYNCED_CACHE_CANDIDATE, PENDING_CREATE, PENDING_CHANGE, REVIEW, PROBLEM }
enum class LegacyReadFailure { UNAVAILABLE, UNSUPPORTED_SCHEMA, SCHEMA_MISMATCH, CORRUPT, INTEGRITY, LIMIT_EXCEEDED, CHANGED_DURING_READ }

/** Metadata only. No names, email, active JSON, set inputs, or another owner's rows. */
data class LegacyItem(
    val kind: LegacyKind, val id: String, val disposition: LegacyDisposition,
    val fingerprint: String, val parentId: String? = null, val requestId: String? = null,
)
data class LegacyDependency(val kind: String, val id: String, val available: Boolean, val fingerprint: String? = null)

sealed interface LegacyInventory {
    val ownerId: String
    data class Absent(override val ownerId: String) : LegacyInventory
    data class Present(
        override val ownerId: String, val schemaVersion: Int, val fingerprint: String,
        val items: List<LegacyItem>, val dependencies: List<LegacyDependency>,
    ) : LegacyInventory
    data class Unknown(override val ownerId: String, val failure: LegacyReadFailure) : LegacyInventory
}

/** Same metadata-only projection used by the stable selected-aggregate reader. */
fun LegacyInventory.Present.aggregateFingerprint(item: LegacyItem): String {
    require(items.count { it == item } == 1) { "Legacy selection changed" }
    return fingerprint(aggregateItems(item).joinToString("") { "${it.kind}:${it.id.length}:${it.id}:${it.fingerprint}\n" })
}

fun LegacyInventory.Present.dependencyFingerprint(): String = fingerprint(
    dependencies.sortedWith(compareBy({ it.kind }, { it.id })).joinToString("") {
        "${it.kind}:${it.id.length}:${it.id}:${it.available}:${it.fingerprint}\n"
    }
)

internal fun LegacyInventory.Present.aggregateItems(item: LegacyItem): List<LegacyItem> {
    val children = when (item.kind) {
        LegacyKind.WORKOUT -> {
            val exercises = items.filter { it.kind == LegacyKind.EXERCISE_LOG && it.parentId == item.id }
            val exerciseIds = exercises.mapTo(hashSetOf()) { it.id }
            exercises + items.filter { it.kind == LegacyKind.SET && it.parentId in exerciseIds }
        }
        LegacyKind.TEMPLATE -> items.filter { it.kind == LegacyKind.TEMPLATE_EXERCISE && it.parentId == item.id }
        LegacyKind.EXERCISE_LOG -> items.filter { it.kind == LegacyKind.SET && it.parentId == item.id }
        else -> emptyList()
    }
    return (listOf(item) + children).sortedWith(compareBy({ it.kind.name }, { it.id }))
}

fun interface LegacyInventoryReader {
    suspend fun inspect(ownerId: String): LegacyInventory
    /** Explicit owner-only recovery inspection; never used by lists/timers/business runtime. */
    suspend fun record(ownerId: String, item: LegacyItem): Map<String, String?> = error("Recovery details unavailable")
}

/** Query-only seam shared by Android OPEN_READONLY and host SQLite verification. */
internal fun interface LegacySqlReader {
    fun rows(sql: String, args: List<String>, consume: (Map<String, String?>) -> Unit)
}

internal fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

internal class LegacyScanException(val failure: LegacyReadFailure) : Exception("Legacy inventory unavailable")

internal fun LegacyInventory.evidence(): String = when (this) {
    is LegacyInventory.Absent -> fingerprint("absent:$ownerId")
    is LegacyInventory.Present -> fingerprint(buildString {
        append(ownerId).append(':').append(schemaVersion).append(':').append(fingerprint).append('\n')
        for (item in items) {
            append(item.kind).append(':').append(item.id.length).append(':').append(item.id)
                .append(':').append(item.disposition).append(':').append(item.fingerprint)
                .append(':').append(item.parentId?.length ?: -1).append(':').append(item.parentId)
                .append(':').append(item.requestId?.length ?: -1).append(':').append(item.requestId).append('\n')
        }
        append(dependencyFingerprint())
    })
    is LegacyInventory.Unknown -> fingerprint("unknown:$ownerId:${failure.name}")
}

internal fun legacyRowFingerprint(row: Map<String, String?>): String = fingerprint(
    row.toSortedMap().entries.joinToString("") { (key, value) -> "$key:${value?.toByteArray(Charsets.UTF_8)?.size ?: -1}:$value\n" }
)
