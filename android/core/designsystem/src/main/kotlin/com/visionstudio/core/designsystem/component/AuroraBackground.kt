package com.visionstudio.core.designsystem.component

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.visionstudio.core.designsystem.theme.StudioBlue
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioLilac
import com.visionstudio.core.designsystem.theme.StudioMagenta
import com.visionstudio.core.designsystem.theme.StudioOnyx
import com.visionstudio.core.designsystem.theme.StudioViolet
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * True below a [StudioBackdrop]: screens then draw no backdrop of their own, so the app shell
 * paints the scene once behind the header, the screen and the navigation dock.
 */
val LocalStudioBackdrop = staticCompositionLocalOf { false }

/**
 * Screen background. Inside the app shell the shell has already painted the [StudioBackdrop],
 * so this is a plain full-size box; on its own (previews, tests) it paints the backdrop itself.
 */
@Composable
fun AuroraBackground(
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    if (LocalStudioBackdrop.current) {
        Box(modifier = modifier.fillMaxSize(), content = content)
    } else {
        StudioBackdrop(modifier = modifier, animated = animated, content = content)
    }
}

/**
 * The deep-space canvas: a navy gradient with violet and blue nebulae, a starfield, a glowing
 * cosmic eye at the top end, a planet horizon below it, thin HUD rails along both edges and a
 * cyan floor glow under the dock.
 *
 * The scene is built once per size and drawn in its own layer, so animating content on top never
 * re-records it. With [animated], only the eye's bloom breathes, in a separate light layer.
 */
@Composable
fun StudioBackdrop(
    modifier: Modifier = Modifier,
    animated: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val stars = remember { starfield() }
    val breath = if (animated) {
        rememberInfiniteTransition(label = "backdrop").animateFloat(
            initialValue = 0.7f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(BREATH_MILLIS), RepeatMode.Reverse),
            label = "eyeBreath",
        )
    } else {
        null
    }
    Box(modifier = modifier.fillMaxSize()) {
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawWithCache {
                    val scene = Scene(size.width, size.height, density)
                    onDrawBehind { drawScene(scene, stars) }
                },
        )
        Spacer(
            Modifier
                .fillMaxSize()
                .graphicsLayer()
                .drawWithCache {
                    val scene = Scene(size.width, size.height, density)
                    val bloom = Brush.radialGradient(
                        listOf(StudioCyan.copy(alpha = 0.22f), StudioBlue.copy(alpha = 0.12f), Color.Transparent),
                        center = scene.eye,
                        radius = scene.eyeHalfWidth * 1.25f,
                    )
                    onDrawBehind {
                        drawCircle(bloom, scene.eyeHalfWidth * 1.25f, scene.eye, alpha = breath?.value ?: 1f)
                    }
                },
        )
        CompositionLocalProvider(LocalStudioBackdrop provides true) {
            Box(Modifier.fillMaxSize(), content = content)
        }
    }
}

private class Star(val x: Float, val y: Float, val radius: Float, val alpha: Float, val color: Color, val flare: Boolean)

private fun starfield(): List<Star> {
    val random = Random(STAR_SEED)
    return List(STAR_COUNT) {
        // Bias toward the top: the nebula is there.
        val y = random.nextFloat().let { it * it }
        val tint = random.nextFloat()
        Star(
            x = random.nextFloat(),
            y = y,
            radius = 0.3f + random.nextFloat() * 1.1f,
            alpha = 0.25f + random.nextFloat() * 0.75f,
            color = when {
                tint < 0.15f -> StudioCyan
                tint < 0.25f -> StudioLilac
                else -> Color.White
            },
            flare = random.nextFloat() < 0.035f,
        )
    }
}

/** Geometry of the scene for one size. */
private class Scene(val width: Float, val height: Float, val density: Float) {
    val eye = Offset(width * 0.80f, height * 0.165f)
    val eyeHalfWidth = width * 0.31f
    val eyeHalfHeight = width * 0.13f
    val planetRadius = width * 0.70f
    val planet = Offset(width * 1.05f, height * 0.25f + planetRadius)

