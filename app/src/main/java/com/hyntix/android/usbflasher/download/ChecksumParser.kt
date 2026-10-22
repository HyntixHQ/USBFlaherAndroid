package com.hyntix.android.usbflasher.download

/**
 * Parses the checksum formats published by Ubuntu, Debian, Linux Mint, and
 * Fedora. GPG signatures are intentionally not verified in v1; HTTPS and the
 * signed APK's pinned catalog are the trust anchors for the checksum file.
 */
object ChecksumParser {

    private val GNU_LINE = Regex("^([0-9a-fA-F]{64})[ \\t]+\\*?(.+?)\\s*$")
    private val FEDORA_LINE = Regex("^SHA256\\s*\\((.+)\\)\\s*=\\s*([0-9a-fA-F]{64})\\s*$")

    data class Table(
        val hashes: Map<String, String>,
        val duplicates: Set<String>
    ) {
        fun hashFor(fileName: String): String? {
            if (fileName in duplicates) return null
            return hashes[fileName]
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
        if (fileName.isBlank()) return
        val normalized = hash.lowercase()
        val previous = hashes[fileName]
        if (previous != null) {
            duplicates.add(fileName)
        } else {
            hashes[fileName] = normalized
        }
    }
}
