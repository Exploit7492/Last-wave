package com.lastwave.trueglass.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.view.View

/**
 * Single capability gate. Every glass entry point must consult this before
 * allocating any GPU object (GraphicsLayer, RenderEffect, RuntimeShader).
 * Impossible to crash by construction: returns STATIC unless ALL hold.
 */
object CapabilityGate {

    fun tier(
        context: Context?,
        view: View? = null,
        hardwareAccelerated: Boolean = true,
    ): GlassTier {
        if (view?.isInEditMode == true) return GlassTier.STATIC
        if (!hardwareAccelerated) return GlassTier.STATIC
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return GlassTier.STATIC
        if (context != null && runCatching {
                (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
                    ?.isLowRamDevice == true
            }.getOrDefault(false)) {
            return GlassTier.STATIC
        }
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> GlassTier.FULL_REFRACT
            else -> GlassTier.BLUR_ONLY
        }
    }

    fun isFullRefract(context: Context?, hardwareAccelerated: Boolean = true): Boolean =
        tier(context, null, hardwareAccelerated) == GlassTier.FULL_REFRACT

    fun isBlurOnly(context: Context?, hardwareAccelerated: Boolean = true): Boolean =
        tier(context, null, hardwareAccelerated) == GlassTier.BLUR_ONLY
}
