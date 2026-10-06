package com.wax.module

import android.content.Context
import androidx.core.content.edit
import android.content.res.Configuration
import androidx.preference.PreferenceManager
import java.util.Locale

/**
 * The language the module's own interface is shown in.
 *
 * Replaces a single "force English" switch, which could only ever produce two states:
 * English, or whatever the system happened to be set to. With one APK serving readers in
 * twelve languages, the useful question is not "English or not" but "which one", and the
 * default answer has to stay "the system's".
 *
 * The values are BCP 47 tags rather than Android resource qualifiers, because a tag is
 * what [Locale.forLanguageTag] accepts. [SYSTEM] is a sentinel rather than a tag: it means
 * "do not override anything", which is different from choosing the language the system is
 * currently using, because the system can change afterwards and a pinned choice must not
 * follow it.
 *
 * The decision rules are separate functions taking the stored value, so they can be
 * tested without Android. Everything that needs a [Context] is a thin wrapper over them.
 */
object AppLanguage {
    /** Preference key. */
    const val KEY: String = "app_language"

    /** Follow the system language. The default, and the absence of a choice. */
    const val SYSTEM: String = "system"

    /** The key the removed switch wrote, read once to honour what it asked for. */
    private const val LEGACY_FORCE_ENGLISH = "force_english"

    /**
     * The languages the interface is actually translated into, in the order they are shown.
     *
     * Each entry is the language's own name in that language. Naming a language in English
     * is the convention for lists like this, and it is wrong for the person reading it:
     * someone who has set their phone to Arabic is looking for "العربية".
     */
    val supported: List<Language> =
        listOf(
            Language("en", "English"),
            Language("ar", "العربية"),
            Language("de", "Deutsch"),
            Language("es", "Español"),
            Language("fr", "Français"),
            Language("in", "Bahasa Indonesia"),
            Language("it", "Italiano"),
            Language("iw", "עברית"),
            Language("pt", "Português"),
            Language("ru", "Русский"),
            Language("tr", "Türkçe"),
            Language("zh", "中文"),
        )

    private val byTag: Map<String, Language> = supported.associateBy { it.tag }

    /** One selectable language. */
    data class Language(
        val tag: String,
        /** The language's name in that language. */
        val label: String,
    )

    // --- the decision rules, free of Android -----------------------------------

    /**
     * The choice to act on, given what is stored.
     *
     * Anything this build does not offer resolves to [SYSTEM]. Following the system is the
     * only safe answer for an unknown tag: the alternative is an interface with no
     * translation of its own while the setting claims a language it cannot provide.
     */
    fun resolve(stored: String?): String {
        if (stored == null || stored.isEmpty() || stored == SYSTEM) return SYSTEM
        return if (isSupported(stored)) stored else SYSTEM
    }

    /**
     * The choice to act on, including the switch this replaced.
     *
     * Someone who turned on "force English" before this release asked for English. The
     * setting is gone from the interface, but their intent is preserved so upgrading does
     * not silently move their interface to a language they did not choose.
     */
    fun resolveWithLegacy(
        stored: String?,
        legacyFlagPresent: Boolean,
        legacyForcedEnglish: Boolean,
    ): String {
        val resolved = resolve(stored)
        if (resolved != SYSTEM) return resolved
        if (!legacyFlagPresent || !legacyForcedEnglish) return SYSTEM
        return "en"
    }

    /** The locale for a resolved choice. */
    fun localeFor(selected: String): Locale = if (selected == SYSTEM) Locale.getDefault() else Locale.forLanguageTag(selected)

    /** Every tag the picker offers, the system option first. */
    fun entryValues(): Array<String> = arrayOf(SYSTEM) + supported.map { it.tag }

    /** The labels for [entryValues], in the same order. */
    fun entryLabels(systemLabel: String): Array<CharSequence> = arrayOf<CharSequence>(systemLabel) + supported.map { it.label }

    /** Whether [tag] is one this build offers. */
    fun isSupported(tag: String?): Boolean = tag != null && byTag.containsKey(tag)

    // --- the Android-facing wrappers ------------------------------------------

    /** The stored choice, or [SYSTEM] when the user has not chosen. */
    fun selected(context: Context): String = resolve(storedValue(context))

    /** The stored choice, honouring the switch it replaces. */
    fun selectedWithLegacyHonoured(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val resolved = resolve(storedValue(context))
        if (resolved != SYSTEM) return resolved
        val present = prefs.contains(LEGACY_FORCE_ENGLISH)
        val forced = runCatching { prefs.getBoolean(LEGACY_FORCE_ENGLISH, false) }.getOrDefault(false)
        val migrated = resolveWithLegacy(null, present, forced)
        if (migrated != SYSTEM) prefs.edit { putString(KEY, migrated) }
        return migrated
    }

    /** The locale the interface should use for the stored choice. */
    fun localeFor(context: Context): Locale = localeFor(selectedWithLegacyHonoured(context))

    /** Stores a choice and returns the locale it resolves to. */
    fun apply(context: Context): Locale {
        val selected = selected(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        if (selected == SYSTEM) {
            prefs.edit { remove(KEY) }
        } else {
            prefs.edit { putString(KEY, selected) }
        }
        return localeFor(selected)
    }

    /** The configuration the interface should use for [locale]. */
    fun configurationFor(
        context: Context,
        locale: Locale,
    ): Configuration = Configuration(context.resources.configuration).apply { setLocale(locale) }

    private fun storedValue(context: Context): String? = PreferenceManager.getDefaultSharedPreferences(context).getString(KEY, null)
}
