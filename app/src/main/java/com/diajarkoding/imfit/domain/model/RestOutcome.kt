package com.diajarkoding.imfit.domain.model

import kotlinx.serialization.Serializable

@Serializable
enum class RestEndReason { EXPIRED, SKIPPED }

/**
 * Actual rest that ended, linked to the completed set that triggered it. Separate from the
 * target `rest_seconds` (never overwritten) and from measurement fields. Written once, only when
 * rest ends by deadline (EXPIRED) or an explicit user Skip (SKIPPED); pause intervals are excluded.
 */
@Serializable
data class RestOutcome(val elapsedMs: Long, val endReason: RestEndReason) {
    fun isValid(): Boolean = elapsedMs >= 0
}
