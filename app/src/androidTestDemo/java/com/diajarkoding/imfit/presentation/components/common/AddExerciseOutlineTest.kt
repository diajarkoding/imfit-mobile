package com.diajarkoding.imfit.presentation.components.common

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.diajarkoding.imfit.theme.IMFITColors
import com.diajarkoding.imfit.theme.IMFITTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class AddExerciseOutlineTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun outlinedAddBorderUsesTheTextPrimaryInBothThemes() {
        val dark = mutableStateOf(false)
        var primary = 0
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        rule.setContent {
            IMFITTheme(darkTheme = dark.value) {
                val color = IMFITColors.training.primary
                SideEffect { primary = color.toArgb() }
                Box(Modifier.fillMaxSize().padding(top = 80.dp)) {
                    RoutineButton("Add exercise fixture", {}, outlined = true, matchOutlineToContent = true,
                        modifier = Modifier.testTag("add_outline"))
                }
            }
        }
        for (value in listOf(false, true)) {
            rule.runOnIdle { dark.value = value }
            val bitmap = rule.onNodeWithTag("add_outline").captureToImage().asAndroidBitmap()
            val pixels = (0 until minOf(16, bitmap.height)).map { bitmap.getPixel(bitmap.width / 2, it) }
            assertTrue("Theme=$value expected=${primary.toUInt().toString(16)} border=${pixels.map { it.toUInt().toString(16) }}",
                pixels.any { actual -> listOf(0, 8, 16).all { shift -> kotlin.math.abs((actual shr shift and 255) - (primary shr shift and 255)) <= 2 } })
        }
    }
}
