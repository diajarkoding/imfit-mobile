package com.diajarkoding.imfit.domain.model

data class WorkoutSession(
    val id: String,
    val templateId: String,
    val templateName: String,
    val startTime: Long,
    val exerciseLogs: List<ExerciseLog>,
    val currentExerciseIndex: Int = 0,
    val restTimerSeconds: Int = 90
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
}
