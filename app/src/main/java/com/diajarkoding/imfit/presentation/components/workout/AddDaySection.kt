package com.diajarkoding.imfit.presentation.components.workout

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
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
fun AddDaySection(
    currentDays: Int,
    maxDays: Int,
    onAddDay: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = IMFITSpacing.md, vertical = IMFITSpacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.workout_day_limit, maxDays),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.customColors.textSecondary
        )
        TextButton(
            onClick = onAddDay,
            enabled = currentDays < maxDays
        ) {
            Icon(
                Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(IMFITSpacing.xs))
            Text(
                text = stringResource(R.string.workout_add_day_button),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
        }
    }
}