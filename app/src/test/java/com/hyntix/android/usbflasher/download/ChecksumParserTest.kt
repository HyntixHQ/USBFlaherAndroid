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

    @Test
    fun parsesSingleHashFiles() {
        val table = ChecksumParser.parse("$hash\n")
        assertEquals(hash, table.hashFor("anything.iso"))
    }

    @Test
    fun parsesMixedMd5Sha256AndSha512Files() {
        val sha512 = "a".repeat(128)
        val table = ChecksumParser.parse(
            """
            d41d8cd98f00b204e9800998ecf8427e  image.iso
            $sha512  image.iso
            $hash  image.iso
            """.trimIndent()
        )
        assertEquals(hash, table.hashFor("image.iso"))
    }

    @Test
    fun parsesQubesDigestsFormat() {
        val table = ChecksumParser.parse(
            """
            $hash *Qubes-R4.3.2-x86_64.iso
            ${"b".repeat(128)} *Qubes-R4.3.2-x86_64.iso
            """.trimIndent()
        )
        assertEquals(hash, table.hashFor("Qubes-R4.3.2-x86_64.iso"))
    }

    @Test
    fun normalizesChecksumFilePaths() {
        val table = ChecksumParser.parse("$hash  ./Nobara-44.iso\n")
        assertEquals(hash, table.hashFor("Nobara-44.iso"))
    }

    @Test
    fun returnsOnlyHashForRollingFilenames() {
        val table = ChecksumParser.parse("$hash  nixos-graphical-26.05.12400.iso\n")
        assertEquals(hash, table.onlyHash())
        assertEquals(hash, table.onlyHash())
    }

    @Test
    fun onlyHashRejectsAmbiguousTables() {
        val table = ChecksumParser.parse(
            """
            $hash  first.iso
            ${hash.reversed()}  second.iso
            """.trimIndent()
        )
        assertNull(table.onlyHash())
    }
}
