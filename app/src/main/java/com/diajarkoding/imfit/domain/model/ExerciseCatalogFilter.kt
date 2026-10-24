package com.diajarkoding.imfit.domain.model

/** Query the entire local catalog, never just the pages currently visible. */
data class ExerciseCatalogFilter(
    val query: String = "",
    val muscleCategory: MuscleCategory? = null,
    val includedIds: Set<String>? = null,
    val excludedIds: Set<String> = emptySet(),
) {
    fun includes(exercise: Exercise): Boolean =
        (muscleCategory == null || exercise.muscleCategory == muscleCategory) &&
            (includedIds == null || exercise.id in includedIds) && exercise.id !in excludedIds &&
            (query.isBlank() || exercise.name.contains(query.trim(), ignoreCase = true))
}
