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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
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
import com.hakim.liquify.shadow.InnerShadow
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
 * Optical recipe per preset: [GlassMaterial] plus how the surface casts depth.
 *
 * The material is deliberately *thin* — definition comes from the rim lens
 * (refractionHeight/Amount) and from light response, never from frosting the
 * backdrop into a blur blob or painting a shiny stroke. Blur stays low so the
 * artwork/background is still recognizable behind the glass.
 */
private data class GlassRecipe(
    val material: GlassMaterial,
    val rim: Highlight?,
    val shadow: Shadow?,
    val innerShadow: InnerShadow?,
)

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
    // Contextual translucency: cap the fill alpha so the refracted backdrop
    // (artwork, content) shows through the glass instead of the surface
    // reading as an opaque card. The cap is theme-aware — dark glass deepens
    // slightly, light glass lifts slightly — and stays high enough that the
    // legibility veil keeps text readable over a complex background.
    // Backdrop may be null (canvas fallback) — the alpha still applies there.
    val cap = if (LocalIsDarkTheme.current) 0.50f else 0.56f
    color.copy(alpha = minOf(color.alpha, cap))
} else color

@Composable
fun isLiquidGlassEnabled(): Boolean = LocalLiquidGlass.current

/**
 * Apple-style mapping: preset -> optical [GlassRecipe].
 *
 * Shared principles (this is what makes it read as *material*, not a filter):
 *  - blur is the *lowest* value that still separates foreground from background;
 *    the artwork/background must stay recognizable. Refraction (the rim lens) is
 *    what supplies the "glass" read, not frost.
 *  - refraction stays subtle and proportional to element size; never fisheye.
 *  - saturation is near 1.0 — just enough vibrancy that saturated artwork picks
 *    up a whisper of color. Never so high it looks like a color filter.
 *  - rim highlight is a sub-pixel hairline (Highlight width 0.5dp), faint, and
 *    *absent* on large flat surfaces (nav bar / mini-player / overlays) where a
 *    lit rim reads as a cheap border. Definition there comes from depth.
 *  - depth is a *soft* drop shadow punched out from under the element, so it
 *    floats; plus a whisper of inner shadow so the pane reads as having mass.
 *  - NO specular gloss strips, NO bright strokes, NO fake diagonal gradients.
 */
