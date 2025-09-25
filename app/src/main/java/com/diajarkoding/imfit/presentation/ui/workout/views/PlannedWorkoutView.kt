package com.diajarkoding.imfit.presentation.ui.workout.views

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.diajarkoding.imfit.presentation.components.workout.AddDaySection
import com.diajarkoding.imfit.presentation.components.workout.WorkoutContentTabs
import com.diajarkoding.imfit.presentation.components.workout.WorkoutDayCard
import com.diajarkoding.imfit.presentation.components.workout.WorkoutEmptyStateIllustration
import com.diajarkoding.imfit.presentation.components.workout.WorkoutHeroSection
import com.diajarkoding.imfit.presentation.ui.workout.WorkoutPlan
import com.diajarkoding.imfit.theme.IMFITSpacing

@Composable
fun PlannedWorkoutView(
    plan: WorkoutPlan,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    onAddDay: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            WorkoutHeroSection(
                title = plan.title,
                imageUrl = plan.imageUrl,
                onAllPlansClick = {},
                onShareClick = {},
                onMoreClick = {}
            )
        }
        item { WorkoutContentTabs(selectedTabIndex = selectedTabIndex, onTabSelected = onTabSelected) }
        items(plan.days) { day ->
            WorkoutDayCard(
                workoutDay = day,
                onCardClick = { /* TODO */ },
                onMoreClick = { /* TODO */ },
                modifier = Modifier.padding(horizontal = IMFITSpacing.md, vertical = IMFITSpacing.sm)
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