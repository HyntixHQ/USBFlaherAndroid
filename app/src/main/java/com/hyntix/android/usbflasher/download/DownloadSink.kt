package com.hyntix.android.usbflasher.download

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.system.Os
import java.io.Closeable
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Random-access destination used by the resumable engine. The SAF provider
 * chosen by the user is written directly; there is no app-private staging copy.
 */
interface DownloadSink : Closeable {
    fun prepare(offset: Long)
    fun size(): Long
    fun write(buffer: ByteArray, length: Int)
    fun flush()
}

class SafDownloadSink private constructor(
    private val stream: FileOutputStream,
    private val channel: FileChannel
) : DownloadSink {

    companion object {
        fun open(context: Context, uri: Uri): SafDownloadSink {
            val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: throw IOException("Destination is not writable")
            return try {
                // AutoCloseOutputStream owns the ParcelFileDescriptor and closes
                // it exactly once. Wrapping pfd.fileDescriptor directly and then
                // closing both objects can double-close a reused fd.
                val stream = ParcelFileDescriptor.AutoCloseOutputStream(pfd)
                SafDownloadSink(stream, stream.channel)
            } catch (e: Exception) {
                runCatching { pfd.close() }
                throw e
            }
        }
    }

    override fun prepare(offset: Long) {
        if (offset < 0) throw IOException("Invalid offset")
        val current = channel.size()
        if (offset > current) {
            throw IOException("Destination has fewer bytes than the resume offset")
        }
        if (current > offset) {
            channel.truncate(offset)
        }
        channel.position(offset)
    }

    override fun size(): Long = channel.size()

    override fun write(buffer: ByteArray, length: Int) {
        var position = 0
        while (position < length) {
            val written = channel.write(ByteBuffer.wrap(buffer, position, length - position))
            if (written <= 0) throw IOException("Could not write to destination")
            position += written
        }
    }

    override fun flush() {
        channel.force(false)
    }

    override fun close() {
        runCatching { stream.close() }
    }
}

object DownloadSinkFactory {
    fun open(context: Context, uri: Uri): DownloadSink = SafDownloadSink.open(context, uri)
}

object DownloadDestination {

    fun takePersistablePermission(context: Context, uri: Uri) {
        val readWrite = runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        if (readWrite.isFailure) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
    }

    fun displayName(context: Context, uri: Uri): String? {
        return queryColumn(context.contentResolver, uri, OpenableColumns.DISPLAY_NAME)?.toString()
    }

    fun size(context: Context, uri: Uri): Long? {
        return when (val value = queryColumn(context.contentResolver, uri, OpenableColumns.SIZE)) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    /**
     * Size of the actual document. Prefer fstat() on the provider's descriptor
     * because some providers return a stale or missing OpenableColumns.SIZE.
     */
    fun fileSize(context: Context, uri: Uri): Long? {
        val stat = runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                pfd.statSize.takeIf { it >= 0 }
            }
        }.getOrNull()
        if (stat != null) return stat
        return size(context, uri)
    }

    /**
     * Free bytes on the volume that actually holds the SAF document. This is
     * best effort: not every provider exposes a stat-able descriptor.
     */
    fun availableBytes(context: Context, uri: Uri): Long? {
        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val stats = Os.fstatvfs(pfd.fileDescriptor)
                val unit = if (stats.f_frsize > 0) stats.f_frsize else stats.f_bsize
                if (unit <= 0) return@use null
                val blocks = stats.f_bavail.coerceAtLeast(0)
                if (blocks > Long.MAX_VALUE / unit) Long.MAX_VALUE else blocks * unit
            }
        }.getOrNull()
    }

    private fun queryColumn(
        resolver: ContentResolver,
        uri: Uri,
        column: String
    ): Any? {
        return runCatching {
            resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(column)
                if (index < 0 || cursor.isNull(index)) null else {
                    when (cursor.getType(index)) {
                        android.database.Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                        else -> cursor.getString(index)
                    }
                }
            }
        }.getOrNull()
    }

    fun delete(context: Context, uri: Uri): Boolean {
        val deleted = runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }.getOrDefault(false)
        if (deleted) return true
        return runCatching {
            context.contentResolver.delete(uri, null, null) > 0
        }.getOrDefault(false)
    }
}
