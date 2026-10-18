package com.diajarkoding.imfit.presentation.ui.workout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.presentation.components.common.Add
import com.diajarkoding.imfit.presentation.components.common.Check
import com.diajarkoding.imfit.presentation.components.common.CheckCircle
import com.diajarkoding.imfit.presentation.components.common.Close
import com.diajarkoding.imfit.presentation.components.common.Delete
import com.diajarkoding.imfit.presentation.components.common.FitnessCenter
import com.diajarkoding.imfit.presentation.components.common.IMFITPlatformDialog
import com.diajarkoding.imfit.presentation.components.common.KeyboardArrowRight
import com.diajarkoding.imfit.presentation.components.common.Pause
import com.diajarkoding.imfit.presentation.components.common.PlayArrow
import com.diajarkoding.imfit.presentation.components.common.RoutineButton
import com.diajarkoding.imfit.presentation.components.common.Schedule
import com.diajarkoding.imfit.presentation.components.common.Symbols
import com.diajarkoding.imfit.presentation.components.common.Timer
import com.diajarkoding.imfit.theme.IMFITColors
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
internal fun ActiveWorkoutTopBar(
    title: String,
    elapsedSeconds: Long,
    isPaused: Boolean,
    onCancelClick: () -> Unit,
    onPauseToggle: () -> Unit,
    modifier: Modifier = Modifier,
    actionsEnabled: Boolean = true
) {
    val colors = IMFITColors.training
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
            .statusBarsPadding()
            .heightIn(min = 64.dp)
            .testTag("active_workout_top_bar")
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onCancelClick,
            enabled = actionsEnabled,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Symbols.Filled.Close,
                contentDescription = stringResource(R.string.action_cancel),
                tint = colors.text,
                modifier = Modifier.size(22.dp)
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = if (isPaused) {
                    stringResource(R.string.active_session_paused_title)
                } else {
                    title.ifBlank { stringResource(R.string.active_session_default_title) }
                },
                color = colors.text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
                text = formatElapsedTime(elapsedSeconds),
                color = colors.success,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        IconButton(
            onClick = onPauseToggle,
            enabled = actionsEnabled,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = if (isPaused) Symbols.Filled.PlayArrow else Symbols.Filled.Pause,
                contentDescription = if (isPaused) {
                    stringResource(R.string.action_resume)
                } else {
                    stringResource(R.string.action_pause)
                },
                tint = colors.text,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
internal fun ActiveSessionOverview(
    completedSets: Int,
    totalSets: Int,
    volume: Float,
    locale: Locale,
    stacked: Boolean,
    modifier: Modifier = Modifier
) {
    val colors = IMFITColors.training
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("active_session_overview"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (stacked) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ActiveMetricCard(
                    icon = Symbols.Filled.CheckCircle,
                    caption = stringResource(R.string.active_session_sets_completed),
                    value = "$completedSets / $totalSets"
                )
                ActiveMetricCard(
                    icon = Symbols.Filled.FitnessCenter,
                    caption = stringResource(R.string.label_volume),
                    value = formatVolume(volume, locale)
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Box(Modifier.weight(1f)) {
                    ActiveMetricCard(
                        icon = Symbols.Filled.CheckCircle,
                        caption = stringResource(R.string.active_session_sets_completed),
                        value = "$completedSets / $totalSets"
                    )
                }
                Box(Modifier.weight(1f)) {
                    ActiveMetricCard(
                        icon = Symbols.Filled.FitnessCenter,
                        caption = stringResource(R.string.label_volume),
                        value = formatVolume(volume, locale)
                    )
                }
            }
        }

        Text(
            text = stringResource(R.string.active_session_volume_meaning),
            color = colors.muted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
    }
}

@Composable
private fun ActiveMetricCard(
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
internal fun ActiveSessionPausedBanner(
    modifier: Modifier = Modifier
) {
    val colors = IMFITColors.training
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("active_workout_pause_banner"),
        shape = RoundedCornerShape(10.dp),
        color = colors.primaryContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Symbols.Filled.Pause,
                contentDescription = null,
                tint = colors.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = stringResource(R.string.active_session_pause_explanation),
                color = colors.onPrimaryContainer,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
internal fun ActiveSessionFinishReminderBanner(
    modifier: Modifier = Modifier
) {
    val colors = IMFITColors.training
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("active_workout_finish_banner"),
        shape = RoundedCornerShape(10.dp),
        color = colors.successContainer
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(
                imageVector = Symbols.Filled.CheckCircle,
                contentDescription = null,
                tint = colors.success,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = stringResource(R.string.active_session_finish_reminder),
                color = colors.success,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
internal fun ActiveExerciseCard(
    exerciseLog: ExerciseLog,
    mutationEnabled: Boolean,
    isPaused: Boolean,
    onRemoveExercise: () -> Unit,
    onSetUpdate: (setIndex: Int, weight: Float, reps: Int) -> Unit,
    onSetComplete: (setIndex: Int) -> Unit,
    onAddSet: () -> Unit,
    onRemoveSet: (setIndex: Int) -> Unit,
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
            // Exercise Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
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

                if (exerciseLog.sets.none { it.isCompleted }) {
                    val removeDesc = stringResource(
                        R.string.active_session_remove_exercise,
                        exerciseLog.exercise.name
                    )
                    IconButton(
                        onClick = onRemoveExercise,
                        enabled = mutationEnabled,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics(mergeDescendants = true) {
                                contentDescription = removeDesc
                            }
                    ) {
                        Icon(
                            imageVector = Symbols.Filled.Delete,
                            contentDescription = null,
                            tint = colors.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // Set Table Header
            if (LocalConfiguration.current.screenWidthDp > 340 && LocalConfiguration.current.fontScale < 1.25f) Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.active_session_column_set),
                    color = colors.muted,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(28.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.active_session_column_kg),
                    color = colors.muted,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.active_session_column_reps),
                    color = colors.muted,
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(4.dp))
                Box(Modifier.size(48.dp)) // Spacer matching delete icon button
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Symbols.Filled.Check,
                        contentDescription = stringResource(R.string.active_session_column_done),
                        tint = colors.muted,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Set Rows
            exerciseLog.sets.forEachIndexed { setIndex, set ->
                key(set.setNumber) {
                    ActiveSetRow(
                        set = set,
                        exerciseName = exerciseLog.exercise.name,
                        mutationEnabled = mutationEnabled,
                        canDelete = exerciseLog.sets.size > 1 && !set.isCompleted && mutationEnabled,
                        isPaused = isPaused,
                        onUpdate = { weight, reps -> onSetUpdate(setIndex, weight, reps) },
                        onComplete = { onSetComplete(setIndex) },
                        onDelete = { onRemoveSet(setIndex) }
                    )
                }
            }

            // Add Set Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.primary.copy(alpha = 0.08f))
                    .clickable(enabled = mutationEnabled, role = Role.Button, onClick = onAddSet)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Symbols.Filled.Add,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = stringResource(R.string.action_add_set),
                        color = colors.primary,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
internal fun ActiveSessionFooter(
    onRestSettingsClick: () -> Unit,
    onFinishClick: () -> Unit,
    canFinish: Boolean,
    isFinishing: Boolean,
    modifier: Modifier = Modifier,
    settingsEnabled: Boolean = true,
    stacked: Boolean = false
) {
    val colors = IMFITColors.training
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("active_workout_footer"),
        color = colors.surface,
        border = BorderStroke(1.dp, colors.border)
    ) {
        Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            val settingsAction: @Composable () -> Unit = {
                // Rest timer settings action
                Surface(
                    onClick = onRestSettingsClick,
                    enabled = settingsEnabled,
                    modifier = Modifier.size(54.dp),
                    shape = RoundedCornerShape(10.dp),
                    color = colors.raised,
                    border = BorderStroke(1.dp, colors.border)
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Symbols.Filled.Timer,
                            contentDescription = stringResource(R.string.active_workout_rest_timer),
                            tint = colors.text,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }

            }
            val finishAction: @Composable () -> Unit = {
                    RoutineButton(
                        text = stringResource(if (isFinishing) R.string.active_session_saving else R.string.action_finish_workout),
                        icon = Symbols.Filled.Check,
                        onClick = onFinishClick,
                        enabled = canFinish,
                        loading = isFinishing
                    )
            }
            if (stacked) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    settingsAction()
                    finishAction()
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    settingsAction()
                    Box(Modifier.weight(1f)) { finishAction() }
                }
            }
        }
    }
}

@Composable
internal fun CancelWorkoutDialog(
    completedSets: Int,
    totalSets: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = IMFITColors.training
    val spec = resolveCancelDialogMessage(completedSets, totalSets)
    val message = stringResource(spec.stringRes, *spec.formatArgs.toTypedArray())

    IMFITPlatformDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .testTag("cancel_workout_dialog"),
            shape = RoundedCornerShape(28.dp),
            color = colors.surface
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(colors.errorContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Symbols.Filled.Delete,
                            contentDescription = null,
                            tint = colors.error,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        text = stringResource(R.string.active_session_cancel_title),
                        color = colors.text,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                }

                Text(
                    text = message,
                    color = colors.muted,
                    style = MaterialTheme.typography.bodyMedium,
                    lineHeight = 20.sp
                )

                WorkoutDialogActions(secondary = {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colors.border),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = colors.text
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.active_session_cancel_continue),
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                }, primary = {
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.errorContainer,
                            contentColor = colors.error
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.active_session_cancel_confirm),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                })
            }
        }
    }
}

