package com.diajarkoding.imfit.presentation

import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.diajarkoding.imfit.MainActivity
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.ui.exercise.ExerciseCategory
import com.diajarkoding.imfit.theme.LocaleManager
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

class MainPolishTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    @Test fun mainTabsShareTopGapAndAllTwelveCategoriesStayAboveNavigation() {
        assumeTrue(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".modaldiagnostic"))
        rule.waitUntil(15_000) { rule.onAllNodesWithText(text(R.string.redesign_ready)).fetchSemanticsNodes().isNotEmpty() }
        for ((tab, first) in listOf(R.string.nav_home to "main_first_content", R.string.nav_exercises to "category_CHEST", R.string.nav_progress to "main_first_content")) {
            rule.onNodeWithText(text(tab)).performClick()
            rule.waitForIdle()
            val appbar = rule.onNodeWithTag("main_app_bar").fetchSemanticsNode().boundsInRoot
            val content = rule.onNodeWithTag(first).fetchSemanticsNode().boundsInRoot
            assertEquals("Tab $tab header gap", 8 * rule.activity.resources.displayMetrics.density, content.top - appbar.bottom, 2f)
            val profileAction = hasContentDescription(text(R.string.redesign_profile)) and hasAnyAncestor(hasTestTag("main_app_bar"))
            if (tab == R.string.nav_home) rule.onNode(profileAction).assertExists()
            else rule.onNode(profileAction).assertDoesNotExist()
            if (tab == R.string.nav_exercises) {
                val viewport = rule.onNodeWithTag("exercise_category_grid").fetchSemanticsNode().boundsInRoot
                for (category in ExerciseCategory.entries) {
                    val node = rule.onNodeWithTag("category_${category.name}").assertIsDisplayed().fetchSemanticsNode()
                    assertTrue("${category.name} must fit", node.boundsInRoot.bottom <= viewport.bottom + 1)
                    assertTrue(node.boundsInRoot.top >= viewport.top)
                }
            }
            capture("tab-$tab")
        }
        // Progress keeps its profile row; Home remains the app-bar entry to Profile.
        rule.onNodeWithText(text(R.string.nav_home)).performClick()
        rule.onNode(hasContentDescription(text(R.string.redesign_profile)) and hasAnyAncestor(hasTestTag("main_app_bar")))
            .performClick()
        rule.onNodeWithTag("profile_list").assertExists()
        rule.onNodeWithContentDescription(text(R.string.action_back)).performClick()
        rule.onNodeWithTag("main_app_bar").assertExists()
        rule.onNodeWithText(text(R.string.nav_progress)).performClick()
        rule.onNodeWithContentDescription(text(R.string.calendar_view_yearly)).performScrollTo().performClick()
        val calendarHeader = rule.onNodeWithTag("screen_header").fetchSemanticsNode().boundsInRoot
        val calendarContent = rule.onNodeWithTag("year_calendar_content").fetchSemanticsNode().boundsInRoot
        assertEquals("Year calendar header gap", 8 * rule.activity.resources.displayMetrics.density,
            calendarContent.top - calendarHeader.bottom, 2f)
    }
    private fun text(id: Int) = LocaleManager.getUpdatedContext(rule.activity).getString(id)
    private fun capture(name: String) {
        val dir = File(rule.activity.filesDir, "polish-evidence").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
