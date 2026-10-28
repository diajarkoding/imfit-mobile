package com.diajarkoding.imfit.presentation.ui.progress

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.theme.*
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun ProgressProfileRow(state: ProgressState, onProfile: () -> Unit) {
    val colors = IMFITColors.training
    val profileLabel = stringResource(R.string.redesign_profile)
    Row(
        Modifier.fillMaxWidth().heightIn(min = IMFITProgress.profileHeight)
            .clip(RoundedCornerShape(IMFITCornerRadius.small))
            .clickable(role = Role.Button, onClick = onProfile)
            .semantics(mergeDescendants = true) { contentDescription = profileLabel },
        horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(IMFITSizes.minimumInteractiveTarget).clip(CircleShape).background(colors.primaryContainer),
            contentAlignment = Alignment.Center) {
            if (state.userProfilePhotoUri.isNullOrBlank()) {
                Icon(Symbols.Default.Person, null, Modifier.size(IMFITSizes.iconMd), tint = colors.onPrimaryContainer)
            } else {
                IMFITProfilePhoto(state.userProfilePhotoUri, size = IMFITSizes.minimumInteractiveTarget)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(IMFITProgress.identityGap)) {
            Text(state.userName.ifBlank { stringResource(R.string.progress_overview_guest) },
                color = colors.text, style = IMFITTypography.profileName)
            if (state.userEmail.isNotBlank()) {
                Text(state.userEmail, color = colors.muted, style = IMFITTypography.caption,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.size(IMFITSizes.minimumInteractiveTarget), contentAlignment = Alignment.Center) {
            Icon(Symbols.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(IMFITSizes.iconSm), tint = colors.muted)
        }
    }
}

@Composable
internal fun ProgressMetricOverview(metrics: ProgressMetrics) {
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val volume = stringResource(R.string.redesign_kg, NumberFormat.getNumberInstance(locale).apply {
        maximumFractionDigits = 0
    }.format(metrics.monthlyVolume))
    val time = pluralStringResource(R.plurals.progress_overview_minutes, metrics.monthlyMinutes, metrics.monthlyMinutes)
    val period = metrics.month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).replaceFirstChar { it.uppercaseChar() }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val stacked = maxWidth < IMFITComponentSizes.sectionStackWidth ||
            LocalDensity.current.fontScale > IMFITComponentSizes.adaptiveFontScale
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(IMFITSpacing.md)) {
                ProgressMetricCard(volume, stringResource(R.string.progress_overview_volume),
                    period, Symbols.Outlined.FitnessCenter)
                ProgressMetricCard(time, stringResource(R.string.progress_overview_weekly_time),
                    period, Symbols.Default.Timer)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md)) {
                ProgressMetricCard(volume, stringResource(R.string.progress_overview_volume),
                    period, Symbols.Outlined.FitnessCenter, Modifier.weight(1f))
                ProgressMetricCard(time, stringResource(R.string.progress_overview_weekly_time),
                    period, Symbols.Default.Timer, Modifier.weight(1f))
            }
        }
    }
}

@Composable
internal fun ProgressMetricCard(value: String, caption: String, period: String, icon: ImageVector, modifier: Modifier = Modifier) {
    val colors = IMFITColors.training
    Surface(modifier.fillMaxWidth().heightIn(min = IMFITProgress.metricHeight),
        shape = IMFITShapes.CardSmall, color = colors.surface, border = BorderStroke(IMFITComponentSizes.border, colors.border)) {
        Column(Modifier.padding(IMFITSpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.compact)) {
                Icon(icon, null, Modifier.size(IMFITSizes.iconXs), tint = colors.primary)
                Text(caption, color = colors.muted, style = IMFITTypography.progressMetricCaption)
            }
            Text(value, color = colors.text, style = IMFITTypography.progressMetricValue)
            Text(period, color = colors.muted, style = IMFITTypography.progressPeriod)
        }
    }
}

