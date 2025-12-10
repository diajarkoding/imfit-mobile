package com.diajarkoding.imfit.data.remote.dto

import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WorkoutTemplateDto(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    val name: String,
    val description: String? = null,
    @SerialName("estimated_duration")
    val estimatedDuration: Int? = null,
    @SerialName("is_deleted")
    val isDeleted: Boolean = false,
    @SerialName("created_at")
    val createdAt: String? = null,
    @SerialName("updated_at")
    val updatedAt: String? = null,
    @SerialName("template_exercises")
    val templateExercises: List<TemplateExerciseDto>? = null
)

@Serializable
data class TemplateExerciseDto(
    val id: String? = null,
    @SerialName("template_id")
    val templateId: String? = null,
    @SerialName("exercise_id")
    val exerciseId: String,
    @SerialName("order_index")
    val orderIndex: Int = 0,
    val sets: Int = 3,
    val reps: Int = 10,
    @SerialName("rest_seconds")
    val restSeconds: Int = 60,
    val notes: String? = null,
    val exercises: ExerciseDto? = null
)

fun WorkoutTemplateDto.toDomain(): WorkoutTemplate {
    return WorkoutTemplate(
        id = id,
        userId = userId,
        name = name,
        exercises = templateExercises?.mapNotNull { it.toDomain() } ?: emptyList(),
        createdAt = parseTimestamp(createdAt)
    )
}

fun TemplateExerciseDto.toDomain(): TemplateExercise? {
    val exercise = exercises?.toDomain() ?: return null
    return TemplateExercise(
        exercise = exercise,
        sets = sets,
        reps = reps,
        restSeconds = restSeconds
    )
}

fun TemplateExercise.toDto(templateId: String, orderIndex: Int): TemplateExerciseDto {
    return TemplateExerciseDto(
        templateId = templateId,
        exerciseId = exercise.id,
        orderIndex = orderIndex,
        sets = sets,
        reps = reps,
        restSeconds = restSeconds
    )
}

private fun parseTimestamp(timestamp: String?): Long {
    if (timestamp == null) return System.currentTimeMillis()
    return try {
        java.time.OffsetDateTime.parse(timestamp).toInstant().toEpochMilli()
    } catch (e: Exception) {
        System.currentTimeMillis()
    }
}
