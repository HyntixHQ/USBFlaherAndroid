package com.hyntix.android.usbflasher.domain

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.hyntix.android.usbflasher.R
// same package: UsbOperationBus
import com.hyntix.android.usbflasher.util.AppLogger
import com.hyntix.lib.androidusbflasher.AndroidUsbFlasher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.security.MessageDigest

class UsbVerificationService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private var job: Job? = null
    private var cancelRequested = false

    companion object {
        const val NOTIFY_ID = 5001
        const val CHANNEL_ID = "usb_verification"
        const val ACTION_START = "com.hyntix.android.usbflasher.VERIFY_START"
        const val ACTION_CANCEL = "com.hyntix.android.usbflasher.VERIFY_CANCEL"
        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_EXPECTED_HASH = "expected_hash"
        const val EXTRA_EXPECTED_SIZE = "expected_size"

        fun start(context: Context, deviceName: String, expectedHash: String? = null, expectedSize: Long = 0L) {
            val intent = Intent(context, UsbVerificationService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_DEVICE_NAME, deviceName)
                putExtra(EXTRA_EXPECTED_HASH, expectedHash)
                putExtra(EXTRA_EXPECTED_SIZE, expectedSize)
            }
            context.startForegroundService(intent)
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, UsbVerificationService::class.java).apply {
                action = ACTION_CANCEL
            })
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        AppLogger.d("UsbVerification", "onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val deviceName = intent.getStringExtra(EXTRA_DEVICE_NAME) ?: return START_NOT_STICKY
                val expectedHash = intent.getStringExtra(EXTRA_EXPECTED_HASH)
                val expectedSize = intent.getLongExtra(EXTRA_EXPECTED_SIZE, 0L)
                begin(deviceName, expectedHash, expectedSize)
            }
            ACTION_CANCEL -> {
                cancelRequested = true
                job?.cancel()
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(android.app.NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = android.app.NotificationChannel(
                CHANNEL_ID,
                "USB Verification",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_verify_desc)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun begin(deviceName: String, expectedHash: String?, expectedSize: Long) {
        ensureNotificationChannel()
        val cancelIntent = Intent(this, UsbVerificationService::class.java).apply { action = ACTION_CANCEL }
        val cancelPending = PendingIntent.getService(this, 0, cancelIntent, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Verifying USB")
            .setContentText("Starting")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .addAction(0, getString(R.string.verify_cancel), cancelPending)
            .build()
        startForeground(NOTIFY_ID, notification)

        UsbOperationBus.setVerify(UsbVerificationState.Running(0L, 1L))

        job = scope.launch {
            try {
                val flasher = AndroidUsbFlasher(this@UsbVerificationService)
                val manager = getSystemService(android.hardware.usb.UsbManager::class.java) as android.hardware.usb.UsbManager
                val device = manager.deviceList.values.firstOrNull { it.deviceName == deviceName }
                if (device == null) {
                    UsbOperationBus.setVerify(UsbVerificationState.Failed("USB device not found"))
                    return@launch
                }
                if (!manager.hasPermission(device)) {
                    UsbOperationBus.setVerify(UsbVerificationState.Failed(this@UsbVerificationService.getString(R.string.err_permission_denied)))
                    return@launch
                }

                val capacity = flasher.getDeviceCapacity(device)
                val total = if (expectedSize > 0L && expectedSize <= capacity) expectedSize else capacity
                if (total <= 0L) {
                    UsbOperationBus.setVerify(UsbVerificationState.Failed(this@UsbVerificationService.getString(R.string.err_no_communication)))
                    return@launch
                }

                val digest = MessageDigest.getInstance("SHA-256")
                val chunk = (8 * 1024 * 1024).toLong()
                var read = 0L
                while (read < total && !cancelRequested) {
                    val len = minOf(chunk, total - read)
                    val data = flasher.readDeviceBytes(device, read, len)
                    if (data == null) {
                        throw IllegalStateException(getString(R.string.err_drive_communication))
                    }
                    digest.update(data)
                    read += data.size
                    val percent = if (total > 0) ((read * 100) / total).toInt() else 0
                    UsbOperationBus.setVerify(UsbVerificationState.Running(read, total))
                    val nm = getSystemService(android.app.NotificationManager::class.java)
                    val progressNotification = NotificationCompat.Builder(this@UsbVerificationService, CHANNEL_ID)
                        .setContentTitle("Verifying USB")
                        .setContentText("Read $percent%")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setOngoing(true)
                        .setProgress(100, percent, false)
                        .addAction(0, getString(R.string.verify_cancel), cancelPending)
                        .build()
                    nm.notify(NOTIFY_ID, progressNotification)
                }

                if (cancelRequested) {
                    UsbOperationBus.setVerify(UsbVerificationState.Cancelled)
                    return@launch
                }

                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                UsbOperationBus.setVerify(
                    UsbVerificationState.Finished(actualSha256 = actual, expectedSha256 = expectedHash)
                )
                val nm = getSystemService(android.app.NotificationManager::class.java)
                nm.cancel(NOTIFY_ID)
            } catch (e: Exception) {
                AppLogger.e("UsbVerification", "Verification failed", e)
                if (cancelRequested) UsbOperationBus.setVerify(UsbVerificationState.Cancelled)
                else UsbOperationBus.setVerify(UsbVerificationState.Failed(e.message ?: getString(R.string.err_drive_communication)))
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }
}