@Composable
internal fun ProgressMonthCalendar(
    month: YearMonth,
    today: LocalDate,
    selectedDate: LocalDate,
    workouts: Map<LocalDate, List<WorkoutLog>>,
    onSelect: (LocalDate) -> Unit,
    onYearlyCalendar: (() -> Unit)? = null,
    enabled: Boolean = true,
    markerCounts: Map<LocalDate, Long>? = null
) {
    val colors = IMFITColors.training
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val monthLabel = month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).replaceFirstChar { it.uppercaseChar() }
    val calendarLabel = stringResource(R.string.calendar_view_yearly)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(IMFITProgress.calendarRadius),
        color = colors.surface, border = BorderStroke(IMFITComponentSizes.border, colors.border)) {
        Column(Modifier.padding(IMFITSpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(IMFITSpacing.controlGap)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = IMFITSizes.minimumInteractiveTarget)
                    .clip(RoundedCornerShape(IMFITCornerRadius.small))
                    .then(if (onYearlyCalendar != null) Modifier.clickable(role = Role.Button, onClick = onYearlyCalendar)
                        .semantics(mergeDescendants = true) { contentDescription = calendarLabel } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)
            ) {
                Text(monthLabel, Modifier.weight(1f), color = colors.text, style = IMFITTypography.calendarMonth)
                if (onYearlyCalendar != null) Box(Modifier.size(IMFITSizes.minimumInteractiveTarget), contentAlignment = Alignment.Center) {
                    Icon(Symbols.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(IMFITSizes.iconSm), tint = colors.primary)
                }
            }
            Row(Modifier.fillMaxWidth()) {
                stringArrayResource(R.array.progress_overview_weekdays).forEach { day ->
                    Text(day, Modifier.weight(1f), color = colors.muted, style = IMFITTypography.calendarWeekday, textAlign = TextAlign.Center)
                }
            }
            Column {
                monthDays(month).chunked(7).forEach { week ->
                    Row(Modifier.fillMaxWidth()) {
                        week.forEach { day ->
                            if (day == null) {
                                Spacer(Modifier.weight(1f).height(IMFITSizes.minimumInteractiveTarget))
                            } else {
                                val date = month.atDay(day)
                                val count = (markerCounts?.get(date) ?: workouts[date].orEmpty().size.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                                val isSelected = date == selectedDate
                                val description = pluralStringResource(R.plurals.progress_overview_day_description, count,
                                    date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", locale)), count,
                                    if (date == today) stringResource(R.string.redesign_today) else "")
                                Box(
                                    Modifier.weight(1f).heightIn(min = IMFITSizes.minimumInteractiveTarget)
                                        .clip(RoundedCornerShape(IMFITCornerRadius.small))
                                        .clickable(enabled = enabled, role = Role.Button) { onSelect(date) }
                                        .semantics(mergeDescendants = true) { contentDescription = description; selected = isSelected },
                                    contentAlignment = Alignment.Center
                                ) {
                                    Box(
                                        Modifier.size(IMFITProgress.dateIndicator)
                                            .background(when { isSelected -> colors.primaryContainer; count > 0 -> colors.primary; else -> colors.raised }, CircleShape)
                                            .then(if (isSelected) Modifier.border(IMFITComponentSizes.activeBorder, colors.primary, CircleShape) else Modifier),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(day.toString(), color = when { isSelected -> colors.onPrimaryContainer; count > 0 -> colors.onPrimary; else -> colors.text },
                                            style = IMFITTypography.progressDate,
                                            fontWeight = if (isSelected || count > 0) FontWeight.SemiBold else FontWeight.Normal)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = IMFITSpacing.xs), horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.xl)) {
                CalendarLegend(stringResource(R.string.progress_overview_has_workout), false, Modifier.weight(1f))
                CalendarLegend(stringResource(R.string.progress_overview_selected), true, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CalendarLegend(label: String, outlined: Boolean, modifier: Modifier) {
    val colors = IMFITColors.training
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.compact)) {
        Box(Modifier.size(IMFITProgress.legendMarker)
            .background(if (outlined) Color.Transparent else colors.primary, CircleShape)
            .then(if (outlined) Modifier.border(IMFITComponentSizes.border, colors.primary, CircleShape) else Modifier))
        Text(label, color = colors.muted, style = IMFITTypography.historyDate)
    }
}

@Composable
internal fun ProgressSelectedDay(date: LocalDate, logs: List<WorkoutLog>, onHistory: () -> Unit) {
    val colors = IMFITColors.training
    val locale = LocalConfiguration.current.locales[0] ?: Locale.ROOT
    val duration = logs.sumOf { it.durationMinutes }
    val volume = stringResource(R.string.redesign_kg,
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 0 }.format(logs.sumOf { it.totalVolume.toDouble() }))
    val minutes = pluralStringResource(R.plurals.progress_overview_minutes, duration, duration)
    val description = if (logs.isEmpty()) stringResource(R.string.progress_overview_empty_day)
        else pluralStringResource(R.plurals.progress_overview_day_workouts, logs.size, logs.size, minutes, volume)
    Row(
        Modifier.fillMaxWidth().heightIn(min = IMFITProgress.selectedDayHeight)
            .clip(RoundedCornerShape(IMFITCornerRadius.small))
            .then(if (logs.isNotEmpty()) Modifier.clickable(role = Role.Button, onClick = onHistory) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md)
    ) {
        Box(Modifier.size(IMFITSizes.avatarSm).clip(RoundedCornerShape(IMFITCornerRadius.small))
            .background(colors.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(Symbols.Default.CalendarMonth, null, Modifier.size(IMFITSizes.iconSm), tint = colors.primary)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(IMFITProgress.identityGap)) {
            Text(date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", locale)).replaceFirstChar { it.uppercaseChar() },
                color = colors.text, style = IMFITTypography.compactTitle)
            Text(description, color = colors.muted, style = IMFITTypography.caption)
        }
    }
}
