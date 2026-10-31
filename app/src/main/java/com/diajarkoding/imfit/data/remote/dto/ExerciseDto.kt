package com.diajarkoding.imfit.data.remote.dto

import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.ExerciseModality
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.TrackingType
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
    val allowedModes: List<String>? = null
)

fun ExerciseDto.toDomain(): Exercise {
    val category = muscleCategory?.toDomain()
        ?: MuscleCategory.entries.getOrNull(muscleCategoryId - 1)
        ?: MuscleCategory.CHEST
    val parsedDefaultTracking = defaultTrackingType?.let { runCatching { TrackingType.valueOf(it) }.getOrNull() }
        ?: TrackingType.WEIGHT_REPS
    val parsedModality = modality?.let { runCatching { ExerciseModality.valueOf(it) }.getOrNull() }
        ?: ExerciseModality.STRENGTH
    val parsedAllowedModes = allowedModes?.mapNotNull { runCatching { TrackingType.valueOf(it) }.getOrNull() }
        ?.takeIf { it.isNotEmpty() } ?: listOf(parsedDefaultTracking)

    return Exercise(
        id = id,
        name = name,
        muscleCategory = category,
        description = description,
        imageUrl = imageUrl,
        isActive = isActive,
        defaultTrackingType = parsedDefaultTracking,
        modality = parsedModality,
        allowedTrackingTypes = parsedAllowedModes
    )
}
