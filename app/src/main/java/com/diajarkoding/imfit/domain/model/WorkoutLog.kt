package com.diajarkoding.imfit.domain.model

data class WorkoutLog(
    val id: String,
    val userId: String,
    val templateName: String,
    val date: Long,
    val startTime: Long,
    val endTime: Long,
    val totalVolume: Float,
    val exerciseLogs: List<ExerciseLog>
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
