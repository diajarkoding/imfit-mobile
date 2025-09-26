package com.diajarkoding.imfit.presentation.components.exercises

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Checkbox
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
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.customColors

@Composable
fun ExerciseSelectionItem(
    exercise: Exercise,
    isSelected: Boolean,
    onItemSelectedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onItemSelectedChange(!isSelected) }
            .padding(horizontal = IMFITSpacing.md, vertical = IMFITSpacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = exercise.imageUrl,
            contentDescription = exercise.name,
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
        Column(modifier = Modifier
            .weight(1f)
            .padding(horizontal = IMFITSpacing.sm)) {
            Text(text = exercise.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = exercise.targetMuscle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.customColors.textSecondary
            )
        }
        Checkbox(checked = isSelected, onCheckedChange = { onItemSelectedChange(it) })
    }
}