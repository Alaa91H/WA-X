package com.wmods.wppenhacer.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureReportParserTest {
    private fun report(
        featureId: String = "AntiRevoke",
        code: FailureCode = FailureCode.RESOLVER_NOT_FOUND,
        resolver: String? = "loadReceiptMethod",
        message: String? = "resolver did not match",
        exceptionClass: String? = "java.lang.RuntimeException",
        frames: List<String> = listOf("com.wmods.wppenhacer.AntiRevoke.doHook", "com.wmods.wppenhacer.X.run"),
        timestampMillis: Long = 1_700_000_000_000L,
        threadName: String? = "WAE-HookInstaller",
    ) = FeatureFailureReport(
        featureId = featureId,
        code = code,
        moduleVersion = "1.6.2",
        whatsappVersion = "2.26.40.21",
        packageName = "com.whatsapp",
        resolver = resolver,
        exceptionClass = exceptionClass,
        message = message,
        frames = frames,
        timestampMillis = timestampMillis,
        threadName = threadName,
    )

    // --- round trip ------------------------------------------------------------------

    @Test
    fun aFullyPopulatedReportSurvivesARoundTrip() {
        val original = report()
        val parsed = FailureReportParser.parseOne(FailureReportCodec.encode(original))
        assertEquals(original, parsed)
    }

    @Test
    fun aMinimalReportSurvivesARoundTrip() {
        val original =
            report(
                resolver = null,
                message = null,
                exceptionClass = null,
                frames = emptyList(),
                timestampMillis = 0L,
                threadName = null,
            )
        assertEquals(original, FailureReportParser.parseOne(FailureReportCodec.encode(original)))
    }

    @Test
    fun aBatchSurvivesARoundTrip() {
        val original =
            listOf(
                report(featureId = "AntiRevoke"),
                report(featureId = "HideSeen", code = FailureCode.CLASS_NOT_FOUND),
                report(featureId = "Others", resolver = null),
            )
        assertEquals(original, FailureReportParser.parseAll(FailureReportCodec.encodeAll(original)))
    }

    @Test
    fun everyFailureCodeSurvivesARoundTrip() {
        for (code in FailureCode.entries) {
            val original = report(code = code)
            val parsed = FailureReportParser.parseOne(FailureReportCodec.encode(original))
            assertEquals("code $code", code, parsed?.code)
        }
    }

    @Test
    fun arabicTextSurvivesARoundTrip() {
        val original = report(message = "فشل تحليل الإصدار")
        assertEquals("فشل تحليل الإصدار", FailureReportParser.parseOne(FailureReportCodec.encode(original))?.message)
    }

    @Test
    fun anEmptyBatchRoundTrips() {
        assertEquals(emptyList<FeatureFailureReport>(), FailureReportParser.parseAll("[]"))
    }

    @Test
    fun manyReportsRoundTrip() {
        val original = (1..40).map { report(featureId = "Feature$it", timestampMillis = it.toLong()) }
        assertEquals(original, FailureReportParser.parseAll(FailureReportCodec.encodeAll(original)))
    }

    // --- escapes ---------------------------------------------------------------------

    @Test
    fun escapedQuotesSurviveARoundTrip() {
        val original = report(message = "said \"boom\"")
        assertEquals("said \"boom\"", FailureReportParser.parseOne(FailureReportCodec.encode(original))?.message)
    }

    @Test
    fun backslashesSurviveARoundTrip() {
        val original = report(message = "path C:\\temp\\x")
        assertEquals("path C:\\temp\\x", FailureReportParser.parseOne(FailureReportCodec.encode(original))?.message)
    }

    @Test
    fun newlinesSurviveARoundTrip() {
        val original = report(message = "line1\nline2\r\nline3")
        assertEquals("line1\nline2\r\nline3", FailureReportParser.parseOne(FailureReportCodec.encode(original))?.message)
    }

    @Test
    fun tabsSurviveARoundTrip() {
        val original = report(message = "a\tb")
        assertEquals("a\tb", FailureReportParser.parseOne(FailureReportCodec.encode(original))?.message)
    }

    @Test
    fun controlCharactersSurviveARoundTrip() {
        val original = report(message = "bell\u0007end")
        assertEquals("bell\u0007end", FailureReportParser.parseOne(FailureReportCodec.encode(original))?.message)
    }

    @Test
    fun theRedactionPlaceholderSurvivesARoundTrip() {
        val original = report(message = "bad ${ReportRedactor.PLACEHOLDER} id")
        assertEquals(original.message, FailureReportParser.parseOne(FailureReportCodec.encode(original))?.message)
    }

    // --- resilience ------------------------------------------------------------------

    @Test
    fun nullInputYieldsNoReports() {
        assertTrue(FailureReportParser.parseAll(null).isEmpty())
    }

    @Test
    fun blankInputYieldsNoReports() {
        assertTrue(FailureReportParser.parseAll("").isEmpty())
        assertTrue(FailureReportParser.parseAll("   ").isEmpty())
    }

    @Test
    fun corruptInputYieldsNoReportsRatherThanThrowing() {
        // A corrupt history file must never be able to break startup.
        for (bad in listOf("{", "[[[", "not json at all", "[{\"feature\":]", "\u0000\u0001")) {
            assertTrue("input: $bad", FailureReportParser.parseAll(bad).isEmpty())
        }
    }

    @Test
    fun aReportMissingItsCodeIsRejected() {
        assertTrue(FailureReportParser.parseAll("[{\"feature\":\"X\"}]").isEmpty())
    }

    @Test
    fun aReportWithAnUnknownCodeIsRejected() {
        assertTrue(FailureReportParser.parseAll("[{\"feature\":\"X\",\"code\":\"NOPE\"}]").isEmpty())
    }

    @Test
    fun aTrailingGarbageEntryIsDroppedButEarlierOnesSurvive() {
        val good = FailureReportCodec.encode(report(featureId = "Good"))
        val parsed = FailureReportParser.parseAll("[$good,{\"broken\":]")
        assertEquals(1, parsed.size)
        assertEquals("Good", parsed.single().featureId)
    }

    @Test
    fun aNestedObjectInAMessageIsToleratedAsAbsent() {
        // The codec never emits one, but a hand-edited file might. It must not throw, and
        // the unusable field simply comes back absent rather than poisoning the report.
        val text = "[{\"feature\":\"X\",\"code\":\"UNEXPECTED\",\"message\":{\"nested\":1}}]"
        val parsed = FailureReportParser.parseAll(text)
        assertEquals(1, parsed.size)
        assertNull(parsed.single().message)
    }

    @Test
    fun parseOneOfAnEmptyArrayIsNull() {
        assertNull(FailureReportParser.parseOne("[]"))
    }

    @Test
    fun parseOneOfGarbageIsNull() {
        assertNull(FailureReportParser.parseOne("garbage"))
    }

    @Test
    fun leadingWhitespaceIsTolerated() {
        val encoded = FailureReportCodec.encode(report())
        assertNotNull(FailureReportParser.parseOne("  \n$encoded  \n "))
    }

    @Test
    fun aLargeTimestampSurvives() {
        val original = report(timestampMillis = Long.MAX_VALUE)
        assertEquals(Long.MAX_VALUE, FailureReportParser.parseOne(FailureReportCodec.encode(original))?.timestampMillis)
    }
}
