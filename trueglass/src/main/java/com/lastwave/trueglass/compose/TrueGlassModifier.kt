package com.lastwave.trueglass.compose

import android.graphics.RuntimeShader
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.lastwave.trueglass.engine.CapabilityGate
import com.lastwave.trueglass.engine.GlassConfig
import com.lastwave.trueglass.engine.GlassEngine
import com.lastwave.trueglass.engine.GlassTier
import com.lastwave.trueglass.engine.TrueGlassShader

/**
 * True liquid-glass chrome for Compose: an optical material above the UI,
 * not a frosted card on top of it.
 *
 * Rendering model (GOOD, not blur+stroke+gradient):
 * underlying sibling content
 * → recorded GPU backdrop (RenderNode, never a Bitmap)
 * → panel-sized background-only chain (blur UNDER bevel-localized AGSL lens
 *   + interior magnification)
 * → theme-driven highlight compression + adaptive luminance preserving artwork
 * → bevel-only specular/rim + asymmetric inner-edge shade (no white stroke)
 * → soft elevation shadow (floating layer, no heavy drop)
 * → sharp foreground content on top (never blurred)
 *
 * No base veil and no press gradient are painted here: the caller's thin
 * translucent Surface color is the substrate, and press travels into the
 * shader itself (quantized to 4 buckets so each press builds the
 * RenderEffect at most a few times, never per animation frame).
 *
 * - FULL_REFRACT (33+, HW, !lowRam): background-only frost+lens in
 *   RenderThread. Foreground stays sharp.
 * - BLUR_ONLY (31-32): background-only blur, no RuntimeShader.
 * - STATIC / null backdrop / never-recorded backdrop / any failure:
 *   staticTrueGlass canvas fallback, zero GPU objects, still intentional.
 *
 * Geometry: uSize/uRadius follow the real panel size and [shape]
 * (CircleShape → min(w,h)/2; rounded shapes → config radius clamped to the
 * size). Thickness is clamped via [GlassConfig.thicknessForSize] so morphs
 * and resizes transition smoothly instead of collapsing to a hollow ring.
 *
 * Interaction: [interactionSource] press drives bend/spec/rim/luminance
 * response inside the AGSL lens (interruptible, reversible). The base
 * RenderEffect is rebuilt only when a press bucket crosses a threshold —
 * no re-blur storm. Null source = idle optics, still correct.
 *
 * Hierarchy: shares one sibling [backdrop] per scope; never samples its own
 * subtree, never stacks glass-on-glass rendering (sibling rule enforced by
 * callers). Panel-sized copies only — no fullscreen realtime passes.
 */
