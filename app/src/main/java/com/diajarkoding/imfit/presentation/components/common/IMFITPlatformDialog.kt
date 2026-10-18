package com.diajarkoding.imfit.presentation.components.common

import android.os.Build
import android.util.TypedValue
import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.diajarkoding.imfit.BuildConfig
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.theme.IMFITOpacity
import com.diajarkoding.imfit.theme.Scrim

// Opt-in observer for instrumented diagnostics. No observer or logging in normal UI.
internal val LocalDialogBackdropDiagnostic =
    staticCompositionLocalOf<((View, Window?) -> Unit)?> { null }

// Instrumentation can observe the real MainActivity tree without replacing setContent.
// Read only by isolated debug builds; normal UI has no observer or diagnostic logging.
internal object DialogBackdropDiagnosticBridge {
    @Volatile var observer: ((View, Window?) -> Unit)? = null
}

@Composable
fun IMFITPlatformDialog(
    onDismissRequest: () -> Unit,
    properties: DialogProperties = DialogProperties(),
    content: @Composable () -> Unit
) {
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val parentResources = LocalResources.current
    val parentDensity = LocalDensity.current
    val dismiss by rememberUpdatedState(onDismissRequest)
    Dialog(
        onDismissRequest = onDismissRequest,
        // A full-window Compose surface owns the scrim. Back/secure/token contracts stay native.
        properties = DialogProperties(
            dismissOnBackPress = properties.dismissOnBackPress,
            dismissOnClickOutside = false,
            securePolicy = properties.securePolicy,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            windowTitle = properties.windowTitle,
            windowType = properties.windowType,
            windowToken = properties.windowToken
        )
    ) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
            LocalResources provides parentResources,
            LocalDensity provides parentDensity
        ) {
            IMFITDialogWindowEffects()
            Box(Modifier.fillMaxSize()) {
                val cancel = stringResource(R.string.action_cancel)
                val dismissSemantics = if (properties.dismissOnClickOutside) Modifier.semantics {
                    contentDescription = cancel
                    onClick { dismiss(); true }
                } else Modifier
                Box(Modifier.matchParentSize().testTag("imfit_dialog_scrim")
                    .background(Scrim.copy(alpha = IMFITOpacity.scrim))
                    .then(dismissSemantics)
                    .pointerInput(properties.dismissOnClickOutside) {
                        detectTapGestures { if (properties.dismissOnClickOutside) dismiss() }
                    })
                // Only the content is inset; the scrim continues behind system bars and IME.
                val insets = if (properties.decorFitsSystemWindows) WindowInsets.safeDrawing else WindowInsets.ime
                Box(Modifier.fillMaxSize().windowInsetsPadding(insets), contentAlignment = Alignment.Center) {
                    val width = if (properties.usePlatformDefaultWidth) platformDialogWidth() else Modifier
                    Box(width.testTag("imfit_dialog_content")
                        // Consume blank card/padding taps without stealing child button/field events.
                        .pointerInput(Unit) { detectTapGestures {} }) {
                        content()
                    }
                }
            }
        }
    }
}

@Composable
internal fun IMFITDialogWindowEffects() {
    if (LocalInspectionMode.current) return

    val view = LocalView.current
    val window = (view.parent as? DialogWindowProvider)?.window
    val diagnostic = LocalDialogBackdropDiagnostic.current

    DisposableEffect(window) {
        val originalFlags = window?.attributes?.flags ?: 0
        val originalDimAmount = window?.attributes?.dimAmount ?: 0f
        val originalBlurRadius = if (Build.VERSION.SDK_INT >= 31) window?.attributes?.blurBehindRadius ?: 0 else 0

        window?.let {
            val attributes = it.attributes
            attributes.flags = attributes.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
            attributes.dimAmount = 0f
            if (Build.VERSION.SDK_INT >= 31) {
                attributes.flags = attributes.flags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND.inv()
                attributes.blurBehindRadius = 0
            }

            it.attributes = attributes
        }

        val observer = diagnostic ?: if (BuildConfig.DEBUG &&
            BuildConfig.APPLICATION_ID.endsWith(".modaldiagnostic")) {
            DialogBackdropDiagnosticBridge.observer
        } else null
        observer?.invoke(view, window)

        onDispose {
            window?.let {
                val attributes = it.attributes
                attributes.dimAmount = originalDimAmount
                if (Build.VERSION.SDK_INT >= 31) attributes.blurBehindRadius = originalBlurRadius

                it.attributes = attributes
                restoreWindowFlag(
                    window = it,
                    flag = WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                    wasEnabled = originalFlags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0
                )
                if (Build.VERSION.SDK_INT >= 31) restoreWindowFlag(
                    window = it,
                    flag = WindowManager.LayoutParams.FLAG_BLUR_BEHIND,
                    wasEnabled = originalFlags and WindowManager.LayoutParams.FLAG_BLUR_BEHIND != 0
                )
            }
        }
    }
}

/** Preserve the platform width option for callers that do not opt into custom sizing. */
@Composable
private fun platformDialogWidth(): Modifier {
    val view = LocalView.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val width = remember(view, configuration, density) {
        val context = (view.parent as? DialogWindowProvider)?.window?.decorView?.context ?: view.context
        val value = TypedValue()
        val portrait = configuration.screenWidthDp < configuration.screenHeightDp
        val attribute = if (portrait) android.R.attr.windowMinWidthMinor else android.R.attr.windowMinWidthMajor
        if (context.theme.resolveAttribute(attribute, value, true)) {
            val screenPx = configuration.screenWidthDp * density.density
            val px = when (value.type) {
                TypedValue.TYPE_FRACTION -> value.getFraction(screenPx, screenPx)
                TypedValue.TYPE_DIMENSION -> value.getDimension(view.resources.displayMetrics)
                else -> screenPx
            }
            with(density) { px.toDp() }
        } else {
            // WindowManager's WRAP_CONTENT dialog fallback, not an app-specific width.
            val preferred = context.resources.getIdentifier("config_prefDialogWidth", "dimen", "android")
            if (preferred != 0) with(density) { context.resources.getDimension(preferred).toDp() }
            else configuration.screenWidthDp.dp
        }
    }
    return Modifier.widthIn(max = width)
}

private fun restoreWindowFlag(window: android.view.Window, flag: Int, wasEnabled: Boolean) {
    if (wasEnabled) {
        window.addFlags(flag)
    } else {
        window.clearFlags(flag)
    }
}
