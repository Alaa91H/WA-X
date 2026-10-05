package com.wmods.wppenhacer.xposed.core.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreferenceValueHooksTest {
    private val constant = PreferenceValueHooks.Transform { _, value -> value }

    private fun upper() =
        PreferenceValueHooks.Transform { _, value ->
            (value as? String)?.uppercase()
        }

    private fun suffix(text: String) =
        PreferenceValueHooks.Transform { _, value ->
            (value as? String)?.plus(text)
        }

    @Test
    fun noHooksLeavesTheValueUnchanged() {
        assertEquals("dark", PreferenceValueHooks.applyAll(emptyList(), "thememode", "dark"))
    }

    @Test
    fun aSingleHookReplacesTheValue() {
        assertEquals("DARK", PreferenceValueHooks.applyAll(listOf(upper()), "thememode", "dark"))
    }

    @Test
    fun hooksRunInOrderAndFeedEachOther() {
        val chain = listOf(upper(), suffix("!"))
        assertEquals("DARK!", PreferenceValueHooks.applyAll(chain, "k", "dark"))
    }

    @Test
    fun reversingTheChainChangesTheResult() {
        val forwards = PreferenceValueHooks.applyAll(listOf(upper(), suffix("!")), "k", "dark")
        val backwards = PreferenceValueHooks.applyAll(listOf(suffix("!"), upper()), "k", "dark")
        assertEquals("DARK!", forwards)
        assertEquals("DARK!", backwards)
    }

    @Test
    fun aHookReturningNullRemovesTheValue() {
        val erase = PreferenceValueHooks.Transform { _, _ -> null }
        assertNull(PreferenceValueHooks.applyAll(listOf(erase), "hideread", true))
    }

    @Test
    fun theChainContinuesAfterAHookReturnsNull() {
        val erase = PreferenceValueHooks.Transform { _, _ -> null }
        val replace = PreferenceValueHooks.Transform { _, _ -> "recovered" }
        assertEquals(
            "recovered",
            PreferenceValueHooks.applyAll(listOf(erase, replace), "k", "v"),
        )
    }

    @Test
    fun theKeyIsVisibleToEveryHook() {
        var seen = mutableListOf<String?>()
        val recorder =
            PreferenceValueHooks.Transform { key, _ ->
                seen.add(key)
                null
            }
        PreferenceValueHooks.applyAll(listOf(recorder, recorder), "antirevoke", true)
        assertEquals(listOf("antirevoke", "antirevoke"), seen)
    }

    @Test
    fun aNullKeyIsPassedThrough() {
        var seen: String? = "unset"
        val recorder =
            PreferenceValueHooks.Transform { key, _ ->
                seen = key
                null
            }
        PreferenceValueHooks.applyAll(listOf(recorder), null, true)
        assertNull(seen)
    }

    @Test
    fun anIdentityHookIsTransparent() {
        assertEquals(42, PreferenceValueHooks.applyAll(listOf(constant), "k", 42))
    }

    @Test
    fun aHookMayChangeTheType() {
        val toText = PreferenceValueHooks.Transform { _, value -> value.toString() }
        assertEquals("true", PreferenceValueHooks.applyAll(listOf(toText), "k", true))
    }
}
