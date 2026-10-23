package com.hyntix.android.usbflasher.download

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Parses the signed, bundled catalog. The catalog is intentionally data-driven:
 * adding a distribution or a variant should only require a JSON edit. URL hosts
 * are declared per distribution so the allowlist does not grow in Kotlin code.
 */
object IsoCatalogParser {

    const val SCHEMA_VERSION = 2

    private val CHECKSUM_FORMATS = setOf("GNU", "FEDORA", "SHA256", "PLAIN", "SINGLE")
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    private val HOST = Regex(
        "^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$"
    )

    fun parse(json: String): CatalogLoadResult {
        return try {
            val root = JsonParser.parseString(json).asJsonObject
            val schemaVersion = root.int("schemaVersion")
            if (schemaVersion != SCHEMA_VERSION) {
                return CatalogLoadResult.Failure("Unsupported catalog schema version")
            }

            val categoryArray = root.getAsJsonArray("categories")
                ?: return CatalogLoadResult.Failure("Catalog has no categories")
            val distributionArray = root.getAsJsonArray("distributions")
                ?: return CatalogLoadResult.Failure("Catalog has no distributions")

            val categories = parseCategories(categoryArray)
            if (categories.isEmpty()) {
                return CatalogLoadResult.Failure("Catalog has no valid categories")
            }
            val categoryIds = categories.mapTo(mutableSetOf()) { it.id }

            val seenDistributions = mutableSetOf<String>()
            val seenVariants = mutableSetOf<String>()
            val distributions = mutableListOf<LinuxDistribution>()
            distributionArray.forEach { element ->
                val distribution = parseDistribution(
                    element as? JsonObject ?: return@forEach,
                    categoryIds = categoryIds,
                    seenDistributions = seenDistributions,
                    seenVariants = seenVariants
                )
                if (distribution != null) distributions.add(distribution)
            }
            if (distributions.isEmpty()) {
                return CatalogLoadResult.Failure("Catalog has no valid distributions")
            }

            // Keep declared order, but do not render empty category headers.
            val usedCategoryIds = distributions.mapTo(mutableSetOf()) { it.categoryId }
            CatalogLoadResult.Success(
                IsoCatalog(
                    schemaVersion = schemaVersion,
                    generatedAt = root.string("generatedAt"),
                    categories = categories.filter { it.id in usedCategoryIds },
                    distributions = distributions
                )
            )
        } catch (e: Exception) {
            CatalogLoadResult.Failure("Catalog is not valid JSON")
        }
    }