@Composable
internal fun RestTimerConfigDialog(
    currentRestSeconds: Int,
    soundEnabled: Boolean,
    onSoundChange: (Boolean) -> Unit,
    vibrationEnabled: Boolean,
    onVibrationChange: (Boolean) -> Unit,
    fullScreenAllowed: Boolean,
    onAlarmSettings: () -> Unit,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    val colors = IMFITColors.training
    val initialHours = currentRestSeconds / 3600
    val initialMinutes = (currentRestSeconds % 3600) / 60
    val initialSeconds = currentRestSeconds % 60

    var hours by rememberSaveable { mutableIntStateOf(initialHours) }
    var minutes by rememberSaveable { mutableIntStateOf(initialMinutes) }
    var seconds by rememberSaveable { mutableIntStateOf(initialSeconds) }
    var draftSoundEnabled by rememberSaveable { mutableStateOf(soundEnabled) }
    var draftVibrationEnabled by rememberSaveable { mutableStateOf(vibrationEnabled) }

    IMFITPlatformDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .testTag("rest_timer_config_dialog"),
            shape = RoundedCornerShape(28.dp),
            color = colors.surface
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = stringResource(R.string.active_workout_rest_timer),
                    color = colors.text,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = stringResource(R.string.rest_timer_scope_desc),
                    color = colors.muted,
                    style = MaterialTheme.typography.bodySmall
                )

                Text(
                    text = stringResource(R.string.rest_duration_label),
                    color = colors.text,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )

                // Wheel pickers
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.weight(1f)) {
                        WheelPickerColumn(
                            value = hours,
                            range = 0..23,
                            unit = stringResource(R.string.rest_timer_unit_hours),
                            onValueChange = { hours = it }
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        WheelPickerColumn(
                            value = minutes,
                            range = 0..59,
                            unit = stringResource(R.string.rest_timer_unit_minutes),
                            onValueChange = { minutes = it }
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        WheelPickerColumn(
                            value = seconds,
                            range = 0..59,
                            unit = stringResource(R.string.rest_timer_unit_seconds),
                            onValueChange = { seconds = it }
                        )
                    }
                }

                HorizontalDivider(color = colors.border)

                // Sound toggle
                RestTogglePreferenceRow(
                    icon = Symbols.Filled.Timer,
                    label = stringResource(R.string.rest_sound_enabled),
                    checked = draftSoundEnabled,
                    onCheckedChange = { draftSoundEnabled = it }
                )

                // Vibration toggle
                RestTogglePreferenceRow(
                    icon = Symbols.Filled.Schedule,
                    label = stringResource(R.string.rest_vibration_enabled),
                    checked = draftVibrationEnabled,
                    onCheckedChange = { draftVibrationEnabled = it }
                )

                HorizontalDivider(color = colors.border)

                // Alarm banner settings button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(role = Role.Button, onClick = onAlarmSettings)
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.rest_full_screen_settings),
                        color = colors.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(
                        imageVector = Symbols.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = colors.primary,
                        modifier = Modifier.size(16.dp)
                    )
                }

                if (!fullScreenAllowed) {
                    Text(
                        text = stringResource(R.string.rest_full_screen_limited),
                        color = colors.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                            .background(colors.errorContainer).padding(12.dp)
                    )
                }

                WorkoutDialogActions(secondary = {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colors.border),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.Transparent,
                            contentColor = colors.text
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.action_cancel),
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                }, primary = {
                    Button(
                        onClick = {
                            val total = hours * 3600 + minutes * 60 + seconds
                            onSoundChange(draftSoundEnabled)
                            onVibrationChange(draftVibrationEnabled)
                            onConfirm(if (total > 0) total else 60)
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 54.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = colors.primary,
                            contentColor = colors.onPrimary
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.action_save),
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                })
            }
        }
    }
}

