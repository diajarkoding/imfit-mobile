package com.diajarkoding.imfit.presentation.components.workout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun WorkoutDayDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onEditExercises: () -> Unit,
    onCopyDay: () -> Unit,
    onDeleteDay: () -> Unit,
    modifier: Modifier = Modifier
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = modifier.width(200.dp),
        shape = RoundedCornerShape(12.dp),
        containerColor = Color.White,
        shadowElevation = 8.dp,
        // Ini kuncinya: biar dropdown nempel kanan bawah anchor
        offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = 0.dp)
    ) {
        DropdownMenuItem(
            text = {
                Text(
                    text = "Edit exercises",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Black
                )
            },
            onClick = {
                onEditExercises()
                onDismiss()
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )

        DropdownMenuItem(
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Copy this day",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.Black
                    )
                }
            },
            onClick = {
                onCopyDay()
                onDismiss()
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )

        DropdownMenuItem(
            text = {
                Text(
                    text = "Delete this day",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Black
                )
            },
            onClick = {
                onDeleteDay()
                onDismiss()
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