private fun glassRecipeForPreset(preset: LiquidGlassPreset): GlassRecipe = when (preset) {
    // ── Large flat surfaces: pure translucency + depth, no rim, no frost ──
    LiquidGlassPreset.BottomNavigation -> GlassRecipe(
        material = GlassMaterial(
            blurRadius = 10.dp,
            refractionHeight = 14.dp,
            refractionAmount = 18.dp,
            saturation = 1.06f,
            depthEffect = true,
        ),
        rim = null,
        shadow = Shadow(radius = 26.dp, color = Color.Black.copy(alpha = 0.14f)),
        innerShadow = InnerShadow(radius = 10.dp, color = Color.Black.copy(alpha = 0.05f)),
    )
    LiquidGlassPreset.MiniPlayer -> GlassRecipe(
        material = GlassMaterial(
            blurRadius = 9.dp,
            refractionHeight = 13.dp,
            refractionAmount = 16.dp,
            saturation = 1.05f,
            depthEffect = true,
        ),
        rim = null,
        shadow = Shadow(radius = 22.dp, color = Color.Black.copy(alpha = 0.13f)),
        innerShadow = InnerShadow(radius = 9.dp, color = Color.Black.copy(alpha = 0.05f)),
    )
    // ── Sheets / menus: a touch more frost for legibility, still thin ──
    LiquidGlassPreset.ModalSheet -> GlassRecipe(
        material = GlassMaterial(
            blurRadius = 14.dp,
            refractionHeight = 16.dp,
            refractionAmount = 20.dp,
            saturation = 1.08f,
            depthEffect = true,
        ),
        rim = null,
        shadow = Shadow(radius = 30.dp, color = Color.Black.copy(alpha = 0.15f)),
        innerShadow = InnerShadow(radius = 12.dp, color = Color.Black.copy(alpha = 0.05f)),
    )
    LiquidGlassPreset.ContextMenu -> GlassRecipe(
        material = GlassMaterial(
            blurRadius = 13.dp,
            refractionHeight = 15.dp,
            refractionAmount = 19.dp,
            saturation = 1.08f,
            depthEffect = true,
        ),
        rim = null,
        shadow = Shadow(radius = 26.dp, color = Color.Black.copy(alpha = 0.15f)),
        innerShadow = InnerShadow(radius = 11.dp, color = Color.Black.copy(alpha = 0.05f)),
    )
    LiquidGlassPreset.Overlay -> GlassRecipe(
        material = GlassMaterial(
            blurRadius = 11.dp,
            refractionHeight = 13.dp,
            refractionAmount = 16.dp,
            saturation = 1.05f,
            depthEffect = true,
        ),
        rim = null,
        shadow = Shadow(radius = 20.dp, color = Color.Black.copy(alpha = 0.12f)),
        innerShadow = InnerShadow(radius = 9.dp, color = Color.Black.copy(alpha = 0.04f)),
    )
    // ── Small floating controls / cards: definition comes from a whisper-thin
    //    hairline rim + the lens, so they read as discrete glass objects. ──
    LiquidGlassPreset.PlayerControls,
    LiquidGlassPreset.FloatingControls -> GlassRecipe(
        material = GlassMaterial(
            blurRadius = 7.dp,
            refractionHeight = 16.dp,
            refractionAmount = 22.dp,
            saturation = 1.07f,
            depthEffect = true,
        ),
        rim = Highlight(width = 0.5.dp, blurRadius = 0.5.dp, alpha = 0.32f),
        shadow = Shadow(radius = 18.dp, color = Color.Black.copy(alpha = 0.14f)),
        innerShadow = InnerShadow(radius = 8.dp, color = Color.Black.copy(alpha = 0.05f)),
    )
    LiquidGlassPreset.Card -> GlassRecipe(
        material = GlassMaterial(
            blurRadius = 8.dp,
            refractionHeight = 14.dp,
            refractionAmount = 18.dp,
            saturation = 1.06f,
            depthEffect = true,
        ),
        rim = Highlight(width = 0.5.dp, blurRadius = 0.5.dp, alpha = 0.26f),
        shadow = Shadow(radius = 18.dp, color = Color.Black.copy(alpha = 0.12f)),
        innerShadow = InnerShadow(radius = 9.dp, color = Color.Black.copy(alpha = 0.04f)),
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
    val recipe = remember(preset) { glassRecipeForPreset(preset) }
    // Subtle press illumination only on interactive controls; bars/cards stay
    // static for perf (they're large surfaces; an animating full-size layer
    // would force a re-blur every frame).
    val interactive = preset == LiquidGlassPreset.FloatingControls ||
        preset == LiquidGlassPreset.PlayerControls
    val tint = fallbackTintOnly(shape, LocalIsDarkTheme.current)
    // NOTE: liquify() is @Composable and the compiler forbids composable
    // invocations inside runCatching/try-catch. Device risk is already gated
    // above (null backdrop, isDeviceGlassCapable); call it directly.
    return this.liquify(
        shape = shape,
        material = recipe.material,
        backdrop = backdrop,
        highlight = recipe.rim,
        shadow = recipe.shadow,
        innerShadow = recipe.innerShadow,
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
/**
 * Subtle contextual veil under the real glass so foreground text/icons stay
 * legible over arbitrary backdrops. This is the "contextual light response"
 * layer: in dark mode it deepens slightly, in light mode it lifts slightly,
 * letting the refracted backdrop (and any album-art color) show through while
 * keeping contrast. The refraction/blur itself comes from [liquify]; this is
 * only the legibility veil — kept separate so it never triggers a second
 * blur pass, and faint enough that it never reads as an opaque card.
 */
private fun Modifier.fallbackTintOnly(shape: Shape, isDark: Boolean): Modifier = drawWithCache {
    if (!size.width.isFinite() || !size.height.isFinite() || size.width <= 0f || size.height <= 0f) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }
    val outline = shape.createOutline(size, layoutDirection, this)
    val veil = if (isDark) {
        Color.Black.copy(alpha = 0.05f)
    } else {
        Color.White.copy(alpha = 0.07f)
    }
    onDrawWithContent {
        drawOutline(outline, veil)
        drawContent()
    }
}

/**
 * Canvas fallback glass — used when the GPU liquify path is unavailable
 * (preview, API < 31, low-RAM, software rendering). Pure [drawWithCache]
 * gradients: zero offscreen layers, zero shaders, zero allocations per frame.
 *
 * This must read as *optical depth*, not "blur + shiny border". So:
 *  - a translucent substrate keyed to the theme (dark glass over dark, light
 *    over light) supplies the translucency + legibility;
 *  - a *very* soft top-light gradient supplies the light interaction — a faint
 *    lift toward the light source, fading to nothing, with a whisper of
 *    darkening at the far edge for depth. This is illumination, NOT a gloss
 *    strip: alphas stay in the 0.02–0.07 range and it never reaches a hard edge;
 *  - a soft ambient drop shadow around the outline supplies the float/depth.
 *
 * There is deliberately NO border stroke, NO bright specular rim, NO fake
 * diagonal pink/blue "refraction". Separation comes from light + depth.
 */
fun Modifier.canvasLiquidGlassChrome(shape: Shape, isDark: Boolean = true): Modifier = drawWithCache {
    if (!size.width.isFinite() || !size.height.isFinite() || size.width <= 0f || size.height <= 0f) {
        return@drawWithCache onDrawWithContent { drawContent() }
    }
    val outline = shape.createOutline(size, layoutDirection, this)

    // Translucent substrate: the glass body. Dark theme deepens slightly; light
    // theme lifts slightly. Alpha is high enough for legibility, low enough to
    // read as translucent rather than an opaque card.
    val substrate = if (isDark) {
        Color(0xFF14161D).copy(alpha = 0.46f)
    } else {
        Color(0xFFFFFFFF).copy(alpha = 0.52f)
    }

    // Soft directional light from above: brightest just under the top edge,
    // fading to transparent by ~35% down, then a whisper of ambient occlusion
    // toward the bottom. Subtle enough to read as material light response.
    val lightInteraction = if (isDark) {
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.05f),
            0.35f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.05f),
            startY = 0f,
            endY = size.height,
        )
    } else {
        Brush.verticalGradient(
            0f to Color.White.copy(alpha = 0.10f),
            0.35f to Color.Transparent,
            1f to Color(0xFF808080).copy(alpha = 0.05f),
            startY = 0f,
            endY = size.height,
        )
    }

    onDrawWithContent {
        // Soft ambient shadow so the pane floats off the content. Drawn as a
        // blurred dark outline *behind* the body (punched-down), not a glow.
        val shadowBrush = Brush.verticalGradient(
            0f to Color.Transparent,
            1f to Color.Black.copy(alpha = if (isDark) 0.10f else 0.12f),
            startY = 0f,
            endY = size.height,
        )
        drawOutline(outline, shadowBrush)
        drawOutline(outline, substrate)
        drawOutline(outline, lightInteraction)
        drawContent()
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
 * Responds to touch with a soft press illumination and its rim lens — no
 * translate/stretch, so it never fights a parent press-scale or scroll.
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
    val recipe = remember { glassRecipeForPreset(LiquidGlassPreset.FloatingControls) }
    // NOTE: liquify() is @Composable — it cannot sit inside runCatching.
    // Capability is pre-gated (backdrop != null && capable); call directly.
    // dragging/stretches stay off: the response is a soft press illumination
    // plus the rim lens, not a translate — so it never fights a parent
    // press-scale or scroll. Keeps it smooth + interruptible.
    val gelModifier = if (backdrop != null && capable) {
        Modifier.liquify(
            shape = shape,
            material = recipe.material,
            backdrop = backdrop,
            highlight = recipe.rim,
            shadow = recipe.shadow,
            innerShadow = recipe.innerShadow,
            dragging = false,
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
