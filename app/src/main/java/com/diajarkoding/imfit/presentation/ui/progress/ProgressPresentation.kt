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
    val monthlyAdditionalVolume: Double = 0.0,
    val monthlyRepetitions: Int = 0,
    val monthlyUnverifiedWorkouts: Int = 0
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
    // Quality belongs to each measurement: a mixed legacy session can contain verified typed children.
    val exercises = monthly.flatMap { it.exerciseLogs }.filter { it.metricQuality == "VERIFIED" }
    val holdDuration = exercises.filter { it.modality == ExerciseModality.ISOMETRIC }.sumOf { it.totalDurationSeconds }
    val cardio = exercises.filter { it.modality == ExerciseModality.CARDIO || it.modality == ExerciseModality.CONDITIONING }
    val cardioDuration = cardio.sumOf { it.totalDurationSeconds }
    val cardioDistance = cardio.sumOf { it.totalDistanceMeters }
    val volume = monthly.sumOf { log ->
        if (log.dataQuality == "VERIFIED" && log.exerciseLogs.all { it.metricQuality == "VERIFIED" }) log.totalVolume.toDouble()
        else log.exerciseLogs.filter { it.metricQuality == "VERIFIED" }.sumOf { it.totalVolume.toDouble() }
    }
    val addVol = monthly.sumOf { log ->
        if (log.dataQuality == "VERIFIED" && log.exerciseLogs.all { it.metricQuality == "VERIFIED" }) log.additionalVolume.toDouble()
        else log.exerciseLogs.filter { it.metricQuality == "VERIFIED" }.sumOf { it.additionalVolume.toDouble() }
    }

    return ProgressMetrics(
        month = month,
        monthlyVolume = volume,
        monthlyMinutes = monthly.sumOf { it.durationMinutes },
        monthlyWorkouts = monthly.size,
        monthlyCompletedSets = monthly.sumOf { log -> log.exerciseLogs.sumOf { exercise -> exercise.sets.count { it.isCompleted } } },
        monthlyHoldDurationSeconds = holdDuration,
        monthlyCardioDurationSeconds = cardioDuration,
        monthlyCardioDistanceMeters = cardioDistance,
        monthlyAdditionalVolume = addVol,
        monthlyRepetitions = exercises.sumOf { it.totalReps },
        monthlyUnverifiedWorkouts = monthly.count { log ->
            log.dataQuality != "VERIFIED" || log.exerciseLogs.any { it.metricQuality != "VERIFIED" }
        }
    )
}

/** Monday-first grid, padded to complete weeks, with no fabricated adjacent dates. */
internal fun monthDays(month: YearMonth): List<Int?> {
    val days = MutableList<Int?>(month.atDay(1).dayOfWeek.value - 1) { null }
    days.addAll((1..month.lengthOfMonth()).toList())
    while (days.size % 7 != 0) days.add(null)
    return days
}
