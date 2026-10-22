package com.hyntix.lib.androidusbflasher

import android.content.Context
import android.os.ParcelFileDescriptor
import com.hyntix.lib.androidusbflasher.UsbFlasher as NativeFlasher

class AndroidUsbFlasher(private val context: Context) {

    // Helper interface for UI
    interface Callback {
        fun onProgress(phase: String, current: Long, total: Long)
        /** Current file being copied (Windows only). */
        fun onFile(name: String) {}
        /** Persistence finished after a successful flash. `error` is set on failure. */
        fun onPersistenceComplete(result: PersistenceResult?, error: String?) {}
        fun onSuccess()
        fun onError(message: String)
    }

    private val nativeFlasher = NativeFlasher()

    fun getDeviceCapacity(device: android.hardware.usb.UsbDevice): Long {
        val manager = context.getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager
        
        // Find mass storage interface
        var massStorageIface: android.hardware.usb.UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == android.hardware.usb.UsbConstants.USB_CLASS_MASS_STORAGE) {
                massStorageIface = iface
                break
            }
        }
        
        val iface = massStorageIface ?: run {
            android.util.Log.e("AndroidUsbFlasher", "No Mass Storage interface found for ${device.deviceName}")
            return 0L
        }
        
        android.util.Log.d("AndroidUsbFlasher", "Found MS interface ${iface.id} for ${device.deviceName}")
        
        // Find endpoints
        var inEp: android.hardware.usb.UsbEndpoint? = null
        var outEp: android.hardware.usb.UsbEndpoint? = null
        
        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == android.hardware.usb.UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == android.hardware.usb.UsbConstants.USB_DIR_IN) {
                    inEp = ep
                } else {
                    outEp = ep
                }
            }
        }
        
        val inEndpoint = inEp ?: run {
            android.util.Log.e("AndroidUsbFlasher", "No IN endpoint found for ${device.deviceName}")
            return 0L
        }
        val outEndpoint = outEp ?: run {
            android.util.Log.e("AndroidUsbFlasher", "No OUT endpoint found for ${device.deviceName}")
            return 0L
        }
        
        android.util.Log.d("AndroidUsbFlasher", "Using endpoints IN=${inEndpoint.address}, OUT=${outEndpoint.address}")
        
        return try {
            val connection = manager.openDevice(device) ?: run {
                android.util.Log.e("AndroidUsbFlasher", "Failed to openDevice ${device.deviceName}")
                return 0L
            }
            android.util.Log.d("AndroidUsbFlasher", "Opened device ${device.deviceName}, FD=${connection.fileDescriptor}")
            try {
                if (connection.claimInterface(iface, true)) {
                     android.util.Log.d("AndroidUsbFlasher", "getDeviceCapacity: Claimed interface ${iface.id}")
                     val capacity = nativeFlasher.getDeviceCapacity(
                         connection.fileDescriptor,
                         iface.id.toUByte(),
                         inEndpoint.address.toUByte(),
                         outEndpoint.address.toUByte()
                     ).toLong()
                     android.util.Log.d("AndroidUsbFlasher", "getDeviceCapacity: Native capacity result: $capacity")
                     capacity
                } else {
                    android.util.Log.e("AndroidUsbFlasher", "getDeviceCapacity: Failed to claim interface ${iface.id}")
                    0L
                }
            } finally {
                connection.close()
            }
        } catch (e: Exception) {
            android.util.Log.e("AndroidUsbFlasher", "Exception in getDeviceCapacity", e)
            0L
        }
    }

    fun isLinuxIso(pfd: ParcelFileDescriptor): Boolean {
        return try {
            nativeFlasher.isLinuxIso(pfd.fd)
        } catch (_: Exception) {
            false
        }
    }

    fun isWindowsIso(pfd: ParcelFileDescriptor): Boolean {
        return try {
            nativeFlasher.isWindowsIso(pfd.fd)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Probe a Linux ISO for persistence support. Returns null when the image is
     * not a supported live ISO or the probe fails; an unsupported ISO must
     * never prevent normal flashing.
     */
    fun probePersistence(pfd: ParcelFileDescriptor): PersistenceSupport? {
        return try {
            nativeFlasher.probePersistence(pfd.fd)
        } catch (e: Exception) {
            android.util.Log.w("AndroidUsbFlasher", "probePersistence failed: ${e.message}", e)
            null
        }
    }

    fun flashWindows(
        pfd: ParcelFileDescriptor,
        usbFd: Int,
        interfaceId: Int,
        inEndpoint: Int,
        outEndpoint: Int,
        callback: Callback
    ) {
        val nativeCallback = object : FlashCallback {
            override fun onProgress(phase: FlashPhase, current: ULong, total: ULong) {
                val phaseStr = when (phase) {
                    FlashPhase.VALIDATING -> "Validating"
                    FlashPhase.FORMATTING -> "Formatting"
                    FlashPhase.FLASHING -> "Flashing"
                    FlashPhase.VERIFYING -> "Verifying"
                    FlashPhase.FINALIZING -> "Finalizing"
                }
                callback.onProgress(phaseStr, current.toLong(), total.toLong())
            }

            override fun onFile(name: String) {
                callback.onFile(name)
            }
        }

        Thread {
            try {
                android.util.Log.d("AndroidUsbFlasher", "flashWindows: Calling native flashWindowsDevice with usbFd=$usbFd")
                nativeFlasher.flashWindowsDevice(
                    pfd.fd,
                    usbFd,
                    interfaceId.toUByte(),
                    inEndpoint.toUByte(),
                    outEndpoint.toUByte(),
                    nativeCallback
                )
                android.util.Log.d("AndroidUsbFlasher", "flashWindows: Native flashWindowsDevice returned success")
                callback.onSuccess()
            } catch (e: Exception) {
                android.util.Log.e("AndroidUsbFlasher", "flashWindows: Native flashWindowsDevice failed: ${e.message}", e)
                callback.onError(e.message ?: "Unknown error")
            } finally {
                try {
                    pfd.close()
                } catch (_: Exception) {
                    // Ignore
                }
            }
        }.start()
    }

    fun flashRaw(
        pfd: ParcelFileDescriptor,
        usbFd: Int,
        interfaceId: Int,
        inEndpoint: Int,
        outEndpoint: Int,
        verify: Boolean,
        callback: Callback
    ) {
        
        val nativeCallback = object : FlashCallback {
            override fun onProgress(phase: FlashPhase, current: ULong, total: ULong) {
                // Map FlashPhase enum to String for UI
                val phaseStr = when (phase) {
                    FlashPhase.VALIDATING -> "Validating"
                    FlashPhase.FORMATTING -> "Formatting"
                    FlashPhase.FLASHING -> "Flashing"
                    FlashPhase.VERIFYING -> "Verifying"
                    FlashPhase.FINALIZING -> "Finalizing"
                }
                callback.onProgress(phaseStr, current.toLong(), total.toLong())
            }

            override fun onFile(name: String) {
                callback.onFile(name)
            }
        }

        Thread {
            try {
                android.util.Log.d("AndroidUsbFlasher", "flashRaw: Calling native flashDevice with usbFd=$usbFd")
                nativeFlasher.flashDevice(
                    pfd.fd,
                    usbFd,
                    interfaceId.toUByte(),
                    inEndpoint.toUByte(),
                    outEndpoint.toUByte(),
                    verify,
                    nativeCallback
                )
                android.util.Log.d("AndroidUsbFlasher", "flashRaw: Native flashDevice returned success")
                callback.onSuccess()
            } catch (e: Exception) {
                android.util.Log.e("AndroidUsbFlasher", "flashRaw: Native flashDevice failed: ${e.message}", e)
                callback.onError(e.message ?: "Unknown error")
            } finally {
                try {
                    pfd.close()
                } catch (_: Exception) {
                    // Ignore
                }
            }
        }.start()
    }

    /**
     * Flash a Linux ISO, then append or repair its persistence partition while
     * the USB connection and image descriptor stay open. A persistence failure
     * is reported as a partial success so the bootable USB remains usable.
     */
    fun flashRawWithPersistence(
        pfd: ParcelFileDescriptor,
        usbFd: Int,
        interfaceId: Int,
        inEndpoint: Int,
        outEndpoint: Int,
        verify: Boolean,
        profile: PersistenceProfile,
        sizeBytes: Long,
        callback: Callback
    ) {
        val nativeCallback = flashCallback(callback)
        var imageWritten = false

        Thread {
            try {
                android.util.Log.d(
                    "AndroidUsbFlasher",
                    "flashRawWithPersistence: Calling native flashDevice with usbFd=$usbFd"
                )
                nativeFlasher.flashDevice(
                    pfd.fd,
                    usbFd,
                    interfaceId.toUByte(),
                    inEndpoint.toUByte(),
                    outEndpoint.toUByte(),
                    verify,
                    nativeCallback
                )
                imageWritten = true
                android.util.Log.d(
                    "AndroidUsbFlasher",
                    "flashRawWithPersistence: Applying persistence"
                )
                val result = nativeFlasher.applyPersistence(
                    pfd.fd,
                    usbFd,
                    interfaceId.toUByte(),
                    inEndpoint.toUByte(),
                    outEndpoint.toUByte(),
                    profile,
                    sizeBytes.toULong(),
                    persistenceCallback(callback)
                )
                callback.onPersistenceComplete(result, null)
                callback.onSuccess()
            } catch (e: Exception) {
                if (imageWritten) {
                    android.util.Log.e(
                        "AndroidUsbFlasher",
                        "flashRawWithPersistence: Persistence failed: ${e.message}",
                        e
                    )
                    callback.onPersistenceComplete(null, e.message ?: "Unknown error")
                    callback.onSuccess()
                } else {
                    android.util.Log.e(
                        "AndroidUsbFlasher",
                        "flashRawWithPersistence: Flash failed: ${e.message}",
                        e
                    )
                    callback.onError(e.message ?: "Unknown error")
                }
            } finally {
                try {
                    pfd.close()
                } catch (_: Exception) {
                    // Ignore
                }
            }
        }.start()
    }

    /** Retry persistence on an already-flashed USB without rewriting the ISO. */
    fun applyPersistence(
        pfd: ParcelFileDescriptor,
        usbFd: Int,
        interfaceId: Int,
        inEndpoint: Int,
        outEndpoint: Int,
        profile: PersistenceProfile,
        sizeBytes: Long,
        callback: Callback
    ) {
        Thread {
            try {
                val result = nativeFlasher.applyPersistence(
                    pfd.fd,
                    usbFd,
                    interfaceId.toUByte(),
                    inEndpoint.toUByte(),
                    outEndpoint.toUByte(),
                    profile,
                    sizeBytes.toULong(),
                    persistenceCallback(callback)
                )
                callback.onPersistenceComplete(result, null)
            } catch (e: Exception) {
                android.util.Log.e(
                    "AndroidUsbFlasher",
                    "applyPersistence: Persistence failed: ${e.message}",
                    e
                )
                callback.onPersistenceComplete(null, e.message ?: "Unknown error")
            } finally {
                try {
                    pfd.close()
                } catch (_: Exception) {
                    // Ignore
                }
                callback.onSuccess()
            }
        }.start()
    }

    private fun flashCallback(callback: Callback): FlashCallback {
        return object : FlashCallback {
            override fun onProgress(phase: FlashPhase, current: ULong, total: ULong) {
                val phaseStr = when (phase) {
                    FlashPhase.VALIDATING -> "Validating"
                    FlashPhase.FORMATTING -> "Formatting"
                    FlashPhase.FLASHING -> "Flashing"
                    FlashPhase.VERIFYING -> "Verifying"
                    FlashPhase.FINALIZING -> "Finalizing"
                }
                callback.onProgress(phaseStr, current.toLong(), total.toLong())
            }

            override fun onFile(name: String) {
                callback.onFile(name)
            }
        }
    }

    private fun persistenceCallback(callback: Callback): PersistenceCallback {
        return object : PersistenceCallback {
            override fun onProgress(phase: PersistencePhase, current: ULong, total: ULong) {
                // Keep the same CamelCase convention as FlashPhase so the
                // ViewModel and UI can share their stage labels.
                val phaseName = when (phase) {
                    PersistencePhase.PLANNING -> "Planning"
                    PersistencePhase.FORMATTING -> "Formatting"
                    PersistencePhase.CONFIGURING -> "Configuring"
                    PersistencePhase.ACTIVATING_BOOT -> "ActivatingBoot"
                    PersistencePhase.PARTITIONING -> "Partitioning"
                    PersistencePhase.VERIFYING -> "Verifying"
                }
                callback.onProgress(
                    "Persistence:$phaseName",
                    current.toLong(),
                    total.toLong()
                )
            }
        }
    }

    fun cancel() {
        nativeFlasher.cancel()
    }
}
