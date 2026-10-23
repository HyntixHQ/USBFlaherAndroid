package com.hyntix.android.usbflasher.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.hyntix.android.usbflasher.MainActivity
import com.hyntix.android.usbflasher.R

class DownloadNotifications(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "iso_downloads"
        const val NOTIFICATION_ID = 4102

        private const val PREFS = "iso_download_notifications"
        private const val KEY_DISPLAYED_RECORD = "displayed_record_id"

        @Volatile
        private var displayedRecordId: String? = null

        private fun prefs(context: Context) =
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        private fun ownerFor(context: Context): String? =
            displayedRecordId ?: prefs(context).getString(KEY_DISPLAYED_RECORD, null)

        private fun setOwner(context: Context, recordId: String?) {
            displayedRecordId = recordId
            val editor = prefs(context).edit()
            if (recordId == null) {
                editor.remove(KEY_DISPLAYED_RECORD)
            } else {
                editor.putString(KEY_DISPLAYED_RECORD, recordId)
            }
            editor.apply()
        }

        /**
         * Dismiss the download notification from outside the service. Used when
         * the app cancels or deletes a record while the service is stopped
         * (paused/completed/failed), so the notification cannot outlive it.
         */
        fun dismiss(context: Context) {
            setOwner(context, null)
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        }

        /**
         * Dismiss only when the visible notification belongs to [recordId].
         * Ownership is persisted so a process restart cannot make this delete a
         * different record's notification. Unknown ownership is treated as ours.
         * Returns true when a dismissal was performed.
         */
        fun dismissFor(context: Context, recordId: String): Boolean {
            val owner = ownerFor(context)
            if (owner != null && owner != recordId) return false
            dismiss(context)
            return true
        }

        fun markDisplayed(context: Context, recordId: String) {
            setOwner(context, recordId)
        }
    }

    private val manager = NotificationManagerCompat.from(context)

    init {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.download_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.download_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    fun progress(record: DownloadRecord, statusText: String): Notification {
        val builder = baseBuilder(record)
            .setContentText(statusText)
            .setSubText(notificationProgress(record))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, (record.percentage * 100).toInt().coerceIn(0, 100), false)

        if (record.status == DownloadStatus.DOWNLOADING && record.rangeSupported) {
            builder.addAction(
                0,
                context.getString(R.string.download_notification_pause),
                DownloadService.pausePendingIntent(context, record.id)
            )
        } else if (record.status == DownloadStatus.WAITING_FOR_NETWORK) {
            builder.addAction(
                0,
                context.getString(R.string.download_notification_pause),
                DownloadService.pausePendingIntent(context, record.id)
            )
        } else if (record.status == DownloadStatus.PAUSED) {
            builder.addAction(
                0,
                context.getString(R.string.download_notification_resume),
                DownloadService.resumePendingIntent(context, record.id)
            )
        }
        builder.addAction(
            0,
            context.getString(R.string.download_notification_cancel),
            DownloadService.cancelPendingIntent(context, record.id)
        )
        return builder.build()
    }

    fun preparing(record: DownloadRecord): Notification {
        return baseBuilder(record)
            .setContentText(context.getString(R.string.download_notification_preparing))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .addAction(
                0,
                context.getString(R.string.download_notification_cancel),
                DownloadService.cancelPendingIntent(context, record.id)
            )
            .build()
    }

    /**
     * Minimal notification used only when a start intent arrives after its
     * record was deleted. A service started with startForegroundService must
     * enter the foreground within five seconds even on this error path.
     */
    fun starting(): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(context.getString(R.string.download_channel_name))
            .setContentText(context.getString(R.string.download_notification_preparing))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(0, 0, true)
            .build()
    }

    fun completed(record: DownloadRecord): Notification {
        return baseBuilder(record)
            .setContentTitle(context.getString(R.string.download_notification_complete))
            .setContentText(
                context.getString(R.string.download_notification_complete_text, record.fileName)
            )
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
    }

    fun failed(record: DownloadRecord): Notification {
        val message = record.error?.let { DownloadStrings.errorMessage(context, it) }
            ?: context.getString(R.string.download_error_unknown)
        return baseBuilder(record)
            .setContentTitle(context.getString(R.string.download_notification_failed))
            .setContentText(
                context.getString(R.string.download_notification_failed_text, record.displayName, message)
            )
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .build()
    }

    fun waiting(record: DownloadRecord): Notification {
        return baseBuilder(record)
            .setContentText(DownloadStrings.statusText(context, record))
            .setSubText(notificationProgress(record))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, (record.percentage * 100).toInt().coerceIn(0, 100), false)
            .addAction(
                0,
                context.getString(R.string.download_notification_pause),
                DownloadService.pausePendingIntent(context, record.id)
            )
            .addAction(
                0,
                context.getString(R.string.download_notification_cancel),
                DownloadService.cancelPendingIntent(context, record.id)
            )
            .build()
    }

    fun paused(record: DownloadRecord): Notification {
        return baseBuilder(record)
            .setContentText(DownloadStrings.statusText(context, record))
            .setSubText(notificationProgress(record))
            .setOngoing(false)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                context.getString(R.string.download_notification_resume),
                DownloadService.resumePendingIntent(context, record.id)
            )
            .addAction(
                0,
                context.getString(R.string.download_notification_cancel),
                DownloadService.cancelPendingIntent(context, record.id)
            )
            .build()
    }

    fun notify(recordId: String, notification: Notification) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            manager.notify(NOTIFICATION_ID, notification)
            setOwner(context, recordId)
        }
    }

    fun cancel() {
        setOwner(context, null)
        manager.cancel(NOTIFICATION_ID)
    }

    private fun baseBuilder(record: DownloadRecord): NotificationCompat.Builder {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_download)
            .setContentTitle(record.displayName)
            .setContentIntent(openAppIntent())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            4102,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun notificationProgress(record: DownloadRecord): String {
        return context.getString(
            R.string.download_notification_progress,
            (record.percentage * 100).toInt().coerceIn(0, 100),
            DownloadFormatters.bytes(context, record.bytesDownloaded),
            DownloadFormatters.bytes(context, record.totalBytes)
        )
    }
}
