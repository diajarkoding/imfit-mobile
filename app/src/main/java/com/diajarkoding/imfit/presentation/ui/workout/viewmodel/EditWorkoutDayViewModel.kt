package com.diajarkoding.imfit.presentation.ui.workout.viewmodel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.diajarkoding.imfit.presentation.ui.exercises.Exercise
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class EditWorkoutDayState(
    val dayName: String = "",
    val exercises: List<Exercise> = emptyList(),
    val isLoading: Boolean = false,
    val deleteSuccess: Boolean = false,
    val saveSuccess: Boolean = false
)

sealed class EditWorkoutDayEvent {
    data class OnDayNameChanged(val name: String) : EditWorkoutDayEvent()
    data class OnAddExercises(val exerciseIds: List<String>) : EditWorkoutDayEvent()
    data class OnDeleteExercise(val exerciseId: String) : EditWorkoutDayEvent()
    object OnDeleteDayClicked : EditWorkoutDayEvent()
    object OnSaveClicked : EditWorkoutDayEvent()
}

@HiltViewModel
class EditWorkoutDayViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val _state = MutableStateFlow(EditWorkoutDayState())
    val state = _state.asStateFlow()

    private val dayId: String = savedStateHandle.get<String>("dayId") ?: ""

    init {
        // TODO: Ganti dengan panggilan API/repository untuk mengambil data hari berdasarkan dayId
        val dayNameFromNav: String = savedStateHandle.get<String>("dayName") ?: "Nama Hari"
        _state.update { it.copy(
            dayName = dayNameFromNav,
            exercises = listOf( // Data dummy latihan yang sudah ada
                Exercise("ex-1", "Barbell Bench Press", "Dada, Trisep", "url1"),
                Exercise("ex-2", "Cable Lat Pulldown", "Punggung, Bahu", "url2")
            )
        )}
    }

    fun onEvent(event: EditWorkoutDayEvent) {
        when(event) {
            is EditWorkoutDayEvent.OnDayNameChanged -> {
                _state.update { it.copy(dayName = event.name) }
            }
            is EditWorkoutDayEvent.OnAddExercises -> {
                // TODO: Ambil detail exercise dari repository berdasarkan Ids
                val newExercises = event.exerciseIds.map { id ->
                    Exercise(id, "Latihan Baru $id", "Otot", "url_baru")
                }
                _state.update { it.copy(exercises = it.exercises + newExercises) }
            }
            is EditWorkoutDayEvent.OnDeleteExercise -> {
                _state.update {
                    it.copy(exercises = it.exercises.filterNot { ex -> ex.id == event.exerciseId })
                }
            }
            EditWorkoutDayEvent.OnDeleteDayClicked -> {
                // TODO: Panggil use case untuk menghapus hari
                _state.update { it.copy(deleteSuccess = true) }
            }
            EditWorkoutDayEvent.OnSaveClicked -> {
                // TODO: Panggil use case untuk menyimpan perubahan
                _state.update { it.copy(saveSuccess = true) }
            }
        }
    }
}