package com.diajarkoding.imfit.presentation.ui.workout.views

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import com.diajarkoding.imfit.presentation.components.workout.AddExerciseCard
import com.diajarkoding.imfit.presentation.components.workout.WorkoutContentTabs
import com.diajarkoding.imfit.presentation.components.workout.WorkoutDayHeader
import com.diajarkoding.imfit.presentation.components.workout.WorkoutHeroSection
import com.diajarkoding.imfit.presentation.ui.workout.WorkoutDay

@Composable
fun WorkoutDayDetailView(
    day: WorkoutDay,
    planTitle: String,
    planImageUrl: String,
    isDropdownExpanded: Boolean,
    onMoreClick: () -> Unit,
    onAddExercise: () -> Unit,
    onTabSelected: (Int) -> Unit,
    onDismissDropdown: () -> Unit,
    onEditDayClicked: () -> Unit
) {
    LazyColumn {
        item {
            WorkoutHeroSection(
                title = planTitle,
                imageUrl = planImageUrl,
                onAllPlansClick = {}, onShareClick = {}, onMoreClick = {}
            )
        }
        item { WorkoutContentTabs(selectedTabIndex = 1, onTabSelected = onTabSelected) }
        item {
            WorkoutDayHeader(
                dayTitle = day.title,
                estimatedTime = day.estimatedTime,
                exerciseCount = day.exerciseCount.toIntOrNull() ?: 0,
                onMoreClick = onMoreClick,
                isDropdownExpanded = isDropdownExpanded,
                onDismissDropdown = onDismissDropdown,
                onEditExercises = onEditDayClicked,
                onCopyDay = { /* TODO */ },
                onDeleteDay = { /* TODO */ }
            )
        }
        item {
            AddExerciseCard(onAddExercise = onAddExercise)
        }
    }
}