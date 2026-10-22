package com.hyntix.android.usbflasher.download

import android.content.Context
import android.net.Uri
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

class DownloadVerifier(private val context: Context) {

    fun verify(
        uri: Uri,
        expectedSha256: String,
        shouldCancel: () -> Boolean = { false },
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): VerificationResult {
        val expected = expectedSha256.trim().lowercase()
        if (!expected.matches(Regex("[0-9a-f]{64}"))) {
            return VerificationResult.Failure(DownloadError(DownloadErrorCode.CHECKSUM_TABLE_INVALID))
        }

        return try {
            val total = DownloadDestination.size(context, uri) ?: 0L
            val digest = MessageDigest.getInstance("SHA-256")
            var readTotal = 0L
            var lastProgressAt = 0L
            val buffer = ByteArray(BUFFER_SIZE)
            context.contentResolver.openInputStream(uri)?.use { input ->
                while (true) {
                    if (shouldCancel()) return VerificationResult.Cancelled
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                    readTotal += read
                    val now = System.currentTimeMillis()
                    if (now - lastProgressAt >= PROGRESS_INTERVAL_MS ||
                        (total > 0 && readTotal >= total)
                    ) {
                        lastProgressAt = now
                        onProgress(readTotal, total)
                    }
                }
            } ?: return VerificationResult.Failure(
                DownloadError(DownloadErrorCode.DESTINATION_UNAVAILABLE)
            )

            onProgress(readTotal, if (total > 0) total else readTotal)
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual.equals(expected, ignoreCase = true)) {
                VerificationResult.Success(actual)
            } else {
                VerificationResult.Mismatch(actual)
            }
        } catch (e: SecurityException) {
            VerificationResult.Failure(DownloadError(DownloadErrorCode.DESTINATION_UNAVAILABLE))
        } catch (e: FileNotFoundException) {
            VerificationResult.Failure(DownloadError(DownloadErrorCode.DESTINATION_UNAVAILABLE))
        } catch (e: IOException) {
            VerificationResult.Failure(DownloadError(DownloadErrorCode.DESTINATION_UNAVAILABLE))
        } catch (e: Exception) {
            VerificationResult.Failure(DownloadError(DownloadErrorCode.UNKNOWN))
        }
    }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val PROGRESS_INTERVAL_MS = 250L
    }
}
