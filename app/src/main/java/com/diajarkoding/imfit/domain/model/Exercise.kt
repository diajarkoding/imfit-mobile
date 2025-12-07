package com.diajarkoding.imfit.domain.model

data class Exercise(
    val id: String,
    val name: String,
    val muscleCategory: MuscleCategory,
    val description: String,
    val imageUrl: String? = null
)
