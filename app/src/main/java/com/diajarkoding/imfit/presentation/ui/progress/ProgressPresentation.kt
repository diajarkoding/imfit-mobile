package com.diajarkoding.imfit.presentation.ui.progress

import com.diajarkoding.imfit.domain.model.ExerciseModality
import com.diajarkoding.imfit.domain.model.WorkoutLog
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

data class ProgressMetrics(
    val month: YearMonth,
    val monthlyVolume: Double,
    val monthlyMinutes: Int,
    val monthlyWorkouts: Int,
    val monthlyCompletedSets: Int,
    val monthlyHoldDurationSeconds: Int = 0,
    val monthlyCardioDurationSeconds: Int = 0,
    val monthlyCardioDistanceMeters: Int = 0,
    val monthlyAdditionalVolume: Double = 0.0
)

/** Presentation-only windows; persisted duration semantics are deliberately unchanged. */
internal fun progressMetrics(
    logs: List<WorkoutLog>, now: Instant, zone: ZoneId,
    month: YearMonth = YearMonth.from(now.atZone(zone))
): ProgressMetrics {
    val start = month.atDay(1).atStartOfDay(zone).toInstant()
    val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
    val monthly = logs.distinctBy { it.id }.filter { log ->
        val sessionStart = Instant.ofEpochMilli(log.startTime)
        !sessionStart.isBefore(start) && sessionStart.isBefore(end) && !sessionStart.isAfter(now)
    }
    val holdDuration = monthly.sumOf { log ->
        log.exerciseLogs.filter { it.modality == ExerciseModality.ISOMETRIC }.sumOf { it.totalDurationSeconds }
    }
    val cardioDuration = monthly.sumOf { log ->
        log.exerciseLogs.filter { it.modality in listOf(ExerciseModality.CARDIO, ExerciseModality.CONDITIONING) }.sumOf { it.totalDurationSeconds }
    }
    val cardioDistance = monthly.sumOf { it.totalDistanceMeters }
    val addVol = monthly.sumOf { it.additionalVolume.toDouble() }

    return ProgressMetrics(
        month = month,
        monthlyVolume = monthly.sumOf { it.totalVolume.toDouble() },
        monthlyMinutes = monthly.sumOf { it.durationMinutes },
        monthlyWorkouts = monthly.size,
        monthlyCompletedSets = monthly.sumOf { log -> log.exerciseLogs.sumOf { exercise -> exercise.sets.count { it.isCompleted } } },
        monthlyHoldDurationSeconds = holdDuration,
        monthlyCardioDurationSeconds = cardioDuration,
        monthlyCardioDistanceMeters = cardioDistance,
        monthlyAdditionalVolume = addVol
    )
}

/** Monday-first grid, padded to complete weeks, with no fabricated adjacent dates. */
internal fun monthDays(month: YearMonth): List<Int?> {
    val days = MutableList<Int?>(month.atDay(1).dayOfWeek.value - 1) { null }
    days.addAll((1..month.lengthOfMonth()).toList())
    while (days.size % 7 != 0) days.add(null)
    return days
}
