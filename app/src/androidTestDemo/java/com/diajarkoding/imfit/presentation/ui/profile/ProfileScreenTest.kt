package com.diajarkoding.imfit.presentation.ui.profile

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.core.view.WindowCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.ui.progress.ProgressState
import com.diajarkoding.imfit.theme.IMFITTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

/** Synthetic presentation fixtures; no account, persistence, network or preference changes. */
@RunWith(AndroidJUnit4::class)
class ProfileScreenTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private data class Scene(
        val state: ProgressState = sample,
        val dark: Boolean = false,
        val indonesian: Boolean = true,
        val width: Int = 412,
        val fontScale: Float = 1f
    )

    private val scene = mutableStateOf(Scene())
    private var themeCalls = 0
    private var languageCalls = 0
    private var retryCalls = 0
    private var backCalls = 0
    private var logoutCalls = 0

    @Test fun pencilReferenceMatrixRendersAllSevenStates() {
        show()
        val variants = listOf(
            "NWdx5-light-id" to Scene(),
            "nD10u-dark-id" to Scene(dark = true),
            "M9lMH-light-en" to Scene(indonesian = false),
            "BP1O0-320-font130" to Scene(width = 320, fontScale = 1.3f,
                state = sample.copy(userName = "Alex Pratama Wijayakusuma", userEmail = "alex.pratama@example.com")),
            "HJCP9-loading" to Scene(state = ProgressState()),
            "G9W2eh-load-failure" to Scene(state = ProgressState(isLoading = false, hasError = true)),
            "KCR2P-optional-empty" to Scene(state = sample.copy(userBirthDate = null, userProfilePhotoUri = null))
        )
        for ((name, variant) in variants) {
            rule.runOnIdle { scene.value = variant }
            rule.waitForIdle()
            when {
                variant.state.isLoading -> rule.onNodeWithTag("profile_loading").assertExists()
                variant.state.hasError -> rule.onNodeWithTag("profile_retry").assertExists()
                else -> {
                    rule.onAllNodesWithText(variant.state.userName).assertCountEquals(2)
                    val expectedDate = if (variant.state.userBirthDate == null) "—"
                        else if (variant.indonesian) "15 Januari 1998" else "15 January 1998"
                    rule.onNodeWithText(expectedDate, useUnmergedTree = true).assertExists()
                }
            }
            screenshot(name + "-top")
            rule.onNodeWithTag("profile_list").performScrollToNode(hasTestTag("profile_logout"))
            rule.onNodeWithTag("profile_logout").assertIsDisplayed()
            assertMinimumTarget("profile_theme")
            assertMinimumTarget("profile_language")
            val themeTrack = rule.onNodeWithTag("profile_theme_track", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val languageTrack = rule.onNodeWithTag("profile_language_track", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertEquals("Language track width must match theme", themeTrack.width, languageTrack.width, 1f)
            assertEquals("Language track height must match theme", themeTrack.height, languageTrack.height, 1f)
            screenshot(name + "-bottom")
            // Start the next reference from the beginning, not the previous list offset.
            rule.onNodeWithTag("profile_list").performScrollToIndex(0)
        }
    }

    @Test fun themeLanguageBackAndLogoutKeepTheirExistingCallbacks() {
        show()
        scrollTo("profile_theme")
        rule.onNodeWithTag("profile_theme").assertIsOff().performClick().assertIsOn()
        rule.runOnIdle { assertEquals(1, themeCalls) }
        rule.onNodeWithTag("profile_language").assertIsOn().performClick().assertIsOff()
        rule.runOnIdle { assertEquals(1, languageCalls) }
        rule.onNodeWithTag("profile_language").performClick().assertIsOn()
        rule.runOnIdle { assertEquals(2, languageCalls) }
        rule.onNodeWithContentDescription(localized(R.string.action_back)).performClick()
        rule.runOnIdle { assertEquals(1, backCalls) }
        scrollTo("profile_logout")
        rule.onNodeWithTag("profile_logout").performClick()
        rule.runOnIdle { assertEquals(1, logoutCalls) }
        rule.onNodeWithTag("profile_list").performScrollToIndex(0)
        rule.onAllNodesWithText(sample.userName).assertCountEquals(2)
    }

    @Test fun failedLoadRetriesWithoutHidingPreferences() {
        scene.value = Scene(state = ProgressState(isLoading = false, hasError = true))
        show()
        rule.onNodeWithTag("profile_identity").assertDoesNotExist()
        rule.onNodeWithTag("profile_retry").performClick()
        rule.runOnIdle { assertEquals(1, retryCalls) }
        rule.onNodeWithTag("profile_loading").assertExists()
        scrollTo("profile_language")
        rule.onNodeWithTag("profile_language").performClick()
        rule.runOnIdle { assertEquals(1, languageCalls) }
        scrollTo("profile_logout")
        rule.onNodeWithTag("profile_logout").assertIsEnabled()
    }

    @Test fun historyErrorAndReloadDoNotReplaceAvailableIdentity() {
        scene.value = Scene(state = sample.copy(hasError = true))
        show()
        rule.onNodeWithTag("profile_error").assertDoesNotExist()
        rule.onAllNodesWithText(sample.userName).assertCountEquals(2)
        rule.runOnIdle { scene.value = scene.value.copy(state = sample.copy(isLoading = true)) }
        rule.onNodeWithTag("profile_loading").assertDoesNotExist()
        rule.onAllNodesWithText(sample.userName).assertCountEquals(2)
    }

    @Test fun longIdentityWrapsWithoutEllipsisAt320AndLargeFont() {
        val longName = "Alex Pratama Wijayakusuma"
        val email = "alex.pratama@example.com"
        scene.value = Scene(state = sample.copy(userName = longName, userEmail = email), width = 320, fontScale = 1.5f)
        show()
        screenshot("320-font150-top")
        val layouts = JSONArray()
        val failures = mutableListOf<String>()
        for (value in listOf(longName, email)) {
            for (node in rule.onAllNodesWithText(value, useUnmergedTree = true).fetchSemanticsNodes()) {
                val results = mutableListOf<TextLayoutResult>()
                val action = node.config[SemanticsActions.GetTextLayoutResult].action
                assertTrue(requireNotNull(action).invoke(results))
                assertTrue(results.isNotEmpty())
                for (result in results) {
                    layouts.put(JSONObject().put("text", value).put("size", result.size.toString())
                        .put("constraints", result.layoutInput.constraints.toString())
                        .put("overflowWidth", result.didOverflowWidth).put("overflowHeight", result.didOverflowHeight)
                        .put("lines", JSONArray((0 until result.lineCount).map { index ->
                            JSONObject().put("right", result.getLineRight(index)).put("left", result.getLineLeft(index))
                                .put("text", value.substring(result.getLineStart(index), result.getLineEnd(index)))
                        })))
                    if (result.hasVisualOverflow) failures.add("$value: ${result.size}, ${result.layoutInput.constraints}")
                }
            }
        }
        val directory = File(rule.activity.filesDir, "profile-redesign-evidence").apply { mkdirs() }
        File(directory, "font150-layout.json").writeText(layouts.toString(2))
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
        scrollTo("profile_logout")
        rule.onNodeWithTag("profile_logout").assertIsDisplayed()
        assertMinimumTarget("profile_theme")
    }

    @Test fun failedPhotoStillShowsFallbackAndDoesNotHideInformation() {
        scene.value = Scene(state = sample.copy(userProfilePhotoUri = "file:///imfit-synthetic-missing-avatar.png"))
        show()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("profile_photo_placeholder").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("profile_photo_placeholder").assertExists()
        rule.onNodeWithTag("profile_avatar").assertExists()
        rule.onNodeWithTag("profile_information").assertExists()
        rule.onNodeWithTag("profile_error").assertDoesNotExist()
        rule.onAllNodesWithText(sample.userName).assertCountEquals(2)
    }

    private fun show() {
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        rule.setContent { Fixture() }
    }

    @Composable
    private fun Fixture() {
        val value = scene.value
        val base = LocalConfiguration.current
        val context = LocalContext.current
        val locale = Locale.forLanguageTag(if (value.indonesian) "id-ID" else "en-US")
        val configuration = remember(base, value.width, value.fontScale, locale) {
            Configuration(base).apply { setLocale(locale); screenWidthDp = value.width; fontScale = value.fontScale }
        }
        val resources = remember(context, configuration) { context.createConfigurationContext(configuration).resources }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // Change only this fixture's Compose viewport, not emulator density/font preferences.
            val density = Density(constraints.maxWidth.toFloat() / value.width, value.fontScale)
            CompositionLocalProvider(LocalConfiguration provides configuration, LocalResources provides resources, LocalDensity provides density) {
                IMFITTheme(darkTheme = value.dark) {
                    ProfileScreenContent(value.state, value.dark, value.indonesian,
                        onNavigateBack = { backCalls++ }, onLogout = { logoutCalls++ },
                        onToggleTheme = { themeCalls++; scene.value = scene.value.copy(dark = !scene.value.dark) },
                        onToggleLanguage = { languageCalls++; scene.value = scene.value.copy(indonesian = !scene.value.indonesian) },
                        onRetry = { retryCalls++; scene.value = scene.value.copy(state = ProgressState()) })
                }
            }
        }
    }

    private fun scrollTo(tag: String) = rule.onNodeWithTag("profile_list").performScrollToNode(hasTestTag(tag))

    private fun assertMinimumTarget(tag: String) {
        val density = rule.onNodeWithTag("profile_list").fetchSemanticsNode().boundsInRoot.width / scene.value.width
        val bounds = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        assertTrue("$tag height must be at least 48 fixture dp", bounds.height + 1 >= 48 * density)
        assertTrue("$tag width must be at least 48 fixture dp", bounds.width + 1 >= 48 * density)
    }

    private fun localized(id: Int): String {
        val config = Configuration(rule.activity.resources.configuration).apply {
            setLocale(Locale.forLanguageTag(if (scene.value.indonesian) "id-ID" else "en-US"))
        }
        return rule.activity.createConfigurationContext(config).getString(id)
    }

    private fun screenshot(name: String) {
        rule.waitForIdle()
        rule.runOnUiThread {
            WindowCompat.getInsetsController(rule.activity.window, rule.activity.window.decorView).apply {
                isAppearanceLightStatusBars = !scene.value.dark
                isAppearanceLightNavigationBars = !scene.value.dark
            }
        }
        val directory = File(rule.activity.filesDir, "profile-redesign-evidence").apply { mkdirs() }
        val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    companion object {
        private val sample = ProgressState(userName = "Alex Pratama", userEmail = "alex@example.com",
            userBirthDate = "1998-01-15", isLoading = false)
    }
}
