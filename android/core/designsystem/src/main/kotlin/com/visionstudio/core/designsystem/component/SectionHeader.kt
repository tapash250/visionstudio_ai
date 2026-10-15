package com.visionstudio.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.visionstudio.core.designsystem.theme.StudioIndigoBright
import com.visionstudio.core.designsystem.theme.StudioTextSecondary
import com.visionstudio.core.designsystem.theme.VisionStudioTheme

/** Uppercase eyebrow followed by a prism hairline that fades into the canvas. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = title.uppercase(), style = VisionStudioTheme.typography.eyebrow, color = StudioTextSecondary)
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .drawWithCache {
                    val rule = Brush.horizontalGradient(listOf(StudioIndigoBright.copy(alpha = 0.6f), Color.Transparent))
                    onDrawBehind { drawRect(rule) }
                },
        )
        trailing?.invoke(this)
    }
}
