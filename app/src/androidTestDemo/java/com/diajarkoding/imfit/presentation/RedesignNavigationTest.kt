package com.diajarkoding.imfit.presentation

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.diajarkoding.imfit.MainActivity
import com.diajarkoding.imfit.R
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import androidx.lifecycle.ViewModelProvider
import com.diajarkoding.imfit.theme.ThemeViewModel
import com.diajarkoding.imfit.theme.LocaleManager
import androidx.test.platform.app.InstrumentationRegistry
import com.diajarkoding.imfit.di.RestTimerEntryPoint
import dagger.hilt.android.EntryPointAccessors
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.repository.ExerciseRepository
import com.diajarkoding.imfit.data.local.FakeExerciseDataSource
import kotlinx.coroutines.runBlocking
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Demo only: real host/backstack, category route, search and profile navigation. */
@RunWith(AndroidJUnit4::class)
class RedesignNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int) = LocaleManager.getUpdatedContext(rule.activity).getString(id)
    private fun waitForText(value: String) {
        rule.waitUntil(15_000) { rule.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun screenshot(name: String) {
        rule.waitForIdle()
        val directory = requireNotNull(rule.activity.getExternalFilesDir("redesign-evidence"))
        val profile = InstrumentationRegistry.getArguments().getString("artifactSuffix", "default")
        File(directory, "$name-$profile.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun rootTabsSearchCategoryAndProfileUseRealDestinations() {
        waitForText(text(R.string.redesign_ready))
        screenshot("home-after")
        rule.onNodeWithText(text(R.string.nav_exercises)).performClick()
        waitForText(text(R.string.muscle_category_chest))
        screenshot("exercise-after")
        rule.onAllNodesWithText(text(R.string.muscle_category_chest)).onFirst().performClick()
        waitForText(text(R.string.redesign_search))
        rule.onNodeWithText(text(R.string.redesign_search)).performTextInput("zz_no_imfit_result")
        waitForText(text(R.string.redesign_no_results))
        rule.onNodeWithText(text(R.string.redesign_search)).performTextClearance()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        waitForText("Barbell Bench Press")
        rule.waitForIdle()
        rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        rule.waitForIdle()
        waitForText(text(R.string.nav_progress))
        rule.onNodeWithText(text(R.string.nav_progress)).performClick()
        try { waitForText(text(R.string.progress_overview_volume)) }
        catch (e: Exception) { screenshot("progress-failure"); throw e }
        screenshot("progress-after")
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription(text(R.string.calendar_view_yearly)))
        screenshot("progress-calendar")
        rule.onNodeWithContentDescription(text(R.string.calendar_view_yearly)).performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithContentDescription(text(R.string.calendar_previous_year)).fetchSemanticsNodes().isNotEmpty()
        }
        val locale = LocaleManager.getUpdatedContext(rule.activity).resources.configuration.locales[0]
        val displayedMonth = YearMonth.now().format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).replaceFirstChar { it.uppercaseChar() }
        rule.onNodeWithText(displayedMonth).assertIsDisplayed()
        rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        waitForText(text(R.string.nav_progress))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription(text(R.string.redesign_profile)))
        rule.onNodeWithContentDescription(text(R.string.redesign_profile)).performClick()
        waitForText(text(R.string.title_profile))
    }

    @Test fun homeCreateDialogStillCreatesNamedEmptyTemplateAndOpensDetail() {
        waitForText(text(R.string.redesign_ready))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text(R.string.home_add_day)))
        rule.onNodeWithText(text(R.string.home_add_day)).performClick()
        rule.onNode(hasText(text(R.string.home_workout_placeholder)) and hasSetTextAction())
            .performTextInput("Redesign smoke template")
        rule.onNode(hasText(text(R.string.routine_create_action)) and hasClickAction()).performClick()
        waitForText("Redesign smoke template")
        // Assert navigation completed, rather than matching text in the open dialog.
        rule.waitUntil(15_000) {
            rule.onAllNodesWithText(text(R.string.home_create_workout_title)).fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun lightDarkAndBothLocalesRenderAllRootTabs() {
        waitForText(text(R.string.redesign_ready))
        val theme = ViewModelProvider(rule.activity)[ThemeViewModel::class.java]
        val originalDark = theme.uiState.value.isDarkMode
        val originalIndonesian = LocaleManager.isIndonesian
        try {
            for (dark in listOf(false, true)) for (indonesian in listOf(false, true)) {
                rule.runOnUiThread {
                    theme.setDarkMode(dark)
                    if (LocaleManager.isIndonesian != indonesian) LocaleManager.toggleLanguage(rule.activity)
                }
                rule.waitForIdle()
                val suffix = (if (dark) "dark" else "light") + "-" + (if (indonesian) "id" else "en")
                rule.onNodeWithText(text(R.string.nav_home)).performClick()
                waitForText(text(R.string.redesign_ready))
                screenshot("home-$suffix")
                rule.onNodeWithText(text(R.string.nav_exercises)).performClick()
                waitForText(text(R.string.muscle_category_chest))
                screenshot("exercise-$suffix")
                rule.onNodeWithText(text(R.string.nav_progress)).performClick()
                waitForText(text(R.string.progress_overview_volume))
                screenshot("progress-$suffix")
            }
        } finally {
            rule.runOnUiThread {
                theme.setDarkMode(originalDark)
                if (LocaleManager.isIndonesian != originalIndonesian) LocaleManager.toggleLanguage(rule.activity)
            }
        }
    }

    @Test fun editedNameAppearsOnRetainedDetailAndHomeWithoutSync() {
        waitForText(text(R.string.redesign_ready))
        val ports = EntryPointAccessors.fromApplication(rule.activity.application, RestTimerEntryPoint::class.java)
        val repository = ports.workouts()
        val before = "Rename ${System.nanoTime()}"
        val after = "$before saved"
        val template = runBlocking {
            repository.createTemplate(requireNotNull(ports.auth().getLocalUserId()), before, emptyList())
        }
        try {
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(before))
            rule.onNodeWithText(before).performClick()
            waitForText(before)
            rule.onNodeWithContentDescription(text(R.string.action_edit)).performClick()
            rule.onNode(hasSetTextAction()).performTextReplacement(after)
            rule.onNodeWithText(text(R.string.action_save_changes)).performClick()
            waitForText(after)
            rule.onNodeWithText(text(R.string.workout_edit_title)).assertDoesNotExist()
            rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(after))
            rule.onNodeWithText(after).assertIsDisplayed()
        } finally { runBlocking { repository.deleteTemplate(template.id) } }
    }

    @Test fun homeHistoryUpdatesToOneFinishedSessionWithoutReopeningHomeOrWaitingForCloud() {
        waitForText(text(R.string.redesign_ready))
        val ports = EntryPointAccessors.fromApplication(rule.activity.application, RestTimerEntryPoint::class.java)
        val repository = ports.workouts()
        val name = "Latest card ${System.nanoTime()}"
        val template = runBlocking {
            repository.cancelWorkout()
            repository.createTemplate(requireNotNull(ports.auth().getLocalUserId()), name,
                listOf(TemplateExercise(FakeExerciseDataSource.exercises.first(), sets = 1)))
        }
        try {
            val finished = runBlocking {
                val session = repository.startWorkout(template)
                repository.mutateActiveSession(session.id) { current -> current.copy(exerciseLogs = current.exerciseLogs.map { log ->
                    log.copy(sets = log.sets.map { it.copy(weight = 20f, reps = 8, isCompleted = true) })
                }) }
                requireNotNull(repository.finishWorkout())
            }
            val localized = LocaleManager.getUpdatedContext(rule.activity)
            val locale = localized.resources.configuration.locales[0]
            val heading = localized.getString(R.string.home_summary_heading, name.uppercase(locale))
            waitForText(heading)
            rule.onNodeWithText(heading).assertIsDisplayed()
            rule.onNodeWithText(String.format(locale, "%,.0f kg", finished.totalVolume)).assertIsDisplayed()
        } finally { runBlocking { repository.cancelWorkout(); repository.deleteTemplate(template.id) } }
    }
}
