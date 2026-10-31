package com.example.data.resolver

import android.util.Log
import com.example.data.model.DiscoveredMediaCandidate
import com.example.data.model.MediaMetadata
import com.example.data.model.ResolutionResult
import com.example.data.model.SourceProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

class MediaSourceResolver(
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    companion object {
        private const val TAG = "MediaSourceResolver"
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    }

    suspend fun resolve(
        url: String,
        ytDlpFallback: (suspend (String) -> Result<MediaMetadata>)? = null
    ): ResolutionResult = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        if (trimmed.isBlank()) {
            return@withContext ResolutionResult.Failure("URL cannot be empty")
        }
        val cleanUrl = if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            "https://$trimmed"
        } else {
            trimmed
        }

        // Case A: Direct media link (e.g. .mp4, .m3u8, .mp3, etc.)
        if (isDirectStreamUrl(cleanUrl)) {
            val candidate = probeDirectMediaUrl(cleanUrl)
            if (candidate != null) {
                val rawFilename = cleanUrl.substringBefore("?").substringBefore("#").substringAfterLast("/")
                val decodedName = try {
                    java.net.URLDecoder.decode(rawFilename, "UTF-8").replace("_", " ").substringBeforeLast(".").trim()
                } catch (_: Exception) {
                    rawFilename
                }
                val meta = MediaMetadata(
                    title = decodedName.ifBlank { "Direct Media Stream" },
                    uploader = "Direct Stream (${candidate.protocol.displayName})",
                    durationSeconds = 0,
                    thumbnailUrl = null,
                    webpageUrl = cleanUrl,
                    candidates = listOf(candidate),
                    selectedCandidateId = candidate.id,
                    sourceProtocol = candidate.protocol
                )
                return@withContext ResolutionResult.Success(listOf(candidate), meta)
            }
        }

        // Case B: Check if URL belongs to a major video hosting platform (YouTube, Vimeo, etc.)
        val isMajorPlatform = isKnownVideoPlatform(cleanUrl)
        var ytMetadata: MediaMetadata? = null

        if (isMajorPlatform && ytDlpFallback != null) {
            val ytResult = ytDlpFallback(cleanUrl)
            if (ytResult.isSuccess) {
                val meta = ytResult.getOrNull()
                if (meta != null && meta.candidates.isNotEmpty()) {
                    Log.i(TAG, "Tier 1 (yt-dlp) extracted ${meta.candidates.size} candidate streams.")
                    return@withContext ResolutionResult.Success(meta.candidates, meta)
                } else if (meta != null) {
                    ytMetadata = meta
                }
            }
        }

        // Case C: Tier-2 Static HTML / PlayerJS inspection (Instant, no Python subprocesses)
        Log.i(TAG, "Probing Tier 2 (Static HTML / PlayerJS) for $cleanUrl")
        try {
            val htmlResult = fetchHtml(cleanUrl)
            if (htmlResult != null) {
                val (html, pageTitle, ogThumb) = htmlResult

                // Check for obvious DRM signs in HTML or scripts
                if (html.contains("widevine", ignoreCase = true) &&
                    (html.contains("com.widevine.alpha") || html.contains("drm/license"))
                ) {
                    return@withContext ResolutionResult.Unsupported(
                        "This stream is protected by DRM encryption (Widevine) and cannot be downloaded.",
                        isDrmProtected = true
                    )
                }

                val html5Candidates = Html5MediaExtractor.extractFromHtml(html, cleanUrl)
                val playerJsCandidates = PlayerJsExtractor.extractFromHtmlOrScript(html, cleanUrl)

                val allDiscovered = (html5Candidates + playerJsCandidates).distinctBy { it.url }

                if (allDiscovered.isNotEmpty()) {
                    // Probe and validate discovered URLs with Referer header
                    val validatedCandidates = allDiscovered.map { candidate ->
                        probeCandidate(candidate)
                    }.filterNotNull()

                    if (validatedCandidates.isNotEmpty()) {
                        val baseTitle = ytMetadata?.title ?: pageTitle.ifBlank { "Discovered Media" }
                        val metadata = MediaMetadata(
                            title = baseTitle,
                            uploader = ytMetadata?.uploader ?: "Webpage Media",
                            durationSeconds = ytMetadata?.durationSeconds ?: 0,
                            thumbnailUrl = ytMetadata?.thumbnailUrl ?: ogThumb,
                            webpageUrl = cleanUrl,
                            candidates = validatedCandidates,
                            selectedCandidateId = validatedCandidates.firstOrNull()?.id,
                            sourceProtocol = validatedCandidates.firstOrNull()?.protocol
                        )
                        return@withContext ResolutionResult.Success(validatedCandidates, metadata)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 2 extraction encountered an error: ${e.message}")
        }

        // Fallback: If not a major platform and HTML5 found nothing, try yt-dlp as secondary fallback
        if (!isMajorPlatform && ytDlpFallback != null) {
            Log.i(TAG, "Falling back to yt-dlp for $cleanUrl")
            val ytResult = ytDlpFallback(cleanUrl)
            if (ytResult.isSuccess) {
                val meta = ytResult.getOrNull()
                if (meta != null && meta.candidates.isNotEmpty()) {
                    return@withContext ResolutionResult.Success(meta.candidates, meta)
                } else if (meta != null) {
                    ytMetadata = meta
                }
            }
        }

        // If ytDlp returned basic metadata without candidate list, still allow generic download
        if (ytMetadata != null) {
            val defaultCandidate = DiscoveredMediaCandidate(
                id = "ytdlp_default_${UUID.randomUUID().toString().take(6)}",
                url = cleanUrl,
                parentPageUrl = cleanUrl,
                protocol = SourceProtocol.DIRECT_HTTP,
                label = "Standard Stream (yt-dlp)",
                requiredHeaders = mapOf("Referer" to cleanUrl)
            )
            val updated = ytMetadata.copy(
                candidates = listOf(defaultCandidate),
                selectedCandidateId = defaultCandidate.id,
                sourceProtocol = SourceProtocol.DIRECT_HTTP
            )
            return@withContext ResolutionResult.Success(listOf(defaultCandidate), updated)
        }

        ResolutionResult.Unsupported("No downloadable media streams found on this webpage.")
    }

    fun isProgressiveDownloadUrl(url: String): Boolean {
        val clean = url.substringBefore("?").substringBefore("#").lowercase()
        return clean.endsWith(".mp4") ||
                clean.endsWith(".m4v") ||
                clean.endsWith(".webm") ||
                clean.endsWith(".mkv") ||
                clean.endsWith(".mov") ||
                clean.endsWith(".mp3") ||
                clean.endsWith(".m4a") ||
                clean.endsWith(".aac") ||
                clean.endsWith(".wav") ||
                clean.endsWith(".opus") ||
                clean.endsWith(".ogg") ||
                clean.endsWith(".flac")
    }

    private fun isKnownVideoPlatform(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") ||
                lower.contains("youtu.be") ||
                lower.contains("vimeo.com") ||
                lower.contains("dailymotion.com") ||
                lower.contains("tiktok.com") ||
                lower.contains("twitter.com") ||
                lower.contains("x.com") ||
                lower.contains("facebook.com") ||
                lower.contains("fb.watch") ||
                lower.contains("instagram.com") ||
                lower.contains("twitch.tv") ||
                lower.contains("soundcloud.com") ||
                lower.contains("bilibili.com") ||
                lower.contains("reddit.com")
    }

    fun isDirectStreamUrl(url: String): Boolean {
        val clean = url.substringBefore("?").substringBefore("#").lowercase()
        return clean.endsWith(".mp4") ||
                clean.endsWith(".m4v") ||
                clean.endsWith(".webm") ||
                clean.endsWith(".mkv") ||
                clean.endsWith(".mov") ||
                clean.endsWith(".m3u8") ||
                clean.endsWith(".mpd") ||
                clean.endsWith(".mp3") ||
                clean.endsWith(".m4a") ||
                clean.endsWith(".aac") ||
                clean.endsWith(".wav") ||
                clean.endsWith(".opus")
    }

    private fun probeDirectMediaUrl(url: String): DiscoveredMediaCandidate? {
        val protocol = Html5MediaExtractor.detectProtocol(url)
        return try {
            val req = Request.Builder()
                .url(url)
                .head()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")
                .build()

            var response = okHttpClient.newCall(req).execute()
            if (response.code in listOf(400, 403, 405)) {
                response.close()
                val getReq = Request.Builder()
                    .url(url)
                    .get()
                    .header("User-Agent", USER_AGENT)
                    .header("Range", "bytes=0-1024")
                    .build()
                response = okHttpClient.newCall(getReq).execute()
            }

            response.use { resp ->
                val length = resp.header("Content-Length")?.toLongOrNull()
                    ?: resp.header("Content-Range")?.substringAfterLast("/")?.toLongOrNull()
                val contentType = resp.header("Content-Type")

                DiscoveredMediaCandidate(
                    id = "direct_${UUID.randomUUID().toString().take(6)}",
                    url = url,
                    parentPageUrl = url,
                    protocol = protocol,
                    mimeType = contentType,
                    label = when (protocol) {
                        SourceProtocol.HLS -> "HLS Master Playlist (.m3u8)"
                        SourceProtocol.DASH -> "DASH Manifest (.mpd)"
                        else -> "Direct Media Stream"
                    },
                    estimatedSizeBytes = length,
                    isAdaptive = (protocol == SourceProtocol.HLS || protocol == SourceProtocol.DASH)
                )
            }
        } catch (e: Exception) {
            // Still return candidate even if network probe fails
            DiscoveredMediaCandidate(
                id = "direct_${UUID.randomUUID().toString().take(6)}",
                url = url,
                parentPageUrl = url,
                protocol = protocol,
                label = when (protocol) {
                    SourceProtocol.HLS -> "HLS Master Playlist (.m3u8)"
                    SourceProtocol.DASH -> "DASH Manifest (.mpd)"
                    else -> "Direct Media Stream"
                },
                isAdaptive = (protocol == SourceProtocol.HLS || protocol == SourceProtocol.DASH)
            )
        }
    }

    private fun probeCandidate(candidate: DiscoveredMediaCandidate): DiscoveredMediaCandidate? {
        // Fast HEAD request with Referer, falling back to Range GET if HEAD is rejected
        try {
            val reqBuilder = Request.Builder()
                .url(candidate.url)
                .head()
                .header("User-Agent", USER_AGENT)
                .header("Accept", "*/*")

            candidate.requiredHeaders.forEach { (k, v) ->
                reqBuilder.header(k, v)
            }

            var response = okHttpClient.newCall(reqBuilder.build()).execute()
            if (response.code in listOf(400, 403, 405)) {
                response.close()
                val getReq = Request.Builder()
                    .url(candidate.url)
                    .get()
                    .header("User-Agent", USER_AGENT)
                    .header("Range", "bytes=0-1024")
                candidate.requiredHeaders.forEach { (k, v) ->
                    getReq.header(k, v)
                }
                response = okHttpClient.newCall(getReq.build()).execute()
            }

            response.use { resp ->
                if (resp.code in 200..399) {
                    val length = resp.header("Content-Length")?.toLongOrNull()
                        ?: resp.header("Content-Range")?.substringAfterLast("/")?.toLongOrNull()
                    val type = resp.header("Content-Type")
                    return candidate.copy(
                        estimatedSizeBytes = length ?: candidate.estimatedSizeBytes,
                        mimeType = type ?: candidate.mimeType
                    )
                }
            }
        } catch (_: Exception) {
            // If probe fails or is rejected, keep the candidate
        }
        return candidate
    }

    private fun fetchHtml(url: String): Triple<String, String, String?>? {
        val req = Request.Builder()
            .url(url)
            .get()
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .build()

        return try {
            okHttpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body ?: return null

                // Limit read to 2.5 MB to avoid memory pressure
                val bytes = body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    val output = java.io.ByteArrayOutputStream()
                    var total = 0
                    val maxBytes = 2_500_000
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        total += read
                        if (total >= maxBytes) break
                    }
                    output.toByteArray()
                }

                val html = String(bytes, Charsets.UTF_8)
                val doc = Jsoup.parse(html, url)
                val title = doc.title().trim()
                val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?: doc.selectFirst("meta[name=twitter:image]")?.attr("content")

                Triple(html, title, ogImage)
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to fetch webpage HTML: ${e.message}")
            null
        }
    }
}
