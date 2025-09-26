package com.diajarkoding.imfit.presentation.ui.workout.views

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.diajarkoding.imfit.presentation.components.workout.AddDaySection
import com.diajarkoding.imfit.presentation.components.workout.WorkoutContentTabs
import com.diajarkoding.imfit.presentation.components.workout.WorkoutDayCard
import com.diajarkoding.imfit.presentation.components.workout.WorkoutHeroSection
import com.diajarkoding.imfit.presentation.ui.workout.WorkoutDay
import com.diajarkoding.imfit.presentation.ui.workout.WorkoutPlan
import com.diajarkoding.imfit.presentation.ui.workout.WorkoutView
import com.diajarkoding.imfit.theme.IMFITSpacing

@Composable
fun PlannedWorkoutView(
    plan: WorkoutPlan,
    modifier: Modifier = Modifier,
    currentView: WorkoutView,
    isDropdownExpanded: Boolean,
    onDayClick: (WorkoutDay) -> Unit,
    onTabSelected: (Int) -> Unit,
    onAddDay: () -> Unit,
    onMoreMenuClick: (WorkoutDay) -> Unit,
    onDismissDropdown: () -> Unit,
    onEditDayClicked: (WorkoutDay) -> Unit,

    ) {
    Box(modifier = modifier.fillMaxSize()) {
        when (currentView) {
            is WorkoutView.Overview -> {
                // Tampilan Ringkasan (Daftar Hari)
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    item {
                        WorkoutHeroSection(
                            title = plan.title,
                            imageUrl = plan.imageUrl,
                            onAllPlansClick = {},
                            onShareClick = {},
                            onMoreClick = {})
                    }
                    item { WorkoutContentTabs(selectedTabIndex = 0, onTabSelected = onTabSelected) }
                    items(plan.days) { day ->
                        WorkoutDayCard(
                            workoutDay = day,
                            onCardClick = { onDayClick(day) },
                            onMoreClick = { /* Opsional: bisa juga buka menu dari sini */ },
                            modifier = Modifier.padding(
                                horizontal = IMFITSpacing.md,
                                vertical = IMFITSpacing.sm
                            )
                        )
                    }
                    item {
                        AddDaySection(
                            currentDays = plan.days.size,
                            maxDays = plan.maxDays,
                            onAddDay = onAddDay
                        )
                    }
                }
            }

            is WorkoutView.DayDetail -> {
                WorkoutDayDetailView(
                    day = currentView.day,
                    planTitle = plan.title,
                    planImageUrl = plan.imageUrl,
                    isDropdownExpanded = isDropdownExpanded,
                    onMoreClick = { onMoreMenuClick(currentView.day) },
                    onAddExercise = { /* TODO */ },
                    onTabSelected = onTabSelected,
                    onDismissDropdown = onDismissDropdown,
                    onEditDayClicked = { onEditDayClicked(currentView.day) }
                )
            }
        }
    }
}