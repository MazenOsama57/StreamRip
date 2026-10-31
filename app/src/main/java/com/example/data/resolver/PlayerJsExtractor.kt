package com.example.data.resolver

import android.util.Log
import com.example.data.model.DiscoveredMediaCandidate
import com.example.data.model.SourceProtocol
import java.util.UUID
import java.util.regex.Pattern

object PlayerJsExtractor {
    private const val TAG = "PlayerJsExtractor"

    // Matches file: "..." or file: '...' respecting matching quotes
    private val FILE_PARAM_REGEX = Pattern.compile(
        """(?i)\b(?:file|src|url)\s*:\s*(?:"([^"]+)"|'([^']+)')"""
    )

    // Matches new Playerjs\s*\(\s*\{([^}]+)\}\s*\)
    private val PLAYERJS_INIT_REGEX = Pattern.compile(
        """(?i)new\s+Playerjs\s*\(\s*\{([^}]+)\}\s*\)"""
    )

    // Matches data-file=["']([^"']+)["']
    private val DATA_FILE_REGEX = Pattern.compile(
        """(?i)data-(?:file|player|stream)\s*=\s*(?:"([^"]+)"|'([^']+)')"""
    )

    // Matches [Quality/Label]URL pairs like: [1080p]https://site.com/1080.mp4
    private val QUALITY_ITEM_REGEX = Pattern.compile(
        """(?:\[([^\]]+)\])?(https?://[^\s,]+|/[^\s,]+|\.\./[^\s,]+)"""
    )

