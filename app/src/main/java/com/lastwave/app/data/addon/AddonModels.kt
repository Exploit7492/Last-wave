package com.lastwave.app.data.addon

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Wire models for the Addon HTTP protocol.
 *
 * Endpoints:
 *  - GET {baseUrl}/manifest.json
 *  - GET {baseUrl}/search?q=...&quality=...&atmos=...
 *  - GET {baseUrl}/stream/{id}?quality=...&atmos=...
 */

@Serializable
data class AddonManifest(
    @SerialName("id") val id: String = "",
    @SerialName("name") val name: String = "",
    @SerialName("version") val version: String = "",
    @SerialName("resources") val resources: List<String> = emptyList(),
    @SerialName("root") val root: String = "",
) {
    fun declares(resource: String): Boolean =
        resources.any { it.equals(resource, ignoreCase = true) }

    val isPlayable: Boolean
        get() = resources.isEmpty() || declares("search") || declares("stream")

    val displayName: String
        get() = name.ifBlank { id }
}

@Serializable
data class AddonSearchResponse(
    @SerialName("tracks") val tracks: List<AddonTrack> = emptyList(),
)

@Serializable
data class AddonMetadata(
    @SerialName("bitDepth") val bitDepth: JsonElement? = null,
    @SerialName("bit_depth") val bitDepthSnake: JsonElement? = null,
    @SerialName("bitsPerSample") val bitsPerSample: JsonElement? = null,
    @SerialName("bits_per_sample") val bitsPerSampleSnake: JsonElement? = null,
    @SerialName("clockRate") val clockRate: JsonElement? = null,
    @SerialName("clock_rate") val clockRateSnake: JsonElement? = null,
    @SerialName("sampleRate") val sampleRate: JsonElement? = null,
    @SerialName("sample_rate") val sampleRateSnake: JsonElement? = null,
    @SerialName("samplingRate") val samplingRate: JsonElement? = null,
    @SerialName("sampling_rate") val samplingRateSnake: JsonElement? = null,
) {
    fun extractBitDepth(): Int? =
        parseDepthElement(bitDepth)
            ?: parseDepthElement(bitDepthSnake)
            ?: parseDepthElement(bitsPerSample)
            ?: parseDepthElement(bitsPerSampleSnake)

    fun extractSampleRate(): Double? =
        parseRateElement(clockRate)
            ?: parseRateElement(clockRateSnake)
            ?: parseRateElement(sampleRate)
            ?: parseRateElement(sampleRateSnake)
            ?: parseRateElement(samplingRate)
            ?: parseRateElement(samplingRateSnake)
}

@Serializable
data class AddonTrack(
    @SerialName("id") val id: String = "",
    @SerialName("title") val title: String = "",
    @SerialName("artist") val artist: String = "",
    @SerialName("album") val album: String = "",
    @SerialName("duration") val duration: Double = 0.0,
    @SerialName("format") val format: String = "",
    @SerialName("audioQuality") val audioQuality: String = "",
    @SerialName("atmos") val atmos: Boolean = false,
    @SerialName("audioModes") val audioModes: List<String> = emptyList(),
    @SerialName("artworkURL") val artworkURL: String? = null,
    @SerialName("bitDepth") val rawBitDepth: JsonElement? = null,
    @SerialName("bit_depth") val rawBitDepthSnake: JsonElement? = null,
    @SerialName("clockRate") val rawClockRate: JsonElement? = null,
    @SerialName("clock_rate") val rawClockRateSnake: JsonElement? = null,
    @SerialName("sampleRate") val rawSampleRate: JsonElement? = null,
    @SerialName("sample_rate") val rawSampleRateSnake: JsonElement? = null,
    @SerialName("metadata") val metadata: AddonMetadata? = null,
) {
    val bitDepth: Int?
        get() = metadata?.extractBitDepth()
            ?: parseDepthElement(rawBitDepth)
            ?: parseDepthElement(rawBitDepthSnake)
            ?: parseDepthFromQualityString(audioQuality)
            ?: parseDepthFromQualityString(format)

    val sampleRate: Double?
        get() = metadata?.extractSampleRate()
            ?: parseRateElement(rawClockRate)
            ?: parseRateElement(rawClockRateSnake)
            ?: parseRateElement(rawSampleRate)
            ?: parseRateElement(rawSampleRateSnake)
            ?: parseRateFromQualityString(audioQuality)
            ?: parseRateFromQualityString(format)
}

