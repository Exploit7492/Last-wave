package com.lastwave.app.data.ytmusic

import android.util.Log
import com.lastwave.app.data.music.InnerTubeMusicApi
import com.lastwave.app.data.music.YouTubeMusicTrack
import com.lastwave.app.playback.MusicPlayer
import com.lastwave.app.playback.MusicPlayerState
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Reports songs genuinely listened to in LastWave to the connected YouTube
 * Music account's watch history, independent of where the audio came from
 * (Qobuz/FLAC, YouTube, or a downloaded file). Playback itself is never
 * touched: matching and submission run on background coroutines, audio keeps
 * playing its original source at its original quality, and every failure is
 * contained here.
 *
 * Protocol (verified against ytmusicapi master `LibraryMixin.add_history_item`
 * and corroborated by ytmapi-rs `AddHistoryItemQuery`): history is registered
 * by GET-ing the per-play `playbackTracking.videostatsPlaybackUrl.baseUrl`
 * taken from an AUTHENTICATED `player` response for the videoId, with
 * `ver=2`, `c=WEB_REMIX` and a random 16-char `cpn`. Merely fetching a song,
 * resolving its stream, or reading history registers nothing.
 *
 * Listening threshold (history-reporting ONLY — never fails, times out, or
 * auto-skips playback): a listen qualifies after genuinely advancing playback
 * of max(30s, min(50% of duration, 4min)); unknown duration falls back to 90s.
 * This mirrors the Last.fm scrobble convention (half the track, capped at 4
 * minutes) with a 30s floor so short tracks still count. Only position
 * movement while `isPlaying && !isBuffering` counts: pauses, buffering,
 * seeks (large jumps are discarded, never credited) and queueing without
 * playing contribute nothing. One submission per qualifying session; a full
 * restart/repeat opens a new session that may qualify again.
 *
 * Account & privacy rules:
 * - Submissions require the "Sync playback to YouTube Music history" switch
 *   (default ON) AND a currently connected account. The pending job captures
 *   the account snapshot at eligibility and aborts if the live connection no
 *   longer equals it — one account's listens are never written to another.
 * - Disabling the switch or disconnecting/switching accounts cancels
 *   in-flight work immediately.
 * - Retries are bounded (3 attempts, backoff) and account-scoped. Timeouts
 *   are treated as UNCERTAIN and never retried (the ping may have landed).
 *   There is no persistent offline queue: the endpoint accepts no timestamp,
 *   so a late replay would misattribute the listen time. Transient blips are
 *   covered by the in-memory retry; longer outages are dropped, never
 *   backfilled, never reordered.
 * - Nothing sensitive is logged: no cookies, auth headers, or tracking URLs —
 *   only videoIds and status codes.
 *
 * Known protocol limitation: YouTube's "pause watch history" account setting
 * has no verified InnerTube read endpoint (ytmusicapi exposes none either),
 * so paused-history status cannot be checked reliably. In that case YouTube
 * itself discards the entry server-side; we do not claim to detect it.
 */
