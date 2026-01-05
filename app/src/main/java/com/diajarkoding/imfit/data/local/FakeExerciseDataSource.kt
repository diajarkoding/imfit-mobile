package com.diajarkoding.imfit.data.local

import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory

object FakeExerciseDataSource {

    val exercises: List<Exercise> = listOf(
        // CHEST
        Exercise(
            id = "ex_chest_1",
            name = "Barbell Bench Press",
            muscleCategory = MuscleCategory.CHEST,
            description = "Lie on a flat bench, grip the barbell slightly wider than shoulder-width, lower to chest, and press up."
        ),
        Exercise(
            id = "ex_chest_2",
            name = "Incline Dumbbell Press",
            muscleCategory = MuscleCategory.CHEST,
            description = "Set bench to 30-45 degrees, press dumbbells up from shoulder level."
        ),
        Exercise(
            id = "ex_chest_3",
            name = "Cable Flyes",
            muscleCategory = MuscleCategory.CHEST,
            description = "Stand between cable machines, bring handles together in front of chest with slight bend in elbows."
        ),
        Exercise(
            id = "ex_chest_4",
            name = "Push-Ups",
            muscleCategory = MuscleCategory.CHEST,
            description = "Standard push-up position, lower body until chest nearly touches floor, push back up."
        ),
        Exercise(
            id = "ex_chest_5",
            name = "Dumbbell Flyes",
            muscleCategory = MuscleCategory.CHEST,
            description = "Lie on flat bench, extend arms above chest, lower dumbbells in arc motion to sides."
        ),

        // BACK
        Exercise(
            id = "ex_back_1",
            name = "Deadlift",
            muscleCategory = MuscleCategory.BACK,
            description = "Stand with feet hip-width, grip barbell, lift by extending hips and knees simultaneously."
        ),
        Exercise(
            id = "ex_back_2",
            name = "Pull-Ups",
            muscleCategory = MuscleCategory.BACK,
            description = "Hang from bar with overhand grip, pull body up until chin clears bar."
        ),
        Exercise(
            id = "ex_back_3",
            name = "Barbell Row",
            muscleCategory = MuscleCategory.BACK,
            description = "Bend at hips, grip barbell, pull to lower chest while keeping back straight."
        ),
        Exercise(
            id = "ex_back_4",
            name = "Lat Pulldown",
            muscleCategory = MuscleCategory.BACK,
            description = "Sit at machine, grip bar wide, pull down to upper chest while squeezing lats."
        ),
        Exercise(
            id = "ex_back_5",
            name = "Seated Cable Row",
            muscleCategory = MuscleCategory.BACK,
            description = "Sit at cable machine, pull handle to lower chest, squeeze shoulder blades together."
        ),

        // SHOULDERS
        Exercise(
            id = "ex_shoulders_1",
            name = "Overhead Press",
            muscleCategory = MuscleCategory.SHOULDERS,
            description = "Stand with barbell at shoulder level, press overhead until arms are fully extended."
        ),
        Exercise(
            id = "ex_shoulders_2",
            name = "Lateral Raises",
            muscleCategory = MuscleCategory.SHOULDERS,
            description = "Hold dumbbells at sides, raise arms laterally until parallel to floor."
        ),
        Exercise(
            id = "ex_shoulders_3",
            name = "Front Raises",
            muscleCategory = MuscleCategory.SHOULDERS,
            description = "Hold dumbbells in front of thighs, raise one arm at a time to shoulder height."
        ),
        Exercise(
            id = "ex_shoulders_4",
            name = "Face Pulls",
            muscleCategory = MuscleCategory.SHOULDERS,
            description = "Pull rope attachment toward face, separating hands and squeezing rear delts."
        ),
        Exercise(
            id = "ex_shoulders_5",
            name = "Arnold Press",
            muscleCategory = MuscleCategory.SHOULDERS,
            description = "Start with dumbbells in front of shoulders, rotate and press overhead."
        ),

        // BICEPS
        Exercise(
            id = "ex_biceps_1",
            name = "Barbell Curl",
            muscleCategory = MuscleCategory.BICEPS,
            description = "Stand with barbell, curl weight up by flexing elbows, keeping upper arms stationary."
        ),
        Exercise(
            id = "ex_biceps_2",
            name = "Dumbbell Curl",
            muscleCategory = MuscleCategory.BICEPS,
            description = "Alternating or simultaneous curls with dumbbells, supinate wrists at top."
        ),
        Exercise(
            id = "ex_biceps_3",
            name = "Hammer Curl",
            muscleCategory = MuscleCategory.BICEPS,
            description = "Curl dumbbells with neutral grip (palms facing each other) throughout movement."
        ),
        Exercise(
            id = "ex_biceps_4",
            name = "Preacher Curl",
            muscleCategory = MuscleCategory.BICEPS,
            description = "Rest upper arms on preacher bench, curl weight up focusing on bicep contraction."
        ),
        Exercise(
            id = "ex_biceps_5",
            name = "Cable Curl",
            muscleCategory = MuscleCategory.BICEPS,
            description = "Stand facing cable machine, curl bar up maintaining constant tension."
        ),

        // TRICEPS
        Exercise(
            id = "ex_triceps_1",
            name = "Tricep Pushdown",
            muscleCategory = MuscleCategory.TRICEPS,
            description = "At cable machine, push bar down by extending elbows, keeping upper arms at sides."
        ),
        Exercise(
            id = "ex_triceps_2",
            name = "Skull Crushers",
            muscleCategory = MuscleCategory.TRICEPS,
            description = "Lie on bench, lower barbell toward forehead by bending elbows, extend back up."
        ),
        Exercise(
            id = "ex_triceps_3",
            name = "Overhead Tricep Extension",
            muscleCategory = MuscleCategory.TRICEPS,
            description = "Hold dumbbell overhead with both hands, lower behind head, extend back up."
        ),
        Exercise(
            id = "ex_triceps_4",
            name = "Close-Grip Bench Press",
            muscleCategory = MuscleCategory.TRICEPS,
            description = "Bench press with hands closer than shoulder-width to emphasize triceps."
        ),
        Exercise(
            id = "ex_triceps_5",
            name = "Tricep Dips",
            muscleCategory = MuscleCategory.TRICEPS,
            description = "Support body on parallel bars, lower by bending elbows, push back up."
        ),

        // LEGS
        Exercise(
            id = "ex_legs_1",
            name = "Barbell Squat",
            muscleCategory = MuscleCategory.LEGS,
            description = "Bar on upper back, squat down until thighs are parallel to floor, stand back up."
        ),
        Exercise(
            id = "ex_legs_2",
            name = "Leg Press",
            muscleCategory = MuscleCategory.LEGS,
            description = "Sit in leg press machine, push platform away by extending legs."
        ),
        Exercise(
            id = "ex_legs_3",
            name = "Romanian Deadlift",
            muscleCategory = MuscleCategory.LEGS,
            description = "Hold barbell, hinge at hips lowering weight along legs, keep back straight."
        ),
        Exercise(
            id = "ex_legs_4",
            name = "Leg Curl",
            muscleCategory = MuscleCategory.LEGS,
            description = "Lie face down on machine, curl weight up by flexing knees."
        ),
        Exercise(
            id = "ex_legs_5",
            name = "Leg Extension",
            muscleCategory = MuscleCategory.LEGS,
            description = "Sit in machine, extend legs by straightening knees against resistance."
        ),
        Exercise(
            id = "ex_legs_6",
            name = "Calf Raises",
            muscleCategory = MuscleCategory.LEGS,
            description = "Stand on edge of platform, raise heels as high as possible, lower and repeat."
        ),
        Exercise(
            id = "ex_legs_7",
            name = "Lunges",
            muscleCategory = MuscleCategory.LEGS,
            description = "Step forward, lower back knee toward floor, push back to starting position."
        ),

        // CORE
        Exercise(
            id = "ex_core_1",
            name = "Plank",
            muscleCategory = MuscleCategory.CORE,
            description = "Hold push-up position on forearms, keep body straight, engage core."
        ),
        Exercise(
            id = "ex_core_2",
            name = "Crunches",
            muscleCategory = MuscleCategory.CORE,
            description = "Lie on back, knees bent, curl shoulders toward hips by contracting abs."
        ),
        Exercise(
            id = "ex_core_3",
            name = "Russian Twist",
            muscleCategory = MuscleCategory.CORE,
            description = "Sit with knees bent, lean back slightly, rotate torso side to side."
        ),
        Exercise(
            id = "ex_core_4",
            name = "Leg Raises",
            muscleCategory = MuscleCategory.CORE,
            description = "Lie on back, raise legs to vertical position, lower slowly without touching floor."
        ),
        Exercise(
            id = "ex_core_5",
            name = "Cable Woodchop",
            muscleCategory = MuscleCategory.CORE,
            description = "Pull cable diagonally across body from high to low or low to high."
        ),

        // CARDIO
        Exercise(
            id = "ex_cardio_1",
            name = "Treadmill Running",
            muscleCategory = MuscleCategory.CARDIO,
            description = "Run on treadmill at desired speed and incline for cardiovascular conditioning."
        ),
        Exercise(
            id = "ex_cardio_2",
            name = "Stationary Bike",
            muscleCategory = MuscleCategory.CARDIO,
            description = "Cycle on stationary bike at moderate to high intensity."
        ),
        Exercise(
            id = "ex_cardio_3",
            name = "Rowing Machine",
            muscleCategory = MuscleCategory.CARDIO,
            description = "Full body cardio exercise using rowing motion for endurance training."
        ),
        Exercise(
            id = "ex_cardio_4",
            name = "Jump Rope",
            muscleCategory = MuscleCategory.CARDIO,
            description = "Skip rope continuously for cardiovascular and coordination training."
        ),
        Exercise(
            id = "ex_cardio_5",
            name = "Burpees",
            muscleCategory = MuscleCategory.CARDIO,
            description = "Full body exercise: squat, jump back to plank, push-up, jump forward, jump up."
        )
    )

    fun getExercisesByCategory(category: MuscleCategory): List<Exercise> {
        return exercises.filter { it.muscleCategory == category }
    }

    fun getExerciseById(id: String): Exercise? {
        return exercises.find { it.id == id }
    }

    fun searchExercises(query: String): List<Exercise> {
        return exercises.filter {
            it.name.contains(query, ignoreCase = true) ||
            it.muscleCategory.displayName.contains(query, ignoreCase = true)
        }
    }
}
