package com.lastwave.trueglass.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline

/**
 * Zero-GPU fallback. Pure drawWithCache gradients: no layers, no shaders,
 * no allocations per frame. Reads as optical depth (substrate + top light +
 * ambient shadow), never a border stroke / gloss strip / fake refraction.
 */
fun Modifier.staticTrueGlass(shape: Shape, isDark: Boolean = true): Modifier = drawWithCache {
    if (!size.width.isFinite() || !size.height.isFinite() || size.width <= 0f || size.height <= 0f) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }
    val outline = shape.createOutline(size, layoutDirection, this)
    val substrate = if (isDark) Color(0xFF14161D).copy(alpha = 0.46f)
    else Color(0xFFFFFFFF).copy(alpha = 0.52f)
    val light = if (isDark) {
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.05f),
            0.35f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.05f),
            startY = 0f, endY = size.height,
        )
    } else {
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.10f),
            0.35f to Color.Transparent,
            1f to Color(0xFF808080).copy(alpha = 0.05f),
            startY = 0f, endY = size.height,
        )
    }
    onDrawWithContent {
        val shadowBrush = Brush.verticalGradient(
            0f to Color.Transparent,
            1f to Color.Black.copy(alpha = if (isDark) 0.10f else 0.12f),
            startY = 0f, endY = size.height,
        )
        drawOutline(outline, shadowBrush)
        drawOutline(outline, substrate)
        drawOutline(outline, light)
        drawContent()
    }
}

fun Modifier.trueGlassVeil(shape: Shape, isDark: Boolean): Modifier = drawWithCache {
    if (!size.width.isFinite() || !size.height.isFinite() || size.width <= 0f || size.height <= 0f) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }
    val outline = shape.createOutline(size, layoutDirection, this)
    val veil = if (isDark) Color.Black.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.07f)
    onDrawWithContent {
        drawOutline(outline, veil)
        drawContent()
    }
}
