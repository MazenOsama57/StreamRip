package com.example.ui.viewmodel

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.StreamRipApp
import com.example.data.BotVerificationException
import com.example.data.YoutubeDLRepository
import com.example.data.model.DownloadedFile
import com.example.data.model.DownloadStatus
import com.example.data.model.MediaMetadata
import com.example.data.model.MediaType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

enum class AppTab {
    DOWNLOAD,
    LIBRARY
}

data class MainUiState(
    val url: String = "",
    val selectedType: MediaType = MediaType.VIDEO_MP4,
    val videoQuality: String = "Best",
    val audioQuality: String = "320k",
    val status: DownloadStatus = DownloadStatus.Idle,
    val downloads: List<DownloadedFile> = emptyList(),
    val engineVersion: String? = null,
    val isUpdatingEngine: Boolean = false,
    val activeTab: AppTab = AppTab.DOWNLOAD,
    val userMessage: String? = null,
    val isEngineReady: Boolean = true,
    val hasActiveCookies: Boolean = false,
    val showCookieDialog: Boolean = false,
    val showSettingsDialog: Boolean = false,
    val requiresVerification: Boolean = false
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as StreamRipApp
    private val repository = YoutubeDLRepository(application)
    private var downloadJob: Job? = null
    private var activeProcessId: String? = null

    private val _uiState = MutableStateFlow(
        MainUiState(
            hasActiveCookies = repository.cookieRepo.hasCookies(),
            engineVersion = repository.getEngineVersion()
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            app.initState.collect { initState ->
                when (initState) {
                    is StreamRipApp.InitState.Ready -> {
                        val ver = repository.getEngineVersion() ?: initState.version
                        _uiState.update {
                            it.copy(
                                isEngineReady = true,
                                engineVersion = ver
                            )
                        }
                    }
                    is StreamRipApp.InitState.Warning -> {
                        val ver = repository.getEngineVersion() ?: initState.version
                        _uiState.update {
                            it.copy(
                                isEngineReady = true,
                                engineVersion = ver
                            )
                        }
                    }
                    is StreamRipApp.InitState.Progress -> {
                        // Startup progress is handled smoothly in the background
                    }
                    is StreamRipApp.InitState.Error -> {
                        _uiState.update {
                            it.copy(
                                isEngineReady = true,
                                engineVersion = repository.getEngineVersion() ?: "Ready"
                            )
                        }
                    }
                }
            }
        }
        refreshDownloads()
    }

    fun openSettingsDialog() {
        _uiState.update { it.copy(showSettingsDialog = true) }
    }

    fun closeSettingsDialog() {
        _uiState.update { it.copy(showSettingsDialog = false) }
    }

    fun openCookieDialog() {
        _uiState.update { it.copy(showCookieDialog = true) }
    }

    fun closeCookieDialog() {
        _uiState.update { it.copy(showCookieDialog = false) }
    }

    fun saveCookiesFromWebView() {
        val success = repository.cookieRepo.saveFromCookieManager()
        if (success) {
            _uiState.update {
                it.copy(
                    hasActiveCookies = true,
                    requiresVerification = false,
                    userMessage = "YouTube cookies saved successfully"
                )
            }
        } else {
            _uiState.update { it.copy(userMessage = "No active cookies detected. Please ensure you are logged in on the page.") }
        }
    }

    fun saveRawCookies(content: String) {
        val isNetscape = content.contains("\t")
        val success = if (isNetscape) {
            repository.cookieRepo.saveRawCookiesTxt(content)
        } else {
            repository.cookieRepo.convertAndSaveCookies(content)
        }
        if (success) {
            _uiState.update {
                it.copy(
                    hasActiveCookies = true,
                    requiresVerification = false,
                    userMessage = "Cookies saved and applied to yt-dlp"
                )
            }
        } else {
            _uiState.update { it.copy(userMessage = "Failed to save cookies. Check format.") }
        }
    }

    fun clearCookies() {
        repository.cookieRepo.clearCookies()
        _uiState.update {
            it.copy(
                hasActiveCookies = false,
                userMessage = "Cookies removed"
            )
        }
    }

    fun onUrlChanged(newUrl: String) {
        val trimmed = newUrl.trim()
        _uiState.update {
            it.copy(
                url = newUrl,
                status = if (trimmed.isEmpty()) DownloadStatus.Idle else it.status,
                requiresVerification = false
            )
        }
    }

    fun onSelectMediaType(type: MediaType) {
        _uiState.update { it.copy(selectedType = type) }
    }

    fun onSelectVideoQuality(quality: String) {
        _uiState.update { it.copy(videoQuality = quality) }
    }

    fun onSelectAudioQuality(quality: String) {
        _uiState.update { it.copy(audioQuality = quality) }
    }

    fun setTab(tab: AppTab) {
        _uiState.update { it.copy(activeTab = tab) }
        if (tab == AppTab.LIBRARY) {
            refreshDownloads()
        }
    }

    fun clearMessage() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun refreshDownloads() {
        viewModelScope.launch {
            val list = repository.listDownloadedFiles()
            _uiState.update { it.copy(downloads = list) }
        }
    }

    fun fetchMetadata() {
        val currentUrl = _uiState.value.url.trim()
        if (currentUrl.isEmpty()) {
            _uiState.update { it.copy(userMessage = "Please enter a valid URL") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(status = DownloadStatus.FetchingInfo) }
            val result = repository.fetchMetadata(currentUrl)
            result.fold(
                onSuccess = { metadata ->
                    _uiState.update { it.copy(status = DownloadStatus.Ready(metadata), requiresVerification = false) }
                },
                onFailure = { error ->
                    val isBot = error is BotVerificationException || (error.message?.contains("Sign in to confirm you're not a bot", true) == true)
                    _uiState.update {
                        it.copy(
                            status = DownloadStatus.Failed(
                                error.message ?: "Failed to fetch media information. Check URL or update engine."
                            ),
                            requiresVerification = isBot
                        )
                    }
                }
            )
        }
    }

    fun startDownload() {
        val currentUrl = _uiState.value.url.trim()
        if (currentUrl.isEmpty()) {
            _uiState.update { it.copy(userMessage = "Please enter a URL first") }
            return
        }

        val state = _uiState.value
        val metadata = when (val s = state.status) {
            is DownloadStatus.Ready -> s.metadata
            is DownloadStatus.Downloading -> s.metadata
            else -> null
        }

        val processId = UUID.randomUUID().toString()
        activeProcessId = processId

        downloadJob?.cancel()
        downloadJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    status = DownloadStatus.Downloading(
                        progress = 0f,
                        etaSeconds = 0L,
                        statusLine = "Starting download...",
                        metadata = metadata
                    ),
                    requiresVerification = false
                )
            }

            val result = repository.downloadMedia(
                url = currentUrl,
                type = state.selectedType,
                videoQuality = state.videoQuality,
                audioQuality = state.audioQuality,
                processId = processId
            ) { progress, eta, line ->
                _uiState.update { current ->
                    if (current.status is DownloadStatus.Downloading) {
                        current.copy(
                            status = DownloadStatus.Downloading(
                                progress = progress,
                                etaSeconds = eta,
                                statusLine = line.ifBlank { "Downloading..." },
                                metadata = metadata
                            )
                        )
                    } else current
                }
            }

            result.fold(
                onSuccess = { file ->
                    _uiState.update {
                        it.copy(
                            status = DownloadStatus.Completed(
                                file = file,
                                type = state.selectedType,
                                title = file.nameWithoutExtension
                            ),
                            userMessage = "Saved to Downloads: ${file.name}",
                            requiresVerification = false
                        )
                    }
                    refreshDownloads()
                },
                onFailure = { error ->
                    val isBot = error is BotVerificationException || (error.message?.contains("Sign in to confirm you're not a bot", true) == true)
                    val errorMsg = error.message ?: "Download failed. Please check connection and URL."
                    _uiState.update {
                        it.copy(
                            status = DownloadStatus.Failed(errorMsg),
                            requiresVerification = isBot
                        )
                    }
                }
            )
        }
    }

    fun cancelDownload() {
        activeProcessId?.let { id ->
            repository.cancelDownload(id)
        }
        downloadJob?.cancel()
        downloadJob = null
        activeProcessId = null
        _uiState.update {
            it.copy(
                status = DownloadStatus.Idle,
                userMessage = "Download canceled"
            )
        }
    }

    fun deleteDownloadedFile(item: DownloadedFile) {
        viewModelScope.launch {
            val success = repository.deleteDownloadedFile(item.file)
            if (success) {
                _uiState.update { current ->
                    current.copy(
                        downloads = current.downloads.filter { it.id != item.id },
                        userMessage = "Deleted ${item.title}"
                    )
                }
            } else {
                _uiState.update { it.copy(userMessage = "Failed to delete file") }
            }
        }
    }

    fun updateEngine() {
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdatingEngine = true) }
            val result = repository.updateEngine()
            result.fold(
                onSuccess = { msg ->
                    val newVersion = repository.getEngineVersion()
                    _uiState.update {
                        it.copy(
                            isUpdatingEngine = false,
                            engineVersion = newVersion ?: it.engineVersion,
                            userMessage = msg
                        )
                    }
                },
                onFailure = { err ->
                    _uiState.update {
                        it.copy(
                            isUpdatingEngine = false,
                            userMessage = err.message ?: "Update check failed"
                        )
                    }
                }
            )
        }
    }

    fun openMediaFile(context: Context, file: File, type: MediaType) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
            val mimeType = if (type == MediaType.VIDEO_MP4) "video/*" else "audio/*"
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open with"))
        } catch (e: Exception) {
            Toast.makeText(context, "No app available to play this file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun shareMediaFile(context: Context, file: File, type: MediaType) {
        try {
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.provider",
                file
            )
            val mimeType = if (type == MediaType.VIDEO_MP4) "video/mp4" else "audio/mpeg"
            val intent = Intent(Intent.ACTION_SEND).apply {
                setType(mimeType)
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Share Media"))
        } catch (e: Exception) {
            Toast.makeText(context, "Could not share file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun openDownloadsFolder(context: Context) {
        try {
            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val pubDir = repository.publicDownloadsDir
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", pubDir)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                Toast.makeText(context, "Files are saved in your Downloads folder (/Download)", Toast.LENGTH_LONG).show()
            }
        }
    }
}
