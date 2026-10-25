package com.diajarkoding.imfit.presentation.ui.progress

import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

class WorkoutHistoryPresentationTest {

    private fun sampleLog(
        id: String,
        templateName: String,
        durationMinutes: Int,
        totalVolume: Float,
        exerciseCount: Int,
        completedSetsPerExercise: Int
    ): WorkoutLog {
        val startTime = 1000000L
        val endTime = startTime + (durationMinutes * 60 * 1000L)
        val exerciseLogs = (1..exerciseCount).map { exIndex ->
            ExerciseLog(
                exercise = Exercise("ex_$exIndex", "Exercise $exIndex", MuscleCategory.CHEST, ""),
                sets = (1..completedSetsPerExercise).map { setIndex ->
                    WorkoutSet(setIndex, totalVolume / (exerciseCount * completedSetsPerExercise), 10, isCompleted = true)
                }
            )
        }
        return WorkoutLog(
            id = id,
            userId = "user_1",
            templateName = templateName,
            date = startTime,
            startTime = startTime,
            endTime = endTime,
            totalVolume = totalVolume,
            exerciseLogs = exerciseLogs
        )
    }

    @Test
    fun initialLoadingWithEmptyWorkoutsReturnsLoading() {
        val presentation = workoutHistoryPresentation(
            workoutsForDate = emptyList(),
            isLoading = true,
            hasError = false
        )
        assertEquals(WorkoutHistoryPresentation.Loading, presentation)
    }

    @Test
    fun failedLoadWithEmptyWorkoutsReturnsFullError() {
        val presentation = workoutHistoryPresentation(
            workoutsForDate = emptyList(),
            isLoading = false,
            hasError = true
        )
        assertEquals(WorkoutHistoryPresentation.FullError, presentation)
    }

    @Test
    fun legitimateEmptyDateWithoutErrorReturnsEmpty() {
        val presentation = workoutHistoryPresentation(
            workoutsForDate = emptyList(),
            isLoading = false,
            hasError = false
        )
        assertEquals(WorkoutHistoryPresentation.Empty, presentation)
    }

    @Test
    fun successfulLoadWithWorkoutsReturnsContentWithoutBanner() {
        val log = sampleLog("1", "Upper Body", 45, 3600f, 3, 3)
        val presentation = workoutHistoryPresentation(
            workoutsForDate = listOf(log),
            isLoading = false,
            hasError = false
        )
        assertTrue(presentation is WorkoutHistoryPresentation.Content)
        val content = presentation as WorkoutHistoryPresentation.Content
        assertEquals(1, content.workouts.size)
        assertFalse(content.showUpdateFailureBanner)
    }

    @Test
    fun reloadingWithExistingWorkoutsRetainsContentWithoutBanner() {
        val log = sampleLog("1", "Upper Body", 45, 3600f, 3, 3)
        val presentation = workoutHistoryPresentation(
            workoutsForDate = listOf(log),
            isLoading = true,
            hasError = false
        )
        assertTrue(presentation is WorkoutHistoryPresentation.Content)
        val content = presentation as WorkoutHistoryPresentation.Content
        assertEquals(1, content.workouts.size)
        assertFalse(content.showUpdateFailureBanner)
    }

    @Test
    fun updateFailureWithExistingWorkoutsShowsContentWithBanner() {
        val log = sampleLog("1", "Upper Body", 45, 3600f, 3, 3)
        val presentation = workoutHistoryPresentation(
            workoutsForDate = listOf(log),
            isLoading = false,
            hasError = true
        )
        assertTrue(presentation is WorkoutHistoryPresentation.Content)
        val content = presentation as WorkoutHistoryPresentation.Content
        assertEquals(1, content.workouts.size)
        assertTrue(content.showUpdateFailureBanner)
    }

    @Test
    fun totalsCalculationMatchesPencilIllustration() {
        // Handoff note 03: 45 + 20 = 65 menit; 3.600 + 1.200 = 4.800 kg; 3 + 2 = 5 gerakan; 9 + 6 = 15 set selesai
        val session1 = sampleLog("1", "Upper Body", 45, 3600f, 3, 3)
        val session2 = sampleLog("2", "Lower Body", 20, 1200f, 2, 3)
        val totals = calculateWorkoutHistoryTotals(listOf(session1, session2))

        assertEquals(2, totals.sessionCount)
        assertEquals(65, totals.durationMinutes)
        assertEquals(4800f, totals.totalVolume, 0.01f)
        assertEquals(5, totals.totalExercises)
        assertEquals(15, totals.completedSets)
    }

    @Test
    fun emptyWorkoutsTotalsAreRealZero() {
        val totals = calculateWorkoutHistoryTotals(emptyList())
        assertEquals(0, totals.sessionCount)
        assertEquals(0, totals.durationMinutes)
        assertEquals(0f, totals.totalVolume, 0.001f)
        assertEquals(0, totals.totalExercises)
        assertEquals(0, totals.completedSets)
    }

    @Test
    fun volumeFormattingIndonesianUsesDot() {
        val formatted = formatVolume(4800f, Locale.forLanguageTag("id-ID"))
        assertEquals("4.800 kg", formatted)
    }

    @Test
    fun volumeFormattingEnglishUsesComma() {
        val formatted = formatVolume(4800f, Locale.ENGLISH)
        assertEquals("4,800 kg", formatted)
    }

    @Test
    fun dateHeaderFormattingIndonesian() {
        val date = LocalDate.of(2026, 10, 16)
        val locale = Locale.forLanguageTag("id-ID")
        assertEquals("Jumat", formatHeaderWeekday(date, locale))
        assertEquals("16 Oktober 2026", formatHeaderFullDate(date, locale))
    }

    @Test
    fun dateHeaderFormattingEnglish() {
        val date = LocalDate.of(2026, 10, 16)
        val locale = Locale.ENGLISH
        assertEquals("Friday", formatHeaderWeekday(date, locale))
        assertEquals("16 October 2026", formatHeaderFullDate(date, locale))
    }
}
