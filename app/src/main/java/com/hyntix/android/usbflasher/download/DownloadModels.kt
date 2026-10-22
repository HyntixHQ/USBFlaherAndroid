package com.hyntix.android.usbflasher.download

/**
 * The v1 download catalog is Linux-only. Windows 11+ remains behind a
 * release/legal gate and is intentionally not represented in this build.
 */
const val WINDOWS_ISO_DOWNLOAD_ENABLED = false

enum class DownloadKind {
    LINUX
}

enum class DownloadStatus {
    QUEUED,
    RESOLVING,
    DOWNLOADING,
    WAITING_FOR_NETWORK,
    PAUSED,
    VERIFYING,
    COMPLETED,
    FAILED,
    CANCELLED;

    val isActive: Boolean
        get() = this == QUEUED || this == RESOLVING || this == DOWNLOADING ||
            this == WAITING_FOR_NETWORK || this == PAUSED || this == VERIFYING
}

enum class PauseReason {
    USER,
    NETWORK,
    INTERRUPTED,
    SYSTEM_TIMEOUT,
    NO_SPACE,
    DESTINATION_UNAVAILABLE
}

enum class DownloadErrorCode {
    NO_NETWORK,
    METERED_BLOCKED,
    ROAMING_BLOCKED,
    DATA_SAVER,
    NO_SPACE,
    DESTINATION_UNAVAILABLE,
    DESTINATION_UNSUPPORTED,
    HTTP_ERROR,
    CHECKSUM_UNAVAILABLE,
    CHECKSUM_MISMATCH,
    CHECKSUM_TABLE_INVALID,
    FILE_CHANGED,
    INVALID_FILE,
    NETWORK,
    SERVER_UNSUPPORTED,
    ANOTHER_DOWNLOAD_ACTIVE,
    SERVICE_UNAVAILABLE,
    HTML_RESPONSE,
    UNKNOWN
}

data class DownloadError(
    val code: DownloadErrorCode,
    val httpStatus: Int? = null
)

data class LinuxIsoEntry(
    val id: String = "",
    val distro: String = "",
    val release: String = "",
    val editionKey: String = "",
    val arch: String = "",
    val fileName: String = "",
    val url: String = "",
    val checksumUrl: String = "",
    val checksumFormat: String = "",
    val approxSizeBytes: Long = 0,
    val licenseUrl: String = "",
    val homepageUrl: String = "",
    val persistenceProfiles: List<String> = emptyList(),
    val minUsbBytes: Long? = null
) {
    val displayName: String
        get() = "$distro $release"

    val persistenceSupported: Boolean
        get() = persistenceProfiles.isNotEmpty()
}

data class IsoCatalog(
    val schemaVersion: Int = 0,
    val generatedAt: String? = null,
    val linux: List<LinuxIsoEntry> = emptyList()
)

sealed interface CatalogLoadResult {
    data class Success(val catalog: IsoCatalog) : CatalogLoadResult
    data class Failure(val message: String) : CatalogLoadResult
}

data class DownloadRecord(
    val id: String = "",
    val catalogId: String = "",
    val kind: DownloadKind = DownloadKind.LINUX,
    val displayName: String = "",
    val fileName: String = "",
    val destinationUri: String = "",
    val totalBytes: Long = 0,
    val bytesDownloaded: Long = 0,
    val expectedSha256: String = "",
    val checksumUrl: String = "",
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val pauseReason: PauseReason? = null,
    val error: DownloadError? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val sourceUrl: String? = null,
    val rangeSupported: Boolean = true,
    val allowMetered: Boolean = false,
    val checksumVerified: Boolean = false,
    val speedBytesPerSecond: Long = 0,
    val etaSeconds: Long = 0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0
) {
    val percentage: Float
        get() = if (totalBytes > 0) {
            (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
        } else {
            0f
        }

    val isReady: Boolean
        get() = status == DownloadStatus.COMPLETED && checksumVerified
}

data class ReadyIso(
    val destinationUri: String,
    val displayName: String,
    val sizeBytes: Long
)

sealed interface DownloadStartResult {
    data class Started(val record: DownloadRecord) : DownloadStartResult
    data class Failed(val error: DownloadError) : DownloadStartResult
}

sealed interface ChecksumResult {
    data class Success(val sha256: String) : ChecksumResult
    data class Failure(val error: DownloadError) : ChecksumResult
}

sealed interface VerificationResult {
    data class Success(val sha256: String) : VerificationResult
    data class Mismatch(val actualSha256: String) : VerificationResult
    data class Failure(val error: DownloadError) : VerificationResult
    data object Cancelled : VerificationResult
}
