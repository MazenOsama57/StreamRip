package com.example.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.example.data.model.DownloadedFile
import com.example.data.model.MediaMetadata
import com.example.data.model.MediaType
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class BotVerificationException(message: String) : Exception(message)

class YoutubeDLRepository(private val context: Context) {

    val cookieRepo = CookieRepository(context)

    // Dedicated internal staging directory where yt-dlp and ffmpeg have 100% POSIX r/w access
    // to build, merge, and convert media without leaving partial .part files in public storage
    private val stagingDir: File
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
        // Automatically migrate any legacy files from the old private app directory to the public Downloads folder
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
                YoutubeDL.UpdateStatus.DONE -> "Successfully updated yt-dlp to latest version ($version)"
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp is already at latest version ($version)"
                else -> "yt-dlp synchronized: $version"
            }
            Log.i("YoutubeDLRepo", statusText)
            Result.success(statusText)
        } catch (e: Exception) {
            Log.e("YoutubeDLRepo", "Update failed", e)
            Result.failure(e)
        }
    }

    suspend fun fetchMetadata(url: String): Result<MediaMetadata> = withContext(Dispatchers.IO) {
        val clientCandidates = listOf(
            "youtube:player_client=default,ios",
            "youtube:player_client=default,-web",
            "youtube:player_client=ios",
            "youtube:player_client=tv_embedded,web_embedded"
        )

        var lastException: Throwable? = null

        for (clientArg in clientCandidates) {
            try {
                val request = YoutubeDLRequest(url).apply {
                    addOption("--no-playlist")
                    addOption("--no-update")
                    addOption("--no-check-certificates")
                    addOption("--extractor-args", clientArg)
                    cookieRepo.getCookiesFilePath()?.let { cookiePath ->
                        addOption("--cookies", cookiePath)
                    }
                    addOption("--dump-single-json")
                }

                val info = YoutubeDL.getInstance().getInfo(request)
                val metadata = MediaMetadata(
                    title = info.title ?: "Unknown Media",
                    uploader = info.uploader ?: info.uploaderId ?: "Unknown Creator",
                    durationSeconds = info.duration,
                    thumbnailUrl = info.thumbnail,
                    webpageUrl = info.webpageUrl ?: url,
                    viewCount = info.viewCount
                )
                return@withContext Result.success(metadata)
            } catch (e: Throwable) {
                lastException = e
                val msg = e.message ?: ""
                if (msg.contains("Sign in to confirm you're not a bot", ignoreCase = true)) {
                    Log.w("YoutubeDLRepo", "Bot detection hit with $clientArg, attempting alternative...")
                    continue
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
        onProgress: (Float, Long, String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val clientStrategies = listOf(
            "youtube:player_client=default,ios",
            "youtube:player_client=default,-web",
            "youtube:player_client=ios",
            "youtube:player_client=tv_embedded,web_embedded"
        )

        var lastException: Throwable? = null

        for (clientArg in clientStrategies) {
            try {
                return@withContext executeDownloadAttempt(
                    url = url,
                    type = type,
                    videoQuality = videoQuality,
                    audioQuality = audioQuality,
                    processId = processId,
                    clientArg = clientArg,
                    onProgress = onProgress
                )
            } catch (e: Exception) {
                lastException = e
                val msg = e.message ?: ""
                if (msg.contains("Sign in to confirm you're not a bot", ignoreCase = true)) {
                    Log.w("YoutubeDLRepo", "Client $clientArg hit bot check, trying alternative...")
                    continue
                } else if (msg.contains("older than 90 days", ignoreCase = true)) {
                    try {
                        updateEngine()
                    } catch (_: Exception) {}
                    continue
                } else {
                    return@withContext Result.failure(processException(e))
                }
            }
        }

        Result.failure(processException(lastException ?: Exception("Download failed.")))
    }

    private suspend fun executeDownloadAttempt(
        url: String,
        type: MediaType,
        videoQuality: String,
        audioQuality: String,
        processId: String,
        clientArg: String,
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
        request.addOption("--extractor-args", clientArg)

        // Inject cookies file if available
        cookieRepo.getCookiesFilePath()?.let { cookiePath ->
            request.addOption("--cookies", cookiePath)
        }

        val outputPattern = "${targetDir.absolutePath}/%(title).180B.%(ext)s"
        request.addOption("-o", outputPattern)

        when (type) {
            MediaType.VIDEO_MP4 -> {
                // Highly resilient format selector that never fails with "requested format not available":
                // Tries requested resolution, then best video+audio, then single best stream
                val formatSelector = when (videoQuality) {
                    "1080p" -> "bv*[height<=?1080]+ba/b[height<=?1080]/bv*+ba/b/best"
                    "720p"  -> "bv*[height<=?720]+ba/b[height<=?720]/bv*+ba/b/best"
                    "480p"  -> "bv*[height<=?480]+ba/b[height<=?480]/bv*+ba/b/best"
                    "360p"  -> "bv*[height<=?360]+ba/b[height<=?360]/bv*+ba/b/best"
                    else    -> "b/bv*+ba/best"
                }
                request.addOption("-f", formatSelector)
                request.addOption("-S", "res,ext:mp4:m4a")
                request.addOption("--merge-output-format", "mp4")
                request.addOption("--recode-video", "mp4")
            }
            MediaType.AUDIO_MP3 -> {
                // Official yt-dlp audio extraction with resilient fallback:
                // ba/b/best ensures streams without separate audio stream also convert cleanly
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

        Log.d("YoutubeDLRepo", "Download finished with exit code ${response.exitCode}")

        val allFiles = targetDir.listFiles() ?: emptyArray()
        val targetExt = if (type == MediaType.VIDEO_MP4) "mp4" else "mp3"

        val newlyCreated = allFiles
            .filter { it.extension.equals(targetExt, ignoreCase = true) }
            .sortedByDescending { it.lastModified() }
            .firstOrNull { it.lastModified() >= beforeTime || !existingFiles.contains(it.absolutePath) }
            ?: allFiles.filter { it.extension.equals(targetExt, ignoreCase = true) }
                .maxByOrNull { it.lastModified() }

        return if (newlyCreated != null && newlyCreated.exists() && newlyCreated.length() > 0) {
            // Save the finished media file into the public Downloads folder so other apps can access it!
            saveToPublicDownloads(newlyCreated, type)
        } else {
            Result.failure(Exception("File conversion completed, but destination file could not be located."))
        }
    }

    /**
     * Saves the downloaded media file into the public Downloads directory (/storage/emulated/0/Download/)
     * using MediaStore.Downloads (on Android 10+) and direct file copy with MediaScannerConnection.
     * This ensures the file is immediately accessible to file managers, music players, video players,
     * and external apps.
     */
    suspend fun saveToPublicDownloads(stagingFile: File, type: MediaType): Result<File> = withContext(Dispatchers.IO) {
        try {
            val fileName = sanitizeFileName(stagingFile.name)
            val mimeType = if (type == MediaType.VIDEO_MP4) "video/mp4" else "audio/mpeg"
            val pubDir = publicDownloadsDir
            if (!pubDir.exists()) {
                pubDir.mkdirs()
            }

            var savedFile: File? = null

            // 1. On Android 10+ (API 29+), publish to MediaStore.Downloads
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

                        // Check the path created in public Downloads
                        val candidate = File(pubDir, fileName)
                        savedFile = if (candidate.exists()) candidate else File(pubDir, fileName)

                        Log.i("YoutubeDLRepo", "Successfully published to MediaStore.Downloads: $uri -> ${savedFile.absolutePath}")
                    } catch (e: Exception) {
                        Log.e("YoutubeDLRepo", "Failed to write via MediaStore.Downloads: ${e.message}", e)
                        try {
                            resolver.delete(uri, null, null)
                        } catch (_: Exception) {}
                    }
                }
            }

            // 2. Direct file write to /storage/emulated/0/Download if not already written or on older OS
            if (savedFile == null || !savedFile.exists()) {
                val directTarget = File(pubDir, fileName)
                stagingFile.copyTo(directTarget, overwrite = true)
                savedFile = directTarget
            }

            // 3. Scan the public file into the Android media catalog so music players & gallery see it immediately
            MediaScannerConnection.scanFile(
                context,
                arrayOf(savedFile.absolutePath),
                arrayOf(mimeType)
            ) { path, scannedUri ->
                Log.i("YoutubeDLRepo", "Scanned media into Android system: $path -> $scannedUri")
            }

            // 4. Clean up staging file so internal app cache is freed
            if (stagingFile.absolutePath != savedFile.absolutePath) {
                stagingFile.delete()
            }

            Result.success(savedFile)
        } catch (e: Exception) {
            Log.e("YoutubeDLRepo", "Failed to save file to public Downloads folder: ${e.message}", e)
            // If copying to public Downloads had a permission problem, return staging file as fallback
            Result.success(stagingFile)
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
            Log.w("YoutubeDLRepo", "Migration of old app files skipped: ${e.message}")
        }
    }

    private fun processException(e: Throwable): Exception {
        val raw = e.message ?: return Exception("Operation failed")
        if (raw.contains("Sign in to confirm you're not a bot", ignoreCase = true)) {
            return BotVerificationException("YouTube requires verification for this video. Tap 'Sign in to YouTube' to authenticate cookies and proceed.")
        }
        val lines = raw.lines()
        val errorLine = lines.firstOrNull { it.startsWith("ERROR:") }
            ?: lines.firstOrNull { it.contains("unavailable", true) || it.contains("Requested format", true) || it.contains("Unsupported URL", true) }
            ?: lines.firstOrNull { it.isNotBlank() }
            ?: "Download failed. Please check your network connection and URL."

        return Exception(errorLine.removePrefix("ERROR:").trim())
    }

    fun cancelDownload(processId: String): Boolean {
        return try {
            YoutubeDL.getInstance().destroyProcessById(processId)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun listDownloadedFiles(): List<DownloadedFile> = withContext(Dispatchers.IO) {
        migrateOldAppFiles()

        val results = mutableListOf<DownloadedFile>()
        val seenPaths = mutableSetOf<String>()

        // 1. Scan public Downloads directory (/storage/emulated/0/Download)
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

        // 2. Also check stagingDir in case any download was kept there
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

            // Also clean up MediaStore index on Android 10+
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

            // Trigger scanner to remove from system catalog
            MediaScannerConnection.scanFile(
                context,
                arrayOf(file.absolutePath),
                null
            ) { path, _ ->
                Log.d("YoutubeDLRepo", "Cleaned up media record for $path")
            }

            deleted
        } catch (e: Exception) {
            Log.e("YoutubeDLRepo", "Error deleting file: ${file.name}", e)
            false
        }
    }
}
