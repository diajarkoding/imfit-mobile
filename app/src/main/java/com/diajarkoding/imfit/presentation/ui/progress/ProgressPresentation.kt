package com.diajarkoding.imfit.presentation.ui.progress

import com.diajarkoding.imfit.domain.model.WorkoutLog
import java.time.DayOfWeek
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

data class ProgressMetrics(val totalVolume: Double, val weeklyMinutes: Int, val monthlyWorkouts: Int, val monthlyCompletedSets: Int)

/** Presentation-only windows; persisted duration semantics are deliberately unchanged. */
internal fun progressMetrics(logs: List<WorkoutLog>, now: Instant, zone: ZoneId): ProgressMetrics {
    val today = now.atZone(zone).toLocalDate()
    val month = YearMonth.from(today)
    val weekStart = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant()
    val monthly = logs.filter { YearMonth.from(Instant.ofEpochMilli(it.startTime).atZone(zone)) == month }
    val weekly = logs.filter { val start = Instant.ofEpochMilli(it.startTime); !start.isBefore(weekStart) && !start.isAfter(now) }
    return ProgressMetrics(logs.sumOf { it.totalVolume.toDouble() }, weekly.sumOf { it.durationMinutes }, monthly.size,
        monthly.sumOf { log -> log.exerciseLogs.sumOf { exercise -> exercise.sets.count { it.isCompleted } } })
}

/** Monday-first grid, padded to complete weeks, with no fabricated adjacent dates. */
internal fun monthDays(month: YearMonth): List<Int?> {
    val days = MutableList<Int?>(month.atDay(1).dayOfWeek.value - 1) { null }
    days.addAll((1..month.lengthOfMonth()).toList())
    while (days.size % 7 != 0) days.add(null)
    return days
}
