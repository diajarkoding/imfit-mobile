package com.diajarkoding.imfit.presentation.ui.home

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.presentation.ui.workout.WorkoutMeasurements
import com.diajarkoding.imfit.presentation.ui.workout.workoutMeasurements
import com.diajarkoding.imfit.theme.*
import java.text.NumberFormat
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

internal data class HomeLastWorkoutSummary(
    val isV3: Boolean,
    val isLegacyOrUnverified: Boolean,
    val durationText: String?,
    val isometricDurationText: String,
    val primaryVolumeText: String,
    val additionalVolumeText: String,
    val distanceKmText: String,
    val cardioDurationText: String,
    val repetitionsCount: Int,
    val repetitionsText: String
)

internal fun isIndonesianLocale(locale: Locale): Boolean =
    locale.language.equals("in", ignoreCase = true) || locale.language.equals("id", ignoreCase = true)

internal fun formatHomeLastWorkoutDuration(log: WorkoutLog, locale: Locale): String {
    val totalMinutes = if (log.measurementVersion == 3 && log.activeElapsedMs != null) {
        (log.activeElapsedMs / 60_000L).toInt()
    } else {
        log.durationMinutes
    }
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (isIndonesianLocale(locale)) {
        when {
            hours > 0 && minutes > 0 -> "${hours}j $minutes mnt"
            hours > 0 -> "${hours}j"
            else -> "$minutes mnt"
        }
    } else {
        when {
            hours > 0 && minutes > 0 -> "${hours}h ${minutes}m"
            hours > 0 -> "${hours}h"
            else -> "${minutes}m"
        }
    }
}

internal fun formatHomeSupportingDurationSeconds(
    seconds: Int,
    locale: Locale,
    preferMinutesWhenZero: Boolean = false
): String {
    val safeSeconds = seconds.coerceAtLeast(0)
    val indonesian = isIndonesianLocale(locale)
    if (safeSeconds == 0) {
        return if (preferMinutesWhenZero) {
            if (indonesian) "0 mnt" else "0m"
        } else {
            if (indonesian) "0 dtk" else "0s"
        }
    }
    val minutes = safeSeconds / 60
    val remainingSeconds = safeSeconds % 60
    return if (indonesian) {
        when {
            minutes == 0 -> "$remainingSeconds dtk"
            remainingSeconds == 0 -> "$minutes mnt"
            else -> "$minutes mnt $remainingSeconds dtk"
        }
    } else {
        when {
            minutes == 0 -> "${remainingSeconds}s"
            remainingSeconds == 0 -> "${minutes}m"
            else -> "${minutes}m ${remainingSeconds}s"
        }
    }
}

internal fun formatHomeDistanceKilometers(distanceMeters: Double, locale: Locale): String {
    val km = distanceMeters.coerceAtLeast(0.0) / 1000.0
    val formatter = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 2
    }
    return formatter.format(km)
}

