package com.lastwave.app.campaign

/**
 * Extracts a YouTube video id from a campaign row's raw `source_url`.
 *
 * The queue-slot RPC's eligibility filter admits a row by the *shape* of its
 * link, so a row that reaches the client without a `resolved_song_id` is one
 * whose link was stored as a plain YouTube URL. This deliberately understands
 * every shape a user actually pastes — a watch link with `v=` first, a
 * `youtu.be` short, a `shorts` or `live` URL, or a bare 11-character id — rather
 * than only the one narrow shape the original inline `indexOf("v=")` handled.
 */
object CampaignUrlResolver {

    // YouTube video ids are always 11 characters from this alphabet.
    private val VIDEO_ID_REGEX = Regex("[A-Za-z0-9_-]{11}")

    private val URL_PATTERNS = listOf(
        // `v=` may be the first query parameter (the common share-link shape)
        // or follow others; zero or more `key=value&` pairs are allowed ahead
        // of it so both match.
        Regex("""(?:music\.)?youtube\.com/watch\?(?:[^&]*&)*v=([A-Za-z0-9_-]{11})"""),
        Regex("""(?:music\.)?youtube\.com/shorts/([A-Za-z0-9_-]{11})"""),
        Regex("""(?:music\.)?youtube\.com/live/([A-Za-z0-9_-]{11})"""),
        Regex("""youtu\.be/([A-Za-z0-9_-]{11})"""),
    )

    /**
     * The video id in [sourceUrl] in any of the shapes above, or null. Falls
     * back to treating the whole trimmed string as a bare id when it is already
     * exactly 11 valid characters.
     */
    fun extractVideoId(sourceUrl: String): String? {
        val trimmed = sourceUrl.trim()
        if (trimmed.isEmpty()) return null
        for (pattern in URL_PATTERNS) {
            pattern.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return if (VIDEO_ID_REGEX.matches(trimmed)) trimmed else null
    }
}
