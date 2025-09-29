package com.diajarkoding.imfit.presentation.ui.exercises

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.components.exercises.ExerciseSelectionItem
import com.diajarkoding.imfit.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExercisesScreen(
    onBackClick: () -> Unit,
    onAddSelectedExercises: (Set<String>) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AddExercisesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val selectedCount = state.selectedExerciseIds.size

    Box {
        LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
            // --- SearchBar dan FilterChips ---
            item {
                Column(modifier = Modifier.padding(horizontal = IMFITSpacing.md)) {
                    OutlinedTextField(
                        value = state.searchQuery,
                        onValueChange = { viewModel.onEvent(AddExercisesEvent.OnSearchQueryChanged(it)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.search_exercise_hint)) },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                    )
                    Spacer(modifier = Modifier.height(IMFITSpacing.sm))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)) {
                        items(listOf("Otot Target", "Alat", "Tipe")) { filter ->
                            FilterChip(selected = false, onClick = { /* TODO */ }, label = { Text(filter) })
                        }
                    }
                }
            }

            // --- List Header ---
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = IMFITSpacing.md, vertical = IMFITSpacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.all_exercises), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { /* TODO */ }) {
                        Text(stringResource(R.string.sort_popular), color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // Gunakan displayedExercises agar daftar bisa berubah saat dicari
            items(state.displayedExercises) { exercise ->
                ExerciseSelectionItem(
                    exercise = exercise,
                    isSelected = exercise.id in state.selectedExerciseIds,
                    onItemSelectedChange = { isSelected ->
                        viewModel.onEvent(AddExercisesEvent.OnExerciseSelected(exercise.id, isSelected))
                    }
                )
            }
        }

        // --- Bottom Action Bar ---
        Box(
            modifier = Modifier.align(Alignment.BottomCenter).padding(IMFITSpacing.md)
        ) {
            Button(
                onClick = { onAddSelectedExercises(state.selectedExerciseIds) },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                enabled = selectedCount > 0 // Tombol aktif jika ada item terpilih
            ) {
                Text(stringResource(R.string.add_n_exercises, selectedCount))
            }
        }
    }
}