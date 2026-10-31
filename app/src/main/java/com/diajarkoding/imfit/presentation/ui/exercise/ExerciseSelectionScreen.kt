package com.diajarkoding.imfit.presentation.ui.exercise

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.ExercisePrescriptionCapability
import com.diajarkoding.imfit.domain.model.prescriptionCapability
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.theme.IMFITColors
import com.diajarkoding.imfit.theme.IMFITOpacity
import com.diajarkoding.imfit.theme.IMFITSpacing
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import com.diajarkoding.imfit.theme.Scrim

@Composable
fun ExerciseSelectionScreen(
    templateId: String,
    onNavigateBack: () -> Unit,
    onExercisesSelected: (List<Exercise>) -> Unit,
    viewModel: ExerciseSelectionViewModel = hiltViewModel(),
    excludedExerciseIds: Set<String>? = null,
    routineName: String? = null,
    selectionContextId: String = templateId
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val focusManager = LocalFocusManager.current
    var filterSheet by rememberSaveable { mutableStateOf(false) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(templateId, excludedExerciseIds, routineName, selectionContextId) {
        viewModel.initialize(templateId, excludedExerciseIds, routineName, selectionContextId)
    }
    if (filterSheet) MuscleFilterSheet(state.selectedCategory, onDismiss = { filterSheet = false },
        onApply = { viewModel.selectCategory(it); filterSheet = false })

    ExercisePickerContent(
        state = state,
        onBack = onNavigateBack,
        onSearch = viewModel::onSearchQueryChange,
        onCategory = viewModel::selectCategory,
        onToggle = viewModel::toggleExercise,
        onReset = viewModel::resetSelection,
        onClearFilters = viewModel::clearFilters,
        onRetry = viewModel::retry,
        onRefresh = viewModel::pullToRefresh,
        onLoadNext = viewModel::loadNextPage,
        onFilter = { filterSheet = true },
        onConfirm = {
            if (!confirmed && state.selectedExercises.isNotEmpty()) {
                focusManager.clearFocus()
                confirmed = true
                onExercisesSelected(state.selectedExercises)
            }
        },
        enabled = !confirmed
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExercisePickerContent(
    state: ExerciseSelectionState,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onCategory: (ExerciseCategory) -> Unit,
    onToggle: (Exercise) -> Unit,
    onReset: () -> Unit,
    onClearFilters: () -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onLoadNext: () -> Unit,
    onFilter: () -> Unit,
    onConfirm: () -> Unit,
    enabled: Boolean = true
) {
    val c = IMFITColors.training
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val searchColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = c.primary,
        unfocusedBorderColor = onSurfaceVariant,
        disabledBorderColor = onSurfaceVariant,
        errorBorderColor = c.error,
        focusedContainerColor = c.surface,
        unfocusedContainerColor = c.surface,
        disabledContainerColor = c.surface,
        errorContainerColor = c.surface,
        focusedTextColor = c.text,
        unfocusedTextColor = c.text,
        disabledTextColor = c.text,
        cursorColor = c.primary,
        focusedLeadingIconColor = c.muted,
        unfocusedLeadingIconColor = c.muted,
        disabledLeadingIconColor = c.muted,
        focusedTrailingIconColor = c.muted,
        unfocusedTrailingIconColor = c.muted,
        disabledTrailingIconColor = c.muted,
        focusedPlaceholderColor = c.muted,
        unfocusedPlaceholderColor = c.muted,
        disabledPlaceholderColor = c.muted
    )
    Scaffold(
        modifier = Modifier.imePadding(),
        containerColor = c.background,
        contentWindowInsets = WindowInsets(0),
        topBar = {
            RoutineAppBar(
                stringResource(R.string.routine_picker_title),
                onBack,
                subtitle = if (state.routineName.isBlank()) stringResource(R.string.routine_picker_context_generic)
                else stringResource(R.string.routine_picker_context, state.routineName)
            )
        },
        bottomBar = {
            RoutineFooter(topPadding = 4.dp, contentGap = 4.dp) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.routine_selected_count, state.selectedExercises.size),
                        Modifier.weight(1f),
                        color = c.primary,
                        style = MaterialTheme.typography.labelMedium
                    )
                    TextButton(onReset, enabled = enabled && state.selectedExercises.isNotEmpty()) {
                        Text(stringResource(R.string.routine_reset), color = c.primary)
                    }
                }
                RoutineButton(
                    if (state.selectedExercises.isEmpty()) stringResource(R.string.routine_add_selected)
                    else stringResource(R.string.routine_add_count, state.selectedExercises.size),
                    onConfirm,
                    enabled = enabled && !state.isLoading && state.selectedExercises.isNotEmpty()
                )
            }
        }
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                state.searchQuery,
                onSearch,
                enabled = enabled,
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = IMFITSpacing.headerContentGap)
                    .heightIn(min = 52.dp)
                    .testTag("exercise_picker_search"),
                shape = RoutineControlShape,
                placeholder = { Text(stringResource(R.string.routine_search_hint), color = c.muted) },
                leadingIcon = { Icon(Symbols.Default.Search, null, tint = c.muted, modifier = Modifier.size(20.dp)) },
                trailingIcon = if (state.searchQuery.isNotBlank()) {
                    @Composable {
                        IconButton({ onSearch("") }) {
                            Icon(Symbols.Default.Clear, stringResource(R.string.action_clear), tint = c.muted)
                        }
                    }
                } else null,
                colors = searchColors
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(ExerciseCategory.ALL, ExerciseCategory.CHEST, ExerciseCategory.BACK).forEach { category ->
                    MuscleFilterChip(
                        category,
                        state.selectedCategory == category,
                        { onCategory(category) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        enabled = enabled,
                        quickFilter = true
                    )
                }
                val otherFilterActive = state.selectedCategory !in listOf(ExerciseCategory.ALL, ExerciseCategory.CHEST, ExerciseCategory.BACK)
                OutlinedIconButton(
                    onFilter,
                    enabled = enabled,
                    modifier = Modifier.size(48.dp),
                    shape = RoutineControlShape,
                    border = BorderStroke(1.dp, if (otherFilterActive) c.primary else c.border),
                    colors = IconButtonDefaults.outlinedIconButtonColors(
                        containerColor = if (otherFilterActive) c.primaryContainer else c.surface,
                        contentColor = if (otherFilterActive) c.onPrimaryContainer else c.muted
                    )
                ) {
                    Icon(
                        androidx.compose.ui.res.painterResource(R.drawable.ic_routine_filter),
                        if (otherFilterActive) stringResource(R.string.routine_active_filter, stringResource(state.selectedCategory.labelRes))
                        else stringResource(R.string.routine_filter_title),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.routine_available_exercises), Modifier.weight(1f), color = c.text, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.routine_result_count, state.filteredExercises.size), color = c.muted, style = MaterialTheme.typography.bodySmall)
            }

            val listState = rememberLazyListState()
            val visibleExerciseIds = remember(state.filteredExercises) { state.filteredExercises.mapTo(hashSetOf()) { it.id } }
            val lastVisibleExerciseId by remember(visibleExerciseIds) {
                derivedStateOf {
                    listState.layoutInfo.visibleItemsInfo.lastOrNull { item ->
                        val id = item.key
                        id is String && id in visibleExerciseIds
                    }?.key as? String
                }
            }
            // A cursor-only change after bounded excluded pages is not fresh scroll demand.
            // The explicit continuation below lets the user advance without an automatic loop.
            LaunchedEffect(lastVisibleExerciseId, state.filteredExercises.lastOrNull()?.id) {
                if (lastVisibleExerciseId != null &&
                    state.filteredExercises.indexOfLast { it.id == lastVisibleExerciseId } >= state.filteredExercises.size - 2 &&
                    state.appendErrorRes == null
                ) onLoadNext()
            }

            PullToRefreshBox(
                isRefreshing = state.isPullRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 12.dp)
                ) {
                    if (state.isLoading) item { RoutineLoading(stringResource(R.string.routine_catalog_loading)) }
                    state.errorRes?.let { error -> item { RoutineFailure(stringResource(error), onRetry) } }
                    if (!state.isLoading && state.errorRes == null && !state.hasMore && state.filteredExercises.isEmpty()) item {
                        Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.routine_search_empty), color = c.text, style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(if (state.allExercises.isEmpty()) R.string.routine_catalog_empty else R.string.routine_search_empty_hint), color = c.muted)
                            if (state.allExercises.isNotEmpty()) TextButton(onClearFilters) { Text(stringResource(R.string.routine_clear_filters), color = c.primary) }
                            else TextButton(onRetry) { Text(stringResource(R.string.routine_retry), color = c.primary) }
                        }
                    }
                    items(state.filteredExercises, key = { it.id }) { exercise ->
                        ExercisePickerRow(exercise, state.selectedExercises.any { it.id == exercise.id },
                            enabled && !state.isLoading && exercise.isActive &&
                                exercise.prescriptionCapability == ExercisePrescriptionCapability.REPS_LOAD) { onToggle(exercise) }
                    }
                    if (state.isAppending) {
                        item(key = "append_progress") {
                            Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.primary)
                            }
                        }
                    }
                    state.appendErrorRes?.let { error ->
                        item(key = "append_error") { RoutineFailure(stringResource(error), onLoadNext) }
                    }
                    if (state.hasMore && !state.isLoading && !state.isPullRefreshing &&
                        !state.isAppending && state.appendErrorRes == null
                    ) {
                        item(key = "load_more") {
                            TextButton(onLoadNext, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.routine_load_more), color = c.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ExercisePickerRow(exercise: Exercise, selected: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    val c = IMFITColors.training
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = if (selected) c.primaryContainer else c.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.border)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
                .heightIn(min = 76.dp)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MuscleCategoryArtwork(categoryArtAsset(exercise.muscleCategory), Modifier.size(48.dp).background(c.raised, RoutineControlShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(exercise.name, color = c.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(exercise.muscleCategory.stringResourceId) + if (selected) " · " + stringResource(R.string.routine_selected) else "",
                    color = if (selected) c.onPrimaryContainer else c.muted,
                    style = MaterialTheme.typography.bodySmall
                )
                if (exercise.prescriptionCapability != ExercisePrescriptionCapability.REPS_LOAD) {
                    Text(stringResource(R.string.routine_duration_unsupported), color = c.muted,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
            Checkbox(
                selected,
                onCheckedChange = null,
                enabled = enabled,
                modifier = Modifier.size(48.dp),
                colors = CheckboxDefaults.colors(
                    checkedColor = c.primary,
                    checkmarkColor = c.onPrimary,
                    uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

@Composable
internal fun MuscleFilterChip(
    category: ExerciseCategory,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    quickFilter: Boolean = false
) {
    val c = IMFITColors.training
    Surface(
        modifier,
        shape = RoutineControlShape,
        color = if (selected) c.primaryContainer else c.surface,
        border = BorderStroke(1.dp, if (selected && quickFilter) c.primaryContainer else if (selected) c.primary else c.border)
    ) {
        Row(
            Modifier
                .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
                .heightIn(min = 48.dp)
                .padding(horizontal = if (quickFilter) 8.dp else 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
        ) {
            if (selected) Icon(Symbols.Default.Check, null, tint = c.onPrimaryContainer, modifier = Modifier.size(14.dp))
            Text(
                stringResource(if (category == ExerciseCategory.ALL) R.string.exercise_filter_all else category.labelRes),
                modifier = Modifier.weight(1f, fill = false),
                textAlign = TextAlign.Center,
                color = if (selected) c.onPrimaryContainer else c.muted,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MuscleFilterSheet(current: ExerciseCategory, onDismiss: () -> Unit, onApply: (ExerciseCategory) -> Unit) {
    val c = IMFITColors.training
    val options = listOf(ExerciseCategory.ALL) + ExerciseCategory.entries.filterNot { it == ExerciseCategory.ALL }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        scrimColor = Scrim.copy(alpha = IMFITOpacity.scrim),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.routine_filter_title), color = c.text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.routine_filter_hint), color = c.muted, style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { category ->
                            MuscleFilterChip(
                                category = category,
                                selected = current == category,
                                onClick = {
                                    onApply(category)
                                    onDismiss()
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
