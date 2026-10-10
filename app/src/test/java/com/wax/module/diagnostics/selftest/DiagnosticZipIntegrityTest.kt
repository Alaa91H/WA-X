package com.wax.module.diagnostics.selftest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Adversarial offline ZIP fixtures: imports are not inherently trustworthy. */
class DiagnosticZipIntegrityTest {
    private val exporter = DiagnosticZipExporter()

    @Test fun unlistedPayloadDoesNotInheritOtherFilesChecksum() {
        val manifest = DiagnosticZipExporter.Entry("manifest.json", "{}".toByteArray())
        val zip =
            rawZip(
                mapOf(
                    manifest.name to manifest.content,
                    "checksums.sha256" to exporter.checksums(listOf(manifest)),
                    "tests/extra.json" to "unlisted".toByteArray(),
                ),
            )
        assertFalse(exporter.verify(zip).valid)
        assertTrue(DiagnosticArchiveImporter().import(zip) is DiagnosticArchiveImporter.ImportResult.Rejected)
    }

    @Test fun compressedOversizedEntryIsRejectedBeforeUnboundedInflation() {
        val giant = ByteArray(512 * 1024 + 1) { 0 }
        val zip = rawZip(mapOf("manifest.json" to giant))
        assertFalse(exporter.verify(zip).valid)
    }

    @Test fun unsafeEntryNamesAreRefusedDuringVerification() {
        val zip = rawZip(mapOf("../secret.txt" to byteArrayOf(1)))
        assertFalse(exporter.verify(zip).valid)
    }

    @Test fun generatedArchivesRequireUniqueNames() {
        val same = DiagnosticZipExporter.Entry("manifest.json", "{}".toByteArray())
        var thrown = false
        try {
            exporter.build(listOf(same, same))
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    private fun rawZip(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            for ((name, content) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
