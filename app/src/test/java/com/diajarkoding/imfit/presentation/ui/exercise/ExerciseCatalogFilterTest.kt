package com.diajarkoding.imfit.presentation.ui.exercise

import com.diajarkoding.imfit.data.local.dao.roomQuery
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import org.junit.Assert.*
import org.junit.Test

class ExerciseCatalogFilterTest {
    @Test fun listCategoryFilterPreservesTheExistingVerifiedLegsSubgroupRules() {
        val glute = Exercise("ex_legs_3", "Romanian deadlift", MuscleCategory.LEGS, "")
        val calf = Exercise("ex_legs_6", "Calf raise", MuscleCategory.LEGS, "")
        assertTrue(ExerciseCategory.LEGS.catalogFilter("").includes(glute))
        assertFalse(ExerciseCategory.LEGS.catalogFilter("").includes(calf))
        assertTrue(ExerciseCategory.GLUTES.catalogFilter("").includes(glute))
        assertTrue(ExerciseCategory.CALVES.catalogFilter("").includes(calf))
    }

    @Test fun queryIsBoundAsLiteralRatherThanInterpolatedAndOrderIsDeterministic() {
        val query = ExerciseCategory.ALL.catalogFilter("50%_'\\").roomQuery()
        assertFalse(query.sql.contains("50%"))
        assertTrue(query.sql.contains("is_active = 1"))
        assertTrue(query.sql.contains("ESCAPE"))
        assertTrue(query.sql.endsWith("ORDER BY id COLLATE BINARY ASC"))
        assertEquals(1, query.argCount)
    }
}
