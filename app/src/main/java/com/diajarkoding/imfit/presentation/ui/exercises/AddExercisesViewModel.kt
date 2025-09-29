package com.diajarkoding.imfit.presentation.ui.exercises

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import javax.inject.Inject

sealed class AddExercisesEvent {
    data class OnSearchQueryChanged(val query: String) : AddExercisesEvent()
    data class OnExerciseSelected(val exerciseId: String, val isSelected: Boolean) : AddExercisesEvent()
    object ClearSelection : AddExercisesEvent()
}

// TODO: Ganti dengan model Exercise dari Domain Layer
data class Exercise(
    val id: String,
    val name: String,
    val targetMuscle: String,
    val imageUrl: String
)

data class AddExercisesState(
    val isLoading: Boolean = false,
    val allExercises: List<Exercise> = emptyList(),
    val displayedExercises: List<Exercise> = emptyList(), // Daftar yang ditampilkan setelah filter/search
    val selectedExerciseIds: Set<String> = emptySet(),
    val searchQuery: String = ""
)

@HiltViewModel
class AddExercisesViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(AddExercisesState())
    val state = _state.asStateFlow()

    init {
        loadDummyExercises()
    }

    private fun loadDummyExercises() {
        val dummyList = List(20) { i ->
            Exercise(
                id = "ex-${i + 1}",
                name = when(i % 5) {
                    0 -> "Barbell Bench Press"
                    1 -> "Cable Lat Pulldown"
                    2 -> "Dumbbell Lateral Raise"
                    3 -> "Machine Leg Extension"
                    else -> "Bicep Curl"
                },
                targetMuscle = when(i % 5) {
                    0 -> "Dada, Trisep"
                    1 -> "Punggung, Bahu"
                    2 -> "Bahu"
                    3 -> "Kaki"
                    else -> "Bisep"
                },
                imageUrl = "https://placehold.co/100x100/EFEFEF/000000?text=Ex-${i+1}"
            )
        }
        _state.update { it.copy(
            isLoading = false,
            allExercises = dummyList,
            displayedExercises = dummyList
        )}
    }

    fun onEvent(event: AddExercisesEvent) {
        when(event) {
            is AddExercisesEvent.OnSearchQueryChanged -> {
                _state.update { it.copy(searchQuery = event.query) }
                filterExercises()
            }
            is AddExercisesEvent.OnExerciseSelected -> {
                val updatedSelection = _state.value.selectedExerciseIds.toMutableSet()
                if (event.isSelected) {
                    updatedSelection.add(event.exerciseId)
                } else {
                    updatedSelection.remove(event.exerciseId)
                }
                _state.update { it.copy(selectedExerciseIds = updatedSelection) }
            }
            AddExercisesEvent.ClearSelection -> {
                _state.update { it.copy(selectedExerciseIds = emptySet()) }
            }
        }
    }

    private fun filterExercises() {
        val filteredList = _state.value.allExercises.filter {
            it.name.contains(_state.value.searchQuery, ignoreCase = true) ||
                    it.targetMuscle.contains(_state.value.searchQuery, ignoreCase = true)
        }
        _state.update { it.copy(displayedExercises = filteredList) }
    }
}