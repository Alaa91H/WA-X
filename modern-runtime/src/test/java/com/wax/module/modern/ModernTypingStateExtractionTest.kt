package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression: WhatsApp's composing state is the third argument, not the first. */
class ModernTypingStateExtractionTest {
    private val exactShape =
        arrayOf<Class<*>>(
            String::class.java,
            Any::class.java,
            Int::class.javaPrimitiveType!!,
        )

    @Test
    fun thirdArgumentIsTheValidatedComposingState() {
        assertTrue(ModernTypingPrivacyFeature.isSafeComposingSignature(exactShape))
        assertEquals(
            ModernTypingPrivacyFeature.STATE_TYPING,
            ModernTypingPrivacyFeature.composingState(exactShape, listOf("opaque", Any(), 0)),
        )
        assertEquals(
            ModernTypingPrivacyFeature.STATE_RECORDING,
            ModernTypingPrivacyFeature.composingState(exactShape, listOf("opaque", Any(), 1)),
        )
    }

    @Test
    fun mismatchedRuntimeArgumentsCannotSuppress() {
        assertNull(ModernTypingPrivacyFeature.composingState(exactShape, listOf(1, "x")))
        assertNull(ModernTypingPrivacyFeature.composingState(exactShape, listOf(1, "x", "bad")))
    }

    @Test
    fun ambiguousIntegerSlotsFailClosed() {
        val ambiguous =
            arrayOf<Class<*>>(
                Int::class.javaPrimitiveType!!,
                String::class.java,
                Int::class.javaPrimitiveType!!,
            )
        assertFalse(ModernTypingPrivacyFeature.isSafeComposingSignature(ambiguous))
        assertNull(ModernTypingPrivacyFeature.composingState(ambiguous, listOf(0, "opaque", 1)))
    }

    @Test
    fun wrongSlotAndMissingStateFailClosed() {
        val wrong = arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, Any::class.java, String::class.java)
        assertFalse(ModernTypingPrivacyFeature.isSafeComposingSignature(wrong))
        assertFalse(
            ModernTypingPrivacyFeature.isSafeComposingSignature(
                arrayOf<Class<*>>(Any::class.java, String::class.java),
            ),
        )
    }
}
