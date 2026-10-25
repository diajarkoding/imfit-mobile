package com.diajarkoding.imfit.presentation.ui.exercise

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.theme.*

@Composable
fun ExerciseListScreen(
    categoryName: String,
    onNavigateBack: () -> Unit,
    viewModel: ExerciseBrowserViewModel = hiltViewModel()
) {
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    ExerciseListContent(ExerciseCategory.fromRoute(categoryName), state, onNavigateBack, viewModel::setQuery, viewModel::refresh)
}

/** Read-only catalog: unlike the picker, rows do not select or navigate to a detail page. */
@Composable
internal fun ExerciseListContent(
    category: ExerciseCategory,
    state: ExerciseBrowserState,
    onBack: () -> Unit,
    onSearch: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = IMFITColors.training
    val exercises = remember(state.results, category) { state.results.filter(category::includes).distinctBy { it.id } }
    Scaffold(
        modifier = modifier.imePadding(),
        containerColor = colors.background,
        contentWindowInsets = WindowInsets.navigationBars,
        topBar = { RoutineAppBar(stringResource(category.labelRes), onBack) }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .padding(horizontal = IMFITSpacing.screenHorizontal)) {
            ExerciseListSearch(state.query, onSearch)
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("exercise_list"),
                contentPadding = PaddingValues(top = IMFITSpacing.md, bottom = IMFITSpacing.xl),
                verticalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)
            ) {
                when {
                    state.isLoading -> item(key = "loading") {
                        Box(Modifier.testTag("exercise_list_loading")) {
                            RoutineLoading(stringResource(R.string.routine_catalog_loading))
                        }
                    }
                    state.hasError -> item(key = "error") {
                        Box(Modifier.testTag("exercise_list_error")) {
                            RoutineFailure(stringResource(R.string.redesign_error), onRetry)
                        }
                    }
                    exercises.isEmpty() -> item(key = "empty") {
                        ExerciseListEmpty(category, state.query, onSearch)
                    }
                    else -> items(exercises, key = { "exercise_${it.id}" }) { exercise ->
                        ExerciseListRow(exercise)
                    }
                }
            }
        }
    }
}

@Composable
private fun ExerciseListSearch(query: String, onSearch: (String) -> Unit) {
    val colors = IMFITColors.training
    val keyboard = LocalSoftwareKeyboardController.current
    val searchLabel = stringResource(R.string.redesign_search)
    OutlinedTextField(
        value = query,
        onValueChange = onSearch,
        modifier = Modifier.fillMaxWidth().padding(top = IMFITSpacing.headerContentGap).heightIn(min = 52.dp)
            .testTag("exercise_list_search").semantics { contentDescription = searchLabel },
        singleLine = true,
        shape = RoutineControlShape,
        placeholder = { Text(searchLabel, color = colors.muted) },
        leadingIcon = { Icon(Symbols.Default.Search, null, tint = colors.muted, modifier = Modifier.size(IMFITSizes.iconSm)) },
        trailingIcon = if (query.isNotEmpty()) {{
            IconButton({ onSearch("") }, Modifier.size(IMFITSizes.minimumInteractiveTarget)) {
                Icon(Symbols.Default.Clear, stringResource(R.string.action_clear), tint = colors.muted)
            }
        }} else null,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.primary, unfocusedBorderColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = colors.surface, unfocusedContainerColor = colors.surface,
            focusedTextColor = colors.text, unfocusedTextColor = colors.text, cursorColor = colors.primary
        )
    )
}

@Composable
private fun ExerciseListRow(exercise: Exercise) {
    val colors = IMFITColors.training
    Surface(Modifier.fillMaxWidth().testTag("exercise_list_row_${exercise.id}"),
        shape = RoundedCornerShape(IMFITCornerRadius.template), color = colors.surface,
        border = BorderStroke(IMFITComponentSizes.border, colors.border)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(IMFITSpacing.md).semantics(mergeDescendants = true) {},
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md)) {
            MuscleCategoryArtwork(categoryArtAsset(exercise.muscleCategory),
                Modifier.size(IMFITSizes.minimumInteractiveTarget).background(colors.raised, RoutineControlShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(IMFITSpacing.xs)) {
                Text(exercise.name, Modifier.fillMaxWidth(), color = colors.text,
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(stringResource(exercise.muscleCategory.stringResourceId), Modifier.fillMaxWidth(),
                    color = colors.muted, style = MaterialTheme.typography.bodySmall)
                if (exercise.description.isNotBlank()) {
                    Text(exercise.description, Modifier.fillMaxWidth(), color = colors.muted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun ExerciseListEmpty(category: ExerciseCategory, query: String, onSearch: (String) -> Unit) {
    val colors = IMFITColors.training
    val searching = query.isNotBlank()
    val message = when {
        searching -> R.string.redesign_no_results
        category in setOf(ExerciseCategory.FOREARMS, ExerciseCategory.GLUTES, ExerciseCategory.CALVES) -> R.string.exercise_subgroup_empty
        else -> R.string.exercise_no_results
    }
    Surface(Modifier.fillMaxWidth().testTag("exercise_list_empty").semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(IMFITCornerRadius.template), color = colors.surface,
        border = BorderStroke(IMFITComponentSizes.border, colors.border)) {
        Column(Modifier.padding(IMFITSpacing.cardPadding), verticalArrangement = Arrangement.spacedBy(IMFITSpacing.md)) {
            Icon(if (searching) Symbols.Default.Search else Symbols.Default.FitnessCenter, null,
                tint = colors.primary, modifier = Modifier.size(24.dp))
            Text(stringResource(message), color = colors.text, style = MaterialTheme.typography.bodyMedium)
            if (query.isNotEmpty()) {
                TextButton({ onSearch("") }, Modifier.heightIn(min = IMFITSizes.minimumInteractiveTarget)) {
                    Text(stringResource(R.string.action_clear), color = colors.primary)
                }
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Preview(showBackground = true, widthDp = 320, fontScale = 1.5f)
@Composable
private fun ExerciseListScreenPreview() {
    IMFITTheme(darkTheme = false) {
        ExerciseListContent(ExerciseCategory.CHEST, ExerciseBrowserState(results = listOf(
            Exercise("bench", "Barbell Bench Press", MuscleCategory.CHEST, "Compound chest exercise with a barbell.")
        )), {}, {}, {})
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun ExerciseListScreenDarkPreview() {
    IMFITTheme(darkTheme = true) {
        ExerciseListContent(ExerciseCategory.CHEST, ExerciseBrowserState(isLoading = true), {}, {}, {})
    }
}
