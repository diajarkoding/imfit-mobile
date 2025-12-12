package com.diajarkoding.imfit.presentation.ui.home

import android.util.Log
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
    val userProfilePhotoUri: String? = null,
    val templates: List<WorkoutTemplate> = emptyList(),
    val lastWorkout: WorkoutLog? = null,
    val isLoading: Boolean = true,
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

            if (user == null) {
                _state.update {
                    it.copy(
                        userName = "Guest",
                        userProfilePhotoUri = null,
                        templates = emptyList(),
                        lastWorkout = null,
                        isLoading = false,
                        activeWorkoutTemplateId = null
                    )
                }
                return@launch
            }

            val userId = user.id
            val userName = user.name
            
            // Get signed URL for profile photo (private bucket)
            val signedAvatarUrl = authRepository.getSignedAvatarUrl(user.profilePhotoUri)

            try {
                val templates = workoutRepository.getTemplates(userId)
                val lastWorkout = workoutRepository.getLastWorkoutLog(userId)
                val activeSession = workoutRepository.getActiveSession()

                _state.update {
                    it.copy(
                        userName = userName,
                        userProfilePhotoUri = signedAvatarUrl,
                        templates = templates,
                        lastWorkout = lastWorkout,
                        isLoading = false,
                        activeWorkoutTemplateId = activeSession?.templateId
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        userName = userName,
                        userProfilePhotoUri = signedAvatarUrl,
                        templates = emptyList(),
                        lastWorkout = null,
                        isLoading = false,
                        activeWorkoutTemplateId = null
                    )
                }
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
            val user = authRepository.getCurrentUser()
            if (user == null) {
                // User is not authenticated, cannot create workout
                return@launch
            }

            try {
                val newWorkout = workoutRepository.createTemplate(
                    userId = user.id,
                    name = name,
                    exercises = emptyList()
                )
                _state.update { it.copy(newlyCreatedWorkoutId = newWorkout.id) }
                refresh()
            } catch (e: Exception) {
                // Handle error silently or show error state
                Log.e("HomeViewModel", "Failed to create workout: ${e.message}", e)
            }
        }
    }

    fun clearNewlyCreatedWorkoutId() {
        _state.update { it.copy(newlyCreatedWorkoutId = null) }
    }
}
