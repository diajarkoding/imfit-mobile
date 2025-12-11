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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
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
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.presentation.components.common.IMFITButton
import com.diajarkoding.imfit.presentation.components.common.IMFITDialog
import com.diajarkoding.imfit.presentation.components.common.IMFITDialogType
import com.diajarkoding.imfit.presentation.components.common.IMFITInputDialog
import com.diajarkoding.imfit.presentation.components.common.IMFITOutlinedButton
import com.diajarkoding.imfit.theme.DeletePink
import com.diajarkoding.imfit.theme.IMFITShapes
import com.diajarkoding.imfit.theme.IMFITSizes
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.Primary
import com.diajarkoding.imfit.theme.PrimaryLight
import com.diajarkoding.imfit.theme.SetComplete
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.components.common.ShimmerExerciseCard
import com.diajarkoding.imfit.presentation.components.common.ShimmerStatCard
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutDetailScreen(
    workoutId: String,
    onNavigateBack: () -> Unit,
    onNavigateToExerciseSelection: (String) -> Unit,
    onStartWorkout: (String) -> Unit,
    onNavigateToEdit: (String) -> Unit = {},
    selectedExercises: List<Exercise>? = null,
    viewModel: WorkoutDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val activeWorkoutWarning = stringResource(R.string.warning_workout_active)

    LaunchedEffect(selectedExercises) {
        selectedExercises?.let { exercises ->
            if (exercises.isNotEmpty()) {
                viewModel.addExercises(exercises)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadWorkout()
    }

    LaunchedEffect(state.workoutFinished) {
        if (state.workoutFinished) {
            viewModel.clearWorkoutFinished()
            viewModel.loadWorkout()
        }
    }

    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) {
            onNavigateBack()
        }
    }

    if (showDeleteDialog) {
        IMFITDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = stringResource(R.string.dialog_delete_workout),
            message = stringResource(R.string.dialog_delete_workout_message, state.workout?.name ?: ""),
            confirmText = stringResource(R.string.action_delete),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                viewModel.deleteWorkout()
                showDeleteDialog = false
            },
            type = IMFITDialogType.DESTRUCTIVE
        )
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
                title = {
                    Text(
                        text = state.workout?.name ?: "Workout",
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
                actions = {
                    IconButton(onClick = {
                        if (state.isWorkoutActive) {
                            scope.launch { snackbarHostState.showSnackbar(activeWorkoutWarning) }
                        } else {
                            onNavigateToEdit(workoutId)
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = stringResource(R.string.action_edit),
                            tint = if (state.isWorkoutActive) Primary.copy(alpha = 0.4f) else Primary
                        )
                    }
                    IconButton(onClick = {
                        if (state.isWorkoutActive) {
                            scope.launch { snackbarHostState.showSnackbar(activeWorkoutWarning) }
                        } else {
                            showDeleteDialog = true
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.action_delete),
                            tint = if (state.isWorkoutActive) DeletePink.copy(alpha = 0.4f) else DeletePink
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
        Box(modifier = Modifier.fillMaxSize()) {
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
                    if (state.isLoading) {
                        item {
                            Spacer(modifier = Modifier.height(IMFITSpacing.sm))
                            ShimmerStatCard()
                        }
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                com.diajarkoding.imfit.presentation.components.common.ShimmerBox(
                                    width = 100.dp,
                                    height = 18.dp
                                )
                                com.diajarkoding.imfit.presentation.components.common.ShimmerBox(
                                    width = 80.dp,
                                    height = 14.dp
                                )
                            }
                        }
                        items(3) { ShimmerExerciseCard() }
                    } else {
                        item {
                            Spacer(modifier = Modifier.height(IMFITSpacing.sm))
                            WorkoutInfoCard(
                                exerciseCount = state.workout?.exerciseCount ?: 0,
                                estimatedMinutes = viewModel.estimatedDuration
                            )
                        }

                        item {
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
                                    text = stringResource(R.string.workout_exercises_total, state.workout?.exerciseCount ?: 0),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }

                        val exercises = state.workout?.exercises ?: emptyList()
                        if (exercises.isEmpty()) {
                            item { EmptyExercisesCard() }
                        } else {
                            items(exercises, key = { it.id }) { templateExercise ->
                                SwipeToDeleteExerciseItem(
                                    templateExercise = templateExercise,
                                    onRemove = { viewModel.removeExercise(templateExercise) },
                                    onUpdateConfig = { sets, reps, rest ->
                                        viewModel.updateExerciseConfig(templateExercise.id, sets, reps, rest)
                                    }
                                )
                            }
                        }

                        item {
                            IMFITOutlinedButton(
                                text = stringResource(R.string.action_add_exercise),
                                onClick = { onNavigateToExerciseSelection(workoutId) },
                                icon = Icons.Default.Add
                            )
                        }
                    }

                    item { Spacer(modifier = Modifier.height(100.dp)) }
                }

            // Bottom Action Button
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(8.dp, spotColor = Color.Black.copy(alpha = 0.1f))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(IMFITSpacing.screenHorizontal)
                    .padding(vertical = IMFITSpacing.lg)
            ) {
                if (state.isWorkoutActive) {
                    IMFITButton(
                        text = stringResource(R.string.action_end_workout),
                        onClick = { viewModel.endWorkout() },
                        icon = Icons.Default.Stop
                    )
                } else {
                    IMFITButton(
                        text = stringResource(R.string.action_start_workout),
                        onClick = { onStartWorkout(workoutId) },
                        enabled = (state.workout?.exerciseCount ?: 0) > 0,
                        icon = Icons.Default.PlayArrow
                    )
                }
            }
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 100.dp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDeleteExerciseItem(
    templateExercise: TemplateExercise,
    onRemove: () -> Unit,
    onUpdateConfig: (sets: Int, reps: Int, rest: Int) -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { false }
    )

    var showDeleteConfirmation by remember { mutableStateOf(false) }

    if (showDeleteConfirmation) {
        IMFITDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = stringResource(R.string.dialog_remove_exercise),
            message = stringResource(R.string.dialog_remove_exercise_message, templateExercise.name),
            icon = Icons.Default.Delete,
            type = IMFITDialogType.DESTRUCTIVE,
            confirmText = stringResource(R.string.action_remove),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                onRemove()
                showDeleteConfirmation = false
            }
        )
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val color by animateColorAsState(
                when (dismissState.targetValue) {
                    SwipeToDismissBoxValue.EndToStart -> DeletePink.copy(alpha = 0.15f)
                    else -> Color.Transparent
                },
                label = "background color"
            )
            val scale by animateFloatAsState(
                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) 1.1f else 0.8f,
                label = "icon scale"
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(IMFITShapes.Card)
                    .background(color)
                    .padding(horizontal = IMFITSpacing.xl),
                contentAlignment = Alignment.CenterEnd
            ) {
                IconButton(
                    onClick = { showDeleteConfirmation = true },
                    modifier = Modifier.scale(scale)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = DeletePink,
                        modifier = Modifier.size(IMFITSizes.iconLg)
                    )
                }
            }
        },
        enableDismissFromStartToEnd = false,
        enableDismissFromEndToStart = true
    ) {
        ExerciseItemCard(
            templateExercise = templateExercise,
            onUpdateConfig = onUpdateConfig
        )
    }
}

