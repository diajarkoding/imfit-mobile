package com.diajarkoding.imfit.presentation.ui.workout

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.diajarkoding.imfit.data.remote.online.WorkoutSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Before
import org.junit.Test

/** Presentation fixtures only: no account, repository, permissions or device setting changes. */
class WorkoutInteractionTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before fun configureWindow() { rule.runOnUiThread { rule.activity.enableEdgeToEdge() } }

    @Test fun compactSetKeepsDecimalWeightStableNumberAndAccessibleTargets() {
        val set = WorkoutSet("set-3", 3, "62.5", 10, false)
        val weight = mutableStateOf(set.weight)
        val reps = mutableStateOf(set.reps.toString())
        var completed = 0
        rule.setContent {
            WorkoutTestEnvironment(width = 320, fontScale = 1.3f) {
                ActiveSetRow(set, "Bench", weight.value, reps.value, true, true, false,
                    onUpdate = { rawWeight, rawReps -> weight.value = rawWeight; reps.value = rawReps },
                    onComplete = { completed++ }, onDelete = {})
            }
        }
        rule.onNodeWithText("Set 3").assertIsDisplayed()
        val field = rule.onNodeWithContentDescription("Bench, set 3, beban dalam kg")
        field.assertIsEnabled().performTextReplacement("67,5")
        rule.runOnIdle { assertEquals("67,5", weight.value); assertEquals("10", reps.value); assertEquals("62.5", set.weight) }
        val complete = rule.onNodeWithContentDescription("Selesaikan Bench, set 3")
        val density = rule.onNodeWithTag("active_set_3").fetchSemanticsNode().boundsInRoot.width / 320f
        val bounds = complete.fetchSemanticsNode().boundsInRoot
        assertTrue(bounds.width + 1 >= 48 * density && bounds.height + 1 >= 48 * density)
        complete.performClick()
        rule.runOnIdle { assertEquals(1, completed) }
    }

    @Test fun pausedAndSavingStatesDisableConflictingSetActions() {
        val paused = mutableStateOf(true)
        val enabled = mutableStateOf(true)
        rule.setContent {
            WorkoutTestEnvironment(indonesian = false) {
                ActiveSetRow(WorkoutSet("set-4", 4, "60", 10, false), "Bench", "60", "10", enabled.value, true, paused.value,
                    onUpdate = { _, _ -> }, onComplete = {}, onDelete = {})
            }
        }
        rule.onNodeWithContentDescription("Complete Bench, set 4").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Bench, set 4, weight in kg").assertIsEnabled()
        rule.runOnIdle { paused.value = false; enabled.value = false }
        rule.onNodeWithContentDescription("Bench, set 4, weight in kg").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Delete Bench, set 4").assertIsNotEnabled()
        rule.onNodeWithContentDescription("Complete Bench, set 4").assertIsNotEnabled()
    }

    @Test fun savingAllowsNewRawRevisionWithoutChangingConfirmedValues() {
        val weight = mutableStateOf("60")
        val reps = mutableStateOf("10")
        val confirmed = WorkoutSet("set-7", 7, "60", 10, false)
        rule.setContent {
            WorkoutTestEnvironment(indonesian = false) {
                ActiveSetRow(confirmed, "Bench", weight.value, reps.value, false, false, false,
                    onUpdate = { w, r -> weight.value = w; reps.value = r }, onComplete = {}, onDelete = {},
                    inputEnabled = true)
            }
        }
        rule.onNodeWithContentDescription("Bench, set 7, weight in kg").performTextReplacement("61,")
        rule.onNodeWithContentDescription("Bench, set 7, reps").performTextClearance()
        rule.runOnIdle {
            assertEquals("61,", weight.value)
            assertEquals("", reps.value)
            assertEquals("60", confirmed.weight)
            assertEquals(10, confirmed.reps)
        }
        rule.onNodeWithContentDescription("Complete Bench, set 7").assertIsNotEnabled()
    }

    @Test fun zeroRestIsSubmittedWithoutAnImplicitDefault() {
        var selected = -1
        rule.setContent {
            WorkoutTestEnvironment(indonesian = false) {
                RestTimerConfigDialog(0, true, {}, false, {}, true, {}, {}, { selected = it })
            }
        }
        rule.onNodeWithText("Save").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(0, selected) }
    }

    @Test fun cancelMessagesFollowResourcesLocaleNotActivityContext() {
        val count = mutableStateOf(0)
        val indonesian = mutableStateOf(true)
        var expected = ""
        rule.setContent {
            WorkoutTestEnvironment(indonesian = indonesian.value) {
                val spec = resolveCancelDialogMessage(count.value, 6)
                expected = LocalResources.current.getString(spec.stringRes, *spec.formatArgs.toTypedArray())
                CancelWorkoutDialog(count.value, 6, onConfirm = {}, onDismiss = {})
            }
        }
        for (isId in listOf(true, false)) {
            for (sets in listOf(0, 1, 2, 6)) {
                rule.runOnIdle { count.value = sets; indonesian.value = isId }
                rule.waitForIdle()
                rule.onNodeWithText(expected).assertIsDisplayed()
                rule.onNodeWithText(if (isId) "Batalkan sesi?" else "Cancel session?").assertIsDisplayed()
                captureWorkoutEvidence("cancel-${if (isId) "id" else "en"}-$sets")
            }
        }
    }

    @Test fun restSettingsCancelDiscardsDraftAndSaveAppliesIt() {
        val open = mutableStateOf(true)
        var soundUpdates = 0
        var duration = 0
        var sound = true
        rule.setContent {
            WorkoutTestEnvironment {
                if (open.value) RestTimerConfigDialog(90, true, {
                    soundUpdates++; sound = it
                }, false, {}, true, {}, { open.value = false }, { duration = it; open.value = false })
            }
        }
        rule.onNodeWithText("Bunyi selesai istirahat").performClick()
        rule.onNodeWithText("Batal").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(0, soundUpdates); assertEquals(0, duration); open.value = true }
        rule.onNodeWithText("Simpan").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(1, soundUpdates)
            assertTrue(sound)
            assertEquals(90, duration)
        }
    }

    @Test fun dialogMatrixKeepsActionsReachableAt320AndLargeFonts() {
        val mode = mutableStateOf(0)
        val compact = mutableStateOf(false)
        val dark = mutableStateOf(false)
        val english = mutableStateOf(false)
        rule.setContent {
            WorkoutTestEnvironment(indonesian = !english.value, dark = dark.value,
                width = if (compact.value) 320 else 412, fontScale = if (compact.value) 1.5f else 1f) {
                when (mode.value) {
                    0 -> CancelWorkoutDialog(6, 6, {}, {})
                    1 -> RestTimerConfigDialog(90, true, {}, false, {}, false, {}, {}, {})
                    else -> RestTimerCountdownDialog(72, "Barbell Bench Press — Wide Grip", {}, {}, {}, totalSeconds = 90f)
                }
            }
        }
        for (type in 0..2) {
            for (variant in 0..3) {
                rule.runOnIdle {
                    mode.value = type
                    compact.value = variant >= 2
                    dark.value = variant % 2 == 1
                    english.value = variant % 2 == 1
                }
                val action = when (type) {
                    0 -> if (english.value) "Cancel session" else "Batalkan sesi"
                    1 -> if (english.value) "Save" else "Simpan"
                    else -> if (english.value) "Skip" else "Lewati"
                }
                rule.onNodeWithText(action).performScrollTo().assertIsDisplayed().assertIsEnabled()
                if (type == 2) {
                    val reduce = rule.onNodeWithText(if (english.value) "−30 seconds" else "−30 detik")
                    val extend = rule.onNodeWithText(if (english.value) "+30 seconds" else "+30 detik")
                    reduce.performScrollTo().assertIsDisplayed().assertIsEnabled()
                    extend.assertIsDisplayed().assertIsEnabled()
                    val left = reduce.fetchSemanticsNode().boundsInRoot
                    val right = extend.fetchSemanticsNode().boundsInRoot
                    assertTrue(left.right <= right.left)
                    assertEquals(left.top, right.top, 1f)
                }
                captureWorkoutEvidence("dialog-$type-variant-$variant")
            }
        }
    }
}
