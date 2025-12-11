package com.diajarkoding.imfit.presentation.ui.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.presentation.components.common.IMFITButton
import com.diajarkoding.imfit.presentation.components.common.IMFITDialog
import com.diajarkoding.imfit.presentation.components.common.IMFITDialogType
import com.diajarkoding.imfit.presentation.components.common.IMFITSecondaryButton
import com.diajarkoding.imfit.theme.DeletePink
import com.diajarkoding.imfit.theme.IMFITShapes
import com.diajarkoding.imfit.theme.IMFITSizes
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.Primary
import com.diajarkoding.imfit.theme.PrimaryLight
import com.diajarkoding.imfit.theme.SetComplete

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActiveWorkoutScreen(
    templateId: String,
    onNavigateBack: () -> Unit,
    onWorkoutFinished: (String) -> Unit,
    viewModel: ActiveWorkoutViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(templateId) {
        viewModel.startWorkout(templateId)
    }

    LaunchedEffect(state.workoutLogId) {
        state.workoutLogId?.let { logId ->
            onWorkoutFinished(logId)
        }
    }

    if (state.isRestTimerActive) {
        RestTimerDialog(
            remainingSeconds = state.restTimerSeconds,
            onDismiss = { viewModel.skipRestTimer() }
        )
    }

    if (state.showCancelDialog) {
        IMFITDialog(
            onDismissRequest = { viewModel.dismissCancelDialog() },
            title = stringResource(R.string.active_workout_cancel_title),
            message = stringResource(R.string.active_workout_cancel_message),
            icon = Icons.Default.Close,
            type = IMFITDialogType.DESTRUCTIVE,
            confirmText = stringResource(R.string.action_cancel_workout),
            dismissText = stringResource(R.string.action_continue),
            onConfirm = {
                viewModel.cancelWorkout()
                onNavigateBack()
            },
            onDismiss = { viewModel.dismissCancelDialog() }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.session?.templateName ?: "Workout",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(
                                R.string.active_workout_elapsed,
                                state.elapsedMinutes
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = SetComplete
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.showCancelDialog() }) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_cancel)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(8.dp, spotColor = Color.Black.copy(alpha = 0.1f))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(IMFITSpacing.screenHorizontal)
                    .padding(vertical = IMFITSpacing.lg)
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = IMFITSpacing.md),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        ProgressChip(
                            label = stringResource(R.string.workout_label_sets),
                            value = "${state.session?.totalCompletedSets ?: 0}/${state.session?.totalSets ?: 0}"
                        )
                        ProgressChip(
                            label = stringResource(R.string.label_volume),
                            value = "${String.format("%.0f", state.session?.totalVolume ?: 0f)} kg",
                            isPrimary = true
                        )
                    }
                    IMFITButton(
                        text = stringResource(R.string.action_finish_workout),
                        onClick = { viewModel.finishWorkout() },
                        enabled = (state.session?.totalCompletedSets ?: 0) > 0,
                        icon = Icons.Default.Check
                    )
                }
            }
        }
    ) { padding ->
        if (state.session == null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    stringResource(R.string.loading_workout),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(IMFITSpacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(IMFITSpacing.lg)
            ) {
                state.session?.exerciseLogs?.forEachIndexed { exerciseIndex, exerciseLog ->
                    item(key = "exercise_$exerciseIndex") {
                        ExerciseSection(
                            exerciseLog = exerciseLog,
                            exerciseIndex = exerciseIndex,
                            onSetUpdate = { setIndex, weight, reps ->
                                viewModel.updateSet(exerciseIndex, setIndex, weight, reps)
                            },
                            onSetComplete = { setIndex ->
                                viewModel.completeSet(exerciseIndex, setIndex)
                            },
                            onAddSet = { viewModel.addSet(exerciseIndex) },
                            onRemoveSet = { setIndex ->
                                viewModel.removeSet(
                                    exerciseIndex,
                                    setIndex
                                )
                            }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(120.dp)) }
            }
        }
    }
}

