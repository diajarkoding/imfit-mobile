package com.diajarkoding.imfit.domain.model

import kotlinx.serialization.Serializable

/** Pending completion metadata; not an observed set measurement or a second timer. */
@Serializable
data class RestTimerTrigger(
    val sessionId: String,
    val exerciseId: String,
    val setId: String,
    val targetRowId: String,
    val restSeconds: Int,
    val deadlineElapsedMs: Long,
    val bootId: String
) {
    fun isValid(): Boolean = sessionId.isNotBlank() && exerciseId.isNotBlank() &&
        setId.isNotBlank() && targetRowId.isNotBlank() && restSeconds in 0..600 &&
        deadlineElapsedMs >= 0 && bootId.isNotBlank()
}
