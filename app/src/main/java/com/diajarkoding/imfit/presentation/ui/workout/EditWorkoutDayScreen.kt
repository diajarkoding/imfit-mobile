package com.diajarkoding.imfit.presentation.ui.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.diajarkoding.imfit.presentation.components.workout.AddedExerciseItem
import com.diajarkoding.imfit.presentation.components.workout.DeleteDayButton
import com.diajarkoding.imfit.presentation.components.workout.EditAddExerciseCard
import com.diajarkoding.imfit.presentation.components.workout.EditDayHeader
import com.diajarkoding.imfit.presentation.ui.workout.viewmodel.EditWorkoutDayEvent
import com.diajarkoding.imfit.presentation.ui.workout.viewmodel.EditWorkoutDayViewModel
import com.diajarkoding.imfit.theme.IMFITSpacing

@Composable
fun EditWorkoutDayScreen(
    navController: NavController,
    workoutTitle: String,
    onBackClick: () -> Unit,
    viewModel: EditWorkoutDayViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    // Mengambil hasil dari AddExercisesScreen
    val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
    val newExerciseIds by savedStateHandle?.getLiveData<List<String>>("selected_exercise_ids")?.observeAsState()

    LaunchedEffect(newExerciseIds) {
        newExerciseIds?.let { ids ->
            viewModel.onEvent(EditWorkoutDayEvent.OnAddExercises(ids))
            savedStateHandle.remove<List<String>>("selected_exercise_ids")
        }
    }

    // Navigasi kembali setelah delete atau save sukses
    LaunchedEffect(state.deleteSuccess, state.saveSuccess) {
        if (state.deleteSuccess || state.saveSuccess) {
            onBackClick()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        EditDayHeader(
            dayName = state.dayName,
            onDayNameChange = { newName -> viewModel.onEvent(EditWorkoutDayEvent.OnDayNameChanged(newName)) },
            workoutTitle = workoutTitle,
            onCloseClick = onBackClick
        )

        // Daftar latihan yang bisa di-scroll
        LazyColumn(modifier = Modifier.weight(1f)) {
            item {
                Spacer(modifier = Modifier.height(IMFITSpacing.lg))
            }
            items(state.exercises) { exercise ->
                // TODO: Tambahkan tombol hapus di AddedExerciseItem
                AddedExerciseItem(exercise = exercise)
            }
            item {
                EditAddExerciseCard(onAddExercise = {
                    // Navigasi ke AddExercisesScreen
                    // navController.navigate(Routes.addExercises(dayId)) // dayId perlu di-pass ke ViewModel
                })
            }
        }

        DeleteDayButton(
            onDeleteDay = { viewModel.onEvent(EditWorkoutDayEvent.OnDeleteDayClicked) },
            modifier = Modifier.padding(bottom = IMFITSpacing.xl)
        )
    }
}