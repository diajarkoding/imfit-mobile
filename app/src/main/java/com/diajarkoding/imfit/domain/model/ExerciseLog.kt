package com.diajarkoding.imfit.domain.model

data class ExerciseLog(
    val exercise: Exercise,
    val sets: List<WorkoutSet>,
    val restSeconds: Int = 60,
    val inputDefaults: ExerciseInputDefaults? = null,
    val trackingType: TrackingType = exercise.defaultTrackingType,
    val modality: ExerciseModality = exercise.modality,
    val metricQuality: String = "VERIFIED",
    val measurementVersion: Int? = null
) {
    val totalVolume: Float
        get() = if (measurementVersion == null && trackingType == TrackingType.WEIGHT_REPS) {
            sets.sumOf { if (it.isCompleted) (it.weightKg ?: 0.0) * (it.reps ?: 0) else 0.0 }.toFloat()
        } else 0f

    val additionalVolume: Float
        get() = if (measurementVersion == null && trackingType == TrackingType.BODYWEIGHT_REPS) {
            sets.sumOf { if (it.isCompleted) (it.additionalWeight ?: 0f).toDouble() * (it.reps ?: 0) else 0.0 }.toFloat()
        } else 0f

    val totalReps: Int
        get() = if (trackingType in listOf(TrackingType.WEIGHT_REPS, TrackingType.BODYWEIGHT_REPS, TrackingType.ASSISTED_REPS, TrackingType.REPS_ONLY)) {
            sets.sumOf { if (it.isCompleted) it.reps ?: 0 else 0 }
        } else 0

    val totalDurationSeconds: Int
        get() = if (trackingType == TrackingType.DURATION || trackingType == TrackingType.DISTANCE_DURATION) {
            sets.sumOf { if (it.isCompleted) it.durationSeconds ?: 0 else 0 }
        } else 0

    val totalDistanceMeters: Double?
        get() {
            if (measurementVersion == 3) {
                if (modality != ExerciseModality.CARDIO) return null
                var total: Double? = null
                for (set in sets) if (set.isCompleted && set.distanceMeters != null) total = (total ?: 0.0) + set.distanceMeters
                return total
            }
            return if (trackingType == TrackingType.DISTANCE_DURATION) {
                sets.sumOf { if (it.isCompleted) it.distanceMeters ?: 0.0 else 0.0 }
            } else 0.0
        }

    val recordedLoadVolume: Double
        get() = if (measurementVersion == 3 && trackingType == TrackingType.WEIGHT_REPS) {
            sets.sumOf { if (it.isCompleted) (it.weightKg ?: 0.0) * (it.reps ?: 0) else 0.0 }
        } else 0.0

    fun restSecondsForSet(setId: String): Int {
        if (measurementVersion != 3) return restSeconds
        val set = sets.single { it.id == setId }
        val snapshot = requireNotNull(inputDefaults) { "Missing v3 configuration snapshot" }
        val target = requireNotNull(snapshot.setTargets).find { it.rowId == set.targetRowId }
            ?: snapshot.extraSetTargets.single { it.rowId == set.targetRowId }
        return target.restSeconds
    }

    val completedSets: Int
        get() = sets.count { it.isCompleted }
}
