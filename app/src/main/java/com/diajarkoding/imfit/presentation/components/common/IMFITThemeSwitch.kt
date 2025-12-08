package com.diajarkoding.imfit.presentation.components.common

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.diajarkoding.imfit.theme.Primary

@Composable
fun IMFITThemeSwitch(
    isDarkMode: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    val thumbOffset by animateDpAsState(
        targetValue = if (isDarkMode) 24.dp else 0.dp,
        animationSpec = tween(durationMillis = 200),
        label = "thumbOffset"
    )

    val trackColor = if (isDarkMode) Primary.copy(alpha = 0.3f) 
                     else MaterialTheme.colorScheme.surfaceVariant
    val thumbColor = if (isDarkMode) Primary 
                     else MaterialTheme.colorScheme.outline

    Box(
        modifier = modifier
            .width(52.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(trackColor)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onToggle() },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .offset(x = thumbOffset)
                .size(24.dp)
                .clip(CircleShape)
                .background(thumbColor),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isDarkMode) Icons.Default.DarkMode else Icons.Default.LightMode,
                contentDescription = if (isDarkMode) "Dark mode" else "Light mode",
                tint = Color.White,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}
