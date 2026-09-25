package com.lastwave.app.data.lyrics

import com.lastwave.app.data.artwork.awaitSuccessfulBodyOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

@Serializable
private data class BiniSearchResponse(
    val total: Int? = null,
    val source: String? = null,
    val results: List<BiniHit> = emptyList(),
)

@Serializable
data class BiniHit(
    @SerialName("track_name") val trackName: String? = null,
    @SerialName("artist_name") val artistName: String? = null,
    @SerialName("album_name") val albumName: String? = null,
    val duration: Int? = null,
    val isrc: String? = null,
    @SerialName("timing_type") val timingType: String? = null,
    val lyricsUrl: String? = null,
)

/**
 * Recording-matched Apple TTML plus the ISRC other providers can reuse.
 * Search is name-based; the hit reports the ISRC of the matched recording
 * so downstream lookups can name the recording instead of describing it.
 */
@Singleton
class BiniLyricsApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
    }

    suspend fun identify(
        title: String,
        artist: String,
        durationSeconds: Int? = null,
        album: String? = null,
        isrc: String? = null,
    ): BiniHit? = withContext(Dispatchers.IO) {
        val builder = "https://lyrics-api.binimum.org/".toHttpUrlOrNull()?.newBuilder() ?: return@withContext null
        if (!isrc.isNullOrBlank()) {
            builder.addQueryParameter("isrc", isrc.trim())
        } else {
            if (title.isBlank()) return@withContext null
            builder.addQueryParameter("track", title.trim())
            builder.addQueryParameter("artist", artist.trim())
            if (!album.isNullOrBlank()) builder.addQueryParameter("album", album.trim())
            if (durationSeconds != null && durationSeconds > 0) {
                builder.addQueryParameter("duration", durationSeconds.toString())
            }
        }
        val request = Request.Builder()
            .url(builder.build())
            .header("User-Agent", "LastWave-Android/1.0 (https://github.com/clash-projects/lastwave)")
            .header("Accept", "application/json")
            .get()
            .build()
        try {
            val body = okHttpClient.newCall(request).awaitSuccessfulBodyOrNull() ?: return@withContext null
            json.decodeFromString<BiniSearchResponse>(body).results.firstOrNull()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: IOException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchLinesFor(hit: BiniHit): Pair<String?, List<LyricLine>>? = withContext(Dispatchers.IO) {
        val documentUrl = hit.lyricsUrl?.takeIf { it.isNotBlank() } ?: return@withContext null
        val ttml = try {
            val request = Request.Builder()
                .url(documentUrl)
                .header("User-Agent", "LastWave-Android/1.0 (https://github.com/clash-projects/lastwave)")
                .header("Accept", "application/xml, text/xml, */*")
                .get()
                .build()
            okHttpClient.newCall(request).awaitSuccessfulBodyOrNull()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        val lines = TtmlParser.parse(ttml).takeIf { it.isNotEmpty() } ?: return@withContext null
        hit.isrc?.takeIf { it.isNotBlank() } to lines
    }

    suspend fun fetchLyrics(
        title: String,
        artist: String,
        album: String? = null,
        durationSeconds: Int? = null,
        isrc: String? = null,
    ): Triple<String?, List<LyricLine>, String?>? {
        val hit = identify(title, artist, durationSeconds, album, isrc) ?: return null
        // Prefer the stored document; fall back to the query shape some
        // hosts answer with when the document URL is absent.
        fetchLinesFor(hit)?.let { (foundIsrc, lines) ->
            return Triple(foundIsrc ?: hit.isrc, lines, "Syllable-Sync")
        }
        return null
    }

    companion object {
        fun scoreHit(hit: BiniHit, title: String, artist: String, durationSeconds: Int?): Int {
            var score = 0
            val candTitle = LrclibLyricsApi.cleanTrackTitle(hit.trackName.orEmpty())
            val reqTitle = LrclibLyricsApi.cleanTrackTitle(title)
            if (candTitle.equals(reqTitle, ignoreCase = true)) score += 3
            else if (LrclibLyricsApi.titlesMatch(candTitle, reqTitle)) score += 1
            val candArtist = LrclibLyricsApi.cleanArtistName(hit.artistName.orEmpty())
            val reqArtist = LrclibLyricsApi.cleanArtistName(artist)
            if (LrclibLyricsApi.artistMatches(candArtist, reqArtist)) score += 2
            if (durationSeconds != null && durationSeconds > 0 && (hit.duration ?: 0) > 0) {
                score += when (abs((hit.duration ?: 0) - durationSeconds)) {
                    in 0..3 -> 3
                    in 4..10 -> 1
                    else -> 0
                }
            }
            return score
        }
    }
}
