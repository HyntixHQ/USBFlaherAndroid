package com.hyntix.android.usbflasher.download

import android.content.Context
import com.hyntix.android.usbflasher.R

object DownloadStrings {

    fun errorRes(error: DownloadErrorCode): Int = when (error) {
        DownloadErrorCode.NO_NETWORK -> R.string.download_error_no_network
        DownloadErrorCode.METERED_BLOCKED -> R.string.download_error_metered_blocked
        DownloadErrorCode.ROAMING_BLOCKED -> R.string.download_error_roaming_blocked
        DownloadErrorCode.DATA_SAVER -> R.string.download_error_data_saver
        DownloadErrorCode.NO_SPACE -> R.string.download_error_no_space
        DownloadErrorCode.DESTINATION_UNAVAILABLE -> R.string.download_error_destination_unavailable
        DownloadErrorCode.DESTINATION_UNSUPPORTED -> R.string.download_error_destination_unsupported
        DownloadErrorCode.HTTP_ERROR -> R.string.download_error_http
        DownloadErrorCode.CHECKSUM_UNAVAILABLE -> R.string.download_error_checksum_unavailable
        DownloadErrorCode.CHECKSUM_MISMATCH -> R.string.download_error_checksum_mismatch
        DownloadErrorCode.CHECKSUM_TABLE_INVALID -> R.string.download_error_checksum_table
        DownloadErrorCode.FILE_CHANGED -> R.string.download_error_file_changed
        DownloadErrorCode.INVALID_FILE -> R.string.download_error_invalid_file
        DownloadErrorCode.NETWORK -> R.string.download_error_network
        DownloadErrorCode.SERVER_UNSUPPORTED -> R.string.download_error_server_unsupported
        DownloadErrorCode.ANOTHER_DOWNLOAD_ACTIVE -> R.string.download_error_active
        DownloadErrorCode.SERVICE_UNAVAILABLE -> R.string.download_error_service
        DownloadErrorCode.HTML_RESPONSE -> R.string.download_error_html
        DownloadErrorCode.UNKNOWN -> R.string.download_error_unknown
    }

    fun errorMessage(context: Context, error: DownloadError): String {
        val res = errorRes(error.code)
        val status = error.httpStatus
        return if (error.code == DownloadErrorCode.HTTP_ERROR && status != null) {
            context.getString(res, status)
        } else {
            context.getString(res)
        }
    }

    fun statusText(context: Context, record: DownloadRecord): String {
        return when (record.status) {
            DownloadStatus.QUEUED -> context.getString(R.string.download_status_queued)
            DownloadStatus.RESOLVING -> context.getString(R.string.download_status_resolving)
            DownloadStatus.DOWNLOADING -> context.getString(R.string.download_status_downloading)
            DownloadStatus.WAITING_FOR_NETWORK -> context.getString(R.string.download_status_waiting_network)
            DownloadStatus.PAUSED -> when (record.pauseReason) {
                PauseReason.INTERRUPTED -> context.getString(R.string.download_status_interrupted)
                PauseReason.SYSTEM_TIMEOUT -> context.getString(R.string.download_status_system_timeout)
                PauseReason.NO_SPACE -> context.getString(R.string.download_error_no_space)
                PauseReason.DESTINATION_UNAVAILABLE ->
                    context.getString(R.string.download_error_destination_unavailable)
                else -> context.getString(R.string.download_status_paused)
            }
            DownloadStatus.VERIFYING -> context.getString(R.string.download_status_verifying)
            DownloadStatus.COMPLETED -> context.getString(R.string.download_status_completed)
            DownloadStatus.FAILED -> record.error?.let { errorMessage(context, it) }
                ?: context.getString(R.string.download_status_failed)
            DownloadStatus.CANCELLED -> context.getString(R.string.download_status_cancelled)
        }
    }
}