@Composable
private fun RestTogglePreferenceRow(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val colors = IMFITColors.training
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colors.muted,
            modifier = Modifier.size(18.dp)
        )
        Text(
            text = label,
            color = colors.text,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = colors.onPrimary,
                checkedTrackColor = colors.primary,
                uncheckedThumbColor = colors.muted,
                uncheckedTrackColor = colors.raised
            )
        )
    }
}

@Composable
private fun WheelPickerColumn(
    value: Int,
    range: IntRange,
    unit: String,
    onValueChange: (Int) -> Unit
) {
    val colors = IMFITColors.training
    val itemHeight = 48.dp
    val listHeight = itemHeight * 3
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = value)
    val flingBehavior = rememberSnapFlingBehavior(lazyListState = listState)

    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val center = listState.firstVisibleItemIndex
            if (center != value && center in range) {
                onValueChange(center)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(listHeight)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.raised)
        ) {
            // Selected item background indicator
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(itemHeight)
                    .align(Alignment.Center)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.primaryContainer)
            )

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                flingBehavior = flingBehavior,
                contentPadding = PaddingValues(vertical = itemHeight)
            ) {
                items(range.toList(), key = { it }) { num ->
                    val isSelected = num == value
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(itemHeight)
                            .semantics { selected = isSelected; contentDescription = "$num $unit" }
                            .clickable {
                                coroutineScope.launch { listState.animateScrollToItem(num) }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = String.format(Locale.US, "%02d", num),
                            fontSize = if (isSelected) 28.sp else 22.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) colors.onPrimaryContainer else colors.muted,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        Text(
            text = unit,
            color = colors.muted,
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
internal fun RestTimerCountdownDialog(
    remainingSeconds: Int,
    exerciseName: String?,
    onExtend: () -> Unit,
    onDismiss: () -> Unit,
    onReduce: () -> Unit,
    totalSeconds: Float = 0f
) {
    val colors = IMFITColors.training
    IMFITPlatformDialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier
                .padding(horizontal = 20.dp, vertical = 24.dp)
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .testTag("rest_timer_countdown_dialog"),
            shape = RoundedCornerShape(28.dp),
            color = colors.surface
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    text = stringResource(R.string.active_workout_rest_timer),
                    color = colors.text,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center
                )

                if (!exerciseName.isNullOrBlank()) {
                    Text(
                        text = exerciseName,
                        color = colors.muted,
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }

                // The arc is derived from committed timer values, never an optimistic ticker.
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 216.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(216.dp)) {
                        val strokeWidth = 12.dp.toPx()
                        // Track
                        drawCircle(
                            color = colors.raised,
                            style = Stroke(width = strokeWidth)
                        )
                        // Remaining progress arc
                        drawArc(
                            color = colors.primary,
                            startAngle = -90f,
                            sweepAngle = 360f * restCountdownProgress(remainingSeconds, totalSeconds),
                            useCenter = false,
                            style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                        )
                    }

                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val minutes = remainingSeconds / 60
                        val secs = remainingSeconds % 60
                        Text(
                            text = String.format(Locale.US, "%d:%02d", minutes.coerceAtLeast(0), secs.coerceAtLeast(0)),
                            color = colors.text,
                            fontSize = 52.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = stringResource(R.string.rest_timer_remaining_label),
                            color = colors.muted,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = onReduce,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colors.primary),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = colors.raised,
                            contentColor = colors.primary
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.rest_reduce),
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                    }
                    OutlinedButton(
                        onClick = onExtend,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 12.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, colors.primary),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = colors.raised,
                            contentColor = colors.primary
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.rest_extend),
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                    }

                }
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.primary,
                        contentColor = colors.onPrimary
                    )
                ) {
                    Text(
                        text = stringResource(R.string.action_skip),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
internal fun ActiveSessionEmptyState(
    onAddExercises: () -> Unit,
    isLoadingAdd: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val colors = IMFITColors.training
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("active_workout_empty_state"),
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
                text = stringResource(R.string.active_session_empty_title),
                color = colors.text,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.active_session_empty_description),
                color = colors.muted,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(4.dp))
            RoutineButton(
                text = stringResource(R.string.active_session_add_exercises),
                icon = Symbols.Filled.Add,
                onClick = onAddExercises,
                enabled = enabled,
                loading = isLoadingAdd
            )
        }
    }
}

@Composable
internal fun ActiveSessionLoadingState(
    modifier: Modifier = Modifier
) {
    val colors = IMFITColors.training
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 24.dp)
            .testTag("active_workout_loading")
            .semantics { liveRegion = LiveRegionMode.Polite },
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
            text = stringResource(R.string.active_session_loading),
            color = colors.muted,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
