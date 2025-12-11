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
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import javax.inject.Inject

data class ProgressState(
    val userName: String = "",
    val userEmail: String = "",
    val userBirthDate: String? = null,
    val userProfilePhotoUri: String? = null,
    val totalVolume: Double = 0.0,
    val weeklyWorkoutTimeMinutes: Int = 0,
    val workoutDates: Set<LocalDate> = emptySet(),
    val workoutLogsByDate: Map<LocalDate, List<WorkoutLog>> = emptyMap(),
    val isLoading: Boolean = true
)

@HiltViewModel
class ProgressViewModel @Inject constructor(
    private val workoutRepository: WorkoutRepository,
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ProgressState())
    val state: StateFlow<ProgressState> = _state.asStateFlow()

    init {
        loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }

            val user = authRepository.getCurrentUser()

            if (user == null) {
                _state.update {
                    it.copy(
                        userName = "Guest",
                        userEmail = "",
                        userBirthDate = null,
                        userProfilePhotoUri = null,
                        totalVolume = 0.0,
                        weeklyWorkoutTimeMinutes = 0,
                        workoutDates = emptySet(),
                        workoutLogsByDate = emptyMap(),
                        isLoading = false
                    )
                }
                return@launch
            }

            try {
                val logs = workoutRepository.getWorkoutLogs(user.id)

                val now = LocalDate.now()
                val startOfWeek = now.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

                val weeklyLogs = logs.filter { log ->
                    val logDate = Instant.ofEpochMilli(log.startTime)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                    !logDate.isBefore(startOfWeek) && !logDate.isAfter(now)
                }

                val weeklyTime = weeklyLogs.sumOf { it.durationMinutes }
                val totalVolume = logs.sumOf { it.totalVolume.toDouble() }

                val workoutLogsByDate = logs.groupBy { log ->
                    Instant.ofEpochMilli(log.startTime)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                }

                val workoutDates = workoutLogsByDate.keys

                _state.update {
                    it.copy(
                        userName = user.name,
                        userEmail = user.email,
                        userBirthDate = user.birthDate,
                        userProfilePhotoUri = user.profilePhotoUri,
                        totalVolume = totalVolume,
                        weeklyWorkoutTimeMinutes = weeklyTime,
                        workoutDates = workoutDates,
                        workoutLogsByDate = workoutLogsByDate,
                        isLoading = false
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        userName = user.name,
                        userEmail = user.email,
                        userBirthDate = user.birthDate,
                        userProfilePhotoUri = user.profilePhotoUri,
                        totalVolume = 0.0,
                        weeklyWorkoutTimeMinutes = 0,
                        workoutDates = emptySet(),
                        workoutLogsByDate = emptyMap(),
                        isLoading = false
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
}
