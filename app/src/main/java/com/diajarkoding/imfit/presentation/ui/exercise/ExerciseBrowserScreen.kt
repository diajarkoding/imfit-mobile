package com.diajarkoding.imfit.presentation.ui.exercise

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.MuscleCategory
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.theme.*

@Composable
fun ExerciseBrowserScreen(
    onNavigateBack: () -> Unit,
    onCategorySelected: (MuscleCategory) -> Unit,
    onNavigateToProfile: () -> Unit = {},
    refreshKey: Long? = null,
    viewModel: ExerciseBrowserViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(refreshKey) { viewModel.refresh() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 360.dp && LocalDensity.current.fontScale <= 1.15f) 4 else 2
        LazyColumn(contentPadding = PaddingValues(IMFITSpacing.screenHorizontal), verticalArrangement = Arrangement.spacedBy(IMFITSpacing.lg)) {
            item { ImfitPageHeader(stringResource(R.string.app_name), onNavigateToProfile, brand = true) }
            item {
                Column {
                    Text(stringResource(R.string.nav_exercises_title), style = MaterialTheme.typography.headlineMedium)
                    Text(stringResource(R.string.redesign_explore_subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                OutlinedTextField(state.query, viewModel::setQuery, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    shape = IMFITShapes.Card,
                    leadingIcon = { Icon(Symbols.Default.Search, null) },
                    label = { Text(stringResource(R.string.redesign_search)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
            }
            if (state.query.isBlank()) {
                item { ImfitSectionHeader(stringResource(R.string.redesign_categories)) }
                items(MuscleCategory.entries.chunked(columns)) { categories ->
                    Row(horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)) {
                        categories.forEach { category ->
                            Card(onClick = { onCategorySelected(category) }, modifier = Modifier.weight(1f), shape = IMFITShapes.CardSmall,
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))) {
                                Column(Modifier.fillMaxWidth().padding(IMFITSpacing.sm), horizontalAlignment = Alignment.CenterHorizontally) {
                                    Image(painterResource(categoryArtRes(category)), null, Modifier.size(56.dp))
                                    Text(stringResource(category.stringResourceId), style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
            }
            item { ImfitSectionHeader(stringResource(R.string.redesign_explore)) }
            when {
                state.isLoading -> item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                state.hasError -> item {
                    ImfitEmptyMessage(stringResource(R.string.redesign_error))
                    TextButton(onClick = viewModel::refresh) { Text(stringResource(R.string.redesign_retry)) }
                }
                state.results.isEmpty() -> item { ImfitEmptyMessage(stringResource(if (state.query.isBlank()) R.string.redesign_empty_catalog else R.string.redesign_no_results)) }
                else -> items(if (state.query.isBlank()) state.results.take(4) else state.results, key = { it.id }) { exercise ->
                    ImfitWorkoutRow(exercise.name, stringResource(exercise.muscleCategory.stringResourceId) +
                        if (exercise.description.isNotBlank()) " · " + exercise.description else "", exercise.muscleCategory, subtitleMaxLines = 2) {
                        onCategorySelected(exercise.muscleCategory)
                    }
                }
            }
            item { Spacer(Modifier.height(IMFITSpacing.lg)) }
        }
    }
}
