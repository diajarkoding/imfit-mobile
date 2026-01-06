package com.diajarkoding.imfit.presentation.ui.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.presentation.components.common.IMFITButton
import com.diajarkoding.imfit.theme.IMFITShapes
import com.diajarkoding.imfit.theme.IMFITSizes
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.Primary
import com.diajarkoding.imfit.theme.SetComplete
import com.diajarkoding.imfit.R
import androidx.compose.ui.res.stringResource

import com.diajarkoding.imfit.presentation.components.common.ShimmerSummaryHeader
import com.diajarkoding.imfit.presentation.components.common.ShimmerSummaryStatCard
import com.diajarkoding.imfit.presentation.components.common.ShimmerExerciseSummaryCard

@Composable
fun WorkoutSummaryScreen(
    workoutLogId: String,
    onNavigateToHome: () -> Unit,
    viewModel: WorkoutSummaryViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(workoutLogId) {
        viewModel.loadWorkoutLog(workoutLogId)
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(IMFITSpacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(IMFITSpacing.lg)
        ) {
            if (state.isLoading) {
                // Shimmer loading state
                item { ShimmerSummaryHeader() }
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md)
                    ) {
                        ShimmerSummaryStatCard(modifier = Modifier.weight(1f))
                        ShimmerSummaryStatCard(modifier = Modifier.weight(1f))
                    }
                }
                item {
                    Text(
                        text = stringResource(R.string.summary_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = IMFITSpacing.sm)
                    )
                }
                items(3, key = { it }) { ShimmerExerciseSummaryCard() }
            } else {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = IMFITSpacing.xxl),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(88.dp)
                                .shadow(12.dp, CircleShape, spotColor = SetComplete.copy(alpha = 0.4f))
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(SetComplete, SetComplete.copy(alpha = 0.8f))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(IMFITSizes.iconXxl)
                            )
                        }

                        Spacer(modifier = Modifier.height(IMFITSpacing.xxl))

                        Text(
                            text = stringResource(R.string.summary_great_work),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onBackground
                        )

                        Spacer(modifier = Modifier.height(IMFITSpacing.sm))

                        state.workoutLog?.let { log ->
                            Text(
                                text = log.templateName,
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md)
                    ) {
                        SummaryStatCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.Timer,
                            value = state.workoutLog?.formattedDuration ?: stringResource(R.string.workout_duration_zero),
                            label = stringResource(R.string.label_duration)
                        )
                        SummaryStatCard(
                            modifier = Modifier.weight(1f),
                            icon = Icons.Default.FitnessCenter,
                            value = state.workoutLog?.formattedVolume ?: stringResource(R.string.workout_volume_zero),
                            label = stringResource(R.string.label_volume)
                        )
                    }
                }

                item {
                    Text(
                        text = stringResource(R.string.summary_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = IMFITSpacing.sm)
                    )
                }

                state.workoutLog?.exerciseLogs?.let { exerciseLogs ->
                    exerciseLogs.forEachIndexed { index, exerciseLog ->
                        item(key = "summary_${index}_${exerciseLog.exercise.id}") {
                            ExerciseSummaryCard(exerciseLog = exerciseLog)
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(IMFITSpacing.lg))
                    IMFITButton(
                        text = stringResource(R.string.action_done),
                        onClick = onNavigateToHome,
                        icon = Icons.Default.CheckCircle
                    )
                    Spacer(modifier = Modifier.height(IMFITSpacing.xxl))
                }
            }
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun WorkoutSummaryScreenPreview() {
    com.diajarkoding.imfit.theme.IMFITTheme(darkTheme = false) {
        WorkoutSummaryScreen(
            workoutLogId = "preview-log",
            onNavigateToHome = {}
        )
    }
}

@Composable
private fun SummaryStatCard(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    value: String,
    label: String
) {
    Card(
        modifier = modifier
            .shadow(6.dp, IMFITShapes.Card, spotColor = Primary.copy(alpha = 0.15f)),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Primary.copy(alpha = 0.1f), Primary.copy(alpha = 0.03f))
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(IMFITSpacing.cardPadding),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(IMFITShapes.IconContainer)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(IMFITSizes.iconSm)
                    )
                }

                Spacer(modifier = Modifier.height(IMFITSpacing.md))

                Text(
                    text = value,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(IMFITSpacing.xs))

                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun ExerciseSummaryCard(exerciseLog: ExerciseLog) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IMFITSpacing.cardPadding)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(IMFITShapes.IconContainer)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FitnessCenter,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(IMFITSizes.iconSm)
                        )
                    }
                    Spacer(modifier = Modifier.width(IMFITSpacing.md))
                    Column {
                        Text(
                            text = exerciseLog.exercise.name,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(id = exerciseLog.exercise.muscleCategory.stringResourceId),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = stringResource(R.string.summary_sets_count, exerciseLog.completedSets),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Primary
                    )
                    Text(
                        text = stringResource(R.string.summary_volume_kg, String.format("%.0f", exerciseLog.totalVolume)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (exerciseLog.sets.any { it.isCompleted }) {
                Spacer(modifier = Modifier.height(IMFITSpacing.md))
                
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(IMFITShapes.Chip)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(IMFITSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(IMFITSpacing.xs)
                ) {
                    exerciseLog.sets.filter { it.isCompleted }.forEach { set ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = stringResource(R.string.active_workout_set_number, set.setNumber),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = stringResource(R.string.summary_set_details, set.weight.toInt(), set.reps),
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}
