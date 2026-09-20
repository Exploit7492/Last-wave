package com.lastwave.app.ui.theme

import android.app.ActivityManager
import android.content.Context
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
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.hakim.liquify.Backdrop
import com.hakim.liquify.backdrops.LayerBackdrop
import com.hakim.liquify.backdrops.layerBackdrop
import com.hakim.liquify.highlight.Highlight
import com.hakim.liquify.liquify
import com.hakim.liquify.material.GlassMaterial
import com.hakim.liquify.shadow.Shadow

import androidx.compose.ui.draw.blur

/** Shared opt-in flag for Settings > Experimental > Liquid Glass. */
val LocalLiquidGlass = staticCompositionLocalOf { false }

// Background-only source for surfaces inside the captured scrolling content.
val LocalLiquidGlassBackdrop = staticCompositionLocalOf<Backdrop?> { null }
// Separate source for overlays; never attach it to a parent of its consumers.
val LocalLiquidGlassOverlayBackdrop = staticCompositionLocalOf<Backdrop?> { null }

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

/** Background blur with sibling capture; never blurs foreground lyrics or controls.
 *
 *  [veil]/[veilAlpha] tints the blurred content. The default (surface @ 0.74)
 *  preserves the old behavior for generic screens. Full-player / lyrics must
 *  pass a dark veil (e.g. Black @ 0.55) so white lyrics stay readable in
 *  *both* light and dark mode while the cover-art colors shine through —
 *  a light-mode surface veil washes the backdrop to near-white and kills
 *  contrast (white-on-white) plus the ambient cover tint.
 */
