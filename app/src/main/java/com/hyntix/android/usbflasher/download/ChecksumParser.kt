package com.hyntix.android.usbflasher.download

/**
 * Parses the checksum formats published by the supported distributions:
 * standard GNU checksum lines, Fedora's CHECKSUM format, and single-hash files
 * such as the `.iso.sha256` files used by NixOS. GPG signatures are
 * intentionally not verified in v1; HTTPS and the signed APK's bundled catalog
 * are the trust anchors for the checksum file.
 */
object ChecksumParser {

    private const val SINGLE_HASH_KEY = "\u0000single"

    private val GNU_LINE = Regex("^([0-9a-fA-F]{64})[ \\t]+\\*?(.+?)\\s*$")
    private val FEDORA_LINE = Regex("^SHA256\\s*\\((.+)\\)\\s*=\\s*([0-9a-fA-F]{64})\\s*$")
    private val PLAIN_HASH = Regex("^([0-9a-fA-F]{64})$")

    data class Table(
        val hashes: Map<String, String>,
        val duplicates: Set<String>
    ) {
        fun hashFor(fileName: String): String? {
            if (fileName in duplicates) return null
            return hashes[fileName] ?: hashes[SINGLE_HASH_KEY]
        }

        /**
         * Returns the only hash in a checksum file when the published filename
         * changes with each build (for example, NixOS and openSUSE snapshots).
         */
        fun onlyHash(): String? {
            if (duplicates.isNotEmpty() || hashes.size != 1) return null
            return hashes.values.first()
        }
    }

    fun parse(text: String): Table {
        val hashes = linkedMapOf<String, String>()
        val duplicates = linkedSetOf<String>()

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() ||
                line.startsWith("#") ||
                line.startsWith("-----BEGIN PGP") ||
                line.startsWith("-----END PGP")
            ) {
                return@forEach
            }

            val fedora = FEDORA_LINE.find(line)
            if (fedora != null) {
                add(
                    fileName = fedora.groupValues[1].trim(),
                    hash = fedora.groupValues[2],
                    hashes = hashes,
                    duplicates = duplicates
                )
                return@forEach
            }

            val plain = PLAIN_HASH.find(line)
            if (plain != null) {
                add(
                    fileName = SINGLE_HASH_KEY,
                    hash = plain.groupValues[1],
                    hashes = hashes,
                    duplicates = duplicates
                )
                return@forEach
            }

            val gnu = GNU_LINE.find(line) ?: return@forEach
            add(
                fileName = gnu.groupValues[2].trim(),
                hash = gnu.groupValues[1],
                hashes = hashes,
                duplicates = duplicates
            )
        }

        return Table(hashes, duplicates)
    }

    private fun add(
        fileName: String,
        hash: String,
        hashes: MutableMap<String, String>,
        duplicates: MutableSet<String>
    ) {
        val name = fileName.trim()
            .substringAfterLast('/')
            .substringAfterLast('\\')
        if (name.isBlank()) return
        val normalized = hash.lowercase()
        val previous = hashes[name]
        if (previous != null) {
            duplicates.add(name)
        } else {
            hashes[name] = normalized
        }
    }
}
