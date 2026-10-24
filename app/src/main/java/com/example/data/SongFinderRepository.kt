package com.example.data

import android.content.Context
import android.util.Log
import com.example.data.model.IdentifiedSong
import com.example.data.model.MatchSource
import com.example.data.model.VideoMusicAnalysis
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SongFinderRepository(private val context: Context) {

    private val cookieRepo = CookieRepository(context)
    private val historyFile = File(context.filesDir, "song_discovery_history.json")

    companion object {
        private const val TAG = "SongFinderRepo"
    }

    /**
     * Analyzes a video URL (YouTube, TikTok, Twitter/X, Instagram, SoundCloud, etc.)
     * and extracts all songs and music tracks playing in it.
     */
    suspend fun analyzeVideoForMusic(
        url: String,
        onStepProgress: (String) -> Unit
    ): Result<VideoMusicAnalysis> = withContext(Dispatchers.IO) {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Please enter a valid video URL"))
        }

        try {
            onStepProgress("Connecting to video source...")
            val request = YoutubeDLRequest(cleanUrl).apply {
                addOption("--no-playlist")
                addOption("--no-update")
                addOption("--no-check-certificates")
                addOption("--dump-json")
                addOption("--extractor-args", "youtube:player_client=default,ios")
                cookieRepo.getCookiesFilePath()?.let { addOption("--cookies", it) }
            }

            var jsonString: String? = null
            var videoInfoTitle = "Video"
            var videoInfoUploader: String? = null
            var videoInfoDuration = 0
            var videoInfoThumb: String? = null
            var videoInfoWebpage = cleanUrl

            try {
                val response = YoutubeDL.getInstance().execute(request)
                jsonString = response.out
            } catch (e: Exception) {
                Log.w(TAG, "Execute dump-json failed, falling back to getInfo: ${e.message}")
            }

            // Fallback to getInfo if dump-json failed
            val rootJson: JSONObject? = if (!jsonString.isNullOrBlank()) {
                try {
                    JSONObject(jsonString)
                } catch (e: Exception) {
                    null
                }
            } else null

            if (rootJson != null) {
                videoInfoTitle = rootJson.optString("title", "Video")
                videoInfoUploader = rootJson.optString("uploader", rootJson.optString("channel", null))
                videoInfoDuration = rootJson.optInt("duration", 0)
                videoInfoThumb = rootJson.optString("thumbnail", null)
                videoInfoWebpage = rootJson.optString("webpage_url", cleanUrl)
            } else {
                // Secondary fallback via standard getInfo
                val info = YoutubeDL.getInstance().getInfo(request)
                videoInfoTitle = info.title ?: "Video"
                videoInfoUploader = info.uploader
                videoInfoDuration = info.duration
                videoInfoThumb = info.thumbnail
                videoInfoWebpage = info.webpageUrl ?: cleanUrl
            }

            onStepProgress("Scanning audio tracks, Content-ID & chapters...")
            val detectedSongs = mutableListOf<IdentifiedSong>()

            // 1. Check Content-ID / Licensed Music tags
            if (rootJson != null) {
                val track = rootJson.optString("track", "").trim()
                val artist = rootJson.optString("artist", "").trim()
                val album = rootJson.optString("album", null)
                val genre = rootJson.optString("genre", null)

                if (track.isNotEmpty() && artist.isNotEmpty()) {
                    detectedSongs.add(
                        IdentifiedSong(
                            title = track,
                            artist = artist,
                            album = album,
                            genre = genre,
                            source = MatchSource.CONTENT_ID,
                            sourceVideoUrl = videoInfoWebpage,
                            sourceVideoTitle = videoInfoTitle
                        )
                    )
                }

                // 2. Scan Video Chapters (common in DJ mixes, concerts, live streams, full albums)
                val chapters = rootJson.optJSONArray("chapters")
                if (chapters != null && chapters.length() > 0) {
                    for (i in 0 until chapters.length()) {
                        val chap = chapters.getJSONObject(i)
                        val chapTitle = chap.optString("title", "").trim()
                        val startTime = chap.optInt("start_time", 0)
                        val endTime = chap.optInt("end_time", 0)

                        if (chapTitle.isNotEmpty() && !isNonMusicChapter(chapTitle)) {
                            val parsed = parseArtistAndTitle(chapTitle)
                            val timeStr = formatChapterTime(startTime, endTime)
                            detectedSongs.add(
                                IdentifiedSong(
                                    title = parsed.second,
                                    artist = parsed.first ?: (videoInfoUploader ?: "Various Artists"),
                                    timestampInVideo = timeStr,
                                    source = MatchSource.CHAPTER,
                                    sourceVideoUrl = videoInfoWebpage,
                                    sourceVideoTitle = videoInfoTitle
                                )
                            )
                        }
                    }
                }

                // 3. Scan Video Description for Tracklists or "Music in this video"
                val description = rootJson.optString("description", "")
                if (description.isNotEmpty()) {
                    val descSongs = parseDescriptionForMusic(description, videoInfoWebpage, videoInfoTitle)
                    detectedSongs.addAll(descSongs)
                }
            }

            // 4. If no songs found yet (or in addition), analyze the Video Title itself
            // e.g. "Alan Walker - Faded (Official Music Video)" -> "Alan Walker" & "Faded"
            if (detectedSongs.isEmpty()) {
                val parsed = parseArtistAndTitle(videoInfoTitle)
                val songTitle = cleanTitle(parsed.second)
                val artistName = parsed.first ?: (videoInfoUploader ?: "Unknown Artist")
                if (songTitle.isNotBlank()) {
                    detectedSongs.add(
                        IdentifiedSong(
                            title = songTitle,
                            artist = cleanArtist(artistName),
                            source = MatchSource.TITLE_ANALYSIS,
                            sourceVideoUrl = videoInfoWebpage,
                            sourceVideoTitle = videoInfoTitle
                        )
                    )
                }
            }

            // 5. Deduplicate songs
            val deduplicated = mutableListOf<IdentifiedSong>()
            val seenKeys = mutableSetOf<String>()
            for (s in detectedSongs) {
                val key = "${s.title.lowercase()}|${s.artist.lowercase()}"
                if (!seenKeys.contains(key) && s.title.isNotBlank()) {
                    seenKeys.add(key)
                    deduplicated.add(s)
                }
            }

            // 6. Enrich identified songs with Open Music Catalog (album art & 30s audio previews)
            onStepProgress("Cross-referencing open music database for previews & artwork...")
            val enrichedSongs = deduplicated.map { song ->
                try {
                    OpenMusicDatabaseClient.enrichSongMetadata(song)
                } catch (e: Exception) {
                    song
                }
            }

            // Save found songs to discovery history
            for (song in enrichedSongs) {
                saveToHistory(song)
            }

            val analysis = VideoMusicAnalysis(
                videoUrl = videoInfoWebpage,
                videoTitle = videoInfoTitle,
                videoUploader = videoInfoUploader,
                durationSeconds = videoInfoDuration,
                thumbnailUrl = videoInfoThumb,
                songs = enrichedSongs
            )

            Result.success(analysis)
        } catch (e: Exception) {
            Log.e(TAG, "Video music analysis failed", e)
            Result.failure(e)
        }
    }

    /**
     * Parses a video description for tracklists with timestamps (e.g., "01:24 Artist - Title")
     * or "Music in this video" metadata sections.
     */
    private fun parseDescriptionForMusic(
        description: String,
        videoUrl: String,
        videoTitle: String
    ): List<IdentifiedSong> {
        val results = mutableListOf<IdentifiedSong>()
        val lines = description.lines()

        val timestampRegex = Regex("""(?:\[?(\d{1,2}:\d{2}(?::\d{2})?)\]?)\s*[-–—:]?\s*(.+)""")
        val songLineRegex = Regex("""(?:Song|Track)\s*:\s*(.+)""", RegexOption.IGNORE_CASE)
        val artistLineRegex = Regex("""(?:Artist|By)\s*:\s*(.+)""", RegexOption.IGNORE_CASE)

        var pendingSong: String? = null

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) continue

            // Check "Song: ..." and "Artist: ..." lines
            val songMatch = songLineRegex.find(trimmed)
            if (songMatch != null) {
                pendingSong = songMatch.groupValues[1].trim()
                continue
            }

            val artistMatch = artistLineRegex.find(trimmed)
            if (artistMatch != null && pendingSong != null) {
                val artist = artistMatch.groupValues[1].trim()
                results.add(
                    IdentifiedSong(
                        title = cleanTitle(pendingSong),
                        artist = cleanArtist(artist),
                        source = MatchSource.TRACKLIST,
                        sourceVideoUrl = videoUrl,
                        sourceVideoTitle = videoTitle
                    )
                )
                pendingSong = null
                continue
            }

            // Check timestamp tracklist lines like "02:15 Artist - Song Title"
            val tsMatch = timestampRegex.find(trimmed)
            if (tsMatch != null) {
                val timestamp = tsMatch.groupValues[1]
                val trackText = tsMatch.groupValues[2].trim()
                if (trackText.isNotEmpty() && !isNonMusicChapter(trackText)) {
                    val parsed = parseArtistAndTitle(trackText)
                    results.add(
                        IdentifiedSong(
                            title = cleanTitle(parsed.second),
                            artist = cleanArtist(parsed.first ?: "Various Artists"),
                            timestampInVideo = "At $timestamp",
                            source = MatchSource.TRACKLIST,
                            sourceVideoUrl = videoUrl,
                            sourceVideoTitle = videoTitle
                        )
                    )
                }
            }
        }

        return results
    }

    /**
     * Splits a text like "Artist - Title" or "Title (by Artist)" into (Artist, Title)
     */
    private fun parseArtistAndTitle(rawText: String): Pair<String?, String> {
        val delimiters = listOf(" - ", " – ", " — ", " : ", " // ")
        for (delim in delimiters) {
            if (rawText.contains(delim)) {
                val parts = rawText.split(delim, limit = 2)
                if (parts.size == 2) {
                    val artist = cleanArtist(parts[0].trim())
                    val title = cleanTitle(parts[1].trim())
                    return Pair(artist, title)
                }
            }
        }

        // Check "by Artist" format
        val byRegex = Regex("""(.+?)\s+by\s+(.+)""", RegexOption.IGNORE_CASE)
        val match = byRegex.find(rawText)
        if (match != null) {
            val title = cleanTitle(match.groupValues[1].trim())
            val artist = cleanArtist(match.groupValues[2].trim())
            return Pair(artist, title)
        }

        return Pair(null, cleanTitle(rawText))
    }

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(Regex("""(?i)\((?:official\s*(?:music\s*)?video|video|audio|lyrics?|hd|4k|visualizer|remastered|explicit)\)"""), "")
            .replace(Regex("""(?i)\[(?:official\s*(?:music\s*)?video|video|audio|lyrics?|hd|4k|visualizer|remastered|explicit)\]"""), "")
            .replace(Regex("""(?i)\bft\.?\s*[^()]+|\bfeat\.?\s*[^()]+"""), "")
            .trim()
    }

    private fun cleanArtist(raw: String): String {
        return raw.replace(Regex("""(?i)\s*-\s*topic$"""), "").trim()
    }

    private fun isNonMusicChapter(text: String): Boolean {
        val lower = text.lowercase()
        val nonMusicWords = listOf("intro", "outro", "subscribe", "sponsor", "like & share", "advertisement", "credits", "preview", "q&a")
        return nonMusicWords.any { lower == it || lower.startsWith("$it ") }
    }

    private fun formatChapterTime(startSeconds: Int, endSeconds: Int): String {
        fun formatSec(s: Int): String {
            val m = s / 60
            val sec = s % 60
            return String.format("%02d:%02d", m, sec)
        }
        return if (endSeconds > startSeconds) {
            "${formatSec(startSeconds)} – ${formatSec(endSeconds)}"
        } else {
            "At ${formatSec(startSeconds)}"
        }
    }

    /**
     * Searches songs directly by name or artist using the open catalog.
     */
    suspend fun searchSongsDirectly(query: String): List<IdentifiedSong> {
        return OpenMusicDatabaseClient.searchSongs(query, limit = 15)
    }

    /**
     * History of discovered songs saved locally.
     */
    suspend fun getDiscoveryHistory(): List<IdentifiedSong> = withContext(Dispatchers.IO) {
        if (!historyFile.exists()) return@withContext emptyList()
        try {
            val text = historyFile.readText()
            val array = JSONArray(text)
            val list = mutableListOf<IdentifiedSong>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    IdentifiedSong(
                        id = obj.optString("id"),
                        title = obj.optString("title"),
                        artist = obj.optString("artist"),
                        album = obj.optString("album", null),
                        releaseYear = obj.optString("releaseYear", null),
                        genre = obj.optString("genre", null),
                        artworkUrl = obj.optString("artworkUrl", null),
                        previewUrl = obj.optString("previewUrl", null),
                        timestampInVideo = obj.optString("timestampInVideo", null),
                        source = try {
                            MatchSource.valueOf(obj.optString("source", MatchSource.CONTENT_ID.name))
                        } catch (_: Exception) {
                            MatchSource.CONTENT_ID
                        },
                        sourceVideoUrl = obj.optString("sourceVideoUrl", null),
                        sourceVideoTitle = obj.optString("sourceVideoTitle", null),
                        discoveredAt = obj.optLong("discoveredAt", System.currentTimeMillis())
                    )
                )
            }
            list.sortedByDescending { it.discoveredAt }
        } catch (e: Exception) {
            Log.w(TAG, "Error reading history: ${e.message}")
            emptyList()
        }
    }

    suspend fun saveToHistory(song: IdentifiedSong) = withContext(Dispatchers.IO) {
        val current = getDiscoveryHistory().toMutableList()
        current.removeAll { it.title.equals(song.title, ignoreCase = true) && it.artist.equals(song.artist, ignoreCase = true) }
        current.add(0, song)
        val trimmed = current.take(50) // keep latest 50 discovered songs

        val array = JSONArray()
        for (item in trimmed) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("artist", item.artist)
                item.album?.let { put("album", it) }
                item.releaseYear?.let { put("releaseYear", it) }
                item.genre?.let { put("genre", it) }
                item.artworkUrl?.let { put("artworkUrl", it) }
                item.previewUrl?.let { put("previewUrl", it) }
                item.timestampInVideo?.let { put("timestampInVideo", it) }
                put("source", item.source.name)
                item.sourceVideoUrl?.let { put("sourceVideoUrl", it) }
                item.sourceVideoTitle?.let { put("sourceVideoTitle", it) }
                put("discoveredAt", item.discoveredAt)
            }
            array.put(obj)
        }

        try {
            historyFile.writeText(array.toString(2))
        } catch (e: Exception) {
            Log.w(TAG, "Error saving history: ${e.message}")
        }
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        if (historyFile.exists()) {
            historyFile.delete()
        }
    }
}
