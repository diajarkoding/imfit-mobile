package com.diajarkoding.imfit.presentation.ui.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.diajarkoding.imfit.theme.IMFITSpacing

// --- Layar Utama ---
@Composable
fun WorkoutScreen(
    modifier: Modifier = Modifier,
    viewModel: WorkoutViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    Column(modifier = modifier.fillMaxSize()) {
        // Ini hanya untuk simulasi, bisa dihapus nanti
//        Button(onClick = { viewModel.simulateStateChange() }, modifier = Modifier.fillMaxWidth()) {
//            Text("Simulate Next State")
//        }

//        WorkoutTabRow(selectedTabIndex = state.selectedMainTabIndex, onTabSelected = { /* TODO */ })

        when (val uiState = state.uiState) {
            is WorkoutUiState.Empty -> EmptyStateContent(
                onFindPlan = { /* TODO */ },
                onCreateFromScratch = { viewModel.simulateStateChange() }
            )

            is WorkoutUiState.AddWorkout -> AddWorkoutStateContent(
                title = uiState.planTitle,
                onAddDay = { viewModel.simulateStateChange() }
            )

            is WorkoutUiState.PlannedWorkout -> PlannedWorkoutStateContent(
                plan = uiState.plan,
                selectedTabIndex = state.selectedContentTabIndex,
                onTabSelected = { /* TODO */ },
                onAddDay = { viewModel.simulateStateChange() }
            )
        }
    }
}

// --- Konten untuk setiap kondisi ---
@Composable
fun EmptyStateContent(onFindPlan: () -> Unit, onCreateFromScratch: () -> Unit) {
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
fun AddWorkoutStateContent(title: String, onAddDay: () -> Unit) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            WorkoutHeroSection(
                title = title,
                onAllPlansClick = {},
                onShareClick = {},
                onMoreClick = {})
        }
        item { Spacer(modifier = Modifier.height(IMFITSpacing.lg)) }
        item { AddWorkoutDayContent(onAddDay = onAddDay) }
    }
}

@Composable
fun PlannedWorkoutStateContent(
    plan: WorkoutPlan,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    onAddDay: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            WorkoutHeroSection(
                title = plan.title,
                onAllPlansClick = {},
                onShareClick = {},
                onMoreClick = {})
        }
        item {
            WorkoutContentTabs(
                selectedTabIndex = selectedTabIndex,
                onTabSelected = onTabSelected
            )
        }

        items(plan.days) { day ->
            WorkoutDayCard(
                workoutDay = day,
                onCardClick = { /* TODO */ },
                onMoreClick = { /* TODO */ },
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

// --- Komponen-komponen Reusable (sesuai analisis Anda) ---

@Composable
fun WorkoutTabRow(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = listOf("Find", "Planned", "Instant")

    TabRow(
        selectedTabIndex = selectedTabIndex,
        modifier = modifier,
        containerColor = Color.White,
        contentColor = Color.Black,
        indicator = { tabPositions ->
            TabRowDefaults.Indicator(
                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                color = Color(0xFF4285F4),
                height = 3.dp
            )
        }
    ) {
        tabs.forEachIndexed { index, title ->
            Tab(
                selected = selectedTabIndex == index,
                onClick = { onTabSelected(index) },
                text = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selectedTabIndex == index) FontWeight.Medium else FontWeight.Normal,
                        color = if (selectedTabIndex == index) Color.Black else Color.Gray
                    )
                }
            )
        }
    }
}

@Composable
fun WorkoutEmptyStateIllustration(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Row 1 - 3 dots
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EmptyStateDot(size = 12.dp, color = Color(0xFFE3F2FD))
            EmptyStateDot(size = 100.dp, color = Color(0xFF2196F3), isBar = true)
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Row 2
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EmptyStateDot(size = 12.dp, color = Color(0xFFE3F2FD))
            EmptyStateDot(size = 80.dp, color = Color(0xFF2196F3), isBar = true)
        }

        // Continue pattern...
    }
}

@Composable
fun EmptyStateDot(
    size: Dp,
    color: Color,
    isBar: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(
                width = if (isBar) size else 12.dp,
                height = if (isBar) 4.dp else 12.dp
            )
            .background(
                color = color,
                shape = if (isBar) RoundedCornerShape(2.dp) else CircleShape
            )
    )
}

