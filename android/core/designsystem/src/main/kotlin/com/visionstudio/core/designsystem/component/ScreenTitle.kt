package com.visionstudio.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.visionstudio.core.designsystem.theme.NeonBrushes
import com.visionstudio.core.designsystem.theme.StudioCyan
import com.visionstudio.core.designsystem.theme.StudioTextPrimary
import com.visionstudio.core.designsystem.theme.StudioViolet
import com.visionstudio.core.designsystem.theme.VisionStudioTheme

/**
 * Screen title block: a cyan eyebrow over a HUD rule, the oversized italic gradient title and an
 * optional description. [trailing] sits on the eyebrow row (an engine chip or a count).
 */
@Composable
fun ScreenTitle(
    eyebrow: String,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = eyebrow.uppercase(),
                    style = VisionStudioTheme.typography.eyebrow.copy(fontSize = 12.sp),
                    color = StudioCyan,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    Modifier
                        .width(EYEBROW_RULE)
                        .height(1.dp)
                        .drawWithCache {
                            val rule = Brush.horizontalGradient(listOf(StudioCyan, StudioCyan.copy(alpha = 0.3f), Color.Transparent))
                            onDrawBehind { drawRect(rule) }
                        },
                )
            }
            trailing?.invoke(this)
        }
        Text(
            text = title,
            style = VisionStudioTheme.typography.hero.copy(
                brush = NeonBrushes.Title,
                shadow = Shadow(StudioViolet.copy(alpha = 0.75f), Offset(0f, 4f), blurRadius = 28f),
            ),
            maxLines = 2,
            modifier = Modifier
                .padding(end = 8.dp)
                .semantics { heading() },
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
                color = StudioTextPrimary.copy(alpha = 0.88f),
            )
        }
    }
}

private val EYEBROW_RULE = 150.dp
