package com.lastwave.app.data.lossless

import android.util.Log
import com.lastwave.app.data.plugin.ModulePlaybackResolver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class LosslessAudioStream(
    val url: String,
    val mimeType: String = "audio/flac",
    val bitDepth: Int = 16,
    val samplingRate: Double = 44.1,
    val formatId: Int = 6,
    val bitrateKbps: Int? = null,
    val trackId: Long = 0,
    val durationSeconds: Int = 0,
)

@Singleton
class LosslessMusicApi @Inject constructor(
    private val moduleResolver: ModulePlaybackResolver,
) {
    companion object {
        fun decodeSecretBytes(data: ByteArray, mask: ByteArray): String {
            if (data.isEmpty() || mask.isEmpty()) return ""
            val decoded = ByteArray(data.size) { i ->
                (data[i].toInt() xor mask[i % mask.size].toInt()).toByte()
            }
            return String(decoded, Charsets.UTF_8)
        }

        // Quality presets
        const val QUALITY_DOLBY_ATMOS = 28 // Dolby Atmos Spatial Audio
        const val QUALITY_MAX_HI_RES = 27 // Up to 24-bit / 192 kHz
        const val QUALITY_HI_RES_96 = 7   // Up to 24-bit / 96 kHz
        const val QUALITY_CD_LOSSLESS = 6 // 16-bit / 44.1 kHz FLAC
        const val QUALITY_MP3_320 = 5     // 320 kbps MP3
        const val QUALITY_DATA_SAVER = 4  // 96 kbps HE-AAC Data Saver
        const val QUALITY_YOUTUBE = -1    // YouTube Music standard stream

        fun getQualityAttemptOrder(preferred: Int): List<Int> {
            if (preferred == QUALITY_YOUTUBE) return emptyList()
            val tiersAscending = listOf(
                QUALITY_DATA_SAVER,
                QUALITY_MP3_320,
                QUALITY_CD_LOSSLESS,
                QUALITY_HI_RES_96,
                QUALITY_MAX_HI_RES,
            )
            val index = tiersAscending.indexOf(preferred)
            if (index == -1) return listOf(QUALITY_MAX_HI_RES, QUALITY_HI_RES_96, QUALITY_CD_LOSSLESS, QUALITY_MP3_320, QUALITY_DATA_SAVER)

            val preferredQuality = tiersAscending[index]
            val above = tiersAscending.subList(index + 1, tiersAscending.size)
            val below = tiersAscending.subList(0, index).reversed()

            return (listOf(preferredQuality) + above + below).distinct()
        }

        private const val TAG = "LosslessMusicApi"
    }

    /**
     * Resolves a high-confidence, verified direct audio stream URL for a given track
     * via the encrypted provider module engine.
     */
    suspend fun resolveStream(
        title: String,
        artist: String,
        preferredQuality: Int = QUALITY_MAX_HI_RES,
        excludedUrls: Set<String> = emptySet(),
    ): LosslessAudioStream? = withContext(Dispatchers.IO) {
        if (preferredQuality == QUALITY_YOUTUBE || title.isBlank() || artist.isBlank()) return@withContext null

        try {
            val descriptor = moduleResolver.resolve(title, artist, preferredQuality) ?: return@withContext null
            val s = descriptor.stream
            if (s.baseUrl.isBlank() || s.baseUrl in excludedUrls) return@withContext null

            val formatId = when (s.quality.uppercase()) {
                "ATMOS", "DOLBY_ATMOS" -> QUALITY_DOLBY_ATMOS
                "UHD", "HI_RES_192" -> QUALITY_MAX_HI_RES
                "HI_RES_96" -> QUALITY_HI_RES_96
                "HD", "CD_LOSSLESS" -> QUALITY_CD_LOSSLESS
                "SD", "MP3_320" -> QUALITY_MP3_320
                "LOW", "DATA_SAVER" -> QUALITY_DATA_SAVER
                else -> if (s.sampleRate > 48000 || s.bitDepth > 16) QUALITY_MAX_HI_RES else QUALITY_CD_LOSSLESS
            }
            val samplingRateKhz = if (s.sampleRate > 0) s.sampleRate / 1000.0 else 44.1
            val bitDepth = if (s.bitDepth > 0) s.bitDepth else 16
            val bitrateKbps = if (s.bandwidth > 0) s.bandwidth / 1000 else when (formatId) {
                QUALITY_DOLBY_ATMOS -> 768
                QUALITY_MAX_HI_RES, QUALITY_HI_RES_96 -> ((bitDepth * samplingRateKhz * 2 * 1000) / 1000).toInt()
                QUALITY_CD_LOSSLESS -> 1411
                QUALITY_MP3_320 -> 320
                QUALITY_DATA_SAVER -> 96
                else -> null
            }
            val trackIdLong = descriptor.trackId.toLongOrNull() ?: 0L

            LosslessAudioStream(
                url = s.baseUrl,
                mimeType = s.mimeType.ifBlank { "audio/flac" },
                bitDepth = bitDepth,
                samplingRate = samplingRateKhz,
                formatId = formatId,
                bitrateKbps = bitrateKbps,
                trackId = trackIdLong,
                durationSeconds = descriptor.durationSec,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Lossless stream resolution via module failed: ${e.message}")
            null
        }
    }
}
