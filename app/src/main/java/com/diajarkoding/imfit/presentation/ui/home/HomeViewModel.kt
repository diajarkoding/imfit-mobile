package com.diajarkoding.imfit.presentation.ui.home

import android.util.Log
import androidx.annotation.StringRes
import com.diajarkoding.imfit.R
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
import kotlinx.coroutines.flow.collectLatest
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
    val activeWorkoutTemplateId: String? = null,
    val isCreating: Boolean = false,
    @StringRes val createErrorRes: Int? = null,
    @StringRes val createNameErrorRes: Int? = null,
    val retryingUploads: Boolean = false,
    val uploadRetryFailed: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val workoutRepository: WorkoutRepository,
    syncStateProvider: SyncStateProvider,
    private val syncRunner: com.diajarkoding.imfit.data.sync.SyncRunner,
) : ViewModel() {

    private val _state = MutableStateFlow(HomeState())
    val state = _state.asStateFlow()
    
    // Expose sync state for UI
    val syncState = syncStateProvider.syncState
    private var loadJob: Job? = null
    private var displayedOwner: String? = null

    init {
        loadData()
    }

    private fun loadData() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            try {
                authRepository.observeCurrentUser().collectLatest { user ->
                if (user == null) {
                    displayedOwner = null
                    _state.update { it.copy(
                        userName = "Guest", userProfilePhotoUri = null, templates = emptyList(),
                        lastWorkout = null, isLoading = false, activeWorkoutTemplateId = null
                    ) }
                    return@collectLatest
                }

                val userId = user.id
                if (displayedOwner != userId) {
                    displayedOwner = userId
                    _state.update { it.copy(userName = user.name, userProfilePhotoUri = null,
                        templates = emptyList(), lastWorkout = null, activeWorkoutTemplateId = null,
                        newlyCreatedWorkoutId = null, uploadRetryFailed = false) }
                }
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

    fun retryPendingUploads() {
        if (_state.value.retryingUploads) return
        _state.update { it.copy(retryingUploads = true, uploadRetryFailed = false) }
        viewModelScope.launch {
            try {
                val owner = authRepository.getLocalUserId()
                val success = owner != null && syncRunner.retryPendingUploads(owner) == com.diajarkoding.imfit.data.sync.SyncRunResult.SUCCESS
                _state.update { it.copy(uploadRetryFailed = !success) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.update { it.copy(uploadRetryFailed = true) } }
            finally { _state.update { it.copy(retryingUploads = false) } }
        }
    }

    fun createWorkout(name: String) {
        if (_state.value.isCreating || _state.value.newlyCreatedWorkoutId != null) return
        if (name.isBlank()) { _state.update { it.copy(createNameErrorRes = R.string.error_template_name_required) }; return }
        _state.update { it.copy(isCreating = true, createErrorRes = null, createNameErrorRes = null) }
        viewModelScope.launch {
            try {
            val user = authRepository.getCurrentUser()
            if (user == null) {
                _state.update { it.copy(isCreating = false, createErrorRes = R.string.routine_create_failed) }
                return@launch
            }

                val newWorkout = workoutRepository.createTemplate(
                    userId = user.id,
                    name = name.trim(),
                    exercises = emptyList()
                )
                _state.update { it.copy(newlyCreatedWorkoutId = newWorkout.id, isCreating = false) }
                refresh()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { it.copy(isCreating = false, createErrorRes = R.string.routine_create_failed) }
                Log.e("HomeViewModel", "Failed to create workout: ${e.message}", e)
            }
        }
    }

    fun clearCreateError() { _state.update { it.copy(createErrorRes = null, createNameErrorRes = null) } }

    fun clearNewlyCreatedWorkoutId() {
        _state.update { it.copy(newlyCreatedWorkoutId = null) }
    }
}
