package com.lastwave.trueglass.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import com.lastwave.trueglass.engine.CapabilityGate
import com.lastwave.trueglass.engine.GlassConfig
import com.lastwave.trueglass.engine.GlassEngine
import com.lastwave.trueglass.engine.GlassTier

/**
 * True liquid-glass chrome for Compose. Clean-break replacement for the old
 * blur-tint recipe and per-view GL optics.
 *
 * - FULL_REFRACT (33+): draws recorded [backdrop] layer, then frost+lens chain
 *   in RenderThread (no app EGL, no bitmap, no GLSurfaceView).
 * - BLUR_ONLY (31-32): system blur chain only.
 * - STATIC / null backdrop / any failure: canvas fallback, zero GPU objects.
 *
 * [backdrop] is the sibling source from trueGlassSource(). It is drawn
 * *inside* this panel so the lens refracts live content with SDF bevel
 * normals + Snell bend + bevel-only dispersion + Fresnel rim.
 */
fun Modifier.trueGlassChrome(
    shape: Shape,
    config: GlassConfig = GlassConfig.Card,
    backdrop: TrueGlassBackdropState? = null,
    enabled: Boolean = true,
    isDark: Boolean = true,
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
    if (tier == GlassTier.STATIC || backdrop == null) {
        return@composed this
            .then(staticTrueGlass(shape, isDark))
            .clip(shape)
    }
    val d = density.density
    var panelSize by remember { mutableStateOf(IntSize.Zero) }
    this
        .onSizeChanged { panelSize = it }
        .graphicsLayer {
            clip = true
            this.shape = shape
            runCatching {
                val w = panelSize.width.toFloat()
                val h = panelSize.height.toFloat()
                if (w > 0f && h > 0f) {
                    val fx = when (tier) {
                        GlassTier.FULL_REFRACT ->
                            GlassEngine.createGlassEffect(w, h, config, d)
                        GlassTier.BLUR_ONLY ->
                            GlassEngine.createBlurEffect(config, d)
                        GlassTier.STATIC -> null
                    }
                    renderEffect = fx?.asComposeRenderEffect()
                }
            }
        }
        .drawWithContent {
            drawTrueGlassBackdrop(backdrop)
            drawContent()
        }
        .then(trueGlassVeil(shape, isDark))
        .clip(shape)
}