@Serializable
data class AddonStream(
    @SerialName("url") val url: String = "",
    @SerialName("dataUrl") val dataUrl: String? = null,
    @SerialName("format") val format: String = "dash",
    @SerialName("codec") val codec: String = "flac",
    @SerialName("quality") val quality: String = "",
    @SerialName("sampleRate") val rawSampleRate: JsonElement? = null,
    @SerialName("sample_rate") val rawSampleRateSnake: JsonElement? = null,
    @SerialName("clockRate") val rawClockRate: JsonElement? = null,
    @SerialName("clock_rate") val rawClockRateSnake: JsonElement? = null,
    @SerialName("samplingRate") val rawSamplingRate: JsonElement? = null,
    @SerialName("sampling_rate") val rawSamplingRateSnake: JsonElement? = null,
    @SerialName("bitDepth") val rawBitDepth: JsonElement? = null,
    @SerialName("bit_depth") val rawBitDepthSnake: JsonElement? = null,
    @SerialName("bitsPerSample") val rawBitsPerSample: JsonElement? = null,
    @SerialName("bits_per_sample") val rawBitsPerSampleSnake: JsonElement? = null,
    @SerialName("bitrate") val bitrate: Int? = null,
    @SerialName("manifest") val manifest: String = "dash",
    @SerialName("manifestXml") val manifestXml: String? = null,
    @SerialName("audioMode") val audioMode: String? = null,
    @SerialName("encrypted") val encrypted: Boolean = false,
    @SerialName("metadata") val metadata: AddonMetadata? = null,
) {
    val bitDepth: Int?
        get() = metadata?.extractBitDepth()
            ?: parseDepthElement(rawBitDepth)
            ?: parseDepthElement(rawBitDepthSnake)
            ?: parseDepthElement(rawBitsPerSample)
            ?: parseDepthElement(rawBitsPerSampleSnake)
            ?: parseDepthFromQualityString(quality)

    val sampleRate: Double
        get() = metadata?.extractSampleRate()
            ?: parseRateElement(rawClockRate)
            ?: parseRateElement(rawClockRateSnake)
            ?: parseRateElement(rawSampleRate)
            ?: parseRateElement(rawSampleRateSnake)
            ?: parseRateElement(rawSamplingRate)
            ?: parseRateElement(rawSamplingRateSnake)
            ?: parseRateFromQualityString(quality)
            ?: 44100.0
}

internal fun parseDepthElement(element: JsonElement?): Int? {
    if (element == null || element is JsonNull) return null
    if (element is JsonPrimitive) {
        element.intOrNull?.let { if (it in 8..32) return it }
        val str = element.content
        Regex("""\b(16|24|32)\b""").find(str)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
    }
    return null
}

internal fun parseRateElement(element: JsonElement?): Double? {
    if (element == null || element is JsonNull) return null
    if (element is JsonPrimitive) {
        element.doubleOrNull?.let { raw ->
            if (raw > 0.0) {
                return if (raw < 1000.0) raw * 1000.0 else raw
            }
        }
        val str = element.content
        val hzMatch = Regex("""\b(\d+(?:\.\d+)?)\s*(?:k|khz)?\b""", RegexOption.IGNORE_CASE).find(str)
        if (hzMatch != null) {
            val num = hzMatch.groupValues[1].toDoubleOrNull() ?: return null
            return if ((str.contains("k", ignoreCase = true) || num < 1000.0) && num < 1000.0) num * 1000.0 else num
        }
    }
    return null
}

internal fun parseDepthFromQualityString(q: String?): Int? {
    if (q.isNullOrBlank()) return null
    val match = Regex("""(?:^|[^\d])(16|24|32)\s*(?:[-_]bit)?\s*[/]\s*(\d{2,3}(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(q)
    if (match != null) return match.groupValues[1].toIntOrNull()
    if (q.contains("24-BIT", ignoreCase = true) || q.contains("24BIT", ignoreCase = true) || q.contains("24 BIT", ignoreCase = true)) return 24
    if (q.contains("32-BIT", ignoreCase = true) || q.contains("32BIT", ignoreCase = true) || q.contains("32 BIT", ignoreCase = true)) return 32
    if (q.contains("16-BIT", ignoreCase = true) || q.contains("16BIT", ignoreCase = true) || q.contains("16 BIT", ignoreCase = true)) return 16
    return null
}

internal fun parseRateFromQualityString(q: String?): Double? {
    if (q.isNullOrBlank()) return null
    val match = Regex("""(?:^|[^\d])(?:16|24|32)\s*(?:[-_]bit)?\s*[/]\s*(\d{2,3}(?:\.\d+)?)""", RegexOption.IGNORE_CASE).find(q)
    if (match != null) {
        val kHz = match.groupValues[1].toDoubleOrNull() ?: return null
        return kHz * 1000.0
    }
    val rateMatch = Regex("""\b(44\.1|48|88\.2|96|176\.4|192|384)\s*k(?:hz)?\b""", RegexOption.IGNORE_CASE).find(q)
    if (rateMatch != null) {
        val kHz = rateMatch.groupValues[1].toDoubleOrNull() ?: return null
        return kHz * 1000.0
    }
    return null
}

sealed interface AddonHealth {
    data class Ok(val info: String?) : AddonHealth
    data class Unreachable(val reason: String) : AddonHealth
    data class Rejected(val reason: String) : AddonHealth
}