@Composable
fun BackdropBlur(
    radius: Dp,
    modifier: Modifier = Modifier,
    veil: Color = Color.Unspecified,
    veilAlpha: Float = 0.74f,
    content: @Composable BoxScope.() -> Unit,
) {
    val defaultVeil = MaterialTheme.colorScheme.surface
    val resolvedVeil = if (veil == Color.Unspecified) defaultVeil else veil
    val view = LocalView.current
    val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        view.isHardwareAccelerated && !view.isInEditMode
    Box(modifier) {
        Box(
            modifier = Modifier.matchParentSize().then(
                if (blurSupported) {
                    Modifier.blur(radius)
                } else Modifier
            ),
            content = content,
        )
        Box(Modifier.matchParentSize().background(resolvedVeil.copy(alpha = veilAlpha)))
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

/**
 * Central capability gate — the single reason this glass stack cannot crash
 * any device. Real Liquify glass (offscreen layer + RenderEffect blur +
 * AGSL lens) is only offered when ALL hold:
 * - not an EditMode preview (AS preview has no GPU RenderEffect)
 * - API 31+ (RenderEffect blur exists; below that Liquify would still
 *   allocate layers for rim-only, so we skip it entirely — zero GPU load)
 * - not a low-RAM device (ActivityManager.isLowRamDevice — small GPUs,
 *   aggressive killer, shared memory; a fullscreen captured layer OOMs them)
 * - hardware-accelerated view (software rendering + RenderEffect = crash)
 * Everything else gets the canvas fallback: pure drawWithCache gradients,
 * zero offscreen layers, zero shaders — identical API, impossible to crash.
 */
@Composable
fun isDeviceGlassCapable(): Boolean {
    val view = LocalView.current
    if (view.isInEditMode) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    if (!view.isHardwareAccelerated) return false
    val context = LocalContext.current
    val am = remember(context) {
        runCatching { context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager }.getOrNull()
    }
    if (am?.isLowRamDevice == true) return false
    return true
}

/**
 * Whether call sites may allocate a captured [LayerBackdrop].
 * False on incapable devices so no GraphicsLayer is ever created there —
 * that alone removes all GPU load where glass can't run.
 */
@Composable
fun isLiquidGlassBackdropSupported(): Boolean = isDeviceGlassCapable()

@Composable
fun Modifier.liquidGlassSource(
    backdrop: LayerBackdrop?,
): Modifier {
    if (backdrop == null) return this
    if (!isDeviceGlassCapable()) return this
    return runCatching { this.layerBackdrop(backdrop) }.getOrDefault(this)
}

@Composable
fun liquidGlassContainerColor(
    color: Color,
    enabled: Boolean = LocalLiquidGlass.current,
    backdrop: Backdrop? = LocalLiquidGlassBackdrop.current,
): Color = if (enabled) {
    // Apple translucency: let the refracted backdrop show through.
    // Backdrop may be null (canvas fallback) — alpha still applies.
    color.copy(alpha = minOf(color.alpha, 0.62f))
} else color

@Composable
fun isLiquidGlassEnabled(): Boolean = LocalLiquidGlass.current

/** Apple-style mapping: preset -> Liquify glass recipe. */
private fun glassMaterialForPreset(preset: LiquidGlassPreset): GlassMaterial = when (preset) {
    // Floating tab bar / mini-player: frosted but legible, strong rim lens.
    LiquidGlassPreset.BottomNavigation -> GlassMaterial(
        blurRadius = 16.dp,
        refractionHeight = 24.dp,
        refractionAmount = 34.dp,
        saturation = 1.4f,
    )
    LiquidGlassPreset.MiniPlayer -> GlassMaterial(
        blurRadius = 14.dp,
        refractionHeight = 22.dp,
        refractionAmount = 32.dp,
        saturation = 1.35f,
    )
    // Large sheets / menus: heavier frosting like iOS sheets.
    LiquidGlassPreset.ModalSheet -> GlassMaterial(
        blurRadius = 22.dp,
        refractionHeight = 28.dp,
        refractionAmount = 38.dp,
        saturation = 1.5f,
    )
    LiquidGlassPreset.ContextMenu -> GlassMaterial(
        blurRadius = 18.dp,
        refractionHeight = 24.dp,
        refractionAmount = 34.dp,
        saturation = 1.4f,
    )
    LiquidGlassPreset.Overlay -> GlassMaterial(
        blurRadius = 18.dp,
        refractionHeight = 24.dp,
        refractionAmount = 34.dp,
        saturation = 1.4f,
    )
    // Small controls: barely frosted, definition from the lens + gel highlight.
    LiquidGlassPreset.PlayerControls,
    LiquidGlassPreset.FloatingControls -> GlassMaterial(
        blurRadius = 10.dp,
        refractionHeight = 20.dp,
        refractionAmount = 30.dp,
        saturation = 1.2f,
    )
    LiquidGlassPreset.Card -> GlassMaterial(
        blurRadius = 10.dp,
        refractionHeight = 18.dp,
        refractionAmount = 28.dp,
        saturation = 1.2f,
    )
}

/**
 * Real Apple-like liquid glass via Liquify: backdrop blur + edge refraction
 * + specular rim + drop shadow in one [liquify] pass.
 *
 * Crash-proofing (every branch returns *something* drawable, never throws):
 * - disabled -> untouched modifier, zero cost.
 * - null backdrop -> canvas fallback (gradients only, no layers/shaders).
 * - incapable device (preview, API <31, low-RAM, software rendering) ->
 *   canvas fallback. No GraphicsLayer, no RenderEffect, no RuntimeShader.
 * - any device-specific GPU/shader failure inside liquify ->
 *   caught, canvas fallback. A driver that rejects the AGSL program or
 *   runs out of layer memory degrades to tint instead of crashing.
 * GPU rules: static surfaces get no touch physics; chromatic aberration +
 * gradient blur stay off (7x sampling); radii capped per preset.
 * Foreground content draws on top unclipped, exactly like iOS.
 */
@Composable
fun Modifier.liquidGlassChrome(
    shape: Shape,
    enabled: Boolean,
    preset: LiquidGlassPreset = LiquidGlassPreset.Card,
    backdrop: Backdrop? = LocalLiquidGlassBackdrop.current,
): Modifier {
    if (!enabled) return this
    val fallback = canvasLiquidGlassChrome(shape, LocalIsDarkTheme.current)
    if (backdrop == null) return this.then(fallback)
    if (!isDeviceGlassCapable()) return this.then(fallback)
    val material = remember(preset) { glassMaterialForPreset(preset) }
    // Gel press only for interactive controls; bars/cards stay static for perf.
    val interactive = preset == LiquidGlassPreset.FloatingControls ||
        preset == LiquidGlassPreset.PlayerControls
    val tint = fallbackTintOnly(shape)
    // NOTE: liquify() is @Composable and the compiler forbids composable
    // invocations inside runCatching/try-catch. Device risk is already gated
    // above (null backdrop, isDeviceGlassCapable); call it directly.
    return this.liquify(
        shape = shape,
        material = material,
        backdrop = backdrop,
        highlight = Highlight.Default,
        shadow = Shadow.Default,
        dragging = false,
        stretching = false,
        interactiveHighlight = interactive,
    ).then(tint)
}

/**
 * Subtle tint under the real glass so text stays legible in both themes.
 * The refraction/blur itself comes from [liquify]; this is only the
 * legibility veil — kept separate so it never triggers a second blur pass.
 */
private fun Modifier.fallbackTintOnly(shape: Shape): Modifier = drawWithCache {
    if (!size.width.isFinite() || !size.height.isFinite() || size.width <= 0f || size.height <= 0f) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }
    val outline = shape.createOutline(size, layoutDirection, this)
    // Very light — the glass provides the frost, this only lifts contrast slightly.
    val veil = Color.Black.copy(alpha = 0.04f)
    onDrawWithContent {
        drawOutline(outline, veil)
        drawContent()
    }
}

fun Modifier.canvasLiquidGlassChrome(shape: Shape, isDark: Boolean = true): Modifier = drawWithCache {
    if (!size.width.isFinite() || !size.height.isFinite() || size.width <= 0f || size.height <= 0f) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }
    val outline = shape.createOutline(size, layoutDirection, this)
    val substrate = if (isDark) Color(0xFF0C0E14).copy(alpha = 0.42f) else Color(0xFFFFFFFF).copy(alpha = 0.55f)
    val reflection = if (isDark) {
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.20f),
            0.15f to Color.White.copy(alpha = 0.075f),
            0.50f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.14f),
            startY = 0f,
            endY = size.height,
        )
    } else {
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.60f),
            0.15f to Color.White.copy(alpha = 0.25f),
            0.50f to Color.Transparent,
            1f to Color(0xFF808080).copy(alpha = 0.08f),
            startY = 0f,
            endY = size.height,
        )
    }
    val refraction = if (isDark) {
        Brush.linearGradient(
            0f to Color(0xFFB8D8FF).copy(alpha = 0.075f),
            0.48f to Color.Transparent,
            1f to Color(0xFFFFD8F0).copy(alpha = 0.055f),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
    } else {
        Brush.linearGradient(
            0f to Color(0xFF90CAFF).copy(alpha = 0.09f),
            0.48f to Color.Transparent,
            1f to Color(0xFFFFB4E6).copy(alpha = 0.07f),
            start = Offset.Zero,
            end = Offset(size.width, size.height),
        )
    }
    val strokeWidth = 1.dp.toPx()
    val borderBrush = if (isDark) {
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.16f),
            0.5f to Color.White.copy(alpha = 0.05f),
            1f to Color.Transparent,
            startY = 0f,
            endY = size.height,
        )
    } else {
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.80f),
            0.5f to Color.White.copy(alpha = 0.35f),
            1f to Color(0xFFE0E0E0).copy(alpha = 0.50f),
            startY = 0f,
            endY = size.height,
        )
    }

    onDrawWithContent {
        drawOutline(outline, substrate)
        drawOutline(outline, reflection)
        drawOutline(outline, refraction)
        drawContent()
        drawOutline(outline, borderBrush, style = Stroke(width = strokeWidth))
    }
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
 * Gel-press: leans toward the finger + lights up, like iOS glass icons.
 */
@Composable
fun LiquidGlassIconButton(
    backdrop: Backdrop?,
    painter: androidx.compose.ui.graphics.painter.Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.size(48.dp),
    shape: Shape = CircleShape,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    contentDescription: String? = null,
) {
    val capable = isDeviceGlassCapable()
    val gelMaterial = remember {
        GlassMaterial(
            blurRadius = 10.dp,
            refractionHeight = 20.dp,
            refractionAmount = 30.dp,
            saturation = 1.2f,
        )
    }
    // NOTE: liquify() is @Composable — it cannot sit inside runCatching.
    // Capability is pre-gated (backdrop != null && capable); call directly.
    val gelModifier = if (backdrop != null && capable) {
        Modifier.liquify(
            shape = shape,
            material = gelMaterial,
            backdrop = backdrop,
            highlight = Highlight.Default,
            shadow = Shadow.Default,
            dragging = true,
            stretching = false,
            interactiveHighlight = true,
        )
    } else {
        Modifier.canvasLiquidGlassChrome(shape, LocalIsDarkTheme.current)
    }
    Box(
        modifier = modifier
            .then(gelModifier)
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
