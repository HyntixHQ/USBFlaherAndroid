package com.hyntix.android.usbflasher.download

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

object IsoCatalogParser {

    private val EDITION_KEYS = setOf("desktop", "live_gnome", "cinnamon", "workstation")
    private val CHECKSUM_FORMATS = setOf("GNU", "FEDORA")

    fun parse(json: String): CatalogLoadResult {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val schemaVersion = root.int("schemaVersion")
            if (schemaVersion != 1) {
                return CatalogLoadResult.Failure("Unsupported catalog schema version")
            }
            val array = root.getAsJsonArray("linux")
                ?: return CatalogLoadResult.Failure("Catalog has no Linux entries")

            val seen = mutableSetOf<String>()
            val entries = mutableListOf<LinuxIsoEntry>()
            array.forEach { element ->
                val entry = parseEntry(element as? JsonObject ?: return@forEach)
                if (entry != null && seen.add(entry.id)) {
                    entries.add(entry)
                }
            }
            if (entries.isEmpty()) {
                CatalogLoadResult.Failure("Catalog has no valid Linux entries")
            } else {
                CatalogLoadResult.Success(
                    IsoCatalog(
                        schemaVersion = schemaVersion,
                        generatedAt = root.string("generatedAt"),
                        linux = entries
                    )
                )
            }
        } catch (e: Exception) {
            CatalogLoadResult.Failure("Catalog is not valid JSON")
        }
    }

    private fun parseEntry(obj: JsonObject): LinuxIsoEntry? {
        val id = obj.string("id") ?: return null
        val distro = obj.string("distro") ?: return null
        val release = obj.string("release") ?: return null
        val editionKey = obj.string("editionKey") ?: return null
        val arch = obj.string("arch") ?: return null
        val fileName = obj.string("fileName") ?: return null
        val url = obj.string("url") ?: return null
        val checksumUrl = obj.string("checksumUrl") ?: return null
        val checksumFormat = obj.string("checksumFormat") ?: return null
        val licenseUrl = obj.string("licenseUrl") ?: return null
        val homepageUrl = obj.string("homepageUrl") ?: return null
        val approxSizeBytes = obj.long("approxSizeBytes") ?: return null

        if (id.isBlank() || distro.isBlank() || release.isBlank() || arch.isBlank()) return null
        if (editionKey !in EDITION_KEYS) return null
        if (checksumFormat !in CHECKSUM_FORMATS) return null
        if (approxSizeBytes <= 0) return null
        if (UrlPolicy.sanitizeFileName(fileName) != fileName) return null

        val iso = UrlPolicy.parseIsoUrl(url) ?: return null
        if (!iso.encodedPath.lowercase().endsWith("/${fileName.lowercase()}")) return null
        if (UrlPolicy.parseChecksumUrl(checksumUrl) == null) return null
        if (!isSafeInfoUrl(licenseUrl) || !isSafeInfoUrl(homepageUrl)) return null

        val profiles = obj.getAsJsonArray("persistenceProfiles")
            ?.mapNotNull { it.takeIf { value -> value.isJsonPrimitive }?.asString }
            ?.filter { it.isNotBlank() }
            ?: emptyList()

        return LinuxIsoEntry(
            id = id,
            distro = distro,
            release = release,
            editionKey = editionKey,
            arch = arch,
            fileName = fileName,
            url = url,
            checksumUrl = checksumUrl,
            checksumFormat = checksumFormat,
            approxSizeBytes = approxSizeBytes,
            licenseUrl = licenseUrl,
            homepageUrl = homepageUrl,
            persistenceProfiles = profiles,
            minUsbBytes = obj.long("minUsbBytes")?.takeIf { it > 0 }
        )
    }

    private fun isSafeInfoUrl(raw: String): Boolean {
        val url = raw.toHttpUrlOrNull() ?: return false
        return url.isHttps && url.username.isEmpty() && url.password.isEmpty()
    }

    private fun JsonObject.string(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive) return null
        return value.asString
    }

    private fun JsonObject.long(name: String): Long? {
        val value = get(name) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive) return null
        return runCatching { value.asLong }.getOrNull()
    }

    private fun JsonObject.int(name: String): Int? {
        val value = get(name) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive) return null
        return runCatching { value.asInt }.getOrNull()
    }

    private fun JsonArray.mapNotNull(transform: (com.google.gson.JsonElement) -> String?): List<String> {
        val result = mutableListOf<String>()
        forEach { element -> transform(element)?.let(result::add) }
        return result
    }
}

class IsoCatalogLoader(private val context: Context) {
    fun load(): CatalogLoadResult {
        return try {
            val json = context.assets.open("iso-catalog.json").bufferedReader().use { it.readText() }
            IsoCatalogParser.parse(json)
        } catch (e: Exception) {
            CatalogLoadResult.Failure("Could not load the ISO catalog")
        }
    }
}
