package com.diajarkoding.imfit.domain.model

data class WorkoutTemplate(
    val id: String,
    val userId: String,
    val name: String,
    val exercises: List<TemplateExercise>,
    val createdAt: Long = System.currentTimeMillis()
) {
    val exerciseCount: Int
        get() = exercises.size
}
