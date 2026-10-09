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
}