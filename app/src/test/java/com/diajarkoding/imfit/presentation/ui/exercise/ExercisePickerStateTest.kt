package com.diajarkoding.imfit.presentation.ui.exercise

import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExercisePickerStateTest {
    private val bench = Exercise("bench", "Bench Press", MuscleCategory.CHEST, "")
    private val row = Exercise("row", "Barbell Row", MuscleCategory.BACK, "")
    private fun state() = ExerciseSelectionState(allExercises = listOf(bench, row), isLoading = false).filtered()

    @Test fun selectionUsesIdRatherThanObjectEquality() {
        val selected = state().toggled(bench)
        assertTrue(selected.toggled(bench.copy(name = "Updated Bench Press")).selectedExercises.isEmpty())
    }

    @Test fun changingFilterKeepsHiddenSelections() {
        val filtered = state().toggled(bench).filtered(category = ExerciseCategory.BACK)
        assertEquals(listOf(row), filtered.filteredExercises)
        assertEquals(listOf(bench), filtered.selectedExercises)
    }

    @Test fun emptySearchResultsDoNotClearSelection() {
        val filtered = state().toggled(bench).toggled(row).filtered(query = "no match")
        assertTrue(filtered.filteredExercises.isEmpty())
        assertEquals(2, filtered.selectedExercises.size)
    }

    @Test fun clearingSearchAndFilterDoesNotResetSelections() {
        val cleared = state().toggled(row).filtered(query = "bench", category = ExerciseCategory.CHEST)
            .filtered(query = "", category = ExerciseCategory.ALL)
        assertEquals(listOf(bench, row), cleared.filteredExercises)
        assertEquals(listOf(row), cleared.selectedExercises)
    }
}
