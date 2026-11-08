package com.diajarkoding.imfit.presentation.ui.workout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.TrackingType
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.presentation.components.common.CheckCircle
import com.diajarkoding.imfit.presentation.components.common.FitnessCenter
import com.diajarkoding.imfit.presentation.components.common.RoutineButton
import com.diajarkoding.imfit.presentation.components.common.Symbols
import com.diajarkoding.imfit.presentation.components.common.Timer
import com.diajarkoding.imfit.theme.IMFITColors
import java.util.Locale

@Composable
internal fun WorkoutSummaryTopBar(
    modifier: Modifier = Modifier
) {
    val colors = IMFITColors.training
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
            .statusBarsPadding()
            .heightIn(min = 64.dp)
            .testTag("workout_summary_top_bar")
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.summary_screen_title),
            color = colors.text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
internal fun WorkoutSummaryOutcome(
    templateName: String,
    modifier: Modifier = Modifier
) {
    val colors = IMFITColors.training
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .testTag("workout_summary_outcome"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(colors.successContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Symbols.Filled.CheckCircle,
                contentDescription = null,
                tint = colors.success,
                modifier = Modifier.size(32.dp)
            )
        }

        Text(
            text = stringResource(R.string.summary_saved_title),
            color = colors.text,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )

        Text(
            text = templateName,
            color = colors.muted,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
internal fun WorkoutSummaryMetrics(
    formattedDuration: String,
    exerciseLogs: List<ExerciseLog>,
    locale: Locale,
    stacked: Boolean,
    modifier: Modifier = Modifier,
    formattedActiveTime: String? = null
) {
    Column(modifier.fillMaxWidth().testTag("workout_summary_metrics"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SummaryMetricCard(Symbols.Filled.Timer, stringResource(R.string.label_duration), formattedDuration)
        // v3 only: whole-session time without Pause (includes rest). Distinct from the gross span above and from exercise durations.
        formattedActiveTime?.let {
            SummaryMetricCard(Symbols.Filled.Timer, stringResource(R.string.label_active_time), it,
                Modifier.testTag("workout_summary_active_time"))
        }
        WorkoutMeasurementMetrics(exerciseLogs, locale, stacked)
    }
}

@Composable
private fun SummaryMetricCard(
    icon: ImageVector,
    caption: String,
    value: String,
    modifier: Modifier = Modifier
) {
    val colors = IMFITColors.training
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = caption,
                    color = colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp
                )
            }
            Text(
                text = value,
                color = colors.text,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                fontSize = 22.sp
            )
        }
    }
}

@Composable
internal fun WorkoutSummaryExerciseCard(
    exerciseLog: ExerciseLog,
    locale: Locale,
    modifier: Modifier = Modifier
) {
    val colors = IMFITColors.training
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Exercise Identity
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = exerciseLog.exercise.name,
                    color = colors.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(exerciseLog.exercise.muscleCategory.stringResourceId),
                    color = colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp
                )
            }

            // Exercise Counts Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = pluralStringResource(
                        R.plurals.summary_completed_sets_count,
                        exerciseLog.completedSets,
                        exerciseLog.completedSets
                    ),
                    color = colors.success,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f)
                )
                val headerMetricText = when (exerciseLog.trackingType) {
                    TrackingType.WEIGHT_REPS -> formatSummaryVolume(exerciseLog.totalVolume, locale)
                    TrackingType.BODYWEIGHT_REPS -> if (exerciseLog.additionalVolume > 0f) "+${formatSummaryVolume(exerciseLog.additionalVolume, locale)}" else "${exerciseLog.totalReps} reps"
                    TrackingType.ASSISTED_REPS, TrackingType.REPS_ONLY -> "${exerciseLog.totalReps} reps"
                    TrackingType.DURATION -> "${exerciseLog.totalDurationSeconds}s"
                    TrackingType.DISTANCE_DURATION -> if ((exerciseLog.totalDistanceMeters ?: 0.0) > 0) "${String.format(locale, "%.2f km", (exerciseLog.totalDistanceMeters ?: 0.0) / 1000.0)} · ${exerciseLog.totalDurationSeconds}s" else "${exerciseLog.totalDurationSeconds}s"
                }
                Text(
                    text = headerMetricText,
                    color = colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }

            // Completed Sets Details
            if (exerciseLog.sets.any { it.isCompleted }) {
                HorizontalDivider(color = colors.border)

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    exerciseLog.sets.filter { it.isCompleted }.forEach { set ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.active_workout_set_number, set.setNumber),
                                color = colors.muted,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            val setText = workoutSetMeasurementText(exerciseLog.trackingType, set, locale)
                            Text(
                                text = setText,
                                color = colors.text,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                fontSize = 13.sp,
                                textAlign = TextAlign.End,
                                modifier = Modifier.weight(2f)
                            )
                        }
                    }
                }
            } else {
                HorizontalDivider(color = colors.border)
                Text(
                    text = stringResource(R.string.summary_no_completed_sets),
                    color = colors.muted,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
internal fun WorkoutSummaryFooter(
    onDoneClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = IMFITColors.training
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("workout_summary_footer"),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp)
        ) {
            RoutineButton(
                text = stringResource(R.string.action_done),
                icon = Symbols.Filled.CheckCircle,
                onClick = onDoneClick
            )
        }
    }
}

