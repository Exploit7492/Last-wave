package com.lastwave.app.campaign

import com.lastwave.app.diagnostics.AppLog as Log
import com.lastwave.app.playback.PlayableTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Reads the campaign system this app is a consumer of.
 *
 * The campaigns themselves are published elsewhere — this app never creates,
 * edits or pauses one. Its whole job is to ask, per queue slot, which campaign
 * should play next, to turn the answer into a playable track, and to report the
 * qualified plays it delivered.
 *
 * The endpoint is the `get_next_campaign_for_queue_slot` RPC, which picks the
 * least-recently-served eligible campaign and marks it served in one
 * transaction. That atomicity is the reason the injection layer calls this once
 * per slot rather than fetching a batch: see [CampaignQueueInjection] for why
 * that distinction is load-bearing.
 *
 * @param supabaseUrl The project URL, without a trailing slash.
 * @param anonKey The public anon key. Blank in either disables campaigns
 *   entirely rather than failing — a build with no campaign backend configured
 *   plays a normal queue.
 * @param trackSource Turns a resolved video id (or a Spotify title/artist pair)
 *   into the app's own playable row.
 */
class CampaignRepository(
    private val supabaseUrl: String,
    private val anonKey: String,
    private val trackSource: Campaigns.TrackSource,
    private val genreTileHost: String = "",
) {

    private val configured: Boolean
        get() = supabaseUrl.isNotBlank() && anonKey.isNotBlank()

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The next campaign for one queue slot, or null when none is eligible.
     *
     * Null is an ordinary answer, not an error: the RPC's own eligibility
     * filter is fail-closed, so an empty result means "nothing to place right
     * now" and the slot is skipped. Every failure path returns null too — a
     * campaign that cannot be placed must never break the queue it rides on.
     *
     * @param genre The listener's current genre, when the queue was started
     *   from a genre surface. Null asks for a campaign without that
     *   constraint, which is what the RPC's own null branch handles.
     */
    suspend fun fetchNextForQueueSlot(genre: String? = null): PlayableTrack? {
        if (!configured) return null
        return withContext(Dispatchers.IO) {
            try {
                val payload = buildString {
                    append("{\"p_genre\":")
                    if (genre == null) append("null") else append(JsonPrimitive(genre).toString())
                    append('}')
                }
                val body = rpc("get_next_campaign_for_queue_slot", payload) ?: return@withContext null
                resolveRow(body)
            } catch (e: Exception) {
                Log.w(TAG, "fetchNextForQueueSlot failed for genre=$genre: ${e.message}")
                null
            }
        }
    }

    /**
     * Turns the RPC's JSON array into a playable track.
     *
     * The RPC returns an array (zero or one row) rather than an object, so an
     * empty array is the "no campaign" case. The row's `resolved_song_id` wins
     * when present; otherwise a video id is extracted from the raw `source_url`
     * (any real YouTube shape), and only then is a Spotify link resolved at
     * runtime. Either way the id is resolved through the app's own InnerTube
     * client, which supplies title, artist and artwork — the campaign row
     * carries only the link.
     */
    private suspend fun resolveRow(body: String): PlayableTrack? {
        val array = try {
            json.parseToJsonElement(body) as? JsonArray
        } catch (e: Exception) {
            Log.w(TAG, "queue-slot response was not JSON: ${e.message}")
            return null
        }
        val row = array?.firstOrNull() as? JsonObject ?: return null

        val sourceUrl = row["source_url"]?.jsonPrimitive?.content.orEmpty()
        val resolvedSongId = row["resolved_song_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        val videoId = resolvedSongId
            ?: CampaignUrlResolver.extractVideoId(sourceUrl)
            ?: SpotifyLink.parse(sourceUrl)?.let { SpotifyLinkTargetResolver.resolve(it, trackSource) }

        // The campaign id rides on the row so the play reporter can attribute a
        // qualified play without a second lookup.
        val campaignId = row["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        return videoId?.let { trackSource.byVideoId(it) }?.copy(campaignId = campaignId)
    }

    /**
     * Registers (or re-registers) this device as a listener and returns the
     * listener id the campaign write RPCs expect. The RPC echoes the device id
     * back. Null on failure — the caller then reports a play without a user id,
     * which the server tolerates.
     */
    suspend fun ensureDeviceListener(deviceId: String): String? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        try {
            rpc("ensure_device_listener", "{\"p_device_id\":\"$deviceId\"}")
                ?.trim()?.trim('"')?.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            Log.w(TAG, "ensureDeviceListener failed: ${e.message}")
            null
        }
    }

    /**
     * Reports a qualified campaign play. Fire-and-forget by contract: a failure
     * here must never surface to the listener, and the play is already over, so
     * there is nothing to retry against.
     */
    suspend fun recordCampaignStream(
        campaignId: String,
        userId: String? = null,
        listenDurationSeconds: Int = 0,
        countryCode: String? = null,
        isFullListen: Boolean = false,
    ) {
        if (!configured) return
        withContext(Dispatchers.IO) {
            try {
                val payload = buildString {
                    append("{\"p_campaign_id\":"); append(JsonPrimitive(campaignId)); append(',')
                    append("\"p_user_id\":"); append(JsonPrimitive(userId ?: UUID.randomUUID().toString())); append(',')
                    append("\"p_listen_duration_seconds\":"); append(listenDurationSeconds.coerceAtLeast(0)); append(',')
                    append("\"p_country_code\":"); append(JsonPrimitive(countryCode ?: "unknown")); append(',')
                    append("\"p_is_full_listen\":"); append(isFullListen)
                    append('}')
                }
                rpc("record_campaign_stream", payload)
            } catch (e: Exception) {
                Log.w(TAG, "recordCampaignStream failed for $campaignId: ${e.message}")
            }
        }
    }

    /**
     * Every currently-live campaign, for the home banner.
     *
     * Read straight from `track_campaigns` over REST rather than through the
     * queue-slot RPC: the RPC hands out one campaign per queue slot and marks it
     * served, which is the opposite of what a browse surface wants — the banner
     * shows what is live, and the RPC's own eligibility filter is
     * YouTube-shape-only while this surface draws from every live row.
     *
     * The `select=` list names every column the parser below reads. PostgREST
     * omits a column that is not named rather than returning it null, so a
     * field missing from that list is silently absent, not empty —
     * `tracks(title,cover_url)` and `users(artist_name)` are pulled in through
     * the table's two real foreign keys rather than invented as flat columns,
     * since neither lives on `track_campaigns` itself.
     *
     * Empty on any failure, the same fail-soft posture as every other read in
     * this file: a banner that cannot load renders nothing rather than breaking
     * the feed behind it.
     */
    suspend fun fetchLiveCampaignsForBanner(): List<CampaignCard> = withContext(Dispatchers.IO) {
        if (!configured) return@withContext emptyList()
        try {
            val request = Request.Builder()
                .url(
                    "$supabaseUrl/rest/v1/track_campaigns" +
                        "?select=id,source_url,resolved_song_id,track_id,artist_id," +
                        "total_streams,current_stage,tracks(title,cover_url),users(artist_name)" +
                        "&is_active=eq.true&is_paused=eq.false" +
                        "&current_stage=neq.completed&current_stage=neq.cancelled",
                )
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "fetchLiveCampaignsForBanner: HTTP ${response.code}")
                    return@use emptyList()
                }
                parseBannerRows(response.body?.string().orEmpty())
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchLiveCampaignsForBanner failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Turns the REST rows into banner cards, dropping any row with no playable
     * id. Deliberately tolerant per row: one malformed campaign must not take
     * the whole banner down, so anything unreadable is skipped rather than
     * thrown on.
     */
    private fun parseBannerRows(body: String): List<CampaignCard> {
        val array = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull()
            ?: return emptyList()
        return array.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val id = row["id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            val sourceUrl = row["source_url"]?.jsonPrimitive?.content.orEmpty()
            val songId = row["resolved_song_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: CampaignUrlResolver.extractVideoId(sourceUrl)
                ?: return@mapNotNull null

            val stage = row["current_stage"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: "planting"
            val tracks = row["tracks"] as? JsonObject
            val users = row["users"] as? JsonObject
            CampaignCard(
                id = id,
                songId = songId,
                title = tracks?.get("title")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: "Untitled",
                artist = users?.get("artist_name")?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    ?: "Unknown Artist",
                thumbnailUrl = tracks?.get("cover_url")?.jsonPrimitive?.content.orEmpty(),
                totalStreams = row["total_streams"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                currentStage = stage,
                // Same stage-based rule Velune uses: a campaign far enough along
                // its lifecycle has been through review.
                certified = stage == "branching" || stage == "full_bloom",
                isLive = true, // the query's own filter guarantees this
            )
        }
    }

    /**
     * A campaign's current gathered stream total, read straight from
     * `track_campaigns`. A card is a snapshot, so while a campaign is on screen
     * its total keeps moving; a surface showing "the campaign's total" re-reads
     * it rather than trusting the tap-time copy. Null on any failure.
     */
    suspend fun fetchCampaignTotalStreams(campaignId: String): Long? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        try {
            val request = Request.Builder()
                .url("$supabaseUrl/rest/v1/track_campaigns?select=total_streams&id=eq.$campaignId")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body?.string().orEmpty()
                val array = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull()
                val row = array?.firstOrNull() as? JsonObject ?: return@use null
                row["total_streams"]?.jsonPrimitive?.content?.toLongOrNull()
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchCampaignTotalStreams failed for $campaignId: ${e.message}")
            null
        }
    }

    /**
     * The reviewed portion of `campaign_genre_tile_mapping`, as a
     * `tile_title -> mapped_genre_id` map (the value may be null for a tile an
     * admin confirmed is a mood, not a genre). Empty on any failure, which
     * callers treat as "no confirmed mapping" — the same fail-closed default
     * the rest of this feature uses.
     */
    suspend fun fetchGenreTileMapping(): Map<String, String?> = withContext(Dispatchers.IO) {
        if (!configured) return@withContext emptyMap()
        try {
            val request = Request.Builder()
                .url("$supabaseUrl/rest/v1/campaign_genre_tile_mapping?is_reviewed=eq.true&select=tile_title,mapped_genre_id")
                .header("apikey", anonKey)
                .header("Authorization", "Bearer $anonKey")
                .get()
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyMap()
                val body = response.body?.string().orEmpty()
                val array = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull()
                    ?: return@use emptyMap()
                array.mapNotNull { element ->
                    val row = element as? JsonObject ?: return@mapNotNull null
                    val title = row["tile_title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                        ?: return@mapNotNull null
                    val mapped = row["mapped_genre_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    title to mapped
                }.toMap()
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetchGenreTileMapping failed: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Reports an unresolved genre tile so an admin can review it. Best-effort:
     * the host is mavins-web, not Supabase, so a blank host or a failed call is
     * silently ignored — the tile still reaches the server as a raw term and
     * fails closed there.
     */
    suspend fun ingestGenreTile(tileTitle: String) {
        if (genreTileHost.isBlank() || tileTitle.isBlank()) return
        withContext(Dispatchers.IO) {
            try {
                val payload = "{\"tile_title\":${JsonPrimitive(tileTitle)}}"
                val request = Request.Builder()
                    .url("${genreTileHost.trimEnd('/')}/api/campaigns/genre-tile-mapping/ingest")
                    .header("Content-Type", "application/json")
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                http.newCall(request).execute().use { }
            } catch (e: Exception) {
                Log.w(TAG, "ingestGenreTile failed for $tileTitle: ${e.message}")
            }
        }
    }

    /** POST a Supabase RPC and return its raw body, or null on a non-2xx. */
    private fun rpc(name: String, jsonBody: String): String? {
        val request = Request.Builder()
            .url("$supabaseUrl/rest/v1/rpc/$name")
            .header("apikey", anonKey)
            .header("Authorization", "Bearer $anonKey")
            .header("Content-Type", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .build()
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }

    private companion object {
        const val TAG = "LastWave"
    }
}
