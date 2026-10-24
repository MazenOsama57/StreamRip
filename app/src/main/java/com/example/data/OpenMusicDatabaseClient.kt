package com.example.data

import android.util.Log
import com.example.data.model.IdentifiedSong
import com.example.data.model.MatchSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Open-source and free music metadata bridge that queries public open music directories
 * (iTunes Open Search & MusicBrainz API) without any API keys, paid tokens, or accounts.
 */
object OpenMusicDatabaseClient {

    private const val TAG = "OpenMusicDB"

    /**
     * Searches the public open catalog for matching songs with high-resolution artwork
     * and free 30-second audio stream previews.
     */
    suspend fun searchSongs(query: String, limit: Int = 5): List<IdentifiedSong> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        val results = mutableListOf<IdentifiedSong>()

        // 1. Query iTunes Public Song Search (instant, no auth, high-res art & audio preview)
        try {
            val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
            val endpoint = "https://itunes.apple.com/search?term=$encodedQuery&entity=song&limit=$limit"
            val jsonText = httpGet(endpoint)
            if (!jsonText.isNullOrBlank()) {
                val root = JSONObject(jsonText)
                val items = root.optJSONArray("results")
                if (items != null) {
                    for (i in 0 until items.length()) {
                        val item = items.getJSONObject(i)
                        val trackName = item.optString("trackName", "").trim()
                        val artistName = item.optString("artistName", "").trim()
                        if (trackName.isNotEmpty() && artistName.isNotEmpty()) {
                            val rawArt = item.optString("artworkUrl100", null)
                            val highResArt = rawArt?.replace("100x100bb", "600x600bb")
                            val previewUrl = item.optString("previewUrl", null)
                            val albumName = item.optString("collectionName", null)
                            val genre = item.optString("primaryGenreName", null)
                            val releaseDate = item.optString("releaseDate", null)
                            val releaseYear = if (!releaseDate.isNullOrEmpty() && releaseDate.length >= 4) {
                                releaseDate.substring(0, 4)
                            } else null

                            results.add(
                                IdentifiedSong(
                                    title = trackName,
                                    artist = artistName,
                                    album = albumName,
                                    releaseYear = releaseYear,
                                    genre = genre,
                                    artworkUrl = highResArt,
                                    previewUrl = previewUrl,
                                    source = MatchSource.OPEN_DATABASE
                                )
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "iTunes public search error for '$query': ${e.message}")
        }

        // 2. If no results or as fallback, query MusicBrainz Open Encyclopedia
        if (results.isEmpty()) {
            try {
                val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
                val endpoint = "https://musicbrainz.org/ws/2/recording?query=$encodedQuery&fmt=json&limit=$limit"
                val jsonText = httpGet(endpoint, userAgent = "StreamRip/1.0 (https://github.com/yausername/youtubedl-android)")
                if (!jsonText.isNullOrBlank()) {
                    val root = JSONObject(jsonText)
                    val recordings = root.optJSONArray("recordings")
                    if (recordings != null) {
                        for (i in 0 until recordings.length()) {
                            val rec = recordings.getJSONObject(i)
                            val title = rec.optString("title", "").trim()
                            val artistCredits = rec.optJSONArray("artist-credit")
                            val artist = if (artistCredits != null && artistCredits.length() > 0) {
                                artistCredits.getJSONObject(0).optString("name", "Unknown Artist")
                            } else {
                                "Unknown Artist"
                            }
                            if (title.isNotEmpty()) {
                                results.add(
                                    IdentifiedSong(
                                        title = title,
                                        artist = artist,
                                        source = MatchSource.OPEN_DATABASE
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "MusicBrainz query error: ${e.message}")
            }
        }

        results
    }

    /**
     * Enriches a detected song title and artist with verified metadata, album art, and 30s preview.
     */
    suspend fun enrichSongMetadata(song: IdentifiedSong): IdentifiedSong = withContext(Dispatchers.IO) {
        if (!song.artworkUrl.isNullOrBlank() && !song.previewUrl.isNullOrBlank()) {
            return@withContext song
        }

        val query = "${song.artist} ${song.title}"
        val matches = searchSongs(query, limit = 1)
        val best = matches.firstOrNull() ?: return@withContext song

        song.copy(
            artworkUrl = best.artworkUrl ?: song.artworkUrl,
            previewUrl = best.previewUrl ?: song.previewUrl,
            album = song.album ?: best.album,
            releaseYear = song.releaseYear ?: best.releaseYear,
            genre = song.genre ?: best.genre
        )
    }

    private fun httpGet(urlStr: String, userAgent: String = "StreamRip/1.0 (Android)"): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(urlStr)
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", userAgent)
                setRequestProperty("Accept", "application/json")
            }
            val responseCode = conn.responseCode
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                    reader.readText()
                }
            } else {
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP error fetching $urlStr: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }
}
