package com.diajarkoding.imfit.presentation.ui.progress

import com.diajarkoding.imfit.domain.model.WorkoutLog
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal sealed interface WorkoutHistoryPresentation {
    data object Loading : WorkoutHistoryPresentation
    data object Empty : WorkoutHistoryPresentation
    data object FullError : WorkoutHistoryPresentation
    data class Content(
        val workouts: List<WorkoutLog>,
        val showUpdateFailureBanner: Boolean
    ) : WorkoutHistoryPresentation
}

internal data class WorkoutHistoryTotals(
    val sessionCount: Int,
    val durationMinutes: Int,
    val totalVolume: Float,
    val totalExercises: Int,
    val completedSets: Int
)

internal fun workoutHistoryPresentation(
    workoutsForDate: List<WorkoutLog>,
    isLoading: Boolean,
    hasError: Boolean
): WorkoutHistoryPresentation {
    return when {
        workoutsForDate.isNotEmpty() -> WorkoutHistoryPresentation.Content(
            workouts = workoutsForDate,
            showUpdateFailureBanner = hasError
        )
        hasError -> WorkoutHistoryPresentation.FullError
        isLoading -> WorkoutHistoryPresentation.Loading
        else -> WorkoutHistoryPresentation.Empty
    }
}

internal fun calculateWorkoutHistoryTotals(workouts: List<WorkoutLog>): WorkoutHistoryTotals {
    val totalDuration = workouts.sumOf { it.durationMinutes }
    val totalVolume = workouts.sumOf { it.totalVolume.toDouble() }.toFloat()
    // Gerakan = total exercise log entries across sessions (per design contract handoff 01)
    val totalExercises = workouts.sumOf { it.exerciseLogs.size }
    val totalSets = workouts.sumOf { log -> log.exerciseLogs.sumOf { it.completedSets } }
    return WorkoutHistoryTotals(
        sessionCount = workouts.size,
        durationMinutes = totalDuration,
        totalVolume = totalVolume,
        totalExercises = totalExercises,
        completedSets = totalSets
    )
}

internal fun formatVolume(volume: Float, locale: Locale): String {
    val formatter = NumberFormat.getIntegerInstance(locale)
    return "${formatter.format(volume.roundToInt())} kg"
}

internal fun formatHeaderWeekday(date: LocalDate, locale: Locale): String {
    return date.format(DateTimeFormatter.ofPattern("EEEE", locale))
}

internal fun formatHeaderFullDate(date: LocalDate, locale: Locale): String {
    return date.format(DateTimeFormatter.ofPattern("d MMMM yyyy", locale))
}
