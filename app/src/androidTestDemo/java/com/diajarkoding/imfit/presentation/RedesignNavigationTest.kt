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
import com.diajarkoding.imfit.data.remote.TemplateWriteRequest
import com.diajarkoding.imfit.data.remote.TemplateWriteExercise
import com.diajarkoding.imfit.data.remote.online.SessionCommand
import com.diajarkoding.imfit.data.remote.online.SessionSetKey
import java.util.UUID
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
        rule.onNodeWithTag("exercise_list_search").performTextInput("zz_no_imfit_result")
        waitForText(text(R.string.redesign_no_results))
        rule.onNodeWithTag("exercise_list_search").performTextClearance()
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
        val reads = ports.reads()
        val edits = ports.edits()
        val before = "Rename ${System.nanoTime()}"
        val after = "$before saved"
        val receipt = runBlocking { edits.save(null, before, emptyList()) }
        val templateId = receipt.templateId
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
        } finally {
            runBlocking {
                val (current, domain) = reads.template(templateId)
                edits.save(current, domain.name, domain.exercises, deleted = true)
            }
        }
    }

    @Test fun homeHistoryUpdatesToOneFinishedSessionWithoutReopeningHomeOrWaitingForCloud() {
        waitForText(text(R.string.redesign_ready))
        val ports = EntryPointAccessors.fromApplication(rule.activity.application, RestTimerEntryPoint::class.java)
        val reads = ports.reads()
        val sessions = ports.sessions()
        val name = "Latest card ${System.nanoTime()}"
        val templateId = UUID.randomUUID().toString()
        val owner = runBlocking { requireNotNull(ports.auth().getLocalUserId()) }
        val template = runBlocking {
            sessions.reload()?.let { sessions.cancel(it.id) }
            reads.saveTemplate(TemplateWriteRequest(UUID.randomUUID().toString(), templateId, null, name,
                listOf(TemplateWriteExercise(FakeExerciseDataSource.exercises.first().id, 0, 1, 8, 60)),
                emptyList(), ownerId = owner))
        }
        try {
            val volume = runBlocking {
                val session = sessions.start(templateId, template.revision)
                for (exercise in session.exercises) for (set in exercise.sets) {
                    sessions.editSet(session.id, SessionSetKey(exercise.id, set.id), "20", "8")
                    sessions.command(session.id, SessionCommand.CompleteSet(exercise.id, set.id))
                }
                val workoutId = requireNotNull(sessions.finish(session.id).workoutId)
                reads.read(com.diajarkoding.imfit.data.remote.online.OnlineRpc.workoutDetail(workoutId)).totalVolume
            }
            val localized = LocaleManager.getUpdatedContext(rule.activity)
            val locale = localized.resources.configuration.locales[0]
            val heading = localized.getString(R.string.home_summary_heading, name.uppercase(locale))
            // The retained Home collector refreshes its summary from the terminal receipt.
            waitForText(heading)
            rule.onNodeWithText(heading).assertIsDisplayed()
            rule.onNodeWithText(String.format(locale, "%,.0f kg", volume.toBigDecimal())).assertIsDisplayed()
        } finally {
            runBlocking {
                sessions.peek()?.let { sessions.cancel(it.id) }
                val current = reads.template(templateId).first
                reads.saveTemplate(TemplateWriteRequest(UUID.randomUUID().toString(), templateId, current.revision,
                    name, emptyList(), current.exercises.map { it.exerciseId }, isDeleted = true, ownerId = owner))
            }
        }
    }
}
