package com.diajarkoding.imfit.data.remote.dto

import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ExerciseDto(
    val id: String,
    @SerialName("muscle_category_id")
    val muscleCategoryId: Int,
    val name: String,
    val description: String,
    @SerialName("image_url")
    val imageUrl: String? = null,
    @SerialName("video_url")
    val videoUrl: String? = null,
    val difficulty: String? = "INTERMEDIATE",
    val equipment: String? = null,
    @SerialName("is_active")
    val isActive: Boolean = true,
    @SerialName("muscle_categories")
    val muscleCategory: MuscleCategoryDto? = null
)

fun ExerciseDto.toDomain(): Exercise {
    val category = muscleCategory?.toDomain()
        ?: MuscleCategory.entries.getOrNull(muscleCategoryId - 1)
        ?: MuscleCategory.CHEST
    
    return Exercise(
        id = id,
        name = name,
        muscleCategory = category,
        description = description,
        imageUrl = imageUrl
    )
}
