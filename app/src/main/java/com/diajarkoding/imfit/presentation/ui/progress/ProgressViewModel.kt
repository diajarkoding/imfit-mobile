package com.diajarkoding.imfit.presentation.ui.progress

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.repository.AuthRepository
import com.diajarkoding.imfit.domain.repository.WorkoutRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

data class ProgressState(
    val userName: String = "",
    val userEmail: String = "",
    val userBirthDate: String? = null,
    val userProfilePhotoUri: String? = null,
    val workoutDates: Set<LocalDate> = emptySet(),
    val workoutLogsByDate: Map<LocalDate, List<WorkoutLog>> = emptyMap(),
    val isLoading: Boolean = true,
    val hasError: Boolean = false
)

@HiltViewModel
class ProgressViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ProgressState())
    val state: StateFlow<ProgressState> = _state.asStateFlow()
    private var loadJob: Job? = null

    init {
        loadData()
    }

    fun loadData() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true, hasError = false) }

            try {
                val user = authRepository.getCurrentUser()
                if (user == null) {
                    _state.update { it.copy(
                        userName = "Guest",
                        userEmail = "",
                        userBirthDate = null,
                        userProfilePhotoUri = null,
                        workoutDates = emptySet(),
                        workoutLogsByDate = emptyMap(),
                        isLoading = false
                    ) }
                    return@launch
                }

                _state.update { it.copy(userName = user.name, userEmail = user.email, userBirthDate = user.birthDate) }
                // Get signed URL for profile photo (private bucket)
                val signedAvatarUrl = try {
                    authRepository.getSignedAvatarUrl(user.profilePhotoUri)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null // A failed avatar fetch must not hide locally available workout history.
                }
                
                workoutRepository.observeWorkoutLogs(user.id).collect { logs ->
                    val workoutLogsByDate = logs.distinctBy { it.id }.groupBy { log ->
                        Instant.ofEpochMilli(log.startTime).atZone(ZoneId.systemDefault()).toLocalDate()
                    }
                    _state.update { it.copy(
                        userProfilePhotoUri = signedAvatarUrl, workoutDates = workoutLogsByDate.keys,
                        workoutLogsByDate = workoutLogsByDate, isLoading = false, hasError = false
                    ) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _state.update { it.copy(isLoading = false, hasError = true) }
            }
        }
    }

    fun logout(onComplete: () -> Unit = {}) {
        viewModelScope.launch {
            authRepository.logout()
            onComplete()
        }
    }
}
