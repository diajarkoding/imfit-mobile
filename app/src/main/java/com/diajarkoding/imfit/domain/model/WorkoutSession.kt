package com.diajarkoding.imfit.domain.model

data class WorkoutSession(
    val id: String,
    val templateId: String,
    val templateName: String,
    val startTime: Long,
    val exerciseLogs: List<ExerciseLog>,
    val currentExerciseIndex: Int = 0,
    val isPaused: Boolean = false,
    val totalPausedTimeMs: Long = 0,
    val lastPauseTime: Long? = null
) {
    val totalVolume: Float
        get() = exerciseLogs.sumOf { it.totalVolume.toDouble() }.toFloat()

    val totalCompletedSets: Int
        get() = exerciseLogs.sumOf { it.completedSets }

    val totalSets: Int
        get() = exerciseLogs.sumOf { it.sets.size }

    val currentExercise: ExerciseLog?
        get() = exerciseLogs.getOrNull(currentExerciseIndex)

    val isCompleted: Boolean
        get() = exerciseLogs.all { log -> log.sets.all { it.isCompleted } }

    val durationMinutes: Int
        get() = ((System.currentTimeMillis() - startTime) / 60000).toInt()

    val actualElapsedMs: Long
        get() {
            val totalTime = System.currentTimeMillis() - startTime
            val currentPauseDuration = if (isPaused && lastPauseTime != null) {
                System.currentTimeMillis() - lastPauseTime
            } else {
                0L
            }
            return totalTime - totalPausedTimeMs - currentPauseDuration
        }

    /**
     * Gets the rest time in seconds for a specific exercise.
     */
    fun getRestSecondsForExercise(exerciseIndex: Int): Int =
        exerciseLogs.getOrNull(exerciseIndex)?.restSeconds ?: 60
}

