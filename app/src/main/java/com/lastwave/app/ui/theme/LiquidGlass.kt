package com.lastwave.app.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.colorControls
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow

/** Shared opt-in flag for Settings > Experimental > Liquid Glass. */
val LocalLiquidGlass = staticCompositionLocalOf { false }

// Background-only source for surfaces inside the captured scrolling content.
val LocalLiquidGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }
// Separate source for overlays; never attach it to a parent of its consumers.
val LocalLiquidGlassOverlayBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

/** Keeps glass inside Material's visual bounds while retaining its outer touch target. */
@Composable
fun LiquidGlassSurface(
    onClick: () -> Unit,
    glassModifier: Modifier,
    modifier: Modifier = Modifier,
    shape: Shape = RectangleShape,
    color: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(color),
    tonalElevation: Dp = 0.dp,
    shadowElevation: Dp = 0.dp,
    border: BorderStroke? = null,
    interactionSource: MutableInteractionSource? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = shape,
        color = Color.Transparent,
        contentColor = contentColor,
        interactionSource = interactionSource,
        enabled = enabled,
    ) {
        Surface(
            modifier = glassModifier,
            shape = shape,
            color = color,
            contentColor = contentColor,
            tonalElevation = tonalElevation,
            shadowElevation = shadowElevation,
            border = border,
            content = content,
        )
    }
}

/** Background blur with sibling capture; never blurs foreground lyrics or controls. */
@Composable
fun BackdropBlur(
    radius: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val view = LocalView.current
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        view.isHardwareAccelerated && !view.isInEditMode
    val backdrop = if (supported) rememberLayerBackdrop() else null
    val background = MaterialTheme.colorScheme.surface
    Box(modifier) {
        Box(
            Modifier.matchParentSize().then(
                if (backdrop != null) Modifier.layerBackdrop(backdrop) else Modifier,
            ),
            content = content,
        )
        if (backdrop != null) {
            Box(Modifier.matchParentSize().drawBackdrop(
                backdrop = backdrop,
                shape = { RectangleShape },
                effects = {
                    if (size.isSpecified && size.width.isFinite() && size.height.isFinite() &&
                        size.width > 0f && size.height > 0f
                    ) blur(radius.toPx())
                },
                highlight = null,
                shadow = null,
                onDrawBehind = { drawRect(background) },
            ))
        } else {
            Box(Modifier.matchParentSize().background(background.copy(alpha = 0.74f)))
        }
    }
}

enum class LiquidGlassPreset(val blur: Float, val lensHeight: Float, val lensAmount: Float) {
    MiniPlayer(12f, 12f, 16f),
    BottomNavigation(12f, 12f, 16f),
    PlayerControls(8f, 8f, 10f),
    FloatingControls(8f, 8f, 10f),
    ModalSheet(20f, 12f, 10f),
    ContextMenu(18f, 10f, 10f),
    Overlay(16f, 10f, 10f),
    Card(10f, 6f, 6f),
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
    color.copy(alpha = minOf(color.alpha, 0.08f))
} else if (enabled) {
    color.copy(alpha = minOf(color.alpha, 0.74f))
} else color

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
    if (!isLiquidGlassBackdropSupported() || backdrop == null) {
        return background(MaterialTheme.colorScheme.surface.copy(alpha = 0.74f), shape)
    }

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val tint = MaterialTheme.colorScheme.surface.copy(alpha = if (dark) 0.10f else 0.08f)
    val lensSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shape is CornerBasedShape
    val highlight = remember(dark, lensSupported) {
        Highlight(
            alpha = if (dark) 0.35f else 0.25f,
            style = if (lensSupported) HighlightStyle.Default else HighlightStyle.Plain,
        )
    }
    val shadow = remember { Shadow(radius = 6.dp, color = Color.Black.copy(alpha = 0.10f)) }

    // Memoize the drawBackdrop modifier chain to prevent recreating shaders on every scroll recomposition
    val glassModifier = remember(backdrop, shape, preset, dark, tint, highlight, lensSupported) {
        Modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                // Kyant0 also invokes effects on attachment, before layout supplies a size.
                if (!size.isSpecified || !size.width.isFinite() || !size.height.isFinite() ||
                    size.width <= 0f || size.height <= 0f
                ) return@drawBackdrop
                colorControls(saturation = if (dark) 1.15f else 1.10f)
                blur(preset.blur.dp.toPx())
                if (lensSupported) {
                    lens(
                        minOf(preset.lensHeight.dp.toPx(), size.minDimension / 2f),
                        minOf(preset.lensAmount.dp.toPx(), size.minDimension / 2f),
                        chromaticAberration = false,
                    )
                }
            },
            highlight = { highlight },
            shadow = { shadow },
            onDrawSurface = { drawRect(tint) },
        )
    }
    // Clip the sampled layer itself, not only the foreground Material surface.
    return this.clip(shape).then(glassModifier)
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
            .liquidGlassChrome(shape, enabled = true, preset = LiquidGlassPreset.FloatingControls, backdrop = backdrop)
            .clip(shape)
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
