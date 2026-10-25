package com.diajarkoding.imfit.presentation.components.common

import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogWindowProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.diajarkoding.imfit.theme.IMFITTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated UI fixtures: no MainActivity, account, repository, session or timer engine. */
@RunWith(AndroidJUnit4::class)
class ModalDimTest {
    @get:Rule val rule = createComposeRule()

    @Test fun platformDialogDisablesNativeDimInLightAndDarkThemes() {
        val dark = mutableStateOf(false)
        var window: Window? = null
        rule.setContent {
            IMFITTheme(darkTheme = dark.value) {
                IMFITPlatformDialog(onDismissRequest = {}) {
                    CaptureWindow { window = it }
                    Text("Timer fixture")
                }
            }
        }
        assertNativeEffectsDisabled { window }
        rule.runOnIdle { dark.value = true }
        assertNativeEffectsDisabled { window }
    }

    @Test fun confirmationDialogInheritsTheSharedBackdrop() {
        var window: Window? = null
        rule.setContent {
            IMFITTheme {
                IMFITDialog(
                    onDismissRequest = {}, title = "Cancel fixture",
                    onConfirm = {}, content = { CaptureWindow { window = it } }
                )
            }
        }
        assertNativeEffectsDisabled { window }
    }

    @Test fun inputDialogInheritsTheSharedBackdrop() {
        var window: Window? = null
        rule.setContent {
            IMFITTheme {
                IMFITInputDialog(onDismissRequest = {}, title = "Input fixture", onConfirm = {}) {
                    CaptureWindow { window = it }
                    Text("Draft fixture")
                }
            }
        }
        assertNativeEffectsDisabled { window }
    }

    @Test fun routineDialogKeepsItsProcessingDismissGuard() {
        val processing = mutableStateOf(true)
        var dismissals = 0
        var window: Window? = null
        rule.setContent {
            IMFITTheme {
                RoutineDialog(
                    title = "Routine fixture", message = "Synthetic draft",
                    icon = Symbols.Default.Check, confirmText = "Save fixture",
                    onConfirm = {}, onDismiss = { dismissals++ }, processing = processing.value
                ) { CaptureWindow { window = it } }
            }
        }
        assertNativeEffectsDisabled { window }
        pressBack()
        rule.runOnIdle { assertEquals(0, dismissals); processing.value = false }
        pressBack()
        rule.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun dismissibleDialogStillRequestsDismissOnBack() {
        var dismissals = 0
        rule.setContent {
            IMFITTheme {
                IMFITPlatformDialog(onDismissRequest = { dismissals++ }) {
                    Text("Dismissible fixture")
                }
            }
        }
        rule.waitForIdle()
        pressBack()
        rule.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test fun countdownStyleDialogDoesNotCloseOnBackButExplicitSkipCanCloseIt() {
        val visible = mutableStateOf(true)
        rule.setContent {
            IMFITTheme {
                Text("Session fixture")
                if (visible.value) IMFITPlatformDialog(onDismissRequest = {}) {
                    TextButton(onClick = { visible.value = false }) { Text("Skip fixture") }
                }
            }
        }
        rule.waitForIdle()
        pressBack()
        rule.onNodeWithText("Skip fixture").assertIsDisplayed().performClick()
        rule.runOnIdle { assertFalse(visible.value) }
    }

    @Test fun backdropRestoresWindowDimAndFlagWhenRemoved() {
        val backdrop = mutableStateOf(true)
        var window: Window? = null
        var originalFlags = 0
        var originalDim = 0f
        rule.setContent {
            IMFITTheme {
                Dialog(onDismissRequest = {}) {
                    val dialogWindow = requireNotNull((LocalView.current.parent as? DialogWindowProvider)?.window)
                    remember(dialogWindow) {
                        originalFlags = dialogWindow.attributes.flags
                        originalDim = dialogWindow.attributes.dimAmount
                        dialogWindow
                    }
                    SideEffect { window = dialogWindow }
                    if (backdrop.value) IMFITDialogWindowEffects()
                    Text("Restoration fixture")
                }
            }
        }
        assertNativeEffectsDisabled { window }
        rule.runOnIdle { backdrop.value = false }
        rule.runOnIdle {
            val attributes = requireNotNull(window).attributes
            assertEquals(originalDim, attributes.dimAmount, 0f)
            assertEquals(
                originalFlags and WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND
            )
        }
    }

    private fun assertNativeEffectsDisabled(window: () -> Window?) {
        rule.runOnIdle {
            val attributes = requireNotNull(window()).attributes
            assertEquals(0f, attributes.dimAmount, 0.001f)
            assertEquals(0, attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                assertEquals(0, attributes.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                assertEquals(0, attributes.blurBehindRadius)
            }
        }
    }

    private fun pressBack() {
        rule.waitForIdle()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).pressBack()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).waitForIdle(1_000)
        rule.waitForIdle()
    }

    @Composable
    private fun CaptureWindow(onWindow: (Window) -> Unit) {
        val window = requireNotNull((LocalView.current.parent as? DialogWindowProvider)?.window)
        SideEffect { onWindow(window) }
    }
}
