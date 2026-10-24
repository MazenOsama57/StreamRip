package com.example.data.model

import java.io.File
import java.util.UUID

enum class MatchSource(val label: String) {
    CONTENT_ID("Content ID Tag"),
    CHAPTER("Video Chapter"),
    TRACKLIST("Video Tracklist"),
    TITLE_ANALYSIS("Video Title & Sound"),
    OPEN_DATABASE("Open Music Catalog"),
    MANUAL_SEARCH("Song Search")
}

data class IdentifiedSong(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val artist: String,
    val album: String? = null,
    val releaseYear: String? = null,
    val genre: String? = null,
    val artworkUrl: String? = null,
    val previewUrl: String? = null,
    val timestampInVideo: String? = null,
    val source: MatchSource = MatchSource.CONTENT_ID,
    val sourceVideoUrl: String? = null,
    val sourceVideoTitle: String? = null,
    val discoveredAt: Long = System.currentTimeMillis()
)

data class VideoMusicAnalysis(
    val videoUrl: String,
    val videoTitle: String,
    val videoUploader: String?,
    val durationSeconds: Int,
    val thumbnailUrl: String?,
    val songs: List<IdentifiedSong>
)

sealed interface FinderStatus {
    data object Idle : FinderStatus
    data class Analyzing(val stepDescription: String) : FinderStatus
    data class Success(val analysis: VideoMusicAnalysis) : FinderStatus
    data class Error(val message: String) : FinderStatus
}
