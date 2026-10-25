package com.diajarkoding.imfit.presentation.ui.workout

import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.diajarkoding.imfit.theme.IMFITTheme
import java.io.File
import java.util.Locale

/** Real layout constraints at the requested dp width, without changing device settings. */
@Composable
internal fun WorkoutTestEnvironment(
    indonesian: Boolean = true,
    dark: Boolean = false,
    width: Int = 412,
    fontScale: Float = 1f,
    content: @Composable () -> Unit
) {
    val base = LocalConfiguration.current
    val context = LocalContext.current
    val config = remember(base, indonesian, width, fontScale) {
        Configuration(base).apply {
            setLocale(Locale.forLanguageTag(if (indonesian) "id-ID" else "en-US"))
            screenWidthDp = width
            this.fontScale = fontScale
        }
    }
    val resources = remember(context, config) { context.createConfigurationContext(config).resources }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalConfiguration provides config,
            LocalResources provides resources,
            LocalDensity provides Density(constraints.maxWidth.toFloat() / width, fontScale)
        ) {
            IMFITTheme(darkTheme = dark, content = content)
        }
    }
}

internal fun captureWorkoutEvidence(name: String) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.uiAutomation.waitForIdle(200, 3_000)
    val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
    val directory = File(output ?: instrumentation.targetContext.filesDir.absolutePath, "workout-redesign-evidence").apply { mkdirs() }
    val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
    File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    bitmap.recycle()
}
