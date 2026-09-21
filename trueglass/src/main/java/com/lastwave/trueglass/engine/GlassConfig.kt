package com.lastwave.trueglass.engine

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Clean-break material config. Replaces Prismal pfl attrs plus the old
 * PrismalLiquidGlass base recipe and PrismalFilter presets.
 *
 * Optical meaning (true iOS26 lens, not frost):
 * - cornerRadius: SDF rounded-box radius (px resolved at draw time).
 * - thickness: bevel ramp width in dp. MUST stay < ~40% of min(w,h)/2 or the
 *   flat center disappears (hollow-ring footgun carried over from Prismal).
 * - ior: index of refraction 1.0 (no bend) .. 2.0 (dense). 1.52 = crown glass.
 * - dispersion: RGB split in px at the bevel. 0 = achromatic. Center always
 *   achromatic regardless of this value.
 * - blur: system background-blur radius (frost depth). Kept low so the
 *   backdrop stays recognizable; the *lens* supplies the glass read.
 * - tint/saturation/brightness/rimStrength/specular: scene-light response,
 *   never a painted gloss strip.
 */
data class GlassConfig(
    val cornerRadius: Dp = 24.dp,
    val thickness: Dp = 12.dp,
    val ior: Float = 1.52f,
    val dispersion: Float = 2.0f,
    val blur: Dp = 9.dp,
    val tint: Color = Color.Transparent,
    val saturation: Float = 1.06f,
    val brightness: Float = 1.08f,
    val rimStrength: Float = 0.6f,
    val specular: Float = 0.8f,
) {
    fun thicknessForSize(minSideDp: Float): GlassConfig {
        val cap = (minSideDp / 2f) * 0.38f
        val safe = thickness.value.coerceIn(1f, maxOf(1f, cap))
        return copy(thickness = safe.dp)
    }

    companion object {
        /** Large cards/sheets (≥120dp). Calibrated base recipe. */
        val Card = GlassConfig(
            cornerRadius = 24.dp, thickness = 14.dp, ior = 1.52f,
            dispersion = 2.0f, blur = 9.dp, saturation = 1.06f,
            brightness = 1.08f, rimStrength = 0.55f, specular = 0.8f,
        )

        /** Nav bar / mini-player: pure translucency + depth, no rim. */
        val Flat = GlassConfig(
            cornerRadius = 28.dp, thickness = 12.dp, ior = 1.48f,
            dispersion = 1.5f, blur = 10.dp, saturation = 1.05f,
            brightness = 1.06f, rimStrength = 0.0f, specular = 0.5f,
        )

        /** Small floating controls: whisper hairline rim + stronger lens. */
        val Control = GlassConfig(
            cornerRadius = 24.dp, thickness = 8.dp, ior = 1.55f,
            dispersion = 2.5f, blur = 7.dp, saturation = 1.07f,
            brightness = 1.10f, rimStrength = 0.9f, specular = 1.1f,
        )

        /** 24dp switch/slider thumbs. */
        val Thumb = GlassConfig(
            cornerRadius = 12.dp, thickness = 4.dp, ior = 1.55f,
            dispersion = 1.2f, blur = 5.dp, saturation = 1.05f,
            brightness = 1.08f, rimStrength = 0.5f, specular = 0.9f,
        )

        /** 48-56dp icon buttons. */
        val IconButton = GlassConfig(
            cornerRadius = 28.dp, thickness = 5.dp, ior = 1.55f,
            dispersion = 1.6f, blur = 7.dp, saturation = 1.06f,
            brightness = 1.10f, rimStrength = 0.7f, specular = 1.0f,
        )
    }
}
