package com.hyntix.android.usbflasher.download

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * The v1 policy is deliberately conservative: downloads run on an unmetered,
 * non-roaming connection. A metered connection is allowed only after an
 * explicit per-download confirmation, which is persisted on the record so a
 * resume cannot silently spend mobile data.
 */
object NetworkPolicy {

    fun check(context: Context, allowMetered: Boolean): DownloadError? {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return DownloadError(DownloadErrorCode.NO_NETWORK)
        val network = manager.activeNetwork
            ?: return DownloadError(DownloadErrorCode.NO_NETWORK)
        val capabilities = manager.getNetworkCapabilities(network)
            ?: return DownloadError(DownloadErrorCode.NO_NETWORK)

        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return DownloadError(DownloadErrorCode.NO_NETWORK)
        }
        if (manager.restrictBackgroundStatus ==
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        ) {
            return DownloadError(DownloadErrorCode.DATA_SAVER)
        }
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) {
            return DownloadError(DownloadErrorCode.ROAMING_BLOCKED)
        }
        if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            !allowMetered
        ) {
            return DownloadError(DownloadErrorCode.METERED_BLOCKED)
        }
        return null
    }
}
