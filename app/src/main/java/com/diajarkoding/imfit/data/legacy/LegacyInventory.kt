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
data class LegacyDependency(val kind: String, val id: String, val available: Boolean)

sealed interface LegacyInventory {
    val ownerId: String
    data class Absent(override val ownerId: String) : LegacyInventory
    data class Present(
        override val ownerId: String, val schemaVersion: Int, val fingerprint: String,
        val items: List<LegacyItem>, val dependencies: List<LegacyDependency>,
    ) : LegacyInventory
    data class Unknown(override val ownerId: String, val failure: LegacyReadFailure) : LegacyInventory
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
    is LegacyInventory.Present -> fingerprint("$ownerId:$schemaVersion:$fingerprint")
    is LegacyInventory.Unknown -> fingerprint("unknown:$ownerId:${failure.name}")
}