@Singleton
class YtMusicHistorySyncManager @Inject constructor(
    private val musicPlayer: MusicPlayer,
    private val innerTube: InnerTubeMusicApi,
    private val ytAuth: YtMusicAuthManager,
    private val preferences: YtMusicPreferences,
    private val applicationScope: CoroutineScope,
) {
    @Volatile private var started = false

    // ── Session accounting (single-threaded: only mutated from the state collector) ──
    private var sessionKey: String? = null
    private var sessionEpoch = 0L
    private var lastPositionMs = 0L
    private var listenedMs = 0L
    private var submittedSessionId: String? = null

    @Volatile private var historyEnabled = true

    /** In-flight submissions, cancelled on disable / disconnect / switch. */
    private val activeSubmissions = ConcurrentHashMap.newKeySet<Job>()

    fun start() {
        if (started) return
        started = true
        applicationScope.launch { musicPlayer.state.collect { onPlayerState(it) } }
        applicationScope.launch {
            ytAuth.connection.collect {
                // Any account change (connect, disconnect, switch) invalidates
                // pending work and restarts accounting so the ongoing listen
                // can only ever qualify fresh for the current account.
                cancelActive("account change")
                resetSession(keepPosition = true)
            }
        }
        applicationScope.launch {
            preferences.historySyncEnabled.collect { enabled ->
                historyEnabled = enabled
                if (!enabled) {
                    cancelActive("disabled")
                    resetSession(keepPosition = true)
                }
            }
        }
    }

    private fun resetSession(keepPosition: Boolean) {
        if (!keepPosition) lastPositionMs = 0L
        listenedMs = 0L
        submittedSessionId = null
        sessionEpoch++
    }

    private fun onPlayerState(s: MusicPlayerState) {
        val track = s.current
        val key = track?.let { "${s.currentIndex}|${it.title}|${it.artist}|${it.album}" }
        if (key == null || key != sessionKey) {
            // New track (or nothing playing): unqualified sessions are simply
            // dropped — history is never backfilled.
            sessionKey = key
            lastPositionMs = s.positionMs
            listenedMs = 0L
            submittedSessionId = null
            return
        }
        val sessionId = "$sessionEpoch|$key"
        if (submittedSessionId == sessionId || !historyEnabled) {
            lastPositionMs = s.positionMs
            return
        }
        if (s.isPlaying && !s.isBuffering) {
            val delta = s.positionMs - lastPositionMs
            when {
                // Genuine advancement only; the cap discards seeks and jumps.
                delta in 1..MAX_POSITION_DELTA_MS -> listenedMs += delta
                // Full restart / repeat-one loop → a new session that may
                // qualify again as a genuine repeat listen.
                s.positionMs < RESTART_POSITION_MS && lastPositionMs > RESTART_FLOOR_MS -> {
                    listenedMs = 0L
                    submittedSessionId = null
                    sessionEpoch++
                    lastPositionMs = s.positionMs
                    return
                }
            }
            // (submittedSessionId != sessionId is guaranteed by the early
            // return above; the restart branch returns before reaching here.)
            if (track != null && listenedMs >= requiredListenMs(s.durationMs)) {
                // Claim the session BEFORE launching so retries, reconnects,
                // or source fallbacks within this listen can't double-submit.
                val claimedId = "$sessionEpoch|$key"
                submittedSessionId = claimedId
                val pending = PendingListen(
                    title = track.title,
                    artist = track.artist,
                    album = track.album,
                    videoId = track.videoId,
                    durationMs = s.durationMs,
                    account = ytAuth.connection.value,
                    sessionId = claimedId,
                )
                launchSubmit(pending)
            }
        }
        lastPositionMs = s.positionMs
    }

    private fun launchSubmit(pending: PendingListen) {
        val job = applicationScope.launch(Dispatchers.IO) {
            try {
                submitWithRetry(pending)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (e: Exception) {
                Log.w(TAG, "History sync failed for session ${pending.sessionId}: ${e.message}")
            }
        }
        activeSubmissions.add(job)
        job.invokeOnCompletion { activeSubmissions.remove(job) }
    }

    private fun cancelActive(reason: String) {
        if (activeSubmissions.isEmpty()) return
        Log.d(TAG, "Cancelling ${activeSubmissions.size} pending history submission(s): $reason")
        activeSubmissions.forEach { it.cancel() }
    }

    private suspend fun submitWithRetry(pending: PendingListen) {
        if (!preferences.historySyncEnabled.first()) {
            Log.d(TAG, "History sync off; dropping ${pending.sessionId}")
            return
        }
        if (!pending.account.isConnected || ytAuth.connection.value != pending.account) {
            Log.d(TAG, "Account changed/disconnected; dropping ${pending.sessionId}")
            return
        }
        if (pending.title.isBlank()) {
            Log.d(TAG, "Blank title; skipping ${pending.sessionId}")
            return
        }
        val videoId = pending.videoId?.takeIf { it.isNotBlank() }
            ?: resolveStrictVideoId(pending)?.also {
                Log.d(TAG, "Strict match for '${pending.title}' -> $it")
            }
        if (videoId == null) {
            Log.d(TAG, "No strict YT match for '${pending.title}' by '${pending.artist}'; skipping")
            return
        }

        var trackingUrl: String? = null
        var attempt = 0
        while (attempt < MAX_SUBMIT_ATTEMPTS) {
            attempt++
            // Re-gate every attempt: never write to a different account than
            // the one that was connected when this listen qualified.
            if (!preferences.historySyncEnabled.first() ||
                !pending.account.isConnected ||
                ytAuth.connection.value != pending.account
            ) {
                Log.d(TAG, "Gate closed mid-retry; dropping ${pending.sessionId}")
                return
            }
            try {
                // One player lookup per session; the tracking URL is reused
                // across retries instead of re-fetching every attempt.
                val baseUrl = trackingUrl
                    ?: withContext(Dispatchers.IO) { innerTube.fetchHistoryTrackingUrl(videoId) }
                    ?: throw IOException("No history tracking URL for $videoId")
                trackingUrl = baseUrl
                val code = withContext(Dispatchers.IO) {
                    innerTube.submitHistoryPlayback(baseUrl, newCpn())
                }
                when {
                    code in 200..299 -> {
                        Log.d(TAG, "History accepted for $videoId (HTTP $code)")
                        return
                    }
                    code == 401 || code == 403 -> {
                        // Stale/revoked credentials. Drop without retry and
                        // without touching playback; the user can reconnect.
                        Log.w(TAG, "History auth failure (HTTP $code); dropping $videoId")
                        return
                    }
                    code == 408 || code == 429 || code in 500..599 -> {
                        Log.w(TAG, "History transient HTTP $code for $videoId (attempt $attempt)")
                    }
                    else -> {
                        Log.w(TAG, "History permanent HTTP $code for $videoId; dropping")
                        return
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: SocketTimeoutException) {
                // Uncertain: the ping may have reached YouTube. Retrying
                // could double-register history, so drop it instead.
                Log.w(TAG, "History ping timed out for $videoId; not retrying (uncertain outcome)")
                return
            } catch (e: InnerTubeMusicApi.InnerTubeHttpException) {
                // post()-level failures from the player lookup.
                if (e.responseCode == 401 || e.responseCode == 403) {
                    Log.w(TAG, "History player auth failure (HTTP ${e.responseCode}); dropping $videoId")
                    return
                }
                Log.w(TAG, "History player HTTP ${e.responseCode} for $videoId (attempt $attempt)")
            } catch (e: Exception) {
                Log.w(TAG, "History attempt $attempt failed for $videoId: ${e.message}")
            }
            if (attempt < MAX_SUBMIT_ATTEMPTS) delay(RETRY_BASE_DELAY_MS * attempt)
        }
        Log.w(TAG, "History giving up on $videoId after $MAX_SUBMIT_ATTEMPTS attempts")
    }

    /**
     * Strict YT match for tracks with no trusted videoId: reuses
     * [InnerTubeMusicApi.findBestMatchOrNull] (title ≥72 / artist ≥50
     * similarity, unexpected remix/live-style variants penalised), then
     * additionally requires duration agreement and exact version-marker
     * parity so originals, remixes, live recordings, covers and sped-up
     * versions are never confused. Returns null when ambiguous.
     */
    private suspend fun resolveStrictVideoId(pending: PendingListen): String? {
        if (pending.artist.isBlank()) return null
        val candidate: YouTubeMusicTrack = try {
            innerTube.findBestMatchOrNull(pending.title, pending.artist, prefetchStreams = false)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            null
        } ?: return null
        if (candidate.videoId.isBlank()) return null
        if (versionMarkers(candidate.title) != versionMarkers(pending.title)) return null
        val candidateMs = candidate.durationSeconds?.times(1000L)
        if (pending.durationMs > 0 && candidateMs != null && candidateMs > 0) {
            val tolerance = maxOf(DURATION_TOLERANCE_MS, pending.durationMs * DURATION_TOLERANCE_RATIO_NUM / 100)
            if (abs(candidateMs - pending.durationMs) > tolerance) return null
        }
        return candidate.videoId
    }

    private fun versionMarkers(title: String): Set<String> {
        val lower = title.lowercase()
        val tokens = lower.split(VERSION_SPLIT).filter { it.isNotBlank() }.toSet()
        return buildSet {
            for (marker in VERSION_MARKERS) {
                if (marker in tokens) add(if (marker == "remastered") "remaster" else marker)
            }
            if ("nightcore" in lower) add("nightcore")
            if ("sped" in tokens || "sped-up" in lower || "sped up" in lower || "speed up" in lower) add("sped")
        }
    }

    private fun newCpn(): String {
        val alphabet = CPN_ALPHABET
        return CharArray(CPN_LENGTH) { alphabet[Random.nextInt(alphabet.length)] }.concatToString()
    }

    private data class PendingListen(
        val title: String,
        val artist: String,
        val album: String?,
        val videoId: String?,
        val durationMs: Long,
        val account: YtConnection,
        val sessionId: String,
    )

    private companion object {
        const val TAG = "YtHistorySync"

        // Listening threshold: max(30s, min(50% of duration, 4min)); 90s when
        // duration is unknown. History-reporting only.
        const val MIN_LISTEN_THRESHOLD_MS = 30_000L
        const val MAX_LISTEN_THRESHOLD_MS = 240_000L
        const val FALLBACK_LISTEN_THRESHOLD_MS = 90_000L

        /** Position jumps larger than this are seeks, never listening time. */
        const val MAX_POSITION_DELTA_MS = 2_500L

        /** Position reset below this (from far in) = restart/repeat → new session. */
        const val RESTART_POSITION_MS = 3_000L
        const val RESTART_FLOOR_MS = 10_000L

        /** Bounded, account-scoped retries for transient failures only. */
        const val MAX_SUBMIT_ATTEMPTS = 3
        const val RETRY_BASE_DELAY_MS = 2_000L

        /** Track-length agreement for strict matches: 7s or 5%, whichever larger. */
        const val DURATION_TOLERANCE_MS = 7_000L
        const val DURATION_TOLERANCE_RATIO_NUM = 5L

        const val CPN_LENGTH = 16
        const val CPN_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        val VERSION_SPLIT = Regex("[^a-z0-9]+")
        val VERSION_MARKERS = setOf(
            "live", "remix", "karaoke", "cover", "instrumental", "slowed",
            "acoustic", "demo", "edit", "remaster", "remastered", "mono",
            "stereo", "unplugged", "stripped",
        )

        fun requiredListenMs(durationMs: Long): Long {
            val need = if (durationMs > 0) {
                minOf(durationMs / 2, MAX_LISTEN_THRESHOLD_MS)
            } else {
                FALLBACK_LISTEN_THRESHOLD_MS
            }
            return maxOf(need, MIN_LISTEN_THRESHOLD_MS)
        }
    }
}
