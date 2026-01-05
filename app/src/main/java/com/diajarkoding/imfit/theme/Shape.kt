package com.diajarkoding.imfit.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val Shapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(18.dp),
    extraLarge = RoundedCornerShape(24.dp)
)

object IMFITShapes {
    val ButtonLarge = RoundedCornerShape(16.dp)
    val Button = RoundedCornerShape(14.dp)
    val ButtonSmall = RoundedCornerShape(10.dp)
    val Card = RoundedCornerShape(20.dp)
    val CardSmall = RoundedCornerShape(14.dp)
    val TextField = RoundedCornerShape(14.dp)
    val BottomSheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val Dialog = RoundedCornerShape(28.dp)
    val Chip = RoundedCornerShape(10.dp)
    val Badge = RoundedCornerShape(50)
    val IconContainer = RoundedCornerShape(14.dp)
    val ListItem = RoundedCornerShape(16.dp)
}