    private fun parseCategories(array: JsonArray): List<LinuxCategory> {
        val result = mutableListOf<LinuxCategory>()
        val seen = mutableSetOf<String>()
        array.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val id = obj.string("id")?.trim() ?: return@forEach
            val name = obj.string("name")?.trim() ?: return@forEach
            if (id.isBlank() || name.isBlank() || !seen.add(id)) return@forEach
            result.add(
                LinuxCategory(
                    id = id,
                    name = name,
                    description = obj.string("description")?.trim().orEmpty()
                )
            )
        }
        return result
    }

    private fun parseDistribution(
        obj: JsonObject,
        categoryIds: Set<String>,
        seenDistributions: MutableSet<String>,
        seenVariants: MutableSet<String>
    ): LinuxDistribution? {
        val id = obj.string("id")?.trim() ?: return null
        val name = obj.string("name")?.trim() ?: return null
        val categoryId = obj.string("categoryId")?.trim() ?: return null
        val description = obj.string("description")?.trim() ?: return null
        val homepageUrl = obj.string("homepageUrl")?.trim() ?: return null
        val licenseUrl = obj.string("licenseUrl")?.trim() ?: return null
        val releaseNotesUrl = obj.string("releaseNotesUrl")?.trim()?.takeIf { it.isNotEmpty() }

        if (id.isBlank() || name.isBlank() || description.isBlank()) return null
        if (categoryId !in categoryIds || !seenDistributions.add(id)) return null
        if (!isSafeInfoUrl(homepageUrl) || !isSafeInfoUrl(licenseUrl)) return null
        if (releaseNotesUrl != null && !isSafeInfoUrl(releaseNotesUrl)) return null

        val hosts = obj.stringList("hosts")
            .map { it.trim().lowercase() }
            .filter { it.matches(HOST) }
            .distinct()
        if (hosts.isEmpty()) return null

        val architectures = obj.stringList("architectures")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (architectures.isEmpty()) return null

        val variantArray = obj.getAsJsonArray("variants") ?: return null
        val variants = mutableListOf<LinuxIsoEntry>()
        variantArray.forEach { element ->
            val variant = parseVariant(
                element as? JsonObject ?: return@forEach,
                distribution = obj,
                categoryId = categoryId,
                hosts = hosts.toSet(),
                seenVariants = seenVariants
            )
            if (variant != null) variants.add(variant)
        }
        if (variants.isEmpty()) return null

        return LinuxDistribution(
            id = id,
            name = name,
            categoryId = categoryId,
            description = description,
            homepageUrl = homepageUrl,
            licenseUrl = licenseUrl,
            releaseNotesUrl = releaseNotesUrl,
            architectures = architectures,
            variants = variants
        )
    }

    private fun parseVariant(
        obj: JsonObject,
        distribution: JsonObject,
        categoryId: String,
        hosts: Set<String>,
        seenVariants: MutableSet<String>
    ): LinuxIsoEntry? {
        val id = obj.string("id")?.trim() ?: return null
        val variantName = obj.string("variantName")?.trim() ?: return null
        val description = obj.string("description")?.trim() ?: return null
        val imageType = obj.string("imageType")?.trim() ?: return null
        val release = obj.string("release")?.trim() ?: return null
        val releaseChannel = obj.string("releaseChannel")?.trim() ?: return null
        val arch = obj.string("arch")?.trim() ?: return null
        val fileName = obj.string("fileName")?.trim() ?: return null
        val checksumFileName = obj.string("checksumFileName")?.trim() ?: fileName
        val url = obj.string("url")?.trim() ?: return null
        val checksumUrl = obj.string("checksumUrl")?.trim().orEmpty()
        val checksumFormat = obj.string("checksumFormat")?.trim() ?: return null
        val sha256 = obj.string("sha256")?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        val approxSizeBytes = obj.long("approxSizeBytes") ?: return null

        if (id.isBlank() || !seenVariants.add(id)) return null
        if (variantName.isBlank() || description.isBlank() || imageType.isBlank()) return null
        if (release.isBlank() || releaseChannel.isBlank() || arch.isBlank()) return null
        if (checksumFormat !in CHECKSUM_FORMATS) return null
        if (approxSizeBytes <= 0) return null
        if (UrlPolicy.sanitizeFileName(fileName) != fileName) return null
        if (UrlPolicy.sanitizeFileName(checksumFileName) != checksumFileName) return null

        val iso = UrlPolicy.parseIsoUrl(url, hosts) ?: return null
        if (!iso.encodedPath.lowercase().endsWith(".iso")) return null
        if (checksumUrl.isNotEmpty()) {
            if (UrlPolicy.parseChecksumUrl(checksumUrl, hosts) == null) return null
        } else if (sha256 == null) {
            return null
        }
        if (sha256 != null && !sha256.matches(SHA256)) return null

        val persistenceObj = obj.getAsJsonObject("persistence")
        val persistence = parsePersistence(persistenceObj) ?: return null
        val desktopEnvironment = obj.string("desktopEnvironment")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        return LinuxIsoEntry(
            id = id,
            distroId = distribution.string("id")?.trim().orEmpty(),
            distro = distribution.string("name")?.trim().orEmpty(),
            categoryId = categoryId,
            variantName = variantName,
            description = description,
            desktopEnvironment = desktopEnvironment,
            imageType = imageType,
            release = release,
            releaseChannel = releaseChannel,
            arch = arch,
            fileName = fileName,
            url = url,
            checksumUrl = checksumUrl,
            checksumFileName = checksumFileName,
            checksumFormat = checksumFormat,
            sha256 = sha256,
            approxSizeBytes = approxSizeBytes,
            licenseUrl = distribution.string("licenseUrl")?.trim().orEmpty(),
            homepageUrl = distribution.string("homepageUrl")?.trim().orEmpty(),
            releaseNotesUrl = distribution.string("releaseNotesUrl")
                ?.trim()
                ?.takeIf { it.isNotEmpty() },
            persistence = persistence,
            minUsbBytes = obj.long("minUsbBytes")?.takeIf { it > 0 },
            isoHosts = hosts.toList()
        )
    }

    private fun parsePersistence(obj: JsonObject?): PersistenceInfo? {
        if (obj == null) return PersistenceInfo()
        val support = when (obj.string("support")?.trim()?.lowercase()) {
            "app" -> PersistenceSupport.APP
            "distro" -> PersistenceSupport.DISTRO
            "none" -> PersistenceSupport.NONE
            else -> return null
        }
        val profiles = obj.stringList("profiles")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
        if (support == PersistenceSupport.APP && profiles.isEmpty()) return null
        val notes = obj.string("notes")?.trim()?.takeIf { it.isNotEmpty() }
        return PersistenceInfo(
            support = support,
            profiles = if (support == PersistenceSupport.APP) profiles else emptyList(),
            requiresConfiguration = obj.boolean("requiresConfiguration") ?: false,
            notes = notes
        )
    }

    private fun isSafeInfoUrl(raw: String): Boolean {
        val url = raw.toHttpUrlOrNull() ?: return false
        return url.isHttps &&
            url.username.isEmpty() &&
            url.password.isEmpty() &&
            url.port == 443
    }

    private fun JsonObject.string(name: String): String? {
        val value = get(name) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive) return null
        return runCatching { value.asString }.getOrNull()
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

    private fun JsonObject.boolean(name: String): Boolean? {
        val value = get(name) ?: return null
        if (value.isJsonNull || !value.isJsonPrimitive) return null
        return runCatching { value.asBoolean }.getOrNull()
    }

    private fun JsonObject.stringList(name: String): List<String> {
        val array = getAsJsonArray(name) ?: return emptyList()
        val result = mutableListOf<String>()
        array.forEach { element ->
            if (element.isJsonPrimitive) {
                runCatching { element.asString }.getOrNull()?.let(result::add)
            }
        }
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
