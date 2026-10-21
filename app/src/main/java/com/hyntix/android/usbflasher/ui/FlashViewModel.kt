package com.hyntix.android.usbflasher.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyntix.android.usbflasher.R
import com.hyntix.android.usbflasher.data.*
import com.hyntix.android.usbflasher.domain.FlashRepository
import com.hyntix.android.usbflasher.util.AppLogger
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FlashViewModel(
    private val repository: FlashRepository
) : ViewModel() {

    init {
        repository.setOnDevicesChangedListener {
            if (_state.value !is FlashState.Flashing &&
                _state.value !is FlashState.Verifying &&
                _state.value !is FlashState.Cancelling
            ) {
                triggerScan()
            }
        }
    }

    private fun triggerScan() {
        viewModelScope.launch(Dispatchers.IO) {
            val devices = repository.scanDevices()
            _availableDevices.value = devices
            processDeviceList(devices)
        }
    }

    private val _state = MutableStateFlow<FlashState>(FlashState.Idle)
    val state: StateFlow<FlashState> = _state.asStateFlow()

    private val _availableDevices = MutableStateFlow<List<UsbDeviceInfo>>(emptyList())
    val availableDevices: StateFlow<List<UsbDeviceInfo>> = _availableDevices.asStateFlow()

    private val _selectedImageInfo = MutableStateFlow<ImageFileInfo?>(null)
    val selectedImageInfo: StateFlow<ImageFileInfo?> = _selectedImageInfo.asStateFlow()

    private val _selectedDeviceInfo = MutableStateFlow<UsbDeviceInfo?>(null)
    val selectedDeviceInfo: StateFlow<UsbDeviceInfo?> = _selectedDeviceInfo.asStateFlow()

    // One-shot UI feedback (Snackbar messages)
    private val _feedbackMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val feedbackMessage: SharedFlow<String> = _feedbackMessage.asSharedFlow()

    // Serializes cancel requests with native callbacks so a late success/error
    // can never overwrite the cancelling state or re-show the progress sheet.
    private val flashLock = Any()
    @Volatile
    private var cancelRequested = false
    @Volatile
    private var activeFlashId = 0L

    // Auto-scan job
    private var scanJob: Job? = null

    fun startDeviceScan() {
        if (scanJob?.isActive == true) return
        triggerScan() // Initial scan
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(3000) // Polling fallback (3s)
                if (_state.value !is FlashState.Flashing &&
                    _state.value !is FlashState.Verifying &&
                    _state.value !is FlashState.Cancelling
                ) {
                    val devices = repository.scanDevices()
                    _availableDevices.value = devices
                    processDeviceList(devices)
                }
            }
        }
    }

    private fun processDeviceList(devices: List<UsbDeviceInfo>) {
        // Auto-select if exactly one device is found and none is selected
        if (devices.size == 1 && _selectedDeviceInfo.value == null) {
            onDeviceSelected(devices[0])
        } else if (devices.isEmpty()) {
            _selectedDeviceInfo.value = null
        }
        
        // Update selected device info (capacity/permission) if it changed
        _selectedDeviceInfo.value?.let { selected ->
            devices.find { it.device.deviceName == selected.device.deviceName }?.let { updated ->
                if (updated != selected) {
                    _selectedDeviceInfo.value = updated
                    checkReadyState()
                }
            }
        }
        
        // Deselect if currently selected disconnected
        val current = _selectedDeviceInfo.value
        if (current != null && devices.none { it.device.deviceName == current.device.deviceName }) {
            _selectedDeviceInfo.value = null
            checkReadyState()
        }
    }
    
    fun stopDeviceScan() {
        scanJob?.cancel()
    }

    fun onFileSelected(uri: Uri, name: String, size: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = repository.context
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            if (pfd != null) {
                val isLinux = repository.isLinuxIso(pfd)
                val isWindows = repository.isWindowsIso(pfd)
                pfd.close()

                if (!isLinux && !isWindows) {
                    _feedbackMessage.tryEmit(
                        repository.context.getString(R.string.file_invalid, name)
                    )
                    return@launch
                }

                val image = ImageFileInfo(uri, name, size, isWindows)
                _selectedImageInfo.value = image
                checkReadyState()
            } else {
                _feedbackMessage.tryEmit(repository.context.getString(R.string.file_cannot_open))
            }
        }
    }

    fun onDeviceSelected(device: UsbDeviceInfo) {
        _selectedDeviceInfo.value = device
        checkReadyState()
    }

    private fun checkReadyState() {
        val image = _selectedImageInfo.value
        val device = _selectedDeviceInfo.value
        
        if (image != null && device != null) {
            if (_state.value !is FlashState.Flashing &&
                _state.value !is FlashState.Verifying &&
                _state.value !is FlashState.Cancelling
            ) {
                 _state.value = FlashState.Ready(image, device)
            }
        } else {
             if (_state.value is FlashState.Ready) {
                 _state.value = FlashState.Idle
             }
        }
    }

    fun startFlash() {
        val image = _selectedImageInfo.value ?: return
        val device = _selectedDeviceInfo.value ?: return

        val flashId = synchronized(flashLock) {
            val current = _state.value
            if (current is FlashState.Flashing ||
                current is FlashState.Verifying ||
                current is FlashState.Cancelling ||
                current is FlashState.Success
            ) {
                return
            }
            cancelRequested = false
            ++activeFlashId
            // Move to Flashing synchronously so an immediate Cancel can never
            // race the coroutine and miss the active session.
            _state.value = FlashState.Flashing(image, device, FlashProgress(0, image.sizeBytes))
            activeFlashId
        }

        viewModelScope.launch(Dispatchers.IO) {
            stopDeviceScan()
            repository.flashDevice(image, device, object : FlashRepository.FlashCallback {
                private val speedSamples = ArrayDeque<SpeedSample>()
                private var currentSpeed = 0L
                private var lastPhase = ""
                private var lastUpdateTime = 0L
                private var lastEmittedCurrent = 0L
                private var latestFile: String? = null
                private var displayedFile: String? = null
                private var lastFileLabelTime = 0L

                /**
                 * Windows file hint. This is a low-frequency channel separate
                 * from physical progress; it never advances the byte counter.
                 */
                @Synchronized
                override fun onFile(name: String) {
                    if (flashId != activeFlashId || cancelRequested) return
                    val displayName = name.substringAfterLast('/').ifBlank { name }
                    if (displayName == latestFile) return
                    latestFile = displayName

                    val previousDisplayed = displayedFile
                    advanceFileLabel(android.os.SystemClock.elapsedRealtime())
                    if (displayedFile != previousDisplayed) {
                        synchronized(flashLock) {
                            if (flashId != activeFlashId || cancelRequested) return
                            val currentState = _state.value
                            if (currentState is FlashState.Flashing) {
                                _state.value = currentState.copy(currentFile = displayedFile)
                            }
                        }
                    }
                }

                /**
                 * Small files can be announced in bursts. Advance the displayed
                 * name at most 5 times/second; progress updates call this too,
                 * so a large file still appears within 200 ms.
                 */
                private fun advanceFileLabel(now: Long) {
                    if (latestFile == displayedFile) return
                    if (now - lastFileLabelTime < FILE_LABEL_INTERVAL_MS) return
                    displayedFile = latestFile
                    lastFileLabelTime = now
                }

                @Synchronized
                override fun onProgress(phase: String, current: Long, total: Long) {
                    if (flashId != activeFlashId || cancelRequested) return
                    val now = android.os.SystemClock.elapsedRealtime()
                    val phaseChanged = phase != lastPhase
                    // Never let a report move backwards inside a phase.
                    // Verification legitimately restarts at zero, so the clamp
                    // only applies while the phase stays the same.
                    val safeCurrent = if (phaseChanged) current
                        else maxOf(current, lastEmittedCurrent)

                    if (phaseChanged) {
                        lastPhase = phase
                        currentSpeed = 0L
                        speedSamples.clear()
                        lastEmittedCurrent = 0L
                        lastUpdateTime = 0L
                        AppLogger.i("FlashTelemetry", "Phase changed to: $phase")
                    }

                    // Every report from the native publisher is a real hardware
                    // position. Keep a 2 s sliding window so speed is honest,
                    // responsive, and decays to zero during a genuine stall.
                    speedSamples.addLast(SpeedSample(now, safeCurrent))
                    while (speedSamples.isNotEmpty() &&
                        now - speedSamples.first().timeMs > SPEED_WINDOW_MS
                    ) {
                        speedSamples.removeFirst()
                    }
                    if (speedSamples.size >= 2) {
                        val oldest = speedSamples.first()
                        val elapsedMs = now - oldest.timeMs
                        if (elapsedMs >= SPEED_MIN_SAMPLE_MS) {
                            val delta = (safeCurrent - oldest.bytes).coerceAtLeast(0)
                            currentSpeed = (delta * 1000L) / elapsedMs
                        }
                    }

                    advanceFileLabel(now)

                    // The native publisher already coalesces hardware updates
                    // and sends a 500 ms heartbeat. Cap UI state churn at 30 Hz;
                    // Compose's spring animates the bar at display refresh rate.
                    val shouldEmit = phaseChanged ||
                        safeCurrent >= total ||
                        now - lastUpdateTime >= UI_UPDATE_INTERVAL_MS
                    if (shouldEmit) {
                        lastUpdateTime = now
                        lastEmittedCurrent = safeCurrent
                        val remaining = (total - safeCurrent).coerceAtLeast(0)
                        val etaSeconds = if (currentSpeed > 0) remaining / currentSpeed else 0L
                        val progress = FlashProgress(safeCurrent, total, currentSpeed, etaSeconds)

                        synchronized(flashLock) {
                            if (flashId != activeFlashId || cancelRequested) return
                            val liveState = _state.value
                            if (liveState !is FlashState.Flashing &&
                                liveState !is FlashState.Verifying
                            ) {
                                return
                            }

                            if (phase.equals("Verifying", ignoreCase = true)) {
                                _state.value = FlashState.Verifying(
                                    image,
                                    device,
                                    progress,
                                    phase
                                )
                            } else {
                                _state.value = FlashState.Flashing(
                                    imageFile = image,
                                    device = device,
                                    progress = progress,
                                    status = phase,
                                    currentFile = displayedFile
                                )
                            }
                        }
                    }
                }

                override fun onSuccess() {
                    synchronized(flashLock) {
                        if (flashId != activeFlashId) return
                        if (cancelRequested) {
                            finishCancellationLocked()
                        } else {
                            activeFlashId++
                            _state.value = FlashState.Success(image, device, true)
                            startDeviceScan() // Resume scan
                        }
                    }
                }

                override fun onError(message: String) {
                    synchronized(flashLock) {
                        if (flashId != activeFlashId) return
                        if (cancelRequested) {
                            finishCancellationLocked()
                        } else {
                            activeFlashId++
                            _state.value = FlashState.Error(toUserMessage(message), image, device)
                            startDeviceScan()
                        }
                    }
                }
            })
        }
    }

    fun cancel() {
        synchronized(flashLock) {
            when (val current = _state.value) {
                is FlashState.Flashing ->
                    beginCancellationLocked(current.imageFile, current.device, current.progress)
                is FlashState.Verifying ->
                    beginCancellationLocked(current.imageFile, current.device, current.progress)
                is FlashState.Cancelling -> Unit
                else -> {
                    repository.cancel()
                    _state.value = FlashState.Idle
                    startDeviceScan()
                    checkReadyState()
                }
            }
        }
    }

    private fun beginCancellationLocked(
        image: ImageFileInfo,
        device: UsbDeviceInfo,
        progress: FlashProgress
    ) {
        cancelRequested = true
        _state.value = FlashState.Cancelling(image, device, progress)
        repository.cancel()
    }

    private fun finishCancellationLocked() {
        // Invalidate any callback still in flight for this session.
        activeFlashId++
        cancelRequested = false
        _state.value = FlashState.Idle
        _feedbackMessage.tryEmit(repository.context.getString(R.string.flash_cancelled))
        startDeviceScan()
        checkReadyState()
    }

    /** Called when user taps Done after a successful flash. Ejects the drive and clears state. */
    fun done() {
        val currentState = _state.value
        if (currentState is FlashState.Success) {
            viewModelScope.launch(Dispatchers.IO) {
                repository.ejectDevice(currentState.device)
                _selectedImageInfo.value = null
                _selectedDeviceInfo.value = null
                _state.value = FlashState.Idle
                startDeviceScan()
            }
        } else {
            cancel()
        }
    }

    /** Map raw Rust/technical errors to concise user-friendly messages. */
    private fun toUserMessage(raw: String): String {
        val lower = raw.lowercase()
        val ctx = repository.context
        return when {
            lower.contains("cancel") -> ctx.getString(R.string.err_cancelled)
            lower.contains("verification") || lower.contains("mismatch") || lower.contains("hash") ->
                ctx.getString(R.string.err_verification_failed)
            lower.contains("permission") || lower.contains("access") ->
                ctx.getString(R.string.err_usb_permission)
            lower.contains("disconnected") || lower.contains("detach") || lower.contains("no device") ->
                ctx.getString(R.string.err_disconnected)
            lower.contains("no space") || lower.contains("capacity") || lower.contains("too large") ->
                ctx.getString(R.string.err_too_large)
            lower.contains("usb") || lower.contains("pipe") || lower.contains("i/o") || lower.contains("ioctl") ->
                ctx.getString(R.string.err_usb_transfer)
            lower.contains("scsi") || lower.contains("csw") || lower.contains("cbw") ->
                ctx.getString(R.string.err_drive_communication)
            lower.contains("timeout") ->
                ctx.getString(R.string.err_timeout)
            else -> raw
        }
    }
}

private data class SpeedSample(val timeMs: Long, val bytes: Long)

/** Sliding window used to derive an honest hardware throughput. */
private const val SPEED_WINDOW_MS = 2_000L

/** Require at least this much history before showing a speed. */
private const val SPEED_MIN_SAMPLE_MS = 400L

/** Coalesce native publisher updates to 30 Hz. */
private const val UI_UPDATE_INTERVAL_MS = 33L

/** Throttle file-name label updates while copying many small files. */
private const val FILE_LABEL_INTERVAL_MS = 200L
