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
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.theme.IMFITColors
import com.diajarkoding.imfit.theme.IMFITOpacity
import com.diajarkoding.imfit.theme.IMFITSpacing
import androidx.compose.ui.platform.testTag
import com.diajarkoding.imfit.theme.Scrim

@Composable
fun ExerciseSelectionScreen(templateId: String, onNavigateBack: () -> Unit, onExercisesSelected: (List<Exercise>) -> Unit,
    viewModel: ExerciseSelectionViewModel = hiltViewModel(), excludedExerciseIds: Set<String>? = null,
    routineName: String? = null) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var filterSheet by rememberSaveable { mutableStateOf(false) }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(templateId, excludedExerciseIds, routineName) { viewModel.initialize(templateId, excludedExerciseIds, routineName) }
    if (filterSheet) MuscleFilterSheet(state.selectedCategory, onDismiss = { filterSheet = false },
        onApply = { viewModel.selectCategory(it); filterSheet = false })
    ExercisePickerContent(state, onNavigateBack, viewModel::onSearchQueryChange, viewModel::selectCategory,
        viewModel::toggleExercise, viewModel::resetSelection, viewModel::clearFilters, viewModel::retry,
        onFilter = { filterSheet = true }, onConfirm = {
            if (!confirmed && state.selectedExercises.isNotEmpty()) { confirmed = true; onExercisesSelected(state.selectedExercises) }
        }, enabled = !confirmed,
        onRefresh = viewModel::refresh, onAccess = viewModel::access, onMore = viewModel::loadMore)
}

@Composable
internal fun ExercisePickerContent(state: ExerciseSelectionState, onBack: () -> Unit, onSearch: (String) -> Unit,
    onCategory: (ExerciseCategory) -> Unit, onToggle: (Exercise) -> Unit, onReset: () -> Unit,
    onClearFilters: () -> Unit, onRetry: () -> Unit, onFilter: () -> Unit, onConfirm: () -> Unit, enabled: Boolean = true,
    onRefresh: () -> Unit = onRetry, onAccess: (String) -> Unit = {}, onMore: () -> Unit = {}) {
    val c = IMFITColors.training
    val list = rememberLazyListState()
    RemotePagingViewport(list, state.filteredExercises.associate { it.id to it.id }, onAccess,
        enabled = !state.refreshing && !state.stale)
    Scaffold(modifier = Modifier.imePadding(), containerColor = c.background, contentWindowInsets = WindowInsets(0),
        topBar = { RoutineAppBar(stringResource(R.string.routine_picker_title), onBack,
            subtitle = if (state.routineName.isBlank()) stringResource(R.string.routine_picker_context_generic)
                else stringResource(R.string.routine_picker_context, state.routineName)) },
        bottomBar = { RoutineFooter {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.routine_selected_count, state.selectedExercises.size), Modifier.weight(1f),
                    color = c.primary, style = MaterialTheme.typography.labelMedium)
                TextButton(onReset, enabled = enabled && state.selectedExercises.isNotEmpty()) { Text(stringResource(R.string.routine_reset), color = c.primary) }
            }
            RoutineButton(if (state.selectedExercises.isEmpty()) stringResource(R.string.routine_add_selected)
                else stringResource(R.string.routine_add_count, state.selectedExercises.size), onConfirm,
                enabled = enabled && !state.isLoading && !state.stale && state.errorRes == null && state.selectedExercises.isNotEmpty())
        } }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(state.searchQuery, onSearch, enabled = enabled, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(top = IMFITSpacing.headerContentGap).heightIn(min = 52.dp)
                    .testTag("exercise_picker_search"), shape = RoutineControlShape,
                placeholder = { Text(stringResource(R.string.routine_search_hint), color = c.muted) },
                leadingIcon = { Icon(Symbols.Default.Search, null, tint = c.muted, modifier = Modifier.size(20.dp)) },
                trailingIcon = if (state.searchQuery.isNotBlank()) {{ IconButton({ onSearch("") }) { Icon(Symbols.Default.Clear, stringResource(R.string.action_clear), tint = c.muted) } }} else null,
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = c.primary, unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    focusedContainerColor = c.surface, unfocusedContainerColor = c.surface, focusedTextColor = c.text, unfocusedTextColor = c.text, cursorColor = c.primary))
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(ExerciseCategory.ALL, ExerciseCategory.CHEST, ExerciseCategory.BACK).forEach { category ->
                    MuscleFilterChip(category, state.selectedCategory == category, { onCategory(category) },
                        modifier = Modifier.weight(1f).fillMaxHeight(), enabled = enabled, quickFilter = true)
                }
                val otherFilterActive = state.selectedCategory !in listOf(ExerciseCategory.ALL, ExerciseCategory.CHEST, ExerciseCategory.BACK)
                OutlinedIconButton(onFilter, enabled = enabled, modifier = Modifier.size(48.dp), shape = RoutineControlShape,
                    border = BorderStroke(1.dp, if (otherFilterActive) c.primary else c.border),
                    colors = IconButtonDefaults.outlinedIconButtonColors(
                        containerColor = if (otherFilterActive) c.primaryContainer else c.surface,
                        contentColor = if (otherFilterActive) c.onPrimaryContainer else c.muted)) {
                    Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_routine_filter),
                        if (otherFilterActive) stringResource(R.string.routine_active_filter, stringResource(state.selectedCategory.labelRes))
                        else stringResource(R.string.routine_filter_title), modifier = Modifier.size(20.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.routine_available_exercises), Modifier.weight(1f), color = c.text, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.remote_read_loaded, state.filteredExercises.size), color = c.muted, style = MaterialTheme.typography.bodySmall)
            }
            RemoteRefreshBox(state.refreshing, onRefresh, Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 12.dp)) {
                if (state.isLoading) item { RoutineLoading(stringResource(R.string.routine_catalog_loading)) }
                state.errorRes?.let { error -> item { RemoteReadFailure(state.stale, onRetry) } }
                if (!state.isLoading && state.errorRes == null && state.filteredExercises.isEmpty()) item {
                    Column(Modifier.padding(vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.routine_search_empty), color = c.text, style = MaterialTheme.typography.titleMedium)
                         val filtered = state.searchQuery.isNotBlank() || state.selectedCategory != ExerciseCategory.ALL
                         Text(stringResource(if (filtered) R.string.routine_search_empty_hint else R.string.routine_catalog_empty), color = c.muted)
                         if (filtered) TextButton(onClearFilters) { Text(stringResource(R.string.routine_clear_filters), color = c.primary) }
                         else TextButton(onRefresh) { Text(stringResource(R.string.routine_retry), color = c.primary) }
                    }
                }
                items(state.filteredExercises, key = { it.id }) { exercise ->
                    ExercisePickerRow(exercise, state.selectedExercises.any { it.id == exercise.id }, enabled && !state.isLoading) { onToggle(exercise) }
                }
                item("page_footer") { RemotePageFooter(state.appending, state.appendError, state.endReached, onRetry, onMore) }
            }
            }
        }
    }
}