    val almond = Path().apply {
        val (cx, cy) = eye
        val a = eyeHalfWidth
        val b = eyeHalfHeight * 1.35f
        moveTo(cx - a, cy)
        cubicTo(cx - a * 0.45f, cy - b, cx + a * 0.45f, cy - b, cx + a, cy)
        cubicTo(cx + a * 0.45f, cy + b, cx - a * 0.45f, cy + b, cx - a, cy)
        close()
    }

    val railLeft = Path().apply {
        val inset = 5f * density
        moveTo(inset + 8f * density, height * 0.20f)
        lineTo(inset, height * 0.20f + 10f * density)
        lineTo(inset, height * 0.58f)
        lineTo(inset + 8f * density, height * 0.58f + 10f * density)
    }
    val railRight = Path().apply {
        val x = width - 5f * density
        moveTo(x - 8f * density, height * 0.34f)
        lineTo(x, height * 0.34f + 10f * density)
        lineTo(x, height * 0.74f)
        lineTo(x - 8f * density, height * 0.74f + 10f * density)
    }
}

private fun DrawScope.drawScene(scene: Scene, stars: List<Star>) {
    val w = scene.width
    val h = scene.height
    drawRect(Brush.verticalGradient(listOf(Color(0xFF070C30), StudioOnyx, Color(0xFF040824), Color(0xFF060A2C))))

    // Nebulae.
    nebula(Offset(w * 0.86f, h * 0.06f), w * 0.75f, StudioViolet, 0.45f)
    nebula(Offset(w * 0.62f, h * 0.20f), w * 0.55f, StudioBlue, 0.32f)
    nebula(Offset(w * 1.02f, h * 0.0f), w * 0.40f, StudioMagenta, 0.30f)
    nebula(Offset(w * 0.10f, h * 0.08f), w * 0.45f, StudioBlue, 0.14f)
    nebula(Offset(w * 0.95f, h * 0.62f), w * 0.55f, StudioViolet, 0.12f)
    nebula(Offset(w * 0.05f, h * 0.78f), w * 0.60f, StudioCyan, 0.08f)

    // Stars.
    val unit = scene.density
    stars.forEach { star ->
        val center = Offset(star.x * w, star.y * h)
        drawCircle(star.color, star.radius * unit, center, alpha = star.alpha)
        if (star.flare) {
            val arm = 6f * unit
            drawLine(star.color, Offset(center.x - arm, center.y), Offset(center.x + arm, center.y), 0.6f * unit, alpha = star.alpha * 0.7f)
            drawLine(star.color, Offset(center.x, center.y - arm), Offset(center.x, center.y + arm), 0.6f * unit, alpha = star.alpha * 0.7f)
            drawCircle(star.color, arm * 0.8f, center, alpha = star.alpha * 0.12f)
        }
    }

    drawPlanet(scene)
    drawEye(scene)

    // HUD rails.
    val railBrush = Brush.verticalGradient(listOf(Color.Transparent, StudioCyan.copy(alpha = 0.55f), StudioBlue.copy(alpha = 0.3f), Color.Transparent))
    drawPath(scene.railLeft, railBrush, style = Stroke(1f * unit))
    drawPath(scene.railRight, railBrush, style = Stroke(1f * unit))

    // Floor glow under the dock.
    nebula(Offset(w * 0.5f, h * 1.02f), w * 0.65f, StudioCyan, 0.22f)
    nebula(Offset(w * 0.5f, h * 1.0f), w * 0.9f, StudioViolet, 0.16f)

    // Vignette keeps the edges deep.
    drawRect(
        Brush.radialGradient(
            listOf(Color.Transparent, StudioOnyx.copy(alpha = 0.55f)),
            center = Offset(w * 0.5f, h * 0.45f),
            radius = maxOf(w, h) * 0.85f,
        ),
    )
}

private fun DrawScope.nebula(center: Offset, radius: Float, color: Color, alpha: Float) {
    drawCircle(
        Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.35f), Color.Transparent), center, radius),
        radius,
        center,
    )
}

