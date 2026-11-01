package com.diajarkoding.imfit.presentation.ui.progress

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.theme.IMFITTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import androidx.test.platform.app.InstrumentationRegistry
import com.diajarkoding.imfit.R
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources

class ProgressOverviewTest {
    @get:Rule val rule = createComposeRule()

    @Test fun calendarSelectsEmptyDatesAndOpensRecordedDayImmediately() {
        val today = LocalDate.of(2026, 10, 13)
        val recorded = today.minusDays(1)
        val start = recorded.atStartOfDay(ZoneId.systemDefault()).toInstant()
        val log = WorkoutLog("recorded", "user", "Push Day", start.toEpochMilli(), start.toEpochMilli(),
            start.plusSeconds(3600).toEpochMilli(), 100f, emptyList())
        var openedDate: LocalDate? = null
        rule.setContent {
            IMFITTheme {
                ProgressOverview(
                    ProgressState(userName = "Calendar user", workoutLogsByDate = mapOf(recorded to listOf(log)), isLoading = false),
                    today.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant(),
                    onWorkoutHistory = { openedDate = it }, onYearlyCalendar = {}, onProfile = {}, onRetry = {}
                )
            }
        }
        val calendar = rule.onNode(hasScrollToNodeAction())
        calendar.performScrollToNode(hasText("14"))
        rule.onNodeWithText("14").performClick().assertIsSelected()
        rule.runOnIdle { assertEquals(null, openedDate) }
        calendar.performScrollToNode(hasText("12"))
        rule.onNodeWithText("12").performClick().assertIsSelected()
        rule.runOnIdle { assertEquals(recorded, openedDate) }
    }

    @Test fun bothMetricCardsShowTheSameCalendarMonthAndMonthlyValues() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val locale = context.resources.configuration.locales[0]
        val month = YearMonth.of(2026, 10)
        val period = month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).replaceFirstChar { it.uppercaseChar() }
        rule.setContent {
            CompositionLocalProvider(LocalConfiguration provides context.resources.configuration,
                LocalResources provides context.resources) {
                IMFITTheme { ProgressMetricOverview(ProgressMetrics(month, 125.0, 120, 2, 2)) }
            }
        }
        rule.onAllNodesWithText(period).assertCountEquals(2)
        rule.onNodeWithText(context.getString(R.string.progress_volume_unit, "125")).assertIsDisplayed()
        rule.onNodeWithText(context.resources.getQuantityString(R.plurals.progress_overview_minutes, 120, 120)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.progress_overview_all_history)).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.progress_overview_this_week)).assertDoesNotExist()
    }

    @Test fun typedOverviewDisplaysEveryMetricWithItsOwnMeaningAndUnit() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        rule.setContent {
            CompositionLocalProvider(LocalConfiguration provides context.resources.configuration,
                LocalResources provides context.resources) {
                IMFITTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ProgressMetricOverview(ProgressMetrics(YearMonth.of(2026, 10), 500.0, 60, 1, 6,
                            monthlyHoldDurationSeconds = 90, monthlyCardioDurationSeconds = 600,
                            monthlyCardioDistanceMeters = 2000, monthlyAdditionalVolume = 100.0,
                            monthlyRepetitions = 68))
                    }
                }
            }
        }
        listOf(R.string.progress_overview_volume, R.string.progress_additional_volume,
            R.string.progress_repetitions, R.string.progress_hold_duration,
            R.string.progress_cardio_duration, R.string.progress_cardio_distance,
            R.string.progress_overview_weekly_time).forEach { caption ->
            rule.onNodeWithText(context.getString(caption)).performScrollTo().assertIsDisplayed()
        }
        val number = java.text.NumberFormat.getNumberInstance(context.resources.configuration.locales[0])
        listOf(R.string.progress_volume_unit to 500, R.string.progress_volume_unit to 100,
            R.string.progress_repetitions_unit to 68, R.string.progress_seconds_unit to 90,
            R.string.progress_seconds_unit to 600, R.string.progress_meters_unit to 2000).forEach { (unit, value) ->
            rule.onNodeWithText(context.getString(unit, number.format(value))).performScrollTo().assertIsDisplayed()
        }
        rule.onNodeWithText(context.resources.getQuantityString(R.plurals.progress_overview_minutes, 60, 60))
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun bodyweightOverviewShowsRepetitionsAndLegacyNoticeNotInventedZeroKgProgress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        rule.setContent {
            CompositionLocalProvider(LocalConfiguration provides context.resources.configuration,
                LocalResources provides context.resources) {
                IMFITTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        ProgressMetricOverview(ProgressMetrics(YearMonth.of(2026, 10), 0.0, 30, 1, 1,
                            monthlyRepetitions = 15, monthlyUnverifiedWorkouts = 1))
                    }
                }
            }
        }
        rule.onNodeWithText(context.getString(R.string.progress_repetitions_unit, "15"))
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.progress_no_external_volume))
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.progress_unverified_notice, 1))
            .performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.redesign_kg, "0")).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.progress_volume_unit, "0")).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.progress_hold_duration)).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.progress_cardio_duration)).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.progress_cardio_distance)).assertDoesNotExist()
    }
}
