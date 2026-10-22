package com.hyntix.android.usbflasher.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyntix.android.usbflasher.R
import com.hyntix.android.usbflasher.data.*
import com.hyntix.android.usbflasher.domain.FlashRepository
import com.hyntix.android.usbflasher.util.AppLogger
import com.hyntix.lib.androidusbflasher.PersistenceResult
import com.hyntix.lib.androidusbflasher.PersistenceSupport
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
            if (!isBusy(_state.value)) {
                triggerScan()
            }
        }
    }

    private fun isBusy(state: FlashState): Boolean =
        state is FlashState.Flashing ||
            state is FlashState.Verifying ||
            state is FlashState.Persisting ||
            state is FlashState.Cancelling

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
    @Volatile
    private var persistenceOutcome: PersistenceOutcome? = null

    // Auto-scan job
    private var scanJob: Job? = null

    fun startDeviceScan() {
        if (scanJob?.isActive == true) return
        triggerScan() // Initial scan
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(3000) // Polling fallback (3s)
                if (!isBusy(_state.value)) {
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
                    clampPersistenceToDevice()
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
                val persistenceSupport =
                    if (isLinux && !isWindows) repository.probePersistence(pfd) else null
                pfd.close()

                if (!isLinux && !isWindows) {
                    _feedbackMessage.tryEmit(
                        repository.context.getString(R.string.file_invalid, name)
                    )
                    return@launch
                }

                val image = ImageFileInfo(
                    uri = uri,
                    name = name,
                    sizeBytes = size,
                    isWindows = isWindows,
                    isLinux = isLinux,
                    persistenceSupport = persistenceSupport
                )
                _selectedImageInfo.value = image
                checkReadyState()
            } else {
                _feedbackMessage.tryEmit(repository.context.getString(R.string.file_cannot_open))
            }
        }
    }

    fun onDeviceSelected(device: UsbDeviceInfo) {
        _selectedDeviceInfo.value = device
        clampPersistenceToDevice()
        checkReadyState()
    }

    private fun checkReadyState() {
        val image = _selectedImageInfo.value
        val device = _selectedDeviceInfo.value
        
        if (image != null && device != null) {
            if (!isBusy(_state.value)) {
                 _state.value = FlashState.Ready(image, device)
            }
        } else {
             if (_state.value is FlashState.Ready) {
                 _state.value = FlashState.Idle
             }
        }
    }

    /** Enable or disable persistence for the selected Linux image. */
    fun setPersistenceEnabled(enabled: Boolean) {
        val image = _selectedImageInfo.value ?: return
        val support = image.persistenceSupport ?: return
        if (image.isWindows) return

        if (!enabled) {
            _selectedImageInfo.value = image.copy(persistence = null)
            checkReadyState()
            return
        }

        val available = persistenceAvailableBytes(image)
        if (available < support.minBytes.toLong()) {
            _feedbackMessage.tryEmit(
                repository.context.getString(R.string.persistence_no_space)
            )
            return
        }

        val size = choosePersistenceSize(support, available)
        _selectedImageInfo.value = image.copy(
            persistence = PersistenceSelection(
                profile = support.profile,
                activation = support.activation,
                sizeBytes = size,
                label = support.label
            )
        )
        checkReadyState()
    }

    /** Change the persistence partition size for the selected image. */
    fun setPersistenceSize(sizeBytes: Long) {
        val image = _selectedImageInfo.value ?: return
        val support = image.persistenceSupport ?: return
        val selection = image.persistence ?: return
        val available = persistenceAvailableBytes(image)
        if (available < support.minBytes.toLong()) {
            _feedbackMessage.tryEmit(
                repository.context.getString(R.string.persistence_no_space)
            )
            return
        }
        val clamped = sizeBytes
            .coerceIn(support.minBytes.toLong(), available)
        val rounded = (clamped / PERSISTENCE_ALIGNMENT_BYTES) * PERSISTENCE_ALIGNMENT_BYTES
        val size = if (rounded >= support.minBytes.toLong()) {
            rounded
        } else {
            support.minBytes.toLong()
        }
        _selectedImageInfo.value = image.copy(persistence = selection.copy(sizeBytes = size))
        checkReadyState()
    }

    private fun choosePersistenceSize(support: PersistenceSupport, available: Long): Long {
        val min = support.minBytes.toLong()
        val recommended = support.recommendedBytes.toLong()
        val target = recommended.coerceAtMost(available)
        val rounded = (target / PERSISTENCE_ALIGNMENT_BYTES) * PERSISTENCE_ALIGNMENT_BYTES
        return if (rounded >= min) rounded else min
    }

    /**
     * Shrink or clear the chosen persistence size when the target drive changes
     * to one with less free tail space.
     */
    private fun clampPersistenceToDevice() {
        val image = _selectedImageInfo.value ?: return
        val support = image.persistenceSupport ?: return
        val selection = image.persistence ?: return
        val device = _selectedDeviceInfo.value ?: return
        if (device.capacityBytes <= 0) return

        val available = persistenceAvailableBytes(image)
        val minimum = support.minBytes.toLong()
        if (available < minimum) {
            _selectedImageInfo.value = image.copy(persistence = null)
            _feedbackMessage.tryEmit(
                repository.context.getString(R.string.persistence_no_space)
            )
            return
        }
        if (selection.sizeBytes <= available) return

        val rounded = (available / PERSISTENCE_ALIGNMENT_BYTES) * PERSISTENCE_ALIGNMENT_BYTES
        _selectedImageInfo.value = image.copy(
            persistence = selection.copy(sizeBytes = rounded.coerceAtLeast(minimum))
        )
    }

    /**
     * Conservative free-tail estimate for the size chips. The native planner
     * clamps again against the real partition table.
     */
    private fun persistenceAvailableBytes(image: ImageFileInfo): Long {
        val capacity = _selectedDeviceInfo.value?.capacityBytes ?: 0L
        return (capacity - image.sizeBytes - PERSISTENCE_OVERHEAD_BYTES).coerceAtLeast(0L)
    }

    /**
     * Free-tail estimate the size chips may offer. The native planner performs
     * the authoritative check against the on-device partition table.
     */
    fun persistenceMaxBytes(): Long {
        val image = _selectedImageInfo.value ?: return 0L
        if (image.persistenceSupport == null) return 0L
        val available = persistenceAvailableBytes(image)
        return (available / PERSISTENCE_ALIGNMENT_BYTES) * PERSISTENCE_ALIGNMENT_BYTES
    }

    fun startFlash() {
        val image = _selectedImageInfo.value ?: return
        val device = _selectedDeviceInfo.value ?: return

        val flashId = synchronized(flashLock) {
            val current = _state.value
            if (isBusy(current) || current is FlashState.Success) {
                return
            }
            cancelRequested = false
            persistenceOutcome = null
            ++activeFlashId
            // Move to Flashing synchronously so an immediate Cancel can never
            // race the coroutine and miss the active session.
            _state.value = FlashState.Flashing(image, device, FlashProgress(0, image.sizeBytes))
            activeFlashId
        }

        viewModelScope.launch(Dispatchers.IO) {
            stopDeviceScan()
            repository.flashDevice(image, device, createFlashCallback(flashId, image, device))
        }
    }

    /**
     * Retry only the persistence stage after a partial success. The ISO
     * already written to the drive is left untouched.
     */
    fun retryPersistence() {
        val current = _state.value as? FlashState.Success ?: return
        if (current.persistence !is PersistenceOutcome.Failed) return
        val image = current.imageFile
        if (image.persistence == null) return
        val device = current.device

        val flashId = synchronized(flashLock) {
            if (isBusy(_state.value)) return
            cancelRequested = false
            persistenceOutcome = null
            ++activeFlashId
            _state.value = FlashState.Persisting(
                imageFile = image,
                device = device,
                progress = FlashProgress(0, 0),
                status = PERSISTENCE_PHASE_PLANNING
            )
            activeFlashId
        }

        viewModelScope.launch(Dispatchers.IO) {
            stopDeviceScan()
            repository.retryPersistence(
                image,
                device,
                createFlashCallback(flashId, image, device)
            )
        }
    }

    private fun createFlashCallback(
        flashId: Long,
        image: ImageFileInfo,
        device: UsbDeviceInfo
    ): FlashRepository.FlashCallback {
        return object : FlashRepository.FlashCallback {
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

                // Persistence reports are byte counts only while formatting;
                // the other stages are step markers (0/1). Keep them out of the
                // ISO speed/ETA window and render them as stages instead.
                if (phase.startsWith(PERSISTENCE_PHASE_PREFIX)) {
                    val stage = phase.removePrefix(PERSISTENCE_PHASE_PREFIX)
                    synchronized(flashLock) {
                        if (flashId != activeFlashId || cancelRequested) return
                        _state.value = FlashState.Persisting(
                            imageFile = image,
                            device = device,
                            progress = FlashProgress(current, total),
                            status = stage
                        )
                    }
                    return
                }

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

            override fun onPersistenceComplete(result: PersistenceResult?, error: String?) {
                synchronized(flashLock) {
                    if (flashId != activeFlashId) return
                    persistenceOutcome = when {
                        result != null -> PersistenceOutcome.Enabled(
                            sizeBytes = result.sizeBytes.toLong(),
                            activation = result.activation,
                            label = result.label,
                            reusedExisting = result.reusedExisting
                        )
                        error != null -> PersistenceOutcome.Failed(toPersistenceMessage(error))
                        else -> PersistenceOutcome.Failed(
                            repository.context.getString(R.string.persistence_error_unknown)
                        )
                    }
                }
            }

            override fun onSuccess() {
                synchronized(flashLock) {
                    if (flashId != activeFlashId) return
                    val outcome = persistenceOutcome
                    if (cancelRequested && outcome == null) {
                        finishCancellationLocked()
                    } else {
                        // Cancelling during persistence leaves a bootable ISO;
                        // report the partial result instead of hiding it.
                        cancelRequested = false
                        activeFlashId++
                        persistenceOutcome = null
                        _state.value = FlashState.Success(image, device, true, outcome)
                        startDeviceScan() // Resume scan
                    }
                }
            }

            override fun onError(message: String) {
                synchronized(flashLock) {
                    if (flashId != activeFlashId) return
                    // A retry only runs after a successful ISO write, so setup
                    // failures must not downgrade the drive to a generic error.
                    val retryingPersistence = _state.value is FlashState.Persisting
                    persistenceOutcome = null
                    if (cancelRequested && !retryingPersistence) {
                        finishCancellationLocked()
                    } else {
                        cancelRequested = false
                        activeFlashId++
                        _state.value = if (retryingPersistence) {
                            FlashState.Success(
                                imageFile = image,
                                device = device,
                                verified = true,
                                persistence = PersistenceOutcome.Failed(
                                    toPersistenceMessage(message)
                                )
                            )
                        } else {
                            FlashState.Error(toUserMessage(message), image, device)
                        }
                        startDeviceScan()
                    }
                }
            }
        }
    }

    fun cancel() {
        synchronized(flashLock) {
            when (val current = _state.value) {
                is FlashState.Flashing ->
                    beginCancellationLocked(current.imageFile, current.device, current.progress)
                is FlashState.Verifying ->
                    beginCancellationLocked(current.imageFile, current.device, current.progress)
                is FlashState.Persisting ->
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

    /** Map persistence errors to actionable messages. */
    private fun toPersistenceMessage(raw: String): String {
        val lower = raw.lowercase()
        val ctx = repository.context
        return when {
            lower.contains("cancel") -> ctx.getString(R.string.err_cancelled)
            lower.contains("space") || lower.contains("capacity") || lower.contains("too small") ->
                ctx.getString(R.string.persistence_error_no_space)
            lower.contains("hybrid") ->
                ctx.getString(R.string.persistence_error_hybrid)
            lower.contains("verif") || lower.contains("mismatch") ->
                ctx.getString(R.string.persistence_error_verify)
            lower.contains("mbr") || lower.contains("slot") || lower.contains("partition") ->
                ctx.getString(R.string.persistence_error_partition)
            lower.contains("unsupported") || lower.contains("profile") || lower.contains("iso") ->
                ctx.getString(R.string.persistence_error_unsupported)
            else -> ctx.getString(R.string.persistence_error_unknown)
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

/** Progress channel prefix used by the native persistence stage. */
private const val PERSISTENCE_PHASE_PREFIX = "Persistence:"

/** Stage shown before the first persistence progress callback arrives. */
private const val PERSISTENCE_PHASE_PLANNING = "Planning"

/** Persistence partitions are aligned to 1 MiB. */
private const val PERSISTENCE_ALIGNMENT_BYTES = 1L * 1024 * 1024

/** Reserve for partition metadata, alignment and the 1 MiB tail. */
private const val PERSISTENCE_OVERHEAD_BYTES = 16L * 1024 * 1024
