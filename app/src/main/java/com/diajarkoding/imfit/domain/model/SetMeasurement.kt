package com.diajarkoding.imfit.domain.model

data class SetMeasurement(
    val weightKg: Double? = null,
    val reps: Int? = null,
    val durationSeconds: Int? = null,
    val distanceMeters: Double? = null,
    val additionalWeight: Float? = null,
    val assistanceWeight: Float? = null,
    val caloriesKcal: Double? = null,
    val speedKph: Double? = null,
    val lapReps: Int? = null,
    val inclinePercent: Double? = null,
    val measurementVersion: Int? = null
) {
    fun isCompleteEligible(type: TrackingType): Boolean = measurementVersion == null && when (type) {
        TrackingType.WEIGHT_REPS -> (weightKg ?: 0.0) > 0.0 && (reps ?: 0) > 0
        TrackingType.BODYWEIGHT_REPS -> (reps ?: 0) > 0 && (additionalWeight ?: 0f) >= 0f
        TrackingType.ASSISTED_REPS -> (reps ?: 0) > 0 && (assistanceWeight ?: 0f) > 0f
        TrackingType.REPS_ONLY -> (reps ?: 0) > 0
        TrackingType.DURATION -> (durationSeconds ?: 0) > 0
        TrackingType.DISTANCE_DURATION -> (durationSeconds ?: 0) > 0 && (distanceMeters ?: 0.0) > 0.0
    }

    fun isCompleteEligible(
        type: TrackingType,
        modality: ExerciseModality,
        trackingProfile: TrackingProfile?,
        supportedMetrics: List<CardioMetric>
    ): Boolean {
        if (measurementVersion == null) return isCompleteEligible(type)
        if (measurementVersion != 3 || !validTrackingMetadata(modality, type, trackingProfile, supportedMetrics)) return false
        if (additionalWeight != null || assistanceWeight != null) return false
        if (type == TrackingType.WEIGHT_REPS) {
            return weightKg?.isTwoDecimalIn(0.0, 1000.0) == true && reps != null && reps in 1..1000 &&
                durationSeconds == null && distanceMeters == null && caloriesKcal == null &&
                speedKph == null && lapReps == null && inclinePercent == null
        }
        if (type != TrackingType.DURATION || durationSeconds == null || durationSeconds !in 1..86400 || weightKg != null || reps != null) return false
        fun metric(value: Double?, capability: CardioMetric, min: Double = 0.0, max: Double = 9_999_999_999.99): Boolean =
            value == null || (modality == ExerciseModality.CARDIO && capability in supportedMetrics && value.isTwoDecimalIn(min, max))
        return metric(distanceMeters, CardioMetric.DISTANCE) &&
            metric(caloriesKcal, CardioMetric.CALORIES) &&
            metric(speedKph, CardioMetric.SPEED) &&
            metric(inclinePercent, CardioMetric.INCLINE, -999.99, 999.99) &&
            (lapReps == null || (modality == ExerciseModality.CARDIO && CardioMetric.LAPS in supportedMetrics && lapReps >= 0))
    }
}
