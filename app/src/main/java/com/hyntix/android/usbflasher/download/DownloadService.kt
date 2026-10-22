package com.hyntix.android.usbflasher.download

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.ServiceCompat
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.ArrayDeque

class DownloadService : Service() {

    companion object {
        const val ACTION_START = "com.hyntix.android.usbflasher.download.START"
        const val ACTION_PAUSE = "com.hyntix.android.usbflasher.download.PAUSE"
        const val ACTION_CANCEL = "com.hyntix.android.usbflasher.download.CANCEL"
        const val EXTRA_RECORD_ID = "record_id"

        const val NOTIFICATION_INTERVAL_MS = 1_000L
        const val SPEED_WINDOW_MS = 5_000L
        const val NETWORK_CHECK_INTERVAL_MS = 30_000L
        const val WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1000L
        const val WAKE_LOCK_RENEW_MS = 5L * 60L * 1000L

        @Volatile
        var isRunning: Boolean = false
            private set

        fun startIntent(context: Context, id: String): Intent {
            return Intent(context, DownloadService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_RECORD_ID, id)
        }

        fun pauseIntent(context: Context, id: String): Intent {
            return Intent(context, DownloadService::class.java)
                .setAction(ACTION_PAUSE)
                .putExtra(EXTRA_RECORD_ID, id)
        }

        fun cancelIntent(context: Context, id: String): Intent {
            return Intent(context, DownloadService::class.java)
                .setAction(ACTION_CANCEL)
                .putExtra(EXTRA_RECORD_ID, id)
        }

        fun pausePendingIntent(context: Context, id: String): PendingIntent {
            return PendingIntent.getService(
                context,
                id.hashCode(),
                pauseIntent(context, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun resumePendingIntent(context: Context, id: String): PendingIntent {
            return PendingIntent.getForegroundService(
                context,
                id.hashCode() + 1,
                startIntent(context, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        fun cancelPendingIntent(context: Context, id: String): PendingIntent {
            return PendingIntent.getService(
                context,
                id.hashCode() + 2,
                cancelIntent(context, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var store: DownloadStore
    private lateinit var notifications: DownloadNotifications

    @Volatile
    private var activeJob: Job? = null

    @Volatile
    private var activeControl: DownloadControl? = null

    @Volatile
    private var currentRecordId: String? = null

    @Volatile
    private var systemTimedOut = false

    @Volatile
    private var cancelRequested = false

    @Volatile
    private var pauseRequested = false

    @Volatile
    private var networkPauseReason: PauseReason? = null

    @Volatile
    private var currentAllowMetered = false

    @Volatile
    private var waitingForNetwork = false

    @Volatile
    private var waitingRecordId: String? = null

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var pendingStartId: String? = null
    private var lastNotificationAt = 0L
    private var lastWakeRenewAt = 0L
    private var lastNetworkCheckAt = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val speedSamples = ArrayDeque<SpeedSample>()
    private val wakeLockRenewal = object : Runnable {
        override fun run() {
            if (activeJob?.isActive != true) return
            lastWakeRenewAt = System.currentTimeMillis()
            wakeLock?.let { lock -> runCatching { lock.acquire(WAKE_LOCK_TIMEOUT_MS) } }
            mainHandler.postDelayed(this, WAKE_LOCK_RENEW_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        val graph = DownloadGraph.init(applicationContext)
        store = graph.store
        notifications = DownloadNotifications(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_RECORD_ID)
        when (intent?.action) {
            ACTION_PAUSE -> {
                if (id != null) pauseDownload(id)
            }

            ACTION_CANCEL -> {
                if (id != null) cancelDownload(id)
            }

            ACTION_START -> {
                if (id != null) startDownload(id)
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTimeout(startId: Int) {
        handleTimeout()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleTimeout()
    }

    override fun onDestroy() {
        isRunning = false
        val interruptedId = currentRecordId ?: waitingRecordId
        waitingForNetwork = false
        waitingRecordId = null
        pendingStartId = null
        unregisterNetworkCallback()
        mainHandler.removeCallbacksAndMessages(null)
        activeControl?.cancel()
        serviceScope.cancel()
        releaseLocks()
        if (::store.isInitialized) {
            interruptedId?.let { id ->
                val record = store.find(id)
                if (record != null && record.status.isActive &&
                    record.status != DownloadStatus.PAUSED
                ) {
                    store.updateStatus(
                        id = id,
                        status = DownloadStatus.PAUSED,
                        pauseReason = PauseReason.INTERRUPTED,
                        persistNow = true
                    )
                }
            }
        }
        super.onDestroy()
    }

    private fun startDownload(id: String) {
        if (activeJob?.isActive == true) {
            if (currentRecordId == id) {
                // A resume can race the pause teardown. The current engine call
                // is already cancelled, so queue one restart for when it unwinds
                // instead of losing the tap.
                if (pauseRequested || activeControl?.isPaused == true) {
                    pendingStartId = id
                }
                return
            }
            // A new record replaced the old one (normally after a cancel).
            // Abort the old job and start the queued record when it unwinds.
            pendingStartId = id
            cancelRequested = true
            networkPauseReason = null
            activeControl?.cancel()
            return
        }
        launchDownload(id)
    }

    private fun launchDownload(id: String) {
        clearWaitingState()
        val record = store.find(id)
        if (record == null || record.status == DownloadStatus.COMPLETED) {
            // The service was started with startForegroundService, so it must
            // still enter the foreground before stopping, even when the record
            // was deleted while the intent was in flight.
            runCatching {
                ServiceCompat.startForeground(
                    this,
                    DownloadNotifications.NOTIFICATION_ID,
                    notifications.starting(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
            notifications.cancel()
            finishActive()
            return
        }

        currentRecordId = id
        systemTimedOut = false
        cancelRequested = false
        pauseRequested = false
        networkPauseReason = null
        currentAllowMetered = record.allowMetered
        lastNetworkCheckAt = 0L
        speedSamples.clear()
        store.updateStatus(
            id = id,
            status = DownloadStatus.QUEUED,
            pauseReason = null,
            error = null,
            persistNow = true
        )

        try {
            ServiceCompat.startForeground(
                this,
                DownloadNotifications.NOTIFICATION_ID,
                notifications.preparing(record),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } catch (e: Exception) {
            store.updateStatus(
                id = id,
                status = DownloadStatus.FAILED,
                error = DownloadError(DownloadErrorCode.SERVICE_UNAVAILABLE)
            )
            finishActive()
            return
        }

        // Re-check on every service start. The repository checks before it
        // queues, but the network may have changed while the app was away.
        NetworkPolicy.check(applicationContext, record.allowMetered)?.let {
            val paused = store.updateStatus(
                id = id,
                status = DownloadStatus.PAUSED,
                pauseReason = PauseReason.NETWORK,
                persistNow = true
            )
            if (paused != null) notifications.notify(notifications.paused(paused))
            finishActive()
            return
        }

        registerNetworkCallback()
        acquireLocks()
        activeJob = serviceScope.launch {
            runDownload(id)
        }
    }

    private fun pauseDownload(id: String) {
        val existing = store.find(id) ?: return
        if (existing.status == DownloadStatus.COMPLETED) return
        // A resume can be queued while the previous (paused) engine call unwinds.
        // A new pause must cancel that restart.
        if (pendingStartId == id) pendingStartId = null
        if (waitingRecordId == id) clearWaitingState()
        if (currentRecordId != id) {
            if (existing.status.isActive) {
                val updated = store.updateStatus(
                    id = id,
                    status = DownloadStatus.PAUSED,
                    pauseReason = PauseReason.USER,
                    persistNow = true
                )
                if (updated != null) notifications.notify(notifications.paused(updated))
            }
            if (activeJob?.isActive != true) finishActive()
            return
        }

        pauseRequested = true
        activeControl?.pause()
        val record = store.updateStatus(
            id = id,
            status = DownloadStatus.PAUSED,
            pauseReason = PauseReason.USER,
            persistNow = true
        )
        if (record != null) {
            notifications.notify(notifications.paused(record))
        }
        if (activeJob?.isActive != true) finishActive()
    }

    private fun cancelDownload(id: String) {
        if (pendingStartId == id) pendingStartId = null
        if (waitingRecordId == id) clearWaitingState()
        val isCurrent = currentRecordId == id
        if (isCurrent && activeJob?.isActive == true) {
            cancelRequested = true
            networkPauseReason = null
            activeControl?.cancel()
            return
        }
        val ownsNotification = isCurrent || waitingRecordId == id ||
            (activeJob?.isActive != true && !waitingForNetwork)
        deleteRecordNow(
            id = id,
            cancelNotification = ownsNotification
        )
        if (activeJob?.isActive != true) finishActive()
    }

    private suspend fun runDownload(id: String) {
        val control = DownloadControl()
        activeControl = control
        if (pauseRequested) control.pause()
        if (cancelRequested) control.cancel()
        try {
            var record = store.find(id) ?: return
            if (control.isCancelled) {
                handleEngineCancelled(id, 0L)
                return
            }
            if (control.isPaused) {
                val updated = store.updateStatus(
                    id = id,
                    status = DownloadStatus.PAUSED,
                    pauseReason = PauseReason.USER,
                    error = null,
                    bytesDownloaded = record.bytesDownloaded,
                    persistNow = true
                )
                if (updated != null) notifications.notify(notifications.paused(updated))
                return
            }
            store.updateStatus(
                id = id,
                status = DownloadStatus.DOWNLOADING,
                pauseReason = null,
                error = null
            )
            updateProgressNotification(id, force = true)

            if (cancelRequested) {
                deleteRecordNow(id)
                return
            }

            if (record.totalBytes > 0 && record.bytesDownloaded >= record.totalBytes) {
                verifyAndFinish(record.copy(bytesDownloaded = record.totalBytes))
                return
            }

            val uri = record.destinationUri.toUri()
            val sink = try {
                DownloadSinkFactory.open(applicationContext, uri)
            } catch (e: Exception) {
                fail(record, DownloadError(DownloadErrorCode.DESTINATION_UNAVAILABLE))
                return
            }

            val result = sink.use { output ->
                val actualSize = runCatching { output.size() }.getOrDefault(0L)
                val start = if (record.bytesDownloaded <= actualSize) {
                    record.bytesDownloaded
                } else {
                    0L
                }
                runCatching { output.prepare(start) }.getOrElse {
                    fail(record, DownloadError(DownloadErrorCode.DESTINATION_UNSUPPORTED))
                    return
                }

                val engine = ResumableDownloadEngine(DownloadGraph.init(applicationContext).downloadClient())
                val source = record.sourceUrl
                    ?: return@use EngineResult.Failed(
                        DownloadError(DownloadErrorCode.INVALID_FILE),
                        start
                    )
                engine.run(
                    request = EngineRequest(
                        url = source,
                        offset = start,
                        expectedTotalBytes = record.totalBytes,
                        etag = record.etag,
                        lastModified = record.lastModified
                    ),
                    sink = output,
                    control = control,
                    onProgress = { bytes, total ->
                        onProgress(record, bytes, total)
                    }
                )
            }

            record = store.find(id) ?: return
            when (result) {
                is EngineResult.Completed -> {
                    store.update(
                        id = id,
                        persistNow = true
                    ) {
                        it.copy(
                            bytesDownloaded = result.totalBytes,
                            totalBytes = result.totalBytes,
                            rangeSupported = result.rangeSupported,
                            etag = result.etag,
                            lastModified = result.lastModified,
                            updatedAt = System.currentTimeMillis()
                        )
                    }
                    record = store.find(id) ?: return
                    verifyAndFinish(record)
                }

                is EngineResult.Paused -> {
                    val reason = when {
                        systemTimedOut -> PauseReason.SYSTEM_TIMEOUT
                        pauseRequested -> PauseReason.USER
                        networkPauseReason != null -> networkPauseReason!!
                        else -> result.reason
                    }
                    networkPauseReason = null
                    if (reason == PauseReason.NETWORK) {
                        // The callback cancels the current call. Re-check once
                        // before sleeping so a network that returned during the
                        // unwind does not leave the download stuck.
                        when (val policyError = NetworkPolicy.check(
                            applicationContext,
                            currentAllowMetered
                        )) {
                            null -> {
                                pendingStartId = id
                                return
                            }

                            else -> if (policyError.code == DownloadErrorCode.NO_NETWORK) {
                                waitingForNetwork = true
                                waitingRecordId = id
                                val waiting = store.updateStatus(
                                    id = id,
                                    status = DownloadStatus.WAITING_FOR_NETWORK,
                                    pauseReason = PauseReason.NETWORK,
                                    error = null,
                                    bytesDownloaded = result.bytesDownloaded,
                                    persistNow = true
                                )
                                if (waiting != null) {
                                    notifications.notify(notifications.waiting(waiting))
                                }
                                return
                            }
                        }
                    }
                    val updated = store.updateStatus(
                        id = id,
                        status = DownloadStatus.PAUSED,
                        pauseReason = reason,
                        error = null,
                        bytesDownloaded = result.bytesDownloaded,
                        persistNow = true
                    )
                    if (updated != null) {
                        notifications.notify(notifications.paused(updated))
                    }
                }

                is EngineResult.Cancelled -> {
                    handleEngineCancelled(id, result.bytesDownloaded)
                }

                is EngineResult.Failed -> {
                    fail(store.find(id) ?: record, result.error, result.bytesDownloaded)
                }
            }
        } catch (e: Exception) {
            fail(store.find(id) ?: return, DownloadError(DownloadErrorCode.UNKNOWN))
        } finally {
            finishActive()
        }
    }

    private fun finishActive() {
        releaseLocks()
        activeControl = null
        currentRecordId = null
        activeJob = null
        pauseRequested = false
        if (waitingForNetwork) {
            // A queued restart for the same record means the network returned
            // just before this job finished unwinding.
            if (pendingStartId != null && pendingStartId == waitingRecordId) {
                clearWaitingState()
            } else {
                // Keep the foreground service and the network callback alive so
                // a returning network can resume automatically.
                return
            }
        }
        mainHandler.post { startPendingOrStop() }
    }

    private fun startPendingOrStop() {
        if (activeJob?.isActive == true) return
        if (waitingForNetwork) return
        val next = pendingStartId
        pendingStartId = null
        if (next != null) {
            startDownload(next)
            return
        }
        unregisterNetworkCallback()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private fun registerNetworkCallback() {
        if (networkCallback != null) return
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post { resumeWaitingDownload() }
            }

            override fun onLost(network: Network) {
                mainHandler.post { checkNetworkConstraints() }
            }

            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                mainHandler.post { checkNetworkConstraints() }
            }
        }
        if (runCatching { manager.registerDefaultNetworkCallback(callback) }.isSuccess) {
            networkCallback = callback
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        runCatching { manager?.unregisterNetworkCallback(callback) }
    }

    private fun checkNetworkConstraints() {
        if (waitingForNetwork) {
            resumeWaitingDownload()
            return
        }
        if (activeJob?.isActive != true) return
        if (NetworkPolicy.check(applicationContext, currentAllowMetered) != null) {
            networkPauseReason = PauseReason.NETWORK
            activeControl?.pause()
        }
    }

    private fun resumeWaitingDownload() {
        if (!waitingForNetwork) return
        val id = waitingRecordId ?: return
        when (val error = NetworkPolicy.check(applicationContext, currentAllowMetered)) {
            null -> {
                clearWaitingState()
                startDownload(id)
            }

            else -> if (error.code != DownloadErrorCode.NO_NETWORK) {
                // The connection came back but is metered, roaming, or behind
                // Data Saver. Stop waiting and let the user decide.
                clearWaitingState()
                val paused = store.updateStatus(
                    id = id,
                    status = DownloadStatus.PAUSED,
                    pauseReason = PauseReason.NETWORK,
                    error = null,
                    persistNow = true
                )
                if (paused != null) notifications.notify(notifications.paused(paused))
                finishActive()
            }
        }
    }

    private fun clearWaitingState() {
        waitingForNetwork = false
        waitingRecordId = null
    }

    private fun verifyAndFinish(record: DownloadRecord) {
        if (cancelRequested) {
            deleteRecordNow(record.id)
            return
        }

        val expectedSize = record.totalBytes
        val actualSize = DownloadDestination.fileSize(applicationContext, record.destinationUri.toUri())
        if (expectedSize > 0 && actualSize != null && actualSize != expectedSize) {
            fail(
                record,
                DownloadError(DownloadErrorCode.INVALID_FILE),
                actualSize
            )
            return
        }

        val verifying = store.updateStatus(
            id = record.id,
            status = DownloadStatus.VERIFYING,
            bytesDownloaded = record.bytesDownloaded,
            totalBytes = record.totalBytes
        ) ?: return
        updateProgressNotification(verifying.id, force = true)

        val result = DownloadVerifier(applicationContext).verify(
            uri = record.destinationUri.toUri(),
            expectedSha256 = record.expectedSha256,
            shouldCancel = { cancelRequested }
        ) { read, total ->
            store.updateProgress(
                id = record.id,
                bytesDownloaded = read,
                totalBytes = if (total > 0) total else record.totalBytes,
                speedBytesPerSecond = 0,
                etaSeconds = 0,
                status = DownloadStatus.VERIFYING
            )
            updateProgressNotification(record.id, force = false)
        }

        if (cancelRequested) {
            deleteRecordNow(record.id)
            return
        }

        when (result) {
            is VerificationResult.Success -> {
                val completed = store.update(record.id, persistNow = true) { current ->
                    current.copy(
                        status = DownloadStatus.COMPLETED,
                        bytesDownloaded = if (record.totalBytes > 0) record.totalBytes else current.bytesDownloaded,
                        totalBytes = if (record.totalBytes > 0) record.totalBytes else current.totalBytes,
                        checksumVerified = true,
                        pauseReason = null,
                        error = null,
                        speedBytesPerSecond = 0,
                        etaSeconds = 0,
                        updatedAt = System.currentTimeMillis()
                    )
                }
                if (completed != null) {
                    notifications.notify(notifications.completed(completed))
                }
            }

            is VerificationResult.Mismatch -> {
                DownloadDestination.delete(applicationContext, record.destinationUri.toUri())
                fail(record, DownloadError(DownloadErrorCode.CHECKSUM_MISMATCH), 0)
            }

            is VerificationResult.Failure -> {
                fail(record, result.error, record.bytesDownloaded)
            }

            is VerificationResult.Cancelled -> {
                deleteRecordNow(record.id)
            }
        }
    }

    private fun fail(record: DownloadRecord, error: DownloadError, bytes: Long = record.bytesDownloaded) {
        val failed = store.updateStatus(
            id = record.id,
            status = DownloadStatus.FAILED,
            error = error,
            bytesDownloaded = bytes,
            persistNow = true
        ) ?: return
        notifications.notify(notifications.failed(failed))
    }

    /**
     * A user cancel (or a replacement download) deletes the partial file.
     * A service teardown merely cancels the socket; keep the partial file and
     * leave the record resumable, preserving an already-recorded pause reason
     * (for example SYSTEM_TIMEOUT).
     */
    private fun handleEngineCancelled(id: String, bytes: Long) {
        if (cancelRequested) {
            deleteRecordNow(id)
            return
        }
        val current = store.find(id) ?: return
        val reason = current.pauseReason
            ?: networkPauseReason
            ?: if (systemTimedOut) PauseReason.SYSTEM_TIMEOUT else PauseReason.INTERRUPTED
        val updated = store.updateStatus(
            id = id,
            status = DownloadStatus.PAUSED,
            pauseReason = reason,
            error = null,
            bytesDownloaded = maxOf(current.bytesDownloaded, bytes),
            persistNow = true
        )
        if (updated != null) notifications.notify(notifications.paused(updated))
    }

    private fun deleteRecordNow(id: String, cancelNotification: Boolean = true) {
        val record = store.find(id)
        if (record != null) {
            runCatching {
                DownloadDestination.delete(applicationContext, record.destinationUri.toUri())
            }
            store.remove(id)
            if (cancelNotification) notifications.cancel()
        }
    }

    private fun onProgress(record: DownloadRecord, bytes: Long, total: Long) {
        val now = System.currentTimeMillis()
        if (now - lastNetworkCheckAt >= NETWORK_CHECK_INTERVAL_MS) {
            lastNetworkCheckAt = now
            if (NetworkPolicy.check(applicationContext, currentAllowMetered) != null) {
                networkPauseReason = PauseReason.NETWORK
                activeControl?.pause()
            }
        }

        val effectiveTotal = if (total > 0) total else record.totalBytes
        val (speed, eta) = speedFor(bytes, effectiveTotal)
        store.updateProgress(
            id = record.id,
            bytesDownloaded = bytes,
            totalBytes = effectiveTotal,
            speedBytesPerSecond = speed,
            etaSeconds = eta
        )
        if (System.currentTimeMillis() - lastNotificationAt >= NOTIFICATION_INTERVAL_MS) {
            updateProgressNotification(record.id, force = false)
        }
        ensureWakeLock()
    }

    private fun updateProgressNotification(id: String, force: Boolean) {
        val now = System.currentTimeMillis()
        if (!force && now - lastNotificationAt < NOTIFICATION_INTERVAL_MS) return
        val record = store.find(id) ?: return
        lastNotificationAt = now
        val status = DownloadStrings.statusText(applicationContext, record)
        val notification = when (record.status) {
            DownloadStatus.PAUSED -> notifications.paused(record)
            DownloadStatus.WAITING_FOR_NETWORK -> notifications.waiting(record)
            else -> notifications.progress(record, status)
        }
        notifications.notify(notification)
    }

    private fun speedFor(bytes: Long, total: Long): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        speedSamples.addLast(SpeedSample(now, bytes))
        while (speedSamples.isNotEmpty() && now - speedSamples.first().time > SPEED_WINDOW_MS) {
            speedSamples.removeFirst()
        }
        if (speedSamples.size < 2) return 0L to 0L
        val oldest = speedSamples.first()
        val elapsed = (now - oldest.time).coerceAtLeast(1L)
        val speed = ((bytes - oldest.bytes).coerceAtLeast(0L) * 1000L / elapsed)
        val remaining = (total - bytes).coerceAtLeast(0L)
        val eta = if (speed > 0) remaining / speed else 0L
        return speed to eta
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        wakeLock = runCatching {
            power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Hyntix:iso-download")?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        }.getOrNull()
        lastWakeRenewAt = System.currentTimeMillis()

        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        wifiLock = runCatching {
            wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Hyntix:iso-download")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        mainHandler.removeCallbacks(wakeLockRenewal)
        mainHandler.postDelayed(wakeLockRenewal, WAKE_LOCK_RENEW_MS)
    }

    private fun ensureWakeLock() {
        val now = System.currentTimeMillis()
        if (now - lastWakeRenewAt < WAKE_LOCK_RENEW_MS) return
        lastWakeRenewAt = now
        val lock = wakeLock ?: return
        runCatching { lock.acquire(WAKE_LOCK_TIMEOUT_MS) }
    }

    private fun releaseLocks() {
        mainHandler.removeCallbacks(wakeLockRenewal)
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun handleTimeout() {
        systemTimedOut = true
        pendingStartId = null
        val interruptedId = currentRecordId ?: waitingRecordId
        waitingForNetwork = false
        waitingRecordId = null
        unregisterNetworkCallback()
        activeControl?.pause()
        interruptedId?.let { id ->
            val record = store.updateStatus(
                id = id,
                status = DownloadStatus.PAUSED,
                pauseReason = PauseReason.SYSTEM_TIMEOUT,
                persistNow = true
            )
            if (record != null) {
                notifications.notify(notifications.paused(record))
            }
        }
        releaseLocks()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    private data class SpeedSample(val time: Long, val bytes: Long)
}
