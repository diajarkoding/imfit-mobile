package com.diajarkoding.imfit.presentation.ui.home

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.domain.model.WorkoutLog
import com.diajarkoding.imfit.domain.model.WorkoutTemplate
import com.diajarkoding.imfit.presentation.components.common.IMFITButton
import com.diajarkoding.imfit.presentation.components.common.IMFITInputDialog
import com.diajarkoding.imfit.presentation.components.common.ShimmerStatCard
import com.diajarkoding.imfit.presentation.components.common.ShimmerWelcomeSection
import com.diajarkoding.imfit.presentation.components.common.ShimmerWorkoutCard
import com.diajarkoding.imfit.theme.IMFITShapes
import com.diajarkoding.imfit.theme.IMFITSizes
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.Primary
import com.diajarkoding.imfit.theme.PrimaryLight
import com.diajarkoding.imfit.theme.SetComplete
import com.diajarkoding.imfit.presentation.components.common.SyncIndicator
import com.diajarkoding.imfit.presentation.components.common.SyncProgressDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToWorkoutDetail: (String) -> Unit,
    onNavigateToActiveWorkout: (String) -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val syncState by viewModel.syncState.collectAsState()
    var showAddDayDialog by remember { mutableStateOf(false) }
    var newDayName by remember { mutableStateOf("") }
    
    // Track last sync status to detect when sync completes
    var lastSyncStatus by remember { mutableStateOf(syncState.status) }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }
    
    // Refresh data when sync completes
    LaunchedEffect(syncState.status) {
        if (lastSyncStatus == com.diajarkoding.imfit.data.sync.SyncState.SyncStatus.SYNCING &&
            syncState.status == com.diajarkoding.imfit.data.sync.SyncState.SyncStatus.SYNCED) {
            // Sync just completed, refresh data
            viewModel.refresh()
        }
        lastSyncStatus = syncState.status
    }

    LaunchedEffect(state.newlyCreatedWorkoutId) {
        state.newlyCreatedWorkoutId?.let { workoutId ->
            viewModel.clearNewlyCreatedWorkoutId()
            onNavigateToWorkoutDetail(workoutId)
        }
    }

    if (showAddDayDialog) {
        IMFITInputDialog(
            onDismissRequest = {
                showAddDayDialog = false
                newDayName = ""
            },
            title = stringResource(R.string.home_create_workout_title),
            message = stringResource(R.string.home_create_workout_message),
            icon = Icons.Default.Add,
            confirmText = stringResource(R.string.action_create),
            dismissText = stringResource(R.string.action_cancel),
            confirmEnabled = newDayName.isNotBlank(),
            onConfirm = {
                if (newDayName.isNotBlank()) {
                    viewModel.createWorkout(newDayName)
                    showAddDayDialog = false
                    newDayName = ""
                }
            }
        ) {
            OutlinedTextField(
                value = newDayName,
                onValueChange = { newDayName = it },
                placeholder = {
                    Text(
                        stringResource(R.string.home_workout_placeholder),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                },
                singleLine = true,
                shape = IMFITShapes.TextField,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(IMFITShapes.IconContainer)
                                .background(
                                    Brush.linearGradient(listOf(Primary, PrimaryLight))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FitnessCenter,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(IMFITSizes.iconSm)
                            )
                        }
                        Spacer(modifier = Modifier.width(IMFITSpacing.md))
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                actions = {
                    SyncIndicator(
                        syncState = syncState,
                        showText = false
                    )
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
            if (state.isLoading) {
                item {
                    Spacer(modifier = Modifier.height(IMFITSpacing.xs))
                    ShimmerWelcomeSection()
                    Spacer(modifier = Modifier.height(IMFITSpacing.xs))
                }
                item { ShimmerStatCard() }
                item {
                    Spacer(modifier = Modifier.height(IMFITSpacing.sm))
                    Text(
                        text = stringResource(R.string.home_title_my_workouts),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(3, key = { it }) { ShimmerWorkoutCard() }
                item { Spacer(modifier = Modifier.height(IMFITSpacing.huge)) }
            } else {
                item {
                    Spacer(modifier = Modifier.height(IMFITSpacing.xs))
                    WelcomeSection(userName = state.userName)
                    Spacer(modifier = Modifier.height(IMFITSpacing.xs))
                }

                item {
                    LastWorkoutCard(workout = state.lastWorkout)
                }

                item {
                    Spacer(modifier = Modifier.height(IMFITSpacing.sm))
                    Text(
                        text = stringResource(R.string.home_title_my_workouts),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (state.templates.isEmpty()) {
                    item {
                        EmptyWorkoutCard(onClick = { showAddDayDialog = true })
                    }
                } else {
                    items(state.templates, key = { it.id }) { template ->
                        val isActive = state.activeWorkoutTemplateId == template.id
                        WorkoutCard(
                            template = template,
                            isActive = isActive,
                            onClick = {
                                if (isActive) {
                                    onNavigateToActiveWorkout(template.id)
                                } else {
                                    onNavigateToWorkoutDetail(template.id)
                                }
                            }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(IMFITSpacing.sm))
                    IMFITButton(
                        text = stringResource(R.string.action_add_workout),
                        onClick = { showAddDayDialog = true },
                        icon = Icons.Default.Add
                    )
                    Spacer(modifier = Modifier.height(IMFITSpacing.huge))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HomeScreenContent(
    userName: String,
    templates: List<WorkoutTemplate>,
    lastWorkout: WorkoutLog?,
    activeWorkoutTemplateId: String?,
    isLoading: Boolean,
    onNavigateToWorkoutDetail: (String) -> Unit,
    onNavigateToActiveWorkout: (String) -> Unit,
    onAddWorkoutClick: () -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(IMFITShapes.IconContainer)
                                .background(
                                    Brush.linearGradient(listOf(Primary, PrimaryLight))
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.FitnessCenter,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.size(IMFITSizes.iconSm)
                            )
                        }
                        Spacer(modifier = Modifier.width(IMFITSpacing.md))
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
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
            if (isLoading) {
                item {
                    Spacer(modifier = Modifier.height(IMFITSpacing.xs))
                    ShimmerWelcomeSection()
                    Spacer(modifier = Modifier.height(IMFITSpacing.xs))
                }
                item { ShimmerStatCard() }
                item {
                    Spacer(modifier = Modifier.height(IMFITSpacing.sm))
                    Text(
                        text = stringResource(R.string.home_title_my_workouts),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(3, key = { it }) { ShimmerWorkoutCard() }
                item { Spacer(modifier = Modifier.height(IMFITSpacing.huge)) }
            } else {
                item {
                    Spacer(modifier = Modifier.height(IMFITSpacing.xs))
                    WelcomeSection(userName = userName)
                    Spacer(modifier = Modifier.height(IMFITSpacing.xs))
                }

                item {
                    LastWorkoutCard(workout = lastWorkout)
                }

                item {
                    Spacer(modifier = Modifier.height(IMFITSpacing.sm))
                    Text(
                        text = stringResource(R.string.home_title_my_workouts),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (templates.isEmpty()) {
                    item {
                        EmptyWorkoutCard(onClick = onAddWorkoutClick)
                    }
                } else {
                    items(templates, key = { it.id }) { template ->
                        val isActive = activeWorkoutTemplateId == template.id
                        WorkoutCard(
                            template = template,
                            isActive = isActive,
                            onClick = {
                                if (isActive) {
                                    onNavigateToActiveWorkout(template.id)
                                } else {
                                    onNavigateToWorkoutDetail(template.id)
                                }
                            }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(IMFITSpacing.sm))
                    IMFITButton(
                        text = stringResource(R.string.action_add_workout),
                        onClick = onAddWorkoutClick,
                        icon = Icons.Default.Add
                    )
                    Spacer(modifier = Modifier.height(IMFITSpacing.huge))
                }
            }
        }
    }
}

@Composable
private fun WelcomeSection(userName: String) {
    Column {
        Text(
            text = stringResource(R.string.home_hello, userName),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(IMFITSpacing.xs))
        Text(
            text = stringResource(R.string.home_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LastWorkoutCard(workout: WorkoutLog?) {
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
                    Brush.horizontalGradient(
                        listOf(Primary.copy(alpha = 0.08f), PrimaryLight.copy(alpha = 0.04f))
                    )
                )
        ) {
            Column(modifier = Modifier.padding(IMFITSpacing.cardPaddingLarge)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.home_last_workout),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = Primary
                    )
                    Text(
                        text = workout?.templateName ?: stringResource(R.string.home_no_workouts_yet),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (workout != null) MaterialTheme.colorScheme.onSurface 
                               else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                Spacer(modifier = Modifier.height(IMFITSpacing.lg))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    StatChip(
                        icon = Icons.Default.Timer,
                        value = workout?.formattedDuration ?: "--:--",
                        label = "Duration"
                    )
                    StatChip(
                        icon = Icons.Default.FitnessCenter,
                        value = workout?.formattedVolume ?: "0 kg",
                        label = "Volume"
                    )
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
            .clip(IMFITShapes.Chip)
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
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
private fun WorkoutCard(
    template: WorkoutTemplate,
    isActive: Boolean = false,
    onClick: () -> Unit
) {
    val estimatedMinutes = template.exerciseCount * 8

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .then(
                if (isActive) Modifier.border(2.dp, SetComplete, IMFITShapes.Card)
                else Modifier
            ),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IMFITSpacing.cardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(IMFITShapes.IconContainer)
                    .background(
                        if (isActive) SetComplete
                        else MaterialTheme.colorScheme.primaryContainer
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isActive) Icons.Default.PlayArrow
                    else Icons.Default.FitnessCenter,
                    contentDescription = null,
                    tint = if (isActive) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(IMFITSizes.iconMd)
                )
            }

            Spacer(modifier = Modifier.width(IMFITSpacing.lg))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = template.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (isActive) {
                        Spacer(modifier = Modifier.width(IMFITSpacing.sm))
                        Box(
                            modifier = Modifier
                                .background(
                                    SetComplete.copy(alpha = pulseAlpha),
                                    RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.home_active),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(IMFITSpacing.xs))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.lg),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(IMFITSizes.iconXs)
                        )
                        Spacer(modifier = Modifier.width(IMFITSpacing.xs))
                        Text(
                            text = if (estimatedMinutes > 0) "~${estimatedMinutes} min" else "-",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.FitnessCenter,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(IMFITSizes.iconXs)
                        )
                        Spacer(modifier = Modifier.width(IMFITSpacing.xs))
                        Text(
                            text = stringResource(
                                R.string.home_exercises_count,
                                template.exerciseCount
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = stringResource(R.string.desc_view_details),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(IMFITSizes.iconMd)
            )
        }
    }
}

@Composable
private fun EmptyWorkoutCard(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
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
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(IMFITSizes.iconLg)
                )
            }
            Spacer(modifier = Modifier.height(IMFITSpacing.lg))
            Text(
                text = stringResource(R.string.home_no_workouts),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(IMFITSpacing.xs))
            Text(
                text = stringResource(R.string.home_create_first),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun HomeScreenPreviewLight() {
    com.diajarkoding.imfit.theme.IMFITTheme(darkTheme = false) {
        HomeScreenContent(
            userName = "John",
            templates = emptyList(),
            lastWorkout = null,
            activeWorkoutTemplateId = null,
            isLoading = false,
            onNavigateToWorkoutDetail = {},
            onNavigateToActiveWorkout = {},
            onAddWorkoutClick = {}
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun HomeScreenPreviewDark() {
    com.diajarkoding.imfit.theme.IMFITTheme(darkTheme = true) {
        HomeScreenContent(
            userName = "John",
            templates = emptyList(),
            lastWorkout = null,
            activeWorkoutTemplateId = null,
            isLoading = false,
            onNavigateToWorkoutDetail = {},
            onNavigateToActiveWorkout = {},
            onAddWorkoutClick = {}
        )
    }
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
private fun HomeScreenPreviewLoading() {
    com.diajarkoding.imfit.theme.IMFITTheme(darkTheme = false) {
        HomeScreenContent(
            userName = "",
            templates = emptyList(),
            lastWorkout = null,
            activeWorkoutTemplateId = null,
            isLoading = true,
            onNavigateToWorkoutDetail = {},
            onNavigateToActiveWorkout = {},
            onAddWorkoutClick = {}
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun LastWorkoutCardPreview() {
    com.diajarkoding.imfit.theme.IMFITTheme(darkTheme = false) {
        LastWorkoutCard(workout = null)
    }
}
