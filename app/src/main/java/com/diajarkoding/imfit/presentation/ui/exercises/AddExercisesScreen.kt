// file: presentation/ui/exercises/AddExercisesScreen.kt
package com.diajarkoding.imfit.presentation.ui.exercises

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.components.exercises.ExerciseSelectionItem
import com.diajarkoding.imfit.theme.IMFITSpacing

@Composable
fun AddExercisesScreen(
    onBackClick: () -> Unit, // onBackClick tidak lagi dibutuhkan di sini
    onAddSelectedExercises: (Set<String>) -> Unit,
    modifier: Modifier = Modifier, // Terima modifier untuk padding dari Scaffold utama
    viewModel: AddExercisesViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val selectedCount = state.selectedExerciseIds.size

    // Hapus Scaffold dan TopAppBar
    Box(modifier = modifier.fillMaxSize()) { // Terapkan padding di sini
        LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
            // TODO: Tambahkan SearchBar dan FilterChips di sini
            items(state.allExercises) { exercise ->
                ExerciseSelectionItem(
                    exercise = exercise,
                    isSelected = exercise.id in state.selectedExerciseIds,
                    onItemSelectedChange = { isSelected ->
                        // viewModel.onExerciseSelected(exercise.id, isSelected)
                    }
                )
            }
        }

        // Bottom Action Bar (tidak ada perubahan)
        if (selectedCount > 0) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(IMFITSpacing.md),
                shadowElevation = 8.dp,
                shape = MaterialTheme.shapes.large
            ) {
                Button(
                    onClick = { onAddSelectedExercises(state.selectedExerciseIds) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(stringResource(R.string.add_n_exercises, selectedCount))
                }
            }
        }
    }
}