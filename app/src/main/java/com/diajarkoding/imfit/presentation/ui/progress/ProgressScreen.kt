package com.diajarkoding.imfit.presentation.ui.progress

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import com.diajarkoding.imfit.presentation.components.common.ShimmerCalendarCard
import com.diajarkoding.imfit.presentation.components.common.ShimmerProfileHeader
import com.diajarkoding.imfit.presentation.components.common.ShimmerBox
import com.diajarkoding.imfit.theme.IMFITShapes
import com.diajarkoding.imfit.theme.IMFITSizes
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.Primary
import com.diajarkoding.imfit.theme.PrimaryLight
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.components.common.IMFITProfilePhoto
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressScreen(
    onNavigateToWorkoutHistory: (LocalDate) -> Unit = {},
    onNavigateToYearlyCalendar: () -> Unit = {},
    onNavigateToProfile: () -> Unit = {},
    viewModel: ProgressViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadData()
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(IMFITSpacing.screenHorizontal),
        verticalArrangement = Arrangement.spacedBy(IMFITSpacing.lg)
    ) {
        if (state.isLoading) {
            item {
                Spacer(modifier = Modifier.height(IMFITSpacing.sm))
                ShimmerProfileHeader()
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md)
                ) {
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = IMFITShapes.Card,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(IMFITSpacing.cardPadding),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ShimmerBox(width = 40.dp, height = 40.dp, shape = RoundedCornerShape(12.dp))
                            Spacer(modifier = Modifier.height(IMFITSpacing.sm))
                            ShimmerBox(width = 60.dp, height = 18.dp)
                            Spacer(modifier = Modifier.height(IMFITSpacing.xxs))
                            ShimmerBox(width = 80.dp, height = 12.dp)
                        }
                    }
                    Card(
                        modifier = Modifier.weight(1f),
                        shape = IMFITShapes.Card,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier.padding(IMFITSpacing.cardPadding),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ShimmerBox(width = 40.dp, height = 40.dp, shape = RoundedCornerShape(12.dp))
                            Spacer(modifier = Modifier.height(IMFITSpacing.sm))
                            ShimmerBox(width = 60.dp, height = 18.dp)
                            Spacer(modifier = Modifier.height(IMFITSpacing.xxs))
                            ShimmerBox(width = 80.dp, height = 12.dp)
                        }
                    }
                }
            }
            item { ShimmerCalendarCard() }
        } else {
            item {
                Spacer(modifier = Modifier.height(IMFITSpacing.sm))
                ProfileHeader(
                    name = state.userName,
                    email = state.userEmail,
                    profilePhotoUri = state.userProfilePhotoUri,
                    onProfileClick = onNavigateToProfile
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md)
                ) {
                    StatCard(
                        icon = Icons.Default.FitnessCenter,
                        title = stringResource(R.string.progress_total_volume),
                        value = "${state.totalVolume.toInt()} kg",
                        modifier = Modifier.weight(1f)
                    )
                    StatCard(
                        icon = Icons.Default.Schedule,
                        title = stringResource(R.string.progress_weekly_time),
                        value = "${state.weeklyWorkoutTimeMinutes} min",
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            item {
                WorkoutCalendar(
                    workoutDates = state.workoutDates,
                    onDateSelected = onNavigateToWorkoutHistory,
                    onNavigateToYearlyCalendar = onNavigateToYearlyCalendar
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@Composable
private fun ProfileHeader(
    name: String,
    email: String,
    profilePhotoUri: String?,
    onProfileClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(6.dp, IMFITShapes.Card, spotColor = Primary.copy(alpha = 0.15f))
            .clickable { onProfileClick() },
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
                IMFITProfilePhoto(
                    profilePhotoUri = profilePhotoUri,
                    size = 64.dp
                )
                Spacer(modifier = Modifier.width(IMFITSpacing.lg))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = name,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(IMFITSpacing.xs))
                    Text(
                        text = email,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = stringResource(R.string.desc_go_to_profile),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatCard(
    icon: ImageVector,
    title: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IMFITSpacing.cardPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
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
            Spacer(modifier = Modifier.height(IMFITSpacing.sm))
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(IMFITSpacing.xxs))
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun WorkoutCalendar(
    workoutDates: Set<LocalDate>,
    onDateSelected: (LocalDate) -> Unit,
    onNavigateToYearlyCalendar: () -> Unit
) {
    val currentMonth = YearMonth.now()
    val daysInMonth = currentMonth.lengthOfMonth()
    val firstDayOfWeek = currentMonth.atDay(1).dayOfWeek.value
    val today = LocalDate.now()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(IMFITSpacing.cardPaddingLarge)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = IMFITSpacing.lg),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${
                        currentMonth.month.getDisplayName(
                            TextStyle.FULL,
                            Locale.getDefault()
                        )
                    } ${currentMonth.year}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = onNavigateToYearlyCalendar,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(R.string.calendar_view_yearly),
                        tint = Primary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                listOf("M", "T", "W", "T", "F", "S", "S").forEach { day ->
                    Text(
                        text = day,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.width(36.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(IMFITSpacing.md))

            val weeks = buildCalendarWeeks(daysInMonth, firstDayOfWeek)

            Column(verticalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)) {
                weeks.forEach { week ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        week.forEach { day ->
                            if (day != null) {
                                val date = currentMonth.atDay(day)
                                val isWorkoutDay = workoutDates.contains(date)
                                val isToday = date == today
                                val isClickable = isWorkoutDay

                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(CircleShape)
                                        .background(
                                            when {
                                                isWorkoutDay -> Primary
                                                isToday -> Primary.copy(alpha = 0.15f)
                                                else -> Color.Transparent
                                            }
                                        )
                                        .then(
                                            if (isClickable) Modifier.clickable {
                                                onDateSelected(
                                                    date
                                                )
                                            }
                                            else Modifier
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = day.toString(),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (isWorkoutDay || isToday) FontWeight.Bold else FontWeight.Normal,
                                        color = when {
                                            isWorkoutDay -> Color.White
                                            isToday -> Primary
                                            else -> MaterialTheme.colorScheme.onSurface
                                        }
                                    )
                                }
                            } else {
                                Box(modifier = Modifier.size(36.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun buildCalendarWeeks(daysInMonth: Int, firstDayOfWeek: Int): List<List<Int?>> {
    val weeks = mutableListOf<List<Int?>>()
    var currentWeek = mutableListOf<Int?>()
    var currentDay = 1

    for (i in 1 until firstDayOfWeek) {
        currentWeek.add(null)
    }

    while (currentDay <= daysInMonth) {
        currentWeek.add(currentDay)
        currentDay++
        if (currentWeek.size == 7) {
            weeks.add(currentWeek)
            currentWeek = mutableListOf()
        }
    }

    if (currentWeek.isNotEmpty()) {
        while (currentWeek.size < 7) {
            currentWeek.add(null)
        }
        weeks.add(currentWeek)
    }

    return weeks
}
