package com.diajarkoding.imfit.domain.model

data class SetMeasurement(
    val weight: Float? = null,
    val reps: Int? = null,
    val durationSeconds: Int? = null,
    val distanceMeters: Int? = null,
    val additionalWeight: Float? = null,
    val assistanceWeight: Float? = null
) {
    fun isCompleteEligible(type: TrackingType): Boolean = when (type) {
        TrackingType.WEIGHT_REPS -> (weight ?: 0f) > 0f && (reps ?: 0) > 0
        TrackingType.BODYWEIGHT_REPS -> (reps ?: 0) > 0 && (additionalWeight ?: 0f) >= 0f
        TrackingType.ASSISTED_REPS -> (reps ?: 0) > 0 && (assistanceWeight ?: 0f) > 0f
        TrackingType.REPS_ONLY -> (reps ?: 0) > 0
        TrackingType.DURATION -> (durationSeconds ?: 0) > 0
        TrackingType.DISTANCE_DURATION -> (durationSeconds ?: 0) > 0 && (distanceMeters ?: 0) > 0
    }
}
