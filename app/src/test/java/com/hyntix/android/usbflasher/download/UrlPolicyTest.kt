package com.hyntix.android.usbflasher.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {

    private val ubuntuHosts = setOf("releases.ubuntu.com")

    @Test
    fun acceptsOfficialHttpsIso() {
        val url = UrlPolicy.parseIsoUrl(
            "https://releases.ubuntu.com/26.04.1/ubuntu-26.04.1-desktop-amd64.iso",
            ubuntuHosts
        )
        assertTrue(url != null)
    }

    @Test
    fun rejectsHttpAndUnknownHosts() {
        assertNull(UrlPolicy.parseIsoUrl("http://releases.ubuntu.com/a.iso", ubuntuHosts))
        assertNull(UrlPolicy.parseIsoUrl("https://evil.example/a.iso", ubuntuHosts))
        assertNull(UrlPolicy.parseIsoUrl("https://releases.ubuntu.com/a.txt", ubuntuHosts))
    }

    @Test
    fun acceptsHttpsChecksumFile() {
        assertTrue(
            UrlPolicy.parseChecksumUrl(
                "https://releases.ubuntu.com/26.04.1/SHA256SUMS",
                ubuntuHosts
            ) != null
        )
        assertNull(
            UrlPolicy.parseChecksumUrl("http://releases.ubuntu.com/SHA256SUMS", ubuntuHosts)
        )
    }

    @Test
    fun honorsPerDistributionHostAllowlist() {
        val hosts = setOf("mirror.example.org")
        assertTrue(
            UrlPolicy.parseIsoUrl("https://mirror.example.org/linux.iso", hosts) != null
        )
        assertNull(
            UrlPolicy.parseIsoUrl("https://releases.ubuntu.com/linux.iso", hosts)
        )
    }

    @Test
    fun sanitizesFileNames() {
        assertEquals("ubuntu.iso", UrlPolicy.sanitizeFileName("../../ubuntu.iso"))
        assertEquals("bad_name.iso", UrlPolicy.sanitizeFileName("bad:name"))
        assertEquals("download.iso", UrlPolicy.sanitizeFileName(".."))
        assertEquals("image.ISO", UrlPolicy.sanitizeFileName("image.ISO"))
    }

    @Test
    fun detectsHtmlContentTypes() {
        assertTrue(UrlPolicy.isHtmlContentType("text/html; charset=utf-8"))
        assertTrue(UrlPolicy.isHtmlContentType("application/xhtml+xml"))
        assertEquals(false, UrlPolicy.isHtmlContentType("application/octet-stream"))
    }
}
