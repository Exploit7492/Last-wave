package com.lastwave.trueglass.compose

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.composed
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline

/**
 * Zero-GPU fallback. Pure drawWithCache: no layers, no shaders, no
 * allocations per frame. Reads as optical depth (theme-keyed translucent
 * substrate + very soft top light + ambient depth), never a border stroke /
 * gloss strip / fake refraction / glow.
 *
 * Press (when [interactionSource] is supplied) lifts the top light a whisper
 * via a spring — interruptible, reversible, never an opacity flash.
 */
fun Modifier.staticTrueGlass(
    shape: Shape,
    isDark: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
): Modifier = composed {
    val pressed: Boolean by if (interactionSource != null) {
        interactionSource.collectIsPressedAsState()
    } else {
        remember { mutableStateOf(false) }
    }
    val pressAnim: Float by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(stiffness = 420f, dampingRatio = 0.78f),
        label = "staticGlassPress",
    )
    this.drawWithCache {
        if (!size.width.isFinite() || !size.height.isFinite() || size.width <= 0f || size.height <= 0f) {
            return@drawWithCache onDrawWithContent { drawContent() }
        }
        val outline = shape.createOutline(size, layoutDirection, this)
        // Translucent substrate keyed to theme; low enough to preserve any
        // backdrop detail that IS present, high enough for legibility.
        val substrate = if (isDark) Color(0xFF14161D).copy(alpha = 0.38f)
        else Color(0xFFFFFFFF).copy(alpha = 0.44f)
        val idleLight = if (isDark) {
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.05f),
                0.35f to Color.Transparent,
                1f to Color.Black.copy(alpha = 0.05f),
                startY = 0f, endY = size.height,
            )
        } else {
            Brush.verticalGradient(
                0f to Color.White.copy(alpha = 0.09f),
                0.35f to Color.Transparent,
                1f to Color(0xFF808080).copy(alpha = 0.05f),
                startY = 0f, endY = size.height,
            )
        }
        val depthBrush = Brush.verticalGradient(
            0f to Color.Transparent,
            1f to Color.Black.copy(alpha = if (isDark) 0.10f else 0.12f),
            startY = 0f, endY = size.height,
        )
        val edgeDepth = Brush.radialGradient(
            colors = listOf(Color.Transparent, Color.Black.copy(alpha = if (isDark) 0.07f else 0.09f)),
            center = Offset(size.width * 0.38f, size.height * 0.30f),
            radius = maxOf(size.width, size.height) * 0.9f,
        )
        onDrawWithContent {
            drawOutline(outline, depthBrush)
            drawOutline(outline, substrate)
            drawOutline(outline, edgeDepth)
            drawOutline(outline, idleLight)
            if (pressAnim > 0.003f) {
                val pa = (if (isDark) 0.07f else 0.09f) * pressAnim
                drawOutline(
                    outline,
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = pa),
                        0.4f to Color.Transparent,
                        startY = 0f, endY = size.height,
                    ),
                )
            }
            drawContent()
        }
    }
}

fun Modifier.trueGlassVeil(shape: Shape, isDark: Boolean): Modifier = drawWithCache {
    if (!size.width.isFinite() || !size.height.isFinite() || size.width <= 0f || size.height <= 0f) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }
    val outline = shape.createOutline(size, layoutDirection, this)
    val veil = if (isDark) Color.Black.copy(alpha = 0.04f) else Color.White.copy(alpha = 0.06f)
    onDrawWithContent {
        drawOutline(outline, veil)
        drawContent()
    }
}
