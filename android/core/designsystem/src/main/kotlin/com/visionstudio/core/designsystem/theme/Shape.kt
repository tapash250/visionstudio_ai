package com.visionstudio.core.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object StudioShapes {
    val Chip = RoundedCornerShape(50)
    val Control = RoundedCornerShape(12.dp)
    val Card = RoundedCornerShape(16.dp)
    val Panel = RoundedCornerShape(24.dp)
    val Sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val Dock = RoundedCornerShape(32.dp)
    val Orb = CircleShape
}

val StudioMaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = StudioShapes.Control,
    medium = StudioShapes.Card,
    large = StudioShapes.Panel,
    extraLarge = RoundedCornerShape(32.dp),
)
