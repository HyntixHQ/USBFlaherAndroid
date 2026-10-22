package com.hyntix.android.usbflasher.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChecksumParserTest {

    private val hash = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

    @Test
    fun parsesGnuChecksumFormats() {
        val table = ChecksumParser.parse(
            """
            # comment
            $hash  ubuntu.iso
            ${hash.uppercase()} *debian.iso
            -----BEGIN PGP SIGNATURE-----
            -----END PGP SIGNATURE-----
            """.trimIndent()
        )

        assertEquals(hash, table.hashFor("ubuntu.iso"))
        assertEquals(hash, table.hashFor("debian.iso"))
        assertNull(table.hashFor("missing.iso"))
    }

    @Test
    fun parsesFedoraChecksumFormat() {
        val table = ChecksumParser.parse("SHA256 (Fedora.iso) = $hash")
        assertEquals(hash, table.hashFor("Fedora.iso"))
    }

    @Test
    fun rejectsDuplicateFileNames() {
        val table = ChecksumParser.parse(
            """
            $hash  same.iso
            ${hash.reversed()}  same.iso
            """.trimIndent()
        )
        assertNull(table.hashFor("same.iso"))
    }
}
