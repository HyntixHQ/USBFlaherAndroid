package com.hyntix.android.usbflasher.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
            if (_state.value !is FlashState.Flashing && _state.value !is FlashState.Verifying) {
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

    // Auto-scan job
    private var scanJob: Job? = null

    fun startDeviceScan() {
        if (scanJob?.isActive == true) return
        triggerScan() // Initial scan
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(3000) // Polling fallback (3s)
                if (_state.value !is FlashState.Flashing && _state.value !is FlashState.Verifying) {
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
                        "\"$name\" is not a supported ISO file. Please select a Linux or Windows installation ISO."
                    )
                    return@launch
                }

                val image = ImageFileInfo(uri, name, size, isWindows)
                _selectedImageInfo.value = image
                checkReadyState()
            } else {
                _feedbackMessage.tryEmit("Could not open the selected file.")
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
            if (_state.value !is FlashState.Flashing && _state.value !is FlashState.Verifying) {
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
        
        viewModelScope.launch(Dispatchers.IO) {
            stopDeviceScan()
            _state.value = FlashState.Flashing(image, device, FlashProgress(0, image.sizeBytes))
            
            repository.flashDevice(image, device, object : FlashRepository.FlashCallback {
                private var lastSpeedTime = android.os.SystemClock.elapsedRealtime()
                private var lastSpeedBytes = 0L
                private var currentSpeed = 0L
                private val speedHistory = ArrayDeque<Long>(3)
                
                private var lastPhase = ""
                private var lastUpdateTime = 0L
                private var lastEmittedCurrent = 0L
                private var lastEmittedPhase = ""

                override fun onProgress(phase: String, current: Long, total: Long) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val phaseChanged = phase != lastPhase
                    
                    if (phaseChanged) {
                        lastPhase = phase
                        lastSpeedBytes = current
                        lastSpeedTime = now
                        currentSpeed = 0L
                        speedHistory.clear()
                        lastEmittedCurrent = 0L
                        AppLogger.i("FlashTelemetry", "Phase changed to: $phase")
                    }
                    
                    val speedDeltaMs = now - lastSpeedTime
                    if (speedDeltaMs >= 1000) { 
                        val instSpeed = ((current - lastSpeedBytes) * 1000) / speedDeltaMs
                        
                        if (speedHistory.size >= 3) {
                            speedHistory.removeFirst()
                        }
                        speedHistory.addLast(instSpeed)
                        
                        currentSpeed = speedHistory.sum() / speedHistory.size
                        
                        AppLogger.i("FlashTelemetry", 
                            "[$phase] $instSpeed B/s | Smoothed: $currentSpeed B/s")
                        
                        lastSpeedBytes = current
                        lastSpeedTime = now
                    }
                    
                    // Update UI at 10Hz OR immediately on phase change
                    // Skip if nothing meaningfully changed to reduce object churn
                    val shouldEmit = (now - lastUpdateTime >= 100 || phaseChanged)
                            && (current != lastEmittedCurrent || phase != lastEmittedPhase)
                    if (shouldEmit) {
                        lastUpdateTime = now
                        lastEmittedCurrent = current
                        lastEmittedPhase = phase
                        val etaSeconds = if (currentSpeed > 0) (total - current) / currentSpeed else 0
                        
                        val progress = FlashProgress(current, total, currentSpeed, etaSeconds)

                        if (phase.equals("Verifying", ignoreCase = true)) {
                            _state.value = FlashState.Verifying(
                                image, 
                                device, 
                                progress,
                                phase
                            )
                        } else {
                            _state.value = FlashState.Flashing(
                                image, 
                                device, 
                                progress,
                                phase
                            )
                        }
                    }
                }

                override fun onSuccess() {
                    _state.value = FlashState.Success(image, device, true)
                    startDeviceScan() // Resume scan
                }

                override fun onError(message: String) {
                    _state.value = FlashState.Error(toUserMessage(message), image, device)
                    startDeviceScan()
                }
            })
        }
    }

    fun cancel() {
        repository.cancel()
        _state.value = FlashState.Idle
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
        return when {
            lower.contains("cancel") -> "Cancelled by user."
            lower.contains("verification") || lower.contains("mismatch") || lower.contains("hash") ->
                "Verification failed. Data may be corrupted."
            lower.contains("permission") || lower.contains("access") ->
                "USB permission denied. Reconnect and try again."
            lower.contains("disconnected") || lower.contains("detach") || lower.contains("no device") ->
                "USB drive was disconnected."
            lower.contains("no space") || lower.contains("capacity") || lower.contains("too large") ->
                "Image is larger than the drive."
            lower.contains("usb") || lower.contains("pipe") || lower.contains("i/o") || lower.contains("ioctl") ->
                "USB transfer failed. Try reconnecting the drive."
            lower.contains("scsi") || lower.contains("csw") || lower.contains("cbw") ->
                "Drive communication error. Try a different USB port."
            lower.contains("timeout") ->
                "Drive not responding. Please reconnect."
            // Already user-friendly messages from FlashRepository pass through as-is
            else -> raw
        }
    }
}
