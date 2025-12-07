package com.diajarkoding.imfit.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

object IMFITShapes {
    val Button = RoundedCornerShape(12.dp)
    val Card = RoundedCornerShape(16.dp)
    val TextField = RoundedCornerShape(12.dp)
    val BottomSheet = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    val Dialog = RoundedCornerShape(24.dp)
    val Chip = RoundedCornerShape(8.dp)
    val Badge = RoundedCornerShape(50)
}
