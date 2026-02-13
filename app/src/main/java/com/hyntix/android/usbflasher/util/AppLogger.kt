package com.hyntix.android.usbflasher.util

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * In-app file-based logger for debugging when logcat isn't available.
 * Logs are written to app's files directory and can be viewed/shared.
 */
object AppLogger {
    
    private const val LOG_FILE_NAME = "flash_debug.log"
    private const val MAX_LOG_SIZE = 5 * 1024 * 1024  // 5MB max
    private const val MAX_MEMORY_LOGS = 500
    
    private var logFile: File? = null
    private val dateFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    
    // In-memory log buffer for UI display
    private val memoryLogs = ConcurrentLinkedQueue<String>()
    
    // Log level
    enum class Level { DEBUG, INFO, WARN, ERROR }
    
    /**
     * Initialize logger with app context.
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
        
        // Also log to logcat
        when (level) {
            Level.DEBUG -> Log.d(tag, message)
            Level.INFO -> Log.i(tag, message)
            Level.WARN -> Log.w(tag, message)
            Level.ERROR -> Log.e(tag, message)
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
        memoryLogs.clear()
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
