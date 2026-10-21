package com.lastwave.app.campaign

import com.lastwave.app.diagnostics.AppLog as Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Wraps [CampaignRepository.fetchGenreTileMapping] with the periodic-refresh
 * caching its own KDoc asks callers to provide — so a session browsing several
 * genre tiles fetches the reviewed mapping once, not on every tap.
 *
 * `object`, not `class`: the point of a cache is amortizing repeated fetches
 * across every genre-tile tap for the process lifetime, which only works if
 * every caller shares one instance.
 */
object GenreTileMappingCache {

    private const val TAG = "GenreTileMappingCache"

    // 15 minutes — a judgment call, not a confirmed product requirement. Long
    // enough that browsing several tiles in one sitting does not refetch the
    // whole table; short enough that a newly-reviewed mapping reaches a running
    // session within one reasonable sitting.
    private const val REFRESH_INTERVAL_MS = 15 * 60 * 1000L

    private val mutex = Mutex()

    private var cachedMapping: Map<String, String?> = emptyMap()

    // 0L means "never fetched" — deliberately NOT inferred from map emptiness.
    // The live table is expected to start (and may stay) genuinely empty, and
    // judging staleness by emptiness would refetch on every call forever in
    // exactly the state the feature launches in.
    private var lastFetchedAtMs: Long = 0L

    /**
     * What a genre-tile tap should send to
     * [CampaignRepository.fetchNextForQueueSlot], or `null` when there is
     * genuinely no genre context.
     *
     * The distinction this exists to preserve:
     *  * no genre context (search, album, playlist, radio, autoplay) — never
     *    reaches here; the caller sends `null` and the RPC reads "any eligible".
     *  * genre context, resolved — returns the canonical genre id.
     *  * genre context, unresolved — returns the **raw tile title**, never
     *    `null`, and fires [CampaignRepository.ingestGenreTile]. Returning null
     *    here is what let a genre-scoped slot widen to the whole catalogue; the
     *    server resolves the raw title or fails closed there.
     *  * genre context, reviewed as a mood (present key, null value) — returns
     *    `null`, deliberately: there is no genre promise to keep, so the slot
     *    behaves like an unscoped queue.
     */
    suspend fun resolveTile(tileTitle: String, repository: CampaignRepository): String? {
        ensureFresh(repository)

        if (!cachedMapping.containsKey(tileTitle)) {
            repository.ingestGenreTile(tileTitle)
            return tileTitle
        }
        return cachedMapping[tileTitle]
    }

    private suspend fun ensureFresh(repository: CampaignRepository) {
        val now = System.currentTimeMillis()
        if (lastFetchedAtMs != 0L && now - lastFetchedAtMs < REFRESH_INTERVAL_MS) return

        mutex.withLock {
            val nowInLock = System.currentTimeMillis()
            if (lastFetchedAtMs != 0L && nowInLock - lastFetchedAtMs < REFRESH_INTERVAL_MS) return@withLock

            val fetched = repository.fetchGenreTileMapping()
            cachedMapping = fetched
            lastFetchedAtMs = nowInLock
            Log.d(TAG, "Refreshed genre tile mapping cache: ${fetched.size} reviewed entries")
        }
    }
}
