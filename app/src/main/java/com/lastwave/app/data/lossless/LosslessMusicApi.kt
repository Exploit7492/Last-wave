package com.lastwave.app.data.lossless

import android.util.Log
import com.lastwave.app.data.artwork.awaitSuccessfulBodyOrNull
import com.lastwave.app.data.plugin.ModuleManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class LosslessAudioStream(
    val url: String,
    val mimeType: String = "application/dash+xml",
    val bitDepth: Int = 16,
    val samplingRate: Double = 44.1,
    val formatId: Int = 6,
    val bitrateKbps: Int? = null,
    val trackId: Long = 0,
    val durationSeconds: Int = 0,
)

data class BackendCredentials(
    val baseUrl: String = "",
    val apiKey: String = "",
)

private data class TidalCandidateItem(
    val id: Long,
    val title: String,
    val duration: Int = 0,
    val performerName: String = "",
    val albumArtistName: String = "",
    val albumTitle: String = "",
    val performers: String = "",
    val isAtmos: Boolean = false,
)

@Singleton
class LosslessMusicApi @Inject constructor(
    okHttpClient: OkHttpClient,
    private val moduleManager: ModuleManager,
    private val nativeSecrets: NativeSecrets,
) {
    // Certificate pinning: blocks MITM proxies (Charles, mitmproxy, Fiddler)
    // from intercepting Tidal backend traffic. Pin the leaf + backup CA.
    private val certificatePinner = CertificatePinner.Builder()
        .add("tidal.kanjijewels.com", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=") // TODO: replace with actual pin
        .add("tidal.kanjijewels.com", "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB=") // backup pin
        .build()

    private val client = okHttpClient.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .certificatePinner(certificatePinner)
        .build()
    private val resolutionClient = client.newBuilder()
        .callTimeout(5, TimeUnit.SECONDS)
        .build()

    @Volatile
    private var cachedCredentials: BackendCredentials? = null

    companion object {
        // Quality presets
        const val QUALITY_DOLBY_ATMOS = 28 // Dolby Atmos Spatial Audio
        const val QUALITY_MAX_HI_RES = 27 // Up to 24-bit / 192 kHz
        const val QUALITY_HI_RES_96 = 7   // Up to 24-bit / 96 kHz
        const val QUALITY_CD_LOSSLESS = 6 // 16-bit / 44.1 kHz FLAC
        const val QUALITY_MP3_320 = 5     // 320 kbps MP3 / AAC High
        const val QUALITY_DATA_SAVER = 4  // 96 kbps HE-AAC Data Saver
        const val QUALITY_YOUTUBE = -1    // YouTube Music standard stream

        fun getQualityAttemptOrder(preferred: Int): List<Int> {
            if (preferred == QUALITY_YOUTUBE) return emptyList()
            val tiersAscending = listOf(
                QUALITY_DATA_SAVER,
                QUALITY_MP3_320,
                QUALITY_CD_LOSSLESS,
                QUALITY_HI_RES_96,
                QUALITY_MAX_HI_RES,
            )
            val index = tiersAscending.indexOf(preferred)
            if (index == -1) return listOf(QUALITY_MAX_HI_RES, QUALITY_HI_RES_96, QUALITY_CD_LOSSLESS, QUALITY_MP3_320, QUALITY_DATA_SAVER)

            val preferredQuality = tiersAscending[index]
            val above = tiersAscending.subList(index + 1, tiersAscending.size)
            val below = tiersAscending.subList(0, index).reversed()

            return (listOf(preferredQuality) + above + below).distinct()
        }

        private val MANIFEST_CODECS = Regex("""codecs="([^"]+)"""")
        private val SIX_CHANNELS = Regex("""value=["']6["']""")

        /**
         * True only when DASH manifest XML really carries E-AC-3 spatial
         * audio on a 6-channel declaration. The atmos endpoint answers
         * stereo-only tracks with a plain FLAC/AAC rendition, so claiming
         * Atmos from anything less would badge stereo as DOLBY ATMOS.
         * Fails closed (false) on anything unparseable.
         */
        fun isAtmosManifest(mpdXml: String): Boolean {
            if (mpdXml.isBlank()) return false
            val lower = mpdXml.lowercase()
            val spatial = lower.contains("ec-3") || lower.contains("eac3") || lower.contains("ec3")
            if (!spatial) return false
            return lower.contains("audiochannelconfiguration") && SIX_CHANNELS.containsMatchIn(lower)
        }

        /** First `codecs=` value inside a base64 DASH data URL, or null when unreadable. */
        fun manifestCodecOf(dataUrl: String): String? {
            val b64 = dataUrl.substringAfter("base64,", "").trim()
            if (b64.isEmpty()) return null
            return runCatching {
                val xml = String(
                    android.util.Base64.decode(b64, android.util.Base64.DEFAULT),
                    Charsets.UTF_8,
                ).lowercase()
                MANIFEST_CODECS.find(xml)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
            }.getOrNull()
        }

        /** True for E-AC-3 spatial codec labels. Pure; safe to unit-test on JVM. */
        fun isAtmosCodec(codec: String?): Boolean {
            val c = codec?.trim()?.lowercase().orEmpty()
            return c.startsWith("ec-3") || c.startsWith("eac3") || c.startsWith("ac-3")
        }

        /** True when the stream bytes are E-AC-3 spatial. Fail-open (false) when unreadable. */
        fun isAtmosStreamUrl(url: String): Boolean {
            if (!url.startsWith("data:application/dash+xml")) return false
            return isAtmosCodec(manifestCodecOf(url))
        }

        private const val TAG = "LosslessMusicApi"
        private const val MAX_DURATION_DIFFERENCE_SECONDS = 8
        private val DIACRITICS = Regex("\\p{M}+")
        private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
        private val MULTI_SPACE = Regex("\\s+")
        private val TOPIC_CHANNEL_SUFFIX = Regex("""(?i)\s*[-–—]\s*topic\s*$|\s+topic\s*$""")
        private val PIPE_NOISE = Regex("""\s*\|.*$""")
        private val SOUNDTRACK_SUFFIX = Regex(
            """(?i)\s*[\[(]\s*from\s+(?:the\s+(?:original\s+)?(?:motion\s+picture|movie|film|soundtrack)\s+)?["“][^"”\r\n]+["”]\s*[\])]\s*$""",
        )
        private val FEATURING_CLAUSE = Regex("""(?i)(?:\s*[\[(])?\s*(feat\.?|ft\.?|featuring)\s+.*$""")
        private val BRACKETED_DISPLAY_NOISE = Regex(
            """(?i)[\[(]\s*(?:explicit|clean|(?:official\s+)?(?:music\s+)?(?:audio|video|lyrics?|lyric\s+video|visualizer|hd|4k|mv|full\s+song|full\s+audio|prod\.?\s*(?:by\s*)?[^\])]+))\s*[\])]""",
        )
        private val TRAILING_DISPLAY_NOISE = Regex("""(?i)\s*[-–—]\s*(?:official\s+)?(?:music\s+)?(?:audio|video|lyrics?|visualizer|mv|full\s+song)\s*$""")
        private val ARTIST_NOISE_WORDS = setOf("the", "and", "feat", "ft", "featuring", "with", "x", "topic")
        private val PERFORMING_ROLE_WORDS = setOf(
            "mainartist", "featuredartist", "performer", "vocal", "vocals", "vocalist", "singer",
        )
        private val IDENTITY_VARIANT_PATTERNS = listOf(
            "live" to Regex("\\blive\\b"),
            "acoustic" to Regex("\\bacoustic\\b"),
            "karaoke" to Regex("\\bkaraoke\\b"),
            "instrumental" to Regex("\\binstrumental\\b"),
            "tribute" to Regex("\\btribute\\b"),
            "cover" to Regex("\\bcover\\b"),
            "remix" to Regex("\\bremix(?:ed)?\\b"),
            "mashup" to Regex("""\bmash[ -]?up\b|\b[a-z0-9]+\s+x\s+[a-z0-9]+\b"""),
            "demo" to Regex("\\bdemo\\b"),
            "slowed" to Regex("\\bslowed\\b"),
            "reverb" to Regex("\\breverb\\b"),
            "sped-up" to Regex("\\bsped up\\b"),
            "nightcore" to Regex("\\bnightcore\\b"),
            "radio-edit" to Regex("\\bradio edit\\b"),
            "extended" to Regex("\\bextended(?: version| mix)?\\b"),
        )
    }

    fun invalidateCredentialsCache() {
        cachedCredentials = null
    }

    suspend fun getCredentials(): BackendCredentials? = withContext(Dispatchers.IO) {
        cachedCredentials?.let { return@withContext it }

        // 1. Primary: Native secrets (ARM code + signature verification)
        val nativeCreds = runCatching { nativeSecrets.credentials() }.getOrNull()
        if (nativeCreds != null && nativeCreds.baseUrl.isNotBlank() && nativeCreds.apiKey.isNotBlank()) {
            cachedCredentials = nativeCreds
            return@withContext nativeCreds
        }

        // 2. Fallback: .lwp module config (baseUrl only, API key from native)
        val handles = runCatching { moduleManager.enabledHandles() }.getOrNull() ?: emptyList()
        for (handle in handles) {
            val json = moduleManager.readDecryptedConfig(handle)
            if (json != null) {
                val url = json.optString("baseUrl").ifBlank { json.optJSONObject("tidal")?.optString("baseUrl").orEmpty() }
                if (url.isNotBlank()) {
                    // API key always from native — never from .lwp
                    val nativeKey = runCatching { nativeSecrets.apiKey() }.getOrDefault("")
                    val creds = BackendCredentials(baseUrl = url.trimEnd('/'), apiKey = nativeKey)
                    cachedCredentials = creds
                    return@withContext creds
                }
            }
        }
        null
    }

    suspend fun resolveStream(
        title: String,
        artist: String,
        expectedDurationSeconds: Int? = null,
        expectedAlbum: String? = null,
        preferredQuality: Int = QUALITY_MAX_HI_RES,
        excludedUrls: Set<String> = emptySet(),
    ): LosslessAudioStream? = withContext(Dispatchers.IO) {
        if (preferredQuality == QUALITY_YOUTUBE || title.isBlank() || artist.isBlank()) return@withContext null

        val creds = getCredentials() ?: return@withContext null
        if (creds.baseUrl.isBlank()) return@withContext null

        try {
            // 1. Search Tidal via backend
            val candidate = findBestVerifiedMatch(
                title = title,
                artist = artist,
                expectedDurationSeconds = expectedDurationSeconds,
                expectedAlbum = expectedAlbum,
                creds = creds,
                preferredQuality = preferredQuality,
            ) ?: return@withContext null

            // 2. Fetch Tidal direct streaming manifest
            val directStream = fetchTrackStreamUrl(candidate, preferredQuality, creds = creds)
            if (directStream != null && directStream.url !in excludedUrls &&
                (preferredQuality == QUALITY_DOLBY_ATMOS || !isAtmosStreamUrl(directStream.url))
            ) {
                return@withContext directStream
            }

            val qualitiesToTry = getQualityAttemptOrder(preferredQuality).filter { it != preferredQuality }
            for (quality in qualitiesToTry) {
                currentCoroutineContext().ensureActive()
                val stream = fetchTrackStreamUrl(candidate, quality, creds = creds)
                if (stream == null || stream.url in excludedUrls) continue
                // Never leak an Atmos (E-AC-3) mix into a stereo request:
                // devices without an EC-3 decoder fail on it outright.
                if (preferredQuality != QUALITY_DOLBY_ATMOS && isAtmosStreamUrl(stream.url)) continue
                return@withContext stream
            }
            null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Lossless Tidal resolution failed: ${e.message}")
            null
        }
    }

    private suspend fun findBestVerifiedMatch(
        title: String,
        artist: String,
        expectedDurationSeconds: Int?,
        expectedAlbum: String?,
        creds: BackendCredentials,
        preferredQuality: Int,
    ): TidalCandidateItem? {
        val cleanArtist = cleanForSearch(artist).ifBlank { artist }
        val cleanTitle = cleanForSearch(title).ifBlank { title }
        val queries = listOf(
            "$cleanTitle $cleanArtist".trim(),
            cleanTitle.trim(),
            title.trim().takeIf { it.isNotBlank() },
        ).distinct().filterNotNull()

        for (query in queries) {
            currentCoroutineContext().ensureActive()
            val url = "${creds.baseUrl}/search/?s=" + URLEncoder.encode(query, "UTF-8")
            val reqBuilder = Request.Builder().url(url).get()
            if (creds.apiKey.isNotBlank()) {
                reqBuilder.addHeader("X-API-Key", creds.apiKey)
            }

            val body = resolutionClient.newCall(reqBuilder.build()).awaitSuccessfulBodyOrNull() ?: continue
            val items = parseTidalSearchItems(body)
            if (items.isEmpty()) continue

            items.asSequence()
                .mapNotNull { item ->
                    verifiedMatchScore(
                        item = item,
                        title = title,
                        artist = artist,
                        expectedDurationSeconds = expectedDurationSeconds,
                        expectedAlbum = expectedAlbum,
                    )?.let { score ->
                        var finalScore = score
                        if (preferredQuality == QUALITY_DOLBY_ATMOS && item.isAtmos) finalScore += 200
                        item to finalScore
                    }
                }
                .sortedWith(
                    compareByDescending<Pair<TidalCandidateItem, Int>> { it.second },
                )
                .firstOrNull()
                ?.first
                ?.let { return it }
        }

        return null
    }

    private fun parseTidalSearchItems(body: String): List<TidalCandidateItem> {
        return runCatching {
            val json = JSONObject(body)
            val dataObj = json.optJSONObject("data") ?: return emptyList()
            val items = dataObj.optJSONArray("items") ?: return emptyList()
            val result = mutableListOf<TidalCandidateItem>()

            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val id = item.optLong("id")
                val itemTitle = item.optString("title")
                if (id <= 0 || itemTitle.isBlank()) continue

                val duration = item.optInt("duration", 0)
                val artistsArray = item.optJSONArray("artists")
                val performer = artistsArray?.optJSONObject(0)?.optString("name")
                    ?: item.optJSONObject("artist")?.optString("name").orEmpty()
                val performers = (0 until (artistsArray?.length() ?: 0))
                    .mapNotNull { artistsArray?.optJSONObject(it)?.optString("name") }
                    .joinToString(", ")
                val albumTitle = item.optJSONObject("album")?.optString("title").orEmpty()
                val audioModes = item.optJSONArray("audioModes")
                val isAtmos = (0 until (audioModes?.length() ?: 0)).any { audioModes?.optString(it) == "DOLBY_ATMOS" }

                result.add(
                    TidalCandidateItem(
                        id = id,
                        title = itemTitle,
                        duration = duration,
                        performerName = performer,
                        albumArtistName = performer,
                        albumTitle = albumTitle,
                        performers = performers,
                        isAtmos = isAtmos,
                    ),
                )
            }
            result
        }.getOrDefault(emptyList())
    }

    private suspend fun fetchTrackStreamUrl(
        candidate: TidalCandidateItem,
        quality: Int,
        creds: BackendCredentials,
    ): LosslessAudioStream? {
        if (quality == QUALITY_DOLBY_ATMOS && candidate.isAtmos) {
            val atmosStream = fetchTidalAtmosStream(candidate, creds)
            if (atmosStream != null) return atmosStream
        }

        val qualityParam = when (quality) {
            QUALITY_MAX_HI_RES, QUALITY_HI_RES_96 -> "HI_RES_LOSSLESS"
            QUALITY_CD_LOSSLESS -> "LOSSLESS"
            QUALITY_MP3_320 -> "HIGH"
            QUALITY_DATA_SAVER -> "LOW"
            else -> "LOSSLESS"
        }

        val url = "${creds.baseUrl}/track/?id=${candidate.id}&quality=$qualityParam"
        val reqBuilder = Request.Builder().url(url).get()
        if (creds.apiKey.isNotBlank()) reqBuilder.addHeader("X-API-Key", creds.apiKey)

        return try {
            val body = resolutionClient.newCall(reqBuilder.build()).awaitSuccessfulBodyOrNull() ?: return null
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: return null
            val manifest = data.optString("manifest")
            if (manifest.isBlank()) return null

            val bitDepth = data.optInt("bitDepth", 16)
            val sampleRate = data.optDouble("sampleRate", 44100.0)
            val audioQuality = data.optString("audioQuality", "LOSSLESS")
            val formatId = when (audioQuality.uppercase()) {
                "HI_RES_LOSSLESS", "HI_RES" -> QUALITY_MAX_HI_RES
                "LOSSLESS" -> QUALITY_CD_LOSSLESS
                "HIGH" -> QUALITY_MP3_320
                "LOW" -> QUALITY_DATA_SAVER
                else -> quality
            }
            val samplingRateKHz = if (sampleRate > 1000) sampleRate / 1000.0 else sampleRate
            val bitrateKbps = if (formatId == QUALITY_MP3_320) 320 else if (formatId == QUALITY_DATA_SAVER) 96
            else ((bitDepth * samplingRateKHz * 2 * 1000) / 1000).toInt()

            LosslessAudioStream(
                url = "data:application/dash+xml;base64,$manifest",
                mimeType = "application/dash+xml",
                bitDepth = bitDepth,
                samplingRate = samplingRateKHz,
                formatId = formatId,
                bitrateKbps = bitrateKbps,
                trackId = candidate.id,
                durationSeconds = candidate.duration,
            )
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchTidalAtmosStream(
        candidate: TidalCandidateItem,
        creds: BackendCredentials,
    ): LosslessAudioStream? {
        val url = "${creds.baseUrl}/trackManifests/?id=${candidate.id}&atmos=true"
        val reqBuilder = Request.Builder().url(url).get()
        if (creds.apiKey.isNotBlank()) reqBuilder.addHeader("X-API-Key", creds.apiKey)

        return try {
            val body = resolutionClient.newCall(reqBuilder.build()).awaitSuccessfulBodyOrNull() ?: return null
            val json = JSONObject(body)
            val data = json.optJSONObject("data")
            val attr = data?.optJSONObject("data")?.optJSONObject("data")?.optJSONObject("attributes")
            val mpdUri = attr?.optString("uri")
            if (mpdUri.isNullOrBlank()) return null

            // Fetch MPD XML directly
            val mpdReq = Request.Builder().url(mpdUri).get().build()
            val mpdXml = resolutionClient.newCall(mpdReq).awaitSuccessfulBodyOrNull() ?: return null
            // Ground truth: the endpoint answers stereo-only tracks with a
            // plain rendition (atmos_available=false). Claiming those as
            // Atmos would badge stereo as DOLBY ATMOS, so fail to null and
            // let the caller fall back to honest stereo tiers instead.
            if (!isAtmosManifest(mpdXml)) {
                Log.d(TAG, "Atmos manifest lacks E-AC-3/6ch for track ${candidate.id}; falling back")
                return null
            }
            val b64 = android.util.Base64.encodeToString(mpdXml.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)

            LosslessAudioStream(
                url = "data:application/dash+xml;base64,$b64",
                mimeType = "application/dash+xml",
                bitDepth = 24,
                samplingRate = 48.0,
                formatId = QUALITY_DOLBY_ATMOS,
                bitrateKbps = 768,
                trackId = candidate.id,
                durationSeconds = candidate.duration,
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun verifiedMatchScore(
        item: TidalCandidateItem,
        title: String,
        artist: String,
        expectedDurationSeconds: Int?,
        expectedAlbum: String?,
    ): Int? {
        val matchArtist = cleanForSearch(artist).ifBlank { artist }
        val targetTitle = normalizeTitle(title, matchArtist)
        val candidateTitle = normalizeTitle(item.title, matchArtist)
        if (targetTitle.isBlank()) return null

        val primaryIdentities = listOf(item.performerName, item.albumArtistName)
            .map(::normalizeText)
            .filter(String::isNotBlank)

        val targetArtists = matchArtist.split(Regex("""(?i)\s*(?:&|,|\bx\b|feat\.?|ft\.?|featuring|with|\+)\s*"""))
            .map(::normalizeText)
            .filter(String::isNotBlank)

        val artistExact = primaryIdentities.any { iden ->
            targetArtists.any { ta -> iden == ta }
        }

        val titleDistance = levenshtein(targetTitle, candidateTitle)
        val isExactMatch = targetTitle == candidateTitle
        val maxFuzz = (targetTitle.length / 5).coerceIn(1, 2)
        val isFuzzyMatch = artistExact && titleDistance <= maxFuzz
        val isDescriptorMatch = artistExact && targetTitle.length >= 4 && candidateTitle.length >= 4 && (
            (candidateTitle.startsWith(targetTitle) && listOf("rap", "song", "theme", "track", "audio", "music").contains(candidateTitle.substring(targetTitle.length).trim())) ||
            (targetTitle.startsWith(candidateTitle) && listOf("rap", "song", "theme", "track", "audio", "music").contains(targetTitle.substring(candidateTitle.length).trim()))
        )

        if (!isExactMatch && !isFuzzyMatch && !isDescriptorMatch) return null

        val targetVariants = identityVariants(title, matchArtist)
        val candidateVariants = identityVariants(item.title, matchArtist)
        if (targetVariants != candidateVariants) return null

        if (!isVerifiedArtistMatch(matchArtist, item.performerName, item.albumArtistName, item.performers)) return null

        val durationDifference = if (expectedDurationSeconds != null && expectedDurationSeconds > 0) {
            if (item.duration <= 0) return null
            kotlin.math.abs(item.duration - expectedDurationSeconds).also { if (it > MAX_DURATION_DIFFERENCE_SECONDS) return null }
        } else null

        var score = 1_000 - titleDistance * 50
        if (artistExact) score += 300
        expectedAlbum?.takeIf(String::isNotBlank)?.let { album ->
            if (normalizeTitle(album, "") == normalizeTitle(item.albumTitle, "")) score += 120
        }
        durationDifference?.let { score += (MAX_DURATION_DIFFERENCE_SECONDS - it) * 10 }
        return score
    }

    private fun cleanForSearch(raw: String): String {
        return raw
            .replace(TOPIC_CHANNEL_SUFFIX, "")
            .replace(PIPE_NOISE, "")
            .replace(SOUNDTRACK_SUFFIX, "")
            .replace(FEATURING_CLAUSE, " ")
            .replace(BRACKETED_DISPLAY_NOISE, " ")
            .replace(TRAILING_DISPLAY_NOISE, " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    private fun normalizeTitle(raw: String, artist: String): String {
        var cleaned = cleanForSearch(raw)
        if (artist.isNotBlank()) {
            val cleanArt = cleanForSearch(artist).ifBlank { artist }
            cleaned = cleaned.replaceFirst(
                Regex("""^\s*${Regex.escape(cleanArt)}\s*[-–—:]\s*""", RegexOption.IGNORE_CASE),
                "",
            )
            cleaned = cleaned.replace(
                Regex("""(?i)\s*[-–—:]\s*${Regex.escape(cleanArt)}\s*$"""),
                "",
            )
        }
        return normalizeText(cleaned)
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private fun normalizeText(raw: String): String = Normalizer.normalize(raw, Normalizer.Form.NFD)
        .replace(DIACRITICS, "")
        .lowercase(Locale.ROOT)
        .replace(NON_ALPHANUMERIC, " ")
        .replace(MULTI_SPACE, " ")
        .trim()

    private fun identityVariants(raw: String, artist: String): Set<String> {
        val withoutArtistPrefix = if (artist.isBlank()) raw else raw.replaceFirst(
            Regex("""^\s*${Regex.escape(artist)}\s*[-–—:]\s*""", RegexOption.IGNORE_CASE),
            "",
        )
        val normalized = normalizeText(withoutArtistPrefix)
        return IDENTITY_VARIANT_PATTERNS.mapNotNullTo(linkedSetOf()) { (name, pattern) ->
            name.takeIf { pattern.containsMatchIn(normalized) }
        }
    }

    private fun isVerifiedArtistMatch(
        targetArtist: String,
        performer: String,
        albumArtist: String,
        performersText: String?,
    ): Boolean {
        val target = normalizeText(targetArtist)
        if (target.isBlank()) return false
        val primaryIdentities = listOf(performer, albumArtist)
            .map(::normalizeText)
            .filter(String::isNotBlank)
        if (primaryIdentities.any { it == target }) return true

        val targetArtists = targetArtist.split(Regex("""(?i)\s*(?:&|,|\bx\b|feat\.?|ft\.?|featuring|with|\+)\s*"""))
            .map(::normalizeText)
            .filter(String::isNotBlank)

        if (primaryIdentities.any { iden -> targetArtists.any { ta -> iden == ta } }) return true

        for (ta in targetArtists) {
            val taTokens = ta.split(' ').filter { it !in ARTIST_NOISE_WORDS }.toSet()
            if (taTokens.isNotEmpty() && primaryIdentities.any { iden -> taTokens.all(iden.split(' ').toSet()::contains) }) {
                return true
            }
        }

        val targetTokens = target.split(' ').filter { it !in ARTIST_NOISE_WORDS }.toSet()
        if (targetTokens.isEmpty()) return false
        if (primaryIdentities.any { identity -> targetTokens.all(identity.split(' ').toSet()::contains) }) return true

        val performingCredits = performersText.orEmpty()
            .split(Regex("""\s+-\s+"""))
            .map(::normalizeText)
            .filter { credit -> PERFORMING_ROLE_WORDS.any { role -> role in credit.split(' ') } }
        val performingTokens = (primaryIdentities + performingCredits)
            .flatMap { it.split(' ') }
            .toSet()
        return targetTokens.all(performingTokens::contains)
    }
}