@Composable
private fun ProgressChip(
    label: String,
    value: String,
    isPrimary: Boolean = false
) {
    Row(
        modifier = Modifier
            .clip(IMFITShapes.Chip)
            .background(
                if (isPrimary) Primary.copy(alpha = 0.1f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            )
            .padding(horizontal = IMFITSpacing.md, vertical = IMFITSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = if (isPrimary) Primary else MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ExerciseSection(
    exerciseLog: ExerciseLog,
    exerciseIndex: Int,
    onSetUpdate: (Int, Float, Int) -> Unit,
    onSetComplete: (Int) -> Unit,
    onAddSet: () -> Unit,
    onRemoveSet: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IMFITSpacing.cardPadding)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(IMFITShapes.IconContainer)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.FitnessCenter,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(IMFITSizes.iconSm)
                    )
                }
                Spacer(modifier = Modifier.width(IMFITSpacing.md))
                Column {
                    Text(
                        text = exerciseLog.exercise.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(id = exerciseLog.exercise.muscleCategory.stringResourceId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(IMFITSpacing.lg))

            // Headers
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = IMFITSpacing.xs),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    stringResource(R.string.label_set_header),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(36.dp),
                    textAlign = TextAlign.Center
                )
                Text(
                    stringResource(R.string.label_kg_header),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Text(
                    stringResource(R.string.label_reps_header),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.width(32.dp))
                Text(
                    stringResource(R.string.exercise_placeholder),
                    modifier = Modifier.width(44.dp)
                )
            }

            Spacer(modifier = Modifier.height(IMFITSpacing.sm))

            // Sets
            exerciseLog.sets.forEachIndexed { setIndex, set ->
                SetInputRow(
                    set = set,
                    setIndex = setIndex,
                    canDelete = exerciseLog.sets.size > 1,
                    onUpdate = { weight, reps -> onSetUpdate(setIndex, weight, reps) },
                    onComplete = { onSetComplete(setIndex) },
                    onDelete = { onRemoveSet(setIndex) }
                )
                if (setIndex < exerciseLog.sets.size - 1) {
                    Spacer(modifier = Modifier.height(IMFITSpacing.sm))
                }
            }

            // Add Set
            Spacer(modifier = Modifier.height(IMFITSpacing.md))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(IMFITShapes.Chip)
                    .background(Primary.copy(alpha = 0.08f))
                    .clickable { onAddSet() }
                    .padding(vertical = IMFITSpacing.md),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = stringResource(R.string.action_add_set),
                    tint = Primary,
                    modifier = Modifier.size(IMFITSizes.iconSm)
                )
                Spacer(modifier = Modifier.width(IMFITSpacing.sm))
                Text(
                    text = stringResource(R.string.action_add_set),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Primary
                )
            }
        }
    }
}

@Composable
private fun SetInputRow(
    set: WorkoutSet,
    setIndex: Int,
    canDelete: Boolean,
    onUpdate: (Float, Int) -> Unit,
    onComplete: () -> Unit,
    onDelete: () -> Unit
) {
    val rowBackground = if (set.isCompleted) SetComplete.copy(alpha = 0.08f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
    val inputBackground = MaterialTheme.colorScheme.background

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(IMFITShapes.Chip)
            .background(rowBackground)
            .padding(horizontal = IMFITSpacing.sm, vertical = IMFITSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Set Number
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(if (set.isCompleted) SetComplete else Primary),
            contentAlignment = Alignment.Center
        ) {
            if (set.isCompleted) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Text(
                    text = "${setIndex + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.width(IMFITSpacing.sm))

        // Weight Input
        Box(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .clip(IMFITShapes.Chip)
                .background(inputBackground)
                .padding(horizontal = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            OutlinedTextField(
                value = if (set.weight > 0) set.weight.toInt().toString() else "",
                onValueChange = { value ->
                    val weight = value.toFloatOrNull() ?: 0f
                    onUpdate(weight, set.reps)
                },
                modifier = Modifier.fillMaxSize(),
                enabled = !set.isCompleted,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold
                ),
                shape = IMFITShapes.Chip,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                    disabledTextColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }

        Spacer(modifier = Modifier.width(IMFITSpacing.sm))

        // Reps Input
        Box(
            modifier = Modifier
                .weight(1f)
                .height(48.dp)
                .clip(IMFITShapes.Chip)
                .background(inputBackground)
                .padding(horizontal = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            OutlinedTextField(
                value = if (set.reps > 0) set.reps.toString() else "",
                onValueChange = { value ->
                    val reps = value.toIntOrNull() ?: 0
                    onUpdate(set.weight, reps)
                },
                modifier = Modifier.fillMaxSize(),
                enabled = !set.isCompleted,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                textStyle = MaterialTheme.typography.titleMedium.copy(
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.SemiBold
                ),
                shape = IMFITShapes.Chip,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    disabledContainerColor = Color.Transparent,
                    disabledBorderColor = Color.Transparent,
                    disabledTextColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }

        Spacer(modifier = Modifier.width(IMFITSpacing.xs))

        // Delete
        IconButton(
            onClick = onDelete,
            enabled = canDelete && !set.isCompleted,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = stringResource(R.string.action_delete),
                tint = if (canDelete && !set.isCompleted) DeletePink.copy(alpha = 0.8f)
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                modifier = Modifier.size(20.dp)
            )
        }

        // Checkbox
        Checkbox(
            checked = set.isCompleted,
            onCheckedChange = { if (!set.isCompleted && set.weight > 0 && set.reps > 0) onComplete() },
            enabled = !set.isCompleted && set.weight > 0 && set.reps > 0,
            colors = CheckboxDefaults.colors(
                checkedColor = SetComplete,
                checkmarkColor = Color.White
            )
        )
    }
}

@Composable
private fun RestTimerDialog(
    remainingSeconds: Int,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = {}) {
        Card(
            shape = IMFITShapes.Dialog,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(IMFITSpacing.xxl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(Primary, PrimaryLight))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Timer,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(IMFITSizes.iconLg)
                    )
                }

                Spacer(modifier = Modifier.height(IMFITSpacing.lg))

                Text(
                    text = stringResource(R.string.active_workout_rest_timer),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(IMFITSpacing.xxl))

                Text(
                    text = String.format("%d:%02d", remainingSeconds / 60, remainingSeconds % 60),
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    color = Primary
                )

                Spacer(modifier = Modifier.height(IMFITSpacing.xxl))

                IMFITSecondaryButton(
                    text = stringResource(R.string.action_skip),
                    onClick = onDismiss
                )
            }
        }
    }
}
