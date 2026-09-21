package com.lastwave.trueglass.views

import android.content.Context
import android.graphics.Outline
import android.util.AttributeSet
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import com.lastwave.trueglass.engine.GlassConfig
import com.lastwave.trueglass.engine.GlassEngine

/**
 * Crash-proof View host. No GLSurfaceView, no EGL, no bitmap capture.
 * Owns only [config]; all GPU work lives in [GlassEngine] (RenderThread).
 */
open class TrueGlassFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : FrameLayout(context, attrs, defStyleAttr) {

    var glassConfig: GlassConfig = GlassConfig.Card
        set(value) {
            field = value
            applyGlass()
        }

    init {
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = (glassConfig.cornerRadius.value * resources.displayMetrics.density)
                    .coerceIn(0f, minOf(width, height) / 2f)
                outline.setRoundRect(0, 0, width, height, r)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        applyGlass()
    }

    override fun onDetachedFromWindow() {
        GlassEngine.clear(this)
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        invalidateOutline()
        applyGlass()
    }

    private fun applyGlass() {
        if (!isAttachedToWindow) return
        if (width <= 0 || height <= 0) return
        runCatching { GlassEngine.applyToView(this, glassConfig) }
    }
}
