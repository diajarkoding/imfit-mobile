package com.diajarkoding.imfit.data.remote.dto

import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSet
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WorkoutLogDto(
    val id: String,
    @SerialName("user_id")
    val userId: String,
    @SerialName("template_id")
    val templateId: String? = null,
    @SerialName("template_name")
    val templateName: String,
    val date: String,
    @SerialName("start_time")
    val startTime: String,
    @SerialName("end_time")
    val endTime: String,
    @SerialName("total_volume")
    val totalVolume: Double = 0.0,
    @SerialName("total_sets")
    val totalSets: Int = 0,
    @SerialName("total_reps")
    val totalReps: Int = 0,
    val notes: String? = null,
    val rating: Int? = null,
    @SerialName("deleted_at")
    val deletedAt: String? = null,
    @SerialName("exercise_logs")
    val exerciseLogs: List<ExerciseLogDto>? = null
)

@Serializable
data class ExerciseLogDto(
    val id: String? = null,
    @SerialName("workout_log_id")
    val workoutLogId: String? = null,
    @SerialName("exercise_id")
    val exerciseId: String,
    @SerialName("exercise_name")
    val exerciseName: String,
    @SerialName("muscle_category")
    val muscleCategory: String,
    @SerialName("order_index")
    val orderIndex: Int = 0,
    @SerialName("total_volume")
    val totalVolume: Double = 0.0,
    @SerialName("workout_sets")
    val workoutSets: List<WorkoutSetDto>? = null,
    val exercises: ExerciseDto? = null
)

@Serializable
data class WorkoutSetDto(
    val id: String? = null,
    @SerialName("exercise_log_id")
    val exerciseLogId: String? = null,
    @SerialName("set_number")
    val setNumber: Int,
    val weight: Double = 0.0,
    val reps: Int = 0,
    @SerialName("is_completed")
    val isCompleted: Boolean = false,
    @SerialName("is_warmup")
    val isWarmup: Boolean = false,
    val notes: String? = null
)

fun WorkoutLogDto.toDomain(): WorkoutLog {
    return WorkoutLog(
        id = id,
        userId = userId,
        templateName = templateName,
        date = parseTimestamp(startTime),
        startTime = parseTimestamp(startTime),
        endTime = parseTimestamp(endTime),
        totalVolume = totalVolume.toFloat(),
        exerciseLogs = exerciseLogs?.map { it.toDomain() } ?: emptyList()
    )
}

fun ExerciseLogDto.toDomain(): ExerciseLog {
    // Try to use the nested exercise, or create a fallback from DTO data
    val exercise = exercises?.toDomain() ?: run {
        // Create fallback exercise using data from the ExerciseLogDto itself
        val category = try {
            com.diajarkoding.imfit.domain.model.MuscleCategory.valueOf(muscleCategory.uppercase())
        } catch (e: Exception) {
            com.diajarkoding.imfit.domain.model.MuscleCategory.CHEST
        }
        com.diajarkoding.imfit.domain.model.Exercise(
            id = exerciseId,
            name = exerciseName,
            muscleCategory = category,
            description = "",
            imageUrl = null
        )
    }
    return ExerciseLog(
        exercise = exercise,
        sets = workoutSets?.map { it.toDomain() } ?: emptyList()
    )
}

fun WorkoutSetDto.toDomain(): WorkoutSet {
    return WorkoutSet(
        setNumber = setNumber,
        weight = weight.toFloat(),
        reps = reps,
        isCompleted = isCompleted
    )
}

fun WorkoutSet.toDto(exerciseLogId: String? = null): WorkoutSetDto {
    return WorkoutSetDto(
        exerciseLogId = exerciseLogId,
        setNumber = setNumber,
        weight = weight.toDouble(),
        reps = reps,
        isCompleted = isCompleted
    )
}

private fun parseTimestamp(timestamp: String?): Long {
    if (timestamp == null) return System.currentTimeMillis()
    return try {
        java.time.OffsetDateTime.parse(timestamp).toInstant().toEpochMilli()
    } catch (e: Exception) {
        try {
            java.time.LocalDate.parse(timestamp).atStartOfDay()
                .toInstant(java.time.ZoneOffset.UTC).toEpochMilli()
        } catch (e2: Exception) {
            System.currentTimeMillis()
        }
    }
}