@Composable
fun EmptyStateActions(
    onFindPlan: () -> Unit,
    onCreateFromScratch: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Let's get your first workout plan!",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = Color.Black,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        WorkoutActionButton(
            text = "Find a Plan",
            onClick = onFindPlan,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(16.dp))

        WorkoutActionButton(
            text = "Create from scratch",
            onClick = onCreateFromScratch,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun WorkoutActionButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF4285F4)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = Color.White
        )
    }
}

@Composable
fun WorkoutHeroSection(
    title: String,
    onAllPlansClick: () -> Unit,
    onShareClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(200.dp)
    ) {
        // Background image with overlay
        AsyncImage(
            model = "workout_hero_image",
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        // Dark overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.3f),
                            Color.Black.copy(alpha = 0.6f)
                        )
                    )
                )
        )

        // Top actions
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.End
        ) {
            IconButton(onClick = onShareClick) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = "Share",
                    tint = Color.White
                )
            }
            IconButton(onClick = onMoreClick) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "More",
                    tint = Color.White
                )
            }
        }

        // Bottom content
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Button(
                onClick = onAllPlansClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Black.copy(alpha = 0.7f)
                ),
                shape = RoundedCornerShape(20.dp)
            ) {
                Text(
                    text = "All Plans",
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun AddWorkoutDayContent(
    onAddDay: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Add a Workout Day",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = Color.Black,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = "Build your workout, track your progress and reach your fitness goals today.",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = Color.Gray,
            modifier = Modifier.padding(bottom = 32.dp)
        )

        WorkoutActionButton(
            text = "Add a day",
            onClick = onAddDay,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
fun WorkoutContentTabs(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = listOf("Overview", "Day Details")

    TabRow(
        selectedTabIndex = selectedTabIndex,
        modifier = modifier,
        containerColor = Color.Transparent,
        indicator = { tabPositions ->
            TabRowDefaults.Indicator(
                modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTabIndex]),
                color = Color.Black,
                height = 2.dp
            )
        }
    ) {
        tabs.forEachIndexed { index, title ->
            Tab(
                selected = selectedTabIndex == index,
                onClick = { onTabSelected(index) },
                text = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (selectedTabIndex == index) FontWeight.Medium else FontWeight.Normal,
                        color = if (selectedTabIndex == index) Color.Black else Color.Gray
                    )
                }
            )
        }
    }
}

@Composable
fun WorkoutDayCard(
    workoutDay: WorkoutDay,
    onCardClick: () -> Unit,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onCardClick() },
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFF5F5F5)
        ),
        shape = RoundedCornerShape(8.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left blue indicator
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(48.dp)
                    .background(
                        color = Color(0xFF4285F4),
                        shape = RoundedCornerShape(2.dp)
                    )
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = Color.White,
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
//                Icon(
//                    painter = painterResource(R.drawable.ic_dumbbell),
//                    contentDescription = null,
//                    tint = Color(0xFF4285F4),
//                    modifier = Modifier.size(24.dp)
//                )
                Icons.Default.FitnessCenter
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Content
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = workoutDay.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = Color.Black
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = workoutDay.estimatedTime,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        modifier = Modifier.padding(start = 4.dp)
                    )

                    Spacer(modifier = Modifier.width(16.dp))

                    Icon(
                        Icons.Default.FitnessCenter,
                        contentDescription = null,
                        tint = Color.Gray,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = workoutDay.exerciseCount,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }

                Text(
                    text = workoutDay.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )
            }

            // Actions
            Row {
                IconButton(onClick = onMoreClick) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = Color.Gray
                    )
                }

                IconButton(onClick = onCardClick) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "View details",
                        tint = Color(0xFF4285F4)
                    )
                }
            }
        }
    }
}

@Composable
fun AddDaySection(
    currentDays: Int,
    maxDays: Int,
    onAddDay: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Day Limit • $maxDays days",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )

        TextButton(
            onClick = onAddDay,
            colors = ButtonDefaults.textButtonColors(
                contentColor = Color(0xFF4285F4)
            )
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = "Add a day",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
