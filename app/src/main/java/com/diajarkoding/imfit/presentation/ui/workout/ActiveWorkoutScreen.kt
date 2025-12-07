package com.diajarkoding.imfit.presentation.ui.workout

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.WorkoutSet
import com.diajarkoding.imfit.presentation.components.common.IMFITButton
import com.diajarkoding.imfit.presentation.components.common.IMFITSecondaryButton
import com.diajarkoding.imfit.theme.IMFITShapes
import com.diajarkoding.imfit.theme.RestTimer
import com.diajarkoding.imfit.theme.SetComplete
import com.diajarkoding.imfit.theme.WorkoutTypography

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

    // Rest Timer Dialog
    if (state.isRestTimerActive) {
        RestTimerDialog(
            remainingSeconds = state.restTimerSeconds,
            onDismiss = { viewModel.skipRestTimer() }
        )
    }

    // Cancel Confirmation Dialog
    if (state.showCancelDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCancelDialog() },
            title = { Text("Cancel Workout?") },
            text = { Text("Are you sure you want to cancel this workout? All progress will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.cancelWorkout()
                    onNavigateBack()
                }) {
                    Text("Cancel Workout", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissCancelDialog() }) {
                    Text("Continue")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = state.session?.templateName ?: "Workout",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "${state.elapsedMinutes}m elapsed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { viewModel.showCancelDialog() }) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                // Progress indicator
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Sets: ${state.session?.totalCompletedSets ?: 0}/${state.session?.totalSets ?: 0}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Volume: ${String.format("%.0f", state.session?.totalVolume ?: 0f)} kg",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                IMFITButton(
                    text = "Finish Workout",
                    onClick = { viewModel.finishWorkout() },
                    enabled = (state.session?.totalCompletedSets ?: 0) > 0
                )
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
                Text("Loading workout...")
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
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
                            }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(100.dp))
                }
            }
        }
    }
}

@Composable
private fun ExerciseSection(
    exerciseLog: ExerciseLog,
    exerciseIndex: Int,
    onSetUpdate: (Int, Float, Int) -> Unit,
    onSetComplete: (Int) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Exercise Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.FitnessCenter,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = exerciseLog.exercise.name,
                        style = WorkoutTypography.exerciseName,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = exerciseLog.exercise.muscleCategory.displayName,
                        style = WorkoutTypography.muscleGroup,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Sets Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "SET",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(40.dp),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "WEIGHT (kg)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "REPS",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "DONE",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(50.dp),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Set Rows
            exerciseLog.sets.forEachIndexed { setIndex, set ->
                SetInputRow(
                    set = set,
                    setIndex = setIndex,
                    onUpdate = { weight, reps -> onSetUpdate(setIndex, weight, reps) },
                    onComplete = { onSetComplete(setIndex) }
                )
                if (setIndex < exerciseLog.sets.size - 1) {
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun SetInputRow(
    set: WorkoutSet,
    setIndex: Int,
    onUpdate: (Float, Int) -> Unit,
    onComplete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (set.isCompleted)
                    SetComplete.copy(alpha = 0.1f)
                else
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                shape = IMFITShapes.Chip
            )
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Set Number
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (set.isCompleted) SetComplete else MaterialTheme.colorScheme.primary
                ),
            contentAlignment = Alignment.Center
        ) {
            if (set.isCompleted) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp)
                )
            } else {
                Text(
                    text = "${setIndex + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }
        }

        // Weight Input
        OutlinedTextField(
            value = if (set.weight > 0) set.weight.toInt().toString() else "",
            onValueChange = { value ->
                val weight = value.toFloatOrNull() ?: 0f
                onUpdate(weight, set.reps)
            },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            enabled = !set.isCompleted,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center)
        )

        // Reps Input
        OutlinedTextField(
            value = if (set.reps > 0) set.reps.toString() else "",
            onValueChange = { value ->
                val reps = value.toIntOrNull() ?: 0
                onUpdate(set.weight, reps)
            },
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            enabled = !set.isCompleted,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(textAlign = TextAlign.Center)
        )

        // Complete Checkbox
        Checkbox(
            checked = set.isCompleted,
            onCheckedChange = { if (!set.isCompleted && set.weight > 0 && set.reps > 0) onComplete() },
            enabled = !set.isCompleted && set.weight > 0 && set.reps > 0,
            colors = CheckboxDefaults.colors(
                checkedColor = SetComplete,
                checkmarkColor = MaterialTheme.colorScheme.onPrimary
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
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Timer,
                    contentDescription = null,
                    tint = RestTimer,
                    modifier = Modifier.size(48.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Rest Timer",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = String.format("%d:%02d", remainingSeconds / 60, remainingSeconds % 60),
                    style = WorkoutTypography.timerDisplay,
                    color = RestTimer
                )

                Spacer(modifier = Modifier.height(24.dp))

                IMFITSecondaryButton(
                    text = "Skip",
                    onClick = onDismiss,
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
    }
}
