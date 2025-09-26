package com.diajarkoding.imfit.presentation.components.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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

@Composable
fun EditDayHeader(
    dayName: String,
    workoutTitle: String,
    onCloseClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = IMFITSpacing.md, vertical = IMFITSpacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)
        ) {
            Text(
                text = dayName,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = workoutTitle,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Normal
            )
        }
        IconButton(
            onClick = onCloseClick,
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = stringResource(R.string.workout_edit_day_close),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}