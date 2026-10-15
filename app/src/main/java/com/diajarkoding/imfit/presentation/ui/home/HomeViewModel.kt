package com.diajarkoding.imfit.presentation.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.data.sync.SyncStateProvider
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
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
    private val workoutRepository: WorkoutRepository,
    syncStateProvider: SyncStateProvider
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state = _state.asStateFlow()
    
    // Expose sync state for UI
    val syncState = syncStateProvider.syncState
    private var loadJob: Job? = null

    init {
        loadData()
    }

    private fun loadData() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            try {
                val user = authRepository.getCurrentUser()
                if (user == null) {
                    _state.update { it.copy(
                        userName = "Guest", userProfilePhotoUri = null, templates = emptyList(),
                        lastWorkout = null, isLoading = false, activeWorkoutTemplateId = null
                    ) }
                    return@launch
                }

                val userId = user.id
                val userName = user.name
                // Avatar failures must not hide locally saved workout history.
                val signedAvatarUrl = try {
                    authRepository.getSignedAvatarUrl(user.profilePhotoUri)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }

                val activeSession = workoutRepository.getActiveSession()

                _state.update {
                    it.copy(
                        userName = userName,
                        userProfilePhotoUri = signedAvatarUrl,
                        activeWorkoutTemplateId = activeSession?.templateId
                    )
                }
                combine(workoutRepository.observeTemplates(userId), workoutRepository.observeLastWorkoutLog(userId)) { templates, last ->
                    templates to last
                }.collect { (templates, last) ->
                    _state.update { it.copy(templates = templates, lastWorkout = last, isLoading = false) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Unable to observe local Home data", e)
                _state.update { it.copy(isLoading = false) }
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
