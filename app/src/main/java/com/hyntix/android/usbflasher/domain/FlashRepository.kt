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
import com.hyntix.android.usbflasher.util.AppLogger
import java.util.concurrent.ConcurrentHashMap

class FlashRepository(
    val context: Context,
    private val usbFlasher: AndroidUsbFlasher
) {
    fun isWindowsIso(pfd: ParcelFileDescriptor): Boolean {
        return usbFlasher.isWindowsIso(pfd)
    }
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
                        AppLogger.d("FlashRepository", "scanDevices: Waiting for usbMutex for ${device.deviceName}")
                        usbMutex.withLock {
                            AppLogger.d("FlashRepository", "scanDevices: Acquired usbMutex for ${device.deviceName}")
                            try {
                                capacity = usbFlasher.getDeviceCapacity(device)
                                if (capacity > 0) {
                                    capacityCache[device.deviceName] = capacity
                                }
                            } catch (e: Exception) {
                                AppLogger.e("FlashRepository", "Capacity probe failed", e)
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
        AppLogger.d("FlashRepository", "flashDevice: Waiting for usbMutex")
        usbMutex.withLock {
            AppLogger.d("FlashRepository", "flashDevice: Acquired usbMutex")
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
        AppLogger.d("FlashRepository", "performFlash: Starting for ${device.deviceName}")
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
            AppLogger.e("FlashRepository", "performFlash: No MS interface found")
            callback.onError("Not a supported USB drive.")
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
             AppLogger.e("FlashRepository", "performFlash: Bulk endpoints not found")
             callback.onError("Unable to communicate with drive.")
             return
        }
        
        AppLogger.d("FlashRepository", "performFlash: Opening device")
        val connection = manager.openDevice(device)
        if (connection == null) {
             AppLogger.e("FlashRepository", "performFlash: openDevice failed")
             callback.onError("USB permission denied. Reconnect and try again.")
             return
        }

        AppLogger.d("FlashRepository", "performFlash: Claiming interface ${msInterface.id}")
        if (!connection.claimInterface(msInterface, true)) {
             AppLogger.e("FlashRepository", "performFlash: claimInterface failed")
             connection.close()
             callback.onError("Drive is in use by another app.")
             return
        }
        
        val pfd = try {
            AppLogger.d("FlashRepository", "performFlash: Opening PFD for ${image.uri}")
            context.contentResolver.openFileDescriptor(image.uri, "r")
        } catch (e: Exception) {
            AppLogger.e("FlashRepository", "performFlash: Failed to open PFD", e)
            connection.close()
            callback.onError("Cannot read the selected file.")
            return
        }

        if (pfd == null) {
            AppLogger.e("FlashRepository", "performFlash: PFD is null")
            connection.close()
            callback.onError("Cannot read the selected file.")
            return
        }

        val isWindows = image.isWindows
        AppLogger.d("FlashRepository", "performFlash: isWindows=$isWindows")

        val flashCallback = object : AndroidUsbFlasher.Callback {
            override fun onProgress(phase: String, current: Long, total: Long) {
                 callback.onProgress(phase, current, total)
            }
            override fun onSuccess() {
                 AppLogger.d("FlashRepository", "performFlash: Success, closing handles")
                 connection.close()
                 pfd.close()
                 callback.onSuccess()
            }
            override fun onError(message: String) {
                 AppLogger.e("FlashRepository", "performFlash: Error: $message")
                 connection.close()
                 pfd.close()
                 callback.onError(message)
            }
        }

        if (isWindows) {
            AppLogger.d("FlashRepository", "performFlash: Calling usbFlasher.flashWindows")
            usbFlasher.flashWindows(
                pfd,
                connection.fileDescriptor,
                msInterface.id,
                inEp.address,
                outEp.address,
                flashCallback
            )
        } else {
            AppLogger.d("FlashRepository", "performFlash: Calling usbFlasher.flashRaw")
            usbFlasher.flashRaw(
                pfd,
                connection.fileDescriptor,
                msInterface.id,
                inEp.address,
                outEp.address,
                true,
                flashCallback
            )
        }
    }

    fun clearCache() {
        capacityCache.clear()
    }

    fun cancel() {
        usbFlasher.cancel()
    }

    /**
     * Eject a USB drive by sending SCSI START STOP UNIT command.
     * This makes it safe to physically unplug the drive.
     */
    fun ejectDevice(deviceInfo: UsbDeviceInfo) {
        val device = deviceInfo.device
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager

        if (!manager.hasPermission(device)) {
            AppLogger.w("FlashRepository", "ejectDevice: No permission, skipping eject")
            return
        }

        // Find mass storage interface and endpoints
        var msInterface: android.hardware.usb.UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_MASS_STORAGE) {
                msInterface = iface
                break
            }
        }
        if (msInterface == null) return

        var inEp: android.hardware.usb.UsbEndpoint? = null
        var outEp: android.hardware.usb.UsbEndpoint? = null
        for (i in 0 until msInterface.endpointCount) {
            val ep = msInterface.getEndpoint(i)
            if (ep.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) inEp = ep
                else outEp = ep
            }
        }
        if (inEp == null || outEp == null) return

        val connection = manager.openDevice(device) ?: return
        if (!connection.claimInterface(msInterface, true)) {
            connection.close()
            return
        }

        try {
            // Build CBW for SCSI START STOP UNIT (opcode 0x1B)
            // LoEj=1, Start=0 → eject the media
            val cbw = ByteArray(31)
            // Signature: "USBC" (0x55534243)
            cbw[0] = 0x55; cbw[1] = 0x53; cbw[2] = 0x42; cbw[3] = 0x43
            // Tag (arbitrary)
            cbw[4] = 0x01; cbw[5] = 0x00; cbw[6] = 0x00; cbw[7] = 0x00
            // DataTransferLength: 0
            // Flags: 0x00 (Host to Device)
            // LUN: 0
            cbw[14] = 6 // CB Length
            // SCSI command: START STOP UNIT
            cbw[15] = 0x1B // Opcode
            cbw[19] = 0x02 // LoEj=1, Start=0

            connection.bulkTransfer(outEp, cbw, cbw.size, 5000)

            // Read CSW
            val csw = ByteArray(13)
            connection.bulkTransfer(inEp, csw, csw.size, 5000)

            AppLogger.i("FlashRepository", "ejectDevice: Drive ejected successfully")
        } catch (e: Exception) {
            AppLogger.e("FlashRepository", "ejectDevice: Eject failed", e)
        } finally {
            connection.releaseInterface(msInterface)
            connection.close()
        }
    }
}
