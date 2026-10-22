package com.hyntix.android.usbflasher.data

import android.hardware.usb.UsbDevice
import android.net.Uri
import com.hyntix.lib.androidusbflasher.PersistenceActivation
import com.hyntix.lib.androidusbflasher.PersistenceProfile
import com.hyntix.lib.androidusbflasher.PersistenceSupport
import java.util.Locale

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
            bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
            else -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
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

    /** Human-readable hardware progress, e.g. "3.1 GB / 5.4 GB". */
    val bytesProgressFormatted: String
        get() = "${formatByteCount(bytesWritten)} / ${formatByteCount(totalBytes)}"
    
    val speedFormatted: String
        get() = when {
            speedBytesPerSecond >= 1_000_000 -> String.format(Locale.US, "%.1f MB/s", speedBytesPerSecond / 1_000_000.0)
            speedBytesPerSecond >= 1_000 -> String.format(Locale.US, "%.1f KB/s", speedBytesPerSecond / 1_000.0)
            else -> "$speedBytesPerSecond B/s"
        }
    
    val etaFormatted: String
        get() = when {
            etaSeconds >= 3600 -> String.format(Locale.US, "%d:%02d:%02d", etaSeconds / 3600, (etaSeconds % 3600) / 60, etaSeconds % 60)
            etaSeconds >= 60 -> String.format(Locale.US, "%d:%02d", etaSeconds / 60, etaSeconds % 60)
            else -> "${etaSeconds}s"
        }
}

/**
 * Selected image file information
 */
data class ImageFileInfo(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val isWindows: Boolean = false,
    val isLinux: Boolean = false,
    /** Probe result when the image is a supported live ISO. */
    val persistenceSupport: PersistenceSupport? = null,
    /** Non-null only while the user has enabled persistence. */
    val persistence: PersistenceSelection? = null
) {
    val sizeFormatted: String
        get() = when {
            sizeBytes >= 1_000_000_000 -> String.format(Locale.US, "%.2f GB", sizeBytes / 1_000_000_000.0)
            sizeBytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", sizeBytes / 1_000_000.0)
            else -> String.format(Locale.US, "%.1f KB", sizeBytes / 1_000.0)
        }
}

/** User-selected persistence configuration for a Linux live ISO. */
data class PersistenceSelection(
    val profile: PersistenceProfile,
    val activation: PersistenceActivation,
    val sizeBytes: Long,
    val label: String
) {
    val sizeFormatted: String
        get() = formatByteCount(sizeBytes)
}

/** Outcome of the optional persistence stage after a successful ISO write. */
sealed interface PersistenceOutcome {
    data class Enabled(
        val sizeBytes: Long,
        val activation: PersistenceActivation,
        val label: String,
        val reusedExisting: Boolean
    ) : PersistenceOutcome {
        val sizeFormatted: String
            get() = formatByteCount(sizeBytes)
    }

    data class Failed(val message: String) : PersistenceOutcome
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
        val status: String? = null,  // Show granular status
        val currentFile: String? = null  // Windows: file currently being copied
    ) : FlashState()
    
    data class Verifying(
        val imageFile: ImageFileInfo,
        val device: UsbDeviceInfo,
        val progress: FlashProgress,
        val status: String? = null  // Show granular status
    ) : FlashState()

    /** The ISO is written; the native persistence stage is running. */
    data class Persisting(
        val imageFile: ImageFileInfo,
        val device: UsbDeviceInfo,
        val progress: FlashProgress,
        val status: String
    ) : FlashState()

    data class Success(
        val imageFile: ImageFileInfo,
        val device: UsbDeviceInfo,
        val verified: Boolean,
        val persistence: PersistenceOutcome? = null
    ) : FlashState()

    /** Cancellation requested; waiting for the native operation to stop. */
    data class Cancelling(
        val imageFile: ImageFileInfo,
        val device: UsbDeviceInfo,
        val progress: FlashProgress
    ) : FlashState()

    data class Error(
        val message: String,
        val imageFile: ImageFileInfo? = null,
        val device: UsbDeviceInfo? = null
    ) : FlashState()
}

/** Compact byte formatting for progress readouts. */
private fun formatByteCount(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.US, "%.1f KB", bytes / 1_000.0)
    else -> "$bytes B"
}
