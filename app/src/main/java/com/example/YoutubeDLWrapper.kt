package com.example

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLResponse
import com.yausername.youtubedl_android.mapper.VideoInfo

object YoutubeDLWrapper {
    fun executeDownload(
        request: YoutubeDLRequest,
        processId: String? = null,
        onProgress: ((progress: Float, etaInSeconds: Long, line: String) -> Unit)? = null
    ): YoutubeDLResponse {
        return YoutubeDL.getInstance().execute(
            request = request,
            processId = processId,
            callback = onProgress
        )
    }

    fun getInfo(url: String): VideoInfo {
        val request = YoutubeDLRequest(url)
        return YoutubeDL.getInstance().getInfo(request)
    }

    fun cancel(processId: String): Boolean {
        return YoutubeDL.getInstance().destroyProcessById(processId)
    }

    fun updateYoutubeDL(context: Context): YoutubeDL.UpdateStatus {
        return YoutubeDL.getInstance().updateYoutubeDL(context)
    }

    fun version(context: Context): String? {
        return try {
            YoutubeDL.getInstance().version(context)
        } catch (e: Exception) {
            null
        }
    }
}
