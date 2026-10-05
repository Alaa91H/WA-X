package com.wax.module.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureFailureReportTest {
    private fun report(
        featureId: String = "AntiRevoke",
        code: FailureCode = FailureCode.RESOLVER_NOT_FOUND,
        resolver: String? = "loadReceiptMethod",
        message: String? = "resolver did not match",
        exceptionClass: String? = "java.lang.RuntimeException",
        frames: List<String> = listOf("com.wax.module.xposed.features.general.AntiRevoke.doHook"),
        timestampMillis: Long = 1_700_000_000_000L,
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
        threadName = "WAE-HookInstaller",
    )

    @Test
    fun aReportAnswersWhichFeatureResolverAndBuild() {
        val text = report().toSummaryLine()
        assertTrue(text.contains("AntiRevoke"))
        assertTrue(text.contains("RESOLVER_NOT_FOUND"))
        assertTrue(text.contains("loadReceiptMethod"))
        assertTrue(text.contains("2.26.40.21"))
    }

    @Test
    fun aReportWithoutAResolverOmitsIt() {
        val text = report(resolver = null).toSummaryLine()
        assertFalse(text.contains("resolver="))
    }

    @Test
    fun displayTextCarriesTheBuildContext() {
        val text = report().toDisplayText()
        assertTrue(text.contains("module: 1.6.2"))
        assertTrue(text.contains("whatsapp: 2.26.40.21"))
        assertTrue(text.contains("package: com.whatsapp"))
        assertTrue(text.contains("thread: WAE-HookInstaller"))
    }

    @Test
    fun displayTextListsFrames() {
        val text = report().toDisplayText()
        assertTrue(text.contains("frames:"))
        assertTrue(text.contains("AntiRevoke.doHook"))
    }

    @Test
    fun displayTextOmitsAbsentOptionalFields() {
        val text =
            report(resolver = null, exceptionClass = null, message = null, frames = emptyList())
                .toDisplayText()
        assertFalse(text.contains("resolver:"))
        assertFalse(text.contains("exception:"))
        assertFalse(text.contains("message:"))
        assertFalse(text.contains("frames:"))
    }

    @Test
    fun displayTextOmitsTheTimestampWhenUnset() {
        val text = report(timestampMillis = 0L).toDisplayText()
        assertFalse(text.contains("at:"))
    }

    // --- construction from a throwable ------------------------------------------------

    private fun throwable(
        message: String,
        cause: Throwable? = null,
    ): Throwable {
        val t = RuntimeException(message, cause)
        t.stackTrace =
            arrayOf(
                StackTraceElement("com.wax.module.xposed.features.general.AntiRevoke", "doHook", "A.kt", 1),
                StackTraceElement("android.app.Activity", "onCreate", "B.kt", 2),
            )
        return t
    }

    @Test
    fun buildingFromAThrowableRedactsTheMessage() {
        val built =
            FeatureFailureReport.fromThrowable(
                featureId = "AntiRevoke",
                throwable = throwable("no match for 4915112345678@s.whatsapp.net"),
                moduleVersion = "1.6.2",
                whatsappVersion = "2.26.40.21",
                packageName = "com.whatsapp",
            )
        assertFalse(built.message!!.contains("4915"))
        assertTrue(built.message!!.contains(ReportRedactor.PLACEHOLDER))
    }

    @Test
    fun buildingFromAThrowablePreservesTheBuildVersion() {
        val built =
            FeatureFailureReport.fromThrowable(
                featureId = "AntiRevoke",
                throwable = throwable("boom"),
                moduleVersion = "1.6.2",
                whatsappVersion = "2.26.40.21",
                packageName = "com.whatsapp",
            )
        assertEquals("2.26.40.21", built.whatsappVersion)
    }

    @Test
    fun buildingFromAThrowableDropsPlatformFrames() {
        val built =
            FeatureFailureReport.fromThrowable(
                featureId = "AntiRevoke",
                throwable = throwable("boom"),
                moduleVersion = "1.6.2",
                whatsappVersion = "2.26.40.21",
                packageName = "com.whatsapp",
            )
        assertEquals(1, built.frames.size)
        assertTrue(built.frames.single().contains("AntiRevoke"))
    }

    @Test
    fun buildingFromAThrowableRecordsTheExceptionClass() {
        val built =
            FeatureFailureReport.fromThrowable(
                featureId = "X",
                throwable = throwable("boom"),
                moduleVersion = "1.6.2",
                whatsappVersion = "2.26.40.21",
                packageName = "com.whatsapp",
            )
        assertEquals("java.lang.RuntimeException", built.exceptionClass)
    }

    @Test
    fun aThrowableWithNoMessageIsHandled() {
        val built =
            FeatureFailureReport.fromThrowable(
                featureId = "X",
                throwable = throwable(""),
                moduleVersion = "1.6.2",
                whatsappVersion = "2.26.40.21",
                packageName = "com.whatsapp",
            )
        assertEquals("", built.message)
    }

    // --- code classification ---------------------------------------------------------

    private fun codeOf(
        throwable: Throwable,
        stage: String? = null,
    ) = FailureCode.classify(throwable, stage)

    @Test
    fun aMissingClassIsClassified() {
        assertEquals(FailureCode.CLASS_NOT_FOUND, codeOf(ClassNotFoundException("nope")))
        assertEquals(FailureCode.CLASS_NOT_FOUND, codeOf(NoClassDefFoundError("nope")))
    }

    @Test
    fun aMissingMemberIsClassified() {
        assertEquals(FailureCode.MEMBER_NOT_FOUND, codeOf(NoSuchMethodException("nope")))
        assertEquals(FailureCode.MEMBER_NOT_FOUND, codeOf(NoSuchFieldException("nope")))
    }

    @Test
    fun aSecurityFailureIsClassified() {
        assertEquals(FailureCode.ACCESS_DENIED, codeOf(SecurityException("denied")))
    }

    @Test
    fun aTimeoutIsClassified() {
        assertEquals(
            FailureCode.TIMEOUT,
            codeOf(java.util.concurrent.TimeoutException("slow")),
        )
    }

    @Test
    fun anAmbiguousResolverHintWins() {
        assertEquals(
            FailureCode.RESOLVER_AMBIGUOUS,
            codeOf(RuntimeException("boom"), stage = "resolve: ambiguous match"),
        )
    }

    @Test
    fun aTimeoutHintWins() {
        assertEquals(
            FailureCode.TIMEOUT,
            codeOf(RuntimeException("boom"), stage = "install timed out"),
        )
    }

    @Test
    fun anUnrecognisedThrowableIsUnexpected() {
        assertEquals(FailureCode.UNEXPECTED, codeOf(IllegalStateException("boom")))
    }

    @Test
    fun everyCodeHasADistinctName() {
        assertEquals(
            FailureCode.entries.size,
            FailureCode.entries
                .map { it.name }
                .toSet()
                .size,
        )
    }

    // --- codec -----------------------------------------------------------------------

    @Test
    fun encodingProducesParseableJson() {
        val json = FailureReportCodec.encode(report())
        assertTrue(json.startsWith("{"))
        assertTrue(json.endsWith("}"))
        // Balanced braces and no stray unescaped quotes.
        assertEquals(json.count { it == '{' }, json.count { it == '}' })
    }

    @Test
    fun theSchemaVersionIsEmittedAsANumberNotAString() {
        // Consumers compare versions numerically; a quoted value would force string parsing.
        val json = FailureReportCodec.encode(report())
        assertTrue(json, json.contains("\"schemaVersion\":1,"))
        assertFalse(json.contains("\"schemaVersion\":\""))
    }

    @Test
    fun theTimestampIsEmittedAsANumber() {
        val json = FailureReportCodec.encode(report(timestampMillis = 1_700_000_000_000L))
        assertTrue(json.contains("\"timestamp\":1700000000000"))
    }

    @Test
    fun encodingIncludesEveryPopulatedField() {
        val json = FailureReportCodec.encode(report())
        for (field in listOf(
            "feature",
            "code",
            "moduleVersion",
            "whatsappVersion",
            "package",
            "resolver",
            "exception",
            "message",
            "thread",
            "timestamp",
            "frames",
        )) {
            assertTrue("missing field $field", json.contains("\"$field\":"))
        }
    }

    @Test
    fun encodingOmitsAbsentOptionalFields() {
        val json =
            FailureReportCodec.encode(
                report(resolver = null, exceptionClass = null, message = null, frames = emptyList(), timestampMillis = 0L),
            )
        assertFalse(json.contains("\"resolver\":"))
        assertFalse(json.contains("\"exception\":"))
        assertFalse(json.contains("\"message\":"))
        assertFalse(json.contains("\"frames\":"))
        assertFalse(json.contains("\"timestamp\":"))
    }

    @Test
    fun quotesInAMessageAreEscaped() {
        val json = FailureReportCodec.encode(report(message = "said \"boom\" loudly"))
        assertTrue(json.contains("\\\"boom\\\""))
    }

    @Test
    fun backslashesAreEscaped() {
        val json = FailureReportCodec.encode(report(message = "path C:\\temp"))
        assertTrue(json.contains("\\\\"))
    }

    @Test
    fun newlinesInAMessageAreEscaped() {
        val json = FailureReportCodec.encode(report(message = "line1\nline2"))
        assertFalse(json.contains("\n"))
        assertTrue(json.contains("\\n"))
    }

    @Test
    fun aControlCharacterIsEscapedAsUnicode() {
        val json = FailureReportCodec.encode(report(message = "bell\u0007here"))
        assertTrue(json.contains("\\u0007"))
    }

    @Test
    fun arabicTextIsEmittedUnescaped() {
        val json = FailureReportCodec.encode(report(message = "فشل"))
        assertTrue(json.contains("فشل"))
    }

    @Test
    fun aBatchIsEncodedAsAnArray() {
        val json = FailureReportCodec.encodeAll(listOf(report(), report(featureId = "HideSeen")))
        assertTrue(json.startsWith("["))
        assertTrue(json.endsWith("]"))
        assertEquals(2, json.split("\"schemaVersion\"").size - 1)
    }

    @Test
    fun anEmptyBatchEncodesAsAnEmptyArray() {
        assertEquals("[]", FailureReportCodec.encodeAll(emptyList()))
    }

    @Test
    fun textRenderingCountsTheReports() {
        val text = FailureReportCodec.renderText(listOf(report(), report(featureId = "HideSeen")))
        assertTrue(text.contains("reports: 2"))
        assertTrue(text.contains("AntiRevoke"))
        assertTrue(text.contains("HideSeen"))
    }

    @Test
    fun textRenderingOfAnEmptyBatchIsStillValid() {
        val text = FailureReportCodec.renderText(emptyList())
        assertTrue(text.contains("reports: 0"))
    }

    @Test
    fun renderedTextIsRedactedBecauseTheFieldsAlreadyAre() {
        val built =
            FeatureFailureReport.fromThrowable(
                featureId = "AntiRevoke",
                throwable = throwable("bad 4915112345678@s.whatsapp.net"),
                moduleVersion = "1.6.2",
                whatsappVersion = "2.26.40.21",
                packageName = "com.whatsapp",
            )
        assertFalse(FailureReportCodec.renderText(listOf(built)).contains("4915"))
    }
}
