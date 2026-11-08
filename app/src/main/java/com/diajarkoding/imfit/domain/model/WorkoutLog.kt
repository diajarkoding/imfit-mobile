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
    val totalDistanceMeters: Double? = exerciseLogs.fold(null as Double?) { sum, log ->
        log.totalDistanceMeters?.let { (sum ?: 0.0) + it } ?: sum
    },
    val additionalVolume: Float = exerciseLogs.sumOf { it.additionalVolume.toDouble() }.toFloat(),
    val dataQuality: String = "VERIFIED",
    val measurementVersion: Int? = null,
    val recordedLoadVolume: Double? = null,
    /** v3: total time paused (ms); null for legacy history. */
    val pausedElapsedMs: Long? = null,
    /** v3: whole-session time without Pause (includes rest); distinct from gross `endTime - startTime`. */
    val activeElapsedMs: Long? = null
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
