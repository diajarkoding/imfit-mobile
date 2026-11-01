package com.diajarkoding.imfit.presentation.navigation

import androidx.navigation3.runtime.NavKey
import com.diajarkoding.imfit.domain.model.RoutineFlowContext
import kotlinx.serialization.Serializable

import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
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

val navKeySerializersModule = SerializersModule {
    polymorphic(NavKey::class) {
        subclass(Splash::class)
        subclass(Login::class)
        subclass(Register::class)
        subclass(RegisterConfirmation::class)
        subclass(Main::class)
        subclass(WorkoutDetail::class)
        subclass(EditWorkout::class)
        subclass(SetupRoutine::class)
        subclass(ExerciseList::class)
        subclass(ExerciseSelection::class)
        subclass(ActiveExerciseSelection::class)
        subclass(ActiveWorkout::class)
        subclass(WorkoutSummary::class)
        subclass(WorkoutHistory::class)
        subclass(YearlyCalendar::class)
        subclass(Profile::class)
    }
}

val navSavedStateConfig = SavedStateConfiguration {
    serializersModule = navKeySerializersModule
}