@Composable
internal fun ExercisePickerRow(exercise: Exercise, selected: Boolean, enabled: Boolean = true, onToggle: () -> Unit) {
    val c = IMFITColors.training
    Surface(shape = RoundedCornerShape(12.dp), color = if (selected) c.primaryContainer else c.surface,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.border)) {
        Row(Modifier.fillMaxWidth().toggleable(selected, enabled = enabled, role = Role.Checkbox, onValueChange = { onToggle() })
            .heightIn(min = 76.dp).padding(12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MuscleCategoryArtwork(categoryArtAsset(exercise.muscleCategory), Modifier.size(48.dp).background(c.raised, RoutineControlShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(exercise.name, color = c.text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(exercise.muscleCategory.stringResourceId) + if (selected) " · " + stringResource(R.string.routine_selected) else "",
                    color = if (selected) c.onPrimaryContainer else c.muted, style = MaterialTheme.typography.bodySmall)
            }
            Checkbox(selected, onCheckedChange = null, enabled = enabled,
                modifier = Modifier.size(48.dp), colors = CheckboxDefaults.colors(checkedColor = c.primary, checkmarkColor = c.onPrimary, uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant))
        }
    }
}

@Composable
internal fun MuscleFilterChip(category: ExerciseCategory, selected: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, quickFilter: Boolean = false) {
    val c = IMFITColors.training
    Surface(modifier, shape = RoutineControlShape, color = if (selected) c.primaryContainer else c.surface,
        border = BorderStroke(1.dp, if (selected && quickFilter) c.primaryContainer else if (selected) c.primary else c.border)) {
        Row(Modifier.selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .heightIn(min = 48.dp).padding(horizontal = if (quickFilter) 8.dp else 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            if (selected) Icon(Symbols.Default.Check, null, tint = c.onPrimaryContainer, modifier = Modifier.size(14.dp))
            Text(stringResource(if (category == ExerciseCategory.ALL) R.string.exercise_filter_all else category.labelRes),
                modifier = Modifier.weight(1f, fill = false), textAlign = TextAlign.Center,
                color = if (selected) c.onPrimaryContainer else c.muted,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun MuscleFilterSheet(current: ExerciseCategory, onDismiss: () -> Unit, onApply: (ExerciseCategory) -> Unit) {
    val c = IMFITColors.training
    var draft by rememberSaveable { mutableStateOf(current) }
    val options = listOf(ExerciseCategory.ALL) + ExerciseCategory.entries.filterNot { it == ExerciseCategory.ALL }
    ModalBottomSheet(onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface,
        scrimColor = Scrim.copy(alpha = IMFITOpacity.scrim),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.routine_filter_title), color = c.text, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.routine_filter_hint), color = c.muted, style = MaterialTheme.typography.bodyMedium)
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                options.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { category -> MuscleFilterChip(category, draft == category, { draft = category }, Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onDismiss, Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.action_cancel), color = c.muted) }
                Button({ onApply(draft) }, Modifier.heightIn(min = 48.dp), shape = RoutineControlShape,
                    colors = ButtonDefaults.buttonColors(containerColor = c.primary, contentColor = c.onPrimary)) {
                    Text(stringResource(R.string.routine_apply_filter))
                }
            }
        }
    }
}
