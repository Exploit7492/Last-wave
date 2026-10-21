package com.lastwave.app.campaign

import com.lastwave.app.diagnostics.AppLog as Log
import com.lastwave.app.playback.PlayableTrack
import java.util.UUID

/**
 * The app's single campaign entry point.
 *
 * The backend address and key are per-app build configuration (read from
 * `BuildConfig`), so they are handed in once at startup rather than hardcoded
 * here. Until [init] runs, [nextForSlot] returns null and queues play without
 * campaigns, which is also exactly what a build with no backend configured
 * does.
 *
 * Track resolution is likewise injected: this object lives outside Hilt, while
 * the InnerTube client that turns a video id (or a title/artist query) into a
 * playable track is a Hilt singleton. [TrackSource] carries those two lookups
 * in, so the campaign package needs no dependency on the DI graph.
 */
object Campaigns {

    private const val TAG = "LastWave"

    /**
     * How the campaign layer turns a resolved video id — or a Spotify
     * title/artist pair — into the app's own playable row. Supplied at [init].
     */
    interface TrackSource {
        /** The track for a known video id, or null when it cannot be resolved. */
        suspend fun byVideoId(videoId: String): PlayableTrack?

        /** The best match for a title/artist query, or null. */
        suspend fun search(title: String, artist: String): PlayableTrack?
    }

    @Volatile
    private var repository: CampaignRepository? = null

    /**
     * This install's listener identity, supplied at [init] from persisted
     * storage. The play reporter needs it to register a listener before it can
     * attribute a play. Defaults to a per-process UUID so an un-wired install
     * still reports rather than crashing.
     */
    @Volatile
    private var deviceIdValue: String = UUID.randomUUID().toString()

    /**
     * The genre/mood term of the surface the current queue was started from, or
     * null for a queue with no genre context (search, album, playlist, radio,
     * autoplay). Set by the genre path, read at injection — see
     * [nextForSlotResolvingGenre].
     */
    @Volatile
    private var genreContext: String? = null

    /**
     * Enables campaign injection. Safe to call more than once; the last call
     * wins. A blank url or key disables campaigns rather than throwing.
     *
     * @param genreTileHost The mavins-web origin used to report an unresolved
     *   genre tile for review. Blank disables that reporting only; the tile
     *   still reaches the server as a raw term and fails closed there.
     * @param deviceId This install's persisted listener identity. Blank leaves
     *   the per-process default in place.
     * @param trackSource The lookups that turn a campaign row into a playable
     *   track. Without one, rows resolve to null and no campaign is placed.
     */
    fun init(
        supabaseUrl: String,
        anonKey: String,
        genreTileHost: String = "",
        deviceId: String = "",
        trackSource: TrackSource? = null,
    ) {
        if (deviceId.isNotBlank()) deviceIdValue = deviceId
        if (supabaseUrl.isBlank() || anonKey.isBlank()) {
            Log.i(TAG, "campaigns disabled: no backend configured")
            repository = null
            return
        }
        if (trackSource == null) {
            Log.w(TAG, "campaigns disabled: no track source supplied")
            repository = null
            return
        }
        repository = CampaignRepository(
            supabaseUrl = supabaseUrl.trimEnd('/'),
            anonKey = anonKey,
            trackSource = trackSource,
            genreTileHost = genreTileHost,
        )
    }

    /** True when a backend is configured and campaign injection is live. */
    val enabled: Boolean get() = repository != null

    /** This install's listener identity, for [ensureDeviceListener]. */
    fun deviceId(): String = deviceIdValue

    /** Records the genre context of the queue about to start; null clears it. */
    fun setGenreContext(term: String?) {
        genreContext = term?.takeIf { it.isNotBlank() }
    }

    /**
     * The campaign for one queue slot, or null when there is none to place.
     *
     * @see CampaignRepository.fetchNextForQueueSlot
     */
    suspend fun nextForSlot(genre: String? = null): PlayableTrack? =
        repository?.fetchNextForQueueSlot(genre)

    /**
     * The campaign for one queue slot on the current queue, resolving the genre
     * context the genre path recorded. A null context reaches the RPC as a null
     * genre and asks for any eligible campaign — injection is a monetization
     * slot, not a genre feature — while a concrete term confines the pick to
     * that genre and fails closed if it cannot be resolved.
     */
    suspend fun nextForSlotResolvingGenre(): PlayableTrack? = nextForSlotForGenre(genreContext)

    /**
     * As [nextForSlotResolvingGenre], but for an explicitly supplied genre term
     * rather than the ambient one. A queue start records its genre by passing
     * the term here instead of calling [setGenreContext], which matters because
     * the splice runs asynchronously: an ambient value set at the call site
     * could be overwritten by a later queue start before the earlier splice
     * reads it.
     */
    suspend fun nextForSlotForGenre(term: String?): PlayableTrack? {
        val r = repository ?: return null
        val genre = term?.let { GenreTileMappingCache.resolveTile(it, r) }
        return r.fetchNextForQueueSlot(genre)
    }

    /**
     * What a genre-tile tap should send: a resolved canonical genre, the raw
     * tile title when it is not yet reviewed, or null for a tile reviewed as a
     * mood. See [GenreTileMappingCache.resolveTile].
     */
    suspend fun resolveGenreTile(tileTitle: String): String? {
        val r = repository ?: return null
        return GenreTileMappingCache.resolveTile(tileTitle, r)
    }

    /** Registers this device as a listener; returns the listener id, or null. */
    suspend fun ensureDeviceListener(deviceId: String): String? =
        repository?.ensureDeviceListener(deviceId)

    /** Reports a qualified campaign play. Fire-and-forget. */
    suspend fun recordStream(
        campaignId: String,
        userId: String? = null,
        listenDurationSeconds: Int = 0,
        countryCode: String? = null,
        isFullListen: Boolean = false,
    ) = repository?.recordCampaignStream(campaignId, userId, listenDurationSeconds, countryCode, isFullListen)

    /** A campaign's current gathered total, or null on failure. */
    suspend fun campaignTotalStreams(campaignId: String): Long? =
        repository?.fetchCampaignTotalStreams(campaignId)

    /**
     * Every currently-live campaign, for the home banner. Empty when no backend
     * is configured, which is what a build without one shows.
     */
    suspend fun liveCampaignsForBanner(): List<CampaignCard> =
        repository?.fetchLiveCampaignsForBanner() ?: emptyList()
}
