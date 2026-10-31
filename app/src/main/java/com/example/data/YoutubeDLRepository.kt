package com.example.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.data.model.DiscoveredMediaCandidate
import com.example.data.model.DownloadedFile
import com.example.data.model.MediaMetadata
import com.example.data.model.MediaType
import com.example.data.model.SourceProtocol
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.ConcurrentHashMap

class BotVerificationException(message: String) : Exception(message)

class YoutubeDLRepository(private val context: Context) {

    val cookieRepo = CookieRepository(context)
    private val activeProcesses = ConcurrentHashMap.newKeySet<String>()

    // Dedicated internal staging directory where yt-dlp, direct downloader and ffmpeg have POSIX r/w access
    val stagingDir: File
        get() {
            val dir = File(context.cacheDir, "streamrip_staging")
            if (!dir.exists()) {
                dir.mkdirs()
            }
            return dir
        }

    // Public system Downloads folder (/storage/emulated/0/Download)
    val publicDownloadsDir: File
        get() = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)

    init {
        migrateOldAppFiles()
    }

    fun getEngineVersion(): String? {
        return try {
            YoutubeDL.getInstance().version(context)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun updateEngine(): Result<String> = withContext(Dispatchers.IO) {
        try {
            val status = YoutubeDL.getInstance().updateYoutubeDL(context)
            val version = YoutubeDL.getInstance().version(context) ?: "latest"
            val statusText = when (status) {
                YoutubeDL.UpdateStatus.DONE -> "Successfully updated yt-dlp to $version"
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp is already up to date ($version)"
                else -> "yt-dlp core synchronized: $version"
            }
            Log.i("YoutubeDLRepo", statusText)
            Result.success(statusText)
        } catch (e: Exception) {
            Log.e("YoutubeDLRepo", "Update failed", e)
            val currentVer = getEngineVersion() ?: "bundled"
            Result.failure(Exception("Could not download update: ${e.message ?: "Network error"}. Current version: $currentVer"))
        }
    }

    private fun isYouTubeUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("youtube.com") || lower.contains("youtu.be")
    }

    private fun shouldRetryWithAlternativeClient(errorMsg: String): Boolean {
        val lower = errorMsg.lowercase()
        return lower.contains("403") ||
                lower.contains("forbidden") ||
                lower.contains("bot") ||
                lower.contains("confirm you're not a bot") ||
                lower.contains("sign in to confirm") ||
                lower.contains("unable to download video data") ||
                lower.contains("http error") ||
                lower.contains("private video")
    }

    suspend fun fetchMetadata(url: String): Result<MediaMetadata> = withContext(Dispatchers.IO) {
        val isYt = isYouTubeUrl(url)
        val hasCookies = cookieRepo.hasCookies()
        val clientCandidates = if (isYt) {
            if (hasCookies) {
                listOf(
                    "",
                    "youtube:player_client=web,default",
                    "youtube:player_client=ios,android",
                    "youtube:player_client=mweb"
                )
            } else {
                listOf(
                    "youtube:player_client=ios,android",
                    "youtube:player_client=android",
                    "youtube:player_client=ios",
                    "youtube:player_client=mweb,android",
                    ""
                )
            }
        } else {
            listOf("")
        }

        var lastException: Throwable? = null

        for (clientArg in clientCandidates) {
            try {
                val request = YoutubeDLRequest(url).apply {
                    addOption("--no-playlist")
                    addOption("--no-update")
                    addOption("--no-check-certificates")
                    addOption("--geo-bypass")
                    addOption("--socket-timeout", "15")
                    // Do NOT set custom browser User-Agent for YouTube URLs, as it breaks
                    // yt-dlp's internal client signatures (iOS/Android) and triggers HTTP 403 Forbidden.
                    if (!isYt) {
                        addOption("--user-agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
                    }
                    if (clientArg.isNotBlank()) {
                        addOption("--extractor-args", clientArg)
                    }
                    cookieRepo.getCookiesFilePath()?.let { cookiePath ->
                        addOption("--cookies", cookiePath)
                    }
                    addOption("--dump-single-json")
                }

                val info = YoutubeDL.getInstance().getInfo(request)

                // Extract candidate formats
                val candidateList = mutableListOf<DiscoveredMediaCandidate>()
                val rawFormats = info.formats ?: emptyList()

                for ((idx, fmt) in rawFormats.withIndex()) {
                    val streamUrl = fmt.url ?: fmt.manifestUrl ?: continue
                    val isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true) || fmt.manifestUrl?.contains(".m3u8", ignoreCase = true) == true
                    val isMpd = streamUrl.contains(".mpd", ignoreCase = true) || fmt.manifestUrl?.contains(".mpd", ignoreCase = true) == true
                    val protocol = when {
                        isM3u8 -> SourceProtocol.HLS
                        isMpd  -> SourceProtocol.DASH
                        else   -> SourceProtocol.DIRECT_HTTP
                    }

                    val fmtExt = fmt.ext
                    val fmtFormatNote = fmt.formatNote
                    val fmtFormatId = fmt.formatId
                    val fmtVcodec = fmt.vcodec
                    val fmtAcodec = fmt.acodec

                    val resolution = if (fmt.width > 0 && fmt.height > 0) {
                        "${fmt.width}x${fmt.height}"
                    } else if (!fmtFormatNote.isNullOrBlank()) {
                        fmtFormatNote
                    } else null

                    val label = buildString {
                        if (fmt.height > 0) append("${fmt.height}p ")
                        if (!fmtFormatNote.isNullOrBlank() && fmtFormatNote != "${fmt.height}p") {
                            append("($fmtFormatNote) ")
                        }
                        if (!fmtExt.isNullOrBlank()) append("[${fmtExt.uppercase()}] ")
                        if (protocol == SourceProtocol.HLS) append("HLS")
                        if (protocol == SourceProtocol.DASH) append("DASH")
                    }.trim().ifBlank { fmt.format ?: "Format ${fmtFormatId ?: idx}" }

                    val size = if (fmt.fileSize > 0) fmt.fileSize else if (fmt.fileSizeApproximate > 0) fmt.fileSizeApproximate else null

                    val headers = mutableMapOf<String, String>()
                    info.httpHeaders?.let { headers.putAll(it) }
                    fmt.httpHeaders?.let { headers.putAll(it) }
                    if (!headers.containsKey("Referer") && !info.webpageUrl.isNullOrBlank()) {
                        headers["Referer"] = info.webpageUrl!!
                    }

                    candidateList.add(
                        DiscoveredMediaCandidate(
                            id = fmtFormatId ?: "fmt_$idx",
                            url = streamUrl,
                            parentPageUrl = info.webpageUrl ?: url,
                            protocol = protocol,
                            mimeType = "video/${fmtExt ?: "mp4"}",
                            label = label,
                            resolution = resolution,
                            formatId = fmtFormatId,
                            ext = fmtExt,
                            bitrateKbps = if (fmt.tbr > 0) fmt.tbr.toLong() else null,
                            estimatedSizeBytes = size,
                            requiredHeaders = headers,
                            isAdaptive = fmt.manifestUrl != null || (fmtVcodec != null && (fmtAcodec == null || fmtAcodec == "none")),
                            isAudioOnly = fmtVcodec == "none" || (fmtAcodec != null && (fmtVcodec == null || fmtVcodec == "none"))
                        )
                    )
                }

                // If no formats were nested, check top-level URL
                if (candidateList.isEmpty() && (!info.url.isNullOrBlank() || !info.manifestUrl.isNullOrBlank())) {
                    val streamUrl = info.url ?: info.manifestUrl ?: url
                    val isM3u8 = streamUrl.contains(".m3u8")
                    val isMpd = streamUrl.contains(".mpd")
                    val protocol = when {
                        isM3u8 -> SourceProtocol.HLS
                        isMpd -> SourceProtocol.DASH
                        else -> SourceProtocol.DIRECT_HTTP
                    }
                    candidateList.add(
                        DiscoveredMediaCandidate(
                            id = info.formatId ?: "default",
                            url = streamUrl,
                            parentPageUrl = info.webpageUrl ?: url,
                            protocol = protocol,
                            label = info.format ?: "Default Stream",
                            resolution = if (info.height > 0) "${info.height}p" else null,
                            formatId = info.formatId,
                            ext = info.ext,
                            requiredHeaders = info.httpHeaders ?: emptyMap()
                        )
                    )
                }

                // Sort candidates: prefer higher resolution video streams first, prioritizing crisp 1080p
                // clean streams over massive 4K streams with diagnostic text overlays (e.g. Akamai DASH)
                val sortedCandidates = candidateList.sortedWith(
                    compareByDescending<DiscoveredMediaCandidate> { !it.isAudioOnly }
                        .thenByDescending {
                            val h = it.resolution?.filter { ch -> ch.isDigit() }?.toIntOrNull() ?: 0
                            if (h > 1080) 1000 else h
                        }
                        .thenByDescending { it.estimatedSizeBytes ?: 0L }
                )

                val metadata = MediaMetadata(
                    title = info.title ?: "Media Title",
                    uploader = info.uploader ?: info.uploaderId ?: "Creator",
                    durationSeconds = info.duration,
                    thumbnailUrl = info.thumbnail,
                    webpageUrl = info.webpageUrl ?: url,
                    viewCount = info.viewCount,
                    candidates = sortedCandidates,
                    selectedCandidateId = sortedCandidates.firstOrNull()?.id,
                    sourceProtocol = sortedCandidates.firstOrNull()?.protocol
                )
                return@withContext Result.success(metadata)
            } catch (e: Throwable) {
                lastException = e
                val msg = e.message ?: ""
                if (isYt && shouldRetryWithAlternativeClient(msg)) {
                    Log.w("YoutubeDLRepo", "Metadata strategy ($clientArg) hit challenge: $msg. Trying fallback client...")
                    delay(100)
                    continue
                }
                if (!isYt) {
                    break
                }
            }
        }

        val finalError = lastException ?: Exception("Failed to fetch media details")
        Result.failure(processException(finalError))
    }

    suspend fun downloadMedia(
        url: String,
        type: MediaType,
        videoQuality: String,
        audioQuality: String,
        processId: String,
        candidate: DiscoveredMediaCandidate? = null,
        onProgress: (Float, Long, String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val targetUrl = if (candidate != null && candidate.protocol != SourceProtocol.DIRECT_HTTP && candidate.url.startsWith("http")) {
            candidate.url
        } else {
            url
        }

        val isYt = isYouTubeUrl(targetUrl)
        val hasCookies = cookieRepo.hasCookies()
        val clientStrategies = if (isYt) {
            if (hasCookies) {
                listOf(
                    "",
                    "youtube:player_client=web,default",
                    "youtube:player_client=ios,android",
                    "youtube:player_client=mweb"
                )
            } else {
                listOf(
                    "youtube:player_client=ios,android",
                    "youtube:player_client=android",
                    "youtube:player_client=ios",
                    "youtube:player_client=mweb,android",
                    ""
                )
            }
        } else {
            listOf("")
        }

        var lastException: Throwable? = null

        for ((index, clientArg) in clientStrategies.withIndex()) {
            val attemptProcessId = "${processId}_att$index"
            try {
                activeProcesses.add(attemptProcessId)
                return@withContext executeDownloadAttempt(
                    url = targetUrl,
                    type = type,
                    videoQuality = videoQuality,
                    audioQuality = audioQuality,
                    processId = attemptProcessId,
                    clientArg = clientArg,
                    candidate = candidate,
                    onProgress = onProgress
                )
            } catch (e: Exception) {
                lastException = e
                val msg = e.message ?: ""
                Log.w("YoutubeDLRepo", "Download strategy ($clientArg) encountered: $msg")
                if (isYt && shouldRetryWithAlternativeClient(msg)) {
                    // Small delay to allow Linux OS descriptors and native streams to cleanly unlink
                    delay(150)
                    continue
                } else if (!isYt) {
                    break
                }
            } finally {
                activeProcesses.remove(attemptProcessId)
            }
        }

        Result.failure(processException(lastException ?: Exception("Download failed.")))
    }

    private fun executeDownloadAttempt(
        url: String,
        type: MediaType,
        videoQuality: String,
        audioQuality: String,
        processId: String,
        clientArg: String,
        candidate: DiscoveredMediaCandidate? = null,
        onProgress: (Float, Long, String) -> Unit
    ): Result<File> {
        val targetDir = stagingDir
        val existingFiles = targetDir.listFiles()?.map { it.absolutePath }?.toSet() ?: emptySet()
        val beforeTime = System.currentTimeMillis() - 3000

        val request = YoutubeDLRequest(url)
        request.addOption("--no-playlist")
        request.addOption("--no-mtime")
        request.addOption("--no-update")
        request.addOption("--no-check-certificates")
        request.addOption("--geo-bypass")
        request.addOption("--socket-timeout", "15")
        request.addOption("--fragment-retries", "5")
        request.addOption("--extractor-retries", "3")

        // CRITICAL: DO NOT add custom --user-agent for YouTube URLs!
        // yt-dlp needs its native matching User-Agent for iOS and Android player clients.
        // A desktop/mobile Chrome User-Agent causes YouTube CDN to return HTTP 403 Forbidden.
        if (!isYouTubeUrl(url)) {
            request.addOption("--user-agent", "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36")
        }

        if (clientArg.isNotBlank()) {
            request.addOption("--extractor-args", clientArg)
        }

        // Forward candidate headers (Referer, Origin, User-Agent)
        candidate?.requiredHeaders?.forEach { (headerName, headerValue) ->
            if (headerName.isNotBlank() && headerValue.isNotBlank()) {
                request.addOption("--add-header", "$headerName:$headerValue")
            }
        }

        val isHls = candidate?.protocol == SourceProtocol.HLS || url.contains(".m3u8", ignoreCase = true)
        val isDash = candidate?.protocol == SourceProtocol.DASH || url.contains(".mpd", ignoreCase = true)

        // HLS & DASH download options: accelerated concurrent fragments and clean ffmpeg logging
        if (isHls || isDash) {
            request.addOption("--concurrent-fragments", "5")
            request.addOption("--fixup", "detect_or_warn")
            request.addOption("--downloader-args", "ffmpeg:-nostats -loglevel warning")
        }

        // Inject cookies file if available
        cookieRepo.getCookiesFilePath()?.let { cookiePath ->
            request.addOption("--cookies", cookiePath)
        }

        val outputPattern = "${targetDir.absolutePath}/%(title).180B.%(ext)s"
        request.addOption("-o", outputPattern)

        when (type) {
            MediaType.VIDEO_MP4 -> {
                if (candidate?.formatId != null && candidate.formatId.isNotBlank() && !isHls && !isDash) {
                    // Use candidate format id directly with audio fallback
                    request.addOption("-f", "${candidate.formatId}+ba/b/${candidate.formatId}/best")
                } else if (isHls || isDash) {
                    // For HLS/DASH streams, ensure we choose clean streams (<=1080p) avoiding slow 4K diagnostic streams with burnt-in text
                    val formatSelector = when (videoQuality) {
                        "1080p" -> "bv*[height<=?1080]+ba/b[height<=?1080]/bv*+ba/best"
                        "720p"  -> "bv*[height<=?720]+ba/b[height<=?720]/bv*+ba/best"
                        "480p"  -> "bv*[height<=?480]+ba/b[height<=?480]/bv*+ba/best"
                        "360p"  -> "bv*[height<=?360]+ba/b[height<=?360]/bv*+ba/best"
                        else    -> "bv*[height<=?1080]+ba/b[height<=?1080]/bv*+ba/best"
                    }
                    request.addOption("-f", formatSelector)
                } else {
                    val formatSelector = when (videoQuality) {
                        "1080p" -> "bv*[height<=?1080]+ba/b[height<=?1080]/bv*+ba/b/best"
                        "720p"  -> "bv*[height<=?720]+ba/b[height<=?720]/bv*+ba/b/best"
                        "480p"  -> "bv*[height<=?480]+ba/b[height<=?480]/bv*+ba/b/best"
                        "360p"  -> "bv*[height<=?360]+ba/b[height<=?360]/bv*+ba/b/best"
                        else    -> "bv*[height<=?1080]+ba/b[height<=?1080]/bv*+ba/b/best"
                    }
                    request.addOption("-f", formatSelector)
                }
                request.addOption("-S", "res,ext:mp4:m4a")
                request.addOption("--remux-video", "mp4")
                request.addOption("--merge-output-format", "mp4")
            }
            MediaType.AUDIO_MP3 -> {
                request.addOption("-f", "ba/b/best")
                request.addOption("-x")
                request.addOption("--audio-format", "mp3")
                val qualityVal = when (audioQuality) {
                    "320k" -> "0"
                    "256k" -> "2"
                    "192k" -> "4"
                    "128k" -> "5"
                    else -> "0"
                }
                request.addOption("--audio-quality", qualityVal)
            }
        }

        val response = YoutubeDL.getInstance().execute(
            request = request,
            processId = processId
        ) { progress, eta, line ->
            onProgress(progress, eta, line)
        }

        Log.d("YoutubeDLRepo", "Download completed with exit code ${response.exitCode}")

        val allFiles = targetDir.listFiles() ?: emptyArray()
        val expectedExtensions = when (type) {
            MediaType.VIDEO_MP4 -> listOf("mp4", "mkv", "webm", "mov", "ts")
            MediaType.AUDIO_MP3 -> listOf("mp3", "m4a", "opus", "aac", "ogg")
        }

        val newlyCreated = allFiles
            .filter { file -> expectedExtensions.any { ext -> file.extension.equals(ext, ignoreCase = true) } }
            .sortedByDescending { it.lastModified() }
            .firstOrNull { it.lastModified() >= beforeTime || !existingFiles.contains(it.absolutePath) }
            ?: allFiles.filter { file -> expectedExtensions.any { ext -> file.extension.equals(ext, ignoreCase = true) } }
                .maxByOrNull { it.lastModified() }

        return if (newlyCreated != null && newlyCreated.exists() && newlyCreated.length() > 0) {
            saveToPublicDownloads(newlyCreated, type)
        } else {
            Result.failure(Exception("File was converted but output file could not be accessed."))
        }
    }

    fun saveToPublicDownloads(stagingFile: File, type: MediaType): Result<File> {
        try {
            val fileName = sanitizeFileName(stagingFile.name)
            val mimeType = when (stagingFile.extension.lowercase()) {
                "mp4" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "mov" -> "video/quicktime"
                "mp3" -> "audio/mpeg"
                "m4a" -> "audio/mp4"
                "opus" -> "audio/opus"
                "aac" -> "audio/aac"
                "ogg" -> "audio/ogg"
                else -> if (type == MediaType.VIDEO_MP4) "video/mp4" else "audio/mpeg"
            }
            val pubDir = publicDownloadsDir
            if (!pubDir.exists()) {
                pubDir.mkdirs()
            }

            var savedFile: File? = null

            // On Android 10+ (API 29+), publish to MediaStore.Downloads
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val uri: Uri? = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                if (uri != null) {
                    try {
                        resolver.openOutputStream(uri, "w")?.use { out ->
                            FileInputStream(stagingFile).use { input ->
                                input.copyTo(out)
                            }
                        }

                        contentValues.clear()
                        contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, contentValues, null, null)

                        val candidate = File(pubDir, fileName)
                        savedFile = if (candidate.exists()) candidate else File(pubDir, fileName)
                    } catch (e: Exception) {
                        Log.e("YoutubeDLRepo", "Failed to write via MediaStore: ${e.message}", e)
                        try {
                            resolver.delete(uri, null, null)
                        } catch (_: Exception) {}
                    }
                }
            }

            // Direct file write fallback to /storage/emulated/0/Download
            if (savedFile == null || !savedFile.exists()) {
                val directTarget = File(pubDir, fileName)
                stagingFile.copyTo(directTarget, overwrite = true)
                savedFile = directTarget
            }

            // Index in Android media scanner
            MediaScannerConnection.scanFile(
                context,
                arrayOf(savedFile.absolutePath),
                arrayOf(mimeType)
            ) { path, scannedUri ->
                Log.i("YoutubeDLRepo", "Media catalog indexed: $path -> $scannedUri")
            }

            if (stagingFile.absolutePath != savedFile.absolutePath) {
                stagingFile.delete()
            }

            return Result.success(savedFile)
        } catch (e: Exception) {
            Log.e("YoutubeDLRepo", "Failed to save file to public Downloads folder: ${e.message}", e)
            return Result.success(stagingFile)
        }
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun migrateOldAppFiles() {
        try {
            val oldDir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "StreamRip")
            if (oldDir.exists() && oldDir.isDirectory) {
                val files = oldDir.listFiles() ?: return
                val pubDir = publicDownloadsDir
                if (!pubDir.exists()) {
                    pubDir.mkdirs()
                }

                for (f in files) {
                    if (f.isFile && f.length() > 0) {
                        val target = File(pubDir, f.name)
                        if (!target.exists()) {
                            f.copyTo(target, overwrite = true)
                            val mime = if (f.extension.equals("mp3", true)) "audio/mpeg" else "video/mp4"
                            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mime), null)
                        }
                        f.delete()
                    } else if (f.isFile) {
                        f.delete()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("YoutubeDLRepo", "Migration skipped: ${e.message}")
        }
    }

    private fun processException(e: Throwable): Exception {
        val raw = e.message ?: return Exception("Operation failed")
        if (raw.contains("InterruptedIOException", ignoreCase = true) ||
            raw.contains("read interrupted by close", ignoreCase = true) ||
            raw.contains("StreamGobbler", ignoreCase = true) ||
            raw.contains("StreamProcessExtractor", ignoreCase = true)
        ) {
            return Exception("Download process stream was interrupted or restarted. Please retry.")
        }
        if (raw.contains("Sign in to confirm you're not a bot", ignoreCase = true)) {
            return BotVerificationException("YouTube requires account verification for this media. Sign in or add cookies using the key icon.")
        }
        if (raw.contains("403", ignoreCase = true) || raw.contains("Forbidden", ignoreCase = true)) {
            return Exception("HTTP 403 Forbidden: Host blocked direct stream. Tap 'Update Core' or use 'Add Cookies / Login' to authenticate.")
        }
        val lines = raw.lines()
        val errorLine = lines.firstOrNull { it.startsWith("ERROR:") }
            ?: lines.firstOrNull { it.contains("unavailable", true) || it.contains("Requested format", true) || it.contains("Unsupported URL", true) }
            ?: lines.firstOrNull { it.isNotBlank() }
            ?: "Download failed. Please check network and URL."

        return Exception(errorLine.removePrefix("ERROR:").trim())
    }

    fun cancelDownload(processId: String): Boolean {
        return try {
            var anyDestroyed = false
            val matched = activeProcesses.filter { it.startsWith(processId) || it == processId }
            for (id in matched) {
                try {
                    YoutubeDL.getInstance().destroyProcessById(id)
                    anyDestroyed = true
                } catch (_: Exception) {}
                activeProcesses.remove(id)
            }
            if (!anyDestroyed) {
                try {
                    anyDestroyed = YoutubeDL.getInstance().destroyProcessById(processId)
                } catch (_: Exception) {}
            }
            anyDestroyed
        } catch (e: Exception) {
            false
        }
    }

    suspend fun listDownloadedFiles(): List<DownloadedFile> = withContext(Dispatchers.IO) {
        migrateOldAppFiles()

        val results = mutableListOf<DownloadedFile>()
        val seenPaths = mutableSetOf<String>()

        val pubDir = publicDownloadsDir
        if (pubDir.exists()) {
            val files = pubDir.listFiles() ?: emptyArray()
            files
                .filter { it.isFile && (it.extension.equals("mp4", true) || it.extension.equals("mp3", true) || it.extension.equals("m4a", true)) }
                .forEach { file ->
                    seenPaths.add(file.absolutePath)
                    val type = if (file.extension.equals("mp3", true) || file.extension.equals("m4a", true)) {
                        MediaType.AUDIO_MP3
                    } else {
                        MediaType.VIDEO_MP4
                    }
                    results.add(
                        DownloadedFile(
                            id = file.absolutePath,
                            title = file.nameWithoutExtension,
                            file = file,
                            type = type,
                            sizeBytes = file.length(),
                            timestamp = file.lastModified()
                        )
                    )
                }
        }

        val stagingFiles = stagingDir.listFiles() ?: emptyArray()
        stagingFiles
            .filter { it.isFile && !seenPaths.contains(it.absolutePath) && (it.extension.equals("mp4", true) || it.extension.equals("mp3", true)) }
            .forEach { file ->
                val type = if (file.extension.equals("mp3", true)) MediaType.AUDIO_MP3 else MediaType.VIDEO_MP4
                results.add(
                    DownloadedFile(
                        id = file.absolutePath,
                        title = file.nameWithoutExtension,
                        file = file,
                        type = type,
                        sizeBytes = file.length(),
                        timestamp = file.lastModified()
                    )
                )
            }

        results.sortedByDescending { it.timestamp }
    }

    suspend fun deleteDownloadedFile(file: File): Boolean = withContext(Dispatchers.IO) {
        try {
            var deleted = false
            if (file.exists()) {
                deleted = file.delete()
            } else {
                deleted = true
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    context.contentResolver.delete(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                        arrayOf(file.name)
                    )
                } catch (e: Exception) {
                    Log.w("YoutubeDLRepo", "MediaStore delete error: ${e.message}")
                }
            }

            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                null
            ) { path, _ ->
                Log.d("YoutubeDLRepo", "Media record cleaned: $path")
            }

            deleted
        } catch (e: Exception) {
            Log.e("YoutubeDLRepo", "Error deleting file: ${file.name}", e)
            false
        }
    }
}
