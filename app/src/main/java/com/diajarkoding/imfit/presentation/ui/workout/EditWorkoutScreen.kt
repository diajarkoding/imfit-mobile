package com.diajarkoding.imfit.presentation.ui.workout

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.theme.IMFITColors

@Composable
fun EditWorkoutScreen(workoutId: String, onNavigateBack: () -> Unit,
    viewModel: EditWorkoutViewModel = hiltViewModel(),
    onAddExercises: (String, List<String>, String) -> Unit = { _, _, _ -> },
    selectedExerciseIds: List<String>? = null, onSelectedExercisesConsumed: () -> Unit = {}) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.isSaving) {}
    LaunchedEffect(workoutId) { viewModel.initialize(workoutId) }
    LaunchedEffect(state.isSaved) { if (state.isSaved) onNavigateBack() }
    LaunchedEffect(selectedExerciseIds, state.hasLoaded) {
        if (selectedExerciseIds != null && state.hasLoaded) {
            viewModel.addExercisesByIds(selectedExerciseIds)
            onSelectedExercisesConsumed()
        }
    }
    EditWorkoutContent(state, onNavigateBack, viewModel::updateWorkoutName, viewModel::removeExercise,
        viewModel::updateExerciseConfig, viewModel::setConfigValidity, viewModel::saveChanges, viewModel::loadWorkout,
        viewModel::retryAddExercises, onAdd = { onAddExercises(workoutId, state.exercises.map { it.id }, state.workoutName) })
}

@Composable
internal fun EditWorkoutContent(state: EditWorkoutState, onBack: () -> Unit = {}, onName: (String) -> Unit = {},
    onRemove: (Int) -> Unit = {}, onConfig: (String, Int, Int, Int) -> Unit = { _, _, _, _ -> },
    onValidity: (String, Boolean) -> Unit = { _, _ -> }, onSave: () -> Unit = {}, onRetryLoad: () -> Unit = {},
    onRetryAdd: () -> Unit = {}, onAdd: () -> Unit = {}) {
    val c = IMFITColors.training
    val editable = state.hasLoaded && !state.isLoading && !state.isSaving && !state.isAdding
    Scaffold(modifier = Modifier.imePadding(), containerColor = c.background, contentWindowInsets = WindowInsets(0),
        topBar = { RoutineAppBar(stringResource(R.string.workout_edit_title), onBack, enabled = !state.isSaving) },
        bottomBar = { RoutineFooter {
            RoutineButton(stringResource(if (state.isSaving) R.string.routine_saving else R.string.action_save_changes), onSave,
                enabled = editable && state.workoutName.isNotBlank() && state.invalidConfigIds.isEmpty(), loading = state.isSaving)
        } }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (state.isLoading) item { RoutineLoading(stringResource(R.string.routine_loading)) }
            state.errorRes?.let { message -> item {
                RoutineFailure(stringResource(message), if (!state.hasLoaded) onRetryLoad else if (message == R.string.routine_add_failed && !state.isAdding) onRetryAdd else null)
            } }
            if (state.hasLoaded) {
                item { RoutineNameField(state.workoutName, onName, enabled = editable,
                    errorMessage = state.nameErrorRes?.let { stringResource(it) }) }
                item { RoutineExerciseHeading(state.exercises.size) }
                itemsIndexed(state.exercises, key = { _, exercise -> exercise.id }) { index, exercise ->
                    RoutineExerciseEditor(exercise, editable,
                        onRemove = { onRemove(index) },
                        onConfig = { sets, reps, rest -> onConfig(exercise.id, sets, reps, rest) },
                        onValidity = { valid -> onValidity(exercise.id, valid) })
                }
                item { RoutineButton(stringResource(R.string.action_add_exercise), onAdd, enabled = editable, outlined = true, icon = Symbols.Default.Add) }
                item { Text(stringResource(R.string.routine_edit_history_hint), color = c.muted, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 12.dp)) }
                if (state.isAdding) item { RoutineLoading(stringResource(R.string.routine_adding)) }
            }
        }
    }
}

@Composable
internal fun RoutineExerciseEditor(exercise: TemplateExercise, enabled: Boolean,
    onRemove: () -> Unit, onConfig: (Int, Int, Int) -> Unit, onValidity: (Boolean) -> Unit) {
    val c = IMFITColors.training
    var sets by rememberSaveable(exercise.id) { mutableStateOf(exercise.sets.toString()) }
    var reps by rememberSaveable(exercise.id) { mutableStateOf(exercise.reps.toString()) }
    var rest by rememberSaveable(exercise.id) { mutableStateOf(exercise.restSeconds.toString()) }
    val valid = (sets.toIntOrNull() ?: 0) > 0 && (reps.toIntOrNull() ?: 0) > 0 && (rest.toIntOrNull() ?: -1) >= 0
    fun publish(newSets: String, newReps: String, newRest: String) {
        val setCount = newSets.toIntOrNull()
        val repCount = newReps.toIntOrNull()
        val restSeconds = newRest.toIntOrNull()
        val validConfig = setCount != null && setCount > 0 && repCount != null && repCount > 0 && restSeconds != null && restSeconds >= 0
        onValidity(validConfig)
        if (validConfig) onConfig(requireNotNull(setCount), requireNotNull(repCount), requireNotNull(restSeconds))
    }
    LaunchedEffect(exercise.id) { publish(sets, reps, rest) }
    Surface(color = c.surface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, c.border)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(exercise.name, color = c.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(exercise.muscleCategory.stringResourceId), color = c.muted, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onRemove, enabled = enabled, modifier = Modifier.size(48.dp)) {
                    Icon(Symbols.Default.Close, stringResource(R.string.routine_remove_named, exercise.name), tint = c.muted, modifier = Modifier.size(20.dp))
                }
            }
            val fields: @Composable (Modifier) -> Unit = { modifier ->
                RoutineConfigNumber(stringResource(R.string.workout_label_sets), sets, { sets = it; publish(it, reps, rest) }, enabled, 1, modifier)
                RoutineConfigNumber(stringResource(R.string.workout_label_reps), reps, { reps = it; publish(sets, it, rest) }, enabled, 1, modifier)
                RoutineConfigNumber(stringResource(R.string.workout_label_rest_short), rest, { rest = it; publish(sets, reps, it) }, enabled, 0, modifier)
            }
            if (LocalDensity.current.fontScale > 1.4f) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { fields(Modifier.fillMaxWidth()) }
            else Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { fields(Modifier.weight(1f)) }
            if (!valid) RoutineFailure(stringResource(R.string.routine_config_invalid))
            Text(stringResource(R.string.routine_config_hint), color = c.muted, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RoutineConfigNumber(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean, min: Int, modifier: Modifier) {
    val c = IMFITColors.training
    val invalid = value.toIntOrNull()?.let { it < min } ?: true
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val errorText = stringResource(R.string.routine_config_invalid)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = c.muted, style = MaterialTheme.typography.bodySmall)
        Surface(color = c.background, shape = RoutineControlShape,
            border = BorderStroke(if (focused) 2.dp else 1.dp, if (invalid) c.error else if (focused) c.primary else MaterialTheme.colorScheme.onSurfaceVariant)) {
            BasicTextField(value, { onChange(it.filter(Char::isDigit).take(6)) }, enabled = enabled, singleLine = true,
                interactionSource = interaction,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), cursorBrush = SolidColor(c.primary),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text, textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics { contentDescription = label; if (invalid) error(errorText) },
                decorationBox = { input -> Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(12.dp), contentAlignment = Alignment.Center) { input() } })
        }
    }
}
