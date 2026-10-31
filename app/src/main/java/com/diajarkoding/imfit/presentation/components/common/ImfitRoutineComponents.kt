package com.diajarkoding.imfit.presentation.components.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.error
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.theme.IMFITColors
import com.diajarkoding.imfit.theme.IMFITCornerRadius

val RoutineControlShape = RoundedCornerShape(IMFITCornerRadius.small)

@Composable
fun RoutineAppBar(title: String, onBack: () -> Unit, subtitle: String? = null,
    enabled: Boolean = true, actions: @Composable RowScope.() -> Unit = {}) {
    val c = IMFITColors.training
    Row(Modifier.fillMaxWidth().background(c.background).statusBarsPadding().heightIn(min = 64.dp).padding(horizontal = 12.dp).testTag("screen_header"),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(Symbols.AutoMirrored.Filled.ArrowBack, stringResource(R.string.action_back), tint = c.text, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 4.dp, vertical = 8.dp)) {
            Text(title, color = c.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) Text(subtitle, color = c.muted, style = MaterialTheme.typography.bodySmall)
        }
        actions()
    }
}

@Composable
fun RoutineButton(text: String, onClick: () -> Unit, enabled: Boolean = true, loading: Boolean = false,
    outlined: Boolean = false, icon: ImageVector? = null, modifier: Modifier = Modifier,
    matchOutlineToContent: Boolean = false) {
    val c = IMFITColors.training
    val content: @Composable RowScope.() -> Unit = {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.primary)
            Spacer(Modifier.width(8.dp))
        } else if (icon != null) { Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
    }
    if (outlined) {
        val colors = ButtonDefaults.outlinedButtonColors(containerColor = c.surface, contentColor = c.primary)
        val borderColor = if (matchOutlineToContent) {
            if (enabled && !loading) colors.contentColor else colors.disabledContentColor
        } else MaterialTheme.colorScheme.onSurfaceVariant
        OutlinedButton(onClick, modifier.fillMaxWidth().heightIn(min = 54.dp), enabled = enabled && !loading,
            shape = RoutineControlShape, border = BorderStroke(1.dp, borderColor), colors = colors, content = content)
    }
    else Button(onClick, modifier.fillMaxWidth().heightIn(min = 54.dp), enabled = enabled && !loading,
        shape = RoutineControlShape, colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary,
            disabledContainerColor = if (loading) c.primaryContainer else c.border, disabledContentColor = c.muted), content = content)
}

@Composable
fun RoutineFooter(
    topPadding: Dp = 12.dp,
    contentGap: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val c = IMFITColors.training
    Column(Modifier.fillMaxWidth().background(c.surface).navigationBarsPadding()) {
        HorizontalDivider(color = c.border)
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = topPadding, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(contentGap), content = content)
    }
}

@Composable
fun RoutineNameField(value: String, onChange: (String) -> Unit, enabled: Boolean = true, readOnly: Boolean = false,
    errorMessage: String? = null, onDone: () -> Unit = {}, requestFocus: Boolean = false) {
    val c = IMFITColors.training
    val focus = remember { FocusRequester() }
    LaunchedEffect(requestFocus) { if (requestFocus) focus.requestFocus() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.routine_name_label), style = MaterialTheme.typography.labelMedium, color = c.text)
        OutlinedTextField(value, onChange, enabled = enabled, readOnly = readOnly, singleLine = true, isError = errorMessage != null,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).focusRequester(focus).semantics { if (errorMessage != null) error(errorMessage) },
            shape = RoutineControlShape, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { onDone() }),
            placeholder = { Text(stringResource(R.string.home_workout_placeholder), color = c.muted) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = c.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                disabledBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                errorBorderColor = c.error,
                focusedContainerColor = c.surface,
                unfocusedContainerColor = c.surface,
                disabledContainerColor = c.surface,
                errorContainerColor = c.surface,
                focusedTextColor = c.text,
                unfocusedTextColor = c.text,
                disabledTextColor = c.text,
                cursorColor = c.primary,
                focusedPlaceholderColor = c.muted,
                unfocusedPlaceholderColor = c.muted,
                disabledPlaceholderColor = c.muted
            ))
        if (errorMessage != null) RoutineFailure(errorMessage)
    }
}

@Composable
fun RoutineFailure(message: String, onRetry: (() -> Unit)? = null) {
    val c = IMFITColors.training
    Surface(color = c.surface, shape = RoutineControlShape, modifier = Modifier.fillMaxWidth()
        .semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(c.error))
            Row(Modifier.weight(1f).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(painterResource(R.drawable.ic_auth_error), null, tint = c.error, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(message, color = c.muted, style = MaterialTheme.typography.bodyMedium)
                    if (onRetry != null) TextButton(onRetry) { Text(stringResource(R.string.routine_retry), color = c.primary) }
                }
            }
        }
    }
}

@Composable
fun RoutineLoading(label: String, topPadding: Dp = 24.dp) {
    val c = IMFITColors.training
    Column(Modifier.fillMaxWidth().padding(top = topPadding, bottom = 24.dp).semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.primary, trackColor = c.primaryContainer)
        Text(label, color = c.muted, style = MaterialTheme.typography.bodyMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutineDialog(title: String, message: String, icon: ImageVector, confirmText: String,
    onConfirm: () -> Unit, onDismiss: () -> Unit, processing: Boolean = false, destructive: Boolean = false,
    errorMessage: String? = null, confirmEnabled: Boolean = true, content: @Composable () -> Unit = {}) {
    val c = IMFITColors.training
    IMFITPlatformDialog(onDismissRequest = { if (!processing) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(24.dp).widthIn(max = 420.dp).fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = c.surface) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(icon, null, tint = if (destructive) c.error else c.primary, modifier = Modifier.size(24.dp))
                    Text(title, color = c.text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                }
                Text(message, color = c.muted, style = MaterialTheme.typography.bodyMedium)
                content()
                if (errorMessage != null) RoutineFailure(errorMessage)
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onDismiss, enabled = !processing, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.action_cancel), color = c.muted)
                    }
                    Button(onConfirm, enabled = !processing && confirmEnabled, modifier = Modifier.heightIn(min = 48.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }, shape = RoutineControlShape,
                        colors = ButtonDefaults.buttonColors(containerColor = if (destructive) c.errorContainer else c.primary,
                            contentColor = if (destructive) c.error else c.onPrimary)) {
                        if (processing) { CircularProgressIndicator(Modifier.size(18.dp), color = c.primary, strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                        Text(confirmText)
                    }
                }
            }
        }
    }
}
