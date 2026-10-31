package com.example.data

import android.content.Context
import android.util.Log
import com.example.data.model.DiscoveredMediaCandidate
import com.example.data.model.MediaType
import com.example.data.model.SourceProtocol
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class DirectMediaDownloader(
    private val context: Context,
    private val repository: YoutubeDLRepository,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    companion object {
        private const val TAG = "DirectMediaDownloader"
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
    }

    private val activeCalls = ConcurrentHashMap<String, Call>()

    fun cancel(processId: String): Boolean {
        val call = activeCalls.remove(processId)
        if (call != null) {
            try {
                call.cancel()
                Log.i(TAG, "Cancelled direct download call for process $processId")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Error cancelling call for $processId: ${e.message}")
            }
        }
        return false
    }

    suspend fun download(
        url: String,
        candidate: DiscoveredMediaCandidate?,
        type: MediaType,
        processId: String,
        onProgress: (Float, Long, String) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val targetUrl = candidate?.url?.takeIf { it.isNotBlank() } ?: url
        Log.i(TAG, "Initiating direct media download for $targetUrl [processId=$processId, type=$type]")

        val reqHeaders = mutableMapOf<String, String>()
        reqHeaders["User-Agent"] = BROWSER_USER_AGENT
        reqHeaders["Accept"] = "*/*"
        reqHeaders["Accept-Encoding"] = "identity"

        val reqBuilder = Request.Builder()
            .url(targetUrl)
            .get()
            .header("User-Agent", BROWSER_USER_AGENT)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity") // Disable compression to receive genuine Content-Length

        // Inject candidate headers (e.g. Referer, Origin, cookies)
        candidate?.requiredHeaders?.forEach { (headerName, headerValue) ->
            if (headerName.isNotBlank() && headerValue.isNotBlank()) {
                reqBuilder.header(headerName, headerValue)
                reqHeaders[headerName] = headerValue
            }
        }

        // Default Referer to parent page if not provided
        if (candidate?.requiredHeaders?.containsKey("Referer") != true && !candidate?.parentPageUrl.isNullOrBlank()) {
            reqBuilder.header("Referer", candidate!!.parentPageUrl)
            reqHeaders["Referer"] = candidate.parentPageUrl
        }

        DownloadLogger.logRequest(TAG, targetUrl, "GET", reqHeaders)

        val call = okHttpClient.newCall(reqBuilder.build())
        activeCalls[processId] = call

        var tempStagingFile: File? = null

        try {
            val response = call.execute()
            val respHeaders = response.headers.toMultimap().mapValues { it.value.joinToString(", ") }
            val code = response.code
            val msg = response.message

            DownloadLogger.logResponse(TAG, targetUrl, code, respHeaders, "Content-Length: ${response.header("Content-Length") ?: "unknown"}")

            if (!response.isSuccessful) {
                DownloadLogger.e(TAG, "HTTP $code $msg for $targetUrl", headers = respHeaders, responseCode = code, url = targetUrl)
                response.close()
                return@withContext Result.failure(
                    Exception("HTTP $code $msg from media server. [Engine: DirectMediaDownloader]")
                )
            }

            val body = response.body ?: run {
                DownloadLogger.e(TAG, "Empty body response for $targetUrl", responseCode = code, url = targetUrl)
                return@withContext Result.failure(
                    Exception("Empty response received from media server. [Engine: DirectMediaDownloader]")
                )
            }

            val contentLength = body.contentLength()
            val contentType = response.header("Content-Type")?.lowercase() ?: ""

            // Determine accurate file extension
            val detectedExt = when {
                candidate?.ext != null && candidate.ext.isNotBlank() -> candidate.ext
                contentType.contains("video/mp4") -> "mp4"
                contentType.contains("video/webm") -> "webm"
                contentType.contains("video/x-matroska") -> "mkv"
                contentType.contains("video/quicktime") -> "mov"
                contentType.contains("audio/mpeg") || contentType.contains("audio/mp3") -> "mp3"
                contentType.contains("audio/mp4") || contentType.contains("audio/m4a") -> "m4a"
                contentType.contains("audio/ogg") || contentType.contains("audio/opus") -> "opus"
                else -> {
                    val rawExt = targetUrl.substringBefore("?").substringBefore("#").substringAfterLast(".", "")
                    if (rawExt.length in 2..5 && rawExt.all { it.isLetterOrDigit() }) {
                        rawExt
                    } else if (type == MediaType.VIDEO_MP4) {
                        "mp4"
                    } else {
                        "mp3"
                    }
                }
            }

            val baseName = candidate?.label?.take(50)?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                ?: targetUrl.substringBefore("?").substringBefore("#").substringAfterLast("/").take(50).ifBlank { "media_${System.currentTimeMillis()}" }

            val cleanBaseName = baseName.substringBeforeLast(".")
            val stagingFile = File(repository.stagingDir, "${cleanBaseName}_${UUID.randomUUID().toString().take(6)}.$detectedExt")
            tempStagingFile = stagingFile

            val startTime = System.currentTimeMillis()
            var bytesReadSoFar = 0L
            var lastUpdateMs = 0L

            body.byteStream().use { input ->
                FileOutputStream(stagingFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes: Int
                    while (input.read(buffer).also { bytes = it } != -1) {
                        output.write(buffer, 0, bytes)
                        bytesReadSoFar += bytes

                        val now = System.currentTimeMillis()
                        if (now - lastUpdateMs > 100 || (contentLength > 0 && bytesReadSoFar == contentLength)) {
                            lastUpdateMs = now
                            val elapsedSec = ((now - startTime) / 1000.0).coerceAtLeast(0.05)
                            val speedBytesPerSec = (bytesReadSoFar / elapsedSec).toLong()
                            val speedKbPerSec = speedBytesPerSec / 1024.0
                            val speedMbPerSec = speedKbPerSec / 1024.0
                            val speedStr = if (speedMbPerSec >= 1.0) {
                                String.format("%.1f MB/s", speedMbPerSec)
                            } else {
                                String.format("%.0f KB/s", speedKbPerSec)
                            }

                            val progress = if (contentLength > 0) {
                                (bytesReadSoFar.toFloat() / contentLength.toFloat()) * 100f
                            } else {
                                50f
                            }

                            val etaSec = if (contentLength > 0 && speedBytesPerSec > 0) {
                                ((contentLength - bytesReadSoFar) / speedBytesPerSec).coerceAtLeast(0)
                            } else 0L

                            val readMb = bytesReadSoFar / (1024.0 * 1024.0)
                            val totalMb = if (contentLength > 0) contentLength / (1024.0 * 1024.0) else 0.0
                            val statusLine = if (contentLength > 0) {
                                String.format("%.1f / %.1f MB (%.1f%% at %s)", readMb, totalMb, progress, speedStr)
                            } else {
                                String.format("%.1f MB downloaded at %s", readMb, speedStr)
                            }

                            onProgress(progress, etaSec, statusLine)
                        }
                    }
                    output.flush()
                }
            }

            Log.i(TAG, "Direct download finished: ${stagingFile.absolutePath} (${stagingFile.length()} bytes)")

            // Extract audio if user chose MP3 but downloaded file was a video container
            if (type == MediaType.AUDIO_MP3 && !detectedExt.equals("mp3", ignoreCase = true)) {
                onProgress(95f, 1L, "Extracting audio to MP3 format...")
                val extractedMp3File = extractAudioWithYoutubeDL(stagingFile, cleanBaseName)
                if (extractedMp3File != null && extractedMp3File.exists() && extractedMp3File.length() > 0) {
                    stagingFile.delete()
                    return@withContext repository.saveToPublicDownloads(extractedMp3File, MediaType.AUDIO_MP3)
                }
            }

            // Save directly to user Downloads directory and index with MediaStore
            return@withContext repository.saveToPublicDownloads(stagingFile, type)
        } catch (e: Exception) {
            tempStagingFile?.delete()
            if (e is CancellationException || call.isCanceled()) {
                DownloadLogger.w(TAG, "Direct download was cancelled by user.")
                Log.i(TAG, "Direct download was cancelled by user.")
                return@withContext Result.failure(Exception("Download cancelled"))
            }
            DownloadLogger.e(TAG, "Direct download encountered an exception: ${e.message}", e)
            Log.e(TAG, "Direct download encountered an exception: ${e.message}", e)
            Result.failure(Exception("Direct download failed: ${e.message ?: "Network error"} [Engine: DirectMediaDownloader]"))
        } finally {
            activeCalls.remove(processId)
        }
    }

    private fun extractAudioWithYoutubeDL(videoFile: File, baseName: String): File? {
        return try {
            val mp3Target = File(repository.stagingDir, "${baseName}_${UUID.randomUUID().toString().take(6)}.mp3")
            val request = YoutubeDLRequest(videoFile.absolutePath).apply {
                addOption("-x")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "0")
                addOption("-o", mp3Target.absolutePath)
            }
            YoutubeDL.getInstance().execute(request)
            if (mp3Target.exists() && mp3Target.length() > 0) {
                mp3Target
            } else {
                repository.stagingDir.listFiles()
                    ?.filter { it.extension.equals("mp3", true) && it.lastModified() >= videoFile.lastModified() }
                    ?.maxByOrNull { it.lastModified() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Audio extraction via yt-dlp fallback note: ${e.message}")
            null
        }
    }
}
