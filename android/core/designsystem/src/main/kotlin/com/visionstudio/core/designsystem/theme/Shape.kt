package com.visionstudio.core.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * HUD geometry: chamfered (cut-corner) frames with a deeper cut on the top-start and
 * bottom-end corners, pointed "hex" ends on tabs and buttons, and pills for inputs and chips.
 */
object StudioShapes {
    /** Status pills and filter chips. */
    val Chip = RoundedCornerShape(50)

    /** Inputs: a pill that stays rounded when the value wraps onto more lines. */
    val Field = RoundedCornerShape(27.dp)

    /** Small chamfered tiles: thumbnails, icon wells, toggles. */
    val Control = CutCornerShape(topStart = 9.dp, topEnd = 3.dp, bottomEnd = 9.dp, bottomStart = 3.dp)

    /** Cards and option rows. */
    val Card = CutCornerShape(topStart = 14.dp, topEnd = 5.dp, bottomEnd = 14.dp, bottomStart = 5.dp)

    /** Large panels, stages and docks. */
    val Panel = CutCornerShape(topStart = 20.dp, topEnd = 7.dp, bottomEnd = 20.dp, bottomStart = 7.dp)

    /** Tab tracks, selected tabs and buttons: pointed ends. */
    val Tab = CutCornerShape(percent = 32)

    val Sheet = CutCornerShape(topStart = 26.dp, topEnd = 26.dp)

    /** Bottom navigation dock. */
    val Dock = CutCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 10.dp, bottomStart = 10.dp)

    val Orb = CircleShape
}

val StudioMaterialShapes = Shapes(
    extraSmall = CutCornerShape(4.dp),
    small = StudioShapes.Control,
    medium = StudioShapes.Card,
    large = StudioShapes.Panel,
    extraLarge = StudioShapes.Sheet,
)
