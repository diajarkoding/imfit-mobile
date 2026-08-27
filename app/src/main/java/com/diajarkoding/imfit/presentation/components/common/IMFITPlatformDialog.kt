package com.diajarkoding.imfit.presentation.components.common

import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider

private const val DialogDimAmount = 0.28f
private val DialogBlurRadius = 10.dp

@Composable
fun IMFITPlatformDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = properties
    ) {
        IMFITDialogBackdrop()
        content()
    }
}

@Composable
fun IMFITDialogBackdrop(
    blurRadius: Dp = DialogBlurRadius,
    dimAmount: Float = DialogDimAmount
) {
    if (LocalInspectionMode.current) return

    val view = LocalView.current
    val blurRadiusPx = with(LocalDensity.current) { blurRadius.roundToPx() }
    val window = (view.parent as? DialogWindowProvider)?.window

    DisposableEffect(window, blurRadiusPx, dimAmount) {
        val originalFlags = window?.attributes?.flags ?: 0
        val originalDimAmount = window?.attributes?.dimAmount ?: 0f
        val originalBlurRadius = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            window?.attributes?.blurBehindRadius ?: 0
        } else {
            0
        }

        window?.let {
            it.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            val attributes = it.attributes
            attributes.dimAmount = dimAmount

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                it.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                attributes.blurBehindRadius = blurRadiusPx
            }

            it.attributes = attributes
        }

        onDispose {
            window?.let {
                val attributes = it.attributes
                attributes.dimAmount = originalDimAmount

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    attributes.blurBehindRadius = originalBlurRadius
                }

                it.attributes = attributes
                restoreWindowFlag(
                    window = it,
                    flag = WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                    wasEnabled = originalFlags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    restoreWindowFlag(
                        window = it,
                        flag = WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
                        wasEnabled = originalFlags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0
                    )
                }
            }
        }
    }
}

private fun restoreWindowFlag(window: android.view.Window, flag: Int, wasEnabled: Boolean) {
    if (wasEnabled) {
        window.addFlags(flag)
    } else {
        window.clearFlags(flag)
    }
}
