package com.diajarkoding.imfit.presentation.navigation

import androidx.navigation3.runtime.NavKey
import com.diajarkoding.imfit.domain.model.RoutineFlowContext
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
data class WorkoutDetail(val workoutId: String, val refreshId: String = "") : NavKey

@Serializable
data class EditWorkout(val context: RoutineFlowContext, val openPicker: Boolean = false) : NavKey

@Serializable
data class SetupRoutine(val context: RoutineFlowContext, val routineName: String = "") : NavKey
@Serializable
data class ExerciseList(val categoryName: String) : NavKey

@Serializable
data class ExerciseSelection(
    val context: RoutineFlowContext,
    val excludedIds: List<String> = emptyList(),
    val routineName: String? = null,
    val isInitial: Boolean
) : NavKey
@Serializable
data class ActiveExerciseSelection(
    val sessionId: String,
    val excludedIds: List<String>,
    val ownerId: String,
    val pickerId: String
) : NavKey

@Serializable
data class ActiveWorkout(val templateId: String) : NavKey

@Serializable
data class WorkoutSummary(val workoutLogId: String) : NavKey

@Serializable
data class WorkoutHistory(val date: String) : NavKey

@Serializable
data class YearlyCalendar(val year: Int, val month: Int) : NavKey

@Serializable
data object Profile : NavKey
