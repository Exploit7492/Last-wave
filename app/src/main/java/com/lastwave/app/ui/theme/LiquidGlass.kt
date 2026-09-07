package com.lastwave.app.ui.theme

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
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
    MiniPlayer(28f, 24f, 38f),
    BottomNavigation(26f, 22f, 34f),
    PlayerControls(22f, 20f, 28f),
    FloatingControls(20f, 18f, 26f),
    ModalSheet(32f, 28f, 34f),
    ContextMenu(24f, 22f, 28f),
    Overlay(26f, 24f, 30f),
    Card(16f, 14f, 18f),
}

@Composable
fun isLiquidGlassBackdropSupported(): Boolean =
    LocalLiquidGlass.current && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        // Android 16 changed RenderNode/RuntimeShader behavior used by
        // Kyant0 backdrop 1.0.0. Keep the opt-in fallback until a compatible
        // library release is available rather than crashing the composition.
        Build.VERSION.SDK_INT < 36 &&
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
    color.copy(alpha = minOf(color.alpha, 0.08f))
} else if (enabled) {
    color.copy(alpha = minOf(color.alpha, 0.74f))
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
    val tint = MaterialTheme.colorScheme.surface.copy(alpha = if (dark) 0.16f else 0.10f)
    val health = remember(backdrop) { GlassEffectHealth() }
    val highlight = remember(dark) { Highlight(alpha = if (dark) 0.52f else 0.36f) }

    // Memoize the drawBackdrop modifier chain to prevent recreating shaders on every scroll recomposition
    val glassModifier = remember(backdrop, shape, preset, dark) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                // Kyant0 also invokes effects on attachment, before layout supplies a size.
                if (!size.isSpecified || size.width <= 0f || size.height <= 0f) return@drawBackdrop
                if (health.blurAvailable) {
                    try {
                        colorControls(saturation = if (dark) 1.35f else 1.25f)
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
                        lens(preset.lensHeight.dp.toPx(), preset.lensAmount.dp.toPx())
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
    return this.then(glassModifier)
}

/**
 * Convenience container wrapping arbitrary content in a liquid-glass surface.
 * Consumers must be siblings of the composable carrying the layerBackdrop source.
 */
@Composable
fun LiquidGlassContainer(
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    preset: LiquidGlassPreset = LiquidGlassPreset.Card,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier.liquidGlassChrome(shape, enabled = true, preset = preset, backdrop = backdrop),
        contentAlignment = contentAlignment,
        content = content,
    )
}

/**
 * Floating action pill hosting icon buttons in a liquid glass shell.
 */
@Composable
fun LiquidGlassActionPill(
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
    preset: LiquidGlassPreset = LiquidGlassPreset.FloatingControls,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .height(48.dp)
            .liquidGlassChrome(shape, enabled = true, preset = preset, backdrop = backdrop),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/**
 * Circular liquid glass button for action icons and back navigation.
 */
@Composable
fun LiquidGlassIconButton(
    backdrop: Backdrop?,
    painter: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.size(48.dp),
    shape: Shape = CircleShape,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .liquidGlassChrome(shape, enabled = true, preset = LiquidGlassPreset.FloatingControls, backdrop = backdrop)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(24.dp),
        )
    }
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
