package com.diajarkoding.imfit.domain.model

import org.junit.Assert.*
import org.junit.Test

class RoutineEstimateTest {
    private fun exercise(id: String, type: TrackingType, modality: ExerciseModality) =
        Exercise(id, id, MuscleCategory.CORE, "d", defaultTrackingType = type, modality = modality)
    private fun v3(id: String, vararg rows: Pair<Int, Int>) = TemplateExercise(
        exercise(id, TrackingType.DURATION, ExerciseModality.ISOMETRIC), trackingType = TrackingType.DURATION,
        setTargets = rows.mapIndexed { i, (d, r) -> SetTarget("$id-$i", i + 1, durationSeconds = d, restSeconds = r) },
        measurementVersion = 3)

    @Test fun durationOnlyRoutineUsesTargetsAndExcludesTheLastRowRest() {
        // 60/90/60 s with rests 60/60/120 → 60+90+60 + 60+60 = 330 s → 6 minutes (PLAN §7.1).
        assertEquals(6, estimateRoutineDurationMinutes(listOf(v3("a", 60 to 60, 90 to 60, 60 to 120))))
    }

    @Test fun restRunsAcrossExerciseBoundaries() {
        // a: 60 s (rest 30) | b: 60 s (rest 600, last, excluded) → 60 + 30 + 60 = 150 s → 3 min.
        assertEquals(3, estimateRoutineDurationMinutes(listOf(v3("a", 60 to 30), v3("b", 60 to 600))))
    }

    @Test fun legacyScalarDurationRoutineExpandsToRows() {
        val legacy = TemplateExercise(exercise("p", TrackingType.DURATION, ExerciseModality.ISOMETRIC), sets = 3, reps = null,
            restSeconds = 30, trackingType = TrackingType.DURATION, targetDurationSeconds = 60)
        assertEquals(4, estimateRoutineDurationMinutes(listOf(legacy))) // 3×60 + 2×30 = 240 s
    }

    @Test fun strengthMixedUnknownOrEmptyRoutinesHaveNoEstimate() {
        val strength = TemplateExercise(exercise("s", TrackingType.WEIGHT_REPS, ExerciseModality.STRENGTH), sets = 3, reps = 8)
        assertNull(estimateRoutineDurationMinutes(listOf(strength)))
        assertNull(estimateRoutineDurationMinutes(listOf(v3("a", 60 to 60), strength))) // mixed: no partial subtotal
        assertNull(estimateRoutineDurationMinutes(emptyList()))
        val missing = TemplateExercise(exercise("p", TrackingType.DURATION, ExerciseModality.ISOMETRIC), sets = 2,
            trackingType = TrackingType.DURATION, targetDurationSeconds = null)
        assertNull(estimateRoutineDurationMinutes(listOf(missing))) // unknown is not forced to 0
    }
}
