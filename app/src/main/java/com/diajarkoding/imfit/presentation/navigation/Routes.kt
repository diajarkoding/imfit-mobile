package com.diajarkoding.imfit.presentation.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
data object Splash : NavKey

@Serializable
data object Login : NavKey

@Serializable
data object Register : NavKey

@Serializable
data object RegisterConfirmation : NavKey

@Serializable
data object Main : NavKey

@Serializable
data class WorkoutDetail(val workoutId: String) : NavKey

@Serializable
data class EditWorkout(val workoutId: String) : NavKey

@Serializable
data class ExerciseList(val categoryName: String) : NavKey

@Serializable
data class ExerciseSelection(val templateId: String) : NavKey

@Serializable
data class ActiveWorkout(val templateId: String) : NavKey

@Serializable
data class WorkoutSummary(val workoutLogId: String) : NavKey

@Serializable
data class WorkoutHistory(val date: String) : NavKey

@Serializable
data object YearlyCalendar : NavKey

@Serializable
data object Profile : NavKey
