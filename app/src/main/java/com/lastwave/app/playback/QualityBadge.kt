package com.lastwave.app.playback

import kotlin.math.roundToInt

/**
 * Now-playing quality pill text.
 *
 * Lossless is formatted as `$bitDepth/${sampleRate}kHz` (e.g. `24/44.1kHz`,
 * `24/48kHz`, `24/88.2kHz`, `24/96kHz`, `24/176.4kHz`, `24/192kHz`,
 * `16/48kHz`, `16/44.1kHz`, `32/384kHz`).
 * Spatial mixes use a short `ATMOS` or `SPATIAL` badge.
 */
fun qualityBadgeLabel(state: MusicPlayerState): String {
    val spatial = spatialIndicatorLabel(state.audioCodec)
    if (spatial != null) return spatial

    val codec = state.audioCodec
    val flacLike = isFlacLikeCodec(codec) || state.isLossless
    val rate = state.samplingRateKHz ?: inferSamplingRate(state)
    // Explicit depth first; measurement-based inference second. A backend
    // "16-bit" beside a >48kHz rate contradicts itself (a 16-bit default
    // for unknown depth): drop it to unknown rather than asserting a false
    // 16. Unknown depth + known rate shows the rate without a depth claim
    // ("96kHz FLAC"), never a fabricated "16-BIT" or "24-BIT".
    val explicitDepth = state.bitDepth?.takeIf { it > 0 }?.let {
        if (it <= 16 && (rate ?: 0.0) > 48.0) null else it
    }
    val depth = explicitDepth
        ?: inferBitDepth(state.copy(bitDepth = null), allowRateGuess = false)

    if (flacLike && depth != null && rate != null && rate > 0.0) {
        return "$depth/${formatSampleRateKHz(rate)}kHz"
    }
    if (flacLike && rate != null && rate > 0.0) {
        return "${formatSampleRateKHz(rate)}kHz FLAC"
    }
    if (flacLike && depth != null) {
        return "$depth-BIT FLAC"
    }
    if (flacLike) {
        val parsed = parseQualityFromCodec(codec)
        if (parsed != null) return parsed
        return codec?.takeIf { it.isNotBlank() && !it.equals("AUDIO", true) } ?: "FLAC"
    }

    if (codec?.equals("MP3 320k", ignoreCase = true) == true) return "MP3 320 kbps"
    if (codec?.uppercase() in GENERIC_AUDIO_LABELS) return "AUDIO"
    if (codec != null && state.bitrateKbps != null) return "${codec.uppercase()} ${state.bitrateKbps} kbps"
    if (codec != null) return codec.uppercase()
    if (state.bitrateKbps != null) return "${state.bitrateKbps} kbps"
    return "AUDIO"
}

/** Compact chip next to the title when the playing stream is spatial. */
fun spatialIndicatorLabel(codec: String?): String? {
    val c = codec?.uppercase().orEmpty()
    if (c.contains("ATMOS")) return "ATMOS"
    if (c.contains("SPATIAL") || c.contains("360")) return "SPATIAL"
    return null
}

fun isSpatialAudioCodec(codec: String?): Boolean = spatialIndicatorLabel(codec) != null

fun isFlacLikeCodec(codec: String?): Boolean {
    val c = codec?.uppercase().orEmpty()
    if (c.isBlank()) return false
    if (isSpatialAudioCodec(codec)) return false
    return c.contains("FLAC") || c == "LOSSLESS" || c.contains("HI-RES") || c.contains("HI_RES") ||
        Regex("""(?:^|[^\d])(16|24|32)\s*(?:[-_]bit)?\s*[/]\s*(\d{2,3}(?:\.\d+)?)\s*k?""", RegexOption.IGNORE_CASE).containsMatchIn(c)
}

/** Format sample rate in kHz with minimal decimal places (e.g. 44.1, 48, 88.2, 96, 176.4, 192). */
fun formatSampleRateKHz(kHzOrHz: Double): String {
    val kHz = if (kHzOrHz > 1000.0) kHzOrHz / 1000.0 else kHzOrHz
    val rounded = (kHz * 10.0).roundToInt() / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
}

/**
 * Standard detailed badge for downloads, track details sheets, and metadata cards.
 * Formats every tier cleanly: e.g. "24-BIT / 96k", "24-BIT / 48k", "24-BIT / 44.1k",
 * "16-BIT / 48k", "16-BIT / 44.1k", "24-BIT / 192k".
 */