private fun DrawScope.drawPlanet(scene: Scene) {
    val r = scene.planetRadius
    val c = scene.planet
    val unit = scene.density
    val top = c.y - r
    // Only the cap shows: a horizon that fades out before it reaches the cards below.
    clipRect(top = top - r * 0.2f, bottom = top + r * HORIZON_DEPTH) {
        drawCircle(
            Brush.verticalGradient(listOf(Color(0xFF16307A), Color(0xFF07103A), Color(0x00030620)), startY = top, endY = top + r * HORIZON_DEPTH),
            r,
            c,
        )
        val rim = Brush.verticalGradient(listOf(StudioCyan, StudioCyan.copy(alpha = 0.4f), Color.Transparent), startY = top, endY = top + r * HORIZON_DEPTH)
        for (i in 3 downTo 1) drawCircle(rim, r, c, alpha = 0.2f * (4 - i) / 3f, style = Stroke(i * 4f * unit))
        drawCircle(rim, r, c, style = Stroke(1.6f * unit))
    }
}

private fun DrawScope.drawEye(scene: Scene) {
    val a = scene.eyeHalfWidth
    val c = scene.eye
    val unit = scene.density
    drawCircle(
        Brush.radialGradient(listOf(StudioBlue.copy(alpha = 0.35f), StudioViolet.copy(alpha = 0.18f), Color.Transparent), c, a * 1.5f),
        a * 1.5f,
        c,
    )
    drawPath(scene.almond, Brush.radialGradient(listOf(Color(0xFF1B3C9E), Color(0xFF0A1440), Color(0xFF050A26)), c, a))

    val irisRadius = a * 0.40f
    clipPath(scene.almond) {
        drawCircle(
            Brush.radialGradient(
                0f to Color(0xFF01020A),
                0.30f to Color(0xFF01020A),
                0.36f to Color(0xFF9CF8FF),
                0.50f to Color(0xFF22C7FF),
                0.72f to Color(0xFF2F5BFF),
                0.90f to Color(0xFF6A2BFF),
                1f to Color(0xFF140A44),
                center = c,
                radius = irisRadius,
            ),
            irisRadius,
            c,
        )
        for (i in 0 until IRIS_STREAKS) {
            val angle = (i * 2.0 * PI / IRIS_STREAKS).toFloat()
            val dx = cos(angle)
            val dy = sin(angle)
            drawLine(
                if (i % 2 == 0) Color.White else StudioCyan,
                Offset(c.x + dx * irisRadius * 0.38f, c.y + dy * irisRadius * 0.38f),
                Offset(c.x + dx * irisRadius * 0.96f, c.y + dy * irisRadius * 0.96f),
                0.7f * unit,
                alpha = if (i % 3 == 0) 0.4f else 0.16f,
            )
        }
        drawCircle(Color(0xFF01020A), irisRadius * 0.30f, c)
        drawCircle(Color.White, irisRadius * 0.08f, Offset(c.x - irisRadius * 0.12f, c.y - irisRadius * 0.14f), alpha = 0.9f)
        drawCircle(Color.White, irisRadius * 0.04f, Offset(c.x + irisRadius * 0.1f, c.y + irisRadius * 0.08f), alpha = 0.6f)
    }

    val lid = Brush.linearGradient(listOf(StudioCyan, StudioBlue, StudioViolet, StudioMagenta), Offset(c.x - a, c.y), Offset(c.x + a, c.y))
    for (i in 3 downTo 1) drawPath(scene.almond, lid, alpha = 0.1f * (4 - i), style = Stroke(i * 4f * unit))
    drawPath(scene.almond, lid, alpha = 0.9f, style = Stroke(1.6f * unit))
}

private const val STAR_SEED = 7
private const val STAR_COUNT = 230
private const val IRIS_STREAKS = 72
private const val BREATH_MILLIS = 3_600
private const val HORIZON_DEPTH = 0.32f
