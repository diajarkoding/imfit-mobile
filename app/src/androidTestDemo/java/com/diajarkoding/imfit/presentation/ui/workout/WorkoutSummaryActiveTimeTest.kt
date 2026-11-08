package com.diajarkoding.imfit.presentation.ui.workout

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.util.Locale
import org.junit.Rule
import org.junit.Test

class WorkoutSummaryActiveTimeTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun activeTimeIsAnExtraMetricOnlyWhenRecordedAndNeverReplacesTheGrossDuration() {
        rule.setContent {
            WorkoutTestEnvironment(indonesian = false) {
                WorkoutSummaryMetrics("40 min", emptyList(), Locale.US, stacked = false, formattedActiveTime = "35 min")
            }
        }
        rule.onNodeWithText("Duration").assertExists()
        rule.onNodeWithText("40 min").assertExists()
        rule.onNodeWithTag("workout_summary_active_time").assertExists()
        rule.onNodeWithText("Active time").assertExists()
        rule.onNodeWithText("35 min").assertExists()
    }

    @Test fun legacyHistoryWithoutActiveTimeShowsNoActiveTimeMetric() {
        rule.setContent {
            WorkoutTestEnvironment(indonesian = false) {
                WorkoutSummaryMetrics("40 min", emptyList(), Locale.US, stacked = false)
            }
        }
        rule.onNodeWithTag("workout_summary_active_time").assertDoesNotExist()
    }
}
