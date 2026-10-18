package com.example

import android.app.Application
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
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
        InitState.Progress("Initializing media runtime...", 1, 4)
    )
    val initState: StateFlow<InitState> = _initState.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        startEngineInitialization(forceUpdate = true)
    }

    fun startEngineInitialization(forceUpdate: Boolean = true) {
        appScope.launch {
            try {
                // Step 1: Initialize YoutubeDL base engine
                _initState.value = InitState.Progress("Initializing Python & yt-dlp core...", 1, 4)
                YoutubeDL.getInstance().init(this@StreamRipApp)

                // Step 2: Initialize FFmpeg
                _initState.value = InitState.Progress("Loading FFmpeg converter engine...", 2, 4)
                try {
                    FFmpeg.getInstance().init(this@StreamRipApp)
                } catch (e: Exception) {
                    Log.w("StreamRipApp", "FFmpeg init warning: ${e.message}")
                }

                val initialVersion = try {
                    YoutubeDL.getInstance().version(this@StreamRipApp) ?: "bundled"
                } catch (e: Exception) {
                    "bundled"
                }

                // Step 3: Always automatically update yt-dlp to latest version from release channel
                _initState.value = InitState.Progress("Updating yt-dlp to latest version...", 3, 4)
                var updateStatusNote = "Checked"
                try {
                    val updateResult = YoutubeDL.getInstance().updateYoutubeDL(this@StreamRipApp)
                    updateStatusNote = when (updateResult) {
                        YoutubeDL.UpdateStatus.DONE -> "Updated to latest"
                        YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Already latest"
                        else -> "Synchronized"
                    }
                    Log.i("StreamRipApp", "yt-dlp auto-update status: $updateResult")
                } catch (e: Exception) {
                    Log.w("StreamRipApp", "Auto update network check failed: ${e.message}", e)
                    updateStatusNote = "Offline mode (using cached engine)"
                }

                // Step 4: Final verification
                _initState.value = InitState.Progress("Finalizing engine setup...", 4, 4)
                val finalVersion = try {
                    YoutubeDL.getInstance().version(this@StreamRipApp) ?: initialVersion
                } catch (e: Exception) {
                    initialVersion
                }

                _initState.value = InitState.Ready(
                    version = finalVersion,
                    updateNote = updateStatusNote
                )
                Log.i("StreamRipApp", "StreamRip Engine Ready: $finalVersion ($updateStatusNote)")
            } catch (e: YoutubeDLException) {
                Log.e("StreamRipApp", "YoutubeDL initialization error", e)
                _initState.value = InitState.Error(
                    e.message ?: "Failed to initialize yt-dlp. Please restart or check permissions."
                )
            } catch (e: Throwable) {
                Log.e("StreamRipApp", "Unexpected initialization error", e)
                _initState.value = InitState.Error(
                    e.message ?: "An unexpected error occurred during engine startup."
                )
            }
        }
    }
}
