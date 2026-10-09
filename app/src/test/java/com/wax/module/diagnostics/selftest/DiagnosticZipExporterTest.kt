package com.wax.module.diagnostics.selftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** ZIP export contract: real archive, traversal-safe, verifiable, honest. */
class DiagnosticZipExporterTest {
    private val exporter = DiagnosticZipExporter()

    private fun entry(name: String, content: String) =
        DiagnosticZipExporter.Entry(name, content.toByteArray())

    @Test fun entryNamesRejectTraversalAndAbsolutePaths() {
        assertFalse(exporter.isSafeEntryName("../evil.json"))
        assertFalse(exporter.isSafeEntryName("logs/../../evil.json"))
        assertFalse(exporter.isSafeEntryName("/etc/passwd"))
        assertFalse(exporter.isSafeEntryName("logs\\evil.json"))
        assertFalse(exporter.isSafeEntryName(""))
        assertTrue(exporter.isSafeEntryName("manifest.json"))
        assertTrue(exporter.isSafeEntryName("logs/sanitized-runtime.log"))
        assertTrue(exporter.isSafeEntryName("tests/hook_hide_blue_tick.json"))
    }

    @Test fun theFileNameIsDeterministicAndSafe() {
        val millis = 1_726_000_000_000L
        assertEquals(exporter.fileName(millis), exporter.fileName(millis))
        assertTrue(exporter.fileName(millis).startsWith("WA-X-diagnostics-"))
        assertTrue(exporter.fileName(millis).endsWith(".zip"))
        assertFalse(exporter.fileName(millis).contains("/"))
    }

    @Test fun anArchiveIsRealAndReopensWithItsManifest() {
        val entries = listOf(entry("manifest.json", "{\"scan\":\"1\"}"), entry("results.json", "[]"))
        val built = exporter.build(entries, declaredMissing = listOf("logs/sanitized-runtime.log"))
        assertTrue(built.bytes.size > 0)
        val verification = exporter.verify(built.bytes)
        assertTrue(verification.manifestPresent)
        assertEquals(listOf("manifest.json", "results.json"), verification.entryNames.sorted())
        assertEquals("{\"scan\":\"1\"}", String(exporter.readEntry(built.bytes, "manifest.json")!!))
        assertEquals(listOf("logs/sanitized-runtime.log"), built.declaredMissing)
    }

    @Test fun anArchiveWithoutAManifestIsNotExportable() {
        val built = exporter.build(listOf(entry("results.json", "[]")))
        assertFalse(
            "an archive lacking the manifest must not pass verification",
            exporter.verify(built.bytes).manifestPresent,
        )
    }

    @Test fun unsafeEntryNamesAreRejectedAtBuildTime() {
        val failure = runCatching {
            exporter.build(listOf(entry("../escape.json", "{}")))
        }.exceptionOrNull()
        assertNotNull("traversal must be refused before anything is written", failure)
    }

    @Test fun oversizedEntriesAreRefused() {
        val small = DiagnosticZipExporter(maxEntryBytes = 64)
        val failure = runCatching {
            small.build(listOf(entry("manifest.json", "x".repeat(128))))
        }.exceptionOrNull()
        assertNotNull(failure)
    }

    @Test fun perEntryChecksumsAreWrittenForEveryEntry() {
        val entries = listOf(entry("manifest.json", "a"), entry("results.json", "b"))
        val checksums = String(exporter.checksums(entries))
        assertEquals(2, checksums.trim().lines().size)
        assertTrue(checksums.contains("manifest.json"))
        assertTrue(checksums.contains("results.json"))
        assertTrue(checksums.lines().first().substringBefore("  ").length == 64)
    }

    @Test fun writeToStreamsEveryByteAndCanBeCancelled() {
        val written = RecordingTarget()
        exporter.writeTo(written, ByteArray(40_000) { 1 })
        assertEquals(40_000, written.total)

        val cancelling = RecordingTarget()
        val failure = runCatching {
            exporter.writeTo(cancelling, ByteArray(40_000) { 1 }, onCancelled = { true })
        }.exceptionOrNull()
        assertNotNull("cancellation must abort the export", failure)
        assertFalse("a cancelled export must not be finalized", cancelling.finished)
    }

    @Test fun missingSourcesAreDeclaredRatherThanFabricated() {
        val inputs = DiagnosticReportBuilder.Inputs(
            report = sampleReport(),
            environment = DiagnosticReportBuilder.Environment(
                appVersion = "1.2.0", appBuildSha = "abc123",
                whatsappPackage = "com.whatsapp", whatsappVersion = "2.26.39.74",
                androidVersion = "17", androidSdk = 37, abi = "arm64-v8a",
            ),
            hooks = emptyList(),
            resolverStates = emptyMap(),
            sanitizedLog = null,
        )
        val names = DiagnosticReportBuilder.entries(inputs).map { it.name }
        assertFalse("a missing log must never be fabricated", names.contains("logs/sanitized-runtime.log"))
        val manifest = String(
            DiagnosticReportBuilder.entries(inputs).first { it.name == "manifest.json" }.content,
        )
        assertTrue(manifest.contains("logs/sanitized-runtime.log"))
        assertTrue(manifest.contains("hooks.json"))
        assertFalse("no unredacted dump option may be advertised", manifest.contains("unredacted_dump_available\":true"))
    }

    @Test fun theReportSetContainsEveryContractFile() {
        val inputs = DiagnosticReportBuilder.Inputs(
            report = sampleReport(),
            environment = DiagnosticReportBuilder.Environment(
                "1.2.0", "abc123", "com.whatsapp", "2.26.39.74", "17", 37, "arm64-v8a",
            ),
            hooks = listOf("wax.modern.typing_privacy.composing"),
            resolverStates = mapOf("jid_class" to "AVAILABLE"),
            sanitizedLog = "sanitized line\n",
        )
        val names = DiagnosticReportBuilder.entries(inputs).map { it.name }.toSet()
        for (required in listOf(
            "manifest.json", "summary.md", "results.json", "environment.json",
            "hooks.json", "resolvers.json", "errors.json", "timings.json",
            "redaction-report.json", "logs/sanitized-runtime.log",
        )) {
            assertTrue("missing $required", names.contains(required))
        }
        assertTrue(names.any { it.startsWith("tests/") })
    }

    @Test fun theSummaryIsBilingual() {
        val summary = String(
            DiagnosticReportBuilder.entries(
                DiagnosticReportBuilder.Inputs(
                    report = sampleReport(),
                    environment = DiagnosticReportBuilder.Environment(
                        "1.2.0", "abc", "com.whatsapp", "2.26.39.74", "17", 37, "arm64-v8a",
                    ),
                    hooks = emptyList(), resolverStates = emptyMap(), sanitizedLog = null,
                ),
            ).first { it.name == "summary.md" }.content,
        )
        assertTrue(summary.contains("Root causes"))
        assertTrue("the contract requires Arabic and English", summary.contains("الجذر"))
    }

    private fun sampleReport(): DiagnosticEngine.Report {
        val engine = DiagnosticEngine()
        try {
            return engine.run(
                DiagnosticEngine.RunConfig.deep("2.26.39.74", "com.whatsapp"),
                AtomicCheckInventory.PIPELINE,
                emptyMap(),
            )
        } finally {
            engine.shutdown()
        }
    }

    private class RecordingTarget : DiagnosticZipExporter.OutputStreamTarget {
        var total = 0
        var finished = false
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            total += length
        }

        override fun finish() {
            finished = true
        }
    }
}