internal fun homeLastWorkoutSummary(log: WorkoutLog?, locale: Locale): HomeLastWorkoutSummary {
    val metrics = if (log != null) {
        workoutMeasurements(log.exerciseLogs)
    } else {
        WorkoutMeasurements(0.0, 0.0, 0, 0, 0, 0, 0.0)
    }
    val numberFormat = NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = 2
    }
    val isV3 = log?.measurementVersion == 3
    val isLegacyOrUnverified = log != null && (
        log.measurementVersion != 3 ||
            log.dataQuality != "VERIFIED" ||
            log.exerciseLogs.any { it.measurementVersion != 3 || it.metricQuality != "VERIFIED" }
        )
    // Historical legacy external volume and additional volume must remain strictly separate
    // and never be summed into v3 recordedLoadVolume.
    val primaryVolumeKg = when {
        log == null -> 0.0
        isV3 -> log.recordedLoadVolume ?: log.exerciseLogs.sumOf { it.recordedLoadVolume }
        log.exerciseLogs.isNotEmpty() -> metrics.externalVolume
        else -> log.totalVolume.toDouble()
    }
    val additionalVolumeKg = when {
        log == null -> 0.0
        log.exerciseLogs.isNotEmpty() -> metrics.additionalVolume
        else -> log.additionalVolume.toDouble()
    }
    val distanceMeters = when {
        log == null -> 0.0
        log.totalDistanceMeters != null && log.totalDistanceMeters > 0.0 -> log.totalDistanceMeters
        else -> metrics.distanceMeters
    }
    val repetitionsCount = when {
        log == null -> 0
        log.exerciseLogs.isNotEmpty() -> metrics.repetitions
        else -> log.totalReps
    }
    return HomeLastWorkoutSummary(
        isV3 = isV3,
        isLegacyOrUnverified = isLegacyOrUnverified,
        durationText = log?.let { formatHomeLastWorkoutDuration(it, locale) },
        isometricDurationText = formatHomeSupportingDurationSeconds(
            seconds = metrics.holdSeconds,
            locale = locale,
            preferMinutesWhenZero = false
        ),
        primaryVolumeText = numberFormat.format(primaryVolumeKg),
        additionalVolumeText = numberFormat.format(additionalVolumeKg),
        distanceKmText = formatHomeDistanceKilometers(distanceMeters, locale),
        cardioDurationText = formatHomeSupportingDurationSeconds(
            seconds = metrics.cardioSeconds,
            locale = locale,
            preferMinutesWhenZero = true
        ),
        repetitionsCount = repetitionsCount,
        repetitionsText = numberFormat.format(repetitionsCount)
    )
}

@Composable
internal fun HomeLastWorkout(log: WorkoutLog?, modifier: Modifier = Modifier) {
    val colors = homePalette()
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val dash = stringResource(R.string.placeholder_dash)
    val headingText = if (log != null) {
        stringResource(R.string.home_summary_heading, log.templateName.uppercase(locale))
    } else {
        stringResource(R.string.home_summary_heading, dash)
    }
    val dateText = if (log != null) {
        Instant.ofEpochMilli(log.startTime)
            .atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
    } else {
        dash
    }
    val summary = remember(log, locale) { homeLastWorkoutSummary(log, locale) }
    val durationText = summary.durationText ?: stringResource(R.string.workout_duration_zero)
    val volumeCaption = stringResource(
        if (summary.isV3) {
            R.string.home_last_workout_recorded_load_label
        } else {
            R.string.home_last_workout_external_volume_label
        }
    )
    val footerText = when {
        log == null -> stringResource(R.string.home_summary_empty)
        summary.isLegacyOrUnverified && summary.repetitionsCount > 0 ->
            stringResource(R.string.home_last_workout_legacy_reps_footer, summary.repetitionsText)
        summary.isLegacyOrUnverified ->
            stringResource(R.string.home_last_workout_legacy_footer)
        summary.repetitionsCount > 0 ->
            stringResource(R.string.home_last_workout_reps_footer, summary.repetitionsText)
        else -> null
    }
    Surface(
        modifier = modifier.fillMaxWidth().testTag("home_last_workout_card"),
        shape = IMFITShapes.CardSmall,
        color = colors.surface,
        border = BorderStroke(IMFITComponentSizes.border, colors.border)
    ) {
        Column(
            Modifier.padding(IMFITSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(IMFITSpacing.summaryGap)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.compact)
            ) {
                Icon(
                    painterResource(R.drawable.ic_home_history),
                    null,
                    Modifier.size(IMFITComponentSizes.metricIcon),
                    tint = colors.muted
                )
                Text(
                    headingText,
                    Modifier.weight(1f),
                    color = colors.muted,
                    style = IMFITTypography.historyHeading
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.xs)
                ) {
                    Text(
                        dateText,
                        color = colors.muted,
                        style = IMFITTypography.historyDate,
                        textAlign = TextAlign.End
                    )
                    Icon(
                        painterResource(R.drawable.ic_home_chevron_right),
                        null,
                        Modifier.size(IMFITSizes.iconXs),
                        tint = colors.muted
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = IMFITComponentSizes.metricStripMinHeight)
                    .padding(IMFITComponentSizes.border),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HomeSummaryMetric(
                    value = durationText,
                    caption = stringResource(R.string.home_last_workout_duration_label),
                    supporting = stringResource(
                        R.string.home_last_workout_isometric_support,
                        summary.isometricDurationText
                    ),
                    icon = R.drawable.ic_home_timer,
                    captionStyle = IMFITTypography.metricCaption,
                    modifier = Modifier.weight(1f)
                )
                HomeSummaryMetricDivider(color = colors.border)
                HomeSummaryMetric(
                    value = stringResource(R.string.progress_volume_unit, summary.primaryVolumeText),
                    caption = volumeCaption,
                    supporting = stringResource(
                        R.string.home_last_workout_additional_volume_support,
                        summary.additionalVolumeText
                    ),
                    icon = R.drawable.ic_home_dumbbell,
                    captionStyle = IMFITTypography.metricCaptionCompact,
                    modifier = Modifier.weight(1f)
                )
                HomeSummaryMetricDivider(color = colors.border)
                HomeSummaryMetric(
                    value = stringResource(R.string.home_last_workout_distance_unit, summary.distanceKmText),
                    caption = stringResource(R.string.home_last_workout_distance_label),
                    supporting = stringResource(
                        R.string.home_last_workout_cardio_support,
                        summary.cardioDurationText
                    ),
                    icon = R.drawable.ic_home_footprints,
                    captionStyle = IMFITTypography.metricCaption,
                    modifier = Modifier.weight(1f)
                )
            }
            if (footerText != null) {
                Text(
                    text = footerText,
                    color = colors.muted,
                    style = IMFITTypography.metricSupporting
                )
            }
        }
    }
}

