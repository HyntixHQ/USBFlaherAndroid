package com.hyntix.android.usbflasher.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

object AppLogger {

    private const val LOG_FILE_NAME = "flash_debug.log"
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024
    private const val MAX_MEMORY_LOGS = 1000

    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val ansiRegex = Regex("\u001b\\[[0-9;]*m")

    // File write queue: log lines are enqueued here and flushed asynchronously
    private val writeQueue = ConcurrentLinkedQueue<String>()

    // Ring buffer for in-memory logs (thread-safe via ringLock)
    private val ringLock = Any()
    private val ringBuffer = ArrayDeque<String>()

    // Flow for log viewer — updated periodically, not per-log-call
    private val _logFlow = MutableStateFlow<List<String>>(emptyList())
    val logFlow: StateFlow<List<String>> = _logFlow.asStateFlow()

    // Throttle snapshot emissions to at most every 500ms
    private var lastSnapshotMs = 0L

    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun init(context: Context) {
        logFile = File(context.filesDir, LOG_FILE_NAME)

        if (logFile?.exists() == true && logFile!!.length() > MAX_LOG_SIZE) {
            val backup = File(context.filesDir, "flash_debug_old.log")
            backup.delete()
            logFile?.renameTo(backup)
            logFile = File(context.filesDir, LOG_FILE_NAME)
        }

        log(Level.INFO, "Logger", "=== App Started ===")
        startLogcatCapture()
        startFileWriter()
    }

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message)
    fun w(tag: String, message: String) = log(Level.WARN, tag, message)
    fun w(tag: String, message: String, throwable: Throwable) {
        log(Level.WARN, tag, "$message: ${throwable.message}")
        log(Level.WARN, tag, throwable.stackTraceToString().take(500))
    }
    fun e(tag: String, message: String) = log(Level.ERROR, tag, message)
    fun e(tag: String, message: String, throwable: Throwable) {
        log(Level.ERROR, tag, "$message: ${throwable.message}")
        log(Level.ERROR, tag, throwable.stackTraceToString().take(500))
    }

    private fun log(level: Level, tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val levelChar = when (level) {
            Level.DEBUG -> "D"
            Level.INFO -> "I"
            Level.WARN -> "W"
            Level.ERROR -> "E"
        }
        val logLine = "$timestamp $levelChar/$tag: $message"

        // Logcat (fast, skip Rust to avoid echo)
        if (tag != "Rust") {
            when (level) {
                Level.DEBUG -> Log.d(tag, message)
                Level.INFO -> Log.i(tag, message)
                Level.WARN -> Log.w(tag, message)
                Level.ERROR -> Log.e(tag, message)
            }
        }

        // Enqueue for async file write (non-blocking)
        writeQueue.offer(logLine)

        // Append to ring buffer (synchronized on a small section, no I/O)
        synchronized(ringLock) {
            ringBuffer.addLast(logLine)
            if (ringBuffer.size > MAX_MEMORY_LOGS) {
                ringBuffer.removeFirst()
            }
        }

        // Emit snapshot to flow at most every 500ms (or immediately for ERRORs)
        val now = System.currentTimeMillis()
        if (now - lastSnapshotMs > 500 || level == Level.ERROR) {
            lastSnapshotMs = now
            synchronized(ringLock) {
                _logFlow.value = ringBuffer.toList()
            }
        }
    }

    private fun startFileWriter() {
        Thread({
            while (true) {
                try {
                    Thread.sleep(250)
                    flushPendingWrites()
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "log-writer").apply {
            isDaemon = true
            start()
        }
    }

    private fun flushPendingWrites() {
        val lines = ArrayList<String>(100)
        while (true) {
            val line = writeQueue.poll() ?: break
            lines.add(line)
            if (lines.size >= 100) break
        }
        if (lines.isEmpty()) return
        try {
            logFile?.appendText(lines.joinToString("\n") + "\n")
        } catch (e: Exception) {
            Log.e("AppLogger", "Failed to write logs: ${e.message}")
        }
    }

    private fun startLogcatCapture() {
        val pid = android.os.Process.myPid()
        Thread({
            try {
                val process = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-v", "brief", "-s", "UsbFlasherRust:*", "--pid=$pid")
                )
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { rawLine ->
                        // Use cached regex to avoid recompilation per line
                        val cleaned = rawLine.replace(ansiRegex, "").trim()
                        if (cleaned.isNotEmpty() && !cleaned.startsWith("-----")) {
                            log(Level.INFO, "Rust", cleaned)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("AppLogger", "Logcat capture failed: ${e.message}")
            }
        }, "logcat-capture").apply {
            isDaemon = true
            start()
        }
    }

    fun getRecentLogs(): List<String> = synchronized(ringLock) {
        ringBuffer.toList()
    }

    suspend fun getAllLogs(): String = withContext(Dispatchers.IO) {
        try {
            logFile?.readText() ?: "No logs available"
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }

    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        synchronized(ringLock) {
            ringBuffer.clear()
            _logFlow.value = emptyList()
        }
        try {
            logFile?.writeText("")
        } catch (e: Exception) {
            Log.e("AppLogger", "Failed to clear logs: ${e.message}")
        }
    }

    fun getLogFile(): File? = logFile
}
