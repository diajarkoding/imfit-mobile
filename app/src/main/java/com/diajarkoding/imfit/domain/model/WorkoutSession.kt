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
    val lastPauseTime: Long? = null,
    val schemaVersion: Int = 2,
    val pendingRestTrigger: RestTimerTrigger? = null,
    /** Routine revision frozen at start (v3 snapshot); null for legacy sessions. */
    val templateRevision: Long? = null,
    /** v3 runtime: whole-session active clock (excludes Pause, includes rest). Null for legacy sessions. */
    val sessionStopwatch: ExecutionStopwatch? = null,
    /** v3 runtime: runs only while paused; its elapsed is `paused_elapsed_ms`. */
    val pauseStopwatch: ExecutionStopwatch? = null
) {
    val totalVolume: Float
        get() = exerciseLogs.sumOf { it.totalVolume.toDouble() }.toFloat()

    val additionalVolume: Float
        get() = exerciseLogs.sumOf { it.additionalVolume.toDouble() }.toFloat()

    val totalCompletedSets: Int
        get() = exerciseLogs.sumOf { it.completedSets }

    val totalSets: Int
        get() = exerciseLogs.sumOf { it.sets.size }

    val totalReps: Int
        get() = exerciseLogs.sumOf { it.totalReps }

    val totalDurationSeconds: Int
        get() = exerciseLogs.sumOf { it.totalDurationSeconds }

    val totalDistanceMeters: Double?
        get() {
            var total: Double? = if (schemaVersion == 3) null else 0.0
            for (log in exerciseLogs) log.totalDistanceMeters?.let { total = (total ?: 0.0) + it }
            return total
        }

    val recordedLoadVolume: Double
        get() = exerciseLogs.sumOf { it.recordedLoadVolume }

    val currentExercise: ExerciseLog?
        get() = exerciseLogs.getOrNull(currentExerciseIndex)

    val isCompleted: Boolean
        get() = exerciseLogs.isNotEmpty() && exerciseLogs.all { log -> log.sets.isNotEmpty() && log.sets.all { it.isCompleted } }

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
