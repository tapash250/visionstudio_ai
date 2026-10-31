package com.visionstudio.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visionstudio.core.designsystem.R
import com.visionstudio.core.designsystem.haptics.HapticPattern
import com.visionstudio.core.designsystem.haptics.tactileClickable
import com.visionstudio.core.designsystem.modifier.hudFrame
import com.visionstudio.core.designsystem.theme.NeonBrushes
import com.visionstudio.core.designsystem.theme.StudioBlue
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioLilac
import com.visionstudio.core.designsystem.theme.StudioMagenta
import com.visionstudio.core.designsystem.theme.StudioShapes
import com.visionstudio.core.designsystem.theme.StudioViolet
import com.visionstudio.core.designsystem.theme.VisionStudioTheme

/**
 * The branded header card at the top of every screen: the sparkle logo, the "Vision Studio"
 * word mark with its tagline, and a profile orb. [onProfileClick] null makes the orb inert.
 */
@Composable
fun StudioHeader(
    modifier: Modifier = Modifier,
    onProfileClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .hudFrame(shape = StudioShapes.Panel, clipContent = false, glow = { 0.75f })
            .padding(start = 12.dp, end = 12.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StudioLogo(Modifier.size(52.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val brand = VisionStudioTheme.typography.brand
                Text(stringResource(R.string.brand_vision) + " ", style = brand, color = Color.White, maxLines = 1)
                Text(stringResource(R.string.brand_studio), style = brand.copy(brush = NeonBrushes.Brand), maxLines = 1)
            }
            Text(
                text = stringResource(R.string.brand_tagline),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, letterSpacing = 0.02.sp),
                color = TAGLINE,
                maxLines = 1,
            )
        }
        ProfileOrb(onClick = onProfileClick)
    }
}

/** The app mark: a four-point sparkle in a glowing rounded tile. */
@Composable
fun StudioLogo(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.brand_logo)
    Box(
        modifier
            .semantics { contentDescription = label }
            .drawWithCache {
                val side = size.minDimension
                val corner = CornerRadius(side * 0.26f)
                val halo = Brush.radialGradient(
                    listOf(StudioViolet.copy(alpha = 0.55f), StudioBlue.copy(alpha = 0.18f), Color.Transparent),
                    center = size.center,
                    radius = side * 0.8f,
                )
                val tile = Brush.linearGradient(listOf(Color(0xFF2A1670), Color(0xFF0D1448)))
                val ring = Brush.linearGradient(listOf(StudioCyan, StudioViolet, StudioMagenta))
                val ringStroke = Stroke(width = 1.6.dp.toPx())
                onDrawBehind {
                    drawCircle(halo, radius = side * 0.8f)
                    drawRoundRect(tile, cornerRadius = corner)
                    drawRoundRect(ring, cornerRadius = corner, style = ringStroke)
                    drawSparkle(center, side * 0.36f)
                    drawSparkle(Offset(size.width * 0.75f, size.height * 0.26f), side * 0.09f, plain = true)
                }
            },
    )
}

/**
 * Draws the four-point star used by the logo and the centre navigation button: a gradient
 * body (magenta → lilac → cyan) with a white-hot core and soft bloom.
 */
internal fun DrawScope.drawSparkle(center: Offset, radius: Float, plain: Boolean = false) {
    val path = sparklePath(center, radius)
    if (plain) {
        drawPath(path, Color.White.copy(alpha = 0.9f))
        return
    }
    drawCircle(
        Brush.radialGradient(listOf(Color.White.copy(alpha = 0.45f), StudioViolet.copy(alpha = 0.25f), Color.Transparent), center, radius * 1.3f),
        radius = radius * 1.3f,
        center = center,
    )
    drawPath(
        path,
        Brush.linearGradient(
            listOf(StudioMagenta, StudioLilac, StudioCyan),
            start = Offset(center.x - radius, center.y - radius),
            end = Offset(center.x + radius, center.y + radius),
        ),
    )
    drawPath(
        path,
        Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0.0f)), center, radius * 0.55f),
    )
}

