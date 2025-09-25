package com.diajarkoding.imfit.presentation.ui.home

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

// Data class untuk menampung data dummy
data class ProgressItem(
    val progress: Float,
    val current: Int,
    val total: Int,
    val title: String,
    val timeRemaining: String
)

data class CategoryItem(val name: String, val isSelected: Boolean)
data class ExerciseItem(
    val imageRes: Int,
    val title: String,
    val exerciseCount: Int,
    val duration: String
)

data class HomeState(
    val progressItems: List<ProgressItem> = emptyList(),
    val categories: List<CategoryItem> = emptyList(),
    val exercises: List<ExerciseItem> = emptyList()
)

@HiltViewModel
class HomeViewModel @Inject constructor() : ViewModel() {
    private val _state = MutableStateFlow(HomeState())
    val state = _state.asStateFlow()

    init {
        // Isi dengan data dummy untuk prototyping UI
        _state.value = HomeState(
            progressItems = listOf(
                ProgressItem(0.41f, 5, 12, "Latihan Dada", "15 menit tersisa"),
                ProgressItem(0.15f, 3, 20, "Latihan Kaki", "23 menit tersisa")
            ),
            categories = listOf(
                CategoryItem("Semua", true),
                CategoryItem("Full Body", false),
                CategoryItem("Kardio", false),
                CategoryItem("Kekuatan", false)
            ),
            exercises = listOf(
                ExerciseItem(android.R.drawable.ic_menu_camera, "Latihan Pagi", 10, "30 menit"),
                ExerciseItem(android.R.drawable.ic_menu_gallery, "Latihan Malam", 8, "25 menit")
            )
        )
    }
}