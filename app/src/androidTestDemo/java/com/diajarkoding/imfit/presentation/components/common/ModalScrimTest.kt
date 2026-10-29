package com.diajarkoding.imfit.presentation.components.common

import android.graphics.Bitmap
import android.graphics.Color
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.diajarkoding.imfit.presentation.ui.auth.AuthBirthDatePicker
import com.diajarkoding.imfit.presentation.ui.auth.PhotoSourceSheet
import com.diajarkoding.imfit.presentation.ui.exercise.ExerciseCategory
import com.diajarkoding.imfit.presentation.ui.exercise.MuscleFilterSheet
import com.diajarkoding.imfit.theme.IMFITTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Real presentation components with synthetic state; no account, save, camera or sync work. */
@RunWith(AndroidJUnit4::class)
class ModalScrimTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun dialogSheetsAndSyncShareOneFiftyPercentComposeScrim() {
        assumeTrue(InstrumentationRegistry.getInstrumentation().targetContext.packageName.endsWith(".modaldiagnostic"))
        val kind = mutableStateOf("none")
        val dark = mutableStateOf(false)
        var window: Window? = null
        val records = JSONArray()
        val observer: (android.view.View, Window?) -> Unit = { _, value -> window = value }
        rule.runOnUiThread { rule.activity.enableEdgeToEdge() }
        rule.setContent {
            IMFITTheme(darkTheme = dark.value) {
                Box(Modifier.fillMaxSize().background(ComposeColor.White)) {
                    CompositionLocalProvider(LocalDialogBackdropDiagnostic provides observer) {
                        when (kind.value) {
                            "calendar" -> AuthBirthDatePicker("2000-10-18", { kind.value = "none" }, {})
                            "routine" -> RoutineDialog("Synthetic routine", "Synthetic draft", Symbols.Default.Add,
                                "Save", {}, { kind.value = "none" }) { Text("Draft is retained") }
                            "message" -> IMFITDialog({ kind.value = "none" }, title = "Synthetic message", onConfirm = {})
                            "photo-sheet" -> PhotoSourceSheet({ kind.value = "none" }, {}, {})
                            "filter-sheet" -> MuscleFilterSheet(ExerciseCategory.ALL, { kind.value = "none" }, {})
                        }
                    }
                }
            }
        }
        val directory = File(rule.activity.filesDir, "compose-scrim-evidence").apply { mkdirs() }
        try {
            for (isDark in listOf(false, true)) for (name in listOf("calendar", "routine", "message", "photo-sheet", "filter-sheet")) {
                rule.runOnIdle { dark.value = isDark; kind.value = name; window = null }
                rule.waitForIdle()
                UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle(1_000)
                val bitmap = requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                val pixel = bitmap.getPixel(12, 400)
                assertTrue("$name/$isDark: 50% white-reference scrim", (0..2).all { c ->
                    abs(listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))[c] - 128) <= 2
                })
                val record = JSONObject().put("kind", name).put("dark", isDark)
                    .put("referenceRgb", JSONArray(listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))))
                if (name in listOf("calendar", "routine", "message")) {
                    rule.runOnIdle {
                        val attrs = requireNotNull(window).attributes
                        assertEquals(0f, attrs.dimAmount, 0f)
                        assertEquals(0, attrs.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                        record.put("nativeDim", attrs.dimAmount).put("nativeDimFlag", false)
                    }
                    val bounds = rule.onNodeWithTag("imfit_dialog_scrim").fetchSemanticsNode().boundsInWindow
                    assertEquals(bitmap.width.toFloat(), bounds.width, 2f)
                    assertEquals(bitmap.height.toFloat(), bounds.height, 2f)
                    record.put("contentBounds", rule.onNodeWithTag("imfit_dialog_content").fetchSemanticsNode().boundsInWindow.toString())
                }
                File(directory, "$name-${if (isDark) "dark" else "light"}.png").outputStream().use {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                records.put(record)
                rule.runOnIdle { kind.value = "none" }
                rule.waitForIdle()
            }
        } finally {
            File(directory, "matrix.json").writeText(records.toString(2))
        }
    }

    @Test fun outsideDismissPropertyAndInsideCardTapsArePreserved() {
        val outside = mutableStateOf(false)
        var dismissals = 0
        rule.setContent {
            IMFITTheme {
                IMFITPlatformDialog({ dismissals++ }, DialogProperties(dismissOnClickOutside = outside.value)) {
                    Surface(Modifier.size(180.dp, 120.dp).testTag("card")) { Text("Card") }
                }
            }
        }
        rule.onNodeWithTag("imfit_dialog_scrim").performTouchInput { click(androidx.compose.ui.geometry.Offset(12f, 200f)) }
        rule.runOnIdle { assertEquals(0, dismissals); outside.value = true }
        rule.onNodeWithTag("card").performTouchInput { click() }
        rule.runOnIdle { assertEquals(0, dismissals) }
        rule.onNodeWithTag("imfit_dialog_scrim").performTouchInput { click(androidx.compose.ui.geometry.Offset(12f, 200f)) }
        rule.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun platformDefaultContentWidthMatchesTheOriginalNativeDialog() {
        val native = mutableStateOf(true)
        var themeDetails = ""
        rule.setContent {
            IMFITTheme {
                val content: @androidx.compose.runtime.Composable () -> Unit = {
                    val view = androidx.compose.ui.platform.LocalView.current
                    androidx.compose.runtime.SideEffect {
                        val context = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window?.decorView?.context ?: view.context
                        themeDetails += "native=${native.value}: " + listOf("windowFixedWidthMinor", "windowFixedWidthMajor", "windowMinWidthMinor", "windowMinWidthMajor").joinToString { name ->
                            val id = context.resources.getIdentifier(name, "attr", "android")
                            val value = android.util.TypedValue()
                            "$name=${context.theme.resolveAttribute(id, value, true)}:$value"
                        } + "\n"
                    }
                    Surface(Modifier.fillMaxWidth().height(120.dp).testTag("width_fixture")) { Text("Width fixture") }
                }
                if (native.value) Dialog({}) { IMFITDialogWindowEffects(); content() }
                else IMFITPlatformDialog({}, content = content)
            }
        }
        val before = rule.onNodeWithTag("width_fixture").fetchSemanticsNode().boundsInWindow.width
        rule.runOnIdle { native.value = false }
        val after = rule.onNodeWithTag("width_fixture").fetchSemanticsNode().boundsInWindow.width
        assertEquals(themeDetails, before, after, 2f)
    }
}
