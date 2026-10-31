package com.example.data.resolver

import android.util.Log
import com.example.data.model.DiscoveredMediaCandidate
import com.example.data.model.SourceProtocol
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.util.UUID

object Html5MediaExtractor {
    private const val TAG = "Html5MediaExtractor"

    fun extractFromHtml(html: String, baseUrl: String): List<DiscoveredMediaCandidate> {
        val candidates = mutableListOf<DiscoveredMediaCandidate>()
        try {
            val doc: Document = Jsoup.parse(html, baseUrl)

            // 1. Direct <video> tags
            val videoTags = doc.select("video")
            for ((index, video) in videoTags.withIndex()) {
                val poster = video.attr("abs:poster").ifBlank { video.attr("poster") }.takeIf { it.isNotBlank() }
                val videoTitle = findVideoTitle(video, index)
                val src = video.attr("abs:src").ifBlank { video.attr("src") }
                val resolvedSrc = resolveUrl(src, baseUrl)

                val sourceTags = video.select("source")
                if (sourceTags.isEmpty() && isValidMediaUrl(resolvedSrc)) {
                    val protocol = detectProtocol(resolvedSrc)
                    candidates.add(
                        DiscoveredMediaCandidate(
                            id = "html5_video_${index}_${UUID.randomUUID().toString().take(6)}",
                            url = resolvedSrc,
                            parentPageUrl = baseUrl,
                            protocol = protocol,
                            mimeType = video.attr("type").ifBlank { inferMimeType(resolvedSrc) },
                            label = videoTitle,
                            resolution = getResolutionFromTag(video),
                            thumbnailUrl = poster,
                            requiredHeaders = mapOf("Referer" to baseUrl)
                        )
                    )
                }

                // 2. <video><source src="..."/></video>
                for ((srcIdx, source) in sourceTags.withIndex()) {
                    val sourceSrc = source.attr("abs:src").ifBlank { source.attr("src") }
                    val resolvedSourceSrc = resolveUrl(sourceSrc, baseUrl)
                    if (isValidMediaUrl(resolvedSourceSrc)) {
                        val protocol = detectProtocol(resolvedSourceSrc)
                        val type = source.attr("type").ifBlank { inferMimeType(resolvedSourceSrc) }
                        val sourceSpecificLabel = source.attr("title").ifBlank {
                            source.attr("label").ifBlank {
                                source.attr("size").ifBlank { "" }
                            }
                        }
                        val candidateLabel = if (sourceSpecificLabel.isNotBlank()) {
                            "$videoTitle ($sourceSpecificLabel)"
                        } else if (sourceTags.size > 1) {
                            "$videoTitle (${srcIdx + 1})"
                        } else {
                            videoTitle
                        }
                        candidates.add(
                            DiscoveredMediaCandidate(
                                id = "html5_source_${index}_${srcIdx}_${UUID.randomUUID().toString().take(6)}",
                                url = resolvedSourceSrc,
                                parentPageUrl = baseUrl,
                                protocol = protocol,
                                mimeType = type,
                                label = candidateLabel,
                                resolution = source.attr("res").ifBlank { source.attr("size") }.ifBlank { null },
                                thumbnailUrl = poster,
                                requiredHeaders = mapOf("Referer" to baseUrl)
                            )
                        )
                    }
                }
            }

            // 3. Standalone <source> tags with video or audio mime/ext
            val standaloneSources = doc.select("source[src]")
            for (source in standaloneSources) {
                val src = source.attr("abs:src").ifBlank { source.attr("src") }
                val resolved = resolveUrl(src, baseUrl)
                if (isValidMediaUrl(resolved) && candidates.none { it.url == resolved }) {
                    candidates.add(
                        DiscoveredMediaCandidate(
                            id = "source_${UUID.randomUUID().toString().take(6)}",
                            url = resolved,
                            parentPageUrl = baseUrl,
                            protocol = detectProtocol(resolved),
                            mimeType = source.attr("type").ifBlank { inferMimeType(resolved) },
                            label = "Discovered Media Stream",
                            requiredHeaders = mapOf("Referer" to baseUrl)
                        )
                    )
                }
            }

            // 4. Data attributes often used in lazy-loaded players: data-src, data-video-src, data-mp4
            val elementsWithDataMedia = doc.select("[data-src], [data-video], [data-mp4], [data-stream]")
            for (el in elementsWithDataMedia) {
                val candidateAttrs = listOf("data-src", "data-video", "data-mp4", "data-stream")
                for (attr in candidateAttrs) {
                    val rawVal = el.attr(attr).trim()
                    if (rawVal.isNotBlank()) {
                        val resolved = resolveUrl(rawVal, baseUrl)
                        if (isValidMediaUrl(resolved) && candidates.none { it.url == resolved }) {
                            candidates.add(
                                DiscoveredMediaCandidate(
                                    id = "data_attr_${UUID.randomUUID().toString().take(6)}",
                                    url = resolved,
                                    parentPageUrl = baseUrl,
                                    protocol = detectProtocol(resolved),
                                    mimeType = inferMimeType(resolved),
                                    label = "Lazy Embedded Media",
                                    requiredHeaders = mapOf("Referer" to baseUrl)
                                )
                            )
                        }
                    }
                }
            }

            // 5. OpenGraph or Twitter video meta tags
            val ogVideo = doc.selectFirst("meta[property=og:video], meta[property=og:video:url], meta[property=og:video:secure_url]")?.attr("content")
            if (!ogVideo.isNullOrBlank()) {
                val resolved = resolveUrl(ogVideo, baseUrl)
                if (isValidMediaUrl(resolved) && candidates.none { it.url == resolved }) {
                    candidates.add(
                        DiscoveredMediaCandidate(
                            id = "og_video_${UUID.randomUUID().toString().take(6)}",
                            url = resolved,
                            parentPageUrl = baseUrl,
                            protocol = detectProtocol(resolved),
                            mimeType = inferMimeType(resolved),
                            label = "OpenGraph Video Feed",
                            requiredHeaders = mapOf("Referer" to baseUrl)
                        )
                    )
                }
            }
                // 6. Embedded iframe sources
                val iframes = doc.select("iframe[src], embed[src]")
                for (iframe in iframes) {
                    val src = iframe.attr("abs:src").ifBlank { iframe.attr("src") }
                    val resolved = resolveUrl(src, baseUrl)
                    if (isValidMediaUrl(resolved) && candidates.none { it.url == resolved }) {
                        candidates.add(
                            DiscoveredMediaCandidate(
                                id = "iframe_${UUID.randomUUID().toString().take(6)}",
                                url = resolved,
                                parentPageUrl = baseUrl,
                                protocol = detectProtocol(resolved),
                                mimeType = inferMimeType(resolved),
                                label = iframe.attr("title").ifBlank { "Embedded Frame Video" },
                                requiredHeaders = mapOf("Referer" to baseUrl)
                            )
                        )
                    }
                }

                // 7. Dynamic JavaScript media URL references in <script> tags
                val scriptMediaRegex = Regex(
                    """https?://[^\s"'<>\\]+?\.(?:mp4|m3u8|mpd|webm|mkv|mp3|m4a)(?:\?[^\s"'<>\\]*)?""",
                    RegexOption.IGNORE_CASE
                )
                for (script in doc.select("script")) {
                    val scriptBody = script.data()
                    val matches = scriptMediaRegex.findAll(scriptBody)
                    for (match in matches) {
                        val streamUrl = match.value
                        if (isValidMediaUrl(streamUrl) && candidates.none { it.url == streamUrl }) {
                            val ext = streamUrl.substringBefore("?").substringAfterLast(".").lowercase()
                            val protocol = detectProtocol(streamUrl)
                            candidates.add(
                                DiscoveredMediaCandidate(
                                    id = "js_stream_${UUID.randomUUID().toString().take(6)}",
                                    url = streamUrl,
                                    parentPageUrl = baseUrl,
                                    protocol = protocol,
                                    mimeType = inferMimeType(streamUrl),
                                    label = "Dynamic JS Stream (${ext.uppercase()})",
                                    requiredHeaders = mapOf("Referer" to baseUrl)
                                )
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error parsing HTML for media sources", e)
            }

        return candidates
    }

    fun resolveUrl(url: String, baseUrl: String): String {
        if (url.isBlank()) return ""
        val trimmed = url.trim()
        return try {
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else if (trimmed.startsWith("//")) {
                val scheme = if (baseUrl.startsWith("https://")) "https:" else "http:"
                scheme + trimmed
            } else {
                val base = URI(baseUrl)
                base.resolve(trimmed).toString()
            }
        } catch (e: Exception) {
            trimmed
        }
    }

    private fun isValidMediaUrl(url: String): Boolean {
        if (url.isBlank()) return false
        val lower = url.lowercase()
        if (lower.startsWith("blob:") || lower.startsWith("data:") || lower.startsWith("javascript:")) {
            return false
        }
        val validExtensions = listOf(
            ".mp4", ".m4v", ".webm", ".mkv", ".m3u8", ".mpd", ".mov", ".flv",
            ".mp3", ".m4a", ".aac", ".ogg", ".opus"
        )
        return validExtensions.any { ext ->
            val path = lower.substringBefore("?").substringBefore("#")
            path.endsWith(ext)
        }
    }

    fun detectProtocol(url: String): SourceProtocol {
        val clean = url.substringBefore("?").substringBefore("#").lowercase()
        return when {
            clean.endsWith(".m3u8") -> SourceProtocol.HLS
            clean.endsWith(".mpd")  -> SourceProtocol.DASH
            else                    -> SourceProtocol.DIRECT_HTTP
        }
    }

    private fun inferMimeType(url: String): String {
        val clean = url.substringBefore("?").substringBefore("#").lowercase()
        return when {
            clean.endsWith(".mp4") -> "video/mp4"
            clean.endsWith(".webm") -> "video/webm"
            clean.endsWith(".m3u8") -> "application/x-mpegURL"
            clean.endsWith(".mpd")  -> "application/dash+xml"
            clean.endsWith(".mp3")  -> "audio/mpeg"
            clean.endsWith(".m4a")  -> "audio/mp4"
            clean.endsWith(".opus") -> "audio/opus"
            else -> "video/mp4"
        }
    }

    private fun findVideoTitle(video: org.jsoup.nodes.Element, index: Int): String {
        val attrTitle = video.attr("title").ifBlank { video.attr("aria-label") }
        if (attrTitle.isNotBlank()) return attrTitle

        // Check nearest heading in parent containers (e.g. card, section, article, div)
        var parent: org.jsoup.nodes.Element? = video.parent()
        var depth = 0
        while (parent != null && depth < 4) {
            val heading = parent.selectFirst("h1, h2, h3, h4, h5, h6, .card-title, .title, [class*=title]")
            val headingText = heading?.text()?.trim()
            if (!headingText.isNullOrBlank() && headingText.length in 3..60) {
                return headingText
            }
            parent = parent.parent()
            depth++
        }

        // Check previous sibling element
        val prevText = video.previousElementSibling()?.text()?.trim()
        if (!prevText.isNullOrBlank() && prevText.length in 3..60) {
            return prevText
        }

        return "Video ${index + 1}"
    }

    private fun getResolutionFromTag(video: org.jsoup.nodes.Element): String? {
        val width = video.attr("width").toIntOrNull()
        val height = video.attr("height").toIntOrNull()
        return if (width != null && height != null && width > 0 && height > 0) {
            "${width}x${height}"
        } else null
    }
}
