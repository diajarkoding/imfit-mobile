package com.diajarkoding.imfit.presentation.ui.workout.viewmodel

import androidx.lifecycle.ViewModel
import com.diajarkoding.imfit.presentation.navigation.Routes
import com.diajarkoding.imfit.presentation.ui.exercises.Exercise
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

// Mode tampilan utama Workout (flow aplikasi)
enum class WorkoutScreenMode {
    EMPTY,      // Belum ada plan sama sekali
    ADD_PLAN,   // Plan baru dibuat, belum ada days
    PLANNED     // Plan sudah memiliki satu atau lebih days
}

// Representasi satu hari latihan
data class WorkoutDay(
    val id: String,
    val title: String,
    val estimatedTime: String,
    val exerciseCount: String,
    val exercises: List<Exercise> = emptyList(),
    val status: String,
)

// Representasi rencana latihan lengkap
data class WorkoutPlan(
    val id: String,
    val title: String,
    val days: List<WorkoutDay>,
    val imageUrl: String,
    val maxDays: Int = 7
)

// Tampilan yang sedang aktif
sealed class WorkoutView {
    object Overview : WorkoutView() // Daftar semua hari
    data class DayDetail(val day: WorkoutDay) : WorkoutView() // Detail 1 hari
}

// Event yang bisa dipicu user dari UI
sealed class WorkoutEvent {
    data class DayCardClicked(val day: WorkoutDay) : WorkoutEvent()
    object BackFromDetailClicked : WorkoutEvent()
    data class TabSelected(val index: Int) : WorkoutEvent()
    data class MoreMenuClicked(val day: WorkoutDay) : WorkoutEvent()
    object DismissDropdownMenu : WorkoutEvent()
    data class EditDayClicked(val day: WorkoutDay) : WorkoutEvent()
    object NavigationHandled : WorkoutEvent()
    object CreatePlanFromScratch : WorkoutEvent()
    object AddDayClicked : WorkoutEvent()
    object AddExerciseClicked : WorkoutEvent()
    data class ExercisesAdded(val exerciseIds: List<String>) : WorkoutEvent()
}

// State global layar Workout
data class WorkoutState(
    val plan: WorkoutPlan? = null,
    val currentView: WorkoutView = WorkoutView.Overview,
    val isDropdownMenuExpanded: Boolean = false,
    val navigateTo: String? = null,
    val screenMode: WorkoutScreenMode = WorkoutScreenMode.EMPTY
)

@HiltViewModel
class WorkoutViewModel @Inject constructor() : ViewModel() {

    private val _state = MutableStateFlow(WorkoutState())
    val state = _state.asStateFlow()

    init {
        // Default pertama kali: belum ada plan
        _state.update { it.copy(plan = null, screenMode = WorkoutScreenMode.EMPTY) }
    }

    // Loader dummy untuk development/testing
    private fun loadWorkoutPlan(mode: String = "null") {
        val dummyPlan = when (mode) {
            "null" -> null
            "empty" -> WorkoutPlan(
                id = "0",
                title = "Empty Plan",
                imageUrl = "https://placehold.co/600x400/CCCCCC/000000?text=Empty+Plan",
                days = emptyList()
            )

            "filled" -> WorkoutPlan(
                id = "1",
                title = "Push Pull Workout",
                imageUrl = "https://placehold.co/600x400/7B1FA2/FFFFFF?text=IMFit",
                days = listOf(
                    // Perbaiki urutan dan tipe data argumen di sini
                    WorkoutDay(
                        id = "d1",
                        title = "TUE Legs",
                        estimatedTime = "57m",
                        exerciseCount = "6", // Ubah menjadi jumlah saja
                        exercises = emptyList(), // Sediakan list kosong
                        status = "Completed"
                    ),
                    WorkoutDay(
                        id = "d2",
                        title = "ANY Push",
                        estimatedTime = "1h 49m",
                        exerciseCount = "14",
                        exercises = emptyList(),
                        status = "Not Started"
                    ),
                    WorkoutDay(
                        id = "d3",
                        title = "ANY Pull",
                        estimatedTime = "1h 38m",
                        exerciseCount = "13",
                        exercises = emptyList(),
                        status = "Not Started"
                    )
                )
            )

            else -> null
        }
        _state.update { it.copy(plan = dummyPlan) }
    }

