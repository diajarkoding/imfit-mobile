package com.diajarkoding.imfit.presentation.ui.progress

import com.diajarkoding.imfit.domain.model.WorkoutLog
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

data class ProgressMetrics(
    val month: YearMonth,
    val monthlyVolume: Double,
    val monthlyMinutes: Int,
    val monthlyWorkouts: Int,
    val monthlyCompletedSets: Int
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
    return ProgressMetrics(month, monthly.sumOf { it.totalVolume.toDouble() }, monthly.sumOf { it.durationMinutes }, monthly.size,
        monthly.sumOf { log -> log.exerciseLogs.sumOf { exercise -> exercise.sets.count { it.isCompleted } } })
}

/** Monday-first grid, padded to complete weeks, with no fabricated adjacent dates. */
internal fun monthDays(month: YearMonth): List<Int?> {
    val days = MutableList<Int?>(month.atDay(1).dayOfWeek.value - 1) { null }
    days.addAll((1..month.lengthOfMonth()).toList())
    while (days.size % 7 != 0) days.add(null)
    return days
}
