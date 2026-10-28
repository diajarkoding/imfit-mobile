package com.diajarkoding.imfit.presentation.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.data.sync.SyncState
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.theme.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun homePalette(): IMFITPalette = IMFITColors.training

@Composable
internal fun HomeGreeting(userName: String, modifier: Modifier = Modifier) {
    val colors = homePalette()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(IMFITSpacing.xs)) {
        Text(if (userName.isBlank()) stringResource(R.string.redesign_hello)
            else stringResource(R.string.redesign_greeting, userName),
            color = colors.text, style = IMFITTypography.greeting)
        Text(stringResource(R.string.redesign_ready), color = colors.muted, style = IMFITTypography.supporting)
    }
}

@Composable
internal fun HomeStrengthBanner(modifier: Modifier = Modifier) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (LocalDensity.current.fontScale > IMFITComponentSizes.adaptiveFontScale) {
                    Modifier.heightIn(min = IMFITBanner.height)
                } else {
                    Modifier.aspectRatio(IMFITBanner.ratio)
                }
            )
            .clip(RoundedCornerShape(IMFITBanner.radius))
            .strengthBannerGradient()
    ) {
        val bannerWidth = maxWidth.value

        val sidePadding = (bannerWidth * 0.065f).coerceIn(18f, 80f).dp
        val titleSize = (bannerWidth * 0.058f).coerceIn(18f, 72f).sp
        val captionSize = (bannerWidth * 0.032f).coerceIn(11f, 40f).sp
        val textSpacing = (bannerWidth * 0.037f).coerceIn(8f, 40f).dp

        Image(
            painter = painterResource(R.drawable.imfit_strength_3d),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight(IMFITBanner.illustrationHeightFraction)
                .aspectRatio(1f)
                .offset(x = -(maxWidth * IMFITBanner.illustrationOffsetFraction))
        )

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxWidth(IMFITBanner.textFraction)
                .padding(start = sidePadding),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = stringResource(R.string.home_strength_headline),
                color = Color.White,
                style = IMFITTypography.bannerTitle.copy(
                    fontSize = titleSize,
                    lineHeight = titleSize * 1.08f,
                    fontWeight = FontWeight.ExtraBold
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(textSpacing))

            Text(
                text = stringResource(R.string.home_strength_caption),
                color = BannerStrengthCaption,
                style = IMFITTypography.caption.copy(
                    fontSize = captionSize,
                    lineHeight = captionSize * 1.3f
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun Modifier.strengthBannerGradient(): Modifier = this.drawWithCache {
    val w = size.width
    val h = size.height

    val base = Brush.horizontalGradient(
        IMFITBanner.stopDeepNavy to BannerDeepNavy,
        IMFITBanner.stopDeepNavyHold to BannerDeepNavy,
        IMFITBanner.stopMidnight to BannerMidnightBlue,
        IMFITBanner.stopMedium to BannerMediumBlue,
        IMFITBanner.stopRoyal to BannerRoyalBlue
    )

    val glow = Brush.radialGradient(
        0.00f to BannerCyanGlow.copy(alpha = IMFITBanner.glowMaxAlpha),
        IMFITBanner.glowMidStop to BannerCyanGlow.copy(alpha = IMFITBanner.glowMidAlpha),
        1.00f to Color.Transparent,
        center = Offset(x = w * IMFITBanner.glowCenterX, y = h * IMFITBanner.glowCenterY),
        radius = w * IMFITBanner.glowRadiusFraction
    )

    val vignette = Brush.verticalGradient(
        0.00f to Color.Black.copy(alpha = IMFITBanner.vignetteTopAlpha),
        IMFITBanner.vignetteMidStop to Color.Transparent,
        1.00f to Color.Black.copy(alpha = IMFITBanner.vignetteBottomAlpha)
    )

    onDrawBehind {
        drawRect(brush = base)
        drawRect(brush = glow)
        drawRect(brush = vignette)
    }
}

@Composable
internal fun HomeLastWorkout(log: WorkoutLog?, modifier: Modifier = Modifier) {
    val colors = homePalette()
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    Surface(modifier.fillMaxWidth(), shape = IMFITShapes.CardSmall, color = colors.surface,
        border = BorderStroke(IMFITComponentSizes.border, colors.border)) {
        Column(Modifier.padding(IMFITSpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(IMFITSpacing.summaryGap)) {
            if (log == null) {
                Text(stringResource(R.string.home_summary_empty), color = colors.text,
                    style = IMFITTypography.compactTitle)
                Text(stringResource(R.string.redesign_start_hint), color = colors.muted, style = IMFITTypography.caption)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.compact)) {
                    Icon(painterResource(R.drawable.ic_home_history), null, Modifier.size(IMFITComponentSizes.metricIcon), tint = colors.muted)
                    Text(stringResource(R.string.home_summary_heading, log.templateName.uppercase(locale)),
                        Modifier.weight(1f), color = colors.muted, style = IMFITTypography.historyHeading)
                    Text(Instant.ofEpochMilli(log.startTime).atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("d MMM yyyy", locale)),
                        color = colors.muted, style = IMFITTypography.historyDate, textAlign = TextAlign.End)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.xl)) {
                    HomeSummaryMetric(log.formattedDuration, stringResource(R.string.home_total_duration),
                        R.drawable.msr_timer_filled, Modifier.weight(1f))
                    Box(Modifier.width(IMFITComponentSizes.border).height(IMFITComponentSizes.metricDivider).background(colors.border))
                    HomeSummaryMetric(String.format(locale, "%,.0f kg", log.totalVolume),
                        stringResource(R.string.home_total_volume), R.drawable.ic_home_weight, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HomeSummaryMetric(value: String, caption: String, @DrawableRes icon: Int, modifier: Modifier) {
    val colors = homePalette()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(IMFITSpacing.compact)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.inline)) {
            Icon(painterResource(icon), null, Modifier.size(IMFITComponentSizes.metricIcon), tint = colors.primary)
            Text(caption, color = colors.muted, style = IMFITTypography.metricCaption, textAlign = TextAlign.Center)
        }
        Text(value, color = colors.text, style = IMFITTypography.metricValue, textAlign = TextAlign.Center)
    }
}

@Composable
internal fun HomeWorkoutSectionHeader(enabled: Boolean, onAdd: () -> Unit) {
    val colors = homePalette()
    val title = stringResource(R.string.home_my_workouts)
    val add: @Composable () -> Unit = {
        TextButton(onClick = onAdd, enabled = enabled,
            modifier = Modifier.widthIn(min = IMFITComponentSizes.addDayWidth).heightIn(min = IMFITSizes.buttonHeight), shape = RoundedCornerShape(IMFITCornerRadius.small),
            contentPadding = PaddingValues(horizontal = IMFITSpacing.controlGap),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.onPrimaryContainer)) {
            Icon(Symbols.Default.Add, null, Modifier.size(18.dp))
            Spacer(Modifier.width(IMFITSpacing.compact))
            Text(stringResource(R.string.home_add_day), style = IMFITTypography.textAction)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < IMFITComponentSizes.sectionStackWidth || LocalDensity.current.fontScale > IMFITComponentSizes.adaptiveFontScale) {
            Column {
                Text(title, color = colors.text, style = IMFITTypography.sectionTitle)
                Box(Modifier.align(Alignment.End)) { add() }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md)) {
                Text(title, Modifier.weight(1f), color = colors.text, style = IMFITTypography.sectionTitle)
                add()
            }
        }
    }
}

@Composable
internal fun HomeWorkoutCard(template: WorkoutTemplate, active: Boolean, onClick: () -> Unit) {
    val colors = homePalette()
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = IMFITSizes.cardMinHeight),
        shape = RoundedCornerShape(IMFITCornerRadius.template), color = colors.surface, contentColor = colors.text,
        border = BorderStroke(if (active) IMFITComponentSizes.activeBorder else IMFITComponentSizes.border, if (active) colors.primary else colors.border)) {
        Row(Modifier.padding(IMFITSpacing.md), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md)) {
            // Reorder is deferred: no non-functional drag control is presented.
            Box(Modifier.size(IMFITComponentSizes.templateThumbnail).clip(RoundedCornerShape(IMFITCornerRadius.small)).background(colors.raised).padding(IMFITSpacing.xs)) {
                MuscleCategoryArtwork(categoryArtAsset(template.exercises.firstOrNull()?.exercise?.muscleCategory), Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(IMFITSpacing.inline)) {
                Text(template.name, style = IMFITTypography.templateTitle)
                Text(pluralStringResource(R.plurals.redesign_exercise_count, template.exerciseCount, template.exerciseCount) +
                    if (active) " · " + stringResource(R.string.home_resume) else "",
                    color = if (active) colors.primary else colors.muted, style = IMFITTypography.caption)
            }
            Icon(Symbols.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(IMFITSizes.iconSm), tint = colors.primary)
        }
    }
}

