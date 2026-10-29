package com.diajarkoding.imfit.data.legacy

fun interface LegacyAggregateReader {
    suspend fun read(ownerId: String, item: LegacyItem, expectedEvidence: String): LegacyAggregate
}

data class LegacyAggregate(
    val inventory: LegacyInventory.Present,
    val item: LegacyItem,
    val records: Map<LegacyKind, List<Map<String, String?>>>,
    val dependencies: Map<String, List<Map<String, String?>>>,
    val aggregateFingerprint: String,
    val dependencyFingerprint: String,
)

/** No partial result is returned for an absent, unstable, unsupported, or changed source. */
class LegacyAggregateReadException(val failure: LegacyReadFailure) : IllegalStateException("Legacy aggregate unavailable: $failure")

internal fun legacyRecordId(kind: LegacyKind, row: Map<String, String?>): String = when (kind) {
    LegacyKind.TEMPLATE_EXERCISE -> "${row["template_id"]}:${row["exercise_id"]}"
    LegacyKind.TEMPLATE_INTENT -> row["key"]
    else -> row["id"]
}?.takeIf { it.isNotBlank() } ?: throw LegacyScanException(LegacyReadFailure.CORRUPT)

/** Called only after the connection transaction/fences have accepted the complete scan. */
internal fun LegacyInventory.Present.selectedAggregate(
    item: LegacyItem,
    expectedEvidence: String,
    rows: Map<LegacyKind, List<Map<String, String?>>>,
    dependencies: Map<String, List<Map<String, String?>>>,
): LegacyAggregate {
    if (evidence() != expectedEvidence || items.count { it == item } != 1) {
        throw LegacyAggregateReadException(LegacyReadFailure.CHANGED_DURING_READ)
    }
    val selected = aggregateItems(item).groupBy { it.kind }
    val records = selected.mapValues { (kind, items) ->
        val ids = items.mapTo(hashSetOf()) { it.id }
        rows.getValue(kind).filter { legacyRecordId(kind, it) in ids }.also {
            if (it.size != items.size) throw LegacyAggregateReadException(LegacyReadFailure.INTEGRITY)
        }
    }
    return LegacyAggregate(this, item, records, dependencies, aggregateFingerprint(item), dependencyFingerprint())
}
