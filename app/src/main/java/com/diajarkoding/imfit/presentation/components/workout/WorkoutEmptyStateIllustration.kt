package com.diajarkoding.imfit.presentation.components.workout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun WorkoutEmptyStateIllustration(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EmptyStateDot(color = Color(0xFFE3F2FD))
            EmptyStateDot(size = 100.dp, color = Color(0xFF2196F3), isBar = true)
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EmptyStateDot(color = Color(0xFFE3F2FD))
            EmptyStateDot(size = 80.dp, color = Color(0xFF2196F3), isBar = true)
        }
    }
}

@Composable
private fun EmptyStateDot(
    modifier: Modifier = Modifier,
    color: Color,
    size: Dp = 12.dp,
    isBar: Boolean = false,
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