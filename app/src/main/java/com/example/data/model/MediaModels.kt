package com.example.data.model

import java.io.File

enum class MediaType {
    VIDEO_MP4,
    AUDIO_MP3
}

data class MediaMetadata(
    val title: String,
    val uploader: String?,
    val durationSeconds: Int,
    val thumbnailUrl: String?,
    val webpageUrl: String,
    val viewCount: String? = null
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
