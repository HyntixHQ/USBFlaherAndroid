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

/**
 * In-app file-based logger for debugging when logcat isn't available.
 * Captures ALL events (Kotlin + Rust) with no filtering.
 * Logs are written to app's files directory and can be viewed/shared from the UI.
 */
object AppLogger {
    
    private const val LOG_FILE_NAME = "flash_debug.log"
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024  // 5MB max
    private const val MAX_MEMORY_LOGS = 1000
    
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    
    // In-memory log buffer for UI display
    private val memoryLogs = ConcurrentLinkedQueue<String>()
    
    // Observable flow for the UI — emits the full buffer on every new log
    private val _logFlow = MutableStateFlow<List<String>>(emptyList())
    val logFlow: StateFlow<List<String>> = _logFlow.asStateFlow()
    
    // Log level
    enum class Level { DEBUG, INFO, WARN, ERROR }
    
    /**
     * Initialize logger with app context.
     * Call this in MainActivity.onCreate() before anything else.
     */
    fun init(context: Context) {
        logFile = File(context.filesDir, LOG_FILE_NAME)
        
        // Rotate log if too large
        if (logFile?.exists() == true && logFile!!.length() > MAX_LOG_SIZE) {
            val backup = File(context.filesDir, "flash_debug_old.log")
            backup.delete()
            logFile?.renameTo(backup)
            logFile = File(context.filesDir, LOG_FILE_NAME)
        }
        
        // Add startup marker
        log(Level.INFO, "Logger", "=== App Started ===")
        
        // Start capturing Rust logs from logcat
        startLogcatCapture()
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
    
    private val lock = Any()
    
    private fun log(level: Level, tag: String, message: String) {
        val timestamp = dateFormat.format(Date())
        val levelChar = when (level) {
            Level.DEBUG -> "D"
            Level.INFO -> "I"
            Level.WARN -> "W"
            Level.ERROR -> "E"
        }
        val logLine = "$timestamp $levelChar/$tag: $message"
        
        // Also log to logcat (skip Rust-captured lines to avoid echo)
        if (tag != "Rust") {
            when (level) {
                Level.DEBUG -> Log.d(tag, message)
                Level.INFO -> Log.i(tag, message)
                Level.WARN -> Log.w(tag, message)
                Level.ERROR -> Log.e(tag, message)
            }
        }
        
        synchronized(lock) {
            // Add to memory buffer
            memoryLogs.add(logLine)
            while (memoryLogs.size > MAX_MEMORY_LOGS) {
                memoryLogs.poll()
            }
            
            // Write to file
            try {
                logFile?.appendText("$logLine\n")
            } catch (e: Exception) {
                Log.e("AppLogger", "Failed to write log: ${e.message}")
            }
            
            // Notify UI observers
            _logFlow.value = memoryLogs.toList()
        }
    }
    
    /**
     * Capture Rust-side logs from logcat.
     * Spawns a background thread that reads logcat for our PID's UsbFlasherRust tag
     * and feeds each line into the in-app logger.
     */
    private fun startLogcatCapture() {
        val pid = android.os.Process.myPid()
        Thread({
            try {
                // Clear old logcat buffer first, then tail new entries
                val process = Runtime.getRuntime().exec(
                    arrayOf("logcat", "-v", "brief", "-s", "UsbFlasherRust:*", "--pid=$pid")
                )
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    line?.let { rawLine ->
                        // Strip ANSI escape codes and logcat prefix, keep the message
                        val cleaned = rawLine.replace(Regex("\u001b\\[[0-9;]*m"), "").trim()
                        if (cleaned.isNotEmpty() && !cleaned.startsWith("-----")) {
                            // Write directly to memory + file, skip logcat echo
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
    
    /**
     * Get recent logs from memory.
     */
    fun getRecentLogs(): List<String> = memoryLogs.toList()
    
    /**
     * Get all logs from file.
     */
    suspend fun getAllLogs(): String = withContext(Dispatchers.IO) {
        try {
            logFile?.readText() ?: "No logs available"
        } catch (e: Exception) {
            "Error reading logs: ${e.message}"
        }
    }
    
    /**
     * Clear all logs.
     */
    suspend fun clearLogs() = withContext(Dispatchers.IO) {
        synchronized(lock) {
            memoryLogs.clear()
            _logFlow.value = emptyList()
        }
        try {
            logFile?.writeText("")
        } catch (e: Exception) {
            Log.e("AppLogger", "Failed to clear logs: ${e.message}")
        }
    }
    
    /**
     * Get log file for sharing.
     */
    fun getLogFile(): File? = logFile
}
