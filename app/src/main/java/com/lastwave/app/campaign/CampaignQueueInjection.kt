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
 * The campaign for a slot is fetched through [campaignForSlot] as the splice
 * walks the base items — one call per slot, none pre-resolved by the caller.
 * The queue-slot RPC picks the least-recently-served eligible campaign and
 * stamps `last_served_at` in the same transaction, so the rotation is global
 * across every listener. Note the stamp happens when the slot is *built*, not
 * when it is played: a queue whose slots are all built up front marks every
 * campaign it spliced as served at that moment, played or not. That is the
 * server's contract, not something this splice can change.
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
     * Marks a spliced row as a promoted campaign. The flag is what the play
     * reporter reads to attribute a qualified play, and what keeps the row from
     * being reported as ordinary listening.
     *
     * LastWave has no queue-tier model, so this flag does not by itself give the
     * row the CONTEXT-tier survival BitChord and Velune implement: those apps
     * keep a `queueTier` on every entry and their clear path spares CONTEXT
     * rows, whereas [com.lastwave.app.playback.MusicPlayer.clearUpcoming] drops
     * every row after the current one, campaigns included. A campaign slot
     * cleared from the upcoming queue is gone; a later queue start fetches a
     * fresh one. Stated plainly because the flag's name invites the opposite
     * assumption.
     */
    private fun PlayableTrack.asCampaignEntry(): PlayableTrack = copy(isCampaign = true)
}
