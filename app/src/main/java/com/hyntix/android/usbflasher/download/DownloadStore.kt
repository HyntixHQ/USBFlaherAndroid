package com.hyntix.android.usbflasher.download

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single source of truth for download records. The store is deliberately
 * small (SharedPreferences + JSON): one active download at a time and only a
 * handful of completed records are expected. Progress is written to memory on
 * every callback and to disk at a throttled interval.
 */
class DownloadStore(context: Context) {

    private companion object {
        const val PREFS = "iso_downloads"
        const val KEY_RECORDS = "records_v1"
        const val PROGRESS_PERSIST_INTERVAL_MS = 5_000L
    }

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = Gson()
    private val lock = Any()
    private val lastProgressPersist = mutableMapOf<String, Long>()

    private val _records = MutableStateFlow<List<DownloadRecord>>(emptyList())
    val records: StateFlow<List<DownloadRecord>> = _records.asStateFlow()

    init {
        _records.value = load()
    }

    fun all(): List<DownloadRecord> = _records.value

    fun find(id: String): DownloadRecord? = _records.value.firstOrNull { it.id == id }

    fun activeRecord(): DownloadRecord? = _records.value.firstOrNull { it.status.isActive }

    fun upsert(record: DownloadRecord, persistNow: Boolean = true) {
        synchronized(lock) {
            val current = _records.value
            val existing = current.indexOfFirst { it.id == record.id }
            val updated = if (existing >= 0) {
                current.toMutableList().also { it[existing] = record }
            } else {
                listOf(record) + current
            }
            _records.value = updated
            if (persistNow) persistLocked(updated, sync = true)
        }
    }

    fun update(
        id: String,
        persistNow: Boolean = true,
        transform: (DownloadRecord) -> DownloadRecord
    ): DownloadRecord? {
        synchronized(lock) {
            val current = _records.value
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) return null
            val updatedRecord = transform(current[index])
            val updated = current.toMutableList().also { it[index] = updatedRecord }
            _records.value = updated
            if (persistNow) persistLocked(updated, sync = true)
            return updatedRecord
        }
    }

    fun updateProgress(
        id: String,
        bytesDownloaded: Long,
        totalBytes: Long,
        speedBytesPerSecond: Long,
        etaSeconds: Long,
        status: DownloadStatus = DownloadStatus.DOWNLOADING
    ) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val current = _records.value
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) return
            val old = current[index]
            // A progress callback can race a pause/cancel/terminal update.
            // Never let a late callback resurrect a record that is no longer
            // downloading or verifying.
            if (old.status != DownloadStatus.DOWNLOADING &&
                old.status != DownloadStatus.VERIFYING
            ) {
                return
            }
            val updatedRecord = old.copy(
                bytesDownloaded = bytesDownloaded.coerceAtLeast(0),
                totalBytes = totalBytes.coerceAtLeast(0),
                speedBytesPerSecond = speedBytesPerSecond.coerceAtLeast(0),
                etaSeconds = etaSeconds.coerceAtLeast(0),
                status = status,
                pauseReason = null,
                error = null,
                updatedAt = now
            )
            val updated = current.toMutableList().also { it[index] = updatedRecord }
            _records.value = updated
            val last = lastProgressPersist[id] ?: 0L
            if (now - last >= PROGRESS_PERSIST_INTERVAL_MS) {
                lastProgressPersist[id] = now
                persistLocked(updated, sync = false)
            }
        }
    }

    fun updateStatus(
        id: String,
        status: DownloadStatus,
        pauseReason: PauseReason? = null,
        error: DownloadError? = null,
        bytesDownloaded: Long? = null,
        totalBytes: Long? = null,
        persistNow: Boolean = true
    ): DownloadRecord? {
        val now = System.currentTimeMillis()
        return update(id, persistNow) { record ->
            record.copy(
                status = status,
                pauseReason = pauseReason,
                error = error,
                bytesDownloaded = bytesDownloaded ?: record.bytesDownloaded,
                totalBytes = totalBytes ?: record.totalBytes,
                speedBytesPerSecond = if (status == DownloadStatus.DOWNLOADING) {
                    record.speedBytesPerSecond
                } else {
                    0L
                },
                etaSeconds = if (status == DownloadStatus.DOWNLOADING) record.etaSeconds else 0L,
                updatedAt = now
            )
        }
    }

    fun remove(id: String) {
        synchronized(lock) {
            val updated = _records.value.filterNot { it.id == id }
            _records.value = updated
            lastProgressPersist.remove(id)
            persistLocked(updated, sync = true)
        }
    }

    /**
     * Called once per process start. A record left in a live state means the
     * service is no longer running (or the process was killed mid-download).
     */
    fun reconcile(serviceRunning: Boolean) {
        if (serviceRunning) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val updated = _records.value.map { record ->
                when (record.status) {
                    DownloadStatus.QUEUED,
                    DownloadStatus.RESOLVING,
                    DownloadStatus.DOWNLOADING,
                    DownloadStatus.WAITING_FOR_NETWORK,
                    DownloadStatus.VERIFYING -> record.copy(
                        status = DownloadStatus.PAUSED,
                        pauseReason = PauseReason.INTERRUPTED,
                        speedBytesPerSecond = 0,
                        etaSeconds = 0,
                        updatedAt = now
                    )
                    else -> record
                }
            }
            _records.value = updated
            persistLocked(updated, sync = true)
        }
    }

    fun isAnotherActive(id: String): Boolean {
        return _records.value.any { it.id != id && it.status.isActive }
    }

    private fun load(): List<DownloadRecord> {
        val json = prefs.getString(KEY_RECORDS, null) ?: return emptyList()
        return runCatching {
            val type = object : TypeToken<List<DownloadRecord>>() {}.type
            val parsed: List<DownloadRecord>? = gson.fromJson(json, type)
            parsed.orEmpty()
                .filter { isValid(it) }
                .distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    private fun isValid(record: DownloadRecord): Boolean {
        return runCatching {
            record.id.isNotBlank() &&
                record.catalogId.isNotBlank() &&
                record.destinationUri.isNotBlank() &&
                record.fileName.isNotBlank() &&
                record.totalBytes > 0 &&
                record.bytesDownloaded >= 0 &&
                record.expectedSha256.matches(Regex("[0-9a-fA-F]{64}")) &&
                DownloadStatus.entries.contains(record.status) &&
                DownloadKind.entries.contains(record.kind)
        }.getOrDefault(false)
    }

    @Suppress("ApplySharedPref")
    private fun persistLocked(records: List<DownloadRecord>, sync: Boolean) {
        val json = gson.toJson(records)
        val editor = prefs.edit().putString(KEY_RECORDS, json)
        if (sync) editor.commit() else editor.apply()
    }
}
