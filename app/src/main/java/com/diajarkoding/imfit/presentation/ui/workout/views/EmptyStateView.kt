package com.diajarkoding.imfit.presentation.ui.workout.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import com.diajarkoding.imfit.theme.IMFITSpacing

@Composable
fun EmptyStateView(onFindPlan: () -> Unit, onCreateFromScratch: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        WorkoutEmptyStateIllustration()
        Spacer(modifier = Modifier.height(IMFITSpacing.xxl))
        EmptyStateActions(onFindPlan = onFindPlan, onCreateFromScratch = onCreateFromScratch)
    }
}

@Composable
private fun EmptyStateActions(onFindPlan: () -> Unit, onCreateFromScratch: () -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = stringResource(R.string.workout_empty_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        WorkoutActionButton(
            text = stringResource(R.string.workout_empty_find_plan),
            onClick = onFindPlan,
            modifier = Modifier.fillMaxWidth()
        )
        WorkoutActionButton(
            text = stringResource(R.string.workout_empty_create_from_scratch),
            onClick = onCreateFromScratch,
            modifier = Modifier.fillMaxWidth()
        )
    }
}