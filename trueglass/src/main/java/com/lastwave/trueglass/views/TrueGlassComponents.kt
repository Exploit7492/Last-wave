package com.lastwave.trueglass.views

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.ImageView
import android.view.Gravity
import androidx.core.widget.ImageViewCompat
import android.content.res.ColorStateList
import com.lastwave.trueglass.engine.GlassConfig

/** Circular glass button. Press = scale spring only; optics stay static (no re-blur storm). */
class TrueGlassIconButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TrueGlassFrameLayout(context, attrs, defStyleAttr) {

    private val iconView = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        layoutParams = LayoutParams(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
        ).apply { gravity = Gravity.CENTER }
    }

    init {
        glassConfig = GlassConfig.IconButton
        addView(iconView)
        isClickable = true
        isFocusable = true
    }

    fun setIcon(resId: Int) {
        runCatching { iconView.setImageResource(resId) }
    }

    fun setIconTint(color: Int) {
        runCatching { ImageViewCompat.setImageTintList(iconView, ColorStateList.valueOf(color)) }
    }

    override fun setOnClickListener(l: OnClickListener?) {
        super.setOnClickListener { v ->
            runCatching {
                v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(60).withEndAction {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
                }.start()
            }
            l?.onClick(v)
        }
    }
}

/** Pressable glass container for arbitrary children. */
class TrueGlassButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TrueGlassFrameLayout(context, attrs, defStyleAttr) {

    init {
        glassConfig = GlassConfig.Control
        isClickable = true
        isFocusable = true
    }
}

/** Minimal switch/sider thumbs share the same engine; full gesture physics
 *  lives in Compose wrappers. Views stay dumb + crash-proof. */
class TrueGlassSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TrueGlassFrameLayout(context, attrs, defStyleAttr) {
    init { glassConfig = GlassConfig.Thumb }
}

class TrueGlassSlider @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : TrueGlassFrameLayout(context, attrs, defStyleAttr) {
    init { glassConfig = GlassConfig.Card }
}
