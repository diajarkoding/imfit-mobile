package com.diajarkoding.imfit.presentation.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
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
    val isCreating: Boolean = false,
    val error: String? = null,
    val newlyCreatedWorkoutId: String? = null,
    val activeWorkoutTemplateId: String? = null,
    val workoutCreatedSuccessfully: Boolean = false
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val workoutRepository: WorkoutRepository
) : ViewModel() {

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Log.e(TAG, "Coroutine exception: ${throwable.message}", throwable)
        _state.update { 
            it.copy(
                isLoading = false, 
                isCreating = false, 
                error = throwable.message ?: "An unexpected error occurred"
            ) 
        }
    }

    private val _state = MutableStateFlow(HomeState())
    val state = _state.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch(exceptionHandler) {
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
                        activeWorkoutTemplateId = null,
                        error = "Failed to load data. Please check your connection."
                    )
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch(exceptionHandler) {
            authRepository.logout()
        }
    }

    fun refresh() {
        loadData()
    }

    fun createWorkout(name: String) {
        viewModelScope.launch(exceptionHandler) {
            val user = authRepository.getCurrentUser()
            if (user == null) {
                _state.update { it.copy(error = "Please log in to create a workout") }
                return@launch
            }

            _state.update { it.copy(isCreating = true, error = null) }
            
            try {
                val newWorkout = workoutRepository.createTemplate(
                    userId = user.id,
                    name = name,
                    exercises = emptyList()
                )
                _state.update { 
                    it.copy(
                        newlyCreatedWorkoutId = newWorkout.id, 
                        isCreating = false,
                        workoutCreatedSuccessfully = true
                    ) 
                }
                refresh()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create workout: ${e.message}", e)
                _state.update { 
                    it.copy(
                        isCreating = false, 
                        error = "Failed to create workout. Please check your connection."
                    ) 
                }
            }
        }
    }

    fun clearNewlyCreatedWorkoutId() {
        _state.update { it.copy(newlyCreatedWorkoutId = null, workoutCreatedSuccessfully = false) }
    }

    fun clearError() {
        _state.update { it.copy(error = null) }
    }
    
    companion object {
        private const val TAG = "HomeViewModel"
    }
}
