package com.diajarkoding.imfit.presentation.ui.progress

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.presentation.components.common.CalendarMonth
import com.diajarkoding.imfit.presentation.components.common.CheckCircle
import com.diajarkoding.imfit.presentation.components.common.FitnessCenter
import com.diajarkoding.imfit.presentation.components.common.Symbols
import com.diajarkoding.imfit.presentation.components.common.Timer
import com.diajarkoding.imfit.theme.IMFITColors
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.IMFITTypography
import java.time.LocalDate
import java.util.Locale

@Composable
internal fun WorkoutHistoryDateHeader(
    localDate: LocalDate,
    locale: Locale,
    modifier: Modifier = Modifier
) {
    val colors = IMFITColors.training
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("workout_history_date_header"),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = stringResource(R.string.workout_history_date_context),
            color = colors.primary,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp
        )
        Text(
            text = formatHeaderWeekday(localDate, locale),
            color = colors.text,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = formatHeaderFullDate(localDate, locale),
            color = colors.muted,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
internal fun WorkoutHistoryUpdateFailureBanner(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = IMFITColors.training
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("workout_history_banner_error")
            .semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(10.dp),
        color = colors.errorContainer
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_auth_error),
                    contentDescription = null,
                    tint = colors.error,
                    modifier = Modifier.size(20.dp)
                )
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.workout_history_banner_error_title),
                        color = colors.error,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.workout_history_banner_error_message),
                        color = colors.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            OutlinedButton(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag("workout_history_banner_retry"),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, colors.error),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = colors.surface,
                    contentColor = colors.error
                )
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_home_refresh),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.workout_history_retry_action),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
internal fun WorkoutHistoryDailyTotals(
    totals: WorkoutHistoryTotals,
    locale: Locale,
    stacked: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = IMFITColors.training
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("workout_history_daily_totals"),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.workout_history_totals_heading),
                color = colors.text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.successContainer)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Symbols.Default.CheckCircle,
                        contentDescription = null,
                        tint = colors.success,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = pluralStringResource(
                            R.plurals.workout_history_sessions_count,
                            totals.sessionCount,
                            totals.sessionCount
                        ),
                        color = colors.success,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        val durationLabel = stringResource(R.string.workout_history_metric_duration)
        val durationValue = stringResource(R.string.workout_history_duration_minutes_format, totals.durationMinutes)
        val volumeLabel = stringResource(R.string.workout_history_metric_volume)
        val volumeValue = formatVolume(totals.totalVolume, locale)
        val exercisesLabel = stringResource(R.string.workout_history_metric_exercises)
        val exercisesValue = "${totals.totalExercises}"
        val setsLabel = stringResource(R.string.workout_history_metric_sets)
        val setsValue = "${totals.completedSets}"

        if (stacked) {
            DailyMetricCardStacked(icon = Symbols.Default.Timer, label = durationLabel, value = durationValue)
            DailyMetricCardStacked(icon = Symbols.Default.FitnessCenter, label = volumeLabel, value = volumeValue)
            DailyMetricCardStacked(painter = painterResource(R.drawable.ic_fitness), label = exercisesLabel, value = exercisesValue)
            DailyMetricCardStacked(icon = Symbols.Default.CheckCircle, label = setsLabel, value = setsValue)
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DailyMetricCardStandard(
                    modifier = Modifier.weight(1f),
                    icon = Symbols.Default.Timer,
                    label = durationLabel,
                    value = durationValue
                )
                DailyMetricCardStandard(
                    modifier = Modifier.weight(1f),
                    icon = Symbols.Default.FitnessCenter,
                    label = volumeLabel,
                    value = volumeValue
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                DailyMetricCardStandard(
                    modifier = Modifier.weight(1f),
                    painter = painterResource(R.drawable.ic_fitness),
                    label = exercisesLabel,
                    value = exercisesValue
                )
                DailyMetricCardStandard(
                    modifier = Modifier.weight(1f),
                    icon = Symbols.Default.CheckCircle,
                    label = setsLabel,
                    value = setsValue
                )
            }
        }
    }
}