@Composable
internal fun HomeSectionErrorCard(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "home_section_error"
) {
    val colors = homePalette()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag(testTag)
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = IMFITShapes.CardSmall,
        color = colors.surface,
        border = BorderStroke(IMFITComponentSizes.border, colors.border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IMFITSpacing.cardPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md)
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_home_alert),
                contentDescription = null,
                modifier = Modifier.size(IMFITSizes.iconSm),
                tint = colors.error
            )
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                color = colors.text,
                style = IMFITTypography.supporting
            )
            TextButton(
                onClick = onRetry,
                modifier = Modifier
                    .heightIn(min = IMFITSizes.buttonHeight)
                    .testTag("${testTag}_retry"),
                colors = ButtonDefaults.textButtonColors(contentColor = colors.primary)
            ) {
                Text(
                    text = stringResource(R.string.redesign_retry),
                    style = IMFITTypography.textAction
                )
            }
        }
    }
}

@Composable
private fun HomeSummaryMetricDivider(color: Color) {
    Box(
        modifier = Modifier
            .width(IMFITComponentSizes.border)
            .height(IMFITComponentSizes.metricDivider)
            .background(color)
    )
}

@Composable
private fun HomeSummaryMetric(
    value: String,
    caption: String,
    supporting: String,
    @DrawableRes icon: Int,
    captionStyle: TextStyle,
    modifier: Modifier = Modifier
) {
    val colors = homePalette()
    Column(
        modifier = modifier.padding(vertical = IMFITSpacing.sm),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(IMFITSpacing.compact, Alignment.CenterVertically)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.xs)
        ) {
            Icon(
                painterResource(icon),
                null,
                Modifier.size(IMFITComponentSizes.metricIcon),
                tint = colors.primary
            )
            Text(
                caption,
                color = colors.muted,
                style = captionStyle,
                textAlign = TextAlign.Center
            )
        }
        Text(
            value,
            color = colors.text,
            style = IMFITTypography.metricValue,
            textAlign = TextAlign.Center
        )
        Text(
            supporting,
            color = colors.muted,
            style = IMFITTypography.metricSupporting,
            textAlign = TextAlign.Center
        )
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
                MuscleCategoryArtwork(categoryArtAsset(template.primaryMuscleCategory), Modifier.fillMaxSize())
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

