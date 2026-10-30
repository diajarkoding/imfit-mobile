package com.diajarkoding.imfit.presentation.components.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.diajarkoding.imfit.theme.IMFITColors
import com.diajarkoding.imfit.theme.IMFITComponentSizes
import com.diajarkoding.imfit.theme.IMFITCornerRadius
import com.diajarkoding.imfit.theme.IMFITShapes
import com.diajarkoding.imfit.theme.IMFITSizes
import com.diajarkoding.imfit.theme.IMFITSpacing
@Composable
fun shimmerBrush(
    targetValue: Float = 1000f,
    showShimmer: Boolean = true
): Brush {
    return if (showShimmer) {
        val palette = IMFITColors.training
        val shimmerColors = listOf(
            palette.border.copy(alpha = 0.7f),
            palette.raised.copy(alpha = 0.95f),
            palette.border.copy(alpha = 0.7f)
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
    val colors = IMFITColors.training
    val brush = shimmerBrush()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = IMFITSizes.cardMinHeight)
            .testTag("shimmer_workout_card"),
        shape = RoundedCornerShape(IMFITCornerRadius.template),
        color = colors.surface,
        border = BorderStroke(IMFITComponentSizes.border, colors.border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IMFITSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.md)
        ) {
            Box(
                modifier = Modifier
                    .size(IMFITComponentSizes.templateThumbnail)
                    .clip(RoundedCornerShape(IMFITCornerRadius.small))
                    .background(brush)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(IMFITSpacing.inline)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.58f)
                        .height(20.dp)
                        .clip(RoundedCornerShape(IMFITCornerRadius.xs))
                        .background(brush)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.36f)
                        .height(16.dp)
                        .clip(RoundedCornerShape(IMFITCornerRadius.xs))
                        .background(brush)
                )
            }
            Box(
                modifier = Modifier
                    .size(IMFITSizes.iconSm)
                    .clip(RoundedCornerShape(IMFITCornerRadius.xs))
                    .background(brush)
            )
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
    val colors = IMFITColors.training
    val brush = shimmerBrush()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("shimmer_last_workout_card"),
        shape = IMFITShapes.CardSmall,
        color = colors.surface,
        border = BorderStroke(IMFITComponentSizes.border, colors.border)
    ) {
        Column(
            modifier = Modifier.padding(IMFITSpacing.cardPadding),
            verticalArrangement = Arrangement.spacedBy(IMFITSpacing.summaryGap)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.compact)
            ) {
                Box(
                    modifier = Modifier
                        .size(IMFITComponentSizes.metricIcon)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush)
                )
                Box(modifier = Modifier.weight(1f)) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(0.55f)
                            .height(14.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(brush)
                    )
                }
                Box(
                    modifier = Modifier
                        .width(68.dp)
                        .height(14.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(brush)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.xl)
            ) {
                ShimmerSummaryMetricColumn(brush = brush, valueWidth = 64.dp, modifier = Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .width(IMFITComponentSizes.border)
                        .height(IMFITComponentSizes.metricDivider)
                        .background(colors.border)
                )
                ShimmerSummaryMetricColumn(brush = brush, valueWidth = 76.dp, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ShimmerSummaryMetricColumn(
    brush: Brush,
    valueWidth: Dp,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(IMFITSpacing.compact)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(IMFITSpacing.inline)
        ) {
            Box(
                modifier = Modifier
                    .size(IMFITComponentSizes.metricIcon)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush)
            )
            Box(
                modifier = Modifier
                    .width(68.dp)
                    .height(13.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush)
            )
        }
        Box(
            modifier = Modifier
                .width(valueWidth)
                .height(25.dp)
                .clip(RoundedCornerShape(IMFITCornerRadius.xs))
                .background(brush)
        )
    }
}

@Composable
fun ShimmerWelcomeSection(
    modifier: Modifier = Modifier
) {
    val brush = shimmerBrush()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .testTag("shimmer_welcome_section"),
        verticalArrangement = Arrangement.spacedBy(IMFITSpacing.xs)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.52f)
                .height(27.dp)
                .clip(RoundedCornerShape(IMFITCornerRadius.xs))
                .background(brush)
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(0.68f)
                .height(18.dp)
                .clip(RoundedCornerShape(IMFITCornerRadius.xs))
                .background(brush)
        )
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

/**
 * Shimmer component for workout summary stat cards
 */
@Composable
fun ShimmerSummaryStatCard(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IMFITSpacing.cardPadding),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ShimmerBox(
                width = 44.dp,
                height = 44.dp,
                shape = RoundedCornerShape(12.dp)
            )
            Spacer(modifier = Modifier.height(IMFITSpacing.md))
            ShimmerBox(width = 60.dp, height = 24.dp)
            Spacer(modifier = Modifier.height(IMFITSpacing.xs))
            ShimmerBox(width = 50.dp, height = 14.dp)
        }
    }
}

/**
 * Shimmer component for exercise summary cards
 */
@Composable
fun ShimmerExerciseSummaryCard(
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = IMFITShapes.Card,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(IMFITSpacing.cardPadding)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ShimmerBox(
                        width = 40.dp,
                        height = 40.dp,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Spacer(modifier = Modifier.width(IMFITSpacing.md))
                    Column {
                        ShimmerBox(width = 100.dp, height = 16.dp)
                        Spacer(modifier = Modifier.height(IMFITSpacing.xs))
                        ShimmerBox(width = 60.dp, height = 12.dp)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    ShimmerBox(width = 40.dp, height = 14.dp)
                    Spacer(modifier = Modifier.height(IMFITSpacing.xs))
                    ShimmerBox(width = 50.dp, height = 12.dp)
                }
            }
        }
    }
}

/**
 * Shimmer component for workout summary success header
 */
@Composable
fun ShimmerSummaryHeader(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = IMFITSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ShimmerCircle(size = 88.dp)
        Spacer(modifier = Modifier.height(IMFITSpacing.xxl))
        ShimmerBox(width = 180.dp, height = 28.dp)
        Spacer(modifier = Modifier.height(IMFITSpacing.sm))
        ShimmerBox(width = 120.dp, height = 18.dp)
    }
}
