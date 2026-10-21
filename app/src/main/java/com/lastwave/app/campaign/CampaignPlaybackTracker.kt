package com.lastwave.app.campaign

import com.lastwave.app.diagnostics.AppLog as Log
import com.lastwave.app.playback.PlayableTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Turns the player's ticks into qualified campaign plays, reported to the
 * backend.
 *
 * A campaign play is a paid event, so it is counted once, and only once enough
 * of the track has actually gone by. This is fed from the player's progress
 * ticker, which only ticks while audio is coming out — so a skip cannot inflate
 * it and a pause cannot either. The qualification rule (half the track, floored
 * at 30s and capped at four minutes) is the same one the app's scrobbler uses,
 * so both agree on what a play is.
 *
 * Every campaign play is reported once per track, keyed on the campaign's own
 * id, so replaying the same promoted track later reports again but a single
 * listen never double-counts. Reporting is best-effort: a failure is logged and
 * dropped, never surfaced to the listener and never retried, because the play
 * is already over.
 */
object CampaignPlaybackTracker {

    private const val TAG = "LastWave"

    private const val PLAY_FLOOR_MS = 30_000L
    private const val PLAY_CEILING_MS = 4 * 60_000L

    /** A pause must not be counted when playback resumes; the gap is capped. */
    private const val MAX_STEP_MS = 10_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class Play(val campaignId: String) {
        var playedMs = 0L
        var lastSampleAt = 0L
        var reported = false
    }

    private val plays = ConcurrentHashMap<String, Play>()

    /**
     * One tick of the player's sampler, called only for a campaign row.
     * [durationMs] is the decoder's figure when it has one; the row's stated
     * runtime stands in until it does.
     *
     * Progress is measured as wall-clock time between ticks, not from the
     * player's position — the same reason the app's own listening record samples
     * rather than measures. Seeking cannot inflate it because position is never
     * consulted, and the gap either side of a pause is capped so an hour on the
     * lock screen does not arrive as an hour of campaign listening.
     */
    @Synchronized
    fun onSample(song: PlayableTrack, durationMs: Long) {
        if (!song.isCampaign) return
        val campaignId = song.campaignId ?: return
        val now = System.currentTimeMillis()
        val play = plays.getOrPut(campaignId) { Play(campaignId) }
        if (play.lastSampleAt == 0L) {
            play.lastSampleAt = now
            return
        }
        val step = (now - play.lastSampleAt).coerceIn(0L, MAX_STEP_MS)
        play.lastSampleAt = now
        if (step <= 0L) return
        play.playedMs += step
        if (play.reported) return

        val length = durationMs.takeIf { it > 0 } ?: song.durationMs ?: 0L
        val threshold = if (length > 0) {
            min(length / 2, PLAY_CEILING_MS).coerceAtLeast(PLAY_FLOOR_MS)
        } else {
            PLAY_FLOOR_MS
        }
        if (play.playedMs >= threshold) {
            play.reported = true
            val seconds = (play.playedMs / 1000L).toInt()
            val full = length > 0 && play.playedMs >= length * 9 / 10
            scope.launch {
                try {
                    // A listener row must exist before the play event's foreign
                    // key can reference it, so this is awaited before the report
                    // — both calls live off the player's own thread, so playback
                    // is never blocked.
                    val listenerId = Campaigns.ensureDeviceListener(Campaigns.deviceId())
                    Campaigns.recordStream(
                        campaignId = campaignId,
                        userId = listenerId,
                        listenDurationSeconds = seconds,
                        isFullListen = full,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "campaign play report failed for $campaignId: ${e.message}")
                }
            }
        }
    }

    /**
     * Called when a track stops; a stopped campaign must not carry progress
     * into its next appearance.
     */
    fun onStopped() {
        plays.clear()
    }
}
