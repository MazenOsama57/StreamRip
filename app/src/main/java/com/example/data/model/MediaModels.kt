package com.example.data.model

import java.io.File

enum class MediaType {
    VIDEO_MP4,
    AUDIO_MP3
}

enum class SourceProtocol(val displayName: String) {
    DIRECT_HTTP("Direct"),
    HLS("HLS Stream"),
    DASH("DASH Stream"),
    PLAYERJS("PlayerJS Embed"),
    HTML5_TAG("HTML5 Video")
}

data class DiscoveredMediaCandidate(
    val id: String,
    val url: String,
    val parentPageUrl: String,
    val protocol: SourceProtocol,
    val mimeType: String? = null,
    val label: String,                     // e.g. "1080p (MP4)", "Master HLS Playlist", "Original Stream"
    val resolution: String? = null,        // e.g. "1920x1080", "720p"
    val formatId: String? = null,          // yt-dlp format_id if known
    val ext: String? = null,               // "mp4", "m3u8", "mp3", etc.
    val thumbnailUrl: String? = null,      // Video poster or preview thumbnail
    val bitrateKbps: Long? = null,
    val estimatedSizeBytes: Long? = null,
    val requiredHeaders: Map<String, String> = emptyMap(), // e.g. "Referer" -> parentUrl
    val isAdaptive: Boolean = false,
    val isAudioOnly: Boolean = false
) {
    val formattedSize: String?
        get() {
            val bytes = estimatedSizeBytes ?: return null
            if (bytes <= 0) return null
            val kb = bytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format("%.2f GB", gb)
                mb >= 1.0 -> String.format("%.1f MB", mb)
                kb >= 1.0 -> String.format("%.1f KB", kb)
                else -> "$bytes B"
            }
        }
}

sealed interface ResolutionResult {
    data class Success(val candidates: List<DiscoveredMediaCandidate>, val metadata: MediaMetadata) : ResolutionResult
    data class Unsupported(val reason: String, val isDrmProtected: Boolean = false) : ResolutionResult
    data class Failure(val message: String, val throwable: Throwable? = null) : ResolutionResult
}

data class MediaMetadata(
    val title: String,
    val uploader: String?,
    val durationSeconds: Int,
    val thumbnailUrl: String?,
    val webpageUrl: String,
    val viewCount: String? = null,
    val candidates: List<DiscoveredMediaCandidate> = emptyList(),
    val selectedCandidateId: String? = null,
    val sourceProtocol: SourceProtocol? = null
) {
    val formattedDuration: String
        get() {
            if (durationSeconds <= 0) return ""
            val hours = durationSeconds / 3600
            val minutes = (durationSeconds % 3600) / 60
            val seconds = durationSeconds % 60
            return if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%d:%02d", minutes, seconds)
            }
        }

    val selectedCandidate: DiscoveredMediaCandidate?
        get() = candidates.firstOrNull { it.id == selectedCandidateId } ?: candidates.firstOrNull()
}

sealed interface DownloadStatus {
    data object Idle : DownloadStatus
    data object FetchingInfo : DownloadStatus
    data class Ready(val metadata: MediaMetadata) : DownloadStatus
    data class Downloading(
        val progress: Float,
        val etaSeconds: Long,
        val statusLine: String,
        val metadata: MediaMetadata?
    ) : DownloadStatus
    data class Completed(
        val file: File,
        val type: MediaType,
        val title: String
    ) : DownloadStatus
    data class Failed(
        val message: String
    ) : DownloadStatus
}

data class DownloadedFile(
    val id: String,
    val title: String,
    val file: File,
    val type: MediaType,
    val sizeBytes: Long,
    val timestamp: Long
) {
    val formattedSize: String
        get() {
            val kb = sizeBytes / 1024.0
            val mb = kb / 1024.0
            val gb = mb / 1024.0
            return when {
                gb >= 1.0 -> String.format("%.2f GB", gb)
                mb >= 1.0 -> String.format("%.1f MB", mb)
                kb >= 1.0 -> String.format("%.1f KB", kb)
                else -> "$sizeBytes B"
            }
        }
}
