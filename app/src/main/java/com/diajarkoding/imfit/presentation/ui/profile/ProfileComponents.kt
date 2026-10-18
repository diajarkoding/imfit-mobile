package com.diajarkoding.imfit.presentation.ui.profile

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.presentation.ui.progress.ProgressState
import com.diajarkoding.imfit.theme.*

@Composable
internal fun ProfileIdentity(state: ProgressState) {
    val colors = IMFITColors.training
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = IMFITSpacing.sm).testTag("profile_identity")) {
        val stacked = maxWidth < 320.dp || LocalDensity.current.fontScale > 1.3f
        val avatar: @Composable () -> Unit = {
            IMFITProfilePhoto(state.userProfilePhotoUri?.takeIf { it.isNotBlank() },
                size = IMFITSizes.avatarLg, modifier = Modifier.testTag("profile_avatar"),
                placeholderBackground = SolidColor(colors.primaryContainer), placeholderTint = colors.onPrimaryContainer)
        }
        val identity: @Composable () -> Unit = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(IMFITSpacing.xs)) {
                Text(state.userName.ifBlank { stringResource(R.string.progress_overview_guest) },
                    modifier = Modifier.fillMaxWidth(), color = colors.text, style = MaterialTheme.typography.titleLarge)
                if (state.userEmail.isNotBlank()) Text(state.userEmail,
                    modifier = Modifier.fillMaxWidth(), color = colors.muted, style = IMFITTypography.supporting)
            }
        }
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(IMFITSpacing.md)) { avatar(); identity() }
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.lg)) {
            avatar()
            Box(Modifier.weight(1f)) { identity() }
        }
    }
}

@Composable
private fun ProfileCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val colors = IMFITColors.training
    Surface(modifier.fillMaxWidth(), shape = IMFITShapes.CardSmall, color = colors.surface,
        border = BorderStroke(IMFITComponentSizes.border, colors.border)) { Column { content() } }
}

@Composable
internal fun ProfileInformation(name: String, email: String, birthDate: String) {
    ProfileCard(Modifier.testTag("profile_information")) {
        ProfileInformationRow(Symbols.Default.Person, stringResource(R.string.profile_name_label), name)
        ProfileDivider()
        ProfileInformationRow(Symbols.Default.Email, stringResource(R.string.label_email), email)
        ProfileDivider()
        ProfileInformationRow(Symbols.Default.Cake, stringResource(R.string.profile_birth_date_label), birthDate)
    }
}