@Composable
private fun DailyMetricCardStandard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    painter: Painter? = null
) {
    val colors = IMFITColors.training
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(16.dp)
                    )
                } else if (painter != null) {
                    Icon(
                        painter = painter,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = label,
                    color = colors.muted,
                    style = MaterialTheme.typography.labelSmall
                )
            }
            Text(
                text = value,
                color = colors.text,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun DailyMetricCardStacked(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    painter: Painter? = null
) {
    val colors = IMFITColors.training
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(16.dp)
                    )
                } else if (painter != null) {
                    Icon(
                        painter = painter,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Text(
                    text = label,
                    color = colors.muted,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Text(
                text = value,
                color = colors.text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
internal fun WorkoutHistorySessionCard(
    workoutLog: WorkoutLog,
    workoutNumber: Int,
    totalWorkouts: Int,
    locale: Locale,
    stacked: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = IMFITColors.training
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("workout_history_session_card_$workoutNumber"),
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Heading: ordinal (if totalWorkouts > 1) + template name + duration badge
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (totalWorkouts > 1) {
                    Text(
                        text = stringResource(
                            R.string.workout_history_session_ordinal_format,
                            workoutNumber,
                            totalWorkouts
                        ),
                        color = colors.primary,
                        style = IMFITTypography.caption,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                val durationBadge = @Composable {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(colors.raised)
                            .padding(horizontal = 8.dp, vertical = 5.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Symbols.Default.Timer,
                                contentDescription = null,
                                tint = colors.muted,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                text = stringResource(
                                    R.string.workout_history_duration_minutes_format,
                                    workoutLog.durationMinutes
                                ),
                                color = colors.muted,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                val templateTitle = workoutLog.templateName.ifBlank {
                    stringResource(R.string.workout_history_session_default_name)
                }
                if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = templateTitle,
                            color = colors.text,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        durationBadge()
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = templateTitle,
                            color = colors.text,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        durationBadge()
                    }
                }
            }

            // Session Metrics: Volume, Gerakan, Set selesai
            Column {
                HorizontalDivider(color = colors.border)
                Spacer(modifier = Modifier.height(12.dp))
                val volumeValue = formatVolume(workoutLog.totalVolume, locale)
                val exercisesValue = "${workoutLog.exerciseLogs.size}"
                val setsValue = "${workoutLog.exerciseLogs.sumOf { it.completedSets }}"
                val volumeLabel = stringResource(R.string.workout_history_metric_volume)
                val exercisesLabel = stringResource(R.string.workout_history_metric_exercises)
                val setsLabel = stringResource(R.string.workout_history_metric_sets)

                if (stacked) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        SessionMetricRow(volumeLabel, volumeValue)
                        SessionMetricRow(exercisesLabel, exercisesValue)
                        SessionMetricRow(setsLabel, setsValue)
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        SessionMetricColumn(Modifier.weight(1f), volumeLabel, volumeValue)
                        SessionMetricColumn(Modifier.weight(1f), exercisesLabel, exercisesValue)
                        SessionMetricColumn(Modifier.weight(1f), setsLabel, setsValue)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = colors.border)
            }

            // Exercise breakdown (only if exercise logs exist)
            if (workoutLog.exerciseLogs.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    workoutLog.exerciseLogs.forEachIndexed { index, exerciseLog ->
                        WorkoutHistoryExerciseRow(exerciseLog = exerciseLog, locale = locale)
                        if (index < workoutLog.exerciseLogs.lastIndex) {
                            HorizontalDivider(color = colors.border)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionMetricColumn(
    modifier: Modifier = Modifier,
    caption: String,
    value: String
) {
    val colors = IMFITColors.training
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = caption,
            color = colors.muted,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 11.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = value,
            color = colors.text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SessionMetricRow(
    caption: String,
    value: String
) {
    val colors = IMFITColors.training
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = caption,
            color = colors.muted,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = value,
            color = colors.text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
internal fun WorkoutHistoryExerciseRow(
    exerciseLog: ExerciseLog,
    locale: Locale,
    modifier: Modifier = Modifier
) {
    val colors = IMFITColors.training
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = exerciseLog.exercise.name,
            color = colors.text,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Symbols.Default.CheckCircle,
                    contentDescription = null,
                    tint = colors.success,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.workout_history_exercise_sets_count,
                        exerciseLog.completedSets,
                        exerciseLog.completedSets
                    ),
                    color = colors.success,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                text = formatVolume(exerciseLog.totalVolume, locale),
                color = colors.muted,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
internal fun WorkoutHistoryLoadingSkeleton(
    modifier: Modifier = Modifier
) {
    val colors = IMFITColors.training
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("workout_history_loading")
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.workout_history_loading_status),
            color = colors.muted,
            style = MaterialTheme.typography.bodySmall
        )
        // 2x2 Metric skeletons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SkeletonMetricCard(Modifier.weight(1f))
            SkeletonMetricCard(Modifier.weight(1f))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SkeletonMetricCard(Modifier.weight(1f))
            SkeletonMetricCard(Modifier.weight(1f))
        }
        // Session card skeleton
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = colors.surface,
            border = BorderStroke(1.dp, colors.border)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(width = 156.dp, height = 18.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors.border)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(28.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors.border)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors.border)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(colors.border)
                )
            }
        }
    }
}

@Composable
private fun SkeletonMetricCard(modifier: Modifier = Modifier) {
    val colors = IMFITColors.training
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(width = 64.dp, height = 10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(colors.border)
            )
            Box(
                modifier = Modifier
                    .size(width = 92.dp, height = 22.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(colors.border)
            )
        }
    }
}

@Composable
internal fun WorkoutHistoryEmptyState(
    modifier: Modifier = Modifier
) {
    val colors = IMFITColors.training
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp, horizontal = 16.dp)
            .testTag("workout_history_empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Symbols.Default.CalendarMonth,
                contentDescription = null,
                tint = colors.onPrimaryContainer,
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            text = stringResource(R.string.workout_history_empty_title),
            color = colors.text,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.workout_history_empty_message),
            color = colors.muted,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
internal fun WorkoutHistoryErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = IMFITColors.training
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 32.dp, horizontal = 16.dp)
            .testTag("workout_history_error")
            .semantics { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(colors.errorContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_auth_error),
                contentDescription = null,
                tint = colors.error,
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            text = stringResource(R.string.workout_history_error_title),
            color = colors.text,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.workout_history_error_message),
            color = colors.muted,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Button(
            onClick = onRetry,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 54.dp)
                .testTag("workout_history_retry"),
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.primary,
                contentColor = colors.onPrimary
            )
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_home_refresh),
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.workout_history_retry_action),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}
