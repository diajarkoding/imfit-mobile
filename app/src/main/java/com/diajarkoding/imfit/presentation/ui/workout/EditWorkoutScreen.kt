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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.ExercisePrescriptionCapability
import com.diajarkoding.imfit.domain.model.RoutineSelectionResult
import com.diajarkoding.imfit.domain.model.prescriptionCapability
import com.diajarkoding.imfit.domain.model.RoutineFormMode
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.theme.IMFITColors
import com.diajarkoding.imfit.theme.IMFITSpacing
import androidx.compose.ui.platform.testTag

@Composable
fun EditWorkoutScreen(
    workoutId: String,
    mode: RoutineFormMode,
    flowId: String,
    ownerId: String,
    initialName: String = "",
    onNavigateBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: EditWorkoutViewModel = hiltViewModel(),
    onAddExercises: (String, List<String>, String) -> Unit,
    selectionResult: RoutineSelectionResult? = null,
    onSelectionAccepted: (String) -> Unit = {},
    openPickerOnLoad: Boolean = false
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showDiscard by rememberSaveable { mutableStateOf(false) }
    val requestBack = {
        if (!state.isSaving) {
            if (state.isDirty || state.hasPendingSave) showDiscard = true else onNavigateBack()
        }
    }
    BackHandler { requestBack() }
    LaunchedEffect(workoutId, mode, flowId, ownerId) {
        viewModel.initialize(workoutId, mode, flowId, ownerId, initialName)
    }
    LaunchedEffect(state.isSaved) {
        if (state.isSaved) {
            viewModel.consumeSaved()
            onSaved()
        }
    }
    LaunchedEffect(selectionResult, state.hasLoaded) {
        selectionResult?.let {
            if (state.hasLoaded && viewModel.acceptSelection(it)) onSelectionAccepted(it.resultId)
        }
    }
    var initialPickerOpened by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openPickerOnLoad, state.hasLoaded) {
        if (openPickerOnLoad && state.hasLoaded && !initialPickerOpened && !state.hasPendingSave && !state.accountInvalid) {
            initialPickerOpened = true
            onAddExercises(workoutId, state.exercises.map { it.id }, state.workoutName)
        }
    }

    if (showDiscard) {
        RoutineDialog(
            title = stringResource(R.string.routine_discard_title),
            message = stringResource(if (state.hasPendingSave) R.string.routine_discard_unknown else R.string.routine_discard_message),
            icon = Symbols.Default.Close,
            confirmText = stringResource(R.string.routine_discard_confirm),
            onConfirm = { showDiscard = false; onNavigateBack() },
            onDismiss = { showDiscard = false }
        )
    }

    if (state.showErrorDialog) {
        RoutineDialog(
            title = stringResource(R.string.error_routine_save),
            message = state.errorMessage ?: stringResource(R.string.routine_create_failed),
            icon = Symbols.Default.Close,
            confirmText = stringResource(if (state.hasPendingSave) R.string.routine_retry else R.string.action_ok),
            onConfirm = if (state.hasPendingSave) viewModel::saveChanges else viewModel::dismissErrorDialog,
            onDismiss = viewModel::dismissErrorDialog
        )
    }

    EditWorkoutContent(
        state = state,
        onBack = requestBack,
        onName = viewModel::updateWorkoutName,
        onRemove = viewModel::removeExercise,
        onMoveUp = viewModel::moveExerciseUp,
        onMoveDown = viewModel::moveExerciseDown,
        onRawConfig = viewModel::updateRawConfig,
        onSave = viewModel::saveChanges,
        onRetryLoad = viewModel::loadWorkout,
        onAdd = { onAddExercises(workoutId, state.exercises.map { it.id }, state.workoutName) }
    )
}

