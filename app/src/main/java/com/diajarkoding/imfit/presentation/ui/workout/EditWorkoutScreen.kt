package com.diajarkoding.imfit.presentation.ui.workout

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.presentation.components.common.IMFITButton
import com.diajarkoding.imfit.presentation.components.common.IMFITDialog
import com.diajarkoding.imfit.presentation.components.common.IMFITDialogType
import com.diajarkoding.imfit.theme.DeletePink
import com.diajarkoding.imfit.theme.IMFITShapes
import com.diajarkoding.imfit.theme.IMFITSizes
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.Primary
import com.diajarkoding.imfit.theme.PrimaryLight
import com.diajarkoding.imfit.theme.SetComplete
import com.diajarkoding.imfit.R
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditWorkoutScreen(
    workoutId: String,
    onNavigateBack: () -> Unit,
    viewModel: EditWorkoutViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadWorkout()
    }

    LaunchedEffect(state.isSaved) {
        if (state.isSaved) {
            onNavigateBack()
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
                title = {
                    Text(
                        text = stringResource(R.string.workout_edit_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                windowInsets = WindowInsets(0)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(IMFITSpacing.screenHorizontal),
                verticalArrangement = Arrangement.spacedBy(IMFITSpacing.lg)
            ) {
                item {
                    Spacer(modifier = Modifier.height(IMFITSpacing.sm))
                    Text(
                        text = stringResource(R.string.workout_name_label),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(IMFITSpacing.sm))
                    OutlinedTextField(
                        value = state.workoutName,
                        onValueChange = { viewModel.updateWorkoutName(it) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.home_workout_placeholder)) },
                        singleLine = true,
                        shape = IMFITShapes.TextField,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                        )
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(IMFITSpacing.md))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.workout_exercises_label),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.workout_exercises_total, state.exercises.size),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (state.exercises.isEmpty()) {
                    item {
                        EmptyExercisesPlaceholder()
                    }
                } else {
                    itemsIndexed(
                        items = state.exercises,
                        key = { _, exercise -> exercise.id }
                    ) { index, exercise ->
                        EditableExerciseCard(
                            exercise = exercise,
                            onRemoveExercise = { viewModel.removeExercise(index) },
                            onUpdateSets = { sets -> viewModel.updateExerciseSets(index, sets) },
                            onUpdateReps = { reps -> viewModel.updateExerciseReps(index, reps) },
                            onUpdateRest = { rest -> viewModel.updateExerciseRest(index, rest) },
                            onRemoveSet = { setIndex -> viewModel.removeSet(index, setIndex) },
                            onAddSet = { viewModel.addSet(index) }
                        )
                    }
                }

                item { Spacer(modifier = Modifier.height(100.dp)) }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(8.dp, spotColor = Color.Black.copy(alpha = 0.1f))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(IMFITSpacing.screenHorizontal)
                    .padding(vertical = IMFITSpacing.lg)
            ) {
                IMFITButton(
                    text = stringResource(R.string.action_save_changes),
                    onClick = { viewModel.saveChanges() },
                    enabled = state.workoutName.isNotBlank(),
                    icon = Icons.Default.Check
                )
            }
        }
    }
}

@Composable
private fun EmptyExercisesPlaceholder() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IMFITSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.FitnessCenter,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Spacer(modifier = Modifier.height(IMFITSpacing.md))
            Text(
                text = stringResource(R.string.workout_no_exercises),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditableExerciseCard(
    exercise: TemplateExercise,
    onRemoveExercise: () -> Unit,
    onUpdateSets: (Int) -> Unit,
    onUpdateReps: (Int) -> Unit,
    onUpdateRest: (Int) -> Unit,
    onRemoveSet: (Int) -> Unit,
    onAddSet: () -> Unit
) {
    var showDeleteConfirmation by remember { mutableStateOf(false) }

    if (showDeleteConfirmation) {
        IMFITDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = stringResource(R.string.dialog_remove_exercise),
            message = stringResource(R.string.dialog_remove_exercise_message, exercise.name),
            confirmText = stringResource(R.string.action_remove),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                onRemoveExercise()
                showDeleteConfirmation = false
            },
            type = IMFITDialogType.DESTRUCTIVE
        )
    }

    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { false }
    )

    val scale by animateFloatAsState(
        targetValue = if (dismissState.targetValue == SwipeToDismissBoxValue.Settled) 1f else 0.95f,
        animationSpec = tween(150),
        label = "scale"
    )

    LaunchedEffect(dismissState.currentValue) {
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) {
            showDeleteConfirmation = true
            dismissState.reset()
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val color by animateColorAsState(
                targetValue = when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.EndToStart -> DeletePink
                    else -> Color.Transparent
                },
                label = "bgColor"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(IMFITShapes.Card)
                    .background(color)
                    .padding(horizontal = IMFITSpacing.lg),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = Color.White,
                    modifier = Modifier.size(IMFITSizes.iconMd)
                )
            }
        }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .scale(scale),
            shape = IMFITShapes.Card,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier.padding(IMFITSpacing.cardPadding)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(IMFITShapes.IconContainer)
                                .background(
                                    Brush.linearGradient(listOf(Primary, PrimaryLight))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FitnessCenter,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(IMFITSizes.iconSm)
                            )
                        }
                        Spacer(modifier = Modifier.width(IMFITSpacing.md))
                        Column {
                            Text(
                                text = exercise.name,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(id = exercise.exercise.muscleCategory.stringResourceId),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    IconButton(onClick = { showDeleteConfirmation = true }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.action_remove),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(IMFITSpacing.md))

                // Sets, Reps, Rest configuration
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)
                ) {
                    ConfigInput(
                        label = stringResource(R.string.workout_label_sets),
                        value = exercise.sets.toString(),
                        onValueChange = { value ->
                            value.toIntOrNull()?.let { if (it > 0) onUpdateSets(it) }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    ConfigInput(
                        label = stringResource(R.string.workout_label_reps),
                        value = exercise.reps.toString(),
                        onValueChange = { value ->
                            value.toIntOrNull()?.let { if (it > 0) onUpdateReps(it) }
                        },
                        modifier = Modifier.weight(1f)
                    )
                    ConfigInput(
                        label = stringResource(R.string.workout_label_rest_short),
                        value = exercise.restSeconds.toString(),
                        onValueChange = { value ->
                            value.toIntOrNull()?.let { if (it >= 0) onUpdateRest(it) }
                        },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Set management
                Spacer(modifier = Modifier.height(IMFITSpacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.workout_sets_configured, exercise.sets),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row {
                        if (exercise.sets > 1) {
                            IconButton(
                                onClick = { onRemoveSet(exercise.sets - 1) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Remove set",
                                    tint = DeletePink,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                        IconButton(
                            onClick = onAddSet,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.action_add_set),
                                tint = SetComplete,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfigInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            shape = IMFITShapes.TextField,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            )
        )
    }
}
