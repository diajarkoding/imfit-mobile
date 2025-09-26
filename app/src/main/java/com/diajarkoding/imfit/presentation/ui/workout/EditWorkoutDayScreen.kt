package com.diajarkoding.imfit.presentation.ui.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.diajarkoding.imfit.presentation.components.workout.DeleteDayButton
import com.diajarkoding.imfit.presentation.components.workout.EditAddExerciseCard
import com.diajarkoding.imfit.presentation.components.workout.EditDayHeader
import com.diajarkoding.imfit.theme.IMFITSpacing

@Composable
fun EditWorkoutDayScreen(
    dayName: String,
    workoutTitle: String,
    onBackClick: () -> Unit,
    onSaveClick: () -> Unit,
    onAddExercise: () -> Unit,
    onDeleteDay: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
//        EditWorkoutTopAppBar(
//            onBackClick = onBackClick,
//            onHelpClick = { /* TODO */ },
//            onSaveClick = onSaveClick
//        )
        EditDayHeader(
            dayName = dayName,
            workoutTitle = workoutTitle,
            onCloseClick = onBackClick
        )
        Spacer(modifier = Modifier.height(IMFITSpacing.lg))
        EditAddExerciseCard(onAddExercise = onAddExercise)
        Spacer(modifier = Modifier.weight(1f))
        DeleteDayButton(
            onDeleteDay = onDeleteDay,
            modifier = Modifier.padding(bottom = IMFITSpacing.xl)
        )
    }
}