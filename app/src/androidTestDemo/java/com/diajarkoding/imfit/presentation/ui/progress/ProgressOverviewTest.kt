package com.diajarkoding.imfit.presentation.ui.progress

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
        rule.onNodeWithText(String.format(locale, "%,.0f kg", 125.0)).assertIsDisplayed()
        rule.onNodeWithText(context.resources.getQuantityString(R.plurals.progress_overview_minutes, 120, 120)).assertIsDisplayed()
        rule.onNodeWithText(context.getString(R.string.progress_overview_all_history)).assertDoesNotExist()
        rule.onNodeWithText(context.getString(R.string.progress_overview_this_week)).assertDoesNotExist()
    }
}
