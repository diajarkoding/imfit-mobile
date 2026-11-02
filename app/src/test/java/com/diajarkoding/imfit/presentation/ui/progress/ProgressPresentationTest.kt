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

    @Test fun typedMetricsKeepExternalAdditionalHoldCardioAndRepetitionsSeparate() {
        val start = Instant.parse("2026-10-13T08:00:00Z")
        val exercises = listOf(
            ExerciseLog(Exercise("ex_chest_1", "Bench", MuscleCategory.CHEST, ""),
                listOf(WorkoutSet(1, weight = 50f, reps = 10, isCompleted = true))),
            ExerciseLog(Exercise("ex_chest_4", "Push up", MuscleCategory.CHEST, "", defaultTrackingType = TrackingType.BODYWEIGHT_REPS, modality = ExerciseModality.CALISTHENICS),
                listOf(WorkoutSet(1, reps = 20, additionalWeight = 5f, isCompleted = true))),
            ExerciseLog(Exercise("ex_back_17", "Assisted", MuscleCategory.BACK, "", defaultTrackingType = TrackingType.ASSISTED_REPS, modality = ExerciseModality.CALISTHENICS),
                listOf(WorkoutSet(1, reps = 8, assistanceWeight = 20f, isCompleted = true))),
            ExerciseLog(Exercise("ex_cardio_5", "Conditioning", MuscleCategory.CARDIO, "", defaultTrackingType = TrackingType.REPS_ONLY, modality = ExerciseModality.CONDITIONING),
                listOf(WorkoutSet(1, reps = 30, isCompleted = true))),
            ExerciseLog(Exercise("ex_core_1", "Plank", MuscleCategory.CORE, "", defaultTrackingType = TrackingType.DURATION, modality = ExerciseModality.ISOMETRIC),
                listOf(WorkoutSet(1, durationSeconds = 90, isCompleted = true),
                    WorkoutSet(2, durationSeconds = 900, isCompleted = false))),
            ExerciseLog(Exercise("ex_cardio_1", "Run", MuscleCategory.CARDIO, "", defaultTrackingType = TrackingType.DISTANCE_DURATION, modality = ExerciseModality.CARDIO),
                listOf(WorkoutSet(1, durationSeconds = 600, distanceMeters = 2000, isCompleted = true))))
        val session = log(start).copy(exerciseLogs = exercises, totalVolume = 500f,
            additionalVolume = 100f)
        val result = progressMetrics(listOf(session), start.plusSeconds(3600), ZoneOffset.UTC)
        assertEquals(500.0, result.monthlyVolume, 0.0)
        assertEquals(100.0, result.monthlyAdditionalVolume, 0.0)
        assertEquals(68, result.monthlyRepetitions)
        assertEquals(90, result.monthlyHoldDurationSeconds)
        assertEquals(600, result.monthlyCardioDurationSeconds)
        assertEquals(2000, result.monthlyCardioDistanceMeters)
        assertEquals(60, result.monthlyMinutes)
        assertEquals(0, result.monthlyUnverifiedWorkouts)
    }

    @Test fun bodyweightRepetitionsDoNotInventExternalLoadOrBodyMassVolume() {
        val start = Instant.parse("2026-10-13T08:00:00Z")
        val exercise = ExerciseLog(Exercise("ex_chest_4", "Push up", MuscleCategory.CHEST, "", defaultTrackingType = TrackingType.BODYWEIGHT_REPS, modality = ExerciseModality.CALISTHENICS),
            listOf(WorkoutSet(1, reps = 15, isCompleted = true)))
        val result = progressMetrics(listOf(log(start).copy(totalVolume = 0f, exerciseLogs = listOf(exercise))),
            start.plusSeconds(3600), ZoneOffset.UTC)
        assertEquals(15, result.monthlyRepetitions)
        assertEquals(0.0, result.monthlyVolume, 0.0)
        assertEquals(0.0, result.monthlyAdditionalVolume, 0.0)
        assertEquals(0, result.monthlyHoldDurationSeconds)
        assertEquals(0, result.monthlyCardioDurationSeconds)
    }

    @Test fun unverifiedHistoricalMeasurementsRemainVisibleButNeverBecomeTypedProgress() {
        val start = Instant.parse("2026-10-13T08:00:00Z")
        val sessionLegacy = log(start, 999f).let { log ->
            log.copy(dataQuality = "LEGACY_UNVERIFIED",
                exerciseLogs = log.exerciseLogs.map { it.copy(metricQuality = "LEGACY_UNVERIFIED") })
        }
        val exerciseLegacy = log(start.plusSeconds(1), 888f).let { log ->
            log.copy(exerciseLogs = log.exerciseLogs.map { it.copy(metricQuality = "LEGACY_UNVERIFIED") })
        }
        val result = progressMetrics(listOf(sessionLegacy, exerciseLegacy), start.plusSeconds(7200), ZoneOffset.UTC)
        assertEquals(2, result.monthlyUnverifiedWorkouts)
        assertEquals(2, result.monthlyWorkouts)
        assertEquals(120, result.monthlyMinutes)
        assertEquals(0.0, result.monthlyVolume, 0.0)
        assertEquals(0.0, result.monthlyAdditionalVolume, 0.0)
        assertEquals(0, result.monthlyRepetitions)
        assertEquals(0, result.monthlyHoldDurationSeconds)
        assertEquals(0, result.monthlyCardioDurationSeconds)
        assertEquals(0, result.monthlyCardioDistanceMeters)
    }

    @Test fun mixedLegacySessionKeepsVerifiedChildrenWithoutTrustingLegacySessionTotals() {
        val start = Instant.parse("2026-10-13T08:00:00Z")
        val legacy = ExerciseLog(Exercise("ex_chest_1", "Legacy bench", MuscleCategory.CHEST, ""),
            listOf(WorkoutSet(1, weight = 999f, reps = 99, isCompleted = true)),
            metricQuality = "LEGACY_UNVERIFIED")
        val strength = ExerciseLog(Exercise("ex_chest_1", "Verified bench", MuscleCategory.CHEST, ""),
            listOf(WorkoutSet(1, weight = 40f, reps = 5, isCompleted = true)))
        val bodyweight = ExerciseLog(Exercise("ex_chest_4", "Push up", MuscleCategory.CHEST, "", defaultTrackingType = TrackingType.BODYWEIGHT_REPS, modality = ExerciseModality.CALISTHENICS),
            listOf(WorkoutSet(1, reps = 10, additionalWeight = 5f, isCompleted = true)))
        val cardio = ExerciseLog(Exercise("ex_cardio_1", "Run", MuscleCategory.CARDIO, "", defaultTrackingType = TrackingType.DISTANCE_DURATION, modality = ExerciseModality.CARDIO),
            listOf(WorkoutSet(1, durationSeconds = 600, distanceMeters = 2000, isCompleted = true)))
        val hold = ExerciseLog(Exercise("ex_core_1", "Plank", MuscleCategory.CORE, "", defaultTrackingType = TrackingType.DURATION, modality = ExerciseModality.ISOMETRIC),
            listOf(WorkoutSet(1, durationSeconds = 30, isCompleted = true)))
        val session = log(start).copy(dataQuality = "LEGACY_UNVERIFIED",
            totalVolume = 999999f, additionalVolume = 999999f,
            exerciseLogs = listOf(legacy, strength, bodyweight, cardio, hold))
        val result = progressMetrics(listOf(session), start.plusSeconds(3600), ZoneOffset.UTC)
        assertEquals(200.0, result.monthlyVolume, 0.0)
        assertEquals(50.0, result.monthlyAdditionalVolume, 0.0)
        assertEquals(15, result.monthlyRepetitions)
        assertEquals(30, result.monthlyHoldDurationSeconds)
        assertEquals(600, result.monthlyCardioDurationSeconds)
        assertEquals(2000, result.monthlyCardioDistanceMeters)
        assertEquals(60, result.monthlyMinutes)
        assertEquals(1, result.monthlyWorkouts)
        assertEquals(1, result.monthlyUnverifiedWorkouts)
    }
}
