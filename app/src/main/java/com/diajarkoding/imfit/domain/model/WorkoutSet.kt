package com.diajarkoding.imfit.domain.model

data class WorkoutSet(
    val setNumber: Int,
    val weight: Float = 0f,
    val reps: Int = 0,
    val isCompleted: Boolean = false
) {
    val volume: Float
        get() = weight * reps
}
