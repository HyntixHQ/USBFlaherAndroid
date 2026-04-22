package com.hyntix.android.usbflasher.domain

import android.content.Context
import android.net.Uri
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelFileDescriptor
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.hyntix.lib.androidusbflasher.AndroidUsbFlasher
import com.hyntix.android.usbflasher.data.UsbDeviceInfo
import com.hyntix.android.usbflasher.data.ImageFileInfo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

class FlashRepository(
    private val context: Context,
    private val usbFlasher: AndroidUsbFlasher
) {
    private val capacityCache = ConcurrentHashMap<String, Long>()
    private val usbMutex = Mutex()
    private var onDevicesChanged: (() -> Unit)? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
                intent?.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                onDevicesChanged?.invoke()
            }
        }
    }

    init {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        context.registerReceiver(usbReceiver, filter)
    }

    fun setOnDevicesChangedListener(listener: () -> Unit) {
        onDevicesChanged = listener
    }
    interface FlashCallback {
        fun onProgress(phase: String, current: Long, total: Long)
        fun onSuccess()
        fun onError(message: String)
    }

    suspend fun scanDevices(): List<UsbDeviceInfo> {
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        return manager.deviceList.values
            .filter { isMassStorage(it) }
            .map { device ->
                val hasPermission = manager.hasPermission(device)
                var capacity = 0L
                if (hasPermission) {
                    val cached = capacityCache[device.deviceName]
                    if (cached != null && cached > 0) {
                        capacity = cached
                    } else {
                        android.util.Log.d("FlashRepository", "scanDevices: Waiting for usbMutex for ${device.deviceName}")
                        usbMutex.withLock {
                            android.util.Log.d("FlashRepository", "scanDevices: Acquired usbMutex for ${device.deviceName}")
                            try {
                                capacity = usbFlasher.getDeviceCapacity(device)
                                if (capacity > 0) {
                                    capacityCache[device.deviceName] = capacity
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("FlashRepository", "Capacity probe failed", e)
                            }
                        }
                    }
                }
                
                UsbDeviceInfo(
                    name = "${device.manufacturerName?.trim() ?: "Generic"} ${device.productName?.trim() ?: "USB Drive"}".replace(Regex("\\s+"), " ").trim(),
                    vendorId = device.vendorId,
                    productId = device.productId,
                    capacityBytes = capacity,
                    hasPermission = hasPermission,
                    device = device
                )
            }
    }
    
    fun requestPermission(device: android.hardware.usb.UsbDevice, pendingIntent: android.app.PendingIntent) {
        val manager = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        manager.requestPermission(device, pendingIntent)
    }

    fun hasPermission(device: android.hardware.usb.UsbDevice): Boolean {
        val manager = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        return manager.hasPermission(device)
    }
    
    private fun isMassStorage(device: android.hardware.usb.UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
             if (device.getInterface(i).interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_MASS_STORAGE) {
                 return true
             }
        }
        return false
    }

    suspend fun flashDevice(
        image: ImageFileInfo,
        deviceInfo: UsbDeviceInfo,
        callback: FlashCallback
    ) {
        android.util.Log.d("FlashRepository", "flashDevice: Waiting for usbMutex")
        usbMutex.withLock {
            android.util.Log.d("FlashRepository", "flashDevice: Acquired usbMutex")
            val device = deviceInfo.device
            val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            
            // ... (rest of the logic)
            performFlash(image, device, manager, callback)
        }
    }

    private fun performFlash(
        image: ImageFileInfo,
        device: UsbDevice,
        manager: UsbManager,
        callback: FlashCallback
    ) {
        android.util.Log.d("FlashRepository", "performFlash: Starting for ${device.deviceName}")
        // Resolve Endpoints
        var msInterface: android.hardware.usb.UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_MASS_STORAGE) {
                msInterface = iface
                break
            }
        }
        
        if (msInterface == null) {
            android.util.Log.e("FlashRepository", "performFlash: No MS interface found")
            callback.onError("No mass storage interface found")
            return
        }
        
        var inEp: android.hardware.usb.UsbEndpoint? = null
        var outEp: android.hardware.usb.UsbEndpoint? = null
        for (i in 0 until msInterface.endpointCount) {
            val ep = msInterface.getEndpoint(i)
            if (ep.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) inEp = ep
                else outEp = ep
            }
        }
        
        if (inEp == null || outEp == null) {
             android.util.Log.e("FlashRepository", "performFlash: Bulk endpoints not found")
             callback.onError("Could not find bulk endpoints")
             return
        }
        
        android.util.Log.d("FlashRepository", "performFlash: Opening device")
        val connection = manager.openDevice(device)
        if (connection == null) {
             android.util.Log.e("FlashRepository", "performFlash: openDevice failed")
             callback.onError("Could not open device. Permission denied?")
             return
        }

        android.util.Log.d("FlashRepository", "performFlash: Claiming interface ${msInterface.id}")
        if (!connection.claimInterface(msInterface, true)) {
             android.util.Log.e("FlashRepository", "performFlash: claimInterface failed")
             connection.close()
             callback.onError("Could not claim interface")
             return
        }
        
        val pfd = try {
            android.util.Log.d("FlashRepository", "performFlash: Opening PFD for ${image.uri}")
            context.contentResolver.openFileDescriptor(image.uri, "r")
        } catch (e: Exception) {
            android.util.Log.e("FlashRepository", "performFlash: Failed to open PFD", e)
            connection.close()
            callback.onError("Failed to open source file: ${e.message}")
            return
        }

        if (pfd == null) {
            android.util.Log.e("FlashRepository", "performFlash: PFD is null")
            connection.close()
            callback.onError("Failed to open source file descriptor")
            return
        }

        android.util.Log.d("FlashRepository", "performFlash: Calling usbFlasher.flashRaw with usbFd=${connection.fileDescriptor}")
        usbFlasher.flashRaw(
            pfd,
            connection.fileDescriptor,
            msInterface.id,
            inEp.address,
            outEp.address,
            true,
            object : AndroidUsbFlasher.Callback {
                override fun onProgress(phase: String, current: Long, total: Long) {
                     callback.onProgress(phase, current, total)
                }
                override fun onSuccess() {
                     android.util.Log.d("FlashRepository", "performFlash: Success, closing handles")
                     connection.close()
                     pfd.close()
                     callback.onSuccess()
                }
                override fun onError(message: String) {
                     android.util.Log.e("FlashRepository", "performFlash: Error: $message")
                     connection.close()
                     pfd.close()
                     callback.onError(message)
                }
            }
        )
    }

    fun clearCache() {
        capacityCache.clear()
    }


    fun cancel() {
        usbFlasher.cancel()
    }
}
