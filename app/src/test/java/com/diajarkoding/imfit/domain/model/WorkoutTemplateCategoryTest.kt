package com.diajarkoding.imfit.domain.model

import com.diajarkoding.imfit.presentation.components.common.categoryArtAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkoutTemplateCategoryTest {

    private fun exercise(id: String, category: MuscleCategory) =
        Exercise(id, id, category, "")

    private fun item(id: String, category: MuscleCategory, sets: Int) =
        TemplateExercise(exercise(id, category), sets = sets, reps = 10, restSeconds = 60)

    @Test
    fun emptyRoutineReturnsNeutralArtwork() {
        val template = WorkoutTemplate("empty", "user", "Empty Routine", emptyList())
        assertNull(template.primaryMuscleCategory)
        assertEquals("all-muscles.svg", categoryArtAsset(template.primaryMuscleCategory))
    }

    @Test
    fun chestDayReturnsChestArtwork() {
        val exercises = listOf(
            item("bench", MuscleCategory.CHEST, sets = 4),
            item("incline", MuscleCategory.CHEST, sets = 3),
            item("triceps", MuscleCategory.TRICEPS, sets = 2)
        )
        val template = WorkoutTemplate("chest_day", "user", "Chest Day", exercises)
        assertEquals(MuscleCategory.CHEST, template.primaryMuscleCategory)
        assertEquals("chest.svg", categoryArtAsset(template.primaryMuscleCategory))
    }

    @Test
    fun pushDayReturnsChestArtwork() {
        val exercises = listOf(
            item("bench", MuscleCategory.CHEST, sets = 4),
            item("ohp", MuscleCategory.SHOULDERS, sets = 3),
            item("pushdown", MuscleCategory.TRICEPS, sets = 2)
        )
        val template = WorkoutTemplate("push_day", "user", "Push Day", exercises)
        assertEquals(MuscleCategory.CHEST, template.primaryMuscleCategory)
        assertEquals("chest.svg", categoryArtAsset(template.primaryMuscleCategory))
    }

    @Test
    fun pullDayReturnsBackArtwork() {
        val exercises = listOf(
            item("row", MuscleCategory.BACK, sets = 4),
            item("pulldown", MuscleCategory.BACK, sets = 3),
            item("curl", MuscleCategory.BICEPS, sets = 3)
        )
        val template = WorkoutTemplate("pull_day", "user", "Pull Day", exercises)
        assertEquals(MuscleCategory.BACK, template.primaryMuscleCategory)
        assertEquals("back.svg", categoryArtAsset(template.primaryMuscleCategory))
    }

    @Test
    fun legDayReturnsLegsArtwork() {
        val exercises = listOf(
            item("squat", MuscleCategory.LEGS, sets = 4),
            item("press", MuscleCategory.LEGS, sets = 3),
            item("curl", MuscleCategory.LEGS, sets = 3)
        )
        val template = WorkoutTemplate("leg_day", "user", "Leg Day", exercises)
        assertEquals(MuscleCategory.LEGS, template.primaryMuscleCategory)
        assertEquals("upper-legs.svg", categoryArtAsset(template.primaryMuscleCategory))
    }

    @Test
    fun fullBodyCombinationReturnsNeutralArtwork() {
        val exercises = listOf(
            item("bench", MuscleCategory.CHEST, sets = 3),
            item("row", MuscleCategory.BACK, sets = 3),
            item("squat", MuscleCategory.LEGS, sets = 3),
            item("curl", MuscleCategory.LEGS, sets = 2)
        )
        val template = WorkoutTemplate("full_body", "user", "Full Body", exercises)
        assertNull(template.primaryMuscleCategory)
        assertEquals("all-muscles.svg", categoryArtAsset(template.primaryMuscleCategory))
    }

    @Test
    fun warmupCardioDoesNotDominateLiftingFocus() {
        val exercises = listOf(
            item("treadmill", MuscleCategory.CARDIO, sets = 1),
            item("squat", MuscleCategory.LEGS, sets = 4),
            item("lunges", MuscleCategory.LEGS, sets = 3),
            item("press", MuscleCategory.LEGS, sets = 3)
        )
        val template = WorkoutTemplate("legs_with_warmup", "user", "Legs with Warmup", exercises)
        assertEquals(MuscleCategory.LEGS, template.primaryMuscleCategory)
        assertEquals("upper-legs.svg", categoryArtAsset(template.primaryMuscleCategory))
    }

    @Test
    fun cardioOnlyReturnsCardioArtwork() {
        val exercises = listOf(
            item("treadmill", MuscleCategory.CARDIO, sets = 1),
            item("bike", MuscleCategory.CARDIO, sets = 1)
        )
        val template = WorkoutTemplate("cardio_only", "user", "Cardio Only", exercises)
        assertEquals(MuscleCategory.CARDIO, template.primaryMuscleCategory)
        assertEquals("cardio.svg", categoryArtAsset(template.primaryMuscleCategory))
    }

    @Test
    fun explicitRpcOverrideTakesPrecedence() {
        val template = WorkoutTemplate(
            id = "rpc_summary",
            userId = "user",
            name = "RPC Summary",
            exercises = emptyList(),
            explicitPrimaryCategory = MuscleCategory.BACK,
            exerciseCountOverride = 4
        )
        assertEquals(MuscleCategory.BACK, template.primaryMuscleCategory)
        assertEquals("back.svg", categoryArtAsset(template.primaryMuscleCategory))
        assertEquals(4, template.exerciseCount)
    }
}
