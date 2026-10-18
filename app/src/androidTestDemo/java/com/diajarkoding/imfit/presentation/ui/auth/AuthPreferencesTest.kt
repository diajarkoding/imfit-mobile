package com.diajarkoding.imfit.presentation.ui.auth

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.diajarkoding.imfit.theme.IMFITTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Synthetic preferences only; no persistence, account or device-setting changes. */
class AuthPreferencesTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun compactTrackKeepsSeparateAccessibleTargetsAt320AndLargeFont() {
        val dark = mutableStateOf(false)
        val indonesian = mutableStateOf(true)
        val fontScale = mutableStateOf(1f)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale.value)) {
                IMFITTheme(darkTheme = dark.value) {
                    Box(Modifier.width(320.dp)) {
                        AuthPreferences(dark.value, indonesian.value, {}, {})
                    }
                }
            }
        }
        for (isDark in listOf(false, true)) {
            for (isIndonesian in listOf(true, false)) {
                for (scale in listOf(1f, 1.5f)) {
                    rule.runOnIdle { dark.value = isDark; indonesian.value = isIndonesian; fontScale.value = scale }
                    rule.onNodeWithTag("auth_language_track", useUnmergedTree = true)
                        .assertWidthIsEqualTo(72.dp).assertHeightIsEqualTo(36.dp)
                    rule.onNodeWithTag("auth_theme_track", useUnmergedTree = true)
                        .assertWidthIsEqualTo(72.dp).assertHeightIsEqualTo(36.dp)
                    for (tag in listOf("auth_language_id", "auth_language_en")) {
                        rule.onNodeWithTag(tag).assertIsDisplayed()
                            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                    }
                    rule.onNodeWithTag(if (isIndonesian) "auth_language_id" else "auth_language_en").assertIsSelected()
                }
            }
        }
    }

    @Test fun selectingCurrentLanguageIsStillANoOp() {
        val indonesian = mutableStateOf(true)
        var calls = 0
        rule.setContent {
            IMFITTheme {
                AuthPreferences(false, indonesian.value, {}, { calls++; indonesian.value = !indonesian.value })
            }
        }
        rule.onNodeWithTag("auth_language_id").performClick().assertIsSelected()
        rule.runOnIdle { assertEquals(0, calls) }
        rule.onNodeWithTag("auth_language_en").performClick().assertIsSelected()
        rule.onNodeWithTag("auth_language_en").performClick().assertIsSelected()
        rule.runOnIdle { assertEquals(1, calls) }
        rule.onNodeWithTag("auth_language_id").performClick().assertIsSelected()
        rule.runOnIdle { assertEquals(2, calls) }
    }
}
