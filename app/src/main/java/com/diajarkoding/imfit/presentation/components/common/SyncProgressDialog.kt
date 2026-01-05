package com.diajarkoding.imfit.presentation.components.common

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diajarkoding.imfit.data.sync.SyncState
import com.diajarkoding.imfit.theme.Primary

/**
 * Full-screen sync overlay with semi-transparent background.
 * Modern, minimal design that covers homescreen content.
 */
@Composable
fun SyncProgressDialog(
    syncState: SyncState,
    onSyncComplete: () -> Unit = {}
) {
    if (!syncState.isInitialSync) return
    
    // Full screen overlay with semi-transparent dark background
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Modern circular progress indicator
            CircularProgressIndicator(
                modifier = Modifier.size(72.dp),
                color = Primary,
                strokeWidth = 6.dp,
                strokeCap = StrokeCap.Round
            )
            
            Spacer(modifier = Modifier.height(32.dp))
            
            // Title - bold and prominent
            Text(
                text = "Syncing",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                letterSpacing = 1.sp
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Progress message - subtle
            Text(
                text = syncState.progressMessage ?: "Please wait...",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
                fontWeight = FontWeight.Medium
            )
            
            Spacer(modifier = Modifier.height(40.dp))
            
            // Progress bar - sleek and modern
            val animatedProgress by animateFloatAsState(
                targetValue = syncState.progress,
                animationSpec = tween(300),
                label = "sync_progress"
            )
            
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .width(200.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = Primary,
                trackColor = Color.White.copy(alpha = 0.2f),
                strokeCap = StrokeCap.Round
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Percentage - prominent
            Text(
                text = "${(syncState.progress * 100).toInt()}%",
                style = MaterialTheme.typography.titleLarge,
                color = Primary,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
        }
    }
}
