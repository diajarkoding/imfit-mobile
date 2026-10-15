package com.diajarkoding.imfit.presentation.components.common

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.diajarkoding.imfit.theme.IMFITShapes
import com.diajarkoding.imfit.theme.IMFITSizes
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.Primary
import com.diajarkoding.imfit.theme.PrimaryLight
import com.diajarkoding.imfit.theme.IMFITOpacity
import com.diajarkoding.imfit.theme.IMFITColors
import com.diajarkoding.imfit.theme.IMFITGradients
import com.diajarkoding.imfit.theme.OnPrimary

@Composable
fun IMFITButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    icon: ImageVector? = null,
    height: Dp = IMFITSizes.buttonHeight
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.97f else 1f,
        animationSpec = tween(100),
        label = "scale"
    )

    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = height.coerceAtLeast(IMFITSizes.minimumInteractiveTarget))
            .scale(scale),
        enabled = enabled && !isLoading,
        shape = IMFITShapes.Button,
        interactionSource = interactionSource,
        contentPadding = PaddingValues(horizontal = IMFITSpacing.xxl, vertical = IMFITSpacing.md),
        colors = ButtonDefaults.buttonColors(
            containerColor = Primary,
            contentColor = OnPrimary,
            disabledContainerColor = Primary.copy(alpha = IMFITOpacity.disabled),
            disabledContentColor = OnPrimary.copy(alpha = IMFITOpacity.disabledContent)
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 2.dp,
            pressedElevation = 0.dp,
            disabledElevation = 0.dp
        )
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = OnPrimary,
                strokeWidth = IMFITSizes.buttonLoadingStroke,
                modifier = Modifier.size(IMFITSizes.buttonLoadingIcon)
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(IMFITSizes.iconSm)
                    )
                    Spacer(modifier = Modifier.width(IMFITSpacing.sm))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
fun IMFITGradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    icon: ImageVector? = null,
    height: Dp = IMFITSizes.buttonHeight
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.97f else 1f,
        animationSpec = tween(100),
        label = "scale"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = height.coerceAtLeast(IMFITSizes.minimumInteractiveTarget))
            .scale(scale)
            .shadow(if (enabled) 4.dp else 0.dp, IMFITShapes.Button)
            .clip(IMFITShapes.Button)
            .background(
                brush = if (enabled) IMFITColors.gradientBrush else IMFITGradients.disabledBrand
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && !isLoading,
                onClick = onClick
            )
            .padding(horizontal = IMFITSpacing.xxl, vertical = IMFITSpacing.md),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = OnPrimary,
                strokeWidth = IMFITSizes.buttonLoadingStroke,
                modifier = Modifier.size(IMFITSizes.buttonLoadingIcon)
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = OnPrimary,
                        modifier = Modifier.size(IMFITSizes.iconSm)
                    )
                    Spacer(modifier = Modifier.width(IMFITSpacing.sm))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) OnPrimary else OnPrimary.copy(alpha = IMFITOpacity.disabledContent)
                )
            }
        }
    }
}

@Composable
fun IMFITOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    icon: ImageVector? = null,
    height: Dp = IMFITSizes.buttonHeight
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.97f else 1f,
        animationSpec = tween(100),
        label = "scale"
    )
    
    val backgroundColor by animateColorAsState(
        targetValue = if (isPressed) Primary.copy(alpha = IMFITOpacity.pressed) else Color.Transparent,
        animationSpec = tween(100),
        label = "backgroundColor"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = height.coerceAtLeast(IMFITSizes.minimumInteractiveTarget))
            .scale(scale)
            .clip(IMFITShapes.Button)
            .background(backgroundColor)
            .border(
                width = IMFITSizes.outlinedButtonStroke,
                color = if (enabled) Primary else Primary.copy(alpha = 0.4f),
                shape = IMFITShapes.Button
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && !isLoading,
                onClick = onClick
            )
            .padding(horizontal = IMFITSpacing.xxl, vertical = IMFITSpacing.md),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = Primary,
                strokeWidth = IMFITSizes.buttonLoadingStroke,
                modifier = Modifier.size(IMFITSizes.buttonLoadingIcon)
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = if (enabled) Primary else Primary.copy(alpha = 0.4f),
                        modifier = Modifier.size(IMFITSizes.iconSm)
                    )
                    Spacer(modifier = Modifier.width(IMFITSpacing.sm))
                }
                Text(
                    text = text,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) Primary else Primary.copy(alpha = 0.4f)
                )
            }
        }
    }
}

@Composable
fun IMFITSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    containerColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    icon: ImageVector? = null,
    height: Dp = IMFITSizes.buttonHeight
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed && enabled) 0.97f else 1f,
        animationSpec = tween(100),
        label = "scale"
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = height.coerceAtLeast(IMFITSizes.minimumInteractiveTarget))
            .scale(scale)
            .clip(IMFITShapes.Button)
            .background(if (enabled) containerColor else containerColor.copy(alpha = 0.5f))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && !isLoading,
                onClick = onClick
            )
            .padding(horizontal = IMFITSpacing.xxl, vertical = IMFITSpacing.md),
        contentAlignment = Alignment.Center
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                color = contentColor,
                strokeWidth = IMFITSizes.buttonLoadingStroke,
                modifier = Modifier.size(IMFITSizes.buttonLoadingIcon)
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(IMFITSizes.iconSm)
                    )
                    Spacer(modifier = Modifier.width(IMFITSpacing.sm))
                }
                Text(
                    text = text,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (enabled) contentColor else contentColor.copy(alpha = 0.5f)
                )
            }
        }
    }
}

@Composable
fun IMFITSmallButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    containerColor: Color = Primary,
    contentColor: Color = OnPrimary
) {
    Box(
        modifier = modifier
            .heightIn(min = IMFITSizes.buttonHeightSmall)
            .clip(IMFITShapes.ButtonSmall)
            .background(if (enabled) containerColor else containerColor.copy(alpha = 0.5f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = IMFITSpacing.lg, vertical = IMFITSpacing.sm),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = contentColor,
                    modifier = Modifier.size(IMFITSizes.iconXs)
                )
                Spacer(modifier = Modifier.width(IMFITSpacing.xs))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) contentColor else contentColor.copy(alpha = 0.5f)
            )
        }
    }
}
