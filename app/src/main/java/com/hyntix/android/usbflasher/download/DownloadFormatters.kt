package com.hyntix.android.usbflasher.download

import android.content.Context
import android.text.format.Formatter
import com.hyntix.android.usbflasher.R
import java.text.NumberFormat

object DownloadFormatters {

    fun bytes(context: Context, value: Long): String {
        return Formatter.formatFileSize(context, value.coerceAtLeast(0))
    }

    fun speed(context: Context, bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return context.getString(R.string.download_value_unknown)
        return context.getString(
            R.string.download_speed_value,
            bytes(context, bytesPerSecond)
        )
    }

    fun eta(context: Context, seconds: Long): String {
        if (seconds <= 0) return context.getString(R.string.download_value_unknown)
        return when {
            seconds >= 3600 -> context.getString(
                R.string.download_eta_hours,
                number(seconds / 3600),
                number((seconds % 3600) / 60)
            )

            seconds >= 60 -> context.getString(
                R.string.download_eta_minutes,
                number(seconds / 60),
                number(seconds % 60)
            )

            else -> context.getString(R.string.download_eta_seconds, number(seconds))
        }
    }

    private fun number(value: Long): String {
        return NumberFormat.getNumberInstance().format(value)
    }
}
