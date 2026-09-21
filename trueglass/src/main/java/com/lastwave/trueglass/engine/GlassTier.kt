package com.lastwave.trueglass.engine

/**
 * Clean-break tier model. No per-view EGL, no bitmap capture, no GLSurfaceView.
 * - FULL_REFRACT: API 33+ + HW accel + !lowRam → AGSL lens + system background blur.
 * - BLUR_ONLY: API 31-32 → system background blur only, no RuntimeShader.
 * - STATIC: everything else → canvas gradients, zero GPU layers.
 */
enum class GlassTier { FULL_REFRACT, BLUR_ONLY, STATIC }
