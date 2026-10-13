package com.diajarkoding.imfit.presentation.ui.progress

import com.diajarkoding.imfit.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ProgressPresentationTest {
    private fun log(start: Instant, volume: Float = 100f): WorkoutLog = WorkoutLog(
        start.toString(), "user", "workout", start.toEpochMilli(), start.toEpochMilli(), start.plusSeconds(3600).toEpochMilli(), volume,
        listOf(ExerciseLog(Exercise("ex", "Exercise", MuscleCategory.CHEST, ""), listOf(WorkoutSet(1, 10f, 10, true), WorkoutSet(2, 10f, 10, false)))))

    @Test fun mondayBoundaryMonthAndCompletedOnly() {
        val zone = ZoneId.of("Asia/Jakarta")
        val now = ZonedDateTime.of(2026, 10, 13, 12, 0, 0, 0, zone).toInstant()
        val monday = LocalDate.of(2026, 10, 12).atStartOfDay(zone).toInstant()
        val september = LocalDate.of(2026, 9, 30).atStartOfDay(zone).toInstant()
        val result = progressMetrics(listOf(log(monday), log(monday.minusSeconds(1)), log(september)), now, zone)
        assertEquals(300.0, result.totalVolume, 0.0)
        assertEquals(60, result.weeklyMinutes)
        assertEquals(2, result.monthlyWorkouts)
        assertEquals(2, result.monthlyCompletedSets)
    }

    @Test fun futureStartsDoNotEnterWeeklyWindow() {
        val now = Instant.parse("2026-10-13T00:00:00Z")
        assertEquals(0, progressMetrics(listOf(log(now.plusSeconds(1))), now, ZoneOffset.UTC).weeklyMinutes)
    }

    @Test fun emptyLogsAreRealZero() {
        assertEquals(ProgressMetrics(0.0, 0, 0, 0), progressMetrics(emptyList(), Instant.EPOCH, ZoneOffset.UTC))
    }

    @Test fun octoberThursdayAndLeapYearGrid() {
        val october = monthDays(YearMonth.of(2026, 10))
        assertEquals(listOf(null, null, null, 1), october.take(4))
        assertEquals((1..31).toList(), october.filterNotNull())
        assertEquals(0, october.size % 7)
        assertEquals(29, monthDays(YearMonth.of(2024, 2)).filterNotNull().size)
        assertEquals(YearMonth.of(2027, 1), YearMonth.of(2026, 12).plusMonths(1))
    }
}
