package com.diajarkoding.imfit.domain.model

data class WorkoutTemplate(
    val id: String,
    val userId: String,
    val name: String,
    val exercises: List<TemplateExercise> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val revision: Long = 0L,
    private val explicitPrimaryCategory: MuscleCategory? = null,
    val exerciseCountOverride: Int? = null
) {
    val exerciseCount: Int
        get() = exerciseCountOverride ?: exercises.size

    val isConfigured: Boolean
        get() = exerciseCount > 0

    val primaryMuscleCategory: MuscleCategory?
        get() = explicitPrimaryCategory ?: derivePrimaryCategory(exercises)

    companion object {
        fun derivePrimaryCategory(exercises: List<TemplateExercise>): MuscleCategory? {
            if (exercises.isEmpty()) return null
            val resistance = exercises.filter { it.exercise.muscleCategory != MuscleCategory.CARDIO }
            if (resistance.isEmpty()) return MuscleCategory.CARDIO
            val totalResistanceSets = resistance.sumOf { it.sets }
            if (totalResistanceSets == 0) return null
            val upperSets = resistance.filter { it.exercise.muscleCategory in upperCategories }.sumOf { it.sets }
            val lowerSets = resistance.filter { it.exercise.muscleCategory == MuscleCategory.LEGS }.sumOf { it.sets }
            if (upperSets >= 0.30 * totalResistanceSets && lowerSets >= 0.25 * totalResistanceSets) {
                return null // Full Body combination -> neutral / all-muscles
            }
            return resistance.groupBy { it.exercise.muscleCategory }
                .maxByOrNull { (_, list) -> list.sumOf { it.sets } }
                ?.key
        }

        private val upperCategories = setOf(
            MuscleCategory.CHEST,
            MuscleCategory.BACK,
            MuscleCategory.SHOULDERS,
            MuscleCategory.BICEPS,
            MuscleCategory.TRICEPS
        )
    }
}
