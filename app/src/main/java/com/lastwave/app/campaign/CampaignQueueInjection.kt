package com.lastwave.app.campaign

import com.lastwave.app.playback.PlayableTrack

/**
 * Splices one campaign track into every fifth queue position.
 *
 * Every fifth position (indices 4, 9, 14, 19, …) is a campaign slot: the base
 * items are grouped into runs of four and one campaign is inserted after each
 * run. Injection happens on top of whatever order the base queue already has,
 * so a shuffled context keeps its shuffle and the campaigns ride along.
 *
 * The campaign for a slot is fetched *at the moment the slot is reached*, one
 * call per slot, never as a batch up front. That is deliberate: the queue-slot
 * RPC picks the least-recently-served eligible campaign and marks it served in
 * the same transaction, so the fairness guarantee is global across every
 * listener. Pre-fetching a batch would mark campaigns served long before any of
 * them reached a played slot, corrupting the bookkeeping for every other
 * listener building a queue at that moment.
 *
 * A slot whose fetch returns `null` (nothing eligible right now) is simply
 * skipped; the base items pass through unchanged and the run-of-four pattern
 * resumes at the next boundary.
 */
object CampaignQueueInjection {

    /** Base items per run; one campaign follows each completed run. */
    const val RUN = 4

    /**
     * @property items The spliced list, campaigns included.
     * @property adjustedTargetIndex Where the item at the requested original
     *   index landed after splicing, or -1 when no target was tracked.
     */
    data class Result(
        val items: List<PlayableTrack>,
        val adjustedTargetIndex: Int,
    )

    /**
     * Splices campaigns into one chunk of base items.
     *
     * @param baseItems The items from the underlying queue.
     * @param offset How many base items were consumed before this chunk. The
     *   run-of-four rule spans chunk boundaries, so this cannot restart at 0
     *   for each page.
     * @param targetOriginalIndex An original (pre-injection) index within
     *   *this* chunk to track through the splice, or -1 to skip tracking.
     *   Callers use it to find where the song the user tapped landed after
     *   campaigns were inserted around it. A real index is never negative, so
     *   -1 is an unambiguous "not applicable".
     * @param campaignForSlot Suspended fetch for one campaign, called once per
     *   slot. `null` skips that slot.
     */
    suspend fun inject(
        baseItems: List<PlayableTrack>,
        offset: Int = 0,
        targetOriginalIndex: Int = -1,
        campaignForSlot: suspend () -> PlayableTrack?,
    ): Result {
        val items = ArrayList<PlayableTrack>(baseItems.size + baseItems.size / RUN + 1)
        var adjustedTargetIndex = -1

        for (i in baseItems.indices) {
            val globalBaseIndex = offset + i
            if (globalBaseIndex == targetOriginalIndex) {
                adjustedTargetIndex = items.size
            }
            items.add(baseItems[i])

            if ((globalBaseIndex + 1) % RUN == 0) {
                campaignForSlot()?.let { items.add(it.asCampaignEntry()) }
            }
        }

        return Result(items = items, adjustedTargetIndex = adjustedTargetIndex)
    }

    /**
     * A campaign track is a CONTEXT-tier entry like any other album or playlist
     * row: it survives a user-queue clear and is never treated as a hand-queued
     * track. LastWave has no explicit queue-tier model, so the flag rides on the
     * row and the queue-clearing path preserves flagged entries.
     */
    private fun PlayableTrack.asCampaignEntry(): PlayableTrack = copy(isCampaign = true)
}