@Composable
internal fun HomeRemoteWorkoutCard(template: com.diajarkoding.imfit.data.remote.online.TemplateSummary, active: Boolean, onClick: () -> Unit) {
    val colors = homePalette()
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = IMFITSizes.cardMinHeight),
        shape = RoundedCornerShape(IMFITCornerRadius.template), color = colors.surface,
        border = BorderStroke(IMFITComponentSizes.border, colors.border)) {
        Row(Modifier.padding(IMFITSpacing.cardPadding), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md)) {
            MuscleCategoryArtwork(categoryArtAsset(null), Modifier.size(IMFITComponentSizes.templateThumbnail))
            Column(Modifier.weight(1f)) {
                Text(template.name, style = IMFITTypography.templateTitle)
                Text(pluralStringResource(R.plurals.redesign_exercise_count, template.exerciseCount, template.exerciseCount) +
                    if (active) " · " + stringResource(R.string.home_resume) else "", style = IMFITTypography.caption)
            }
            Icon(Symbols.AutoMirrored.Filled.KeyboardArrowRight, null)
        }
    }
}

@Composable
internal fun HomeRemoteLastWorkout(log: com.diajarkoding.imfit.data.remote.online.WorkoutSummary?) {
    if (log == null) { HomeLastWorkout(null); return }
    val colors = homePalette()
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    Surface(Modifier.fillMaxWidth(), shape = IMFITShapes.CardSmall, color = colors.surface,
        border = BorderStroke(IMFITComponentSizes.border, colors.border)) {
        Column(Modifier.padding(IMFITSpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)) {
            Text(stringResource(R.string.home_summary_heading, log.templateName.uppercase(locale)), style = IMFITTypography.historyHeading)
            Text(java.time.OffsetDateTime.parse(log.startTime).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM yyyy", locale)))
            Row(horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md)) {
                HomeSummaryMetric(stringResource(R.string.remote_read_minutes, log.durationSeconds / 60), stringResource(R.string.home_total_duration), R.drawable.msr_timer_filled, Modifier.weight(1f))
                HomeSummaryMetric(java.text.NumberFormat.getNumberInstance(locale).format(log.totalVolume.toBigDecimal()) + " kg", stringResource(R.string.home_total_volume), R.drawable.ic_home_weight, Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun HomeSyncStatusCard(state: SyncState, modifier: Modifier = Modifier,
    onRetryUploads: (() -> Unit)? = null, retrying: Boolean = false, retryFailed: Boolean = false) {
    val colors = homePalette()
    val pending = state.pendingCount.coerceAtLeast(0)
    val status = when {
        state.status == SyncState.SyncStatus.SYNCED && pending > 0 -> SyncState.SyncStatus.IDLE
        else -> state.status
    }
    val (accent, background) = when (status) {
        SyncState.SyncStatus.OFFLINE -> colors.warning to colors.warningContainer
        SyncState.SyncStatus.FAILED -> colors.error to colors.errorContainer
        SyncState.SyncStatus.SYNCED -> colors.success to colors.successContainer
        SyncState.SyncStatus.IDLE, SyncState.SyncStatus.SYNCING -> colors.primary to colors.primaryContainer
    }
    val title = when (status) {
        SyncState.SyncStatus.IDLE -> if (pending > 0) stringResource(R.string.home_sync_pending, pending)
            else stringResource(R.string.home_sync_idle)
        SyncState.SyncStatus.SYNCING -> stringResource(R.string.home_sync_syncing)
        SyncState.SyncStatus.SYNCED -> stringResource(R.string.home_sync_synced)
        SyncState.SyncStatus.OFFLINE -> if (pending > 0) stringResource(R.string.home_sync_offline_pending, pending)
            else stringResource(R.string.home_sync_offline)
        SyncState.SyncStatus.FAILED -> stringResource(R.string.home_sync_failed)
    }
    val description = when (status) {
        SyncState.SyncStatus.IDLE -> if (pending > 0) R.string.home_sync_local else R.string.home_sync_idle_description
        SyncState.SyncStatus.SYNCING -> R.string.home_sync_syncing_description
        SyncState.SyncStatus.SYNCED -> R.string.home_sync_synced_description
        SyncState.SyncStatus.OFFLINE -> R.string.home_sync_local
        SyncState.SyncStatus.FAILED -> R.string.home_sync_failed_description
    }
    val icon = when (status) {
        SyncState.SyncStatus.IDLE -> R.drawable.ic_home_cloud
        SyncState.SyncStatus.SYNCING -> R.drawable.ic_home_refresh
        SyncState.SyncStatus.SYNCED -> R.drawable.ic_home_cloud_check
        SyncState.SyncStatus.OFFLINE -> R.drawable.ic_home_cloud_off
        SyncState.SyncStatus.FAILED -> R.drawable.ic_home_alert
    }
    Surface(modifier.fillMaxWidth().semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(IMFITCornerRadius.small), color = background) {
        Row(Modifier.padding(IMFITSpacing.cardPadding), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md)) {
            Icon(painterResource(icon), null, Modifier.size(IMFITSizes.iconSm), tint = accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(IMFITSpacing.xxs)) {
                Text(title, color = accent, style = IMFITTypography.statusTitle)
                Text(stringResource(description), color = colors.text, style = IMFITTypography.statusBody)
                if (pending > 0 && onRetryUploads != null) {
                    Text(stringResource(R.string.manual_upload_description), color = colors.text, style = IMFITTypography.statusBody)
                    if (retryFailed) Text(stringResource(R.string.manual_upload_failed), color = colors.error, style = IMFITTypography.statusBody)
                    TextButton(onRetryUploads, enabled = !retrying && status != SyncState.SyncStatus.SYNCING,
                        modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(if (retrying) R.string.manual_upload_running else R.string.manual_upload_retry))
                    }
                }
                if (status == SyncState.SyncStatus.SYNCING) {
                    val progress = state.progress
                    if (state.isInitialSync && progress.isFinite()) {
                        LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            color = accent, trackColor = accent.copy(alpha = 0.15f))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp),
                            color = accent, trackColor = accent.copy(alpha = 0.15f))
                    }
                }
            }
        }
    }
}
