package com.diajarkoding.imfit.presentation.ui.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.components.TwoToneTitle
import com.diajarkoding.imfit.presentation.components.workout.AddedExerciseItem
import com.diajarkoding.imfit.presentation.components.workout.DeleteDayButton
import com.diajarkoding.imfit.presentation.components.workout.EditAddExerciseCard
import com.diajarkoding.imfit.presentation.components.workout.EditDayHeader
import com.diajarkoding.imfit.presentation.ui.workout.viewmodel.EditWorkoutDayEvent
import com.diajarkoding.imfit.presentation.ui.workout.viewmodel.EditWorkoutDayViewModel
import com.diajarkoding.imfit.theme.IMFITSpacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditWorkoutDayScreen(
    navController: NavController,
    onBackClick: () -> Unit,
    // onSaveClick tidak lagi diperlukan sebagai parameter
    viewModel: EditWorkoutDayViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    // Mengambil hasil dari AddExercisesScreen
    val newExerciseIdsState = navController.currentBackStackEntry?.savedStateHandle
        ?.getLiveData<List<String>>("selected_exercise_ids")?.observeAsState()

    LaunchedEffect(newExerciseIdsState) {
        newExerciseIdsState?.value?.let { ids ->
            viewModel.onEvent(EditWorkoutDayEvent.OnAddExercises(ids))
            navController.currentBackStackEntry?.savedStateHandle?.remove<List<String>>("selected_exercise_ids")
        }
    }

    // Navigasi kembali setelah delete atau save sukses
    LaunchedEffect(state.deleteSuccess, state.saveSuccess) {
        if (state.deleteSuccess || state.saveSuccess) {
            onBackClick()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TwoToneTitle(text = stringResource(R.string.workout_edit_day_title)) },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.onEvent(EditWorkoutDayEvent.OnSaveClicked) }) {
                        Text(
                            stringResource(R.string.workout_edit_day_save),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues) // Gunakan padding dari Scaffold lokal
                .background(MaterialTheme.colorScheme.background)
        ) {
            EditDayHeader(
                dayName = state.dayName,
                onDayNameChange = { newName ->
                    viewModel.onEvent(
                        EditWorkoutDayEvent.OnDayNameChanged(
                            newName
                        )
                    )
                },
                workoutTitle = "", // TODO: Ambil dari ViewModel
                onCloseClick = onBackClick
            )

            LazyColumn(modifier = Modifier.weight(1f)) {
                item {
                    Spacer(modifier = Modifier.height(IMFITSpacing.lg))
                }
                items(state.exercises) { exercise ->
                    AddedExerciseItem(
                        exercise = exercise,
                        onDelete = { viewModel.onEvent(EditWorkoutDayEvent.OnDeleteExercise(exercise.id)) }
                    )
                }
                item {
                    EditAddExerciseCard(onAddExercise = {
                        // val dayId = viewModel.dayId // ViewModel perlu expose dayId
                        // navController.navigate(Routes.addExercises(dayId))
                    })
                }
            }

            DeleteDayButton(
                onDeleteDay = { viewModel.onEvent(EditWorkoutDayEvent.OnDeleteDayClicked) },
                modifier = Modifier.padding(bottom = IMFITSpacing.xl)
            )
        }
    }
}