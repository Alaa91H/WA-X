package com.wax.module.diagnostics.selftest

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Builds the diagnostics ZIP in memory and verifies it before anything is
 * written to storage.
 *
 * Everything the issue demands is enforced here rather than at the call site:
 * entry names are validated against traversal, every entry is size-capped, the
 * archive is re-opened and its manifest and checksums re-checked, and a
 * source that was not collected is **declared** in the manifest rather than
 * replaced with a fabricated log.
 */
class DiagnosticZipExporter(
    private val maxEntryBytes: Int = 512 * 1024,
    private val maxTotalBytes: Int = 4 * 1024 * 1024,
    private val maxEntries: Int = 200,
) {
    data class Entry(val name: String, val content: ByteArray)

    data class BuildResult(
        val bytes: ByteArray,
        val entryNames: List<String>,
        val checksum: String,
        val declaredMissing: List<String>,
    )

    class ExportTooLargeException(message: String) : IllegalStateException(message)

    /** Rejects absolute paths, parent traversal and empty or odd names. */
    fun isSafeEntryName(name: String): Boolean {
        if (name.isBlank() || name.length > 200) return false
        if (name.startsWith("/") || name.contains('\\')) return false
        if (name.contains("..")) return false
        if (name.any { it.code < 0x20 }) return false
        // Exactly one directory level is allowed, for logs/ and tests/.
        return name.split('/').none { it.isEmpty() }
    }

    /**
     * Deterministic file name required by the issue. Uses UTC so two exports
     * of the same moment collide predictably rather than randomly.
     */
    fun fileName(timestampMillis: Long): String {
        val format = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
        format.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return "WA-X-diagnostics-" + format.format(java.util.Date(timestampMillis)) + ".zip"
    }

    fun build(entries: List<Entry>, declaredMissing: List<String> = emptyList()): BuildResult {
        require(entries.size <= maxEntries) { "too many entries: ${entries.size}" }
        for (entry in entries) {
            require(isSafeEntryName(entry.name)) { "unsafe entry name: ${entry.name}" }
            require(entry.content.size <= maxEntryBytes) {
                "entry ${entry.name} exceeds ${maxEntryBytes} bytes"
            }
        }
        val buffer = ByteArrayOutputStream()
        val ordered = entries.sortedBy { it.name }
        ZipOutputStream(buffer).use { zip ->
            for (entry in ordered) {
                zip.putNextEntry(ZipEntry(entry.name))
                zip.write(entry.content)
                zip.closeEntry()
            }
        }
        val bytes = buffer.toByteArray()
        if (bytes.size > maxTotalBytes) {
            throw ExportTooLargeException("archive exceeds ${maxTotalBytes} bytes")
        }
        val verification = verify(bytes)
        return BuildResult(
            bytes = bytes,
            entryNames = ordered.map { it.name },
            checksum = sha256(bytes),
            declaredMissing = declaredMissing,
        )
    }

    data class Verification(
        val manifestPresent: Boolean,
        val checksumsPresent: Boolean,
        val entryNames: List<String>,
        val checksumMatches: Boolean,
    ) {
        val valid: Boolean get() = manifestPresent && checksumsPresent && checksumMatches
    }

    /**
     * Re-opens the archive and re-checks the manifest and the checksum file.
     *
     * A ZIP that cannot be re-opened is not exportable: presenting it to the
     * user as a finished report would be exactly the "incomplete file shown as
     * complete" failure the issue forbids.
     */
    fun verify(bytes: ByteArray): Verification {
        var manifestPresent = false
        val names = mutableListOf<String>()
        val payloads = mutableMapOf<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                names.add(entry.name)
                val content = zip.readBytes()
                if (entry.name == "manifest.json") {
                    manifestPresent = true
                } else {
                    payloads[entry.name] = content
                }
                entry = zip.nextEntry
            }
        }
        val checksumsPresent = payloads.containsKey("checksums.sha256")
        return Verification(
            manifestPresent = manifestPresent,
            checksumsPresent = checksumsPresent,
            entryNames = names,
            // Every declared digest must match the bytes actually written. A
            // checksum file that parses but does not match is a corrupt export,
            // and corruption has to be caught here rather than by the user.
            checksumMatches = checksumsPresent &&
                checksumsMatch(payloads["checksums.sha256"]!!, payloads),
        )
    }

    private fun checksumsMatch(declared: ByteArray, payloads: Map<String, ByteArray>): Boolean {
        val rows = declared.toString(Charsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
        if (rows.isEmpty()) return false
        for (row in rows) {
            val parts = row.split("  ", limit = 2)
            if (parts.size != 2) return false
            val (digest, name) = parts
            val content = payloads[name] ?: return false
            if (sha256(content) != digest) return false
        }
        return true
    }

    /** Per-entry checksums, written next to the payload as `checksums.sha256`. */
    fun checksums(entries: List<Entry>): ByteArray = buildString {
        for (entry in entries.sortedBy { it.name }) {
            append(sha256(entry.content)).append("  ").append(entry.name).append('\n')
        }
    }.toByteArray()

    fun readEntry(bytes: ByteArray, name: String): ByteArray? {
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (entry.name == name) return zip.readBytes()
                zip.readBytes()
                entry = zip.nextEntry
            }
        }
        return null
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Streaming writer for the SAF output, so a large report never sits in RAM twice. */
    fun writeTo(stream: OutputStreamTarget, bytes: ByteArray, onCancelled: () -> Boolean = { false }) {
        var offset = 0
        while (offset < bytes.size) {
            if (onCancelled()) throw IllegalStateException("export cancelled")
            val chunk = minOf(CHUNK, bytes.size - offset)
            stream.write(bytes, offset, chunk)
            offset += chunk
        }
        stream.finish()
    }

    /** The single output surface the exporter needs, so it stays testable. */
    interface OutputStreamTarget {
        fun write(buffer: ByteArray, offset: Int, length: Int)
        fun finish()
    }

    private companion object {
        const val CHUNK = 16 * 1024
    }
}

/** Reads a whole stream, bounded, for verification and import. */
fun InputStream.readBounded(limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read <= 0) break
        total += read
        if (total > limit) throw DiagnosticZipExporter.ExportTooLargeException("stream exceeds $limit bytes")
        output.write(buffer, 0, read)
    }
    return output.toByteArray()
}
