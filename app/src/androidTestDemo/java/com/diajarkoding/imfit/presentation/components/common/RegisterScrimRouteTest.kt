package com.diajarkoding.imfit.presentation.components.common

import android.graphics.Bitmap
import android.graphics.Color
import android.view.Window
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.diajarkoding.imfit.MainActivity
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.data.local.FakeUserDataSource
import com.diajarkoding.imfit.data.repository.DemoAuthRepository
import com.diajarkoding.imfit.theme.LocaleManager
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual Register route in an isolated demo installation; never submits or opens camera/gallery. */
@RunWith(AndroidJUnit4::class)
class RegisterScrimRouteTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    @Test fun registerCalendarAndPhotoSheetHaveMatchingScrimsAndKeepDraft() {
        assumeTrue(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".modaldiagnostic"))
        var window: Window? = null
        try {
            waitFor(text(R.string.redesign_ready))
            rule.onNodeWithContentDescription(text(R.string.redesign_profile)).performClick()
            rule.onNodeWithTag("profile_list").performScrollToNode(hasTestTag("profile_logout"))
            rule.onNodeWithTag("profile_logout").performClick()
            waitFor(text(R.string.login_redirect_action))
            rule.onNodeWithText(text(R.string.login_redirect_action)).performClick()
            waitFor(text(R.string.register_select_birth_date))
            val header = rule.onNodeWithTag("screen_header").fetchSemanticsNode().boundsInRoot
            val content = rule.onNodeWithTag("register_first_content").fetchSemanticsNode().boundsInRoot
            assertEquals("Register header gap", 8 * rule.activity.resources.displayMetrics.density,
                content.top - header.bottom, 2f)
            rule.onNode(hasText(text(R.string.placeholder_name)) and hasSetTextAction()).performTextInput("Synthetic Register")
            Espresso.closeSoftKeyboard()
            rule.onNodeWithText(text(R.string.register_select_birth_date)).performScrollTo()
            rule.runOnIdle { DialogBackdropDiagnosticBridge.observer = { _, value -> window = value } }
            capture("register-baseline", false)
            rule.onNodeWithText(text(R.string.register_select_birth_date)).performClick()
            waitFor(text(R.string.auth_select_date))
            capture("register-calendar", true)
            rule.runOnIdle {
                val attrs = requireNotNull(window).attributes
                assertEquals(0f, attrs.dimAmount, 0f)
                assertEquals(0, attrs.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }
            // Cancel calendar keeps the form draft and does not commit its temporary date.
            rule.onNodeWithText(text(R.string.action_cancel)).performClick()
            rule.onNodeWithText(text(R.string.register_select_birth_date)).assertExists()
            rule.onNodeWithText("Synthetic Register").assertExists()
            rule.onNodeWithText(text(R.string.auth_add_photo)).performScrollTo().performClick()
            waitFor(text(R.string.register_choose_photo))
            capture("register-photo-sheet", true)
            rule.onNodeWithText(text(R.string.action_cancel)).performClick()
            rule.onNodeWithText("Synthetic Register").assertExists()
        } finally {
            rule.runOnUiThread {
                DialogBackdropDiagnosticBridge.observer = null
                // Restore this process's synthetic demo session for other route tests.
                FakeUserDataSource.login(DemoAuthRepository.DEMO_EMAIL, DemoAuthRepository.DEMO_PASSWORD).getOrThrow()
            }
        }
    }

    private fun text(id: Int) = LocaleManager.getUpdatedContext(rule.activity).getString(id)
    private fun waitFor(text: String) = rule.waitUntil(15_000) { rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun capture(name: String, darkened: Boolean) {
        rule.waitForIdle()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle(1_000)
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val pixel = bitmap.getPixel(12, 400)
        val rgb = listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
        if (darkened) assertTrue("$name: $rgb", rgb.all { it in 120..130 })
        else assertTrue("$name: $rgb", rgb.all { it > 240 })
        val dir = File(rule.activity.filesDir, "compose-scrim-evidence").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        File(dir, "$name-rgb.json").writeText(JSONArray(rgb).toString())
    }
}
