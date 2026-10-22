package com.hyntix.android.usbflasher.download

import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Controls an in-flight OkHttp call from the service's notification actions.
 * Pausing cancels the blocking socket operation; the engine reports the bytes
 * already persisted so the next run can resume with Range.
 */
class DownloadControl {
    private val paused = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val call = AtomicReference<Call?>(null)

    val isPaused: Boolean get() = paused.get()
    val isCancelled: Boolean get() = cancelled.get()

    fun pause() {
        paused.set(true)
        call.getAndSet(null)?.cancel()
    }

    fun cancel() {
        cancelled.set(true)
        call.getAndSet(null)?.cancel()
    }

    internal fun attach(newCall: Call) {
        call.set(newCall)
        if (paused.get() || cancelled.get()) {
            newCall.cancel()
        }
    }

    internal fun detach(oldCall: Call) {
        call.compareAndSet(oldCall, null)
    }
}

data class EngineRequest(
    val url: String,
    val offset: Long = 0,
    val expectedTotalBytes: Long = 0,
    val etag: String? = null,
    val lastModified: String? = null
)

sealed interface EngineResult {
    data class Completed(
        val totalBytes: Long,
        val etag: String?,
        val lastModified: String?,
        val rangeSupported: Boolean,
        val finalUrl: String
    ) : EngineResult

    data class Paused(
        val bytesDownloaded: Long,
        val reason: PauseReason = PauseReason.USER
    ) : EngineResult

    data class Cancelled(val bytesDownloaded: Long) : EngineResult

    data class Failed(
        val error: DownloadError,
        val bytesDownloaded: Long
    ) : EngineResult
}

