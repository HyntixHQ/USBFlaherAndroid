package com.hyntix.android.usbflasher.download

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoCatalogParserTest {

    private val validVariant = """
        {
          "id": "ubuntu-test",
          "variantName": "Desktop",
          "description": "Test desktop image.",
          "desktopEnvironment": "GNOME",
          "imageType": "Live",
          "release": "26.10",
          "releaseChannel": "Current",
          "arch": "x86_64",
          "fileName": "ubuntu-test.iso",
          "url": "https://releases.ubuntu.com/26.10/ubuntu-test.iso",
          "checksumUrl": "https://releases.ubuntu.com/26.10/SHA256SUMS",
          "checksumFormat": "GNU",
          "approxSizeBytes": 1234,
          "minUsbBytes": 2048,
          "persistence": {
            "support": "app",
            "profiles": ["casper"],
            "requiresConfiguration": false,
            "notes": "Handled automatically."
          }
        }
    """.trimIndent()

    private fun catalog(vararg variants: String): String {
        val variantsJson = variants.joinToString(",")
        return """
            {
              "schemaVersion": 2,
              "categories": [
                { "id": "ubuntu-based", "name": "Ubuntu-based", "description": "Ubuntu family" }
              ],
              "distributions": [
                {
                  "id": "ubuntu",
                  "name": "Ubuntu",
                  "categoryId": "ubuntu-based",
                  "description": "Test distribution.",
                  "homepageUrl": "https://ubuntu.com/download/desktop",
                  "licenseUrl": "https://ubuntu.com/legal",
                  "releaseNotesUrl": "https://ubuntu.com/blog",
                  "architectures": ["x86_64"],
                  "hosts": ["releases.ubuntu.com"],
                  "variants": [$variantsJson]
                }
              ]
            }
        """.trimIndent()
    }

    @Test
    fun parsesNestedCatalog() {
        val result = IsoCatalogParser.parse(catalog(validVariant))
        assertTrue(result is CatalogLoadResult.Success)
        val parsed = (result as CatalogLoadResult.Success).catalog
        assertEquals(1, parsed.categories.size)
        assertEquals(1, parsed.distributions.size)
        assertEquals(1, parsed.entries.size)

        val entry = parsed.entries.first()
        assertEquals("Ubuntu Desktop 26.10", entry.displayName)
        assertEquals("ubuntu", entry.distroId)
        assertEquals("ubuntu-based", entry.categoryId)
        assertEquals("GNOME", entry.desktopEnvironment)
        assertEquals(PersistenceSupport.APP, entry.persistence.support)
        assertEquals(listOf("casper"), entry.persistence.profiles)
        assertTrue(entry.persistenceSupported)
        assertEquals(listOf("releases.ubuntu.com"), entry.isoHosts)
    }

    @Test
    fun rejectsUnsupportedSchema() {
        val result = IsoCatalogParser.parse(catalog(validVariant).replace("\"schemaVersion\": 2", "\"schemaVersion\": 1"))
        assertTrue(result is CatalogLoadResult.Failure)
    }

    @Test
    fun skipsVariantWithUndeclaredHost() {
        val wrongHost = validVariant.replace(
            "https://releases.ubuntu.com/26.10/ubuntu-test.iso",
            "https://evil.example/ubuntu-test.iso"
        )
        val result = IsoCatalogParser.parse(catalog(wrongHost))
        assertTrue(result is CatalogLoadResult.Failure)
    }

    @Test
    fun dropsDuplicateVariantIds() {
        val result = IsoCatalogParser.parse(catalog(validVariant, validVariant))
        assertTrue(result is CatalogLoadResult.Success)
        assertEquals(1, (result as CatalogLoadResult.Success).catalog.entries.size)
    }

    @Test
    fun acceptsStaticSha256WithoutChecksumUrl() {
        val staticHash = "a".repeat(64)
        val staticVariant = validVariant
            .replace(
                "\"checksumUrl\": \"https://releases.ubuntu.com/26.10/SHA256SUMS\",",
                "\"sha256\": \"$staticHash\","
            )
            .replace("\"checksumFormat\": \"GNU\"", "\"checksumFormat\": \"SHA256\"")
        val result = IsoCatalogParser.parse(catalog(staticVariant))
        assertTrue(result is CatalogLoadResult.Success)
        val entry = (result as CatalogLoadResult.Success).catalog.entries.first()
        assertEquals(staticHash, entry.sha256)
        assertEquals("", entry.checksumUrl)
    }

    @Test
    fun rejectsUnknownPersistenceSupport() {
        val bad = validVariant.replace("\"support\": \"app\"", "\"support\": \"unknown\"")
        val result = IsoCatalogParser.parse(catalog(bad))
        assertTrue(result is CatalogLoadResult.Failure)
    }

    @Test
    fun bundledCatalogIsValidAndMaintainable() {
        val file = File("src/main/assets/iso-catalog.json")
        assertTrue("Bundled catalog not found at ${file.absolutePath}", file.isFile)
        val result = IsoCatalogParser.parse(file.readText())
        assertTrue(result is CatalogLoadResult.Success)
        val parsed = (result as CatalogLoadResult.Success).catalog

        assertEquals(5, parsed.categories.size)
        assertEquals(20, parsed.distributions.size)
        assertTrue(parsed.entries.size >= 40)
        assertEquals(parsed.entries.size, parsed.entries.map { it.id }.distinct().size)
        assertEquals(parsed.distributions.size, parsed.distributions.map { it.id }.distinct().size)
        assertTrue(parsed.entries.all { it.isoHosts.isNotEmpty() })
        assertTrue(parsed.entries.all { it.persistence.support == PersistenceSupport.APP || it.persistence.notes != null })

        val ubuntu = parsed.distributions.first { it.id == "ubuntu" }
        assertTrue(ubuntu.variants.any { it.persistence.support == PersistenceSupport.APP })
        assertTrue(ubuntu.variants.any { it.variantName == "Kubuntu" })
    }
}
