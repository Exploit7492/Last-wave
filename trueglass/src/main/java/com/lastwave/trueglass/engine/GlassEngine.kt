package com.lastwave.trueglass.engine

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View

/**
 * Singleton owner of ALL GPU objects. Views/panels hold only GlassConfig.
 * - Cached RuntimeShader validation (compile probe once, fail-closed after).
 * - Blur chained UNDER the AGSL lens in RenderThread (no bitmap, no EGL):
 *   lens `input` = already-softened content. Chain order: inner = blur,
 *   outer = lens.
 * - Compose path applies the chain to a background-only intermediate layer
 *   so foreground icons/text stay sharp; this engine never blurs foreground
 *   by itself, it only builds the background effect.
     * - Interaction (press 0..1, highlight, light drift) flows through
 *   uPress/uHighlight/uLightDir so the material itself responds,
 *   never a plain opacity change.
 * - Every entry fail-closed: null/false on any driver rejection → STATIC.
 */
object GlassEngine {

    @Volatile
    private var shaderValid: Boolean? = null

    private fun isLensAvailable(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        shaderValid?.let { return it }
        val ok = TrueGlassShader.validate()
        shaderValid = ok
        return ok
    }

    fun createLensEffect(
        widthPx: Float,
        heightPx: Float,
        config: GlassConfig,
        density: Float = 1f,
        runtimeShader: RuntimeShader? = null,
    ): RenderEffect? = createGlassEffect(widthPx, heightPx, config, density, runtimeShader = runtimeShader)

    /**
     * Background-only optical chain for the sampled backdrop layer.
     * inner = blur, outer = lens (lens `input` = blurred output).
     * Callers must draw ONLY the recorded background through this effect and
     * draw foreground content sharp on top — never apply it to a node that
     * also contains text/icons, or the result collapses to a frosted card.
     *
     * @param press 0..1 interaction drive (interruptible press/hover/focus).
     * @param lightX light direction drift; press shifts it subtly in-shader too.
     * @param highlight luma compression strength (0 = off; ~1.35 dark theme,
     *   ~0.4 light theme). Dims bright backdrops for legibility without a wash.
     */
    fun createGlassEffect(
        widthPx: Float,
        heightPx: Float,
        config: GlassConfig,
        density: Float = 1f,
        press: Float = 0f,
        lightX: Float = -0.5f,
        lightY: Float = -0.8f,
        highlight: Float = 0f,
        runtimeShader: RuntimeShader? = null,
    ): RenderEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        if (!widthPx.isFinite() || !heightPx.isFinite() || widthPx <= 0f || heightPx <= 0f) return null
        if (!isLensAvailable()) return null
        return runCatching {
            val blurPx = (config.blur.value * density).coerceIn(0f, 60f)
            val blurFx = if (blurPx > 0.5f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RenderEffect.createBlurEffect(
                    blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP,
                )
            } else null
            val rt = runtimeShader ?: RuntimeShader(TrueGlassShader.LENS_AGSL)
            val radiusPx = config.cornerRadius.value * density
            val thicknessPx = config.thickness.value * density
            rt.setFloatUniform("uSize", widthPx, heightPx)
            rt.setFloatUniform("uRadius", radiusPx)
            rt.setFloatUniform("uThickness", thicknessPx.coerceIn(1f, maxOf(1f, minOf(widthPx, heightPx))))
            rt.setFloatUniform("uIOR", config.ior.coerceIn(1f, 2.2f))
            rt.setFloatUniform("uDispersion", config.dispersion.coerceIn(0f, 8f))
            rt.setFloatUniform(
                "uTint",
                config.tint.red, config.tint.green, config.tint.blue, config.tint.alpha,
            )
            rt.setFloatUniform("uBrightness", config.brightness.coerceIn(0.5f, 1.8f))
            rt.setFloatUniform("uSaturation", config.saturation.coerceIn(0.5f, 1.6f))
            rt.setFloatUniform("uRimStrength", config.rimStrength.coerceIn(0f, 2f))
            rt.setFloatUniform("uSpecular", config.specular.coerceIn(0f, 2f))
            rt.setFloatUniform(
                "uLightDir",
                lightX.takeIf { it.isFinite() } ?: -0.5f,
                lightY.takeIf { it.isFinite() } ?: -0.8f,
            )
            rt.setFloatUniform("uPress", press.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f)
            rt.setFloatUniform("uHighlight", highlight.takeIf { it.isFinite() }?.coerceIn(0f, 4f) ?: 0f)
            rt.setFloatUniform("uInteriorLens", config.interiorLens.coerceIn(0f, 0.05f))
            val lensFx = RenderEffect.createRuntimeShaderEffect(rt, "input")
            if (blurFx != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                RenderEffect.createChainEffect(lensFx, blurFx)
            } else lensFx
        }.getOrNull()
    }

    /** Blur-only chain for API 31-32 (no RuntimeShader). */
    fun createBlurEffect(config: GlassConfig, density: Float): RenderEffect? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return runCatching {
            val blurPx = (config.blur.value * density).coerceIn(0f, 60f)
            if (blurPx <= 0.5f) return null
            RenderEffect.createBlurEffect(
                blurPx, blurPx, android.graphics.Shader.TileMode.CLAMP,
            )
        }.getOrNull()
    }

    /** Full attach for Views. Clears everything on failure. */
    fun applyToView(view: View, config: GlassConfig): Boolean {
        return runCatching {
            val density = view.resources.displayMetrics.density
            val tier = CapabilityGate.tier(view.context, view, view.isHardwareAccelerated)
            when (tier) {
                GlassTier.STATIC -> {
                    clear(view)
                    false
                }
                GlassTier.BLUR_ONLY -> {
                    val fx = createBlurEffect(config, density)
                    view.setRenderEffect(fx)
                    true
                }
                GlassTier.FULL_REFRACT -> {
                    val w = view.width.toFloat()
                    val h = view.height.toFloat()
                    if (w <= 0f || h <= 0f) {
                        // Sized later via onSizeChanged re-apply; keep blur only.
                        view.setRenderEffect(createBlurEffect(config, density))
                        return true
                    }
                    val fx = createGlassEffect(w, h, config, density)
                    view.setRenderEffect(fx)
                    fx != null
                }
            }
        }.getOrDefault(false)
    }

    fun clear(view: View) {
        runCatching { view.setRenderEffect(null) }
    }
}
