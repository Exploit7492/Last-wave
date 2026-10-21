package com.lastwave.app.campaign

import com.lastwave.app.diagnostics.AppLog as Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Resolves a Spotify campaign link to a YouTube video id, at runtime — the
 * Spotify counterpart to [CampaignRepository]'s YouTube branch.
 *
 * Why this exists: the repaired seed data gives every stored row a
 * `resolved_song_id`, so those rows take the cheap path. This is for a campaign
 * the repair cannot cover — one created from the web promote form with a
 * Spotify URL that was never resolved ahead of time. Without it such a row is
 * dropped by the queue RPC (correctly: it has no `resolved_song_id`), so the
 * advertiser's link never plays.
 *
 * Metadata comes from Spotify's public embed page, which needs no credential:
 * a `track` link exposes the track's own title/artists, and an `artist` or
 * `album` link exposes a `trackList` whose first entry is the entity's headline
 * track. The title/artist is then handed to the app's own YouTube Music search —
 * the same matcher the rest of the app uses, so runtime and offline resolution
 * agree.
 */
object SpotifyLinkTargetResolver {

    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** A resolved Spotify entity reduced to what a search needs. */
    data class Track(val title: String, val artist: String)

    /**
     * Resolves [target] to a YouTube video id via the app's search, or null if
     * the entity cannot be read or has no confident match. Null is the safe
     * answer: the caller drops the row rather than pointing a paid campaign at
     * a stranger's video.
     */
    suspend fun resolve(target: SpotifyLink.Target, trackSource: Campaigns.TrackSource): String? {
        val track = readTrack(target) ?: return null
        if (track.title.isBlank()) return null
        val query = listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(" ")
        if (query.isBlank()) return null
        return trackSource.search(track.title, track.artist)?.videoId
    }

    /**
     * Reads the headline track (title + artist) for a Spotify entity. Visible
     * for testing the JSON reduction without a network call.
     */
    suspend fun readTrack(target: SpotifyLink.Target): Track? {
        val embedEntity = fetchEmbedEntity(SpotifyLink.embedUrl(target))
        if (embedEntity != null) {
            trackFromEntity(embedEntity)?.let { return it }
        }
        // oEmbed exposes only a title, no artist — enough to search on for a
        // track link, and a worse-but-nonempty answer than nothing if the
        // embed page's shape ever changes. Deliberately last, never first.
        val fallbackTitle = fetchOEmbedTitle(SpotifyLink.oEmbedUrl(target))
        return fallbackTitle?.takeIf { it.isNotBlank() }?.let { Track(it, "") }
    }

    /**
     * Reduces a Spotify embed `entity` object to its headline track. Pure (no
     * I/O) so the shape handling is testable directly.
     */
    fun trackFromEntity(entity: JsonObject): Track? {
        val trackList = entity["trackList"] as? JsonArray
        if (trackList != null && trackList.isNotEmpty()) {
            val first = trackList.firstOrNull()?.jsonObject ?: return null
            val title = first["title"]?.jsonPrimitive?.content?.normalized().orEmpty()
            val artist = first["subtitle"]?.jsonPrimitive?.content?.normalized().orEmpty()
            if (title.isNotBlank()) return Track(title, artist)
        }

        val title = (entity["name"]?.jsonPrimitive?.content ?: entity["title"]?.jsonPrimitive?.content)
            ?.normalized().orEmpty()
        if (title.isBlank()) return null
        return Track(title, artistNames(entity))
    }

    private fun artistNames(entity: JsonObject): String {
        val artists = entity["artists"] as? JsonArray ?: return ""
        return artists
            .mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.content?.takeIf { n -> n.isNotBlank() } }
            .joinToString(", ") { it.normalized() }
    }

    /**
     * Spotify separates a multi-artist `subtitle` with a **non-breaking** space
     * (U+00A0), not a regular one. Left as-is it defeats any `", "`-based split
     * downstream, so it is normalised at the point of reading. Collapsing runs
     * also guards the search query against doubled spaces.
     */
    private fun String.normalized(): String =
        replace('\u00A0', ' ').replace('\u202F', ' ').trim().replace(Regex("\\s+"), " ")

    private suspend fun fetchEmbedEntity(url: String): JsonObject? {
        val html = fetch(url) ?: return null
        return try {
            val nextData = extractNextData(html) ?: return null
            json.parseToJsonElement(nextData).jsonObject
                .get("props")?.jsonObject
                ?.get("pageProps")?.jsonObject
                ?.get("state")?.jsonObject
                ?.get("data")?.jsonObject
                ?.get("entity")?.jsonObject
        } catch (e: Exception) {
            null
        }
    }

    /** The `__NEXT_DATA__` JSON blob every Spotify embed page carries. */
    private fun extractNextData(html: String): String? {
        val marker = html.indexOf("id=\"__NEXT_DATA__\"")
        if (marker < 0) return null
        val open = html.indexOf('>', marker)
        if (open < 0) return null
        val close = html.indexOf("</script>", open)
        if (close < 0) return null
        return html.substring(open + 1, close)
    }

    private suspend fun fetchOEmbedTitle(url: String): String? {
        val body = fetch(url) ?: return null
        return try {
            json.parseToJsonElement(body).jsonObject["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .header("Accept-Language", "en")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        } catch (e: Exception) {
            Log.w("CampaignSpotify", "fetch failed for $url: ${e.message}")
            null
        }
    }
}