fun Modifier.trueGlassChrome(
    shape: Shape,
    config: GlassConfig = GlassConfig.Card,
    backdrop: TrueGlassBackdropState? = null,
    enabled: Boolean = true,
    isDark: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
): Modifier = composed {
    if (!enabled) return@composed this
    val view = LocalView.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val tier = remember(view, context) {
        runCatching {
            CapabilityGate.tier(context, view, view.isHardwareAccelerated)
        }.getOrDefault(GlassTier.STATIC)
    }
    val runtimeShader = remember {
        if (tier == GlassTier.FULL_REFRACT) {
            runCatching { RuntimeShader(TrueGlassShader.LENS_AGSL) }.getOrNull()
        } else null
    }
    // Never-recorded backdrops (theme locals with no attached source) must
    // not pay for a RenderEffect they can never sample: static fallback.
    // The .recorded read is snapshot-observed, so the 5 recorded surfaces
    // upgrade to the full lens automatically after their source's first frame.
    val src = backdrop?.takeIf { it.recorded }
    if (tier == GlassTier.STATIC || src == null) {
        return@composed this
            .then(staticTrueGlass(shape, isDark, interactionSource))
            .clip(shape)
    }

    val d = density.density
    var panelSize by remember { mutableStateOf(IntSize.Zero) }
    var panelCoords: LayoutCoordinates? by remember { mutableStateOf(null) }
    val bgLayer = rememberGraphicsLayer()

    val pressed: Boolean by if (interactionSource != null) {
        interactionSource.collectIsPressedAsState()
    } else {
        remember { mutableStateOf(false) }
    }
    // Quantized press → shader: spring runs continuously, but the bucket
    // only changes at 0 / 1/3 / 2/3 / 1 (≤3 writes per press edge). Each
    // write rebuilds the RenderEffect once — uniforms are immutable per
    // effect — so a full press cycle costs a handful of builds, not 60/s.
    var pressBucket by remember { mutableStateOf(0f) }
    LaunchedEffect(pressed) {
        animate(
            initialValue = pressBucket,
            targetValue = if (pressed) 1f else 0f,
            animationSpec = spring(stiffness = 420f, dampingRatio = 0.78f),
        ) { value, _ ->
            val bucketed = when {
                value < 0.17f -> 0f
                value < 0.50f -> 1f / 3f
                value < 0.83f -> 2f / 3f
                else -> 1f
            }
            if (bucketed != pressBucket) pressBucket = bucketed
        }
    }

    // Theme-driven highlight compression: dark themes dim bright/washed
    // backdrops hard for legibility; light themes only whisper (must not
    // turn white backgrounds gray behind dark text).
    val highlight = if (isDark) 1.35f else 0.4f

    // Geometry-aware radius + safe thickness, resolved per real size.
    // CircleShape pills/buttons become true circles; rounded rects clamp.
    val isCircle = shape === androidx.compose.foundation.shape.CircleShape
    val safeConfig = remember(config, panelSize, d, isCircle, shape) {
        val w = panelSize.width.toFloat()
        val h = panelSize.height.toFloat()
        if (w <= 0f || h <= 0f) return@remember config
        val minSideDp = minOf(w, h) / maxOf(d, 0.5f)
        val sized = config.thicknessForSize(minSideDp)
        val isRectangle = shape === androidx.compose.ui.graphics.RectangleShape
        if (isRectangle) return@remember sized.copy(cornerRadius = 0.dp)
        if (shape is CornerBasedShape) {
            val sizePx = Size(w, h)
            val tl = shape.topStart.toPx(sizePx, density)
            val tr = shape.topEnd.toPx(sizePx, density)
            val bl = shape.bottomStart.toPx(sizePx, density)
            val br = shape.bottomEnd.toPx(sizePx, density)
            if (tl == tr && tr == bl && bl == br) {
                val maxRadiusPx = minOf(w, h) * 0.5f
                val radiusDp = tl.coerceAtMost(maxRadiusPx) / maxOf(d, 0.5f)
                return@remember sized.copy(cornerRadius = radiusDp.dp)
            }
        }
        if (!isCircle) return@remember sized
        val circleRadiusDp = (minOf(w, h) / 2f) / maxOf(d, 0.5f)
        sized.copy(cornerRadius = circleRadiusDp.dp)
    }

    // Optical chain: rebuilt per size/config/tier plus the quantized press
    // bucket (both change rarely, by construction). Highlight is constant
    // per composition, so it is passed but kept out of the keys.
    val pressKey = if (tier == GlassTier.FULL_REFRACT) pressBucket else 0f
    val bgEffect = remember(safeConfig, panelSize, d, tier, pressKey) {
        runCatching {
            val w = panelSize.width.toFloat()
            val h = panelSize.height.toFloat()
            if (w <= 0f || h <= 0f) return@runCatching null
            when (tier) {
                GlassTier.FULL_REFRACT ->
                    GlassEngine.createGlassEffect(
                        w, h, safeConfig, d,
                        press = pressBucket,
                        highlight = highlight,
                        runtimeShader = runtimeShader,
                    )?.asComposeRenderEffect()
                GlassTier.BLUR_ONLY ->
                    GlassEngine.createBlurEffect(safeConfig, d)
                        ?.asComposeRenderEffect()
                GlassTier.STATIC -> null
            }
        }.getOrNull()
    }

    // Soft physical elevation per family: floating layer, never heavy drop.
    val shadowDp: Dp = remember(config) { shadowForConfig(config) }

    this
        .shadow(elevation = shadowDp, shape = shape, clip = false)
        .onSizeChanged { panelSize = it }
        .onGloballyPositioned { panelCoords = it }
        .drawWithContent {
            // Read subscribes this draw snapshot to source movement.
            val anchor = src.sourceAnchor
            if (panelSize.width > 0 && panelSize.height > 0 &&
                bgEffect != null && !src.isRecording
            ) {
                runCatching {
                    bgLayer.renderEffect = bgEffect
                    // Outer .clip(shape) clips drawLayer(bgLayer); lens SDF
                    // follows the same uSize/uRadius, so optics track geometry.
                    bgLayer.record(this, layoutDirection, panelSize) {
                        val srcCoords = src.sourceCoordinates
                        val mine = panelCoords
                        if (srcCoords != null && mine != null &&
                            srcCoords.isAttached && mine.isAttached
                        ) {
                            runCatching {
                                val inSrc = srcCoords.localPositionOf(mine, Offset.Zero)
                                withTransform({ translate(-inSrc.x, -inSrc.y) }) {
                                    drawLayer(src.layer)
                                }
                            }
                        }
                    }
                    drawLayer(bgLayer)
                }.onFailure {
                    // Fail-closed: sharp content only, no frost, no stroke.
                }
            }
            // No base veil, no press gradient: substrate = caller's thin
            // translucent Surface color; press lives in the shader uniforms.
            // Foreground stays sharp: icons/text/labels are never blurred.
            drawContent()
        }
        .clip(shape)
}

private fun shadowForConfig(config: GlassConfig): Dp {
    // Soft physical depth by family (copies preserve rim/blur, so match by
    // value, not identity). Flat functional docks float highest; tiny thumbs
    // lowest. Never a heavy drop. Thresholds track the low-blur (3-8dp)
    // material scale.
    return when {
        config.rimStrength == 0f -> 9.dp
        config.blur.value <= 3.5f -> 2.dp
        config.blur.value <= 4.5f -> 3.dp
        config.blur.value <= 5.5f -> 5.dp
        else -> 7.dp
    }
}