    fun onEvent(event: WorkoutEvent) {
        when (event) {
            is WorkoutEvent.DayCardClicked -> {
                _state.update { it.copy(currentView = WorkoutView.DayDetail(event.day)) }
            }

            is WorkoutEvent.TabSelected -> {
                if (event.index == 0) {
                    _state.update { it.copy(currentView = WorkoutView.Overview) }
                }
            }

            is WorkoutEvent.MoreMenuClicked -> {
                _state.update { it.copy(isDropdownMenuExpanded = true) }
            }

            WorkoutEvent.DismissDropdownMenu -> {
                _state.update { it.copy(isDropdownMenuExpanded = false) }
            }

            is WorkoutEvent.EditDayClicked -> {
                _state.update {
                    it.copy(
                        isDropdownMenuExpanded = false,
                        navigateTo = Routes.editWorkoutDay(event.day.id, event.day.title)
                    )
                }
            }

            WorkoutEvent.NavigationHandled -> {
                _state.update { it.copy(navigateTo = null) }
            }

            WorkoutEvent.CreatePlanFromScratch -> {
                val newPlan = WorkoutPlan(
                    id = "new_plan",
                    title = "My New Workout Plan",
                    imageUrl = "https://placehold.co/600x400/2196F3/FFFFFF?text=New+Plan",
                    days = emptyList()
                )
                _state.update {
                    it.copy(
                        plan = newPlan,
                        currentView = WorkoutView.Overview,
                        screenMode = WorkoutScreenMode.ADD_PLAN
                    )
                }
            }

            WorkoutEvent.AddDayClicked -> {
                val currentPlan = _state.value.plan
                if (currentPlan != null) {
                    val newDay = WorkoutDay(
                        id = "day_${currentPlan.days.size + 1}",
                        title = "New Day ${currentPlan.days.size + 1}",
                        estimatedTime = "0m",
                        exerciseCount = "0 exercises",
                        exercises = emptyList(),
                        status = "Not Started"
                    )
                    val updatedPlan = currentPlan.copy(days = currentPlan.days + newDay)
                    _state.update {
                        it.copy(
                            plan = updatedPlan,
                            screenMode = WorkoutScreenMode.PLANNED
                        )
                    }
                }
            }

            WorkoutEvent.AddExerciseClicked -> {
                // Pastikan kita berada di tampilan detail untuk mendapatkan ID hari
                val currentView = _state.value.currentView
                if (currentView is WorkoutView.DayDetail) {
                    _state.update {
                        it.copy(navigateTo = Routes.addExercises(currentView.day.id))
                    }
                }
            }

            is WorkoutEvent.ExercisesAdded -> {
                addExercisesToDay(event.exerciseIds)
            }

            else -> {}
        }
    }

    private fun addExercisesToDay(exerciseIds: List<String>) {
        val currentView = _state.value.currentView
        val currentPlan = _state.value.plan

        // Pastikan kita berada di DayDetail dan ada plan yang aktif
        if (currentView is WorkoutView.DayDetail && currentPlan != null) {
            val dayIdToUpdate = currentView.day.id

            // Buat daftar latihan baru dari ID yang diterima (menggunakan data dummy)
            val newExercises = exerciseIds.map { id ->
                // TODO: Di aplikasi nyata, Anda akan mengambil detail latihan dari repository berdasarkan ID
                Exercise(
                    id = id,
                    name = "Latihan Baru ${id.replaceFirst("ex-", "")}",
                    targetMuscle = "Otot Target",
                    imageUrl = "https://placehold.co/100x100/CCCCCC/000000?text=Ex"
                )
            }

            // Perbarui daftar hari (days) di dalam rencana (plan)
            val updatedDays = currentPlan.days.map { day ->
                if (day.id == dayIdToUpdate) {
                    // Jika hari cocok, tambahkan latihan baru ke daftar yang sudah ada
                    val updatedExercises = day.exercises + newExercises
                    day.copy(
                        exercises = updatedExercises,
                        exerciseCount = updatedExercises.size.toString() // Perbarui jumlah latihan
                    )
                } else {
                    day
                }
            }

            // Perbarui state dengan rencana yang sudah dimodifikasi
            val updatedPlan = currentPlan.copy(days = updatedDays)
            _state.update {
                it.copy(
                    plan = updatedPlan,
                    // Perbarui juga currentView agar UI langsung me-render data baru
                    currentView = WorkoutView.DayDetail(updatedDays.find { it.id == dayIdToUpdate }!!)
                )
            }
        }
    }

}
