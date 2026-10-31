package com.diajarkoding.imfit.domain.model

data class ExerciseTarget(
    val sets: Int = 3,
    val targetReps: Int? = null,
    val targetDurationSeconds: Int? = null,
    val targetDistanceMeters: Int? = null,
    val restSeconds: Int = 60
) {
    fun isValidFor(type: TrackingType): Boolean {
        if (sets !in 1..20 || restSeconds !in 0..600) return false
        return when (type) {
            TrackingType.WEIGHT_REPS,
            TrackingType.BODYWEIGHT_REPS,
            TrackingType.ASSISTED_REPS,
            TrackingType.REPS_ONLY -> (targetReps ?: 0) in 1..100
            TrackingType.DURATION -> (targetDurationSeconds ?: 0) in 1..86400
            TrackingType.DISTANCE_DURATION -> (targetDurationSeconds ?: 0) in 1..86400 &&
                (targetDistanceMeters == null || targetDistanceMeters in 1..1000000)
        }
    }
}