@Composable
private fun ProfileInformationRow(icon: ImageVector, label: String, value: String) {
    val colors = IMFITColors.training
    Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = IMFITSpacing.cardPadding, vertical = IMFITSpacing.md)
        .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md), verticalAlignment = Alignment.CenterVertically) {
        ProfileIcon(icon, accent = false)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(IMFITSpacing.xxs)) {
            Text(label, color = colors.muted, style = IMFITTypography.caption)
            Text(value, color = colors.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ProfileIcon(icon: ImageVector, accent: Boolean) {
    val colors = IMFITColors.training
    Box(Modifier.size(IMFITSizes.avatarSm).clip(RoundedCornerShape(IMFITCornerRadius.small))
        .background(if (accent) colors.primaryContainer else colors.raised), contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(IMFITSizes.iconSm), tint = if (accent) colors.onPrimaryContainer else colors.muted)
    }
}

@Composable
private fun ProfileDivider() = HorizontalDivider(color = IMFITColors.training.border)

@Composable
internal fun ProfilePreferences(isDarkMode: Boolean, isIndonesian: Boolean, onToggleTheme: () -> Unit, onToggleLanguage: () -> Unit) {
    val themeState = stringResource(if (isDarkMode) R.string.settings_dark_mode_on else R.string.settings_dark_mode_off)
    val languageState = stringResource(if (isIndonesian) R.string.settings_language_id else R.string.settings_language_en)
    ProfileCard {
        ProfilePreferenceRow(Symbols.Default.DarkMode, stringResource(R.string.profile_dark_mode_label), themeState,
            Modifier.testTag("profile_theme").toggleable(isDarkMode, role = Role.Switch, onValueChange = { onToggleTheme() })
                .semantics { stateDescription = themeState }) { ProfileThemeIndicator(isDarkMode) }
        ProfileDivider()
        ProfilePreferenceRow(Symbols.Default.Language, stringResource(R.string.settings_language), languageState,
            Modifier.testTag("profile_language").toggleable(isIndonesian, role = Role.Switch, onValueChange = { onToggleLanguage() })
                .semantics { stateDescription = languageState }) {
            ProfileLanguageSelector(isIndonesian)
        }
    }
}

@Composable
private fun ProfilePreferenceRow(icon: ImageVector, title: String, subtitle: String, modifier: Modifier = Modifier, control: @Composable () -> Unit) {
    val colors = IMFITColors.training
    Row(modifier.fillMaxWidth().heightIn(min = 88.dp).padding(IMFITSpacing.cardPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md)) {
        ProfileIcon(icon, accent = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(IMFITSpacing.xxs)) {
            Text(title, color = colors.text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, color = colors.muted, style = IMFITTypography.caption)
        }
        control()
    }
}

@Composable
private fun ProfileThemeIndicator(isDarkMode: Boolean) {
    val colors = IMFITColors.training
    val offset by animateDpAsState(if (isDarkMode) IMFITPreferenceSizes.thumbTravel else 0.dp,
        animationSpec = tween(IMFITMotion.ThemeTransitionDurationMillis), label = "profile_theme_thumb")
    // The containing row owns the switch semantics and the touch target.
    Box(Modifier.width(IMFITPreferenceSizes.trackWidth).height(IMFITSizes.minimumInteractiveTarget), contentAlignment = Alignment.Center) {
        Surface(Modifier.size(IMFITPreferenceSizes.trackWidth, IMFITPreferenceSizes.trackHeight).testTag("profile_theme_track").clearAndSetSemantics {}, shape = CircleShape,
            color = if (isDarkMode) colors.primary else colors.raised,
            border = BorderStroke(IMFITComponentSizes.border, if (isDarkMode) colors.primary else colors.border)) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                Surface(Modifier.padding(start = IMFITPreferenceSizes.thumbInset).offset { IntOffset(offset.roundToPx(), 0) }.size(IMFITPreferenceSizes.thumb), shape = CircleShape,
                    color = if (isDarkMode) colors.onPrimary else colors.surface,
                    border = BorderStroke(IMFITComponentSizes.border, if (isDarkMode) colors.onPrimary else colors.border)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(if (isDarkMode) Symbols.Default.DarkMode else Symbols.Default.LightMode, null,
                            Modifier.size(14.dp), tint = colors.primary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileLanguageSelector(isIndonesian: Boolean) {
    val colors = IMFITColors.training
    val offset by animateDpAsState(
        targetValue = if (isIndonesian) 0.dp else IMFITPreferenceSizes.thumbTravel,
        animationSpec = tween(IMFITMotion.ThemeTransitionDurationMillis),
        label = "profile_language_thumb"
    )
    // The containing row owns the switch semantics and the touch target.
    Box(
        modifier = Modifier
            .width(IMFITPreferenceSizes.trackWidth)
            .height(IMFITSizes.minimumInteractiveTarget),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.size(IMFITPreferenceSizes.trackWidth, IMFITPreferenceSizes.trackHeight).testTag("profile_language_track").clearAndSetSemantics {},
            shape = CircleShape,
            color = colors.raised,
            border = BorderStroke(IMFITComponentSizes.border, colors.border)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                Surface(
                    modifier = Modifier
                        .padding(start = IMFITPreferenceSizes.thumbInset)
                        .offset { IntOffset(offset.roundToPx(), 0) }
                        .size(IMFITPreferenceSizes.thumb),
                    shape = CircleShape,
                    color = colors.primary,
                    border = BorderStroke(IMFITComponentSizes.border, colors.primary)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(if (isIndonesian) R.string.profile_option_id else R.string.profile_option_en),
                            color = colors.onPrimary,
                            style = IMFITTypography.caption.copy(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold)
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProfileLogout(onLogout: () -> Unit) {
    val colors = IMFITColors.training
    Button(onLogout, Modifier.fillMaxWidth().heightIn(min = 54.dp).testTag("profile_logout"),
        shape = RoundedCornerShape(IMFITCornerRadius.small), elevation = null,
        colors = ButtonDefaults.buttonColors(containerColor = colors.errorContainer, contentColor = colors.error),
        contentPadding = PaddingValues(horizontal = IMFITSpacing.lg, vertical = IMFITSpacing.md)) {
        Icon(Symbols.AutoMirrored.Filled.ExitToApp, null, Modifier.size(IMFITSizes.iconSm))
        Spacer(Modifier.width(IMFITSpacing.controlGap))
        Text(stringResource(R.string.profile_logout_label), style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
internal fun ProfileLoadFailure(onRetry: () -> Unit) {
    val colors = IMFITColors.training
    Surface(Modifier.fillMaxWidth().testTag("profile_error").semantics { liveRegion = LiveRegionMode.Polite },
        shape = IMFITShapes.CardSmall, color = colors.surface) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(colors.error))
            Column(Modifier.weight(1f).padding(IMFITSpacing.cardPaddingLarge), verticalArrangement = Arrangement.spacedBy(IMFITSpacing.md)) {
                Row(horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.controlGap), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_auth_error), null, Modifier.size(IMFITSizes.iconSm), tint = colors.error)
                    Text(stringResource(R.string.profile_error_title), color = colors.text, style = IMFITTypography.statusTitle)
                }
                Text(stringResource(R.string.profile_error_message), color = colors.muted, style = IMFITTypography.supporting)
                OutlinedButton(onRetry, Modifier.fillMaxWidth().heightIn(min = IMFITSizes.buttonHeight).testTag("profile_retry"),
                    shape = RoundedCornerShape(IMFITCornerRadius.small), border = BorderStroke(IMFITComponentSizes.border, colors.primary),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.primary)) {
                    Icon(painterResource(R.drawable.ic_home_refresh), null, Modifier.size(IMFITSizes.iconSm))
                    Spacer(Modifier.width(IMFITSpacing.sm))
                    Text(stringResource(R.string.profile_retry_label), style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
internal fun ProfileIdentityLoading() {
    val label = stringResource(R.string.profile_loading)
    val brush = shimmerBrush()
    BoxWithConstraints(Modifier.fillMaxWidth().padding(bottom = IMFITSpacing.sm).testTag("profile_loading")
        .clearAndSetSemantics { contentDescription = label; liveRegion = LiveRegionMode.Polite }) {
        val labels: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(IMFITSpacing.controlGap)) {
                ProfileSkeleton(brush, 76.dp, 10.dp)
                ProfileSkeleton(brush, 166.dp, 20.dp)
                ProfileSkeleton(brush, 140.dp, 12.dp)
            }
        }
        if (maxWidth < 320.dp || LocalDensity.current.fontScale > 1.3f) {
            Column(verticalArrangement = Arrangement.spacedBy(IMFITSpacing.md)) { ProfileSkeleton(brush, 72.dp, 72.dp, circle = true); labels() }
        } else Row(horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.lg), verticalAlignment = Alignment.CenterVertically) {
            ProfileSkeleton(brush, 72.dp, 72.dp, circle = true)
            Box(Modifier.weight(1f)) { labels() }
        }
    }
}

@Composable
internal fun ProfileInformationLoading() {
    val brush = shimmerBrush()
    ProfileCard(Modifier.clearAndSetSemantics {}) {
        repeat(3) { index ->
            Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(horizontal = IMFITSpacing.lg, vertical = IMFITSpacing.md),
                horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md), verticalAlignment = Alignment.CenterVertically) {
                ProfileSkeleton(brush, 40.dp, 40.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)) {
                    ProfileSkeleton(brush, 82.dp, 10.dp)
                    ProfileSkeleton(brush, if (index == 1) 178.dp else 132.dp, 13.dp)
                }
            }
            if (index < 2) ProfileDivider()
        }
    }
}

@Composable
private fun ProfileSkeleton(brush: Brush, width: Dp, height: Dp, circle: Boolean = false) {
    Box(Modifier.width(width).height(height).clip(if (circle) CircleShape else RoundedCornerShape(IMFITCornerRadius.small))
        .background(IMFITColors.training.border).background(brush))
}