fun formatDetailedQualityBadge(bitDepth: Int?, sampleRateKHzOrHz: Double?, isAtmos: Boolean = false): String {
    if (isAtmos) return "DOLBY ATMOS"
    val rateKHz = if ((sampleRateKHzOrHz ?: 0.0) > 1000.0) (sampleRateKHzOrHz ?: 0.0) / 1000.0 else (sampleRateKHzOrHz ?: 0.0)
    // A backend "16-bit" beside a >48kHz rate contradicts itself (a 16-bit
    // default standing in for unknown depth): treat depth as unknown rather
    // than asserting a false 16. Unknown depth shows the rate with no depth
    // claim; unknown everything shows plain FLAC.
    val rawDepth = bitDepth?.takeIf { it > 0 }
    val depth = if (rawDepth != null && rawDepth <= 16 && rateKHz > 48.0) null else rawDepth
    return when {
        depth != null && rateKHz > 0.0 -> "$depth-BIT / ${formatSampleRateKHz(rateKHz)}k"
        rateKHz > 0.0 -> "${formatSampleRateKHz(rateKHz)}k FLAC"
        depth != null -> "$depth-BIT FLAC"
        else -> "FLAC"
    }
}

internal fun inferBitDepth(state: MusicPlayerState, allowRateGuess: Boolean = true): Int? {
    val codec = state.audioCodec?.uppercase().orEmpty()
    if (codec.contains("32-BIT") || codec.contains("32BIT") || codec.contains("32/")) return 32
    // NOTE: "HI-RES" alone never implies 24-bit (it describes the rate, and
    // our own rate-derived "HI-RES FLAC" labels would loop back into a
    // fabricated 24). Only explicit depth claims count here.
    if (codec.contains("24-BIT") || codec.contains("24BIT") || codec.contains("24/")) return 24
    if (codec.contains("16-BIT") || codec.contains("16BIT") || codec.contains("CD") || codec.contains("16/")) return 16

    val explicit = state.bitDepth?.takeIf { it > 0 }
    if (explicit != null) return explicit

    val rate = state.samplingRateKHz
    val kbps = state.bitrateKbps
    if (rate != null && rate > 0.0 && kbps != null && kbps > 0) {
        val inferred = (kbps * 1000.0 / (rate * 1000.0 * 2.0)).roundToInt()
        when (inferred) {
            in 15..17 -> return 16
            in 23..25 -> return 24
            in 31..33 -> return 32
        }
        // Compressed FLAC heuristics at 44.1/48kHz:
        // A stereo 16-bit 44.1/48k FLAC almost never exceeds 1200 kbps.
        // A stereo 24-bit 44.1/48k FLAC typically sits between 1400 and 2400 kbps.
        if (rate <= 48.0) {
            if (kbps >= 1500) return 24
            if (kbps in 400..1150) return 16
        }
    }
    // Last-resort rate guess, disallowed where honesty matters (badges and
    // signal path): a 16-bit/96kHz FLAC is valid, so rate alone must never
    // assert a depth.
    if (!allowRateGuess) return null
    if (rate != null && rate > 48.0) {
        return if (rate > 192.0) 32 else 24
    }
    return null
}

internal fun inferSamplingRate(state: MusicPlayerState): Double? {
    val explicit = state.samplingRateKHz?.takeIf { it > 0.0 }
    if (explicit != null) return explicit

    val codec = state.audioCodec.orEmpty()
    // Prefer the rate half of an explicit "depth / rate" label ("24-BIT / 96k",
    // "16/44.1kHz"): the generic number scan below matches the FIRST number,
    // which is the bit depth (24), not the rate (96).
    Regex("""(?:^|[^\d])(?:16|24|32)\s*(?:[-_]bit)?\s*/\s*(\d{2,3}(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        .find(codec)?.groupValues?.getOrNull(1)?.toDoubleOrNull()?.takeIf { it > 0.0 }
        ?.let { return it }
    val match = Regex("""(?:^|[^\d])(\d{2,3}(?:\.\d+)?)\s*(?:k|khz)?(?:[^\d]|$)""", RegexOption.IGNORE_CASE).find(codec)
    if (match != null) {
        val v = match.groupValues[1].toDoubleOrNull()
        if (v != null) {
            return when {
                v in listOf(44.1, 48.0, 88.2, 96.0, 176.4, 192.0, 352.8, 384.0) -> v
                v > 1000.0 -> v / 1000.0
                else -> null
            }
        }
    }
    return null
}

internal fun parseQualityFromCodec(codec: String?): String? {
    val c = codec.orEmpty()
    if (c.isBlank()) return null
    val match = Regex("""(?:^|[^\d])(16|24|32)\s*(?:[-_]bit)?\s*[/]\s*(\d{2,3}(?:\.\d+)?)\s*k?""", RegexOption.IGNORE_CASE).find(c)
    if (match != null) {
        // Trust an explicit "depth/rate" label verbatim: a parsed 16/96 is
        // a real (if unusual) combination, never to be "corrected" to 24.
        val depth = match.groupValues[1].toIntOrNull() ?: 16
        val rate = match.groupValues[2].toDoubleOrNull()
        if (rate != null) {
            return "$depth/${formatSampleRateKHz(rate)}kHz"
        }
    }
    return null
}

private val GENERIC_AUDIO_LABELS = setOf("AUDIO", "LOCAL AUDIO")

