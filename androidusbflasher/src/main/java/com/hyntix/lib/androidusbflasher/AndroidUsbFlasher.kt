package com.hyntix.lib.androidusbflasher

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.File
import com.hyntix.lib.androidusbflasher.UsbFlasher as NativeFlasher
import com.hyntix.lib.androidusbflasher.FlashCallback
import com.hyntix.lib.androidusbflasher.FlashPhase
// import com.hyntix.lib.androidusbflasher.FlasherError // UniFFI generates this

class AndroidUsbFlasher(private val context: Context) {

    // Helper interface for UI
    interface Callback {
        fun onProgress(phase: String, current: Long, total: Long)
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
            println("AndroidUsbFlasher: No Mass Storage interface found for ${device.deviceName}")
            return 0L
        }
        
        println("AndroidUsbFlasher: Found MS interface ${iface.id} for ${device.deviceName}")
        
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
            println("AndroidUsbFlasher: No IN endpoint found for ${device.deviceName}")
            return 0L
        }
        val outEndpoint = outEp ?: run {
            println("AndroidUsbFlasher: No OUT endpoint found for ${device.deviceName}")
            return 0L
        }
        
        println("AndroidUsbFlasher: Using endpoints IN=${inEndpoint.address}, OUT=${outEndpoint.address}")
        
        return try {
            val connection = manager.openDevice(device) ?: run {
                println("AndroidUsbFlasher: Failed to openDevice ${device.deviceName}")
                return 0L
            }
            println("AndroidUsbFlasher: Opened device ${device.deviceName}, FD=${connection.fileDescriptor}")
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
            println("AndroidUsbFlasher: Exception in getDeviceCapacity: ${e.message}")
            e.printStackTrace()
            0L
        }
    }

    fun isLinuxIso(isoFile: File): Boolean {
        return ParcelFileDescriptor.open(isoFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            try {
                nativeFlasher.isLinuxIso(pfd.fd)
            } catch (e: Exception) {
                false
            }
        }
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
                } catch (e: Exception) {
                    // Ignore
                }
            }
        }.start()
    }

    fun cancel() {
        nativeFlasher.cancel()
    }
}
