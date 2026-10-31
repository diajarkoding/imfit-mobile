package com.diajarkoding.imfit.presentation.ui.exercise

import androidx.annotation.StringRes
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory

/** Presentation filters are independent of the persisted MuscleCategory ordinal IDs. */
enum class ExerciseCategory(
    @StringRes val labelRes: Int,
    val artwork: String,
    val domainCategory: MuscleCategory? = null
) {
    CHEST(R.string.muscle_category_chest, "chest.svg", MuscleCategory.CHEST),
    BACK(R.string.muscle_category_back, "back.svg", MuscleCategory.BACK),
    SHOULDERS(R.string.muscle_category_shoulders, "shoulders.svg", MuscleCategory.SHOULDERS),
    BICEPS(R.string.muscle_category_biceps, "biceps.svg", MuscleCategory.BICEPS),
    TRICEPS(R.string.muscle_category_triceps, "triceps.svg", MuscleCategory.TRICEPS),
    FOREARMS(R.string.exercise_category_forearms, "forearms.svg"),
    CORE(R.string.muscle_category_core, "core.svg", MuscleCategory.CORE),
    LEGS(R.string.exercise_category_thighs, "upper-legs.svg", MuscleCategory.LEGS),
    GLUTES(R.string.exercise_category_glutes, "glutes.svg"),
    CALVES(R.string.exercise_category_calves, "calves.svg"),
    CARDIO(R.string.muscle_category_cardio, "cardio.svg", MuscleCategory.CARDIO),
    ALL(R.string.exercise_category_all, "all-muscles.svg");

    val databaseId: Int? get() = when (this) {
        ALL, FOREARMS -> null
        GLUTES, CALVES -> MuscleCategory.LEGS.databaseId
        else -> domainCategory?.databaseId
    }

    fun includes(exercise: Exercise): Boolean = when (this) {
        ALL -> true
        FOREARMS, GLUTES, CALVES -> this in verifiedSubgroups[exercise.id].orEmpty()
        LEGS -> exercise.muscleCategory == MuscleCategory.LEGS &&
            CALVES !in verifiedSubgroups[exercise.id].orEmpty()
        else -> exercise.muscleCategory == domainCategory
    }

    companion object {
        // Verified against FakeExerciseDataSource. No name-based guesses for remote records.
        // Remote subgroup IDs can be registered here when catalog metadata is available.
        private val verifiedSubgroups = mapOf(
            "ex_legs_3" to setOf(GLUTES), // Romanian deadlift
            "ex_legs_6" to setOf(CALVES), // Calf raises
            "ex_legs_7" to setOf(GLUTES), // Lunges
            "ex_legs_10" to setOf(GLUTES), // Glute Bridge
            "ex_legs_14" to setOf(GLUTES), // Barbell Hip Thrust
            "ex_legs_15" to setOf(GLUTES), // Dumbbell Walking Lunges
            "ex_legs_17" to setOf(CALVES), // Seated Calf Raise Machine
            "ex_legs_18" to setOf(GLUTES), // Cable Pull-Through
            "ex_legs_20" to setOf(GLUTES) // Hip Abductor Machine
        )

        fun fromRoute(name: String): ExerciseCategory =
            entries.firstOrNull { it.name == name } ?: ALL
    }
}