private fun sparklePath(center: Offset, radius: Float): Path {
    val waist = radius * SPARKLE_WAIST
    val (x, y) = center
    return Path().apply {
        moveTo(x, y - radius)
        quadraticTo(x + waist, y - waist, x + radius, y)
        quadraticTo(x + waist, y + waist, x, y + radius)
        quadraticTo(x - waist, y + waist, x - radius, y)
        quadraticTo(x - waist, y - waist, x, y - radius)
        close()
    }
}

/** Circular profile button with a neon ring. */
@Composable
fun ProfileOrb(onClick: (() -> Unit)?, modifier: Modifier = Modifier, size: Dp = 46.dp) {
    val label = stringResource(R.string.action_profile)
    val interaction = if (onClick != null) {
        Modifier
            .semantics { contentDescription = label }
            .tactileClickable(haptic = HapticPattern.Click, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .size(size)
            .drawWithCache {
                val ring = Brush.sweepGradient(listOf(StudioCyan, StudioViolet, StudioMagenta, StudioBlue, StudioCyan), this.size.center)
                val fill = Brush.radialGradient(listOf(Color(0xFF1A2466), Color(0xFF070C2C)), this.size.center, this.size.minDimension / 2f)
                val stroke = 2.dp.toPx()
                val r = this.size.minDimension / 2f - stroke
                onDrawBehind {
                    for (i in 3 downTo 1) drawCircle(ring, r + stroke * i * 1.4f, alpha = 0.08f * (4 - i), style = Stroke(stroke * 1.4f))
                    drawCircle(fill, r)
                    drawCircle(ring, r, style = Stroke(stroke))
                }
            }
            .then(interaction),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.52f))
    }
}

/** A navy tile with corner brackets and a glowing well around [icon], tinted by [tint]. */
@Composable
fun NeonIconTile(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .drawWithCache {
                val side = this.size.minDimension
                val bracket = side * 0.22f
                val bracketStroke = 2.dp.toPx()
                val inset = side * 0.16f
                val wellSize = Size(side - inset * 2, side - inset * 2)
                val corner = CornerRadius(side * 0.14f)
                val well = Brush.radialGradient(listOf(tint.copy(alpha = 0.22f), tint.copy(alpha = 0.04f)), this.size.center, side * 0.5f)
                onDrawBehind {
                    for (i in 3 downTo 1) {
                        drawRoundRect(
                            tint,
                            topLeft = Offset(inset, inset),
                            size = wellSize,
                            cornerRadius = corner,
                            alpha = 0.07f * (4 - i),
                            style = Stroke(i * 3.dp.toPx()),
                        )
                    }
                    drawRoundRect(well, topLeft = Offset(inset, inset), size = wellSize, cornerRadius = corner)
                    drawRoundRect(tint, topLeft = Offset(inset, inset), size = wellSize, cornerRadius = corner, style = Stroke(1.6.dp.toPx()))
                    val w = this.size.width
                    val h = this.size.height
                    val c = tint.copy(alpha = 0.85f)
                    drawLine(c, Offset(0f, bracket), Offset(0f, 0f), bracketStroke)
                    drawLine(c, Offset(0f, 0f), Offset(bracket, 0f), bracketStroke)
                    drawLine(c, Offset(w - bracket, 0f), Offset(w, 0f), bracketStroke)
                    drawLine(c, Offset(w, 0f), Offset(w, bracket), bracketStroke)
                    drawLine(c, Offset(w, h - bracket), Offset(w, h), bracketStroke)
                    drawLine(c, Offset(w, h), Offset(w - bracket, h), bracketStroke)
                    drawLine(c, Offset(bracket, h), Offset(0f, h), bracketStroke)
                    drawLine(c, Offset(0f, h), Offset(0f, h - bracket), bracketStroke)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.4f))
    }
}

private val TAGLINE = Color(0xFF9CC6FF)
private const val SPARKLE_WAIST = 0.17f
