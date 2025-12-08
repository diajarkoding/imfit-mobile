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
    val totalVolume: Double = 0.0,
    val weeklyWorkoutTimeMinutes: Int = 0,
    val workoutDates: Set<LocalDate> = emptySet(),
    val workoutLogsByDate: Map<LocalDate, List<WorkoutLog>> = emptyMap(),
    val isLoading: Boolean = false
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
            val userId = user?.id ?: "user_1"
            val logs = workoutRepository.getWorkoutLogs(userId)

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
                    userName = user?.name ?: "User",
                    userEmail = user?.email ?: "",
                    totalVolume = totalVolume,
                    weeklyWorkoutTimeMinutes = weeklyTime,
                    workoutDates = workoutDates,
                    workoutLogsByDate = workoutLogsByDate,
                    isLoading = false
                )
            }
        }
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }
}
