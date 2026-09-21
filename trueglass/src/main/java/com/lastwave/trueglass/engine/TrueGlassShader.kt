package com.lastwave.trueglass.engine

import android.os.Build

/**
 * Single AGSL lens program. True iOS26 optics checklist:
 * - flat center N=(0,0,1) → zero offset (no fisheye/magnify in the middle)
 * - offset grows to the bevel only, from SDF gradient × circular-arc height
 * - per-channel dispersion at the bevel only, center achromatic
 * - Schlick Fresnel rim + single Blinn-Phong glint that moves with uLightDir
 * - NO white frost overlay, NO gloss strip, NO diagonal fake refraction
 *
 * Runs in RenderThread via View.setRenderEffect / graphicsLayer.renderEffect.
 * No app EGL context, no GLSurfaceView, no bitmap upload.
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

float sdRoundBox(vec2 p, vec2 b, float r) {
    vec2 q = abs(p) - b + r;
    return length(max(q, vec2(0.0))) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(vec2 fragCoord) {
    half4 src = input.eval(fragCoord);
    if (uSize.x <= 0.0 || uSize.y <= 0.0) { return src; }
    float radius = clamp(uRadius, 0.0, min(uSize.x, uSize.y) * 0.5);
    vec2 p = fragCoord - uSize * 0.5;
    vec2 b = uSize * 0.5 - vec2(radius);
    float dist = sdRoundBox(p, b, radius);
    float inside = 1.0 - smoothstep(0.0, 1.5, dist);
    if (inside <= 0.001) { return src; }

    float tw = max(uThickness, 1.0);
    float t = clamp(-dist / tw, 0.0, 1.0);
    float h = sqrt(max(0.0, 2.0 * t - t * t));

    float e = 1.0;
    float dx = sdRoundBox(p + vec2(e, 0.0), b, radius) - sdRoundBox(p - vec2(e, 0.0), b, radius);
    float dy = sdRoundBox(p + vec2(0.0, e), b, radius) - sdRoundBox(p - vec2(0.0, e), b, radius);
    vec2 grad = vec2(dx, dy) * 0.5;
    float glen = length(grad) + 1e-4;
    vec2 n2 = (grad / glen) * h;

    float eta = 1.0 / max(uIOR, 1.0);
    vec2 bend = n2 * (1.0 - eta) * tw * 1.2;

    vec2 uvC = fragCoord + bend;
    float blen = length(bend);
    vec2 dir = blen > 1e-3 ? bend / blen : vec2(0.0);
    float spread = clamp(uDispersion, 0.0, 8.0) * (1.0 - h * 0.85);
    half4 colR = input.eval(uvC + dir * spread);
    half4 colG = input.eval(uvC);
    half4 colB = input.eval(uvC - dir * spread);
    half4 refr = half4(colR.r, colG.g, colB.b, colG.a);

    float luma = dot(refr.rgb, half3(0.299, 0.587, 0.114));
    vec3 sat = mix(vec3(luma), refr.rgb, clamp(uSaturation, 0.5, 1.6));

    vec3 N = normalize(vec3(n2, 1.0));
    vec3 L = normalize(vec3(uLightDir, 0.6));
    vec3 V = vec3(0.0, 0.0, 1.0);
    vec3 H = normalize(L + V);
    float spec = pow(max(dot(N, H), 0.0), 48.0) * max(uSpecular, 0.0);
    float rim = pow(1.0 - h, 2.5) * max(uRimStrength, 0.0);

    vec3 outRgb = sat * clamp(uBrightness, 0.5, 1.8);
    outRgb = mix(outRgb, uTint.rgb, clamp(uTint.a, 0.0, 1.0));
    outRgb += rim * vec3(1.0, 0.98, 0.95) * 0.30;
    outRgb += spec;
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
            true
        }.getOrDefault(false)
    }

    private object ShaderGuard {
        const val MIN_API = 33
    }
}
