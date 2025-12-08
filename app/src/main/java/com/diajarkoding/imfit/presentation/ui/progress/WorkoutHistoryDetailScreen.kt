package com.diajarkoding.imfit.presentation.ui.progress

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.diajarkoding.imfit.domain.model.ExerciseLog
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.theme.IMFITShapes
import com.diajarkoding.imfit.theme.IMFITSizes
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.Primary
import com.diajarkoding.imfit.theme.PrimaryLight
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

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
    val formattedDate = localDate.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"))

    LaunchedEffect(Unit) {
        viewModel.loadData()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
                title = {
                    Text(
                        text = "Workout History",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            verticalArrangement = Arrangement.spacedBy(IMFITSpacing.lg)
        ) {
            item {
                Spacer(modifier = Modifier.height(IMFITSpacing.sm))
                DateHeader(formattedDate = formattedDate, workoutCount = workoutsForDate.size)
            }

            if (workoutsForDate.isEmpty()) {
                item {
                    EmptyWorkoutCard()
                }
            } else {
                items(workoutsForDate) { workoutLog ->
                    WorkoutLogCard(workoutLog = workoutLog)
                }
            }

            item { Spacer(modifier = Modifier.height(100.dp)) }
        }
    }
}

@Composable
private fun DateHeader(formattedDate: String, workoutCount: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, IMFITShapes.Card, spotColor = Primary.copy(alpha = 0.15f)),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.horizontalGradient(
                        listOf(Primary.copy(alpha = 0.08f), PrimaryLight.copy(alpha = 0.04f))
                    )
                )
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(IMFITSpacing.cardPaddingLarge)
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(Primary, PrimaryLight))
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(IMFITSizes.iconLg)
                    )
                }
                Spacer(modifier = Modifier.width(IMFITSpacing.lg))
                Column {
                    Text(
                        text = formattedDate,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(IMFITSpacing.xs))
                    Text(
                        text = "$workoutCount workout${if (workoutCount != 1) "s" else ""} completed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkoutLogCard(workoutLog: WorkoutLog) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IMFITSpacing.cardPaddingLarge)
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
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.FitnessCenter,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(IMFITSizes.iconSm)
                        )
                    }
                    Spacer(modifier = Modifier.width(IMFITSpacing.md))
                    Text(
                        text = workoutLog.templateName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(IMFITSpacing.lg))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.lg)
            ) {
                SummaryChip(
                    icon = Icons.Default.Timer,
                    value = workoutLog.formattedDuration,
                    label = "Duration"
                )
                SummaryChip(
                    icon = Icons.Default.FitnessCenter,
                    value = workoutLog.formattedVolume,
                    label = "Volume"
                )
            }

            if (workoutLog.exerciseLogs.isNotEmpty()) {
                Spacer(modifier = Modifier.height(IMFITSpacing.lg))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(IMFITShapes.Chip)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(IMFITSpacing.md),
                    verticalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)
                ) {
                    workoutLog.exerciseLogs.forEach { exerciseLog ->
                        ExerciseLogRow(exerciseLog = exerciseLog)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryChip(
    icon: ImageVector,
    value: String,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(IMFITShapes.Chip)
            .background(Primary.copy(alpha = 0.08f))
            .padding(horizontal = IMFITSpacing.md, vertical = IMFITSpacing.sm)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Primary,
            modifier = Modifier.size(IMFITSizes.iconSm)
        )
        Spacer(modifier = Modifier.width(IMFITSpacing.sm))
        Column {
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
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
private fun ExerciseLogRow(exerciseLog: ExerciseLog) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = exerciseLog.exercise.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = "${exerciseLog.completedSets} sets • ${String.format("%.0f", exerciseLog.totalVolume)} kg",
            style = MaterialTheme.typography.bodySmall,
            color = Primary,
            fontWeight = FontWeight.SemiBold
        )
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
                    .size(56.dp)
                    .clip(IMFITShapes.IconContainer)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.FitnessCenter,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(IMFITSizes.iconLg)
                )
            }
            Spacer(modifier = Modifier.height(IMFITSpacing.lg))
            Text(
                text = "No workouts on this day",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
