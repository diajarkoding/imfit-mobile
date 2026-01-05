package com.diajarkoding.imfit.domain.model

data class ExerciseLog(
    val exercise: Exercise,
    val sets: List<WorkoutSet>,
    val restSeconds: Int = 60
) {
    val totalVolume: Float
        get() = sets.sumOf { it.volume.toDouble() }.toFloat()

    val completedSets: Int
        get() = sets.count { it.isCompleted }
}
