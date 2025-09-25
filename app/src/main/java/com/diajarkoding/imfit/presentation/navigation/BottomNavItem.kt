package com.diajarkoding.imfit.presentation.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.ui.graphics.vector.ImageVector
import com.diajarkoding.imfit.R

sealed class BottomNavItem(
    val route: String,
    val titleResId: Int,
    val icon: ImageVector
) {
    object Home : BottomNavItem("home", R.string.nav_home, Icons.Default.Home)
    object Workout : BottomNavItem("workout", R.string.nav_workout, Icons.Default.FitnessCenter)
    object Exercises : BottomNavItem("exercises", R.string.nav_exercises, Icons.Default.DateRange)
    object Progress : BottomNavItem("progress", R.string.nav_progress, Icons.Default.Timeline)
}