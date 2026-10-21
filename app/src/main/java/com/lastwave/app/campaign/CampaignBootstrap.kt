package com.lastwave.app.campaign

import android.content.Context
import com.lastwave.app.BuildConfig
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.playback.PlayableTrack
import java.util.UUID

/**
 * Wires the campaign package to the app's own build configuration and InnerTube
 * client, once, at startup.
 *
 * Kept out of [Campaigns] itself so the campaign package stays free of Android
 * and DI types: this is the one place that knows how to read `BuildConfig`, how
 * to persist a listener id, and how to turn a YouTube Music track into a
 * playable row. A build with no backend configured leaves campaigns disabled
 * and queues play normally.
 */
object CampaignBootstrap {

    private const val PREFS = "lastwave_campaign"
    private const val KEY_DEVICE_ID = "device_id"

    fun init(context: Context, innerTube: InnerTubeMusicApi) {
        val trackSource = object : Campaigns.TrackSource {
            override suspend fun byVideoId(videoId: String): PlayableTrack? =
                innerTube.fetchSongDetails(videoId)?.toPlayableTrack()

            override suspend fun search(title: String, artist: String): PlayableTrack? {
                val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
                if (query.isBlank()) return null
                return innerTube.searchSongs(query, limit = 5).firstOrNull()?.toPlayableTrack()
            }
        }
        Campaigns.init(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            anonKey = BuildConfig.SUPABASE_ANON_KEY,
            genreTileHost = BuildConfig.CAMPAIGN_GENRE_TILE_HOST,
            deviceId = persistedDeviceId(context),
            trackSource = trackSource,
        )
    }

    /**
     * This install's stable listener identity. Generated once and kept, so a
     * play reported across sessions attributes to the same listener rather than
     * a new anonymous id each launch.
     */
    private fun persistedDeviceId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
        return generated
    }

    private fun YouTubeMusicTrack.toPlayableTrack(): PlayableTrack = PlayableTrack(
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artworkUrl ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
        videoId = videoId,
        durationMs = durationSeconds?.let { it * 1000L },
    )
}
