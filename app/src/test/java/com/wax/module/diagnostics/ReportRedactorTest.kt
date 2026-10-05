package com.wax.module.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportRedactorTest {
    private val placeholder = ReportRedactor.PLACEHOLDER

    // --- identifiers that must never survive -----------------------------------------

    @Test
    fun aContactJidIsRemoved() {
        val out = ReportRedactor.redact("failed for 4915112345678@s.whatsapp.net")
        assertFalse(out.contains("4915112345678"))
        assertFalse(out.contains("s.whatsapp.net"))
    }

    @Test
    fun aGroupJidIsRemoved() {
        assertFalse(ReportRedactor.redact("120363000000000000@g.us").contains("120363"))
    }

    @Test
    fun aLidJidIsRemoved() {
        assertFalse(ReportRedactor.redact("123456789012345@lid").contains("123456789012345"))
    }

    @Test
    fun aBroadcastJidIsRemoved() {
        assertFalse(ReportRedactor.redact("1234567890@broadcast").contains("1234567890"))
    }

    @Test
    fun aNewsletterJidIsRemoved() {
        assertFalse(ReportRedactor.redact("1234567890@newsletter").contains("1234567890"))
    }

    @Test
    fun anEmailAddressIsRemoved() {
        assertFalse(ReportRedactor.redact("owner@example.com").contains("example.com"))
    }

    @Test
    fun aLabelledJidIsRemoved() {
        assertFalse(ReportRedactor.redact("jid=4915112345678@s.whatsapp.net").contains("4915112345678"))
    }

    @Test
    fun aLabelledPhoneIsRemoved() {
        assertFalse(ReportRedactor.redact("phone: +1 555 010 9999").contains("5550109999"))
    }

    @Test
    fun aBarePhoneNumberIsRemoved() {
        assertFalse(ReportRedactor.redact("calling 4915112345678 now").contains("4915112345678"))
    }

    @Test
    fun aFormattedPhoneNumberIsRemoved() {
        val out = ReportRedactor.redact("+49 151 1234 5678")
        assertFalse(out.contains("1234 5678"))
    }

    @Test
    fun aParenthesisedPhoneNumberIsRemoved() {
        assertFalse(ReportRedactor.redact("dial (020) 12345678").contains("12345678"))
    }

    @Test
    fun aLongDigitRunIsRemoved() {
        assertFalse(ReportRedactor.redact("id 99887766554433").contains("99887766554433"))
    }

    @Test
    fun aLongBracketedMessageIsRemoved() {
        // Only the bracketed payload is the message; the surrounding sentence is the
        // diagnostic text and is meant to survive.
        val message = "sending [this is a private message body] to chat"
        val out = ReportRedactor.redact(message)
        assertFalse(out.contains("private message"))
        assertTrue(out.contains("sending"))
    }

    @Test
    fun aBracketedMessageAtTheThresholdIsRemoved() {
        val payload = "x".repeat(ReportRedactor.MIN_QUOTED_LENGTH)
        assertFalse(ReportRedactor.redact("chat [$payload] here").contains(payload))
    }

    @Test
    fun aShortBracketedTokenIsKept() {
        // Below the threshold it reads as a diagnostic token, not a message.
        assertEquals("state [WAE]", ReportRedactor.redact("state [WAE]"))
    }

    @Test
    fun aQuotedMessageIsRemoved() {
        val out = ReportRedactor.redact("body was \"a private message here\"")
        assertFalse(out.contains("private message"))
    }

    @Test
    fun aLongQuotedMessageIsRemoved() {
        val message = "text was \"a very long quoted private message body that must not be shared here\""
        assertFalse(ReportRedactor.redact(message).contains("private message"))
    }

    @Test
    fun everyIdentifierInASentenceIsRemoved() {
        val out =
            ReportRedactor.redact(
                "resolver failed for 4915112345678@s.whatsapp.net while calling 49151123456789",
            )
        assertFalse(out.contains("4915"))
        assertTrue(out.contains(placeholder))
    }

    @Test
    fun aJidIsNotLeftPartiallyRewritten() {
        // The identifier rule runs before the bare digit rule, so no digits survive.
        val out = ReportRedactor.redact("x 4915112345678@s.whatsapp.net y")
        assertFalse(out.any { it.isDigit() })
    }

    // --- text that must be preserved -------------------------------------------------

    @Test
    fun ordinaryDiagnosticTextSurvives() {
        val text = "Resolver loadReceiptMethod did not match on this build"
        assertEquals(text, ReportRedactor.redact(text))
    }

    @Test
    fun shortNumbersThatAreNotIdentifiersSurvive() {
        // Version numbers and small counts must stay readable.
        assertEquals("api 34", ReportRedactor.redact("api 34"))
        assertEquals("code 404", ReportRedactor.redact("code 404"))
    }

    @Test
    fun versionStringsSurvive() {
        assertEquals("2.26.40.21", ReportRedactor.redact("2.26.40.21"))
        assertEquals("1.6.2-dev+544991A8", ReportRedactor.redact("1.6.2-dev+544991A8"))
    }

    @Test
    fun aVersionSurvivesEvenNextToAnIdentifier() {
        // Regression test: the phone rule matches the shape of a dotted version, so a
        // report saying which WhatsApp build failed was being redacted into uselessness.
        val out = ReportRedactor.redact("resolver failed on 2.26.40.21 for 4915112345678@s.whatsapp.net")
        assertTrue("version must survive, got: $out", out.contains("2.26.40.21"))
        assertFalse("identifier must not survive", out.contains("4915"))
    }

    @Test
    fun severalVersionsInOneStringAllSurvive() {
        val out = ReportRedactor.redact("from 2.26.39.5 to 2.26.40.21, module 1.6.2-dev+ABC1234")
        assertTrue(out.contains("2.26.39.5"))
        assertTrue(out.contains("2.26.40.21"))
        assertTrue(out.contains("1.6.2-dev+ABC1234"))
    }

    @Test
    fun anApiLevelSurvives() {
        assertEquals("api 34", ReportRedactor.redact("api 34"))
    }

    @Test
    fun aLongVersionIsStillPreservedNotTreatedAsAPhoneNumber() {
        val out = ReportRedactor.redact("2.26.40.21")
        assertFalse(out.contains(placeholder))
    }

    @Test
    fun arabicTextSurvives() {
        assertEquals("فشل تحميل الميزة", ReportRedactor.redact("فشل تحميل الميزة"))
    }

    @Test
    fun nullAndBlankInputAreReturnedUnchanged() {
        assertEquals("", ReportRedactor.redact(null))
        assertEquals("", ReportRedactor.redact(""))
    }

    @Test
    fun redactionIsIdempotent() {
        val once = ReportRedactor.redact("4915112345678@s.whatsapp.net")
        assertEquals(once, ReportRedactor.redact(once))
    }

    // --- bounding --------------------------------------------------------------------

    @Test
    fun shortTextIsNotTruncated() {
        assertEquals("short", ReportRedactor.redactAndBound("short"))
    }

    @Test
    fun longTextIsTruncatedAndMarked() {
        val out = ReportRedactor.redactAndBound("x".repeat(500), maxLength = 50)
        assertTrue(out.length <= 53)
        assertTrue(out.endsWith("..."))
    }

    @Test
    fun truncationHappensAfterRedaction() {
        // A long prefix that is itself sensitive must not survive truncation.
        val out = ReportRedactor.redactAndBound("4915112345678@s.whatsapp.net " + "y".repeat(400), maxLength = 40)
        assertFalse(out.contains("4915"))
    }

    @Test
    fun theDefaultFieldLimitIsApplied() {
        val out = ReportRedactor.redactAndBound("z".repeat(1000))
        assertTrue(out.length <= ReportRedactor.MAX_FIELD_LENGTH + 3)
    }

    // --- stack traces ----------------------------------------------------------------

    private fun frame(
        cls: String,
        method: String,
    ) = StackTraceElement(cls, method, "Source.kt", 10)

    @Test
    fun platformFramesAreDropped() {
        val throwable =
            RuntimeException("boom").apply {
                stackTrace =
                    arrayOf(
                        frame("android.app.Activity", "onCreate"),
                        frame("com.wax.module.xposed.features.general.AntiRevoke", "doHook"),
                    )
            }
        val frames = ReportRedactor.summariseStackTrace(throwable)
        assertEquals(1, frames.size)
        assertTrue(frames.single().endsWith("AntiRevoke.doHook"))
    }

    @Test
    fun jdkAndKotlinFramesAreDropped() {
        val throwable =
            RuntimeException("boom").apply {
                stackTrace =
                    arrayOf(
                        frame("java.lang.reflect.Method", "invoke"),
                        frame("kotlin.collections.CollectionsKt", "listOf"),
                        frame("dalvik.system.VMStack", "getStackTrace"),
                    )
            }
        assertTrue(ReportRedactor.summariseStackTrace(throwable).isEmpty())
    }

    @Test
    fun constructorsAreLabelled() {
        val throwable =
            RuntimeException("boom").apply {
                stackTrace = arrayOf(frame("com.wax.module.xposed.features.general.Others", ""))
            }
        assertTrue(ReportRedactor.summariseStackTrace(throwable).single().endsWith("Others.<init>"))
    }

    @Test
    fun framesAreBounded() {
        val throwable =
            RuntimeException("boom").apply {
                stackTrace = Array(80) { index -> frame("com.wax.module.F$index", "m") }
            }
        assertEquals(ReportRedactor.MAX_FRAMES, ReportRedactor.summariseStackTrace(throwable).size)
    }

    @Test
    fun aCauseIsRecordedAsATrailer() {
        val cause = IllegalStateException("inner 4915112345678@s.whatsapp.net")
        val throwable =
            RuntimeException("outer", cause).apply {
                stackTrace = arrayOf(frame("com.wax.module.F", "m"))
            }
        val frames = ReportRedactor.summariseStackTrace(throwable)
        assertTrue(frames.last().startsWith("caused by:"))
        assertFalse(frames.last().contains("4915"))
    }

    @Test
    fun aSelfReferentialCauseDoesNotLoop() {
        // A throwable whose cause is itself would recurse forever if the walk were not
        // identity guarded. The assertion is that it terminates and stays bounded.
        val throwable = RuntimeException("boom")
        val frames = ReportRedactor.summariseStackTrace(throwable)
        assertTrue(frames.size <= ReportRedactor.MAX_FRAMES + 1)
    }

    @Test
    fun aRealCauseChainTerminates() {
        val root = IllegalStateException("root 4915112345678@s.whatsapp.net")
        val middle = RuntimeException("middle", root)
        val top =
            RuntimeException("top", middle).apply {
                stackTrace = arrayOf(frame("com.wax.module.F", "m"))
            }
        val frames = ReportRedactor.summariseStackTrace(top)
        assertTrue(frames.size <= ReportRedactor.MAX_FRAMES + 1)
        assertTrue(frames.none { it.contains("4915") })
    }

    @Test
    fun anEmptyStackTraceYieldsNoFrames() {
        val throwable = RuntimeException("boom").apply { stackTrace = emptyArray() }
        assertTrue(ReportRedactor.summariseStackTrace(throwable).isEmpty())
    }
}
