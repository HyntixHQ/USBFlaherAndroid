package com.hyntix.android.usbflasher.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoCatalogParserTest {

    private val validEntry = """
        {
          "id": "ubuntu-test",
          "distro": "Ubuntu",
          "release": "26.04 LTS",
          "editionKey": "desktop",
          "arch": "x86_64",
          "fileName": "ubuntu-test.iso",
          "url": "https://releases.ubuntu.com/26.04/ubuntu-test.iso",
          "checksumUrl": "https://releases.ubuntu.com/26.04/SHA256SUMS",
          "checksumFormat": "GNU",
          "approxSizeBytes": 1234,
          "licenseUrl": "https://ubuntu.com/legal",
          "homepageUrl": "https://ubuntu.com/download",
          "persistenceProfiles": ["casper"],
          "minUsbBytes": 2048
        }
    """.trimIndent()

    @Test
    fun parsesValidCatalog() {
        val result = IsoCatalogParser.parse(
            """{ "schemaVersion": 1, "linux": [$validEntry] }"""
        )
        assertTrue(result is CatalogLoadResult.Success)
        val catalog = (result as CatalogLoadResult.Success).catalog
        assertEquals(1, catalog.linux.size)
        assertEquals("ubuntu-test.iso", catalog.linux.first().fileName)
        assertTrue(catalog.linux.first().persistenceSupported)
    }

    @Test
    fun rejectsUnsupportedSchema() {
        val result = IsoCatalogParser.parse(
            """{ "schemaVersion": 2, "linux": [$validEntry] }"""
        )
        assertTrue(result is CatalogLoadResult.Failure)
    }

    @Test
    fun skipsHttpAndUnknownEditionEntries() {
        val httpEntry = validEntry.replace(
            "https://releases.ubuntu.com/26.04/ubuntu-test.iso",
            "http://releases.ubuntu.com/26.04/ubuntu-test.iso"
        )
        val unknownEdition = validEntry
            .replace("ubuntu-test", "debian-test")
            .replace("\"desktop\"", "\"unknown_edition\"")
            .replace("releases.ubuntu.com", "cdimage.debian.org")

        val result = IsoCatalogParser.parse(
            """{ "schemaVersion": 1, "linux": [$httpEntry, $unknownEdition] }"""
        )
        assertTrue(result is CatalogLoadResult.Failure)
    }

    @Test
    fun dropsDuplicateIds() {
        val result = IsoCatalogParser.parse(
            """{ "schemaVersion": 1, "linux": [$validEntry, $validEntry] }"""
        )
        assertTrue(result is CatalogLoadResult.Success)
        assertEquals(1, (result as CatalogLoadResult.Success).catalog.linux.size)
    }
}
