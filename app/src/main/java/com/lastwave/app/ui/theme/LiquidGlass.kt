package com.lastwave.app.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight

/** Shared opt-in flag for Settings > Experimental > Liquid Glass. */
val LocalLiquidGlass = staticCompositionLocalOf { false }

// Background-only source for surfaces inside the captured scrolling content.
val LocalLiquidGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }
// Content source for sibling overlays; never attach it to a parent of its consumers.
val LocalLiquidGlassOverlayBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

enum class LiquidGlassPreset(val blur: Float, val lensHeight: Float, val lensAmount: Float) {
    MiniPlayer(8f, 14f, 18f),
    BottomNavigation(6f, 12f, 14f),
    PlayerControls(6f, 12f, 16f),
    FloatingControls(5f, 10f, 16f),
    ModalSheet(12f, 18f, 12f),
    ContextMenu(10f, 16f, 12f),
    Overlay(8f, 14f, 16f),
    Card(4f, 8f, 6f),
}

@Composable
fun isLiquidGlassBackdropSupported(): Boolean =
    LocalLiquidGlass.current && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        LocalView.current.isHardwareAccelerated && !LocalView.current.isInEditMode

@Composable
fun Modifier.liquidGlassSource(
    backdrop: LayerBackdrop? = LocalLiquidGlassOverlayBackdrop.current,
): Modifier = if (isLiquidGlassBackdropSupported() && backdrop != null) layerBackdrop(backdrop) else this

@Composable
fun liquidGlassContainerColor(
    color: Color,
    enabled: Boolean = LocalLiquidGlass.current,
    backdrop: Backdrop? = LocalLiquidGlassBackdrop.current,
): Color = if (enabled && isLiquidGlassBackdropSupported() && backdrop != null) {
    color.copy(alpha = minOf(color.alpha, 0.12f))
} else if (enabled) {
    color.copy(alpha = minOf(color.alpha, 0.78f))
} else color

private class GlassEffectHealth {
    var lensAvailable = true
    var blurAvailable = true
}

@Composable
fun isLiquidGlassEnabled(): Boolean = LocalLiquidGlass.current

/** Kyant0 renders only the sampled background; foreground text and controls stay sharp. */
@Composable
fun Modifier.liquidGlassChrome(
    shape: Shape,
    enabled: Boolean,
    preset: LiquidGlassPreset = LiquidGlassPreset.Card,
    backdrop: Backdrop? = LocalLiquidGlassBackdrop.current,
): Modifier {
    if (!enabled) return this
    if (!isLiquidGlassBackdropSupported() || backdrop == null) return legacyLiquidGlassChrome(shape, true)

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val tint = MaterialTheme.colorScheme.surface.copy(alpha = if (dark) 0.24f else 0.14f)
    val health = remember(backdrop) { GlassEffectHealth() }
    val highlight = remember(dark) { Highlight(alpha = if (dark) 0.45f else 0.28f) }
    return drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            if (health.blurAvailable) {
                try {
                    colorControls(saturation = if (dark) 1.18f else 1.08f)
                    blur(preset.blur.dp.toPx())
                } catch (_: Throwable) {
                    health.blurAvailable = false
                    renderEffect = null
                }
            }
            if (health.blurAvailable && health.lensAvailable &&
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shape is CornerBasedShape
            ) {
                val reducedEffect = renderEffect
                val reducedPadding = padding
                try {
                    lens(preset.lensHeight.dp.toPx(), preset.lensAmount.dp.toPx(), depthEffect = true)
                } catch (_: Throwable) {
                    health.lensAvailable = false
                    renderEffect = reducedEffect
                    padding = reducedPadding
                }
            }
        },
        highlight = { highlight },
        shadow = null,
        onDrawSurface = { drawRect(if (health.blurAvailable) tint else tint.copy(alpha = 0.74f)) },
    )
}

/** Retained for Android 10/11, software rendering and surfaces without a backdrop. */
private fun Modifier.legacyLiquidGlassChrome(shape: Shape, enabled: Boolean): Modifier =
    if (!enabled) this else drawWithCache {
        if (size.width <= 0f || size.height <= 0f) {
            return@drawWithCache onDrawWithContent { drawContent() }
        }
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = when (outline) {
            is Outline.Rounded -> Path().apply { addRoundRect(outline.roundRect) }
            is Outline.Generic -> outline.path
            is Outline.Rectangle -> Path().apply { addRect(outline.rect) }
        }
        val substrate = Color(0xFF090A0D).copy(alpha = 0.38f)
        val reflection = Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.20f),
            0.16f to Color.White.copy(alpha = 0.075f),
            0.48f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.14f),
            startY = 0f,
            endY = size.height,
        )
        val refraction = Brush.linearGradient(
            0f to Color(0xFFB8D8FF).copy(alpha = 0.075f),
            0.48f to Color.Transparent,
            1f to Color(0xFFFFD8F0).copy(alpha = 0.055f),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
        onDrawWithContent {
            clipPath(path) {
                drawRect(substrate)
                drawRect(reflection)
                drawRect(refraction)
            }
            drawContent()
        }
    }
