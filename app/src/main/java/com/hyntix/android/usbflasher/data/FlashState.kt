package com.hyntix.android.usbflasher.data

import android.hardware.usb.UsbDevice
import android.net.Uri

/**
 * Information about a connected USB mass storage device
 */
data class UsbDeviceInfo(
    val name: String,
    val vendorId: Int,
    val productId: Int,
    val capacityBytes: Long,
    val hasPermission: Boolean = false,
    val device: UsbDevice
) {
    val capacityFormatted: String
        get() = formatBytes(capacityBytes)
    
    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
            else -> String.format("%.1f KB", bytes / 1_000.0)
        }
    }
}

/**
 * Progress information during flash/verify operations
 */
data class FlashProgress(
    val bytesWritten: Long = 0,
    val totalBytes: Long = 0,
    val speedBytesPerSecond: Long = 0,
    val etaSeconds: Long = 0
) {
    val percentage: Float
        get() = if (totalBytes > 0) (bytesWritten.toFloat() / totalBytes) * 100f else 0f
    
    val speedFormatted: String
        get() = when {
            speedBytesPerSecond >= 1_000_000 -> String.format("%.1f MB/s", speedBytesPerSecond / 1_000_000.0)
            speedBytesPerSecond >= 1_000 -> String.format("%.1f KB/s", speedBytesPerSecond / 1_000.0)
            else -> "$speedBytesPerSecond B/s"
        }
    
    val etaFormatted: String
        get() = when {
            etaSeconds >= 3600 -> String.format("%d:%02d:%02d", etaSeconds / 3600, (etaSeconds % 3600) / 60, etaSeconds % 60)
            etaSeconds >= 60 -> String.format("%d:%02d", etaSeconds / 60, etaSeconds % 60)
            else -> "${etaSeconds}s"
        }
}

/**
 * Selected image file information
 */
data class ImageFileInfo(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long
) {
    val sizeFormatted: String
        get() = when {
            sizeBytes >= 1_000_000_000 -> String.format("%.2f GB", sizeBytes / 1_000_000_000.0)
            sizeBytes >= 1_000_000 -> String.format("%.1f MB", sizeBytes / 1_000_000.0)
            else -> String.format("%.1f KB", sizeBytes / 1_000.0)
        }
}

/**
 * Sealed class representing all possible UI states
 */
sealed class FlashState {
    data object Idle : FlashState()
    
    data class Ready(
        val imageFile: ImageFileInfo,
        val selectedDevice: UsbDeviceInfo
    ) : FlashState()
    
    data class Flashing(
        val imageFile: ImageFileInfo,
        val device: UsbDeviceInfo,
        val progress: FlashProgress,
        val status: String? = null  // Show granular status
    ) : FlashState()
    
    data class Verifying(
        val imageFile: ImageFileInfo,
        val device: UsbDeviceInfo,
        val progress: FlashProgress,
        val status: String? = null  // Show granular status
    ) : FlashState()
    
    data class Success(
        val imageFile: ImageFileInfo,
        val device: UsbDeviceInfo,
        val verified: Boolean
    ) : FlashState()
    
    data class Error(
        val message: String,
        val imageFile: ImageFileInfo? = null,
        val device: UsbDeviceInfo? = null
    ) : FlashState()
    
    data object Cancelled : FlashState()
}
