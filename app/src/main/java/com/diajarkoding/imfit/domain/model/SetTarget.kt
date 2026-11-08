package com.diajarkoding.imfit.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.nio.charset.StandardCharsets
import java.util.UUID

@Serializable
data class SetTarget(
    @SerialName("row_id") val rowId: String,
    @SerialName("set_number") val setNumber: Int,
    @SerialName("weight") val weightKg: Double? = null,
    val reps: Int? = null,
    @SerialName("duration_seconds") val durationSeconds: Int? = null,
    @SerialName("rest_seconds") val restSeconds: Int = 60
) {
    fun isValidFor(type: TrackingType, allowUnknownWeight: Boolean = false): Boolean {
        if (rowId.isBlank() || setNumber !in 1..20 || restSeconds !in 0..600) return false
        return when (type) {
            TrackingType.WEIGHT_REPS -> (weightKg?.isTwoDecimalIn(0.0, 1000.0) == true || (allowUnknownWeight && weightKg == null)) &&
                reps != null && reps in 1..100 && durationSeconds == null
            TrackingType.DURATION -> durationSeconds != null && durationSeconds in 1..86400 && weightKg == null && reps == null
            else -> false
        }
    }
}

/** A view over legacy scalar configuration. Never writes or promotes its source. */
object LegacyTargetAdapter {
    fun expand(
        scope: String,
        sets: Int,
        reps: Int?,
        durationSeconds: Int?,
        distanceMeters: Int?,
        restSeconds: Int,
        trackingType: TrackingType
    ): List<SetTarget> {
        require(scope.isNotBlank() && sets in 1..20) { "Invalid legacy target identity/count" }
        require(distanceMeters == null || distanceMeters >= 0) { "Invalid legacy target distance" }
        val durationBased = trackingType == TrackingType.DURATION || trackingType == TrackingType.DISTANCE_DURATION
        return List(sets) { index ->
            SetTarget(
                rowId = UUID.nameUUIDFromBytes("imfit:legacy-target:$scope:${index + 1}".toByteArray(StandardCharsets.UTF_8)).toString(),
                setNumber = index + 1,
                weightKg = null,
                reps = if (durationBased) null else reps,
                durationSeconds = if (durationBased) durationSeconds else null,
                restSeconds = restSeconds
            )
        }
    }
}
