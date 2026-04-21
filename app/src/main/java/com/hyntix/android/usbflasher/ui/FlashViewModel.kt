package com.hyntix.android.usbflasher.ui

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hyntix.android.usbflasher.data.*
import com.hyntix.android.usbflasher.domain.FlashRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.Dispatchers
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

    // Auto-scan job
    private var scanJob: Job? = null

    fun startDeviceScan() {
        if (scanJob?.isActive == true) return
        triggerScan() // Initial scan
        scanJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(10000) // Polling fallback (10s)
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
        val image = ImageFileInfo(uri, name, size)
        _selectedImageInfo.value = image
        checkReadyState()
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
        
        // Normally we'd need to find endpoints again or store them in UsbDeviceInfo
        // For simplicity, we assume we can get them from the device object again.
        // We'll delegate finding endpoints to repo/flasher logic helper if possible,
        // but for now let's assume we pass what we have.
        // Wait, FlashRepository.flashImage takes raw Ints.
        // We need to resolve them.
        
        viewModelScope.launch(Dispatchers.IO) {
            stopDeviceScan() // Pause scan during flash
            _state.value = FlashState.Flashing(image, device, FlashProgress(0, image.sizeBytes))
            
            // We need to resolve USB connection params.
            // This is a UI-layer/ViewModel logic simplification.
            // Ideally Repo handles this resolution.
            // Let's modify Repo to take UsbDevice!
            
            repository.flashDevice(image, device, object : FlashRepository.FlashCallback {
                private var lastSpeedTime = android.os.SystemClock.elapsedRealtime()
                private var lastSpeedBytes = 0L
                private var currentSpeed = 0L
                
                private var lastPhase = ""
                private var lastUpdateTime = 0L

                override fun onProgress(phase: String, current: Long, total: Long) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    val phaseChanged = phase != lastPhase
                    
                    // Reset speed counters if phase changed (e.g. Flashing -> Verifying)
                    if (phaseChanged) {
                        lastPhase = phase
                        lastSpeedBytes = current
                        lastSpeedTime = now
                        currentSpeed = 0L
                    }
                    
                    val speedDeltaMs = now - lastSpeedTime
                    if (speedDeltaMs >= 1000) { // 1-second rolling window for stable speed
                        currentSpeed = ((current - lastSpeedBytes) * 1000) / speedDeltaMs
                        lastSpeedBytes = current
                        lastSpeedTime = now
                    }
                    
                    // Update UI at 10Hz (100ms) OR immediately on phase change
                    if (now - lastUpdateTime >= 100 || phaseChanged) {
                        lastUpdateTime = now
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
                    _state.value = FlashState.Error(message, image, device)
                    startDeviceScan() // Resume scan
                }
            })
        }
    }

    fun cancel() {
        repository.cancel()
        _state.value = FlashState.Idle
        startDeviceScan() // Resume scan
        // Keep selections? Yes.
        checkReadyState() 
    }
}
