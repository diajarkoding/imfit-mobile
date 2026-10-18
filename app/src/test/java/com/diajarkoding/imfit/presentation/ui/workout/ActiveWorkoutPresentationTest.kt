package com.diajarkoding.imfit.presentation.ui.workout

import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.WorkoutLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ActiveWorkoutPresentationTest {

    @Test
    fun countdownArcUsesCommittedDurationAndClampsBounds() {
        assertEquals(0.75f, restCountdownProgress(45, 60f), 0.001f)
        assertEquals(1f, restCountdownProgress(90, 60f), 0.001f)
        assertEquals(0f, restCountdownProgress(-1, 60f), 0.001f)
        assertEquals(0f, restCountdownProgress(45, 0f), 0.001f)
    }

    @Test
    fun fractionalSetWeightIsNotTruncated() {
        assertEquals("62.5", formatSetWeight(62.5f, Locale.US))
        assertEquals("62,5", formatSetWeight(62.5f, Locale.forLanguageTag("id-ID")))
        assertEquals("60", formatSetWeight(60f, Locale.US))
    }

    @Test
    fun cancelDialogMessageResolvesZeroCompletedSets() {
        val spec = resolveCancelDialogMessage(completedSets = 0, totalSets = 6)
        assertEquals(R.string.active_session_cancel_message_zero, spec.stringRes)
        assertTrue(spec.formatArgs.isEmpty())
    }

    @Test
    fun cancelDialogMessageResolvesOneCompletedSet() {
        val spec = resolveCancelDialogMessage(completedSets = 1, totalSets = 6)
        assertEquals(R.string.active_session_cancel_message_one, spec.stringRes)
        assertEquals(listOf(6), spec.formatArgs)
    }

    @Test
    fun cancelDialogMessageResolvesSomeCompletedSets() {
        val spec = resolveCancelDialogMessage(completedSets = 2, totalSets = 6)
        assertEquals(R.string.active_session_cancel_message_some, spec.stringRes)
        assertEquals(listOf(2, 6), spec.formatArgs)
    }

    @Test
    fun cancelDialogMessageResolvesAllCompletedSets() {
        val spec = resolveCancelDialogMessage(completedSets = 6, totalSets = 6)
        assertEquals(R.string.active_session_cancel_message_all, spec.stringRes)
        assertEquals(listOf(6), spec.formatArgs)
    }

    @Test
    fun formatElapsedTimeFormatsWithHoursAndZeroPadding() {
        assertEquals("00:00:00", formatElapsedTime(0))
        assertEquals("00:23:42", formatElapsedTime(23 * 60 + 42))
        assertEquals("01:12:05", formatElapsedTime(3600 + 12 * 60 + 5))
        assertEquals("00:00:00", formatElapsedTime(-10))
    }

    @Test
    fun formatVolumeFormatsWithLocaleGrouping() {
        val idLocale = Locale("in", "ID")
        val enLocale = Locale.US
        assertEquals("2.520 kg", formatVolume(2520f, idLocale))
        assertEquals("2,520 kg", formatVolume(2520f, enLocale))
        assertEquals("0 kg", formatVolume(0f, idLocale))
    }

    @Test
    fun workoutSummaryPresentationStates() {
        assertTrue(workoutSummaryPresentation(log = null, isLoading = true) is WorkoutSummaryPresentation.Loading)
        assertTrue(workoutSummaryPresentation(log = null, isLoading = false) is WorkoutSummaryPresentation.Unavailable)

        val log = WorkoutLog(
            id = "log_1",
            userId = "user_1",
            templateName = "Upper Body",
            date = 1000L,
            startTime = 1000L,
            endTime = 2000L,
            totalVolume = 2520f,
            exerciseLogs = emptyList()
        )
        val content = workoutSummaryPresentation(log = log, isLoading = false)
        assertTrue(content is WorkoutSummaryPresentation.Content)
        assertEquals("Upper Body", (content as WorkoutSummaryPresentation.Content).log.templateName)
    }

    @Test
    fun formatSummaryDurationRespectsLocaleAndHours() {
        val idLocale = Locale("in", "ID")
        val enLocale = Locale.US

        assertEquals("38 menit", formatSummaryDuration(38, idLocale))
        assertEquals("38m", formatSummaryDuration(38, enLocale))

        assertEquals("1 jam 15 mnt", formatSummaryDuration(75, idLocale))
        assertEquals("1h 15m", formatSummaryDuration(75, enLocale))
    }
}
