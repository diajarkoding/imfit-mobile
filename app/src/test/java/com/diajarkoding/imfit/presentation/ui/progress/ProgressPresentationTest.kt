package com.diajarkoding.imfit.presentation.ui.progress

import com.diajarkoding.imfit.domain.model.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ProgressPresentationTest {
    private fun log(start: Instant, volume: Float = 100f): WorkoutLog = WorkoutLog(
        start.toString(), "user", "workout", start.toEpochMilli(), start.toEpochMilli(), start.plusSeconds(3600).toEpochMilli(), volume,
        listOf(ExerciseLog(Exercise("ex", "Exercise", MuscleCategory.CHEST, ""), listOf(WorkoutSet(1, 10f, 10, true), WorkoutSet(2, 10f, 10, false)))))

    @Test fun volumeAndDurationUseTheSameCalendarMonthRatherThanWeekOrAllTime() {
        val zone = ZoneId.of("Asia/Jakarta")
        val now = ZonedDateTime.of(2026, 10, 13, 12, 0, 0, 0, zone).toInstant()
        val monday = LocalDate.of(2026, 10, 12).atStartOfDay(zone).toInstant()
        val september = LocalDate.of(2026, 9, 30).atStartOfDay(zone).toInstant()
        val result = progressMetrics(listOf(log(monday), log(monday.minusSeconds(1)), log(september)), now, zone)
        assertEquals(YearMonth.of(2026, 10), result.month)
        assertEquals(200.0, result.monthlyVolume, 0.0)
        assertEquals(120, result.monthlyMinutes)
        assertEquals(2, result.monthlyWorkouts)
        assertEquals(2, result.monthlyCompletedSets)
    }

    @Test fun futureStartsDoNotEnterMonthlyMetrics() {
        val now = Instant.parse("2026-10-13T00:00:00Z")
        val result = progressMetrics(listOf(log(now.plusSeconds(1))), now, ZoneOffset.UTC)
        assertEquals(0, result.monthlyMinutes)
        assertEquals(0.0, result.monthlyVolume, 0.0)
    }

    @Test fun emptyLogsAreRealZero() {
        assertEquals(ProgressMetrics(YearMonth.of(1970, 1), 0.0, 0, 0, 0), progressMetrics(emptyList(), Instant.EPOCH, ZoneOffset.UTC))
    }

    @Test fun explicitDisplayedMonthUsesLocalTimeAndExclusiveNextMonthBoundary() {
        val zone = ZoneId.of("Asia/Jakarta")
        val month = YearMonth.of(2026, 10)
        val start = month.atDay(1).atStartOfDay(zone).toInstant()
        val next = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
        val result = progressMetrics(listOf(log(start, 25f), log(start.minusMillis(1), 100f), log(next, 200f)),
            Instant.parse("2026-11-02T12:00:00Z"), zone, month)
        assertEquals(25.0, result.monthlyVolume, 0.0)
        assertEquals(60, result.monthlyMinutes)
        assertEquals(1, result.monthlyWorkouts)
    }

    @Test fun sessionsCrossingMidnightBelongToTheirStartMonthAcrossYearBoundary() {
        val zone = ZoneOffset.UTC
        val december = log(Instant.parse("2026-12-31T23:30:00Z"), 10f)
        val january = log(Instant.parse("2027-01-01T01:00:00Z"), 20f)
        val now = Instant.parse("2027-01-02T12:00:00Z")
        val decemberMetrics = progressMetrics(listOf(december, january), now, zone, YearMonth.of(2026, 12))
        val januaryMetrics = progressMetrics(listOf(december, january), now, zone)
        assertEquals(10.0, decemberMetrics.monthlyVolume, 0.0)
        assertEquals(20.0, januaryMetrics.monthlyVolume, 0.0)
        assertEquals(60, decemberMetrics.monthlyMinutes)
        assertEquals(60, januaryMetrics.monthlyMinutes)
    }

    @Test fun multipleSessionsOnOneDateAreCountedOncePerIdAndMonthRolloverResetsMetrics() {
        val first = log(Instant.parse("2026-10-13T08:00:00Z"), 50f)
        val second = log(Instant.parse("2026-10-13T10:00:00Z"), 75f)
        val logs = listOf(first, second, first)
        val october = progressMetrics(logs, Instant.parse("2026-10-14T00:00:00Z"), ZoneOffset.UTC)
        assertEquals(125.0, october.monthlyVolume, 0.0)
        assertEquals(120, october.monthlyMinutes)
        assertEquals(2, october.monthlyWorkouts)
        val november = progressMetrics(logs, Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC)
        assertEquals(0.0, november.monthlyVolume, 0.0)
        assertEquals(0, november.monthlyMinutes)
    }

    @Test fun octoberThursdayAndLeapYearGrid() {
        val october = monthDays(YearMonth.of(2026, 10))
        assertEquals(listOf(null, null, null, 1), october.take(4))
        assertEquals((1..31).toList(), october.filterNotNull())
        assertEquals(0, october.size % 7)
        assertEquals(29, monthDays(YearMonth.of(2024, 2)).filterNotNull().size)
        assertEquals(YearMonth.of(2027, 1), YearMonth.of(2026, 12).plusMonths(1))
    }

    @Test fun sixRowMonthKeepsAllDatesAndTrailingEmptyCells() {
        val grid = monthDays(YearMonth.of(2026, 3))
        assertEquals(42, grid.size)
        assertEquals(6, grid.takeWhile { it == null }.size)
        assertEquals((1..31).toList(), grid.filterNotNull())
        assertEquals(5, grid.takeLastWhile { it == null }.size)
    }
}
