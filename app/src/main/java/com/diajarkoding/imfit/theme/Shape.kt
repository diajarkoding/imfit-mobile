package com.diajarkoding.imfit.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes

val Shapes = Shapes(
    extraSmall = RoundedCornerShape(IMFITCornerRadius.xs),
    small = RoundedCornerShape(IMFITCornerRadius.small),
    medium = RoundedCornerShape(IMFITCornerRadius.medium),
    large = RoundedCornerShape(IMFITCornerRadius.large),
    extraLarge = RoundedCornerShape(IMFITCornerRadius.xlarge)
)

object IMFITShapes {
    val ButtonLarge = RoundedCornerShape(IMFITCornerRadius.listItem)
    val Button = RoundedCornerShape(IMFITCornerRadius.medium)
    val ButtonSmall = RoundedCornerShape(IMFITCornerRadius.small)
    val Card = RoundedCornerShape(IMFITCornerRadius.card)
    val CardSmall = RoundedCornerShape(IMFITCornerRadius.medium)
    val TextField = RoundedCornerShape(IMFITCornerRadius.medium)
    val BottomSheet = RoundedCornerShape(topStart = IMFITCornerRadius.dialog, topEnd = IMFITCornerRadius.dialog)
    val Dialog = RoundedCornerShape(IMFITCornerRadius.dialog)
    val Chip = RoundedCornerShape(IMFITCornerRadius.small)
    val Badge = RoundedCornerShape(50)
    val IconContainer = RoundedCornerShape(IMFITCornerRadius.medium)
    val ListItem = RoundedCornerShape(IMFITCornerRadius.listItem)
}
