package com.diajarkoding.imfit.presentation.ui.progress

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.theme.IMFITShapes
import com.diajarkoding.imfit.theme.IMFITSizes
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.Primary
import com.diajarkoding.imfit.theme.PrimaryLight
import com.diajarkoding.imfit.theme.SetComplete
import com.diajarkoding.imfit.R
import androidx.compose.ui.res.stringResource
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkoutHistoryDetailScreen(
    date: String,
    onNavigateBack: () -> Unit,
    viewModel: ProgressViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val localDate = try {
        LocalDate.parse(date)
    } catch (e: Exception) {
        LocalDate.now()
    }
    
    val workoutsForDate = state.workoutLogsByDate[localDate] ?: emptyList()
    val dayName = localDate.format(DateTimeFormatter.ofPattern("EEEE"))
    val formattedDate = localDate.format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))

    LaunchedEffect(Unit) {
        viewModel.loadData()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
                title = {
                    Column {
                        Text(
                            text = dayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = formattedDate,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                windowInsets = WindowInsets(0)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(IMFITSpacing.screenHorizontal),
            verticalArrangement = Arrangement.spacedBy(IMFITSpacing.md)
        ) {
            item { Spacer(modifier = Modifier.height(IMFITSpacing.xs)) }

            if (workoutsForDate.isEmpty()) {
                item { EmptyWorkoutCard() }
            } else {
                item {
                    DaySummaryCard(workouts = workoutsForDate)
                }

                itemsIndexed(workoutsForDate) { index, workoutLog ->
                    WorkoutLogCard(
                        workoutLog = workoutLog,
                        workoutNumber = index + 1,
                        totalWorkouts = workoutsForDate.size
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(100.dp)) }
        }
    }
}

@Composable
private fun DaySummaryCard(workouts: List<WorkoutLog>) {
    val totalDuration = workouts.sumOf { it.durationMinutes }
    val totalVolume = workouts.sumOf { it.totalVolume.toDouble() }.toFloat()
    val totalExercises = workouts.sumOf { it.exerciseLogs.size }
    val totalSets = workouts.sumOf { log -> log.exerciseLogs.sumOf { it.completedSets } }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, IMFITShapes.Card, spotColor = Primary.copy(alpha = 0.2f)),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Primary.copy(alpha = 0.1f), Primary.copy(alpha = 0.02f))
                    )
                )
        ) {
            Column(
                modifier = Modifier.padding(IMFITSpacing.cardPaddingLarge)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(Primary, PrimaryLight))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocalFireDepartment,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(IMFITSpacing.md))
                    Column {
                        Text(
                            text = stringResource(R.string.summary_day),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (workouts.size > 1) stringResource(R.string.summary_workouts_completed_plural, workouts.size) else stringResource(R.string.summary_workouts_completed, workouts.size),
                            style = MaterialTheme.typography.bodySmall,
                            color = SetComplete
                        )
                    }
                }

                Spacer(modifier = Modifier.height(IMFITSpacing.lg))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    SummaryStatItem(
                        value = "${totalDuration}",
                        unit = "min",
                        label = stringResource(R.string.label_duration),
                        icon = Icons.Default.Timer
                    )
                    SummaryStatItem(
                        value = String.format("%.0f", totalVolume),
                        unit = "kg",
                        label = stringResource(R.string.label_volume),
                        icon = Icons.Default.FitnessCenter
                    )
                    SummaryStatItem(
                        value = "$totalExercises",
                        unit = "",
                        label = stringResource(R.string.label_exercises),
                        icon = Icons.Default.CheckCircle
                    )
                    SummaryStatItem(
                        value = "$totalSets",
                        unit = "",
                        label = stringResource(R.string.label_sets),
                        icon = Icons.Default.AccessTime
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryStatItem(
    value: String,
    unit: String,
    label: String,
    icon: ImageVector
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp)
            )
        }
        Spacer(modifier = Modifier.height(IMFITSpacing.xs))
        Row(
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (unit.isNotEmpty()) {
                Text(
                    text = unit,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 2.dp)
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun WorkoutLogCard(
    workoutLog: WorkoutLog,
    workoutNumber: Int,
    totalWorkouts: Int
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(4.dp, IMFITShapes.Card, spotColor = Color.Black.copy(alpha = 0.08f)),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(IMFITSpacing.cardPaddingLarge)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(IMFITShapes.IconContainer)
                            .background(Brush.linearGradient(listOf(Primary, PrimaryLight))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FitnessCenter,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(IMFITSizes.iconSm)
                        )
                    }
                    Spacer(modifier = Modifier.width(IMFITSpacing.md))
                    Column {
                        Text(
                            text = workoutLog.templateName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (totalWorkouts > 1) {
                            Text(
                                text = stringResource(R.string.workout_number_of_total, workoutNumber, totalWorkouts),
                                style = MaterialTheme.typography.labelSmall,
                                color = Primary
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(SetComplete.copy(alpha = 0.1f))
                        .padding(horizontal = IMFITSpacing.sm, vertical = IMFITSpacing.xs)
                ) {
                    Text(
                        text = workoutLog.formattedDuration,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = SetComplete
                    )
                }
            }

            Spacer(modifier = Modifier.height(IMFITSpacing.lg))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)
            ) {
                item {
                    StatChip(
                        icon = Icons.Default.FitnessCenter,
                        value = workoutLog.formattedVolume,
                        label = "Volume"
                    )
                }
                item {
                    StatChip(
                        icon = Icons.Default.CheckCircle,
                        value = "${workoutLog.exerciseLogs.size}",
                        label = "Exercises"
                    )
                }
                item {
                    StatChip(
                        icon = Icons.Default.AccessTime,
                        value = "${workoutLog.exerciseLogs.sumOf { it.completedSets }}",
                        label = stringResource(R.string.label_total_sets)
                    )
                }
            }

            if (workoutLog.exerciseLogs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(IMFITSpacing.lg))
                
                Text(
                    text = stringResource(R.string.summary_exercise_breakdown),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(IMFITSpacing.sm))

                Column(
                    verticalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)
                ) {
                    workoutLog.exerciseLogs.forEach { exerciseLog ->
                        ExerciseProgressRow(exerciseLog = exerciseLog)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatChip(
    icon: ImageVector,
    value: String,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .padding(horizontal = IMFITSpacing.md, vertical = IMFITSpacing.sm)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Primary,
            modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(IMFITSpacing.xs))
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ExerciseProgressRow(exerciseLog: ExerciseLog) {
    val progress by animateFloatAsState(
        targetValue = exerciseLog.completedSets.toFloat() / 3f.coerceAtLeast(1f),
        animationSpec = tween(800),
        label = "progress"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier.padding(IMFITSpacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = exerciseLog.exercise.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${exerciseLog.completedSets} sets",
                    style = MaterialTheme.typography.labelMedium,
                    color = Primary,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(modifier = Modifier.height(IMFITSpacing.xs))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = SetComplete,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round
                )
                Spacer(modifier = Modifier.width(IMFITSpacing.md))
                Text(
                    text = "${String.format("%.0f", exerciseLog.totalVolume)} kg",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyWorkoutCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IMFITSpacing.xxxl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.CalendarMonth,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(modifier = Modifier.height(IMFITSpacing.lg))
            Text(
                text = stringResource(R.string.summary_rest_day),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(IMFITSpacing.xs))
            Text(
                text = stringResource(R.string.summary_no_workouts),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}
