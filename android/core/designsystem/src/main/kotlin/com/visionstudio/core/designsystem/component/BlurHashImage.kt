package com.visionstudio.core.designsystem.component

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntSize
import com.visionstudio.core.common.image.BlurHash
import com.visionstudio.core.designsystem.theme.StudioGraphite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Paints a BlurHash placeholder stretched to fill its bounds. The hash is decoded off the main
 * thread into a 32 px software bitmap, uploaded to a hardware bitmap, and the software copy is
 * recycled straight away. Bilinear upscaling does the blurring for free.
 */
@Composable
fun BlurHashImage(hash: String?, modifier: Modifier = Modifier, punch: Float = 1f) {
    val image by produceState<ImageBitmap?>(initialValue = null, hash, punch) {
        value = hash?.let { withContext(Dispatchers.Default) { decodeBlurHash(it, punch) } }
    }
    Box(
        modifier = modifier.drawWithCache {
            val target = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1))
            onDrawBehind {
                val bitmap = image
                if (bitmap == null) {
                    drawRect(StudioGraphite)
                } else {
                    drawImage(bitmap, dstSize = target, filterQuality = FilterQuality.Low)
                }
            }
        },
    )
}

private fun decodeBlurHash(hash: String, punch: Float): ImageBitmap? {
    val pixels = BlurHash.decode(hash, DECODE_EDGE, DECODE_EDGE, punch) ?: return null
    val software = Bitmap.createBitmap(pixels, DECODE_EDGE, DECODE_EDGE, Bitmap.Config.ARGB_8888)
    val hardware = software.copy(Bitmap.Config.HARDWARE, false)
    return if (hardware != null) {
        software.recycle()
        hardware.asImageBitmap()
    } else {
        software.asImageBitmap()
    }
}

private const val DECODE_EDGE = 32
