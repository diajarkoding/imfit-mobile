package com.diajarkoding.imfit.presentation.ui.auth

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.error as semanticsError
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.theme.IMFITColors
import com.diajarkoding.imfit.theme.IMFITCornerRadius
import com.diajarkoding.imfit.theme.IMFITSizes
import com.diajarkoding.imfit.theme.IMFITSpacing

internal val AuthControlShape = RoundedCornerShape(IMFITCornerRadius.small)

internal object AuthTokens {
    val buttonHeight = 54.dp
    val photoRowHeight = 76.dp
    val outline: Color
        @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
internal fun AuthFieldLabel(label: String, error: String? = null, focused: Boolean = false) {
    val colors = IMFITColors.training
    Text(label, color = if (error != null) colors.error else if (focused) colors.primary else colors.text,
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp, lineHeight = 19.sp),
        fontWeight = FontWeight.Medium)
}

@Composable
internal fun AuthFieldError(error: String?) {
    if (error != null) Text(error, color = IMFITColors.training.error,
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite })
}

@Composable
internal fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    modifier: Modifier = Modifier,
    error: String? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    enabled: Boolean = true,
    onDone: () -> Unit = {}
) {
    val colors = IMFITColors.training
    val focus = LocalFocusManager.current
    var visible by rememberSaveable { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(Modifier.fillMaxWidth()) {
        AuthFieldLabel(label, error, focused)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = value, onValueChange = onValueChange,
            modifier = modifier.fillMaxWidth().heightIn(min = IMFITSizes.textFieldHeight)
                .semantics { if (error != null) semanticsError(error) },
            interactionSource = interaction,
            enabled = enabled, singleLine = true, isError = error != null,
            shape = AuthControlShape,
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, color = colors.text),
            placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = colors.muted) },
            visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (password) KeyboardType.Password else keyboardType,
                imeAction = imeAction
            ),
            keyboardActions = KeyboardActions(
                onNext = { focus.moveFocus(FocusDirection.Next) },
                onDone = { focus.clearFocus(); onDone() }
            ),
            trailingIcon = if (password) {
                {
                    IconButton(onClick = { visible = !visible }, enabled = enabled, modifier = Modifier.size(48.dp)) {
                        Icon(if (visible) Symbols.Default.Visibility else Symbols.Default.VisibilityOff,
                            stringResource(if (visible) R.string.auth_hide_password else R.string.auth_show_password),
                            tint = colors.muted, modifier = Modifier.size(20.dp))
                    }
                }
            } else null,
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = colors.text, unfocusedTextColor = colors.text,
                focusedContainerColor = colors.surface, unfocusedContainerColor = colors.surface,
                errorContainerColor = colors.surface, disabledContainerColor = colors.surface,
                focusedBorderColor = colors.primary,
                unfocusedBorderColor = AuthTokens.outline,
                disabledBorderColor = AuthTokens.outline.copy(alpha = 0.5f),
                disabledTextColor = colors.muted,
                errorBorderColor = colors.error, cursorColor = colors.primary
            )
        )
        AuthFieldError(error)
    }
}

@Composable
internal fun AuthPrimaryButton(
    text: String, onClick: () -> Unit, loading: Boolean = false, enabled: Boolean = true,
    loadingText: String = stringResource(R.string.auth_submitting)
) {
    val colors = IMFITColors.training
    Button(onClick, modifier = Modifier.fillMaxWidth().heightIn(min = AuthTokens.buttonHeight)
        .semantics { liveRegion = LiveRegionMode.Polite },
        enabled = enabled && !loading, shape = AuthControlShape,
        colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary)) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(IMFITSizes.buttonLoadingIcon), color = colors.onPrimary, strokeWidth = 2.dp)
            Spacer(Modifier.width(IMFITSpacing.sm))
        }
        Text(if (loading) loadingText else text, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AuthRedirect(prompt: String, action: String, onClick: () -> Unit, enabled: Boolean = true) {
    FlowRow(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.Center) {
        Text(prompt.trim(), modifier = Modifier.align(Alignment.CenterVertically),
            style = MaterialTheme.typography.bodyMedium, color = IMFITColors.training.muted)
        TextButton(onClick, enabled = enabled, modifier = Modifier.heightIn(min = 48.dp).align(Alignment.CenterVertically)) {
            Text(action.trim(), color = IMFITColors.training.primary, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun AuthPreferences(isDarkMode: Boolean, isIndonesian: Boolean, onToggleTheme: () -> Unit, onToggleLanguage: () -> Unit) {
    val colors = IMFITColors.training
    Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.size(104.dp, 56.dp).selectableGroup().clip(RoundedCornerShape(28.dp)).background(colors.raised).padding(4.dp)) {
            listOf("ID", "EN").forEachIndexed { index, label ->
                val selected = (index == 0) == isIndonesian
                Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(20.dp))
                    .background(if (selected) colors.primary else Color.Transparent)
                    .selectable(selected = selected, role = Role.RadioButton, onClick = { if (!selected) onToggleLanguage() }), contentAlignment = Alignment.Center) {
                    Text(label, color = if (selected) colors.onPrimary else colors.muted,
                        fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        IconButton(onToggleTheme, Modifier.size(48.dp).background(colors.raised, RoundedCornerShape(24.dp))) {
            Icon(if (isDarkMode) Symbols.Default.DarkMode else Symbols.Default.LightMode,
                stringResource(R.string.auth_toggle_theme), tint = colors.primary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun AuthBorderedRow(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isError: Boolean = false,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    val colors = IMFITColors.training
    Surface(modifier.fillMaxWidth(), shape = AuthControlShape, color = colors.surface,
        border = BorderStroke(1.dp, if (isError) colors.error else AuthTokens.outline)) {
        Row(Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick).heightIn(min = 56.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun AuthOperationError(message: String?, title: String) {
    if (message == null) return
    val colors = IMFITColors.training
    // Nonblocking feedback (SqLf7 / uqBq4), not a full-screen data error.
    Surface(color = colors.surface,
        shape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
        modifier = Modifier.fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(colors.error))
            Row(Modifier.weight(1f).padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(32.dp).background(colors.errorContainer, AuthControlShape),
                    contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.ic_auth_error), contentDescription = null,
                        tint = colors.error, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(title, color = colors.text, fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp, lineHeight = 19.sp))
                    Text(message, color = colors.muted,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp))
                }
            }
        }
    }
}

@Composable
internal fun AuthRegisterHeading(onBack: () -> Unit, enabled: Boolean = true) {
    val colors = IMFITColors.training
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md)) {
        IconButton(onBack, enabled = enabled, modifier = Modifier.size(IMFITSizes.minimumInteractiveTarget)) {
            Icon(Symbols.AutoMirrored.Filled.ArrowBack, stringResource(R.string.auth_back_to_login),
                tint = colors.text, modifier = Modifier.size(IMFITSizes.iconSm))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(IMFITSpacing.xs)) {
            Text(stringResource(R.string.register_title), color = colors.text, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp, lineHeight = 35.sp, letterSpacing = (-0.6).sp))
            Text(stringResource(R.string.register_subtitle), color = colors.muted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun AuthBrandMark() {
    Box(Modifier.size(72.dp).background(com.diajarkoding.imfit.theme.Primary, RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center) {
        Icon(Symbols.Default.FitnessCenter, stringResource(R.string.app_name),
            tint = com.diajarkoding.imfit.theme.OnPrimary, modifier = Modifier.size(36.dp))
    }
}
