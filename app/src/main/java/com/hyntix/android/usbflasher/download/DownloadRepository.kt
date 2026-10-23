package com.hyntix.android.usbflasher.download

import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.UnknownHostException
import java.util.UUID

class DownloadRepository(
    private val context: Context,
    private val store: DownloadStore,
    private val catalogLoader: IsoCatalogLoader,
    private val downloadClient: OkHttpClient,
    private val checksumClient: OkHttpClient
) {

    fun loadCatalog(): CatalogLoadResult = catalogLoader.load()

    suspend fun startDownload(
        entry: LinuxIsoEntry,
        destinationUri: Uri,
        allowMetered: Boolean = false
    ): DownloadStartResult = withContext(Dispatchers.IO) {
        if (store.activeRecord() != null) {
            return@withContext DownloadStartResult.Failed(
                DownloadError(DownloadErrorCode.ANOTHER_DOWNLOAD_ACTIVE)
            )
        }
        val hosts = entry.isoHosts.toSet()
        val staticChecksum = entry.sha256
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        val hasStaticChecksum = staticChecksum != null
        if (hosts.isEmpty() ||
            UrlPolicy.parseIsoUrl(entry.url, hosts) == null ||
            (!hasStaticChecksum && UrlPolicy.parseChecksumUrl(entry.checksumUrl, hosts) == null) ||
            UrlPolicy.sanitizeFileName(entry.fileName) != entry.fileName
        ) {
            return@withContext DownloadStartResult.Failed(
                DownloadError(DownloadErrorCode.INVALID_FILE)
            )
        }

        NetworkPolicy.check(context, allowMetered)?.let {
            return@withContext DownloadStartResult.Failed(it)
        }

        // Validate that the destination is writable. Do not truncate here:
        // every network preflight must finish before an existing file is
        // touched.
        try {
            DownloadSinkFactory.open(context, destinationUri).use { it.size() }
        } catch (e: Exception) {
            return@withContext DownloadStartResult.Failed(
                DownloadError(DownloadErrorCode.DESTINATION_UNAVAILABLE)
            )
        }

        val checksum = staticChecksum ?: when (val result = fetchChecksum(entry)) {
            is ChecksumResult.Success -> result.sha256
            is ChecksumResult.Failure -> return@withContext DownloadStartResult.Failed(result.error)
        }

        val metadata = fetchMetadata(entry)
        if (metadata is MetadataResult.Failure) {
            return@withContext DownloadStartResult.Failed(metadata.error)
        }
        val meta = (metadata as MetadataResult.Success).value
        if (meta.totalBytes < MIN_ISO_BYTES) {
            return@withContext DownloadStartResult.Failed(
                DownloadError(DownloadErrorCode.INVALID_FILE)
            )
        }

        // Best effort: not every SAF provider exposes a stat-able descriptor.
        // Replacing an existing document frees its bytes, so count them as
        // usable space; the destination is truncated only when writing starts.
        val available = DownloadDestination.availableBytes(context, destinationUri)
            ?: Long.MAX_VALUE
        if (available != Long.MAX_VALUE) {
            val existingSize = DownloadDestination.fileSize(context, destinationUri) ?: 0L
            val usable = if (existingSize > Long.MAX_VALUE - available) {
                Long.MAX_VALUE
            } else {
                available + existingSize
            }
            if (usable < meta.totalBytes + FREE_SPACE_RESERVE_BYTES) {
                return@withContext DownloadStartResult.Failed(
                    DownloadError(DownloadErrorCode.NO_SPACE)
                )
            }
        }

        val providerName = DownloadDestination.displayName(context, destinationUri)
        val safeName = UrlPolicy.sanitizeFileName(providerName ?: entry.fileName)
        val now = System.currentTimeMillis()
        val record = DownloadRecord(
            id = UUID.randomUUID().toString(),
            catalogId = entry.id,
            kind = DownloadKind.LINUX,
            displayName = entry.displayName,
            fileName = safeName,
            destinationUri = destinationUri.toString(),
            totalBytes = meta.totalBytes,
            bytesDownloaded = 0,
            expectedSha256 = checksum,
            checksumUrl = entry.checksumUrl,
            status = DownloadStatus.QUEUED,
            etag = meta.etag,
            lastModified = meta.lastModified,
            sourceUrl = entry.url,
            rangeSupported = meta.rangeSupported,
            allowMetered = allowMetered,
            createdAt = now,
            updatedAt = now
        )
        store.upsert(record)

        val serviceError = startService(record.id)
        if (serviceError != null) {
            // The service never opened the destination, so leave any existing
            // file untouched and remove only the bookkeeping record.
            store.remove(record.id)
            return@withContext DownloadStartResult.Failed(serviceError)
        }
        DownloadStartResult.Started(store.find(record.id) ?: record)
    }

    fun pause(id: String) {
        val record = store.find(id) ?: return
        if (!record.status.isActive) return
        if (DownloadService.isRunning) {
            // A background-service start can be rejected if the activity is
            // leaving the foreground; a missed pause is preferable to a crash.
            runCatching { context.startService(DownloadService.pauseIntent(context, id)) }
        } else {
            store.updateStatus(
                id = id,
                status = DownloadStatus.PAUSED,
                pauseReason = PauseReason.USER
            )
        }
    }

    fun resume(id: String, allowMetered: Boolean = false): DownloadError? {
        val record = store.find(id) ?: return DownloadError(DownloadErrorCode.DESTINATION_UNAVAILABLE)
        if (record.status == DownloadStatus.COMPLETED) return null
        if (record.status != DownloadStatus.PAUSED &&
            record.status != DownloadStatus.FAILED
        ) {
            return null
        }
        if (store.isAnotherActive(id)) {
            return DownloadError(DownloadErrorCode.ANOTHER_DOWNLOAD_ACTIVE)
        }
        val effectiveAllowMetered = record.allowMetered || allowMetered
        NetworkPolicy.check(context, effectiveAllowMetered)?.let { return it }
        store.update(id, persistNow = true) { current ->
            current.copy(
                status = DownloadStatus.QUEUED,
                pauseReason = null,
                error = null,
                allowMetered = effectiveAllowMetered,
                updatedAt = System.currentTimeMillis()
            )
        }
        val error = startService(id)
        if (error != null) {
            val failed = store.updateStatus(
                id = id,
                status = DownloadStatus.FAILED,
                error = error
            )
            if (failed != null) {
                // The service never started, so replace the paused notification
                // directly instead of leaving a stale "Resume" action behind.
                val notifications = DownloadNotifications(context)
                notifications.notify(failed.id, notifications.failed(failed))
            }
        }
        return error
    }

    fun cancel(id: String) {
        if (DownloadService.isRunning) {
            // Leave the record intact if the service cannot be reached; the
            // engine may still be writing to the destination.
            runCatching { context.startService(DownloadService.cancelIntent(context, id)) }
        } else {
            deleteNow(id)
        }
    }

    fun delete(id: String) {
        cancel(id)
    }

    /**
     * Mark an existing record failed from an app-side validation step (for
     * example, the completed ISO disappeared). The service is not involved, so
     * dismiss any notification still showing for that record.
     */
    fun markFailed(id: String, error: DownloadError) {
        store.updateStatus(
            id = id,
            status = DownloadStatus.FAILED,
            error = error,
            persistNow = true
        )
        DownloadNotifications.dismissFor(context, id)
    }

    fun reconcile() {
        store.reconcile(DownloadService.isRunning)
    }

    private fun deleteNow(id: String) {
        val record = store.find(id)
        if (record != null) {
            runCatching { DownloadDestination.delete(context, record.destinationUri.toUri()) }
            store.remove(id)
        }
        // The service is stopped on this path, so no active download owns the
        // single notification. Dismiss it only if it belongs to this record.
        DownloadNotifications.dismissFor(context, id)
    }

    private fun startService(id: String): DownloadError? {
        return try {
            ContextCompat.startForegroundService(
                context,
                DownloadService.startIntent(context, id)
            )
            null
        } catch (e: Exception) {
            DownloadError(DownloadErrorCode.SERVICE_UNAVAILABLE)
        }
    }

    private fun fetchChecksum(entry: LinuxIsoEntry): ChecksumResult {
        if (entry.checksumUrl.isBlank()) {
            return ChecksumResult.Failure(DownloadError(DownloadErrorCode.CHECKSUM_UNAVAILABLE))
        }
        return try {
            val request = Request.Builder().url(entry.checksumUrl).get().build()
            checksumClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return ChecksumResult.Failure(
                        DownloadError(DownloadErrorCode.HTTP_ERROR, response.code)
                    )
                }
                if (!response.request.url.isHttps) {
                    return ChecksumResult.Failure(
                        DownloadError(DownloadErrorCode.CHECKSUM_UNAVAILABLE)
                    )
                }
                if (UrlPolicy.isHtmlContentType(response.header("Content-Type"))) {
                    return ChecksumResult.Failure(
                        DownloadError(DownloadErrorCode.CHECKSUM_TABLE_INVALID)
                    )
                }
                val body = response.body
                    ?: return ChecksumResult.Failure(
                        DownloadError(DownloadErrorCode.CHECKSUM_UNAVAILABLE)
                    )
                val text = body.byteStream().bufferedReader().use { reader ->
                    val buffer = CharArray(MAX_CHECKSUM_CHARS)
                    var total = 0
                    while (total < buffer.size) {
                        val count = reader.read(buffer, total, buffer.size - total)
                        if (count < 0) break
                        total += count
                    }
                    String(buffer, 0, total)
                }
                val table = ChecksumParser.parse(text)
                val checksumFileName = entry.checksumFileName.ifBlank { entry.fileName }
                val hash = if (entry.checksumFormat == "SINGLE") {
                    table.onlyHash()
                } else {
                    table.hashFor(checksumFileName)
                } ?: return ChecksumResult.Failure(
                    DownloadError(DownloadErrorCode.CHECKSUM_UNAVAILABLE)
                )
                ChecksumResult.Success(hash)
            }
        } catch (e: Exception) {
            ChecksumResult.Failure(DownloadError(DownloadErrorCode.CHECKSUM_UNAVAILABLE))
        }
    }

    private fun fetchMetadata(entry: LinuxIsoEntry): MetadataResult {
        return try {
            val request = Request.Builder().url(entry.url).head().build()
            downloadClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    if (response.code == 405 || response.code == 403) {
                        return MetadataResult.Success(
                            Metadata(entry.approxSizeBytes, null, null, true)
                        )
                    }
                    return MetadataResult.Failure(
                        DownloadError(DownloadErrorCode.HTTP_ERROR, response.code)
                    )
                }
                if (!UrlPolicy.isSafeFinalIsoUrl(response.request.url)) {
                    return MetadataResult.Failure(DownloadError(DownloadErrorCode.INVALID_FILE))
                }
                if (UrlPolicy.isHtmlContentType(response.header("Content-Type"))) {
                    return MetadataResult.Failure(DownloadError(DownloadErrorCode.HTML_RESPONSE))
                }
                val length = response.header("Content-Length")?.toLongOrNull()
                    ?.takeIf { it > 0 }
                    ?: entry.approxSizeBytes
                val rangeSupported = response.header("Accept-Ranges")
                    ?.equals("bytes", ignoreCase = true) != false
                MetadataResult.Success(
                    Metadata(
                        totalBytes = length,
                        etag = response.header("ETag"),
                        lastModified = response.header("Last-Modified"),
                        rangeSupported = rangeSupported
                    )
                )
            }
        } catch (e: UnknownHostException) {
            MetadataResult.Failure(DownloadError(DownloadErrorCode.NO_NETWORK))
        } catch (e: IOException) {
            MetadataResult.Failure(DownloadError(DownloadErrorCode.NETWORK))
        }
    }

    private sealed interface MetadataResult {
        data class Success(val value: Metadata) : MetadataResult
        data class Failure(val error: DownloadError) : MetadataResult
    }

    private data class Metadata(
        val totalBytes: Long,
        val etag: String?,
        val lastModified: String?,
        val rangeSupported: Boolean
    )

    private companion object {
        const val MAX_CHECKSUM_CHARS = 2 * 1024 * 1024
        const val MIN_ISO_BYTES = 1L * 1024L * 1024L
        const val FREE_SPACE_RESERVE_BYTES = 1L * 1024L * 1024L * 1024L
    }
}
