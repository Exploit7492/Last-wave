package com.lastwave.app.data.music

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeJavaScriptPlayerManager
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves the signed/ciphered YouTube media URLs that raw InnerTube player
 * responses no longer reliably expose. Extraction happens locally; no proxy
 * or account is used.
 */
@Singleton
class YouTubeStreamExtractor @Inject constructor(
    private val http: OkHttpClient,
) {
    private val downloader = OkHttpNewPipeDownloader(http)

    @Volatile
    private var initialized = false

    fun invalidateCache(@Suppress("UNUSED_PARAMETER") videoId: String) = Unit

    suspend fun resolveAudioStream(
        videoId: String,
        preferM4a: Boolean = false,
        preferOpus: Boolean = false,
    ): YouTubeAudioStream = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        initialize()
        val info = try {
            StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            throw IOException("YouTube stream extraction failed for $videoId", error)
        }
        val ordered = info.audioStreams.sortedWith(
            compareBy<org.schabi.newpipe.extractor.stream.AudioStream> { audioPreference(it, preferM4a, preferOpus) }
                .thenByDescending { maxOf(it.averageBitrate, it.bitrate) },
        )
        if (ordered.isEmpty()) throw IOException("YouTube returned no playable audio stream for $videoId")
        for (stream in ordered.take(MAX_PROBED_AUDIO_STREAMS)) {
            val candidate = stream.toYouTubeAudioStream(videoId, info.duration, now)
            if (probePlayable(candidate)) return@withContext candidate
        }
        invalidatePlayerState(videoId)
        throw IOException("YouTube audio URLs were rejected for $videoId")
    }

    private fun org.schabi.newpipe.extractor.stream.AudioStream.toYouTubeAudioStream(
        videoId: String,
        durationSeconds: Long,
        now: Long,
    ): YouTubeAudioStream {
        val reportedBitrate = maxOf(averageBitrate, bitrate)
        return YouTubeAudioStream(
            videoId = videoId,
            url = content,
            itag = itag.takeIf { it >= 0 },
            mimeType = format?.mimeType,
            codec = codec?.takeIf(String::isNotBlank),
            // NewPipe reports kbps while raw InnerTube formats report bps;
            // normalize both providers to bps for one truthful UI value.
            bitrate = if (reportedBitrate in 1..9_999) reportedBitrate * 1_000 else reportedBitrate,
            sampleRateHz = itagItem?.sampleRate?.takeIf { it > 0 },
            durationMs = itagItem?.approxDurationMs?.takeIf { it > 0 }
                ?: durationSeconds.takeIf { it > 0 }?.times(1_000L),
            contentLength = itagItem?.contentLength?.takeIf { it > 0 },
            isAdaptive = true,
            clientProfile = NEWPIPE_CLIENT_PROFILE,
            authScope = ANONYMOUS_AUTH_SCOPE,
            requestHeaders = mapOf(
                "User-Agent" to YOUTUBE_WEB_USER_AGENT,
                "Origin" to YOUTUBE_ORIGIN,
                "Referer" to "$YOUTUBE_ORIGIN/watch?v=$videoId",
            ),
            expiresAtEpochMs = content.toHttpUrlOrNull()
                ?.queryParameter("expire")
                ?.toLongOrNull()
                ?.times(1_000L)
                ?: now + UNKNOWN_EXPIRY_TTL_MS,
        )
    }

    private fun audioPreference(
        stream: org.schabi.newpipe.extractor.stream.AudioStream,
        preferM4a: Boolean,
        preferOpus: Boolean,
    ): Int {
        val mime = stream.format?.mimeType.orEmpty().lowercase()
        val opus = mime.contains("webm") || stream.codec?.contains("opus", ignoreCase = true) == true
        val m4a = mime.contains("mp4") || mime.contains("m4a")
        return when {
            preferOpus && opus -> 0
            preferM4a && m4a -> 0
            stream.itag == 140 || m4a -> 1
            stream.itag == 251 || opus -> 2
            else -> 3
        }
    }

    /** A googlevideo URL that answers 403 must never be handed to the player. */
    private fun probePlayable(stream: YouTubeAudioStream): Boolean {
        val request = okhttp3.Request.Builder()
            .url(stream.url)
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=0-1")
            .apply {
                stream.requestHeaders.forEach { (name, value) -> header(name, value) }
            }
            .build()
        val call = http.newCall(request)
        call.timeout().timeout(PROBE_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        return try {
            call.execute().use { response ->
                val type = response.header("Content-Type").orEmpty().lowercase()
                val playableType = !type.contains("text/html") &&
                    !type.contains("application/json") &&
                    !type.contains("text/plain")
                (response.code == 200 || response.code == 206) && playableType
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun getSignatureTimestamp(videoId: String): Int? = withContext(Dispatchers.IO) {
        initialize()
        try {
            YoutubeJavaScriptPlayerManager.getSignatureTimestamp(videoId)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    fun decipherStreamUrl(videoId: String, signatureCipher: String): String? {
        initialize()
        val parameters = "https://cipher.invalid/?$signatureCipher".toHttpUrlOrNull() ?: return null
        var resolvedUrl = parameters.queryParameter("url") ?: return null
        val encryptedSignature = parameters.queryParameter("s")
        if (!encryptedSignature.isNullOrBlank()) {
            val signature = runCatching {
                YoutubeJavaScriptPlayerManager.deobfuscateSignature(videoId, encryptedSignature)
            }.getOrNull() ?: return null
            if (resolvedUrl.toHttpUrlOrNull() == null) return null
            resolvedUrl = appendQueryParameterPreservingUrl(
                url = resolvedUrl,
                name = parameters.queryParameter("sp") ?: "signature",
                value = signature,
            )
        }
        return runCatching {
            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, resolvedUrl)
        }.getOrDefault(resolvedUrl)
    }

    fun deobfuscateThrottlingParameter(videoId: String, url: String): String {
        initialize()
        return runCatching {
            YoutubeJavaScriptPlayerManager.getUrlWithThrottlingParameterDeobfuscated(videoId, url)
        }.getOrDefault(url)
    }

    /** A rejected signed URL usually means NewPipe's cached player script is
     * stale. Clear that state locally so playback can recover without an app
     * force-stop. */
    fun invalidatePlayerState(videoId: String) {
        invalidateCache(videoId)
        runCatching { YoutubeJavaScriptPlayerManager.clearAllCaches() }
    }

    fun preWarm() {
        initialize()
    }

    private fun appendQueryParameterPreservingUrl(url: String, name: String, value: String): String {
        val fragmentIndex = url.indexOf('#').takeIf { it >= 0 } ?: url.length
        val base = url.substring(0, fragmentIndex)
        val fragment = url.substring(fragmentIndex)
        val separator = when {
            base.endsWith('?') || base.endsWith('&') -> ""
            '?' in base -> "&"
            else -> "?"
        }
        return "$base$separator${Uri.encode(name)}=${Uri.encode(value)}$fragment"
    }

    private fun initialize() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            NewPipe.init(downloader)
            initialized = true
        }
    }

    companion object {
        private const val UNKNOWN_EXPIRY_TTL_MS = 5 * 60 * 1000L
        private const val MAX_PROBED_AUDIO_STREAMS = 4
        private const val PROBE_TIMEOUT_MS = 1_500L
        private const val NEWPIPE_CLIENT_PROFILE = "NEWPIPE"
        private const val ANONYMOUS_AUTH_SCOPE = "anonymous"
    }
}

internal const val YOUTUBE_ORIGIN = "https://www.youtube.com"

private class OkHttpNewPipeDownloader(
    private val http: OkHttpClient,
) : Downloader() {
    override fun execute(request: Request): Response {
        val headers = request.headers()
        val requestBuilder = okhttp3.Request.Builder()
            .url(request.url())
            .method(request.httpMethod(), request.dataToSend()?.toRequestBody())

        headers.forEach { (name, values) ->
            values.forEach { value -> requestBuilder.addHeader(name, value) }
        }
        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            requestBuilder.header("User-Agent", YOUTUBE_WEB_USER_AGENT)
        }
        if (headers.keys.none { it.equals("Accept-Language", ignoreCase = true) }) {
            requestBuilder.header("Accept-Language", "en-US,en;q=0.9")
        }
        val origin = if (request.url().contains("music.youtube.com", ignoreCase = true)) {
            "https://music.youtube.com"
        } else {
            YOUTUBE_ORIGIN
        }
        if (headers.keys.none { it.equals("Origin", ignoreCase = true) }) {
            requestBuilder.header("Origin", origin)
        }
        if (headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
            requestBuilder.header("Referer", "$origin/")
        }

        return http.newCall(requestBuilder.build()).execute().use { response ->
            Response(
                response.code,
                response.message,
                response.headers.names().associateWith(response.headers::values),
                response.body?.string().orEmpty(),
                response.request.url.toString(),
            )
        }
    }
}

internal const val YOUTUBE_WEB_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
