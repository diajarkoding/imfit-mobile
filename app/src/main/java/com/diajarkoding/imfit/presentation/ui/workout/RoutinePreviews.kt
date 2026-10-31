package com.diajarkoding.imfit.presentation.ui.workout

import android.content.res.Configuration
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.*
import com.diajarkoding.imfit.presentation.components.common.*
import com.diajarkoding.imfit.presentation.ui.exercise.*
import com.diajarkoding.imfit.theme.IMFITTheme

private val previewExercises = listOf(
    Exercise("preview_bench", "Barbell Bench Press", MuscleCategory.CHEST, ""),
    Exercise("preview_incline", "Incline Dumbbell Press", MuscleCategory.CHEST, ""),
    Exercise("preview_cable", "Cable Flyes", MuscleCategory.CHEST, "")
)
private val previewRoutine = WorkoutTemplate("preview", "preview", "Push Day", previewExercises.map { TemplateExercise(it) })

@Composable
private fun RoutinePreviewTheme(content: @Composable () -> Unit) {
    IMFITTheme(darkTheme = isSystemInDarkTheme(), content = content)
}

@Preview(name = "Detail · Light", widthDp = 412, heightDp = 956)
@Preview(name = "Detail · Dark", widthDp = 412, heightDp = 956, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RoutineDetailPreview() { RoutinePreviewTheme { WorkoutDetailContent(WorkoutDetailState(previewRoutine, isLoading = false), 24) } }

@Preview(name = "Empty detail · Light", widthDp = 412, heightDp = 956)
@Preview(name = "Empty detail · Dark", widthDp = 412, heightDp = 956, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RoutineEmptyPreview() {
    RoutinePreviewTheme { WorkoutDetailContent(WorkoutDetailState(previewRoutine.copy(name = "Mager Day", exercises = emptyList()), isLoading = false), 0) }
}

@Preview(name = "Edit · Light", widthDp = 412, heightDp = 956)
@Preview(name = "Edit · Dark", widthDp = 412, heightDp = 956, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RoutineEditPreview() {
    RoutinePreviewTheme { EditWorkoutContent(EditWorkoutState(workoutName = "Pull Day", hasLoaded = true,
        exercises = listOf(TemplateExercise(Exercise("preview_deadlift", "Deadlift", MuscleCategory.BACK, "")),
            TemplateExercise(Exercise("preview_pulldown", "Straight-Arm Pulldown", MuscleCategory.BACK, ""))))) }
}

@Composable
private fun PickerPreview(selected: Boolean) {
    ExercisePickerContent(ExerciseSelectionState(allExercises = previewExercises, filteredExercises = previewExercises,
        selectedExercises = if (selected) previewExercises.take(2) else emptyList(), routineName = "Push Day", isLoading = false,
        selectedCategory = if (selected) ExerciseCategory.CHEST else ExerciseCategory.ALL),
        onBack = {}, onSearch = {}, onCategory = {}, onToggle = {}, onReset = {}, onClearFilters = {},
        onRetry = {}, onRefresh = {}, onLoadNext = {}, onFilter = {}, onConfirm = {})
}

@Preview(name = "Choose · None · Light", widthDp = 412, heightDp = 956)
@Preview(name = "Choose · None · Dark", widthDp = 412, heightDp = 956, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PickerEmptySelectionPreview() { RoutinePreviewTheme { PickerPreview(false) } }

@Preview(name = "Choose · Two selected · Light", widthDp = 412, heightDp = 956)
@Preview(name = "Choose · Two selected · Dark", widthDp = 412, heightDp = 956, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun PickerTwoSelectionPreview() { RoutinePreviewTheme { PickerPreview(true) } }

@Preview(name = "Create dialog · Light", widthDp = 412, heightDp = 956)
@Preview(name = "Create dialog · Dark", widthDp = 412, heightDp = 956, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RoutineCreatePreview() {
    RoutinePreviewTheme {
        RoutineDialog(stringResource(R.string.home_create_workout_title), stringResource(R.string.home_create_workout_message),
            Symbols.Default.Add, stringResource(R.string.routine_create_action), onConfirm = {}, onDismiss = {}) {
            RoutineNameField("Push Day", {})
        }
    }
}

@Preview(name = "Delete dialog · Light", widthDp = 412, heightDp = 956)
@Preview(name = "Delete dialog · Dark", widthDp = 412, heightDp = 956, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RoutineDeletePreview() {
    RoutinePreviewTheme {
        WorkoutDetailContent(WorkoutDetailState(previewRoutine, isLoading = false), 24)
        RoutineDialog(stringResource(R.string.dialog_delete_workout), stringResource(R.string.routine_delete_body, "Push Day"),
            Symbols.Default.Delete, stringResource(R.string.routine_delete_action), onConfirm = {}, onDismiss = {}, destructive = true)
    }
}

@Preview(name = "Muscle sheet · Light", widthDp = 412, heightDp = 956)
@Preview(name = "Muscle sheet · Dark", widthDp = 412, heightDp = 956, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun RoutineFilterPreview() {
    RoutinePreviewTheme { PickerPreview(true); MuscleFilterSheet(ExerciseCategory.CHEST, {}, {}) }
}
