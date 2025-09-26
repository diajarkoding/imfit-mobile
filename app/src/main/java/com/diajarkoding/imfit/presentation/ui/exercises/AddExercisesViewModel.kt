package com.diajarkoding.imfit.presentation.ui.exercises


import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

// TODO: Ganti dengan model Exercise dari Domain Layer
data class Exercise(
    val id: String,
    val name: String,
    val targetMuscle: String,
    val imageUrl: String
)

data class AddExercisesState(
    val isLoading: Boolean = true,
    val allExercises: List<Exercise> = emptyList(),
    val selectedExerciseIds: Set<String> = emptySet(), // Gunakan Set untuk efisiensi
    val searchQuery: String = ""
)

@HiltViewModel
class AddExercisesViewModel @Inject constructor(
    // private val getExercisesUseCase: GetExercisesUseCase // Inject nanti
) : ViewModel() {
    private val _state = MutableStateFlow(AddExercisesState())
    val state = _state.asStateFlow()
    // TODO: Buat fungsi untuk fetch exercises, handle search, dan selection
}