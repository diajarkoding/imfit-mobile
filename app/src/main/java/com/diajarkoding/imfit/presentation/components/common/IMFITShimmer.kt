package com.diajarkoding.imfit.presentation.components.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.diajarkoding.imfit.theme.IMFITShapes
import com.diajarkoding.imfit.theme.IMFITSpacing

@Composable
fun shimmerBrush(
    targetValue: Float = 1000f,
    showShimmer: Boolean = true
): Brush {
    return if (showShimmer) {
        val shimmerColors = listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )

        val transition = rememberInfiniteTransition(label = "shimmer")
        val translateAnim by transition.animateFloat(
            initialValue = 0f,
            targetValue = targetValue,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            ),
            label = "shimmer_translate"
        )

        Brush.linearGradient(
            colors = shimmerColors,
            start = Offset(translateAnim - 200f, translateAnim - 200f),
            end = Offset(translateAnim, translateAnim)
        )
    } else {
        Brush.linearGradient(
            colors = listOf(Color.Transparent, Color.Transparent)
        )
    }
}

@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    width: Dp = 100.dp,
    height: Dp = 16.dp,
    shape: RoundedCornerShape = RoundedCornerShape(4.dp)
) {
    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .clip(shape)
            .background(shimmerBrush())
    )
}

@Composable
fun ShimmerCircle(
    modifier: Modifier = Modifier,
    size: Dp = 48.dp
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(shimmerBrush())
    )
}

@Composable
fun ShimmerWorkoutCard(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IMFITSpacing.cardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ShimmerBox(
                width = 52.dp,
                height = 52.dp,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.width(IMFITSpacing.lg))
            Column(modifier = Modifier.weight(1f)) {
                ShimmerBox(width = 140.dp, height = 20.dp)
                Spacer(modifier = Modifier.height(IMFITSpacing.sm))
                Row(horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.lg)) {
                    ShimmerBox(width = 60.dp, height = 14.dp)
                    ShimmerBox(width = 80.dp, height = 14.dp)
                }
            }
            ShimmerBox(width = 24.dp, height = 24.dp)
        }
    }
}

@Composable
fun ShimmerProfileHeader(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IMFITSpacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ShimmerCircle(size = 100.dp)
            Spacer(modifier = Modifier.height(IMFITSpacing.lg))
            ShimmerBox(width = 160.dp, height = 24.dp)
            Spacer(modifier = Modifier.height(IMFITSpacing.sm))
            ShimmerBox(width = 200.dp, height = 16.dp)
        }
    }
}

@Composable
fun ShimmerInfoCard(
    modifier: Modifier = Modifier,
    itemCount: Int = 3
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column {
            repeat(itemCount) { index ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(IMFITSpacing.cardPadding),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ShimmerCircle(size = 40.dp)
                    Spacer(modifier = Modifier.width(IMFITSpacing.md))
                    Column(modifier = Modifier.weight(1f)) {
                        ShimmerBox(width = 80.dp, height = 12.dp)
                        Spacer(modifier = Modifier.height(4.dp))
                        ShimmerBox(width = 140.dp, height = 16.dp)
                    }
                }
                if (index < itemCount - 1) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 64.dp)
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                    )
                }
            }
        }
    }
}

@Composable
fun ShimmerStatCard(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(IMFITSpacing.cardPaddingLarge)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                ShimmerBox(width = 100.dp, height = 14.dp)
                ShimmerBox(width = 120.dp, height = 18.dp)
            }
            Spacer(modifier = Modifier.height(IMFITSpacing.lg))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ShimmerBox(width = 100.dp, height = 48.dp, shape = RoundedCornerShape(8.dp))
                ShimmerBox(width = 100.dp, height = 48.dp, shape = RoundedCornerShape(8.dp))
            }
        }
    }
}

@Composable
fun ShimmerWelcomeSection(
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        ShimmerBox(width = 200.dp, height = 28.dp)
        Spacer(modifier = Modifier.height(IMFITSpacing.xs))
        ShimmerBox(width = 280.dp, height = 18.dp)
    }
}

@Composable
fun ShimmerCalendarCard(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(IMFITSpacing.cardPaddingLarge)) {
            ShimmerBox(width = 150.dp, height = 20.dp)
            Spacer(modifier = Modifier.height(IMFITSpacing.lg))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                repeat(7) {
                    ShimmerBox(width = 36.dp, height = 14.dp)
                }
            }
            Spacer(modifier = Modifier.height(IMFITSpacing.md))
            repeat(5) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    repeat(7) {
                        ShimmerCircle(size = 36.dp)
                    }
                }
                Spacer(modifier = Modifier.height(IMFITSpacing.sm))
            }
        }
    }
}

@Composable
fun ShimmerExerciseCard(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IMFITSpacing.cardPadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ShimmerBox(
                width = 48.dp,
                height = 48.dp,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.width(IMFITSpacing.lg))
            Column(modifier = Modifier.weight(1f)) {
                ShimmerBox(width = 120.dp, height = 16.dp)
                Spacer(modifier = Modifier.height(IMFITSpacing.xs))
                ShimmerBox(width = 80.dp, height = 12.dp)
            }
        }
    }
}

@Composable
fun ShimmerCategoryGrid(
    modifier: Modifier = Modifier,
    columns: Int = 3,
    rows: Int = 3
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)
    ) {
        repeat(rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.sm)
            ) {
                repeat(columns) {
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(0.85f),
                        shape = IMFITShapes.Card,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(IMFITSpacing.md),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            ShimmerBox(
                                width = 40.dp,
                                height = 40.dp,
                                shape = RoundedCornerShape(12.dp)
                            )
                            Spacer(modifier = Modifier.height(IMFITSpacing.sm))
                            ShimmerBox(width = 60.dp, height = 14.dp)
                        }
                    }
                }
            }
        }
    }
}
