package com.lastwave.trueglass.engine

import android.os.Build

/**
 * Single AGSL lens program. Optical material, not frosted card:
 * - bevel bend lives in the band near the boundary (bevel mask), plus a
 *   whisper interior magnification (uInteriorLens: subtle pull toward the
 *   center so the flat middle carries liquid mass instead of a dead window).
 * - offset magnitude is clamped to a few px: subtle shift, never warping.
 * - per-channel dispersion at the bevel only, center achromatic.
 * - luma-based highlight compression (uHighlight): bright/washed backdrops
 *   compress for legibility while hue/saturation survive; dark backdrops
 *   lift a whisper. Replaces fixed-alpha washes.
 * - asymmetric inner-edge depth shade (~0.085): darker away from the light,
 *   contrast-aided — thickness read without any white ring.
 * - Schlick-style Fresnel rim + single Blinn-Phong glint, both bevel-only,
 *   light-direction dependent, whisper gain. Edge definition comes from
 *   refraction contrast + lighting, never from a permanent bright stroke.
 * - uPress (0..1) scales bend, luminance, spec, rim, and light drift.
 *   Idle is extremely restrained.
 * - NO white frost overlay, NO gloss strip, NO diagonal fake refraction,
 *   NO permanent white border.
 *
 * Runs in RenderThread via graphicsLayer.renderEffect on a background-only
 * intermediate layer (foreground content is drawn sharp on top, never
 * blurred). No app EGL context, no GLSurfaceView, no bitmap upload.
 */
object TrueGlassShader {

