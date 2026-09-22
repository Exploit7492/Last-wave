package com.lastwave.trueglass.engine

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Optical material config (background-sampled lens, not frosted card).
 *
 * Optical meaning:
 * - cornerRadius: SDF rounded-box radius (px resolved at draw time from the
 *   actual Shape; CircleShape resolves to min(w,h)/2). Geometry-aware: the
 *   same config refracts differently on pills vs cards because uSize/uRadius
 *   follow the real outline, never a scaled bitmap texture.
 * - thickness: bevel ramp width in dp. MUST stay < ~40% of min(w,h)/2 or the
 *   flat center disappears. Enforced via [thicknessForSize].
 * - ior: index of refraction 1.0 (no bend) .. 2.0 (dense). 1.48-1.55 range
 *   keeps card/flat lensing subtle; icon controls run 1.60 for a denser,
 *   glossier bead without obvious warping.
 * - dispersion: RGB split in px at the bevel. 0 = achromatic. Center always
 *   achromatic regardless of this value (bevel mask in AGSL).
 * - blur: RenderEffect blur radius chained UNDER the lens (lens samples
 *   already-softened content). Kept deliberately low (3-6dp): artwork and
 *   backdrop stay recognizable and the bend stays visible over sharp pixels;
 *   the *lens* supplies the glass read, not frost.
 * - interiorLens: fraction-of-pixel pull toward the center in the flat
 *   interior (0 = off). Gives liquid mass without fisheye; clamped ≤0.05.
 * - tint/saturation/brightness: whisper scene response; shader adds
 *   background-dependent adaptive gain + theme-driven highlight compression
 *   on top (bright vs dark backdrops diverge without a fixed wash).
 * - rimStrength/specular: idle-whisper values. Idle specular is scaled to
 *   30% inside the shader; press adds up to ~120%. No permanent bright
 *   stroke: rim gain is 0.16 in-shader, bevel-only, light-dependent.
 */
data class GlassConfig(
    val cornerRadius: Dp = 24.dp,
    val thickness: Dp = 12.dp,
    val ior: Float = 1.52f,
    val dispersion: Float = 2.0f,
    val blur: Dp = 6.dp,
    val tint: Color = Color.Transparent,
    val saturation: Float = 1.06f,
    val brightness: Float = 1.08f,
    val rimStrength: Float = 0.6f,
    val specular: Float = 0.8f,
    val interiorLens: Float = 0.010f,
) {
    fun thicknessForSize(minSideDp: Float): GlassConfig {
        val cap = (minSideDp / 2f) * 0.38f
        val safe = thickness.value.coerceIn(1f, maxOf(1f, cap))
        return copy(thickness = safe.dp)
    }

    companion object {
        /** Large cards/sheets (≥120dp). Optical depth, whisper rim. */
        val Card = GlassConfig(
            cornerRadius = 24.dp, thickness = 14.dp, ior = 1.52f,
            dispersion = 2.0f, blur = 5.dp, saturation = 1.06f,
            brightness = 1.06f, rimStrength = 0.35f, specular = 0.6f,
        )

        /** Nav bar / mini-player: pure translucency + depth, no rim. */
        val Flat = GlassConfig(
            cornerRadius = 28.dp, thickness = 12.dp, ior = 1.48f,
            dispersion = 1.5f, blur = 6.dp, saturation = 1.05f,
            brightness = 1.05f, rimStrength = 0.0f, specular = 0.50f,
        )

        /**
         * Floating/player icon controls: thick glossy bead. Deep bevel
         * (11dp, size-clamped), dense 1.60 IOR, strong whisper rim + glint
         * for a jewel-like edge — still bevel-only, never a white stroke.
         */
        val Control = GlassConfig(
            cornerRadius = 24.dp, thickness = 11.dp, ior = 1.60f,
            dispersion = 3.0f, blur = 4.dp, saturation = 1.06f,
            brightness = 1.07f, rimStrength = 0.75f, specular = 1.05f,
            interiorLens = 0.014f,
        )

        /** 24dp switch/slider thumbs. */
        val Thumb = GlassConfig(
            cornerRadius = 12.dp, thickness = 4.dp, ior = 1.55f,
            dispersion = 1.2f, blur = 3.dp, saturation = 1.05f,
            brightness = 1.06f, rimStrength = 0.35f, specular = 0.6f,
        )

        /**
         * 48-56dp icon buttons: same thick glossy bead as [Control] with a
         * slightly tighter bevel ramp for small circles.
         */
        val IconButton = GlassConfig(
            cornerRadius = 28.dp, thickness = 8.dp, ior = 1.60f,
            dispersion = 2.4f, blur = 4.dp, saturation = 1.06f,
            brightness = 1.07f, rimStrength = 0.70f, specular = 1.0f,
            interiorLens = 0.014f,
        )
    }
}
