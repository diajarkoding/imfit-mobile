package com.diajarkoding.imfit.presentation.ui.workout.views

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.components.workout.WorkoutActionButton
import com.diajarkoding.imfit.presentation.components.workout.WorkoutEmptyStateIllustration
import com.diajarkoding.imfit.presentation.components.workout.WorkoutHeroSection
import com.diajarkoding.imfit.theme.IMFITSpacing

@Composable
fun AddWorkoutView(title: String, imageUrl: String, onAddDay: () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            WorkoutHeroSection(
                title = title,
                imageUrl = imageUrl,
                onAllPlansClick = {},
                onShareClick = {},
                onMoreClick = {}
            )
        }
        item { Spacer(modifier = Modifier.height(IMFITSpacing.xxl)) }
        item { WorkoutEmptyStateIllustration() }
        item { Spacer(modifier = Modifier.height(IMFITSpacing.xxl)) }
        item { AddWorkoutDayContent(onAddDay = onAddDay) }
    }
}

@Composable
private fun AddWorkoutDayContent(onAddDay: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(R.string.workout_add_day_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = Color.Black,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Text(
            text = stringResource(R.string.workout_add_day_desc),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 32.dp)
        )
        WorkoutActionButton(
            text = stringResource(R.string.workout_add_day_button),
            onClick = onAddDay,
            modifier = Modifier.fillMaxWidth()
        )
    }
}