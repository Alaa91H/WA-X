package com.wax.module.diagnostics.selftest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Redaction must actually remove user data from an export. */
class ExportRedactorTest {
    private val redactor = ExportRedactor()

    @Test fun jidsAreRemoved() {
        val cleaned = redactor.redact("sender=4915112345678@s.whatsapp.net")
        assertFalse(cleaned.contains("4915112345678"))
        assertTrue(cleaned.contains("[redacted]"))
    }

    @Test fun groupAndLidJidsAreRemoved() {
        for (jid in listOf(
            "12345@g.us", "99887766@lid", "status@broadcast",
        )) {
            assertFalse(redactor.redact("jid $jid").contains(jid.split("@")[1]))
        }
    }

    @Test fun tokensAreRemoved() {
        val cleaned = redactor.redact("token=abcdef123456 password: hunter2")
        assertFalse(cleaned.contains("abcdef123456"))
        assertFalse(cleaned.contains("hunter2"))
    }

    @Test fun filesystemPathsAreRemoved() {
        val cleaned = redactor.redact("failed at /data/data/com.whatsapp/shared_prefs/x.xml")
        assertFalse(cleaned.contains("/data/data"))
    }

    @Test fun theReportCountsWhatWasRemoved() {
        val redacted = redactor.redactAll(
            listOf("4915112345678@s.whatsapp.net", "token=abcdef123456"),
        )
        assertTrue(redacted.report.jidsRedacted >= 1)
        assertTrue(redacted.report.tokensRedacted >= 1)
        assertTrue(redacted.report.total >= 2)
        assertTrue(redacted.report.toJson().contains("\"jids\""))
    }

    @Test fun versionLikeTextSurvivesSoDiagnosticsStayUseful() {
        // A redactor that eats everything is useless; version strings must stay.
        val cleaned = redactor.redact("WhatsApp 2.26.39.74 build 4501")
        assertTrue(cleaned.contains("2.26.39.74"))
    }

    @Test fun archivedEntriesAreRedactedForRealNotPreviewed() {
        // The written archive must be the redacted one: a preview that does not
        // match the bytes on disk would be a privacy claim, not a guarantee.
        val entries = listOf(
            DiagnosticZipExporter.Entry("results.json", "{\"jid\":\"4915112345678@s.whatsapp.net\"}"),
            DiagnosticZipExporter.Entry("environment.json", "path=/data/data/com.whatsapp/databases/wa.db"),
            DiagnosticZipExporter.Entry("summary.md", "no user data here"),
        )
        val redacted = redactor.redactEntries(entries)
        assertEquals(entries.map { it.name }, redacted.entries.map { it.name })
        val jids = String(redacted.entries.first { it.name == "results.json" }.content)
        val environment = String(redacted.entries.first { it.name == "environment.json" }.content)
        assertFalse(jids.contains("4915112345678"))
        assertFalse(environment.contains("/data/data"))
        assertTrue(String(redacted.entries.first { it.name == "summary.md" }.content).isNotBlank())
        assertTrue("the export report must describe the whole archive", redacted.report.total >= 2)
    }

    @Test fun phoneNumbersAreCountedInTheExportReport() {
        val redacted = redactor.redactAll(listOf("msisdn 4915112345678"))
        assertTrue(
            "a removed phone number must appear in the report, not just vanish",
            redacted.report.numbersRedacted >= 1,
        )
    }
}