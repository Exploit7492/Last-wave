package com.lastwave.app.campaign

/**
 * A single promoted-content card for the home banner.
 *
 * Ported from Velune's `CampaignCard` (the reference implementation of this
 * feature). The fields are the ones the banner actually draws; the ranking
 * fields Velune carries for its other campaign surfaces are deliberately absent
 * here, because LastWave's banner never reads them — see
 * [CampaignRepository.fetchLiveCampaignsForBanner] for why the row order is not
 * a ranking signal.
 */
data class CampaignCard(
    /** The campaign's own id, used to attribute a play and to re-read the total. */
    val id: String,
    /** The YouTube video id the card plays. */
    val songId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    /** The campaign's gathered stream total, as of the last read. */
    val totalStreams: Long,
    /** The lifecycle stage, e.g. `planting` or `full_bloom`. */
    val currentStage: String,
    /** Whether a human reviewer approved this campaign. Derived from the stage. */
    val certified: Boolean,
    /** Currently live and unpaused. The query's own filter guarantees this. */
    val isLive: Boolean,
)