class ResumableDownloadEngine(
    private val client: OkHttpClient,
    private val finalUrlValidator: (HttpUrl) -> Boolean = UrlPolicy::isSafeFinalIsoUrl,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) {

    fun run(
        request: EngineRequest,
        sink: DownloadSink,
        control: DownloadControl,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit
    ): EngineResult {
        var offset = request.offset.coerceAtLeast(0)
        var total = request.expectedTotalBytes.coerceAtLeast(0)
        var etag = request.etag
        var lastModified = request.lastModified
        var rangeSupported = true
        var retries = 0
        var restarts = 0

        try {
            sink.prepare(offset)
        } catch (e: Exception) {
            return EngineResult.Failed(
                DownloadError(DownloadErrorCode.DESTINATION_UNSUPPORTED),
                sinkSize(sink)
            )
        }

        while (true) {
            if (control.isCancelled) {
                return EngineResult.Cancelled(sinkSize(sink))
            }
            if (control.isPaused) {
                return EngineResult.Paused(sinkSize(sink), PauseReason.USER)
            }

            val attempt = attempt(
                url = request.url,
                offset = offset,
                total = total,
                etag = etag,
                lastModified = lastModified,
                rangeSupported = rangeSupported,
                sink = sink,
                control = control,
                onProgress = onProgress
            )

            when (attempt) {
                is Attempt.Complete -> {
                    onProgress(attempt.totalBytes, attempt.totalBytes)
                    return EngineResult.Completed(
                        totalBytes = attempt.totalBytes,
                        etag = attempt.etag,
                        lastModified = attempt.lastModified,
                        rangeSupported = attempt.rangeSupported,
                        finalUrl = attempt.finalUrl
                    )
                }

                is Attempt.Paused -> return EngineResult.Paused(attempt.bytes, attempt.reason)
                is Attempt.Cancelled -> return EngineResult.Cancelled(attempt.bytes)

                is Attempt.Restart -> {
                    restarts++
                    if (restarts > MAX_RESTARTS) {
                        return EngineResult.Failed(
                            DownloadError(DownloadErrorCode.SERVER_UNSUPPORTED),
                            sinkSize(sink)
                        )
                    }
                    runCatching { sink.prepare(0) }.onFailure {
                        return EngineResult.Failed(
                            DownloadError(DownloadErrorCode.DESTINATION_UNSUPPORTED),
                            sinkSize(sink)
                        )
                    }
                    offset = 0
                    total = attempt.totalBytes.coerceAtLeast(0)
                    retries = 0
                    rangeSupported = true
                    continue
                }

                is Attempt.Retry -> {
                    retries++
                    if (retries > MAX_RETRIES) {
                        return EngineResult.Failed(attempt.error, sinkSize(sink))
                    }
                    runCatching { sink.flush() }
                    offset = sinkSize(sink)
                    val delay = if (attempt.retryAfterMillis > 0) {
                        attempt.retryAfterMillis
                    } else {
                        (1L shl (retries - 1)).coerceAtMost(8L) * 1000L
                    }
                    sleepWithControl(delay, control)
                    continue
                }

                is Attempt.Terminal -> {
                    return EngineResult.Failed(attempt.error, sinkSize(sink))
                }
            }
        }
    }

    private fun attempt(
        url: String,
        offset: Long,
        total: Long,
        etag: String?,
        lastModified: String?,
        rangeSupported: Boolean,
        sink: DownloadSink,
        control: DownloadControl,
        onProgress: (Long, Long) -> Unit
    ): Attempt {
        val requestBuilder = Request.Builder().url(url)
        if (offset > 0 && rangeSupported) {
            requestBuilder.header("Range", "bytes=$offset-")
            val validator = etag?.takeIf { it.isNotBlank() }
                ?: lastModified?.takeIf { it.isNotBlank() }
            if (validator != null) {
                requestBuilder.header("If-Range", validator)
            }
        }

        val call = client.newCall(requestBuilder.build())
        control.attach(call)
        try {
            call.execute().use { response ->
                if (control.isCancelled) return Attempt.Cancelled(sinkSize(sink))
                if (control.isPaused) return Attempt.Paused(sinkSize(sink), PauseReason.USER)

                if (!finalUrlValidator(response.request.url)) {
                    return Attempt.Terminal(DownloadError(DownloadErrorCode.INVALID_FILE))
                }
                if (UrlPolicy.isHtmlContentType(response.header("Content-Type"))) {
                    return Attempt.Terminal(DownloadError(DownloadErrorCode.HTML_RESPONSE))
                }

                var fileTotal = total
                var bodyOffset = offset
                var resumed = false
                var rangeSupported = rangeSupported

                when (response.code) {
                    200 -> {
                        if (offset > 0) {
                            runCatching { sink.prepare(0) }
                                .onFailure {
                                    return Attempt.Terminal(
                                        DownloadError(DownloadErrorCode.DESTINATION_UNSUPPORTED)
                                    )
                                }
                            bodyOffset = 0
                        }
                        val bodyLength = response.body?.contentLength() ?: -1L
                        val headerLength = response.header("Content-Length")?.toLongOrNull() ?: -1L
                        fileTotal = when {
                            bodyLength >= 0 -> bodyLength
                            headerLength >= 0 -> headerLength
                            else -> total
                        }
                        rangeSupported = response.header("Accept-Ranges")
                            ?.equals("bytes", ignoreCase = true) == true
                    }

                    206 -> {
                        val contentRange = response.header("Content-Range")
                            ?: return Attempt.Terminal(DownloadError(DownloadErrorCode.SERVER_UNSUPPORTED))
                        val parsed = parseContentRange(contentRange)
                            ?: return Attempt.Terminal(DownloadError(DownloadErrorCode.SERVER_UNSUPPORTED))
                        if (parsed.start != offset) {
                            return Attempt.Restart(parsed.total ?: total)
                        }
                        resumed = offset > 0
                        if (parsed.total != null && parsed.total > 0) {
                            fileTotal = parsed.total
                        }
                    }

                    416 -> {
                        val current = sinkSize(sink)
                        if (total > 0 && current >= total) {
                            return Attempt.Complete(
                                totalBytes = total,
                                etag = etag,
                                lastModified = lastModified,
                                rangeSupported = rangeSupported,
                                finalUrl = response.request.url.toString()
                            )
                        }
                        return Attempt.Restart(0)
                    }

                    408, 429 -> {
                        val retryAfter = response.header("Retry-After")?.toLongOrNull()
                            ?.let { it.coerceIn(0, 30) * 1000L }
                            ?: 0L
                        return Attempt.Retry(
                            DownloadError(DownloadErrorCode.HTTP_ERROR, response.code),
                            retryAfter
                        )
                    }

                    in 500..599 -> {
                        return Attempt.Retry(
                            DownloadError(DownloadErrorCode.HTTP_ERROR, response.code),
                            0
                        )
                    }

                    else -> {
                        return Attempt.Terminal(
                            DownloadError(DownloadErrorCode.HTTP_ERROR, response.code)
                        )
                    }
                }

                if (bodyOffset > 0 && !resumed) {
                    return Attempt.Restart(fileTotal)
                }
                if (fileTotal > 0 && bodyOffset > fileTotal) {
                    return Attempt.Restart(fileTotal)
                }

                val body = response.body
                    ?: return Attempt.Retry(DownloadError(DownloadErrorCode.NETWORK), 0)
                val input = body.byteStream()
                val buffer = ByteArray(BUFFER_SIZE)
                var written = bodyOffset
                var lastProgress = 0L
                var lastFlush = written

                try {
                    while (true) {
                        if (control.isCancelled) {
                            runCatching { sink.flush() }
                            return Attempt.Cancelled(written)
                        }
                        if (control.isPaused) {
                            runCatching { sink.flush() }
                            return Attempt.Paused(written, PauseReason.USER)
                        }

                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        sink.write(buffer, read)
                        written += read

                        val now = System.currentTimeMillis()
                        if (now - lastProgress >= PROGRESS_INTERVAL_MS ||
                            (fileTotal > 0 && written >= fileTotal)
                        ) {
                            lastProgress = now
                            onProgress(written, fileTotal)
                        }
                        if (written - lastFlush >= FLUSH_INTERVAL_BYTES) {
                            sink.flush()
                            lastFlush = written
                        }
                    }
                    sink.flush()
                    onProgress(written, if (fileTotal > 0) fileTotal else written)
                } catch (e: IOException) {
                    if (control.isCancelled) return Attempt.Cancelled(sinkSize(sink))
                    if (control.isPaused) return Attempt.Paused(sinkSize(sink), PauseReason.USER)
                    return when {
                        isNoSpace(e) -> Attempt.Paused(sinkSize(sink), PauseReason.NO_SPACE)
                        isDestinationError(e) -> Attempt.Terminal(
                            DownloadError(DownloadErrorCode.DESTINATION_UNAVAILABLE)
                        )
                        else -> Attempt.Retry(DownloadError(DownloadErrorCode.NETWORK), 0)
                    }
                }

                if (fileTotal > 0 && written < fileTotal) {
                    return Attempt.Retry(DownloadError(DownloadErrorCode.NETWORK), 0)
                }
                if (fileTotal > 0 && written > fileTotal) {
                    return Attempt.Restart(fileTotal)
                }

                return Attempt.Complete(
                    totalBytes = if (fileTotal > 0) fileTotal else written,
                    etag = response.header("ETag") ?: etag,
                    lastModified = response.header("Last-Modified") ?: lastModified,
                    rangeSupported = rangeSupported,
                    finalUrl = response.request.url.toString()
                )
            }
        } catch (e: IOException) {
            if (control.isCancelled) return Attempt.Cancelled(sinkSize(sink))
            if (control.isPaused) return Attempt.Paused(sinkSize(sink), PauseReason.USER)
            return when {
                isNoSpace(e) -> Attempt.Paused(sinkSize(sink), PauseReason.NO_SPACE)
                isDestinationError(e) -> Attempt.Terminal(
                    DownloadError(DownloadErrorCode.DESTINATION_UNAVAILABLE)
                )
                isNoNetwork(e) -> Attempt.Terminal(DownloadError(DownloadErrorCode.NO_NETWORK))
                else -> Attempt.Retry(DownloadError(DownloadErrorCode.NETWORK), 0)
            }
        } catch (e: SecurityException) {
            return Attempt.Terminal(DownloadError(DownloadErrorCode.DESTINATION_UNAVAILABLE))
        } finally {
            control.detach(call)
        }
    }

    private fun sinkSize(sink: DownloadSink): Long = runCatching { sink.size() }.getOrDefault(0L)

    private fun isNoSpace(e: IOException): Boolean {
        val message = e.message?.lowercase().orEmpty()
        return message.contains("no space") || message.contains("enospc")
    }

    private fun isDestinationError(e: IOException): Boolean {
        if (e is java.io.FileNotFoundException) return true
        if (e is java.nio.channels.ClosedChannelException) return true
        val message = e.message?.lowercase().orEmpty()
        return message.contains("permission denied") ||
            message.contains("operation not permitted") ||
            message.contains("read-only") ||
            message.contains("invalid offset") ||
            message.contains("fewer bytes")
    }

    private fun isNoNetwork(e: IOException): Boolean {
        return e is UnknownHostException
    }

    private fun sleepWithControl(millis: Long, control: DownloadControl) {
        var remaining = millis
        while (remaining > 0) {
            if (control.isPaused || control.isCancelled) return
            val step = remaining.coerceAtMost(200L)
            runCatching { sleeper(step) }
            remaining -= step
        }
    }

    private data class ContentRange(val start: Long, val end: Long, val total: Long?)

    private fun parseContentRange(value: String): ContentRange? {
        val match = CONTENT_RANGE.find(value.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val totalText = match.groupValues[3]
        val total = if (totalText == "*") null else totalText.toLongOrNull()
        if (end < start) return null
        return ContentRange(start, end, total)
    }

    private sealed interface Attempt {
        data class Complete(
            val totalBytes: Long,
            val etag: String?,
            val lastModified: String?,
            val rangeSupported: Boolean,
            val finalUrl: String
        ) : Attempt

        data class Paused(
            val bytes: Long,
            val reason: PauseReason
        ) : Attempt

        data class Cancelled(val bytes: Long) : Attempt
        data class Restart(val totalBytes: Long) : Attempt
        data class Retry(val error: DownloadError, val retryAfterMillis: Long) : Attempt
        data class Terminal(val error: DownloadError) : Attempt
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val FLUSH_INTERVAL_BYTES = 4L * 1024L * 1024L
        const val PROGRESS_INTERVAL_MS = 200L
        const val MAX_RETRIES = 5
        const val MAX_RESTARTS = 3
        val CONTENT_RANGE = Regex("^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)\\s*$")
    }
}
