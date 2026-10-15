package com.diajarkoding.imfit.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticPaletteTest {
    @Test fun lightStatusTextRemainsReadable() = checkStatusContrast(TrainingLightPalette)
    @Test fun darkStatusTextRemainsReadable() = checkStatusContrast(TrainingDarkPalette)

    private fun checkStatusContrast(palette: IMFITPalette) {
        listOf(
            "success" to (palette.success to palette.successContainer),
            "warning" to (palette.warning to palette.warningContainer),
            "error" to (palette.error to palette.errorContainer)
        ).forEach { (role, pair) ->
            assertTrue("$role foreground/container contrast must be at least 4.5:1", contrast(pair.first, pair.second) >= 4.5f)
        }
    }

    private fun contrast(foreground: Color, background: Color): Float {
        val first = foreground.luminance()
        val second = background.luminance()
        return (maxOf(first, second) + 0.05f) / (minOf(first, second) + 0.05f)
    }
}
