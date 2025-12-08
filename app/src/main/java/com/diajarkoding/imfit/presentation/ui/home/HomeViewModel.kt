package com.diajarkoding.imfit.presentation.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeState(
    val userName: String = "User",
    val templates: List<WorkoutTemplate> = emptyList(),
    val lastWorkout: WorkoutLog? = null,
    val isLoading: Boolean = false,
    val newlyCreatedWorkoutId: String? = null,
    val activeWorkoutTemplateId: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val workoutRepository: WorkoutRepository
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state = _state.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            val user = authRepository.getCurrentUser()
            val userId = user?.id ?: "user_1"
            val userName = user?.name ?: "User"

            val templates = workoutRepository.getTemplates(userId)
            val lastWorkout = workoutRepository.getLastWorkoutLog(userId)
            val activeSession = workoutRepository.getActiveSession()

            _state.update {
                it.copy(
                    userName = userName,
                    templates = templates,
                    lastWorkout = lastWorkout,
                    isLoading = false,
                    activeWorkoutTemplateId = activeSession?.templateId
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }

    fun refresh() {
        loadData()
    }

    fun createWorkout(name: String) {
        viewModelScope.launch {
            val userId = authRepository.getCurrentUser()?.id ?: "user_1"
            val newWorkout = workoutRepository.createTemplate(
                userId = userId,
                name = name,
                exercises = emptyList()
            )
            _state.update { it.copy(newlyCreatedWorkoutId = newWorkout.id) }
            refresh()
        }
    }

    fun clearNewlyCreatedWorkoutId() {
        _state.update { it.copy(newlyCreatedWorkoutId = null) }
    }
}
