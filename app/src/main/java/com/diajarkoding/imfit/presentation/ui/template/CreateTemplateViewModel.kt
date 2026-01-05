package com.diajarkoding.imfit.presentation.ui.template

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.Exercise
import com.diajarkoding.imfit.domain.model.TemplateExercise
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CreateTemplateState(
    val tempTemplateId: String = "temp_${System.currentTimeMillis()}",
    val templateName: String = "",
    val selectedExercises: List<TemplateExercise> = emptyList(),
    val nameError: String? = null,
    val isLoading: Boolean = false,
    val isSaved: Boolean = false
)

@HiltViewModel
class CreateTemplateViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val workoutRepository: WorkoutRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val _state = MutableStateFlow(CreateTemplateState())
    val state = _state.asStateFlow()

    init {
        savedStateHandle.getStateFlow<List<Exercise>?>("selected_exercises", null)
            .let { flow ->
                viewModelScope.launch {
                    flow.collect { exercises ->
                        exercises?.let { addExercises(it) }
                    }
                }
            }
    }

    fun onNameChange(name: String) {
        _state.update { it.copy(templateName = name, nameError = null) }
    }

    fun addExercises(exercises: List<Exercise>) {
        val currentExercises = _state.value.selectedExercises.toMutableList()
        exercises.forEach { exercise ->
            if (currentExercises.none { it.id == exercise.id }) {
                currentExercises.add(TemplateExercise(exercise = exercise))
            }
        }
        _state.update { it.copy(selectedExercises = currentExercises) }
    }

    fun removeExercise(templateExercise: TemplateExercise) {
        val currentExercises = _state.value.selectedExercises.toMutableList()
        currentExercises.remove(templateExercise)
        _state.update { it.copy(selectedExercises = currentExercises) }
    }

    fun saveTemplate() {
        val currentState = _state.value

        if (currentState.templateName.isBlank()) {
            _state.update { it.copy(nameError = "Template name is required") }
            return
        }

        if (currentState.selectedExercises.isEmpty()) {
            return
        }

        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            val user = authRepository.getCurrentUser()
            if (user == null) {
                _state.update { it.copy(isLoading = false) }
                return@launch
            }

            try {
                workoutRepository.createTemplate(
                    userId = user.id,
                    name = currentState.templateName,
                    exercises = currentState.selectedExercises
                )
                _state.update { it.copy(isLoading = false, isSaved = true) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }
}
