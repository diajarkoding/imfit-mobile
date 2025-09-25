package com.diajarkoding.imfit.presentation.components.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.presentation.ui.workout.WorkoutDay
import com.diajarkoding.imfit.theme.*

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
            containerColor = MaterialTheme.customColors.surfaceElevated
        ),
        shape = RoundedCornerShape(IMFITCornerRadius.medium),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(IMFITSpacing.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(48.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(2.dp)
                    )
            )
            Spacer(modifier = Modifier.width(IMFITSpacing.sm))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = MaterialTheme.customColors.backgroundPrimary,
                        shape = RoundedCornerShape(IMFITCornerRadius.small)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.FitnessCenter,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(IMFITSpacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = workoutDay.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.customColors.textPrimary
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.customColors.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = workoutDay.estimatedTime,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.customColors.textSecondary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                    Spacer(modifier = Modifier.width(IMFITSpacing.md))
                    Icon(
                        Icons.Default.FitnessCenter,
                        contentDescription = null,
                        tint = MaterialTheme.customColors.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = workoutDay.exerciseCount,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.customColors.textSecondary,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
                Text(
                    text = workoutDay.status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.customColors.textSecondary
                )
            }
            Row {
                IconButton(onClick = onMoreClick) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.workout_day_card_more_options),
                        tint = MaterialTheme.customColors.textSecondary
                    )
                }
                IconButton(onClick = onCardClick) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.workout_day_card_view_details),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}