@Composable
internal fun EditWorkoutContent(
    state: EditWorkoutState,
    onBack: () -> Unit = {},
    onName: (String) -> Unit = {},
    onRemove: (Int) -> Unit = {},
    onMoveUp: (Int) -> Unit = {},
    onMoveDown: (Int) -> Unit = {},
    onRawConfig: (String, String, String, String) -> Unit = { _, _, _, _ -> },
    onSave: () -> Unit = {},
    onRetryLoad: () -> Unit = {},
    onAdd: () -> Unit = {}
) {
    val c = IMFITColors.training
    val focusManager = LocalFocusManager.current
    val editable = state.hasLoaded && !state.isLoading && !state.isSaving && !state.hasPendingSave && !state.accountInvalid
    val titleText = if (state.mode == RoutineFormMode.SETUP) {
        stringResource(R.string.routine_setup_title)
    } else {
        stringResource(R.string.workout_edit_title)
    }
    val ctaText = if (state.mode == RoutineFormMode.SETUP) {
        stringResource(if (state.isSaving) R.string.routine_saving else R.string.action_save_template)
    } else {
        stringResource(if (state.isSaving) R.string.routine_saving else R.string.action_save_changes)
    }

    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = c.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            RoutineAppBar(titleText, onBack, enabled = !state.isSaving)
        },
        bottomBar = {
            RoutineFooter {
                RoutineButton(
                    ctaText,
                    onClick = {
                        focusManager.clearFocus()
                        onSave()
                    },
                    enabled = (editable && state.workoutName.isNotBlank() && state.invalidConfigIds.isEmpty()) ||
                        (state.hasPendingSave && !state.isSaving && !state.isLoading && state.hasLoaded),
                    loading = state.isSaving
                )
            }
        }
    ) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .testTag("routine_edit_list"),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = IMFITSpacing.headerContentGap, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (state.isLoading) item(key = "routine_loading") { RoutineLoading(stringResource(R.string.routine_loading), topPadding = 0.dp) }
            state.errorRes?.let { message ->
                item(key = "routine_error") {
                    RoutineFailure(
                        listOfNotNull(stringResource(message), state.errorMessage).joinToString("\n"),
                        if (!state.hasLoaded) onRetryLoad else null
                    )
                }
            }
            if (state.hasPendingSave && !state.isSaving) item(key = "routine_save_unknown") {
                RoutineFailure(stringResource(R.string.routine_save_unknown), onSave)
            }
            if (state.hasLoaded) {
                item(key = "routine_name_field") {
                    RoutineNameField(
                        state.workoutName,
                        onName,
                        enabled = !state.isSaving && !state.accountInvalid,
                        readOnly = state.isSaving,
                        errorMessage = state.nameErrorRes?.let { stringResource(it) }
                    )
                }
                item(key = "routine_exercise_heading") { RoutineExerciseHeading(state.exercises.size) }
                itemsIndexed(state.exercises, key = { _, exercise -> exercise.id }) { index, exercise ->
                    RoutineExerciseEditor(
                        exercise = exercise,
                        enabled = editable,
                        onRemove = { onRemove(index) },
                        onMoveUp = { onMoveUp(index) },
                        onMoveDown = { onMoveDown(index) },
                        canMoveUp = index > 0,
                        canMoveDown = index < state.exercises.size - 1,
                        rawConfig = state.rawConfigs[exercise.id] ?: RawExerciseConfig(
                            exercise.sets.toString(), exercise.reps.toString(), exercise.restSeconds.toString()),
                        onRawConfig = { sets, reps, rest -> onRawConfig(exercise.id, sets, reps, rest) }
                    )
                }
                item(key = "routine_add_exercise_button") {
                    RoutineButton(
                        stringResource(R.string.action_add_exercise),
                        onAdd,
                        enabled = editable,
                        outlined = true,
                        icon = Symbols.Default.Add,
                        matchOutlineToContent = true
                    )
                }
                item(key = "routine_edit_history_hint") {
                    Text(
                        stringResource(R.string.routine_edit_history_hint),
                        color = c.muted,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 12.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun RoutineExerciseEditor(
    exercise: TemplateExercise,
    enabled: Boolean,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit = {},
    onMoveDown: () -> Unit = {},
    canMoveUp: Boolean = false,
    canMoveDown: Boolean = false,
    rawConfig: RawExerciseConfig,
    onRawConfig: (String, String, String) -> Unit
) {
    val c = IMFITColors.training
    val supported = exercise.exercise.prescriptionCapability == ExercisePrescriptionCapability.REPS_LOAD
    val configEnabled = enabled && exercise.exercise.isActive
    val sets = rawConfig.sets
    val reps = rawConfig.reps
    val rest = rawConfig.rest
    val valid = rawConfig.isValid

    Surface(color = c.surface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, c.border)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(exercise.name, color = c.text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(exercise.muscleCategory.stringResourceId), color = c.muted, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = onMoveUp, enabled = enabled && canMoveUp, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Symbols.Filled.KeyboardArrowUp,
                        stringResource(R.string.routine_action_move_up),
                        tint = if (enabled && canMoveUp) c.text else c.muted,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onMoveDown, enabled = enabled && canMoveDown, modifier = Modifier.size(48.dp)) {
                    Icon(
                        Symbols.Filled.KeyboardArrowDown,
                        stringResource(R.string.routine_action_move_down),
                        tint = if (enabled && canMoveDown) c.text else c.muted,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onRemove, enabled = enabled, modifier = Modifier.size(48.dp)) {
                    Icon(Symbols.Default.Close, stringResource(R.string.routine_remove_named, exercise.name), tint = c.muted, modifier = Modifier.size(20.dp))
                }
            }
            if (supported) {
                val fields: @Composable (Modifier) -> Unit = { modifier ->
                    RoutineConfigNumber(stringResource(R.string.workout_label_sets), sets, { onRawConfig(it, reps, rest) }, configEnabled, 1, modifier)
                    RoutineConfigNumber(stringResource(R.string.workout_label_reps), reps, { onRawConfig(sets, it, rest) }, configEnabled, 1, modifier)
                    RoutineConfigNumber(stringResource(R.string.workout_label_rest_short), rest, { onRawConfig(sets, reps, it) }, configEnabled, 0, modifier)
                }
                if (LocalDensity.current.fontScale > 1.4f) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { fields(Modifier.fillMaxWidth()) }
                else Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { fields(Modifier.weight(1f)) }
                if (!valid) RoutineFailure(stringResource(R.string.routine_config_invalid))
            }
            if (!exercise.exercise.isActive) RoutineFailure(stringResource(R.string.routine_exercise_unavailable))

            if (!supported) {
                Text(
                    stringResource(R.string.routine_duration_unsupported),
                    color = c.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(stringResource(R.string.routine_config_hint), color = c.muted, style = MaterialTheme.typography.bodySmall)
            }
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
            BasicTextField(value, onChange, enabled = enabled, singleLine = true,
                interactionSource = interaction,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), cursorBrush = SolidColor(c.primary),
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.text, textAlign = TextAlign.Center),
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).semantics { contentDescription = label; if (invalid) error(errorText) },
                decorationBox = { input -> Box(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(12.dp), contentAlignment = Alignment.Center) { input() } })
        }
    }
}
