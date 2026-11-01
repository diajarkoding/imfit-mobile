package com.diajarkoding.imfit.domain.model

data class WorkoutLog(
    val id: String,
    val userId: String,
    val templateName: String,
    val date: Long,
    val startTime: Long,
    val endTime: Long,
    val totalVolume: Float,
    val exerciseLogs: List<ExerciseLog>,
    val totalSets: Int = exerciseLogs.sumOf { it.completedSets },
    val totalReps: Int = exerciseLogs.sumOf { it.totalReps },
    val totalDurationSeconds: Int = exerciseLogs.sumOf { it.totalDurationSeconds },
    val totalDistanceMeters: Int = exerciseLogs.sumOf { it.totalDistanceMeters },
    val additionalVolume: Float = exerciseLogs.sumOf { it.additionalVolume.toDouble() }.toFloat(),
    val dataQuality: String = "VERIFIED"
) {
    val durationMinutes: Int
        get() = ((endTime - startTime) / 60000).toInt()

    val formattedDuration: String
        get() {
            val hours = durationMinutes / 60
            val minutes = durationMinutes % 60
            return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
        }

    val formattedVolume: String
        get() = String.format("%,.0f kg", totalVolume)
}
