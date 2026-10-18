package com.diajarkoding.imfit.presentation.components.common

import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.view.Choreographer
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.diajarkoding.imfit.MainActivity
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.theme.IMFITOpacity
import com.diajarkoding.imfit.theme.LocaleManager
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

/** Real MainActivity -> NavGraph -> MainScreen -> HomeScreen. Observe only; never save. */
@RunWith(AndroidJUnit4::class)
class HomeBackdropDiagnosticTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val directory by lazy { File(instrumentation.targetContext.filesDir, "home-backdrop-diagnostic").apply { mkdirs() } }
    private val events = JSONArray()
    private val captures = JSONArray()
    private val failures = mutableListOf<String>()
    private var dialogView: View? = null
    private var dialogWindow: Window? = null
    private var observerCalls = 0
    private var activityBefore: String? = null
    private var activityAfter: String? = null
    private var draftAfterInput: String? = null
    private var dismissed = false

    @Test fun actualHomeRoutineDialogWindowAndSystemPixelsAtT0T1T2() {
        assumeTrue("Only install the isolated synthetic demo package",
            instrumentation.targetContext.packageName.endsWith(".modaldiagnostic"))
        assumeTrue(Build.VERSION.SDK_INT == 34)
        try {
            waitForText(text(R.string.redesign_ready))
            rule.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text(R.string.home_add_day)))
            rule.onNodeWithText(text(R.string.home_add_day)).assertIsEnabled()
            settleFrames()
            activityBefore = rule.runOnIdle { activityAttributes().toString() }
            capture("baseline")
            rule.runOnIdle {
                DialogBackdropDiagnosticBridge.observer = { view, window ->
                    observerCalls++
                    dialogView = view
                    dialogWindow = window
                    snapshot("T0-after-assignment")
                }
            }
            rule.onNodeWithText(text(R.string.home_add_day)).performClick()
            waitForText(text(R.string.home_create_workout_title))
            settleFrames()
            rule.runOnIdle { snapshot("T1-autofocus") }
            capture("T1-autofocus")
            // Keep autofocus behavior intact. Also compare a matched, keyboard-free scene.
            Espresso.closeSoftKeyboard()
            settleFrames()
            rule.runOnIdle { snapshot("T1-ime-hidden") }
            capture("T1-ime-hidden")
            val field = rule.onNode(hasSetTextAction())
            for (index in 1..3) {
                field.performTextReplacement("Synthetic Home routine $index")
                settleFrames()
                field.assertTextContains("Synthetic Home routine $index")
                rule.runOnIdle { snapshot("T2.$index-input") }
            }
            draftAfterInput = "Synthetic Home routine 3"
            capture("T2")
            // Native input on the Home add action underneath the modal must only dismiss.
            val action = rule.onNode(hasText(text(R.string.home_add_day)) and hasClickAction()).fetchSemanticsNode().boundsInWindow
            val location = IntArray(2)
            rule.runOnIdle { rule.activity.window.decorView.getLocationOnScreen(location) }
            val x = location[0] + action.center.x.roundToInt()
            val y = location[1] + action.center.y.roundToInt()
            val dialogBounds = rule.onNodeWithTag("imfit_dialog_content").fetchSemanticsNode().boundsInWindow
            val dialogLocation = IntArray(2)
            rule.runOnIdle { dialogWindow?.decorView?.getLocationOnScreen(dialogLocation) }
            val insideCard = x >= dialogLocation[0] + dialogBounds.left && x <= dialogLocation[0] + dialogBounds.right &&
                y >= dialogLocation[1] + dialogBounds.top && y <= dialogLocation[1] + dialogBounds.bottom
            // If Home's add action is covered, use the known outside left margin instead.
            UiDevice.getInstance(instrumentation).click(if (insideCard) dp(8f) else x, if (insideCard) dp(160f) else y)
            settleFrames()
            dismissed = rule.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty()
            if (!dismissed) failures.add("Outside native click did not dismiss")
            activityAfter = rule.runOnIdle { activityAttributes().toString() }
            capture("after-dismiss")
            if (activityBefore != activityAfter) failures.add("Activity attributes changed")
            // No create action was invoked. Reopen through Home to check normal draft dismissal.
            rule.onNodeWithText(text(R.string.home_add_day)).performClick()
            rule.onNode(hasSetTextAction()).assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
            rule.onNodeWithText(text(R.string.action_cancel)).performClick()
            if (observerCalls != 2) failures.add("Expected one effect callback per opening, got $observerCalls")
            for (index in 0 until events.length()) {
                val event = events.getJSONObject(index)
                if (!event.getBoolean("windowFound") || event.getBoolean("dimFlag") ||
                    abs(event.optDouble("dimAmount", 0.0)) > 0.001) {
                    failures.add("${event.getString("phase")}: window/dim mismatch")
                }
                if (event.getBoolean("blurFlag") || event.getInt("blurRadiusPx") != 0) {
                    failures.add("${event.getString("phase")}: blur must remain disabled")
                }
            }
            val baseline = captures.getJSONObject(0).getJSONArray("systemRois")
            for (index in 1..3) {
                val capture = captures.getJSONObject(index)
                val rois = capture.getJSONArray("systemRois")
                for (roiIndex in 0 until rois.length()) {
                    val before = baseline.getJSONObject(roiIndex).getJSONArray("rgb")
                    val after = rois.getJSONObject(roiIndex).getJSONArray("rgb")
                    // Quantization tolerance; all sampled ROIs are outside the card and IME.
                    for (channel in 0..2) {
                        val expected = before.getDouble(channel) * (1 - IMFITOpacity.scrim)
                        if (abs(after.getDouble(channel) - expected) > 3) {
                            failures.add("${capture.getString("phase")}/ROI$roiIndex: $after expected ${IMFITOpacity.scrim} scrim of $before")
                        }
                    }
                }
            }
            val restored = captures.getJSONObject(4).getJSONArray("systemRois")
            for (index in 0 until baseline.length()) {
                val before = baseline.getJSONObject(index).getJSONArray("rgb")
                val after = restored.getJSONObject(index).getJSONArray("rgb")
                if ((0..2).any { abs(before.getDouble(it) - after.getDouble(it)) > 2 }) {
                    failures.add("ROI$index: residual dim after dismiss")
                }
            }
        } catch (failure: Throwable) {
            failures.add("Fixture error: ${failure.javaClass.simpleName}: ${failure.message}")
            throw failure
        } finally {
            rule.runOnUiThread { DialogBackdropDiagnosticBridge.observer = null }
            val report = JSONObject().put("api", Build.VERSION.SDK_INT).put("fingerprint", Build.FINGERPRINT)
                .put("package", instrumentation.targetContext.packageName)
                .put("apkSha256", InstrumentationRegistry.getArguments().getString("apkSha256"))
                .put("fixture", "Unmodified MainActivity navigation and real Home create dialog; autofocus on; no save")
                .put("composeScrimToken", IMFITOpacity.scrim).put("events", events).put("captures", captures)
                .put("observerCalls", observerCalls).put("draftAfterInput", draftAfterInput)
                .put("outsideDismissed", dismissed).put("activityBefore", activityBefore).put("activityAfter", activityAfter)
                .put("failures", JSONArray(failures))
            File(directory, "report.json").writeText(report.toString(2))
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun text(id: Int) = LocaleManager.getUpdatedContext(rule.activity).getString(id)
    private fun waitForText(value: String) {
        rule.waitUntil(15_000) { rule.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun activityAttributes(): JSONObject {
        val attrs = rule.activity.window.attributes
        return JSONObject().put("flags", attrs.flags).put("dimAmount", attrs.dimAmount)
    }

    private fun snapshot(phase: String) {
        val view = dialogView
        val window = dialogWindow
        val attrs = window?.attributes
        val chain = JSONArray()
        var cursor = view
        var traversed: Window? = null
        while (cursor != null) {
            chain.put(cursor.javaClass.name)
            if (cursor is DialogWindowProvider && traversed == null) traversed = cursor.window
            cursor = cursor.parent as? View
        }
        val event = JSONObject().put("phase", phase).put("windowFound", window != null)
            .put("windowIdentity", window?.let(System::identityHashCode))
            .put("directParentProvider", view?.parent is DialogWindowProvider)
            .put("viewHierarchy", chain).put("traversalFound", traversed != null)
            .put("directEqualsTraversal", window != null && window === traversed)
            .put("dimFlag", attrs != null && attrs.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
            .put("dimAmount", attrs?.dimAmount).put("flags", attrs?.flags)
            .put("viewAttached", view?.isAttachedToWindow).put("decorShown", window?.decorView?.isShown)
            .put("hasWindowFocus", window?.decorView?.hasWindowFocus())
            .put("imeVisible", view?.let { ViewCompat.getRootWindowInsets(it)?.isVisible(WindowInsetsCompat.Type.ime()) })
            .put("windowWidth", attrs?.width).put("windowHeight", attrs?.height)
        if (Build.VERSION.SDK_INT >= 31) event.put("blurRadiusPx", attrs?.blurBehindRadius)
            .put("blurFlag", attrs != null && attrs.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0)
            .put("crossWindowBlurEnabled", rule.activity.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled)
        events.put(event)
    }

    private fun settleFrames() {
        rule.waitForIdle()
        val latch = CountDownLatch(1)
        rule.runOnUiThread { Choreographer.getInstance().postFrameCallback {
            Choreographer.getInstance().postFrameCallback { latch.countDown() }
        } }
        check(latch.await(5, TimeUnit.SECONDS)) { "Frame barrier timed out" }
        instrumentation.waitForIdleSync()
        UiDevice.getInstance(instrumentation).waitForIdle(1_000)
    }

    private fun dp(value: Float) = (value * rule.activity.resources.displayMetrics.density).roundToInt()

    private fun capture(phase: String) {
        settleFrames()
        val system = requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        val result = JSONObject().put("phase", phase).put("width", system.width).put("height", system.height)
        result.put("systemRois", rois(system, 0, 0))
        writeBitmap(system, "$phase-system.png")
        // Dialog's IsDialog marker is a descendant, not necessarily on its root.
        // Resolve by the root-tab label (dialog title and Home add action share text).
        val activityRoot = rule.onNode(isRoot() and hasAnyDescendant(hasText(text(R.string.nav_home))))
        val rootBounds = activityRoot.fetchSemanticsNode().boundsInWindow
        val location = IntArray(2)
        rule.runOnIdle { rule.activity.window.decorView.getLocationOnScreen(location) }
        val compose = activityRoot.captureToImage().asAndroidBitmap()
        val originX = location[0] + rootBounds.left.roundToInt()
        val originY = location[1] + rootBounds.top.roundToInt()
        result.put("composeRootOrigin", JSONArray(listOf(originX, originY)))
            .put("composeRois", rois(compose, originX, originY))
        writeBitmap(compose, "$phase-compose-activity.png")
        if (phase != "baseline" && phase != "after-dismiss") {
            val root = rule.onNode(isDialog())
            val bounds = rule.onNodeWithTag("imfit_dialog_content").fetchSemanticsNode().boundsInWindow
            val dialogLocation = IntArray(2)
            rule.runOnIdle { dialogWindow?.decorView?.getLocationOnScreen(dialogLocation) }
            val x = dialogLocation[0] + bounds.left.roundToInt() + dp(40f)
            val y = dialogLocation[1] + bounds.top.roundToInt() + dp(40f)
            val surface = averageRgb(system, x, y, dp(3f))
            result.put("dialogSurfaceSampleXY", JSONArray(listOf(x, y))).put("dialogSurfaceRgb", JSONArray(surface))
            if (surface.any { it < 252 }) failures.add("$phase: light dialog surface is not white: $surface")
            val cardImage = root.captureToImage().asAndroidBitmap()
            writeBitmap(cardImage, "$phase-compose-dialog.png")
        }
        captures.put(result)
    }

    private fun rois(bitmap: Bitmap, originX: Int, originY: Int): JSONArray {
        val rois = JSONArray()
        for (yDp in listOf(140f, 180f, 220f)) {
            val x = dp(8f); val y = dp(yDp); val side = dp(3f)
            rois.put(JSONObject().put("screenXY", JSONArray(listOf(x, y))).put("sidePx", side)
                .put("rgb", JSONArray(averageRgb(bitmap, x - originX, y - originY, side))))
        }
        return rois
    }

    private fun averageRgb(bitmap: Bitmap, x: Int, y: Int, side: Int): List<Double> {
        val sums = DoubleArray(3)
        for (dx in 0 until side) for (dy in 0 until side) {
            val pixel = bitmap.getPixel(x + dx, y + dy)
            sums[0] += Color.red(pixel); sums[1] += Color.green(pixel); sums[2] += Color.blue(pixel)
        }
        return sums.map { it / (side * side) }
    }

    private fun writeBitmap(bitmap: Bitmap, name: String) {
        File(directory, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
