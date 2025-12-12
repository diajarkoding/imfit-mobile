package com.diajarkoding.imfit.presentation.components.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.diajarkoding.imfit.data.sync.SyncState

/**
 * Compact sync status indicator that shows current sync state.
 * Shows a colored dot + optional text based on sync status.
 */
@Composable
fun SyncIndicator(
    syncState: SyncState,
    showText: Boolean = true,
    modifier: Modifier = Modifier
) {
    val (color, text) = when (syncState.status) {
        SyncState.SyncStatus.SYNCING -> Pair(
            MaterialTheme.colorScheme.primary,
            "Syncing..."
        )
        SyncState.SyncStatus.SYNCED -> Pair(
            Color(0xFF4CAF50), // Green
            "Synced"
        )
        SyncState.SyncStatus.FAILED -> Pair(
            MaterialTheme.colorScheme.error,
            "Sync failed"
        )
        SyncState.SyncStatus.OFFLINE -> Pair(
            Color(0xFFFF9800), // Orange
            "Offline"
        )
        SyncState.SyncStatus.IDLE -> Pair(
            Color(0xFF9E9E9E), // Gray
            ""
        )
    }

    val animatedColor by animateColorAsState(
        targetValue = color,
        animationSpec = tween(durationMillis = 300),
        label = "syncColorAnimation"
    )

    // Pulse animation for syncing
    val infiniteTransition = rememberInfiniteTransition(label = "syncTransition")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (syncState.status == SyncState.SyncStatus.SYNCING) 0.5f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "syncPulse"
    )

    if (syncState.status != SyncState.SyncStatus.IDLE) {
        Row(
            modifier = modifier
                .background(
                    color = animatedColor.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(16.dp)
                )
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Animated status dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .alpha(alpha)
                    .background(animatedColor, CircleShape)
            )

            // Text label
            if (showText && text.isNotEmpty()) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelSmall,
                    color = animatedColor
                )
            }

            // Pending count badge
            if (syncState.pendingCount > 0 && syncState.status != SyncState.SyncStatus.SYNCING) {
                Text(
                    text = "(${syncState.pendingCount})",
                    style = MaterialTheme.typography.labelSmall,
                    color = animatedColor.copy(alpha = 0.7f)
                )
            }
        }
    }
}

/**
 * Minimal sync dot indicator for toolbar/header.
 */
@Composable
fun SyncDot(
    syncState: SyncState,
    modifier: Modifier = Modifier
) {
    val color = when (syncState.status) {
        SyncState.SyncStatus.SYNCING -> MaterialTheme.colorScheme.primary
        SyncState.SyncStatus.SYNCED -> Color(0xFF4CAF50)
        SyncState.SyncStatus.FAILED -> MaterialTheme.colorScheme.error
        SyncState.SyncStatus.OFFLINE -> Color(0xFFFF9800)
        SyncState.SyncStatus.IDLE -> Color.Transparent
    }

    val infiniteTransition = rememberInfiniteTransition(label = "dotTransition")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (syncState.status == SyncState.SyncStatus.SYNCING) 0.3f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "dotAlpha"
    )

    if (syncState.status != SyncState.SyncStatus.IDLE) {
        Box(
            modifier = modifier
                .size(10.dp)
                .alpha(alpha)
                .background(color, CircleShape)
        )
    }
}
