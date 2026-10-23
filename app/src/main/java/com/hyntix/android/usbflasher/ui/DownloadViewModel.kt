package com.hyntix.android.usbflasher.ui

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyntix.android.usbflasher.download.CatalogLoadResult
import com.hyntix.android.usbflasher.download.DownloadDestination
import com.hyntix.android.usbflasher.download.DownloadError
import com.hyntix.android.usbflasher.download.DownloadErrorCode
import com.hyntix.android.usbflasher.download.DownloadRecord
import com.hyntix.android.usbflasher.download.DownloadRepository
import com.hyntix.android.usbflasher.download.DownloadStartResult
import com.hyntix.android.usbflasher.download.DownloadStatus
import com.hyntix.android.usbflasher.download.DownloadStore
import com.hyntix.android.usbflasher.download.LinuxIsoEntry
import com.hyntix.android.usbflasher.download.ReadyIso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DownloadCatalogUiState(
    val entries: List<LinuxIsoEntry> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false
)

data class MeteredDownloadPrompt(
    val displayName: String,
    val sizeBytes: Long,
    val entry: LinuxIsoEntry? = null,
    val destinationUri: Uri? = null,
    val recordId: String? = null
)

class DownloadViewModel(
    private val appContext: Context,
    private val repository: DownloadRepository,
    private val store: DownloadStore
) : ViewModel() {

    private val _catalogState = MutableStateFlow(DownloadCatalogUiState())
    val catalogState: StateFlow<DownloadCatalogUiState> = _catalogState.asStateFlow()

    val records: StateFlow<List<DownloadRecord>> = store.records

    private val _startingEntryId = MutableStateFlow<String?>(null)
    val startingEntryId: StateFlow<String?> = _startingEntryId.asStateFlow()

    private val _focusRecordId = MutableStateFlow<String?>(null)
    val focusRecordId: StateFlow<String?> = _focusRecordId.asStateFlow()

    private val _uiError = MutableStateFlow<DownloadError?>(null)
    val uiError: StateFlow<DownloadError?> = _uiError.asStateFlow()

    private val _meteredPrompt = MutableStateFlow<MeteredDownloadPrompt?>(null)
    val meteredPrompt: StateFlow<MeteredDownloadPrompt?> = _meteredPrompt.asStateFlow()

    private val _readyIso = MutableStateFlow<ReadyIso?>(null)
    val readyIso: StateFlow<ReadyIso?> = _readyIso.asStateFlow()

    /**
     * The catalog entry waiting for ACTION_CREATE_DOCUMENT. Kept in the
     * ViewModel so an activity recreation (permission dialog, rotation) does
     * not lose the user's selection.
     */
    private var pendingDestinationEntry: LinuxIsoEntry? = null

    init {
        loadCatalog()
    }

    fun loadCatalog() {
        _catalogState.value = DownloadCatalogUiState(loading = true)
        viewModelScope.launch(Dispatchers.IO) {
            when (val result = repository.loadCatalog()) {
                is CatalogLoadResult.Success -> {
                    _catalogState.value = DownloadCatalogUiState(
                        entries = result.catalog.linux,
                        loading = false,
                        failed = false
                    )
                }
                is CatalogLoadResult.Failure -> {
                    _catalogState.value = DownloadCatalogUiState(
                        entries = emptyList(),
                        loading = false,
                        failed = true
                    )
                }
            }
        }
    }

    fun rememberDestinationRequest(entry: LinuxIsoEntry) {
        pendingDestinationEntry = entry
    }

    fun pendingDestinationRequest(): LinuxIsoEntry? = pendingDestinationEntry

    fun takeDestinationRequest(): LinuxIsoEntry? {
        val entry = pendingDestinationEntry
        pendingDestinationEntry = null
        return entry
    }

    fun startDownload(entry: LinuxIsoEntry, destinationUri: Uri) {
        startDownload(entry, destinationUri, allowMetered = false)
    }

    private fun startDownload(
        entry: LinuxIsoEntry,
        destinationUri: Uri,
        allowMetered: Boolean
    ) {
        if (_startingEntryId.value != null) return
        _startingEntryId.value = entry.id
        _uiError.value = null
        _meteredPrompt.value = null
        viewModelScope.launch(Dispatchers.IO) {
            try {
                when (val result = repository.startDownload(entry, destinationUri, allowMetered)) {
                    is DownloadStartResult.Started -> {
                        _focusRecordId.value = result.record.id
                    }
                    is DownloadStartResult.Failed -> {
                        if (result.error.code == DownloadErrorCode.METERED_BLOCKED &&
                            !allowMetered
                        ) {
                            _meteredPrompt.value = MeteredDownloadPrompt(
                                displayName = entry.displayName,
                                sizeBytes = entry.approxSizeBytes,
                                entry = entry,
                                destinationUri = destinationUri
                            )
                        } else {
                            _uiError.value = result.error
                        }
                    }
                }
            } finally {
                _startingEntryId.value = null
            }
        }
    }

    fun confirmMeteredDownload() {
        val prompt = _meteredPrompt.value ?: return
        _meteredPrompt.value = null
        if (prompt.recordId != null) {
            resume(prompt.recordId, allowMetered = true)
            return
        }
        val entry = prompt.entry ?: return
        val uri = prompt.destinationUri ?: return
        startDownload(entry, uri, allowMetered = true)
    }

    fun dismissMeteredPrompt() {
        _meteredPrompt.value = null
    }

    fun pause(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.pause(id)
        }
    }

    fun resume(id: String) {
        resume(id, allowMetered = false)
    }

    private fun resume(id: String, allowMetered: Boolean) {
        _uiError.value = null
        viewModelScope.launch(Dispatchers.IO) {
            val error = repository.resume(id, allowMetered)
            if (error == null) return@launch
            if (error.code == DownloadErrorCode.METERED_BLOCKED && !allowMetered) {
                val record = store.find(id)
                if (record != null) {
                    _meteredPrompt.value = MeteredDownloadPrompt(
                        displayName = record.displayName,
                        sizeBytes = record.totalBytes,
                        recordId = id
                    )
                } else {
                    _uiError.value = error
                }
            } else {
                _uiError.value = error
            }
        }
    }

    fun cancel(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.cancel(id)
        }
    }

    fun delete(id: String) {
        if (_focusRecordId.value == id) _focusRecordId.value = null
        viewModelScope.launch(Dispatchers.IO) {
            repository.delete(id)
        }
    }

    fun useIso(id: String) {
        val record = store.find(id) ?: return
        if (record.status != DownloadStatus.COMPLETED || !record.checksumVerified) return
        viewModelScope.launch(Dispatchers.IO) {
            val actualSize = DownloadDestination.fileSize(
                appContext,
                record.destinationUri.toUri()
            )
            val error = when {
                actualSize == null -> DownloadError(DownloadErrorCode.DESTINATION_UNAVAILABLE)
                record.totalBytes > 0 && actualSize != record.totalBytes ->
                    DownloadError(DownloadErrorCode.FILE_CHANGED)
                else -> null
            }
            if (error != null) {
                repository.markFailed(id, error)
                _uiError.value = error
                return@launch
            }
            // The record may have been deleted while the size check ran.
            if (store.find(id) == null) return@launch
            _readyIso.value = ReadyIso(
                destinationUri = record.destinationUri,
                displayName = record.fileName,
                sizeBytes = record.totalBytes
            )
            _focusRecordId.value = null
        }
    }

    fun consumeReadyIso() {
        _readyIso.value = null
    }

    fun focus(id: String) {
        _focusRecordId.value = id
    }

    fun dismissFocus() {
        _focusRecordId.value = null
    }

    fun dismissError() {
        _uiError.value = null
    }
}
