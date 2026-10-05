package com.wax.module.resolver

import com.wax.module.diagnostics.FailureCode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserExplanationTest {
    // --- resolution outcomes ---------------------------------------------------------

    @Test
    fun anExactResultReadsAsWorkingNormally() {
        assertTrue(UserExplanation.forOutcome(Resolution.exact("m")).contains("works normally"))
    }

    @Test
    fun aLikelyResultSaysItIsAGuess() {
        val text = UserExplanation.forOutcome(Resolution.likely("m", "heuristic"))
        assertTrue(text.contains("guess"))
        assertTrue("the user should be warned it may break", text.contains("WhatsApp update"))
    }

    @Test
    fun notFoundBlamesAWhatsAppChangeAndPromisesAFix() {
        val text = UserExplanation.forOutcome(Resolution.NotFound())
        assertTrue(text.contains("WhatsApp changed"))
        assertTrue("the user needs to know it is not their fault", text.contains("when WA X is updated"))
    }

    @Test
    fun ambiguousSaysItRefusedToGuess() {
        val text = UserExplanation.forOutcome(Resolution.Ambiguous(listOf("a", "b")))
        assertTrue(text.contains("could not tell which"))
        assertTrue(text.contains("not installed"))
    }

    @Test
    fun incompatibleRepeatsTheReason() {
        val text = UserExplanation.forOutcome(Resolution.Incompatible("needs API 31"))
        assertTrue(text.contains("needs API 31"))
    }

    @Test
    fun theTargetIsIncludedWhenKnown() {
        val text = UserExplanation.forOutcome(Resolution.NotFound(), "com.whatsapp.Foo")
        assertTrue(text.contains("com.whatsapp.Foo"))
    }

    @Test
    fun anIdentifierInTheTargetIsRedacted() {
        // The target normally comes from a resolver name, but a hand-written target must
        // not become a leak path.
        val text = UserExplanation.forOutcome(Resolution.NotFound(), "chat_4915112345678@s.whatsapp.net")
        assertFalse(text.contains("4915"))
    }

    @Test
    fun theFeatureIdIsPrependedWhenGiven() {
        assertTrue(
            UserExplanation
                .forOutcome("AntiRevoke", Resolution.NotFound())
                .startsWith("AntiRevoke"),
        )
    }

    // --- failure codes ---------------------------------------------------------------

    @Test
    fun everyCodeProducesASentence() {
        for (code in FailureCode.entries) {
            val text = UserExplanation.forCode(code)
            assertTrue("$code produced no text", text.isNotBlank())
            assertTrue("$code produced no ending", text.endsWith("."))
        }
    }

    @Test
    fun everyCodeAvoidsInternalClassNames() {
        // A user cannot act on a resolver name; naming one invites pointless bug reports.
        for (code in FailureCode.entries) {
            val text = UserExplanation.forCode(code)
            assertFalse("$code leaked an internal name: $text", text.contains("Unobfuscator"))
            assertFalse("$code leaked an internal name: $text", text.contains("Resolution"))
        }
    }

    @Test
    fun aNotFoundCodeSaysWhatsAppChangedSomething() {
        assertTrue(UserExplanation.forCode(FailureCode.RESOLVER_NOT_FOUND).contains("WhatsApp changed"))
    }

    @Test
    fun aTimeoutCodeSaysItWasSkipped() {
        assertTrue(UserExplanation.forCode(FailureCode.TIMEOUT).contains("skipped"))
    }

    @Test
    fun anAccessDeniedCodeSaysAndroidBlockedIt() {
        assertTrue(UserExplanation.forCode(FailureCode.ACCESS_DENIED).contains("Android blocked"))
    }

    @Test
    fun aDetailIsAppended() {
        assertTrue(UserExplanation.forCode(FailureCode.UNEXPECTED, "hook refused").contains("hook refused"))
    }

    @Test
    fun anIdentifierInTheDetailIsRedacted() {
        val text =
            UserExplanation.forCode(
                FailureCode.UNEXPECTED,
                "failed for 4915112345678@s.whatsapp.net",
            )
        assertFalse(text.contains("4915"))
    }

    @Test
    fun aBlankDetailIsOmitted() {
        assertFalse(UserExplanation.forCode(FailureCode.UNEXPECTED, "   ").contains("()"))
    }

    @Test
    fun onlyNotableStatesAreWorthTellingTheUserAbout() {
        assertFalse(UserExplanation.shouldTellUser(FeatureHealth.HEALTHY))
        assertFalse(UserExplanation.shouldTellUser(FeatureHealth.UNKNOWN))
        assertTrue(UserExplanation.shouldTellUser(FeatureHealth.INCOMPATIBLE))
        assertTrue(UserExplanation.shouldTellUser(FeatureHealth.FAILED))
    }
}
