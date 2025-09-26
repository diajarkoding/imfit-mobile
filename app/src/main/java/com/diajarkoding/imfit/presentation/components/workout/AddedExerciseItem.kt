package com.diajarkoding.imfit.presentation.components.workout


import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.diajarkoding.imfit.presentation.ui.exercises.Exercise
import com.diajarkoding.imfit.theme.IMFITCornerRadius
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.customColors

@Composable
fun AddedExerciseItem(
    exercise: Exercise,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = IMFITSpacing.md, vertical = IMFITSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)
    ) {
        Icon(
            imageVector = Icons.Default.DragHandle,
            contentDescription = "Drag to reorder",
            tint = MaterialTheme.customColors.textTertiary
        )
        AsyncImage(
            model = exercise.imageUrl,
            contentDescription = exercise.name,
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(IMFITCornerRadius.small)),
            contentScale = ContentScale.Crop
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = exercise.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = "3 x 8 reps", // TODO: Buat data ini dinamis
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.customColors.textSecondary
            )
        }
    }
}