package com.hyntix.android.usbflasher.domain

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hyntix.android.usbflasher.R
import com.hyntix.android.usbflasher.util.AppLogger
import com.hyntix.lib.androidusbflasher.AndroidUsbFlasher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.security.MessageDigest

class UsbBackupService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var job: Job? = null
    private var cancelRequested = false

    companion object {
        const val NOTIFY_ID = 5002
        const val CHANNEL_ID = "usb_backup"
        const val ACTION_START = "com.hyntix.android.usbflasher.BACKUP_START"
        const val ACTION_CANCEL = "com.hyntix.android.usbflasher.BACKUP_CANCEL"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_DESTINATION_URI = "destination_uri"
        const val EXTRA_EXPECTED_BYTES = "expected_bytes"

        fun start(context: Context, deviceName: String, destinationUri: String, expectedBytes: Long = 0L) {
            val intent = Intent(context, UsbBackupService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE_NAME, deviceName)
                putExtra(EXTRA_DESTINATION_URI, destinationUri)
                putExtra(EXTRA_EXPECTED_BYTES, expectedBytes)
            }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, UsbBackupService::class.java).apply {
                action = ACTION_CANCEL
            })
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureNotificationChannel() {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "USB Backup",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_backup_desc)
            }
            manager.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: return START_NOT_STICKY
                val destinationUri = intent.getStringExtra(EXTRA_DESTINATION_URI) ?: return START_NOT_STICKY
                val expectedBytes = intent.getLongExtra(EXTRA_EXPECTED_BYTES, 0L)
                begin(deviceName, destinationUri, expectedBytes)
            }
            ACTION_CANCEL -> {
                cancelRequested = true
                job?.cancel()
            }
        }
        return START_NOT_STICKY
    }

    private fun begin(deviceName: String, destinationUri: String, expectedBytes: Long) {
        ensureNotificationChannel()
        val cancelIntent = Intent(this, UsbBackupService::class.java).apply { action = ACTION_CANCEL }
        val cancelPending = PendingIntent.getService(this, 0, cancelIntent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Backing up USB")
            .setContentText("Starting")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .addAction(0, "Cancel", cancelPending)
            .build()
        startForeground(NOTIFY_ID, notification)

        UsbOperationBus.setBackup(UsbBackupState.Running(0L, 1L))

        job = scope.launch {
            try {
                val flasher = AndroidUsbFlasher(this@UsbBackupService)
                val usbManager = getSystemService(android.hardware.usb.UsbManager::class.java) as android.hardware.usb.UsbManager
                val device = usbManager.deviceList.values.firstOrNull { it.deviceName == deviceName }
                if (device == null) {
                    UsbOperationBus.setBackup(UsbBackupState.Failed("USB device not found"))
                    return@launch
                }
                if (!usbManager.hasPermission(device)) {
                    UsbOperationBus.setBackup(UsbBackupState.Failed(getString(R.string.err_permission_denied)))
                    return@launch
                }

                val total = if (expectedBytes > 0L) expectedBytes else flasher.getDeviceCapacity(device)
                if (total <= 0L) {
                    UsbOperationBus.setBackup(UsbBackupState.Failed(getString(R.string.err_no_communication)))
                    return@launch
                }

                val uri = android.net.Uri.parse(destinationUri)
                val output = contentResolver.openOutputStream(uri, "w")
                if (output == null) {
                    UsbOperationBus.setBackup(UsbBackupState.Failed(getString(R.string.download_error_destination_unavailable)))
                    return@launch
                }

                val digest = MessageDigest.getInstance("SHA-256")
                val chunk = (8 * 1024 * 1024).toLong()
                var written = 0L
                try {
                    while (written < total && !cancelRequested) {
                        val len = minOf(chunk, total - written)
                        val data = flasher.readDeviceBytes(device, written, len)
                        if (data == null) {
                            throw IllegalStateException(getString(R.string.err_drive_communication))
                        }
                        output.write(data)
                        digest.update(data)
                        written += data.size
                        val percent = if (total > 0) ((written * 100) / total).toInt() else 0
                        UsbOperationBus.setBackup(UsbBackupState.Running(written, total))
                        if (written % (256 * 1024 * 1024) < len || written == total) {
                            val nm = getSystemService(android.app.NotificationManager::class.java)
                            val progressNotification = NotificationCompat.Builder(this@UsbBackupService, CHANNEL_ID)
                                .setContentTitle("Backing up USB")
                                .setContentText("Written $percent%")
                                .setSmallIcon(android.R.drawable.ic_dialog_info)
                                .setOngoing(true)
                                .setProgress(100, percent, false)
                                .addAction(0, "Cancel", cancelPending)
                                .build()
                            nm.notify(NOTIFY_ID, progressNotification)
                        }
                    }
                } finally {
                    output.close()
                }

                if (cancelRequested) {
                    runCatching { android.provider.DocumentsContract.deleteDocument(contentResolver, uri) }
                    UsbOperationBus.setBackup(UsbBackupState.Cancelled)
                    return@launch
                }

                val sha = digest.digest().joinToString("") { "%02x".format(it) }
                UsbOperationBus.setBackup(UsbBackupState.Finished(writtenBytes = written, sha256 = sha))
                val nm = getSystemService(android.app.NotificationManager::class.java)
                nm.cancel(NOTIFY_ID)
            } catch (e: Exception) {
                AppLogger.e("UsbBackup", "Backup failed", e)
                if (cancelRequested) UsbOperationBus.setBackup(UsbBackupState.Cancelled)
                else UsbOperationBus.setBackup(UsbBackupState.Failed(e.message ?: getString(R.string.err_drive_communication)))
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }
}
