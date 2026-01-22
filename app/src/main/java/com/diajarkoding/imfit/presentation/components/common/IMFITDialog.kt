package com.diajarkoding.imfit.presentation.components.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.diajarkoding.imfit.theme.DeletePink
import com.diajarkoding.imfit.theme.IMFITShapes
import com.diajarkoding.imfit.theme.IMFITSizes
import com.diajarkoding.imfit.theme.IMFITSpacing
import com.diajarkoding.imfit.theme.Primary
import com.diajarkoding.imfit.theme.PrimaryLight

enum class IMFITDialogType {
    DEFAULT,
    DESTRUCTIVE,
    SUCCESS
}

@Composable
fun IMFITDialog(
    onDismissRequest: () -> Unit,
    title: String,
    message: String? = null,
    icon: ImageVector? = null,
    type: IMFITDialogType = IMFITDialogType.DEFAULT,
    confirmText: String = "Confirm",
    dismissText: String = "Cancel",
    isLoading: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: (() -> Unit)? = null,
    content: @Composable (() -> Unit)? = null
) {
    val iconColor = when (type) {
        IMFITDialogType.DESTRUCTIVE -> DeletePink
        IMFITDialogType.SUCCESS -> Primary
        IMFITDialogType.DEFAULT -> Primary
    }

    val iconBackground = when (type) {
        IMFITDialogType.DESTRUCTIVE -> DeletePink.copy(alpha = 0.12f)
        else -> Brush.linearGradient(listOf(Primary.copy(alpha = 0.15f), PrimaryLight.copy(alpha = 0.1f)))
    }

    val confirmColor = when (type) {
        IMFITDialogType.DESTRUCTIVE -> DeletePink
        else -> Primary
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
                    indication = null,
                    onClick = { onDismiss?.invoke() ?: onDismissRequest() }
                ),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = IMFITSpacing.xl)
                    .clickable(
                        interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
                        indication = null,
                        onClick = {}
                    ),
                shape = IMFITShapes.Dialog,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(IMFITSpacing.xl),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Icon
                    if (icon != null) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(
                                    if (type == IMFITDialogType.DESTRUCTIVE) 
                                        DeletePink.copy(alpha = 0.12f)
                                    else 
                                        Primary.copy(alpha = 0.12f)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = iconColor,
                                modifier = Modifier.size(IMFITSizes.iconLg)
                            )
                        }
                        Spacer(modifier = Modifier.height(IMFITSpacing.lg))
                    }

                    // Title
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // Message
                    if (message != null) {
                        Spacer(modifier = Modifier.height(IMFITSpacing.sm))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Custom content
                    if (content != null) {
                        Spacer(modifier = Modifier.height(IMFITSpacing.lg))
                        content()
                    }

                    Spacer(modifier = Modifier.height(IMFITSpacing.xl))

                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = { onDismiss?.invoke() ?: onDismissRequest() },
                            enabled = !isLoading
                        ) {
                            Text(
                                text = dismissText,
                                fontWeight = FontWeight.Medium,
                                color = if (!isLoading) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                        Spacer(modifier = Modifier.width(IMFITSpacing.sm))
                        TextButton(
                            onClick = onConfirm,
                            enabled = !isLoading
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Primary
                                )
                            } else {
                                Text(
                                    text = confirmText,
                                    fontWeight = FontWeight.SemiBold,
                                    color = confirmColor
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun IMFITInputDialog(
    onDismissRequest: () -> Unit,
    title: String,
    message: String? = null,
    icon: ImageVector? = null,
    confirmText: String = "Confirm",
    dismissText: String = "Cancel",
    confirmEnabled: Boolean = true,
    isLoading: Boolean = false,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit
) {
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .clickable(
                    interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
                    indication = null,
                    onClick = onDismissRequest
                ),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = IMFITSpacing.xl)
                    .clickable(
                        interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
                        indication = null,
                        onClick = {}
                    ),
                shape = IMFITShapes.Dialog,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(IMFITSpacing.xl)
                ) {
                    // Header with icon
                    if (icon != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(Primary.copy(alpha = 0.12f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = icon,
                                    contentDescription = null,
                                    tint = Primary,
                                    modifier = Modifier.size(IMFITSizes.iconMd)
                                )
                            }
                            Spacer(modifier = Modifier.width(IMFITSpacing.md))
                            Text(
                                text = title,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    } else {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    // Message
                    if (message != null) {
                        Spacer(modifier = Modifier.height(IMFITSpacing.sm))
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(IMFITSpacing.lg))

                    // Content (input fields)
                    content()

                    Spacer(modifier = Modifier.height(IMFITSpacing.xl))

                    // Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(
                            onClick = onDismissRequest,
                            enabled = !isLoading
                        ) {
                            Text(
                                text = dismissText,
                                fontWeight = FontWeight.Medium,
                                color = if (!isLoading) MaterialTheme.colorScheme.onSurfaceVariant 
                                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                        }
                        Spacer(modifier = Modifier.width(IMFITSpacing.sm))
                        TextButton(
                            onClick = onConfirm,
                            enabled = confirmEnabled && !isLoading
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = Primary
                                )
                            } else {
                                Text(
                                    text = confirmText,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (confirmEnabled) Primary else Primary.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
