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
import com.diajarkoding.imfit.domain.model.RoutineSelectionResult
import com.diajarkoding.imfit.domain.model.RoutineFormMode
import com.diajarkoding.imfit.domain.model.SetTarget
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.model.TrackingType
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
    onSelectionAccepted: (String) -> Unit = {}
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
        state = state.copy(mode = mode),
        onBack = requestBack,
        onName = viewModel::updateWorkoutName,
        onRemove = viewModel::removeExercise,
        onMoveUp = viewModel::moveExerciseUp,
        onMoveDown = viewModel::moveExerciseDown,
        onRawConfig = viewModel::updateRawConfig,
        onAddSet = viewModel::addSetRow,
        onRemoveSet = viewModel::removeSetRow,
        onUpdateSet = viewModel::updateSetRow,
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
    onRawConfig: (String, RawExerciseConfig) -> Unit = { _, _ -> },
    onAddSet: (String) -> Unit = {},
    onRemoveSet: (String, String) -> Unit = { _, _ -> },
    onUpdateSet: (String, RawSetTargetDraft) -> Unit = { _, _ -> },
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
                    val fallbackMode = exercise.exercise.v3TrackingType()
                    val fallbackConfig = state.rawConfigs[exercise.id] ?: run {
                        val fallbackTargets = exercise.setTargets ?: List(exercise.sets.coerceIn(1, MAX_ROUTINE_SET_ROWS)) { idx ->
                            SetTarget(
                                rowId = "${exercise.id}-${idx + 1}",
                                setNumber = idx + 1,
                                weightKg = if (fallbackMode == TrackingType.WEIGHT_REPS) 0.0 else null,
                                reps = if (fallbackMode == TrackingType.WEIGHT_REPS) (exercise.reps ?: 10) else null,
                                durationSeconds = if (fallbackMode == TrackingType.DURATION) (exercise.targetDurationSeconds ?: 60) else null,
                                restSeconds = exercise.restSeconds
                            )
                        }
                        RawExerciseConfig(
                            trackingType = fallbackMode,
                            setRows = fallbackTargets.map { target ->
                                RawSetTargetDraft(
                                    rowId = target.rowId,
                                    weight = if (fallbackMode == TrackingType.WEIGHT_REPS) target.weightKg?.let(::formatWeightInput).orEmpty() else "",
                                    reps = if (fallbackMode == TrackingType.WEIGHT_REPS) target.reps?.toString().orEmpty() else "",
                                    duration = if (fallbackMode == TrackingType.DURATION) target.durationSeconds?.let(::formatDurationHhMmSs).orEmpty() else "",
                                    rest = target.restSeconds.toString()
                                )
                            }
                        )
                    }
                    RoutineExerciseEditor(
                        exercise = exercise,
                        enabled = editable,
                        onRemove = { onRemove(index) },
                        onMoveUp = { onMoveUp(index) },
                        onMoveDown = { onMoveDown(index) },
                        canMoveUp = index > 0,
                        canMoveDown = index < state.exercises.size - 1,
                        rawConfig = fallbackConfig,
                        onRawConfig = { onRawConfig(exercise.id, it) },
                        onAddSet = { onAddSet(exercise.id) },
                        onRemoveSet = { rowId -> onRemoveSet(exercise.id, rowId) },
                        onUpdateSet = { updated -> onUpdateSet(exercise.id, updated) }
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
    onRawConfig: (RawExerciseConfig) -> Unit = {},
    onAddSet: () -> Unit = {},
    onRemoveSet: (String) -> Unit = {},
    onUpdateSet: (RawSetTargetDraft) -> Unit = {}
) {
    val c = IMFITColors.training
    val configEnabled = enabled && exercise.exercise.isActive
    val valid = rawConfig.isValid
    val durationMode = rawConfig.trackingType == TrackingType.DURATION ||
        rawConfig.trackingType == TrackingType.DISTANCE_DURATION

    Surface(color = c.surface, shape = RoundedCornerShape(14.dp), border = BorderStroke(1.dp, c.border)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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

            if (rawConfig.setRows.isEmpty()) {
                RoutineFailure(stringResource(R.string.routine_empty_sets_error))
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    rawConfig.setRows.forEachIndexed { setIndex, setRow ->
                        key(setRow.rowId) {
                            val setNumber = setIndex + 1
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("routine_set_row_${exercise.id}_$setNumber"),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Column(
                                    modifier = Modifier.width(32.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        stringResource(R.string.routine_set_number_label),
                                        color = c.muted,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(48.dp)
                                            .background(c.raised, RoutineControlShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            setNumber.toString(),
                                            color = c.text,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                if (durationMode) {
                                    RoutineSetField(
                                        label = stringResource(R.string.routine_duration_seconds),
                                        value = setRow.duration,
                                        onChange = { onUpdateSet(setRow.copy(duration = it)) },
                                        enabled = configEnabled,
                                        invalid = !setRow.isDurationValid(rawConfig.trackingType),
                                        keyboardType = KeyboardType.Ascii,
                                        fieldTag = "routine_duration_${exercise.id}_$setNumber",
                                        modifier = Modifier.weight(1.4f)
                                    )
                                    RoutineSetField(
                                        label = stringResource(R.string.routine_set_rest_label),
                                        value = setRow.rest,
                                        onChange = { onUpdateSet(setRow.copy(rest = it)) },
                                        enabled = configEnabled,
                                        invalid = !setRow.isRestValid,
                                        keyboardType = KeyboardType.Number,
                                        fieldTag = "routine_rest_${exercise.id}_$setNumber",
                                        modifier = Modifier.weight(1f)
                                    )
                                } else {
                                    RoutineSetField(
                                        label = stringResource(R.string.routine_set_weight_label),
                                        value = setRow.weight,
                                        onChange = { onUpdateSet(setRow.copy(weight = it)) },
                                        enabled = configEnabled,
                                        invalid = !setRow.isWeightValid(rawConfig.trackingType),
                                        keyboardType = KeyboardType.Decimal,
                                        fieldTag = "routine_weight_${exercise.id}_$setNumber",
                                        modifier = Modifier.weight(1f)
                                    )
                                    RoutineSetField(
                                        label = stringResource(R.string.routine_set_reps_label),
                                        value = setRow.reps,
                                        onChange = { onUpdateSet(setRow.copy(reps = it)) },
                                        enabled = configEnabled,
                                        invalid = !setRow.isRepsValid(rawConfig.trackingType),
                                        keyboardType = KeyboardType.Number,
                                        fieldTag = "routine_reps_${exercise.id}_$setNumber",
                                        modifier = Modifier.weight(1f)
                                    )
                                    RoutineSetField(
                                        label = stringResource(R.string.routine_set_rest_label),
                                        value = setRow.rest,
                                        onChange = { onUpdateSet(setRow.copy(rest = it)) },
                                        enabled = configEnabled,
                                        invalid = !setRow.isRestValid,
                                        keyboardType = KeyboardType.Number,
                                        fieldTag = "routine_rest_${exercise.id}_$setNumber",
                                        modifier = Modifier.weight(1f)
                                    )
                                }

                                IconButton(
                                    onClick = { onRemoveSet(setRow.rowId) },
                                    enabled = configEnabled,
                                    modifier = Modifier
                                        .size(40.dp, 48.dp)
                                        .testTag("routine_delete_set_${exercise.id}_$setNumber")
                                ) {
                                    Icon(
                                        Symbols.Default.Delete,
                                        contentDescription = stringResource(R.string.routine_remove_set_named, exercise.name, setNumber),
                                        tint = if (configEnabled) c.muted else c.muted.copy(alpha = 0.5f),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                if (!valid) {
                    RoutineFailure(stringResource(R.string.routine_config_invalid))
                }
            }

            RoutineButton(
                text = stringResource(R.string.action_add_set),
                onClick = onAddSet,
                enabled = configEnabled && rawConfig.setRows.size < MAX_ROUTINE_SET_ROWS,
                outlined = true,
                icon = Symbols.Default.Add,
                matchOutlineToContent = true,
                modifier = Modifier.testTag("routine_add_set_${exercise.id}")
            )

            if (!exercise.exercise.isActive) RoutineFailure(stringResource(R.string.routine_exercise_unavailable))
            Text(
                stringResource(if (durationMode) R.string.routine_config_hint_duration else R.string.routine_config_hint),
                color = c.muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun RoutineSetField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    enabled: Boolean,
    invalid: Boolean,
    keyboardType: KeyboardType,
    fieldTag: String,
    modifier: Modifier = Modifier
) {
    val c = IMFITColors.training
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val errorText = stringResource(R.string.routine_config_invalid)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            label,
            color = if (invalid) c.error else c.muted,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
        Surface(
            color = c.background,
            shape = RoutineControlShape,
            border = BorderStroke(
                if (focused) 2.dp else 1.dp,
                if (invalid) c.error else if (focused) c.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                enabled = enabled,
                singleLine = true,
                interactionSource = interaction,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                cursorBrush = SolidColor(c.primary),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.text, textAlign = TextAlign.Center),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .testTag(fieldTag)
                    .semantics {
                        contentDescription = label
                        if (invalid) error(errorText)
                    },
                decorationBox = { input ->
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 6.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) { input() }
                }
            )
        }
    }
}
