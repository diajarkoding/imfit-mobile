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

/** Demo only: real host/backstack, category route, search and profile navigation. */
@RunWith(AndroidJUnit4::class)
class RedesignNavigationTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun text(id: Int) = rule.activity.getString(id)
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
        waitForText(text(R.string.redesign_latest))
        screenshot("home-after")
        rule.onNodeWithText("Exercise").performClick()
        waitForText(text(R.string.redesign_categories))
        screenshot("exercise-after")
        rule.onNodeWithText(text(R.string.redesign_search)).performTextInput("zz_no_imfit_result")
        waitForText(text(R.string.redesign_no_results))
        rule.onNodeWithText(text(R.string.redesign_search)).performTextClearance()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        waitForText(text(R.string.redesign_categories))
        rule.onAllNodesWithText(text(R.string.muscle_category_chest)).onFirst().performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        rule.waitForIdle()
        waitForText("Progress")
        rule.onNodeWithText("Progress").performClick()
        try { waitForText(text(R.string.redesign_month_summary)) }
        catch (e: Exception) { screenshot("progress-failure"); throw e }
        screenshot("progress-after")
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text(R.string.redesign_calendar)))
        screenshot("progress-calendar")
        rule.onNodeWithText(text(R.string.redesign_calendar)).performClick()
        rule.waitUntil(15_000) {
            rule.onAllNodesWithContentDescription(text(R.string.calendar_previous_year)).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        waitForText("Progress")
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasContentDescription(text(R.string.redesign_profile)))
        rule.onNodeWithContentDescription(text(R.string.redesign_profile)).performClick()
        waitForText(text(R.string.title_profile))
    }

    @Test fun homeCreateDialogStillCreatesNamedEmptyTemplateAndOpensDetail() {
        waitForText(text(R.string.redesign_latest))
        rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text(R.string.action_add_workout)))
        rule.onNodeWithText(text(R.string.action_add_workout)).performClick()
        rule.onNodeWithText(text(R.string.home_workout_placeholder)).performTextInput("Redesign smoke template")
        rule.onNodeWithText(text(R.string.action_create)).performClick()
        waitForText("Redesign smoke template")
        // Assert navigation completed, rather than matching text in the open dialog.
        rule.onNodeWithText(text(R.string.home_create_workout_title)).assertDoesNotExist()
    }

    @Test fun lightDarkAndBothLocalesRenderAllRootTabs() {
        waitForText(text(R.string.redesign_latest))
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
                rule.onNodeWithText("Home").performClick()
                waitForText(text(R.string.redesign_latest))
                screenshot("home-$suffix")
                rule.onNodeWithText("Exercise").performClick()
                waitForText(text(R.string.redesign_categories))
                screenshot("exercise-$suffix")
                rule.onNodeWithText("Progress").performClick()
                waitForText(text(R.string.redesign_month_summary))
                screenshot("progress-$suffix")
            }
        } finally {
            rule.runOnUiThread {
                theme.setDarkMode(originalDark)
                if (LocaleManager.isIndonesian != originalIndonesian) LocaleManager.toggleLanguage(rule.activity)
            }
        }
    }
}
