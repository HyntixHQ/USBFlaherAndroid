package com.hyntix.android.usbflasher.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPolicyTest {

    @Test
    fun acceptsOfficialHttpsIso() {
        val url = UrlPolicy.parseIsoUrl(
            "https://releases.ubuntu.com/26.04.1/ubuntu-26.04.1-desktop-amd64.iso"
        )
        assertTrue(url != null)
    }

    @Test
    fun rejectsHttpAndUnknownHosts() {
        assertNull(UrlPolicy.parseIsoUrl("http://releases.ubuntu.com/a.iso"))
        assertNull(UrlPolicy.parseIsoUrl("https://evil.example/a.iso"))
        assertNull(UrlPolicy.parseIsoUrl("https://releases.ubuntu.com/a.txt"))
    }

    @Test
    fun acceptsHttpsChecksumFile() {
        assertTrue(
            UrlPolicy.parseChecksumUrl("https://releases.ubuntu.com/26.04.1/SHA256SUMS") != null
        )
        assertNull(UrlPolicy.parseChecksumUrl("http://releases.ubuntu.com/SHA256SUMS"))
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