@Composable
private fun ExerciseItemCard(
    templateExercise: TemplateExercise,
    onUpdateConfig: (sets: Int, reps: Int, rest: Int) -> Unit
) {
    var showEditDialog by remember { mutableStateOf(false) }
    var editSets by remember { mutableStateOf(templateExercise.sets.toString()) }
    var editReps by remember { mutableStateOf(templateExercise.reps.toString()) }
    var editRest by remember { mutableStateOf(templateExercise.restSeconds.toString()) }

    if (showEditDialog) {
        IMFITInputDialog(
            onDismissRequest = { showEditDialog = false },
            title = templateExercise.name,
            icon = Icons.Default.FitnessCenter,
            confirmText = stringResource(R.string.action_save),
            dismissText = stringResource(R.string.action_cancel),
            onConfirm = {
                val sets = editSets.toIntOrNull() ?: 3
                val reps = editReps.toIntOrNull() ?: 8
                val rest = editRest.toIntOrNull() ?: 60
                onUpdateConfig(sets.coerceAtLeast(1), reps.coerceAtLeast(1), rest.coerceAtLeast(0))
                showEditDialog = false
            }
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(IMFITSpacing.lg)) {
                EditField(label = stringResource(R.string.workout_label_sets), value = editSets, onValueChange = { editSets = it.filter { c -> c.isDigit() } })
                EditField(label = stringResource(R.string.workout_label_reps), value = editReps, onValueChange = { editReps = it.filter { c -> c.isDigit() } })
                EditField(label = stringResource(R.string.workout_label_rest), value = editRest, onValueChange = { editRest = it.filter { c -> c.isDigit() } })
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = { 
            editSets = templateExercise.sets.toString()
            editReps = templateExercise.reps.toString()
            editRest = templateExercise.restSeconds.toString()
            showEditDialog = true 
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IMFITSpacing.cardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(IMFITShapes.IconContainer)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.FitnessCenter,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(IMFITSizes.iconMd)
                )
            }
            Spacer(modifier = Modifier.width(IMFITSpacing.lg))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = templateExercise.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(IMFITSpacing.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(id = templateExercise.muscleCategory.stringResourceId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = " • ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${templateExercise.sets} x ${templateExercise.reps} reps",
                        style = MaterialTheme.typography.bodySmall,
                        color = Primary,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun EditField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = IMFITSpacing.xs)
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            shape = IMFITShapes.TextField,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            ),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun WorkoutInfoCard(
    exerciseCount: Int,
    estimatedMinutes: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, IMFITShapes.Card, spotColor = Primary.copy(alpha = 0.15f)),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(Primary.copy(alpha = 0.1f), PrimaryLight.copy(alpha = 0.05f))
                    )
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(IMFITSpacing.cardPaddingLarge),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                InfoItem(
                    icon = Icons.Default.FitnessCenter,
                    value = "$exerciseCount",
                    label = stringResource(R.string.workout_exercises_label)
                )
                InfoItem(
                    icon = Icons.Default.Schedule,
                    value = if (estimatedMinutes > 0) "~$estimatedMinutes" else "-",
                    label = stringResource(R.string.workout_est_minutes)
                )
            }
        }
    }
}

@Composable
private fun InfoItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    value: String,
    label: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Primary,
                modifier = Modifier.size(IMFITSizes.iconSm)
            )
            Spacer(modifier = Modifier.width(IMFITSpacing.sm))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(modifier = Modifier.height(IMFITSpacing.xs))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyExercisesCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IMFITSpacing.xxxl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(IMFITShapes.IconContainer)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.FitnessCenter,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(IMFITSizes.iconLg)
                )
            }
            Spacer(modifier = Modifier.height(IMFITSpacing.lg))
            Text(
                text = stringResource(R.string.workout_no_exercises),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(IMFITSpacing.xs))
            Text(
                text = stringResource(R.string.workout_add_exercises_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}