@Composable
internal fun WorkoutSummaryLoadingState(
    modifier: Modifier = Modifier
) {
    val colors = IMFITColors.training
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp)
            .testTag("workout_summary_loading"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        repeat(3) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.border.copy(alpha = 0.5f))
            )
        }
        Text(
            text = stringResource(R.string.summary_loading),
            color = colors.muted,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
internal fun WorkoutSummaryUnavailableState(
    onNavigateToHome: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = IMFITColors.training
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp)
            .testTag("workout_summary_unavailable"),
        shape = RoundedCornerShape(14.dp),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.summary_unavailable_title),
                color = colors.text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.summary_unavailable_desc),
                color = colors.muted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            RoutineButton(
                text = stringResource(R.string.summary_back_to_home),
                onClick = onNavigateToHome
            )
        }
    }
}

@Composable
internal fun workoutSetMeasurementText(type: TrackingType, set: WorkoutSet, locale: Locale): String = when (type) {
    TrackingType.WEIGHT_REPS -> stringResource(R.string.summary_set_details_formatted, set.weightKg?.let { formatSetWeight(it, locale) } ?: "—", set.reps ?: 0)
    TrackingType.BODYWEIGHT_REPS -> if ((set.additionalWeight ?: 0f) > 0f) {
        "+${formatSetWeight((set.additionalWeight ?: 0f).toDouble(), locale)} kg × ${set.reps} reps"
    } else "${set.reps} reps"
    TrackingType.ASSISTED_REPS -> "${set.reps} reps · ${stringResource(R.string.active_set_assistance_weight)} ${set.assistanceWeight?.let { formatSetWeight(it.toDouble(), locale) } ?: "—"}"
    TrackingType.REPS_ONLY -> "${set.reps} reps"
    TrackingType.DURATION -> set.durationSeconds?.takeIf { it > 0 }?.let { "$it s" } ?: "—"
    TrackingType.DISTANCE_DURATION -> {
        val pace = set.paceSecondsPerKilometer?.let { stringResource(R.string.active_set_pace, formatPace(it, locale)) }
        buildString {
            set.distanceMeters?.takeIf { it > 0 }?.let { append(String.format(locale, "%.2f km", it / 1000.0)) }
            set.durationSeconds?.takeIf { it > 0 }?.let {
                if (isNotEmpty()) append(" · ")
                append(String.format(locale, "%d:%02d", it / 60, it % 60))
            }
            if (pace != null) { if (isNotEmpty()) append(" · "); append(pace) }
            if (isEmpty()) append("—")
        }
    }
}
