package com.hyntix.android.usbflasher.download

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * All download URLs are HTTPS. The ISO path/host checks are deliberately
 * narrow; the SHA-256 fetched from the official source remains the integrity
 * gate for every byte written to disk.
 */
object UrlPolicy {

    private val INVALID_FILE_CHARS = Regex("[\\\\/:*?\"<>|\\u0000-\\u001F\\u007F]")
    private val WINDOWS_RESERVED = Regex(
        "^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?$",
        RegexOption.IGNORE_CASE
    )

    fun parseIsoUrl(raw: String, allowedHosts: Set<String>): HttpUrl? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        if (!url.isHttps) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (url.port != 443) return null
        if (url.host.lowercase() !in allowedHosts) return null
        if (!url.encodedPath.lowercase().endsWith(".iso")) return null
        return url
    }

    fun parseChecksumUrl(raw: String, allowedHosts: Set<String>): HttpUrl? {
        val url = raw.trim().toHttpUrlOrNull() ?: return null
        if (!url.isHttps) return null
        if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
        if (url.port != 443) return null
        if (url.host.lowercase() !in allowedHosts) return null
        if (url.encodedPath.isBlank()) return null
        return url
    }

    /**
     * Fedora redirects its official URL to a rotating HTTPS mirror, so the
     * final host cannot be pinned. The path must still look like an ISO and
     * SHA-256 remains the integrity gate.
     */
    fun isSafeFinalIsoUrl(url: HttpUrl): Boolean {
        return url.isHttps &&
            url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.port == 443 &&
            url.encodedPath.lowercase().endsWith(".iso")
    }

    fun isHtmlContentType(contentType: String?): Boolean {
        val value = contentType?.lowercase() ?: return false
        return value.startsWith("text/html") || value.startsWith("application/xhtml+xml")
    }

    /**
     * Converts a server-provided filename into a safe single-component name.
     * The result always has an .iso suffix.
     */
    fun sanitizeFileName(raw: String): String {
        var name = raw.trim()
        name = name.substringAfterLast('/').substringAfterLast('\\')
        name = INVALID_FILE_CHARS.replace(name, "_")
        name = name.trim().trimEnd('.', ' ')
        if (name.isBlank() || name == "." || name == ".." || WINDOWS_RESERVED.matches(name)) {
            name = "download.iso"
        }
        if (!name.lowercase().endsWith(".iso")) {
            name = "$name.iso"
        }
        if (name.length > 120) {
            name = name.take(116).trimEnd('.', ' ') + ".iso"
        }
        return name
    }
}
