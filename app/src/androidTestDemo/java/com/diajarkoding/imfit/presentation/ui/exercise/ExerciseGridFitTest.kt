package com.diajarkoding.imfit.presentation.ui.exercise

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.diajarkoding.imfit.theme.IMFITTheme
import org.junit.Rule
import org.junit.Test

class ExerciseGridFitTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Test fun largeTextOnShortViewportCanScrollToTheFinalCategoryWithoutClipping() {
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                IMFITTheme { Box(Modifier.width(320.dp).height(350.dp)) { ExerciseBrowserScreen {} } }
            }
        }
        rule.onNodeWithTag("exercise_category_grid").performScrollToIndex(11)
        rule.onNodeWithTag("category_ALL").assertIsDisplayed().assertHeightIsAtLeast(48.dp)
    }
}
