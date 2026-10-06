package com.wax.module.ui.fragments.base

import android.content.SharedPreferences
import android.os.Build
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.jaredrummler.android.colorpicker.ColorPreferenceCompat
import com.wax.module.AppLanguage
import com.wax.module.ModuleApplication
import com.wax.module.R
import rikka.material.preference.MaterialSwitchPreference
import java.util.Locale

internal object PreferenceIconResolver {
    private data class Rule(
        val icon: Int,
        val keywords: Set<String>,
    )

    private val sectionRules =
        listOf(
            Rule(R.drawable.ic_privacy, setOf("privacy", "privacidade")),
            Rule(R.drawable.ic_media, setOf("media", "midia", "video", "image", "download", "audio")),
            Rule(R.drawable.ic_recording, setOf("call", "chamada", "gravacao")),
            Rule(R.drawable.ic_home_black_24dp, setOf("status", "home", "inicio")),
            Rule(R.drawable.ic_dashboard_black_24dp, setOf("custom", "personal", "personalizacao")),
            Rule(R.drawable.ic_general, setOf("conversation", "conversa", "general", "geral")),
        )

    private val preferenceRules =
        listOf(
            Rule(R.drawable.ic_privacy, setOf("privacy", "privacidade", "archive", "ghost", "freeze", "read")),
            Rule(R.drawable.ic_recording, setOf("record", "grav")),
            Rule(
                R.drawable.ic_media,
                setOf("media", "video", "image", "audio", "download", "transcription"),
            ),
            Rule(
                R.drawable.ic_dashboard_black_24dp,
                setOf("color", "theme", "wallpaper", "bubble", "css", "animation"),
            ),
            Rule(R.drawable.ic_home_black_24dp, setOf("status", "home", "inicio")),
        )

    fun sectionIcon(
        title: CharSequence?,
        fallback: Int,
    ): Int = resolve(normalize(title), sectionRules, fallback)

    fun preferenceIcon(
        preference: Preference,
        fallback: Int,
    ): Int {
        val value = "${normalize(preference.key)} ${normalize(preference.title)}"
        return resolve(value, preferenceRules, fallback)
    }

    private fun resolve(
        value: String,
        rules: List<Rule>,
        fallback: Int,
    ): Int =
        rules.firstOrNull { rule -> rule.keywords.any(value::contains) }?.icon ?: fallback

    private fun normalize(value: CharSequence?): String =
        value
            ?.toString()
            ?.lowercase(Locale.ROOT)
            ?.replace('í', 'i')
            ?.replace('ç', 'c')
            ?.replace('ã', 'a')
            ?.replace('á', 'a')
            ?.replace('é', 'e')
            ?.replace('ê', 'e')
            ?.replace('ó', 'o')
            ?.replace('ú', 'u')
            .orEmpty()
}

internal class PreferenceStateController(
    private val fragment: PreferenceFragmentCompat,
    private val prefs: SharedPreferences,
) {
    fun update(changedKey: String?) {
        updateThemeAndColorState(changedKey)
        updateStatusState()
        updatePresenceState()
        updateGroupFilterState()
        updateBootloaderState()
        updateTranscriptionState()
        updateCallPrivacyState()
    }

    private fun updateThemeAndColorState(changedKey: String?) {
        val changeColorEnabled = prefs.getBoolean("changecolor", false)
        val colorMode = prefs.getString("changecolor_mode", "manual")
        val monetAvailable = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val useMonetColors = changeColorEnabled && monetAvailable && colorMode == "monet"

        setEnabled("changecolor_mode", changeColorEnabled && monetAvailable)
        listOf("primary_color", "background_color", "text_color")
            .forEach { setEnabled(it, changeColorEnabled && !useMonetColors) }

        if (changedKey == "thememode") {
            val mode = prefs.getString("thememode", "0")?.toIntOrNull() ?: 0
            ModuleApplication.setThemeMode(mode)
        }

        val appColorMode = prefs.getString("wae_color_mode", "preset")
        val useAppMonet = appColorMode == "monet" && monetAvailable
        setEnabled("wae_color_preset", !useAppMonet)

        if (changedKey in setOf("wae_color_mode", "wae_color_preset", AppLanguage.KEY)) {
            fragment.activity?.recreate()
        }
    }

    private fun updateStatusState() {
        val instagramStatus = prefs.getBoolean("igstatus", false)
        setEnabled("oldstatus", !instagramStatus)

        val oldStatus = prefs.getBoolean("oldstatus", false)
        listOf("verticalstatus", "channels", "removechannel_rec", "status_style", "igstatus")
            .forEach { setEnabled(it, !oldStatus) }

        val channels = prefs.getBoolean("channels", false)
        setEnabled("removechannel_rec", !channels && !oldStatus)
    }

    private fun updatePresenceState() {
        val freezeLastSeen = prefs.getBoolean("freezelastseen", false)
        listOf("show_freezeLastSeen", "showonlinetext", "dotonline")
            .forEach { setEnabled(it, !freezeLastSeen) }
    }

    private fun updateGroupFilterState() {
        val separateGroups = prefs.getBoolean("separategroups", false)
        val filterGroups = prefs.getBoolean("filtergroups", false)
        setEnabled("filtergroups", !separateGroups)
        setEnabled("separategroups", !filterGroups)
    }

    private fun updateBootloaderState() {
        val spooferEnabled = prefs.getBoolean("bootloader_spoofer", false)
        val customKeyBoxEnabled = prefs.getBoolean("bootloader_spoofer_custom", false)
        setEnabled("bootloader_spoofer_custom", spooferEnabled)
        setEnabled("bootloader_spoofer_xml", spooferEnabled && customKeyBoxEnabled)
    }

    private fun updateTranscriptionState() {
        val enabled = prefs.getBoolean("audio_transcription", false)
        val provider = prefs.getString("transcription_provider", "assemblyai")
        setEnabled("transcription_provider", enabled)
        setEnabled("assemblyai_key", enabled && provider == "assemblyai")
        setEnabled("groq_api_key", enabled && provider == "groq")
    }

    private fun updateCallPrivacyState() {
        val mode = prefs.getString("call_privacy", "0")?.toIntOrNull() ?: 0
        setEnabled("call_block_contacts", mode == 3)
        setEnabled("call_white_contacts", mode == 4)
    }

    private fun setEnabled(
        key: String,
        enabled: Boolean,
    ) {
        val preference = fragment.findPreference<Preference>(key) ?: return
        preference.isEnabled = enabled
        if (preference is MaterialSwitchPreference && !enabled) {
            preference.isChecked = false
        }
        if (preference is ColorPreferenceCompat && !enabled) {
            preference.isSelectable = false
        } else if (preference is ColorPreferenceCompat) {
            preference.isSelectable = true
        }
    }
}
