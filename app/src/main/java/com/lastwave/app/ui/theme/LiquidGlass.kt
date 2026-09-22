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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lastwave.trueglass.compose.TrueGlassBackdropState
import com.lastwave.trueglass.compose.rememberTrueGlassBackdrop
import com.lastwave.trueglass.compose.staticTrueGlass
import com.lastwave.trueglass.compose.trueGlassChrome
import com.lastwave.trueglass.compose.trueGlassSource
import com.lastwave.trueglass.engine.CapabilityGate
import com.lastwave.trueglass.engine.GlassConfig
import com.lastwave.trueglass.engine.GlassTier

/** Shared opt-in flag for Settings > Experimental > Liquid Glass. */
val LocalLiquidGlass = staticCompositionLocalOf { false }

// Background-only source for surfaces inside the captured scrolling content.
// TrueGlass GPU backdrop (RenderNode offscreen, never a Bitmap, never EGL).
val LocalLiquidGlassBackdrop = staticCompositionLocalOf<TrueGlassBackdropState?> { null }
// Separate source for overlays; never attach it to a parent of its consumers.
val LocalLiquidGlassOverlayBackdrop = staticCompositionLocalOf<TrueGlassBackdropState?> { null }

/**
 * Backwards-compatible aliases so existing call sites keep compiling after the
 * Liquify → TrueGlass strip. New code should use TrueGlassBackdropState.
 */
typealias Backdrop = TrueGlassBackdropState
typealias LayerBackdrop = TrueGlassBackdropState

@Composable
fun rememberLayerBackdrop(): TrueGlassBackdropState = rememberTrueGlassBackdrop()

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
 * Central capability gate — single reason this stack cannot crash.
 * Delegates to TrueGlass CapabilityGate (no per-view EGL, no bitmap capture):
 * - preview / API <31 / low-RAM / software → STATIC canvas fallback
 * - API 31-32 → system blur only
 * - API 33+ → full AGSL refraction + blur chain in RenderThread
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

@Composable
fun isLiquidGlassBackdropSupported(): Boolean = isDeviceGlassCapable()

@Composable
fun Modifier.liquidGlassSource(
    backdrop: TrueGlassBackdropState?,
): Modifier {
    if (backdrop == null) return this
    if (!isDeviceGlassCapable()) return this
    return this.trueGlassSource(backdrop)
}

@Composable
fun liquidGlassContainerColor(
    color: Color,
    enabled: Boolean = LocalLiquidGlass.current,
    backdrop: TrueGlassBackdropState? = LocalLiquidGlassBackdrop.current,
): Color = if (enabled) {
    // Optical substrate, not a frosted card: deliberately thin so the
    // refracted backdrop stays visible (artwork preserved). Legibility comes
    // from the AGSL lens — theme-driven highlight compression + adaptive
    // gain + bevel lighting — not from stacking opaque white here.
    val cap = if (LocalIsDarkTheme.current) 0.18f else 0.22f
    color.copy(alpha = minOf(color.alpha, cap))
} else color

@Composable
fun isLiquidGlassEnabled(): Boolean = LocalLiquidGlass.current

/**
 * Apple-style mapping: preset -> true [GlassConfig].
 * Blur stays low so the backdrop is recognizable; the SDF bevel lens +
 * Snell bend + bevel-only dispersion supplies the glass read, not frost.
 * Rim is a hairline whisper on small controls only, absent on large flats.
 */
private fun glassConfigForPreset(preset: LiquidGlassPreset): GlassConfig = when (preset) {
    LiquidGlassPreset.BottomNavigation -> GlassConfig.Flat.copy(
        cornerRadius = 28.dp, blur = 6.dp,
    )
    LiquidGlassPreset.MiniPlayer -> GlassConfig.Flat.copy(
        cornerRadius = 24.dp, blur = 5.dp,
    )
    LiquidGlassPreset.ModalSheet -> GlassConfig.Card.copy(
        cornerRadius = 28.dp, blur = 8.dp, thickness = 14.dp,
    )
    LiquidGlassPreset.ContextMenu -> GlassConfig.Card.copy(
        cornerRadius = 20.dp, blur = 7.dp, thickness = 12.dp,
    )
    LiquidGlassPreset.Overlay -> GlassConfig.Flat.copy(
        cornerRadius = 20.dp, blur = 6.dp,
    )
    LiquidGlassPreset.PlayerControls,
    LiquidGlassPreset.FloatingControls -> GlassConfig.Control
    LiquidGlassPreset.Card -> GlassConfig.Card
}

/**
 * True liquid glass via TrueGlass engine: sibling-recorded backdrop drawn
 * through a background-only chain (blur UNDER bevel-localized AGSL lens with
 * flat-center zero bend, bevel-only dispersion, adaptive luminance,
 * light-dependent rim + single glint) in RenderThread; foreground content
 * stays sharp on top.
 *
 * No white border / glow / gloss is ever painted here: edge definition comes
 * from refraction contrast + bevel lighting + soft elevation shadow.
 *
 * Crash-proofing: disabled → untouched; null backdrop → canvas fallback;
 * incapable device → canvas fallback; any driver rejection inside the engine
 * → null RenderEffect → canvas fallback. Never throws.
 */
@Composable
fun Modifier.liquidGlassChrome(
    shape: Shape,
    enabled: Boolean,
    preset: LiquidGlassPreset = LiquidGlassPreset.Card,
    backdrop: TrueGlassBackdropState? = LocalLiquidGlassBackdrop.current,
    interactionSource: MutableInteractionSource? = null,
): Modifier {
    if (!enabled) return this
    if (backdrop == null) {
        return this.then(staticTrueGlass(shape, LocalIsDarkTheme.current, interactionSource)).clip(shape)
    }
    if (!isDeviceGlassCapable()) {
        return this.then(staticTrueGlass(shape, LocalIsDarkTheme.current, interactionSource)).clip(shape)
    }
    val config = remember(preset) { glassConfigForPreset(preset) }
    return this.trueGlassChrome(
        shape = shape,
        config = config,
        backdrop = backdrop,
        enabled = true,
        isDark = LocalIsDarkTheme.current,
        interactionSource = interactionSource,
    )
}

/**
 * Convenience container wrapping arbitrary content in a liquid-glass surface.
 * Consumers must be siblings of the composable carrying the trueGlassSource.
 */
@Composable
fun LiquidGlassContainer(
    backdrop: TrueGlassBackdropState?,
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

/** Floating action pill hosting icon buttons in a liquid glass shell. */
@Composable
fun LiquidGlassActionPill(
    backdrop: TrueGlassBackdropState?,
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
 * Circular liquid glass button. Press drives the material itself (soft press
 * illumination + depth via shared interactionSource), never an opacity flash,
 * translate, or stretch. Interruptible spring, smoothly reversible.
 */
@Composable
fun LiquidGlassIconButton(
    backdrop: TrueGlassBackdropState?,
    painter: androidx.compose.ui.graphics.painter.Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.size(48.dp),
    shape: Shape = CircleShape,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    contentDescription: String? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val capable = isDeviceGlassCapable()
    val config = remember { glassConfigForPreset(LiquidGlassPreset.FloatingControls) }
    val gelModifier = if (backdrop != null && capable) {
        Modifier.trueGlassChrome(shape, config, backdrop, true, LocalIsDarkTheme.current, interaction)
    } else {
        Modifier.staticTrueGlass(shape, LocalIsDarkTheme.current, interaction)
    }
    Box(
        modifier = modifier
            .then(gelModifier)
            .clip(shape)
            .clickable(
                interactionSource = interaction,
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