    const val LENS_AGSL = """
uniform shader input;
uniform vec2 uSize;
uniform float uRadius;
uniform float uThickness;
uniform float uIOR;
uniform float uDispersion;
uniform vec4 uTint;
uniform float uBrightness;
uniform float uSaturation;
uniform float uRimStrength;
uniform float uSpecular;
uniform vec2 uLightDir;
uniform float uPress;
uniform float uHighlight;
uniform float uInteriorLens;

float sdRoundBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(vec2 fragCoord) {
    half4 src = input.eval(fragCoord);
    if (uSize.x <= 0.0 || uSize.y <= 0.0) { return src; }
    float radius = clamp(uRadius, 0.0, min(uSize.x, uSize.y) * 0.5);
    vec2 p = fragCoord - uSize * 0.5;
    vec2 b = uSize * 0.5;
    float dist = sdRoundBox(p, b, radius);
    float inside = 1.0 - smoothstep(0.0, 1.5, dist);
    if (inside <= 0.001) { return src; }

    float tw = max(uThickness, 1.0);
    float t = clamp(-dist / tw, 0.0, 1.0);
    float hArc = sqrt(max(0.0, 2.0 * t - t * t));
    // Bevel mask: 1 in the band near the boundary, 0 deep in the flat
    // interior. Interior (1 - bevel) carries the soft magnification lens;
    // the boundary band carries the Snell bend.
    float bevel = 1.0 - smoothstep(0.55, 1.0, t);
    float interior = 1.0 - bevel;
    float slope = (1.0 - t) / max(hArc, 0.25);
    float h = min(slope, 1.4) * bevel;

    float e = 1.0;
    float dx = sdRoundBox(p + vec2(e, 0.0), b, radius) - sdRoundBox(p - vec2(e, 0.0), b, radius);
    float dy = sdRoundBox(p + vec2(0.0, e), b, radius) - sdRoundBox(p - vec2(0.0, e), b, radius);
    vec2 grad = vec2(dx, dy) * 0.5;
    float glen = length(grad) + 1e-4;
    vec2 n2 = (grad / glen) * h;

    float press = clamp(uPress, 0.0, 1.0);
    float eta = 1.0 / max(uIOR, 1.0);
    vec2 bend = n2 * (1.0 - eta) * tw * 0.9;
    // Interior magnification: gentle pull toward the center, zero at the
    // boundary, growing into the flat middle (liquid mass, not fisheye).
    bend += -p * clamp(uInteriorLens, 0.0, 0.05) * interior;
    // Press deepens the lens: material responds, not an opacity flash.
    bend *= (1.0 + press * 0.18);
    float maxBend = min(tw * 0.45, 7.0);
    float bendLen = length(bend);
    if (bendLen > maxBend) { bend *= maxBend / max(bendLen, 1e-4); }
    float backOff = min(tw * 0.5, radius * 0.6);
    float ghost = smoothstep(backOff - tw * 0.10, backOff + tw * 0.10, -dist);
    bend *= (0.72 + 0.28 * ghost);

    vec2 uvC = fragCoord + bend;
    float blen = length(bend);
    vec2 dir = blen > 1e-3 ? bend / blen : vec2(0.0);
    // Bevel-only dispersion: flat center stays achromatic.
    float spread = clamp(uDispersion, 0.0, 8.0) * bevel;
    half4 colG = input.eval(clamp(uvC, vec2(0.5), uSize - vec2(0.5)));
    half4 refr = colG;
    if (spread > 0.01) {
        half4 colR = input.eval(clamp(uvC + dir * spread, vec2(0.5), uSize - vec2(0.5)));
        half4 colB = input.eval(clamp(uvC - dir * spread, vec2(0.5), uSize - vec2(0.5)));
        refr = half4(colR.r, colG.g, colB.b, colG.a);
    }

    float luma = dot(refr.rgb, half3(0.299, 0.587, 0.114));
    vec3 sat = mix(vec3(luma), refr.rgb, clamp(uSaturation, 0.5, 1.6));

    // Background-dependent luminance: one material over any content.
    // Dark backdrops lift a whisper; bright/washed backdrops compress
    // (uHighlight) so text stays legible — hue and saturation survive,
    // so artwork reads as itself under glass, never as a white wash.
    float adapt = smoothstep(0.05, 0.95, luma);
    float adaptiveGain = mix(1.03, 1.0, adapt);
    float compress = 1.0 / (1.0 + max(uHighlight, 0.0) * max(luma - 0.5, 0.0));
    vec3 lit = sat * clamp(uBrightness, 0.5, 1.8) * adaptiveGain * compress;
    lit *= (1.0 + press * 0.05);
    lit = mix(lit, uTint.rgb, clamp(uTint.a, 0.0, 1.0));

    // Geometry-aware light: flat center N=(0,0,1) gives ~zero response,
    // bevel normals tilt toward/away from the light. Light drifts subtly
    // with press so the glint moves instead of sitting static.
    vec3 N = normalize(vec3(n2, 1.0));
    vec2 shiftedLight = uLightDir + vec2(press * 0.12, press * 0.08);
    vec3 L = normalize(vec3(shiftedLight, 0.6));
    vec3 V = vec3(0.0, 0.0, 1.0);
    vec3 H = normalize(L + V);
    float specIdle = 0.30;
    float spec = pow(max(dot(N, H), 0.0), 64.0) * max(uSpecular, 0.0) * (specIdle + press * 0.65);

    // Soft bevel-only rim, light-dependent: edge definition from optics,
    // never a permanent bright stroke.
    float rimBase = pow(1.0 - hArc, 3.0) * bevel;
    vec2 gdir = grad / max(glen, 1e-4);
    vec2 ldir = shiftedLight / max(length(shiftedLight), 1e-4);
    float rimLight = 0.5 + 0.5 * dot(gdir, ldir);
    float rim = rimBase * max(uRimStrength, 0.0) * (0.18 + 0.82 * rimLight);
    rim *= (0.85 + 0.35 * press);

    // Asymmetric inner-edge depth shade: strongest just inside the boundary
    // on the side away from the light, aided where the backdrop is bright.
    // Gives thickness/depth as a shadow, never a white ring.
    float edgeBand = 1.0 - smoothstep(0.0, 0.35, t);
    float awayFromLight = 1.0 - rimLight;
    float edgeShade = 0.085 * edgeBand * (0.3 + 0.7 * awayFromLight) * (0.55 + 0.45 * adapt);
    float wall = 1.0 - smoothstep(backOff * 0.75, backOff * 1.15, -dist);
    float wallShade = 0.028 * wall * (0.40 + 0.60 * awayFromLight);
    vec3 outRgb = lit * (1.0 - edgeShade - wallShade);
    outRgb += rim * vec3(1.0, 0.98, 0.95) * 0.16;
    outRgb += spec * vec3(1.0, 0.99, 0.97) * 0.55;
    return half4(mix(src.rgb, outRgb, inside), src.a);
}
"""

    /** Fail-closed validation: shader + uniform names must survive driver quirks. */
    fun validate(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return runCatching {
            val rt = android.graphics.RuntimeShader(LENS_AGSL)
            rt.setFloatUniform("uSize", 100f, 100f)
            rt.setFloatUniform("uRadius", 24f)
            rt.setFloatUniform("uThickness", 12f)
            rt.setFloatUniform("uIOR", 1.52f)
            rt.setFloatUniform("uDispersion", 2f)
            rt.setFloatUniform("uTint", 0f, 0f, 0f, 0f)
            rt.setFloatUniform("uBrightness", 1.08f)
            rt.setFloatUniform("uSaturation", 1.06f)
            rt.setFloatUniform("uRimStrength", 0.6f)
            rt.setFloatUniform("uSpecular", 0.8f)
            rt.setFloatUniform("uLightDir", -0.5f, -0.8f)
            rt.setFloatUniform("uPress", 0f)
            rt.setFloatUniform("uHighlight", 1.7f)
            rt.setFloatUniform("uInteriorLens", 0.010f)
            true
        }.getOrDefault(false)
    }

    private object ShaderGuard {
        const val MIN_API = 33
    }
}
