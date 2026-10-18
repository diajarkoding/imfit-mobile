package com.diajarkoding.imfit.presentation.components.common

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Build
import android.view.Choreographer
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.theme.IMFITOpacity
import com.diajarkoding.imfit.theme.IMFITTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Actual create-routine dialog; no MainActivity, account, save, repository or timer. */
@RunWith(AndroidJUnit4::class)
class RoutineBackdropDiagnosticTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val events = JSONArray()
    private val cases = JSONArray()
    private val failures = mutableListOf<String>()
    private val directory by lazy { File(instrumentation.targetContext.filesDir, "modal-diagnostic").apply { mkdirs() } }
    private var scenario = ""
    private var diagnosticView: View? = null
    private var diagnosticWindow: Window? = null
    private var backgroundClicks = 0

    @Test fun createRoutineComposeScrimAtT0T1T2WithoutNativeDimOrBlur() {
        assumeTrue("Build with -PmodalDiagnostic=true; skip this fixture in the installed demo package",
            instrumentation.targetContext.packageName.endsWith(".modaldiagnostic"))
        val visible = mutableStateOf(false)
        val name = mutableStateOf("Synthetic routine")
        val observer: (View, Window?) -> Unit = { view, window ->
            diagnosticView = view
            diagnosticWindow = window
            snapshot("T0-native-effects-disabled", view, window)
        }
        rule.setContent {
            IMFITTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().background(Color.White)) {
                    Canvas(Modifier.fillMaxSize()) {
                        val unit = 4.dp.toPx()
                        val start = size.width - 64.dp.toPx()
                        for (x in 0..16) for (y in 0..(size.height / unit).toInt()) {
                            drawRect(if ((x + y) % 2 == 0) Color.Black else Color.White,
                                Offset(start + x * unit, y * unit), Size(unit, unit))
                        }
                    }
                    Column(Modifier.align(Alignment.TopCenter).padding(top = 64.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("SYNTHETIC WORKOUT BACKGROUND", color = Color.Black)
                        Text("No account, session or repository", color = Color.Black)
                    }
                    Button(onClick = { backgroundClicks++ }, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp)) {
                        Text("Background action")
                    }
                    if (visible.value) CompositionLocalProvider(LocalDialogBackdropDiagnostic provides observer) {
                        RoutineDialog(
                            title = instrumentation.targetContext.getString(R.string.home_create_workout_title),
                            message = instrumentation.targetContext.getString(R.string.home_create_workout_message),
                            icon = Symbols.Default.Add,
                            confirmText = instrumentation.targetContext.getString(R.string.routine_create_action),
                            onConfirm = {}, onDismiss = { visible.value = false }
                        ) { RoutineNameField(name.value, { name.value = it }) }
                    }
                }
            }
        }
        try {
            run {
                scenario = "light-compose-scrim"
                diagnosticView = null
                diagnosticWindow = null
                rule.runOnIdle { name.value = "Synthetic routine" }
                val activityBefore = rule.runOnIdle { activityAttributes() }
                val baseline = capture("baseline", false)
                val backgroundAction = rule.onNodeWithText("Background action").fetchSemanticsNode().boundsInWindow
                rule.runOnIdle { visible.value = true }
                settleFrames()
                rule.runOnIdle { snapshot("T1-after-render", diagnosticView, diagnosticWindow) }
                val shown = capture("T1", true)
                for (index in 1..3) {
                    rule.runOnIdle { name.value = "Synthetic routine $index" }
                    settleFrames()
                    rule.runOnIdle { snapshot("T2.$index-after-recomposition", diagnosticView, diagnosticWindow) }
                }
                val recomposed = capture("T2", true)
                val result = JSONObject().put("case", scenario).put("baseline", baseline)
                    .put("T1", shown).put("T2", recomposed)
                for (stage in listOf(shown, recomposed)) {
                    val rgb = stage.getJSONArray("whiteReferenceRgb")
                    val darkened = (0..2).all { rgb.getDouble(it) < baseline.getJSONArray("whiteReferenceRgb").getDouble(it) - 20 }
                    if (!darkened) failures.add("$scenario: background not visibly darkened at ${stage.getString("phase")}")
                    result.put("visualDarkening-" + stage.getString("phase"), darkened)
                    val card = stage.getJSONArray("cardRgb")
                    if ((0..2).any { card.getDouble(it) < 245 }) failures.add("$scenario: card is not light at ${stage.getString("phase")}")
                }
                UiDevice.getInstance(instrumentation).click(backgroundAction.center.x.roundToInt(), backgroundAction.center.y.roundToInt())
                settleFrames()
                rule.runOnIdle {
                    result.put("backgroundClicks", backgroundClicks).put("outsideDismissed", !visible.value)
                    if (backgroundClicks != 0) failures.add("$scenario: background action received a modal click")
                    if (visible.value) failures.add("$scenario: normal routine outside-dismiss callback not observed")
                    visible.value = false
                }
                settleFrames()
                val activityAfter = rule.runOnIdle { activityAttributes() }
                val restored = activityBefore.toString() == activityAfter.toString()
                if (!restored) failures.add("$scenario: Activity window attributes changed")
                result.put("activityWindowUnchanged", restored).put("activityBefore", activityBefore).put("activityAfter", activityAfter)
                cases.put(result)
            }
            for (index in 0 until events.length()) {
                val event = events.getJSONObject(index)
                if (!event.getBoolean("windowFound") || event.getBoolean("dimFlag") || abs(event.optDouble("dimAmount", 0.0)) > 0.001) {
                    failures.add("${event.getString("case")}/${event.getString("phase")}: window/dim acceptance failed")
                }
                if (Build.VERSION.SDK_INT >= 31 && (event.getBoolean("blurFlag") || event.getInt("blurRadiusPx") != 0)) {
                    failures.add("${event.getString("phase")}: blur must remain disabled")
                }
            }
        } finally {
            val report = JSONObject().put("api", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT)
                .put("package", instrumentation.targetContext.packageName)
                .put("apkSha256", InstrumentationRegistry.getArguments().getString("apkSha256"))
                .put("fixture", "Actual RoutineDialog + RoutineNameField; autofocus off; no save")
                .put("composeScrimToken", IMFITOpacity.scrim)
                .put("cases", cases).put("events", events).put("failures", JSONArray(failures))
            File(directory, "report.json").writeText(report.toString(2))
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun snapshot(phase: String, view: View?, window: Window?) {
        val chain = JSONArray()
        var cursor = view
        var traversed: Window? = null
        while (cursor != null) {
            chain.put(cursor.javaClass.name)
            if (cursor is DialogWindowProvider && traversed == null) traversed = cursor.window
            cursor = cursor.parent as? View
        }
        val attrs = window?.attributes
        val event = JSONObject().put("case", scenario).put("phase", phase)
            .put("windowFound", window != null).put("windowIdentity", window?.let(System::identityHashCode))
            .put("viewHierarchy", chain).put("traversalFound", traversed != null)
            .put("directEqualsTraversal", window != null && window === traversed)
            .put("dimFlag", attrs != null && attrs.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
            .put("dimAmount", attrs?.dimAmount).put("flags", attrs?.flags)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) event
            .put("blurFlag", attrs != null && attrs.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0)
            .put("blurRadiusPx", attrs?.blurBehindRadius)
            .put("crossWindowBlurEnabled", rule.activity.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled)
        events.put(event)
    }

    private fun activityAttributes(): JSONObject {
        val attrs = rule.activity.window.attributes
        return JSONObject().put("flags", attrs.flags).put("dimAmount", attrs.dimAmount)
    }

    private fun settleFrames() {
        rule.waitForIdle()
        val latch = CountDownLatch(1)
        rule.runOnUiThread {
            Choreographer.getInstance().postFrameCallback {
                Choreographer.getInstance().postFrameCallback { latch.countDown() }
            }
        }
        check(latch.await(5, TimeUnit.SECONDS)) { "Rendered-frame barrier timed out" }
        instrumentation.waitForIdleSync()
        UiDevice.getInstance(instrumentation).waitForIdle(1_000)
    }

    private fun capture(phase: String, dialog: Boolean): JSONObject {
        settleFrames()
        val bitmap = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val density = rule.activity.resources.displayMetrics.density
        val result = JSONObject().put("phase", phase).put("width", bitmap.width).put("height", bitmap.height)
        val reference = rgb(bitmap, (8 * density).roundToInt(), bitmap.height / 2, (4 * density).roundToInt())
        result.put("whiteReferenceRgb", JSONArray(reference.toList()))
        if (dialog) {
            val bounds = rule.onNodeWithTag("imfit_dialog_content").fetchSemanticsNode().boundsInWindow
            val location = IntArray(2)
            rule.runOnIdle { diagnosticWindow?.decorView?.getLocationOnScreen(location) }
            val x = location[0] + bounds.left.roundToInt() + (40 * density).roundToInt()
            val y = location[1] + bounds.top.roundToInt() + (40 * density).roundToInt()
            result.put("cardRgb", JSONArray(rgb(bitmap, x, y, (4 * density).roundToInt()).toList()))
            result.put("cardSampleXY", JSONArray(listOf(x, y)))
        }
        val x = bitmap.width - (12 * density).roundToInt()
        val y = bitmap.height / 2
        val samples = (0 until (32 * density).roundToInt()).map { AndroidColor.red(bitmap.getPixel(x, y + it)).toDouble() }
        val mean = samples.average()
        result.put("checkerboardStdDev", sqrt(samples.sumOf { (it - mean) * (it - mean) } / samples.size))
        File(directory, "$scenario-$phase.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return result
    }

    private fun rgb(bitmap: Bitmap, x: Int, y: Int, side: Int): DoubleArray {
        val sums = DoubleArray(3)
        for (dx in 0 until side) for (dy in 0 until side) {
            val pixel = bitmap.getPixel((x + dx).coerceIn(0, bitmap.width - 1), (y + dy).coerceIn(0, bitmap.height - 1))
            sums[0] += AndroidColor.red(pixel); sums[1] += AndroidColor.green(pixel); sums[2] += AndroidColor.blue(pixel)
        }
        return sums.map { it / (side * side) }.toDoubleArray()
    }
}