    fun extractFromHtmlOrScript(content: String, baseUrl: String): List<DiscoveredMediaCandidate> {
        val candidates = mutableListOf<DiscoveredMediaCandidate>()
        val seenUrls = mutableSetOf<String>()

        try {
            // 1. Check data-file attributes
            val dataMatcher = DATA_FILE_REGEX.matcher(content)
            while (dataMatcher.find()) {
                val rawFile = (dataMatcher.group(1) ?: dataMatcher.group(2))?.trim() ?: continue
                parseFileString(rawFile, baseUrl, candidates, seenUrls)
            }

            // 2. Check PlayerJS constructor blocks
            val initMatcher = PLAYERJS_INIT_REGEX.matcher(content)
            while (initMatcher.find()) {
                val block = initMatcher.group(1) ?: continue
                val blockMatcher = FILE_PARAM_REGEX.matcher(block)
                while (blockMatcher.find()) {
                    val rawFile = (blockMatcher.group(1) ?: blockMatcher.group(2))?.trim() ?: continue
                    parseFileString(rawFile, baseUrl, candidates, seenUrls)
                }
            }

            // 3. Scan general file: "..." occurrences in <script> tags
            val generalMatcher = FILE_PARAM_REGEX.matcher(content)
            while (generalMatcher.find()) {
                val rawFile = (generalMatcher.group(1) ?: generalMatcher.group(2))?.trim() ?: continue
                if (looksLikeMediaStream(rawFile) || rawFile.contains("\"file\"")) {
                    parseFileString(rawFile, baseUrl, candidates, seenUrls)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing PlayerJS content", e)
        }

        return candidates
    }

    private fun parseFileString(
        rawFile: String,
        baseUrl: String,
        candidates: MutableList<DiscoveredMediaCandidate>,
        seenUrls: MutableSet<String>
    ) {
        // If rawFile is a JSON array: [{"title":"1080p","file":"https://..."}, ...]
        if (rawFile.trim().startsWith("[") && rawFile.contains("\"file\"")) {
            val jsonItemRegex = Pattern.compile("""(?i)\{"(?:title|label)"\s*:\s*"([^"]+)"\s*,\s*"file"\s*:\s*"([^"]+)"\}|\{"file"\s*:\s*"([^"]+)"\s*,\s*"(?:title|label)"\s*:\s*"([^"]+)"\}""")
            val jsonMatcher = jsonItemRegex.matcher(rawFile)
            var foundAnyJson = false
            while (jsonMatcher.find()) {
                val label = jsonMatcher.group(1) ?: jsonMatcher.group(4) ?: "PlayerJS Stream"
                val file = jsonMatcher.group(2) ?: jsonMatcher.group(3) ?: continue
                foundAnyJson = true
                val resolved = Html5MediaExtractor.resolveUrl(file.trim(), baseUrl)
                if (resolved.isNotBlank() && !seenUrls.contains(resolved) && looksLikeMediaStream(resolved)) {
                    seenUrls.add(resolved)
                    val protocol = detectProtocol(resolved)
                    candidates.add(
                        DiscoveredMediaCandidate(
                            id = "playerjs_json_${UUID.randomUUID().toString().take(6)}",
                            url = resolved,
                            parentPageUrl = baseUrl,
                            protocol = protocol,
                            mimeType = inferMimeType(resolved),
                            label = "$label (${protocol.displayName})",
                            resolution = extractResolutionFromUrl(resolved) ?: label,
                            requiredHeaders = mapOf("Referer" to baseUrl)
                        )
                    )
                }
            }
            if (foundAnyJson) return
        }

        // PlayerJS quality separator can be comma or " or "
        val parts = if (rawFile.contains(" or ")) {
            rawFile.split(" or ")
        } else if (rawFile.contains(",")) {
            rawFile.split(",")
        } else {
            listOf(rawFile)
        }

        for (part in parts) {
            val trimmedPart = part.trim()
            if (trimmedPart.isBlank()) continue

            val matcher = QUALITY_ITEM_REGEX.matcher(trimmedPart)
            if (matcher.find()) {
                val qualityTag = matcher.group(1)?.trim()
                val rawUrl = matcher.group(2)?.trim() ?: continue

                val resolvedUrl = Html5MediaExtractor.resolveUrl(rawUrl, baseUrl)
                if (resolvedUrl.isBlank() || seenUrls.contains(resolvedUrl)) continue
                if (!looksLikeMediaStream(resolvedUrl)) continue

                seenUrls.add(resolvedUrl)
                val protocol = detectProtocol(resolvedUrl)

                val label = buildString {
                    if (!qualityTag.isNullOrBlank()) {
                        append(qualityTag)
                    } else {
                        val res = extractResolutionFromUrl(resolvedUrl)
                        if (res != null) append(res) else append("PlayerJS Stream")
                    }
                    if (protocol == SourceProtocol.HLS) {
                        append(" (HLS)")
                    }
                }

                candidates.add(
                    DiscoveredMediaCandidate(
                        id = "playerjs_${UUID.randomUUID().toString().take(6)}",
                        url = resolvedUrl,
                        parentPageUrl = baseUrl,
                        protocol = protocol,
                        mimeType = inferMimeType(resolvedUrl),
                        label = label,
                        resolution = qualityTag ?: extractResolutionFromUrl(resolvedUrl),
                        requiredHeaders = mapOf("Referer" to baseUrl)
                    )
                )
            }
        }
    }

    private fun looksLikeMediaStream(str: String): Boolean {
        val lower = str.lowercase()
        return lower.contains(".mp4") ||
                lower.contains(".m3u8") ||
                lower.contains(".mpd") ||
                lower.contains(".webm") ||
                lower.contains(".mkv") ||
                lower.contains(".mp3") ||
                lower.contains(".m4a")
    }

    private fun detectProtocol(url: String): SourceProtocol {
        val clean = url.substringBefore("?").substringBefore("#").lowercase()
        return when {
            clean.endsWith(".m3u8") -> SourceProtocol.HLS
            clean.endsWith(".mpd")  -> SourceProtocol.DASH
            else                    -> SourceProtocol.PLAYERJS
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
            else -> "video/mp4"
        }
    }

    private fun extractResolutionFromUrl(url: String): String? {
        val regex = Regex("""(?i)(1080p|720p|480p|360p|240p|4k|2k|\d{3,4}x\d{3,4})""")
        return regex.find(url)?.value
    }
}
