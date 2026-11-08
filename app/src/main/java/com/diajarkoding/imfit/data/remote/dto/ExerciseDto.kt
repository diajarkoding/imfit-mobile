package com.diajarkoding.imfit.data.remote.dto

import com.diajarkoding.imfit.data.local.entity.ExerciseEntity
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.ExerciseModality
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.TrackingType
import com.diajarkoding.imfit.domain.model.TrackingProfile
import com.diajarkoding.imfit.domain.model.CardioMetric
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ExerciseDto(
    val id: String,
    @SerialName("muscle_category_id")
    val muscleCategoryId: Int,
    val name: String,
    val description: String = "",
    @SerialName("image_url")
    val imageUrl: String? = null,
    @SerialName("video_url")
    val videoUrl: String? = null,
    val difficulty: String? = "INTERMEDIATE",
    val equipment: String? = null,
    @SerialName("is_active")
    val isActive: Boolean = true,
    @SerialName("muscle_categories")
    val muscleCategory: MuscleCategoryDto? = null,
    @SerialName("default_tracking_type")
    val defaultTrackingType: String? = null,
    @SerialName("modality")
    val modality: String? = null,
    @SerialName("allowed_modes")
    val allowedModes: List<String>? = null,
    @SerialName("exercise_tracking_modes")
    val trackingModes: List<ExerciseTrackingModeDto>? = null,
    @SerialName("tracking_profile")
    val trackingProfile: String? = null,
    @SerialName("supported_metrics")
    val supportedMetrics: List<String> = emptyList()
)

@Serializable
data class ExerciseTrackingModeDto(
    @SerialName("tracking_type") val trackingType: String
)

fun ExerciseDto.toDomain(): Exercise {
    val category = muscleCategory?.toDomain()
        ?: MuscleCategory.entries.getOrNull(muscleCategoryId - 1)
        ?: MuscleCategory.CHEST
    val parsedDefaultTracking = TrackingType.valueOf(requireNotNull(defaultTrackingType) {
        "Exercise $id has no server default tracking mode"
    })
    val parsedModality = ExerciseModality.valueOf(requireNotNull(modality) {
        "Exercise $id has no server modality"
    })
    val parsedAllowedModes = requireNotNull(allowedModes ?: trackingModes?.map { it.trackingType }) {
        "Exercise $id has no server allowed tracking modes"
    }.map(TrackingType::valueOf)

    return Exercise(
        id = id,
        name = name,
        muscleCategory = category,
        description = description,
        imageUrl = imageUrl,
        isActive = isActive,
        defaultTrackingType = parsedDefaultTracking,
        modality = parsedModality,
        allowedTrackingTypes = parsedAllowedModes,
        trackingProfile = trackingProfile?.let(TrackingProfile::fromWire),
        supportedMetrics = supportedMetrics.map(CardioMetric::fromWire)
    )
}

fun ExerciseDto.toEntity(): ExerciseEntity {
    val exercise = toDomain()
    return ExerciseEntity(
        id = id, name = name, muscleCategoryId = muscleCategoryId,
        description = description, imageUrl = imageUrl, isActive = isActive,
        defaultTrackingType = exercise.defaultTrackingType.name,
        modality = exercise.modality.name,
        allowedTrackingTypes = exercise.allowedTrackingTypes.map { it.name },
        trackingProfile = exercise.trackingProfile?.wireValue,
        supportedMetrics = exercise.supportedMetrics.map { it.wireValue }
    )
}
