package com.diajarkoding.imfit.presentation.ui.workout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.*
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.theme.IMFITColors
import com.diajarkoding.imfit.theme.IMFITSpacing
import androidx.compose.ui.platform.testTag

@Composable
fun WorkoutDetailScreen(
    workoutId: String, onNavigateBack: () -> Unit,
    onNavigateToExerciseSelection: (String, Boolean) -> Unit, onStartWorkout: (String) -> Unit,
    onNavigateToEdit: (String) -> Unit = {}, viewModel: WorkoutDetailViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val startRequest = rememberWorkoutStartRequest(workoutId, state.isCurrentWorkoutActive) { onStartWorkout(workoutId) }
    var deleteDialog by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(workoutId) { viewModel.initialize(workoutId) }
    LaunchedEffect(state.hasPendingDelete, state.isLoading) {
        if (state.hasPendingDelete && !state.isLoading) deleteDialog = true
    }
    LaunchedEffect(state.isDeleted) {
        if (state.isDeleted) {
            deleteDialog = false
            onNavigateBack()
        }
    }
    androidx.activity.compose.BackHandler(enabled = state.isDeleting) {}
    if (deleteDialog) RoutineDialog(
        title = stringResource(R.string.dialog_delete_workout),
        message = stringResource(R.string.routine_delete_body, viewModel.deleteRoutineName),
        icon = Symbols.Default.Delete,
        confirmText = stringResource(if (state.isDeleting) R.string.routine_deleting else R.string.routine_delete_action),
        onConfirm = viewModel::deleteWorkout,
        onDismiss = { deleteDialog = false; viewModel.clearDeleteError() },
        processing = state.isDeleting, destructive = true,
        errorMessage = state.deleteErrorRes?.let { stringResource(it) }
    )
    WorkoutDetailContent(state, viewModel.estimatedDuration,
        onBack = onNavigateBack, onEdit = { onNavigateToEdit(workoutId) },
        onDelete = { viewModel.clearDeleteError(); deleteDialog = true },
        onAdd = { onNavigateToExerciseSelection(workoutId, state.workout?.exercises?.isEmpty() == true) }, onRetry = viewModel::loadWorkout,
        onStart = startRequest.start, startPending = startRequest.pending)
}

@Composable
internal fun WorkoutDetailContent(state: WorkoutDetailState, estimatedMinutes: Int,
    onBack: () -> Unit = {}, onEdit: () -> Unit = {}, onDelete: () -> Unit = {},
    onAdd: () -> Unit = {}, onStart: () -> Unit = {}, onRetry: () -> Unit = {}, startPending: Boolean = false) {
    val c = IMFITColors.training
    val workout = state.workout
    val exercises = workout?.exercises.orEmpty()
    val editable = workout != null && !state.isWorkoutActive && !state.isDeleting
    Scaffold(containerColor = c.background, contentWindowInsets = WindowInsets(0),
        topBar = {
            RoutineAppBar(stringResource(R.string.routine_detail_title), onBack, enabled = !state.isDeleting) {
                IconButton(onEdit, enabled = editable) { Icon(Symbols.Default.Edit, stringResource(R.string.action_edit), tint = if (editable) c.primary else c.muted) }
                IconButton(onDelete, enabled = editable) { Icon(Symbols.Default.Delete, stringResource(R.string.action_delete), tint = if (editable) c.error else c.muted) }
            }
        }, bottomBar = {
            RoutineFooter {
                if (workout != null && exercises.isEmpty()) Text(stringResource(R.string.routine_start_requirement), color = c.muted, style = MaterialTheme.typography.bodySmall)
                if (state.isWorkoutActive) Text(stringResource(R.string.routine_mutation_active), color = c.muted, style = MaterialTheme.typography.bodySmall)
                RoutineButton(stringResource(if (state.isCurrentWorkoutActive) R.string.routine_resume else R.string.action_start_workout), onStart,
                    enabled = !startPending && workout != null && exercises.isNotEmpty() && !state.isLoading && !state.isDeleting && (!state.isWorkoutActive || state.isCurrentWorkoutActive))
            }
        }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag("routine_detail_list"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = IMFITSpacing.headerContentGap, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.isLoading && workout == null) item { RoutineLoading(stringResource(R.string.routine_loading), topPadding = 0.dp) }
            state.errorRes?.let { error -> item { RoutineFailure(stringResource(error), onRetry) } }
            if (state.hasPendingDelete) item {
                RoutineButton(stringResource(R.string.routine_delete_action), onDelete, enabled = !state.isDeleting, outlined = true)
            }
            if (workout != null) {
                item {
                    val categories = exercises.map { it.muscleCategory }.distinct().map { stringResource(it.stringResourceId) }
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (exercises.isEmpty()) {
                            Surface(
                                color = c.raised,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.padding(bottom = 2.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.routine_empty_badge),
                                    color = c.primary,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        } else {
                            Text(stringResource(R.string.routine_eyebrow), color = c.primary, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(workout.name, color = c.text, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(if (exercises.isEmpty()) stringResource(R.string.routine_empty_description) else categories.joinToString(" · "), color = c.muted, style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { RoutineSummary(exercises.size, exercises.sumOf { it.sets }, estimatedMinutes) }
                item { RoutineExerciseHeading(exercises.size) }
                if (exercises.isEmpty()) item { RoutineEmptyExercises(onAdd, editable) }
                else {
                    items(exercises, key = { it.id }) { RoutineExerciseRow(it) }
                    item { RoutineButton(stringResource(R.string.action_add_exercise), onAdd, enabled = editable, outlined = true, icon = Symbols.Default.Add, matchOutlineToContent = true) }
                }
            }
        }
    }
}

@Composable
internal fun RoutineSummary(exerciseCount: Int, totalSets: Int, minutes: Int) {
    val c = IMFITColors.training
    Surface(color = c.primaryContainer, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            listOf(exerciseCount.toString() to stringResource(R.string.workout_exercises_label),
                totalSets.toString() to stringResource(R.string.routine_total_sets),
                (if (exerciseCount == 0) "—" else "±$minutes") to stringResource(R.string.workout_est_minutes)).forEach { (value, label) ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(value, Modifier.fillMaxWidth(), color = c.onPrimaryContainer, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(label, Modifier.fillMaxWidth(), color = c.onPrimaryContainer, textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
internal fun RoutineExerciseHeading(count: Int) {
    val c = IMFITColors.training
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.workout_exercises_label), Modifier.weight(1f), color = c.text,
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.workout_exercises_total, count), color = c.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun RoutineExerciseRow(exercise: TemplateExercise) {
    val c = IMFITColors.training
    Surface(color = c.surface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, c.border)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MuscleCategoryArtwork(categoryArtAsset(exercise.muscleCategory), Modifier.size(48.dp, 56.dp).background(c.raised, RoutineControlShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(exercise.name, color = c.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.routine_prescription, stringResource(exercise.muscleCategory.stringResourceId), exercise.sets, exercise.reps, exercise.restSeconds),
                    color = c.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun RoutineEmptyExercises(onAdd: () -> Unit, enabled: Boolean = true) {
    val c = IMFITColors.training
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(64.dp).background(c.primaryContainer, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
            Icon(Symbols.Default.FitnessCenter, null, tint = c.onPrimaryContainer, modifier = Modifier.size(28.dp))
        }
        Text(stringResource(R.string.routine_empty_title), color = c.text, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.routine_empty_description), color = c.muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
        RoutineButton(stringResource(R.string.routine_complete_action), onAdd, enabled = enabled, icon = Symbols.Default.Add)
    }
}
