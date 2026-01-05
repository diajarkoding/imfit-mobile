package com.diajarkoding.imfit.domain.model

data class TemplateExercise(
    val exercise: Exercise,
    val sets: Int = 3,
    val reps: Int = 8,
    val restSeconds: Int = 60
) {
    val id: String get() = exercise.id
    val name: String get() = exercise.name
    val muscleCategory: MuscleCategory get() = exercise.muscleCategory
    val description: String get() = exercise.description
    val imageUrl: String? get() = exercise.imageUrl
}
