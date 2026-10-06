package com.wax.module

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * The language picker's rules.
 *
 * Three things have to hold, and each was a way to get this wrong: the default follows the
 * system, an explicit choice survives a system language change, and a choice this build
 * does not offer falls back instead of showing an untranslated interface with a setting
 * that claims otherwise.
 *
 * Tested as plain functions because they are plain functions. The Android wrappers are
 * preference plumbing over exactly these decisions, and adding a test runner to assert on
 * preference plumbing would test the runner.
 */
class AppLanguageTest {
    // --- default ---------------------------------------------------------------

    @Test
    fun `nothing stored means follow the system`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.resolve(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.resolve(""))
    }

    @Test
    fun `following the system is what an explicit system choice resolves to`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.resolve(AppLanguage.SYSTEM))
        assertEquals(Locale.getDefault(), AppLanguage.localeFor(AppLanguage.SYSTEM))
    }

    // --- an explicit choice -----------------------------------------------------

    @Test
    fun `an explicit choice is kept`() {
        assertEquals("tr", AppLanguage.resolve("tr"))
        assertEquals("tr", AppLanguage.localeFor("tr").language)
    }

    @Test
    fun `a choice survives a later change of the system language`() {
        // The point of the sentinel: "follow the system" reads the system at the moment it
        // is asked, while a pinned choice never looks at the system again.
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("de"))
            assertEquals(Locale.forLanguageTag("de"), AppLanguage.localeFor(AppLanguage.resolve(null)))

            Locale.setDefault(Locale.forLanguageTag("ar"))
            assertEquals("de", AppLanguage.localeFor(AppLanguage.resolve("de")).language)
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `a language this build does not offer follows the system instead`() {
        assertEquals(AppLanguage.SYSTEM, AppLanguage.resolve("xx"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.resolve("pt-BR"))
        assertEquals(Locale.getDefault(), AppLanguage.localeFor(AppLanguage.resolve("xx")))
    }

    // --- the switch this replaces ------------------------------------------------

    @Test
    fun `the old switch still gets the English it asked for`() {
        assertEquals("en", AppLanguage.resolveWithLegacy(null, legacyFlagPresent = true, legacyForcedEnglish = true))
    }

    @Test
    fun `someone who never turned the old switch on is unaffected`() {
        assertEquals(
            AppLanguage.SYSTEM,
            AppLanguage.resolveWithLegacy(null, legacyFlagPresent = false, legacyForcedEnglish = false),
        )
        assertEquals(
            AppLanguage.SYSTEM,
            AppLanguage.resolveWithLegacy(null, legacyFlagPresent = true, legacyForcedEnglish = false),
        )
    }

    @Test
    fun `an explicit choice is not overwritten by the old flag`() {
        assertEquals(
            "de",
            AppLanguage.resolveWithLegacy("de", legacyFlagPresent = true, legacyForcedEnglish = true),
        )
    }

    // --- what is offered ----------------------------------------------------------

    @Test
    fun `english is offered, because it used to be the only other option`() {
        assertEquals(true, AppLanguage.isSupported("en"))
    }

    @Test
    fun `every shipped translation is offered and nothing else is`() {
        // The translations shipped as resource directories. A language offered here with
        // no directory behind it shows an untranslated interface.
        val shipped =
            listOf("en", "ar", "de", "es", "fr", "in", "it", "iw", "pt", "ru", "tr", "zh")
        val offered = AppLanguage.supported.map { it.tag }
        assertEquals(shipped.sorted(), offered.sorted())
    }

    @Test
    fun `the picker starts with the system option`() {
        val values = AppLanguage.entryValues()
        assertEquals(AppLanguage.SYSTEM, values.first())
        assertEquals(AppLanguage.supported.size + 1, values.size)
    }

    @Test
    fun `the labels line up with the values`() {
        val labels = AppLanguage.entryLabels("System default")
        assertEquals(AppLanguage.entryValues().size, labels.size)
        assertEquals("System default", labels.first())
        assertEquals("العربية", labels[AppLanguage.entryValues().indexOf("ar")])
    }

    @Test
    fun `each language is labelled in its own language`() {
        // A list of languages named only in English does not help the person reading it:
        // someone looking for Arabic is looking for the word العربية.
        val labels = AppLanguage.supported.associate { it.tag to it.label }
        assertEquals("العربية", labels["ar"])
        assertEquals("Deutsch", labels["de"])
        assertEquals("עברית", labels["iw"])
        assertEquals("中文", labels["zh"])
    }

    @Test
    fun `no language is offered twice`() {
        val tags = AppLanguage.supported.map { it.tag }
        assertEquals(tags.size, tags.toSet().size)
    }

    @Test
    fun `every offered tag has a label of its own`() {
        for (language in AppLanguage.supported) {
            assertEquals(true, language.label.isNotBlank())
        }
    }

    @Test
    fun `the values are the ones the preference screen stores`() {
        // Guards the one thing that would break silently: a tag the preference framework
        // stores but the locale lookup cannot parse.
        assertArrayEquals(
            listOf("system", "en", "ar", "de", "es", "fr", "in", "it", "iw", "pt", "ru", "tr", "zh").toTypedArray(),
            AppLanguage.entryValues(),
        )
    }
}
