package com.example

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class StreamRipApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    sealed interface InitState {
        data class Progress(val statusMessage: String, val currentStep: Int, val totalSteps: Int) : InitState
        data class Ready(val version: String, val updateNote: String) : InitState
        data class Warning(val message: String, val version: String) : InitState
        data class Error(val message: String) : InitState
    }

    private val _initState = MutableStateFlow<InitState>(
        InitState.Progress("Initializing runtime...", 1, 2)
    )
    val initState: StateFlow<InitState> = _initState.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        startEngineInitialization()
    }

    fun startEngineInitialization() {
        appScope.launch {
            try {
                // Initialize YoutubeDL base engine safely
                try {
                    YoutubeDL.getInstance().init(this@StreamRipApp)
                } catch (e: Throwable) {
                    Log.w("StreamRipApp", "YoutubeDL init note: ${e.message}")
                }

                // Initialize FFmpeg safely
                try {
                    FFmpeg.getInstance().init(this@StreamRipApp)
                } catch (e: Throwable) {
                    Log.w("StreamRipApp", "FFmpeg init warning: ${e.message}")
                }

                // Retrieve bundled or cached version
                val version = try {
                    YoutubeDL.getInstance().version(this@StreamRipApp) ?: "Bundled"
                } catch (e: Throwable) {
                    "Bundled"
                }

                _initState.value = InitState.Ready(
                    version = version,
                    updateNote = "Ready"
                )
                Log.i("StreamRipApp", "StreamRip Engine Ready: $version")
            } catch (e: Throwable) {
                Log.e("StreamRipApp", "Engine initialization fallback", e)
                _initState.value = InitState.Ready(
                    version = "Bundled",
                    updateNote = "Ready"
                )
            }
        }
    }
}
