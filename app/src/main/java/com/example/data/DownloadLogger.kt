package com.example.data

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

enum class LogLevel {
    DEBUG,
    INFO,
    WARNING,
    ERROR
}

data class LogEntry(
    val id: Long,
    val timestamp: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val headers: Map<String, String>? = null,
    val responseCode: Int? = null,
    val url: String? = null
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(timestamp))
}

object DownloadLogger {
    private const val MAX_LOGS = 300
    private val counter = AtomicLong(0)
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun d(
        tag: String,
        message: String,
        headers: Map<String, String>? = null,
        responseCode: Int? = null,
        url: String? = null
    ) {
        val formattedHeaders = headers?.takeIf { it.isNotEmpty() }?.let { " | Headers: $it" } ?: ""
        val statusText = responseCode?.let { " [HTTP $it]" } ?: ""
        Log.d(tag, "$message$statusText$formattedHeaders")

        addEntry(
            LogLevel.DEBUG,
            tag,
            message,
            headers,
            responseCode,
            url
        )
    }

    fun i(
        tag: String,
        message: String,
        headers: Map<String, String>? = null,
        responseCode: Int? = null,
        url: String? = null
    ) {
        val formattedHeaders = headers?.takeIf { it.isNotEmpty() }?.let { " | Headers: $it" } ?: ""
        val statusText = responseCode?.let { " [HTTP $it]" } ?: ""
        Log.i(tag, "$message$statusText$formattedHeaders")

        addEntry(
            LogLevel.INFO,
            tag,
            message,
            headers,
            responseCode,
            url
        )
    }

    fun w(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        headers: Map<String, String>? = null,
        responseCode: Int? = null,
        url: String? = null
    ) {
        val err = throwable?.let { " - ${it.message ?: it.javaClass.simpleName}" } ?: ""
        Log.w(tag, "$message$err", throwable)

        addEntry(
            LogLevel.WARNING,
            tag,
            "$message$err",
            headers,
            responseCode,
            url
        )
    }

    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        headers: Map<String, String>? = null,
        responseCode: Int? = null,
        url: String? = null
    ) {
        val err = throwable?.let { " - ${it.message ?: it.javaClass.simpleName}" } ?: ""
        Log.e(tag, "$message$err", throwable)

        addEntry(
            LogLevel.ERROR,
            tag,
            "$message$err",
            headers,
            responseCode,
            url
        )
    }

    fun logRequest(tag: String, url: String, method: String = "GET", headers: Map<String, String>? = null) {
        d(
            tag = tag,
            message = "--> $method $url",
            headers = headers,
            url = url
        )
    }

    fun logResponse(
        tag: String,
        url: String,
        statusCode: Int,
        headers: Map<String, String>? = null,
        details: String? = null
    ) {
        val msg = "<-- HTTP $statusCode for $url${details?.let { " ($it)" } ?: ""}"
        if (statusCode in 200..299) {
            i(tag = tag, message = msg, headers = headers, responseCode = statusCode, url = url)
        } else if (statusCode == 403 || statusCode == 429) {
            e(tag = tag, message = "$msg [CHALLENGE / FORBIDDEN]", headers = headers, responseCode = statusCode, url = url)
        } else {
            w(tag = tag, message = msg, headers = headers, responseCode = statusCode, url = url)
        }
    }

    fun logProcessLine(tag: String, line: String) {
        val clean = line.trim()
        if (clean.isBlank()) return
        if (clean.contains("ERROR:", ignoreCase = true) || clean.contains("HTTP Error", ignoreCase = true)) {
            e(tag = tag, message = clean)
        } else if (clean.contains("WARNING:", ignoreCase = true)) {
            w(tag = tag, message = clean)
        } else {
            d(tag = tag, message = clean)
        }
    }

    private fun addEntry(
        level: LogLevel,
        tag: String,
        message: String,
        headers: Map<String, String>?,
        responseCode: Int?,
        url: String?
    ) {
        val entry = LogEntry(
            id = counter.incrementAndGet(),
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message,
            headers = headers,
            responseCode = responseCode,
            url = url
        )
        _logs.update { current ->
            (current + entry).takeLast(MAX_LOGS)
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun getExportText(): String {
        return _logs.value.joinToString("\n") { entry ->
            val codeStr = entry.responseCode?.let { " [HTTP $it]" } ?: ""
            val headersStr = entry.headers?.takeIf { it.isNotEmpty() }?.let { "\n    Headers: $it" } ?: ""
            "[${entry.formattedTime}] [${entry.level}] [${entry.tag}]$codeStr ${entry.message}$headersStr"
        }
    }
}
