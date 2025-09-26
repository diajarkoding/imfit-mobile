package com.diajarkoding.imfit.presentation.components.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diajarkoding.imfit.R
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.customColors

@Composable
fun WorkoutDayHeader(
    dayTitle: String,
    estimatedTime: String,
    exerciseCount: Int,
    onMoreClick: () -> Unit,
    modifier: Modifier = Modifier,
    isDropdownExpanded: Boolean = false,
    onDismissDropdown: () -> Unit = {},
    onEditExercises: () -> Unit = {},
    onCopyDay: () -> Unit = {},
    onDeleteDay: () -> Unit = {}
) {
    Box(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = IMFITSpacing.md, vertical = IMFITSpacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dayTitle,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.customColors.textPrimary
                )
                Row(
                    modifier = Modifier.padding(top = IMFITSpacing.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.customColors.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = estimatedTime,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.customColors.textSecondary,
                        modifier = Modifier.padding(start = IMFITSpacing.xs, end = IMFITSpacing.md)
                    )
                    Icon(
                        Icons.Default.FitnessCenter,
                        contentDescription = null,
                        tint = MaterialTheme.customColors.textSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stringResource(R.string.workout_exercise_count, exerciseCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.customColors.textSecondary,
                        modifier = Modifier.padding(start = IMFITSpacing.xs)
                    )
                }
            }

            IconButton(onClick = onMoreClick) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.workout_more_options),
                    tint = MaterialTheme.customColors.textSecondary
                )
            }
        }

        // DropdownMenu tepat di posisi kanan atas
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 8.dp, end = 8.dp) // Adjust sesuai kebutuhan
        ) {
            WorkoutDayDropdownMenu(
                expanded = isDropdownExpanded,
                onDismiss = onDismissDropdown,
                onEditExercises = onEditExercises,
                onCopyDay = onCopyDay,
                onDeleteDay = onDeleteDay
            )
        }
    }
}