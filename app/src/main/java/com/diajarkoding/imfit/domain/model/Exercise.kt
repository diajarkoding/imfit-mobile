package com.diajarkoding.imfit.domain.model

data class Exercise(
    val id: String,
    val name: String,
    val muscleCategory: MuscleCategory,
    val description: String,
    val imageUrl: String? = null,
    val isActive: Boolean = true,
    val defaultTrackingType: TrackingType = TrackingType.WEIGHT_REPS,
    val modality: ExerciseModality = ExerciseModality.STRENGTH,
    val allowedTrackingTypes: List<TrackingType> = listOf(defaultTrackingType),
    val trackingProfile: TrackingProfile? = null,
    val supportedMetrics: List<CardioMetric> = emptyList()
) {
    init {
        require(defaultTrackingType in allowedTrackingTypes) {
            "Exercise $id default tracking mode must be allowed"
        }
    }

    fun requireV3Metadata() {
        require(validTrackingMetadata(modality, defaultTrackingType, trackingProfile, supportedMetrics)) {
            "Exercise $id has invalid v3 tracking metadata"
        }
    }
}
