package com.example.ui.components

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log

class AudioPreviewPlayer(private val context: Context) {

    private var mediaPlayer: MediaPlayer? = null
    var currentlyPlayingUrl: String? = null
        private set
    var isPlaying: Boolean = false
        private set

    fun play(
        url: String,
        onStateChange: (isPlaying: Boolean, playingUrl: String?) -> Unit
    ) {
        if (currentlyPlayingUrl == url && isPlaying) {
            stop(onStateChange)
            return
        }

        stop { _, _ -> }

        try {
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(url)
                setOnPreparedListener { mp ->
                    mp.start()
                    this@AudioPreviewPlayer.isPlaying = true
                    this@AudioPreviewPlayer.currentlyPlayingUrl = url
                    onStateChange(true, url)
                }
                setOnCompletionListener {
                    this@AudioPreviewPlayer.isPlaying = false
                    this@AudioPreviewPlayer.currentlyPlayingUrl = null
                    onStateChange(false, null)
                }
                setOnErrorListener { _, what, extra ->
                    Log.w("AudioPreviewPlayer", "Playback error: what=$what, extra=$extra")
                    this@AudioPreviewPlayer.isPlaying = false
                    this@AudioPreviewPlayer.currentlyPlayingUrl = null
                    onStateChange(false, null)
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.e("AudioPreviewPlayer", "Failed to start preview: ${e.message}")
            this@AudioPreviewPlayer.isPlaying = false
            this@AudioPreviewPlayer.currentlyPlayingUrl = null
            onStateChange(false, null)
        }
    }

    fun stop(onStateChange: (isPlaying: Boolean, playingUrl: String?) -> Unit = { _, _ -> }) {
        try {
            if (mediaPlayer?.isPlaying == true) {
                mediaPlayer?.stop()
            }
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null
        isPlaying = false
        currentlyPlayingUrl = null
        onStateChange(false, null)
    }

    fun release() {
        stop()
    }
}
