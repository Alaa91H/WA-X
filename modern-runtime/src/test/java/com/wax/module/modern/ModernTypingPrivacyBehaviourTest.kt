package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a stored preference becomes a reported behaviour (#450).
 *
 * The rule under test is the one the issue insists on: a hook being installed,
 * and a switch being on, are not evidence that the behaviour is in force. Only
 * both together are, and otherwise the state has to say so.
 */
class ModernTypingPrivacyBehaviourTest {
    private class FakePreferences(
        private val values: Map<String, Boolean>,
    ) : android.content.SharedPreferences {
        override fun getAll(): MutableMap<String, *> = HashMap<String, Any>(values)

        override fun getString(
            key: String,
            defValue: String?,
        ): String? = defValue

        override fun getStringSet(
            key: String,
            defValues: MutableSet<String>?,
        ): MutableSet<String>? = defValues

        override fun getInt(
            key: String,
            defValue: Int,
        ): Int = defValue

        override fun getLong(
            key: String,
            defValue: Long,
        ): Long = defValue

        override fun getFloat(
            key: String,
            defValue: Float,
        ): Float = defValue

        override fun getBoolean(
            key: String,
            defValue: Boolean,
        ): Boolean = values[key] ?: defValue

        override fun contains(key: String): Boolean = values.containsKey(key)

        override fun edit(): android.content.SharedPreferences.Editor = throw UnsupportedOperationException("read-only fixture")

        override fun registerOnSharedPreferenceChangeListener(
            listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
        }

        override fun unregisterOnSharedPreferenceChangeListener(
            listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener?,
        ) {
        }
    }

    @Test fun anInstalledHookWithTheSwitchOnIsTheOnlyActiveState() {
        val state =
            ModernTypingPrivacyFeature.behaviourState(
                FakePreferences(mapOf("ghostmode_t" to true)),
                ModernTypingPrivacyFeature.Outcome.INSTALLED,
            )
        assertTrue(state.typingRequested)
        assertTrue(state.typingWithheld)
        assertTrue(
            state.isBehaviourActive(state.typingRequested, state.typingWithheld),
        )
    }

    @Test fun aStoredSwitchWithoutAnInstalledHookIsNotEvidence() {
        val state =
            ModernTypingPrivacyFeature.behaviourState(
                FakePreferences(mapOf("ghostmode_t" to true)),
                ModernTypingPrivacyFeature.Outcome.RESOLVER_MISSING,
            )
        assertTrue("the preference is still requested", state.typingRequested)
        assertFalse(
            "a switch that was stored is not a behaviour that is in force",
            state.typingWithheld,
        )
        assertFalse(state.isBehaviourActive(state.typingRequested, state.typingWithheld))
    }

    @Test fun typingAndRecordingAreReportedSeparately() {
        val state =
            ModernTypingPrivacyFeature.behaviourState(
                FakePreferences(mapOf("ghostmode_r" to true)),
                ModernTypingPrivacyFeature.Outcome.INSTALLED,
            )
        assertTrue(state.recordingRequested)
        assertTrue(state.recordingWithheld)
        assertFalse("recording alone must not imply typing", state.typingWithheld)
        assertFalse(state.typingRequested)
    }

    @Test fun theLegacyGlobalCoversBothBehaviours() {
        val state =
            ModernTypingPrivacyFeature.behaviourState(
                FakePreferences(mapOf("ghostmode" to true)),
                ModernTypingPrivacyFeature.Outcome.INSTALLED,
            )
        assertTrue(state.typingWithheld)
        assertTrue(state.recordingWithheld)
    }

    @Test fun nothingIsWithheldWhenNothingIsRequested() {
        val state =
            ModernTypingPrivacyFeature.behaviourState(
                FakePreferences(emptyMap()),
                ModernTypingPrivacyFeature.Outcome.DISABLED,
            )
        assertFalse(state.typingWithheld)
        assertFalse(state.recordingWithheld)
    }

    @Test fun theSuppressionRuleIsUnchangedByThePort() {
        // Typing is suppressed only by the typing rule, recording only by the
        // recording rule; this is the legacy semantics and must not drift.
        assertTrue(ModernTypingPrivacyFeature.shouldSuppress(0, true, false))
        assertFalse(ModernTypingPrivacyFeature.shouldSuppress(0, false, true))
        assertTrue(ModernTypingPrivacyFeature.shouldSuppress(1, false, true))
        assertFalse(ModernTypingPrivacyFeature.shouldSuppress(1, true, false))
        assertFalse(ModernTypingPrivacyFeature.shouldSuppress(0, false, false))
        assertEquals(0, ModernTypingPrivacyFeature.STATE_TYPING)
        assertEquals(1, ModernTypingPrivacyFeature.STATE_RECORDING)
    }
}
