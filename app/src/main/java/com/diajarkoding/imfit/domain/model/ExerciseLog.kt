package com.diajarkoding.imfit.domain.model

data class ExerciseLog(
    val exercise: Exercise,
    val sets: List<WorkoutSet>,
    val restSeconds: Int = 60,
    val inputDefaults: ExerciseInputDefaults? = null,
    val trackingType: TrackingType = exercise.defaultTrackingType,
    val modality: ExerciseModality = exercise.modality,
    val metricQuality: String = "VERIFIED"
) {
    val totalVolume: Float
        get() = if (trackingType == TrackingType.WEIGHT_REPS) {
            sets.filter { it.isCompleted }.sumOf { (it.weight * it.reps).toDouble() }.toFloat()
        } else 0f

    val additionalVolume: Float
        get() = if (trackingType == TrackingType.BODYWEIGHT_REPS) {
            sets.filter { it.isCompleted }.sumOf { ((it.additionalWeight ?: 0f) * it.reps).toDouble() }.toFloat()
        } else 0f

    val totalReps: Int
        get() = if (trackingType in listOf(TrackingType.WEIGHT_REPS, TrackingType.BODYWEIGHT_REPS, TrackingType.ASSISTED_REPS, TrackingType.REPS_ONLY)) {
            sets.filter { it.isCompleted }.sumOf { it.reps }
        } else 0

    val totalDurationSeconds: Int
        get() = if (trackingType in listOf(TrackingType.DURATION, TrackingType.DISTANCE_DURATION)) {
            sets.filter { it.isCompleted }.sumOf { it.durationSeconds ?: 0 }
        } else 0

    val totalDistanceMeters: Int
        get() = if (trackingType == TrackingType.DISTANCE_DURATION) {
            sets.filter { it.isCompleted }.sumOf { it.distanceMeters ?: 0 }
        } else 0

    val completedSets: Int
        get() = sets.count { it.isCompleted }
}
