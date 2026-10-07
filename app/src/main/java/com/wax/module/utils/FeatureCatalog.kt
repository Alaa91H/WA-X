package com.wax.module.utils

import android.content.Context
import androidx.annotation.StringRes
import com.wax.module.AppLanguage
import com.wax.module.R
import com.wax.module.model.SearchableFeature
import com.wax.module.settings.SettingKeyRegistry

/** Search catalog derived from the same preference registry that powers target settings. */
object FeatureCatalog {
    private data class Route(
        val category: SearchableFeature.Category,
        val fragment: SearchableFeature.FragmentType,
        val parentKey: String? = null,
    )

    private data class ExtraEntry(
        val key: String,
        @field:StringRes val titleRes: Int,
        @field:StringRes val summaryRes: Int? = null,
        val screen: String,
        val tags: List<String> = emptyList(),
    )

    private data class HomeAction(
        val key: String,
        @field:StringRes val titleRes: Int,
        @field:StringRes val summaryRes: Int? = null,
        val tags: List<String>,
    )

    @Volatile
    private var cachedLocaleTag: String? = null

    @Volatile
    private var cachedFeatures: List<SearchableFeature> = emptyList()

    @JvmStatic
    fun getAllFeatures(context: Context): List<SearchableFeature> {
        val localeTag = currentLocaleTag(context)
        val existing = cachedFeatures
        if (existing.isNotEmpty() && cachedLocaleTag == localeTag) return existing

        return synchronized(this) {
            if (cachedFeatures.isEmpty() || cachedLocaleTag != localeTag) {
                cachedFeatures = buildFeatureCatalog(context.applicationContext)
                cachedLocaleTag = localeTag
            }
            cachedFeatures
        }
    }

    @JvmStatic
    fun search(
        context: Context,
        query: String?,
    ): List<SearchableFeature> {
        if (query.isNullOrBlank()) return emptyList()
        return getAllFeatures(context).filter { it.matches(query) }
    }

    private fun buildFeatureCatalog(context: Context): List<SearchableFeature> =
        buildList {
            SettingKeyRegistry.entries
                .mapNotNull { entry ->
                    val route = ROUTES[entry.screen] ?: return@mapNotNull null
                    SearchableFeature(
                        entry.key,
                        context.getString(entry.titleRes),
                        null,
                        route.category,
                        route.fragment,
                        route.parentKey,
                        keyTags(entry.key),
                    )
                }.forEach(::add)

            EXTRA_ENTRIES
                .mapNotNull { extra -> extra.toSearchableFeature(context) }
                .forEach(::add)

            HOME_ACTIONS
                .map { action -> action.toSearchableFeature(context) }
                .forEach(::add)
        }.distinctBy(SearchableFeature::key)

    private fun ExtraEntry.toSearchableFeature(context: Context): SearchableFeature? {
        val route = ROUTES[screen] ?: return null
        return SearchableFeature(
            key,
            context.getString(titleRes),
            summaryRes?.let(context::getString),
            route.category,
            route.fragment,
            route.parentKey,
            (keyTags(key) + tags).distinct(),
        )
    }

    private fun HomeAction.toSearchableFeature(context: Context): SearchableFeature =
        SearchableFeature(
            key,
            context.getString(titleRes),
            summaryRes?.let(context::getString),
            SearchableFeature.Category.HOME_ACTIONS,
            SearchableFeature.FragmentType.HOME,
            null,
            (keyTags(key) + tags).distinct(),
        )

    private fun keyTags(key: String): List<String> =
        key
            .split('_', '-')
            .filter(String::isNotBlank)
            .distinct()

    private fun currentLocaleTag(context: Context): String =
        context.resources.configuration.locales[0]
            .toLanguageTag()

    private val ROUTES: Map<String, Route> =
        mapOf(
            "fragment_general" to
                Route(
                    SearchableFeature.Category.GENERAL,
                    SearchableFeature.FragmentType.GENERAL,
                ),
            "preference_general_home" to
                Route(
                    SearchableFeature.Category.GENERAL_HOME,
                    SearchableFeature.FragmentType.GENERAL,
                    "general_home",
                ),
            "preference_general_homescreen" to
                Route(
                    SearchableFeature.Category.GENERAL_HOMESCREEN,
                    SearchableFeature.FragmentType.GENERAL,
                    "homescreen",
                ),
            "preference_general_conversation" to
                Route(
                    SearchableFeature.Category.GENERAL_CONVERSATION,
                    SearchableFeature.FragmentType.GENERAL,
                    "conversation",
                ),
            "fragment_privacy" to
                Route(
                    SearchableFeature.Category.PRIVACY,
                    SearchableFeature.FragmentType.PRIVACY,
                ),
            "fragment_media" to
                Route(
                    SearchableFeature.Category.MEDIA,
                    SearchableFeature.FragmentType.MEDIA,
                ),
            "fragment_customization" to
                Route(
                    SearchableFeature.Category.CUSTOMIZATION,
                    SearchableFeature.FragmentType.CUSTOMIZATION,
                ),
        )

    private val EXTRA_ENTRIES: List<ExtraEntry> =
        listOf(
            ExtraEntry("wallpaper_file", R.string.select_wallpaper, R.string.wallpaper_not_selected, "fragment_customization"),
            ExtraEntry("css_theme", R.string.custom_theme_css, R.string.custom_theme_css_sum, "fragment_customization"),
            ExtraEntry(
                "per_target_settings",
                R.string.open_per_target_settings,
                R.string.open_per_target_settings_summary,
                "fragment_general",
                listOf("whatsapp", "business", "global", "target"),
            ),
            ExtraEntry("call_recording_path", R.string.call_recording_path, null, "fragment_media"),
            ExtraEntry(
                "call_recording_settings",
                R.string.call_recording_settings,
                R.string.recording_mode_description,
                "fragment_media",
            ),
            ExtraEntry(
                "transcription_provider",
                R.string.transcription_provider,
                R.string.transcription_provider_sum,
                "fragment_media",
            ),
            ExtraEntry("assemblyai_key", R.string.assemblyai_key, R.string.assemblyai_key_sum, "fragment_media"),
            ExtraEntry("groq_api_key", R.string.groq_api_key, R.string.groq_api_key_sum, "fragment_media"),
            ExtraEntry("thememode", R.string.theme_mode, null, "preference_general_home", listOf("dark", "light")),
            ExtraEntry("wae_color_mode", R.string.wae_color_mode, null, "preference_general_home", listOf("monet", "color")),
            ExtraEntry("wae_color_preset", R.string.wae_color_preset, null, "preference_general_home", listOf("color")),
            ExtraEntry("update_check", R.string.update_check, R.string.update_check_sum, "preference_general_home"),
            ExtraEntry(
                AppLanguage.KEY,
                R.string.app_language,
                null,
                "preference_general_home",
                listOf("language", "locale", "system"),
            ),
            ExtraEntry("enablelogs", R.string.verbose_logs, null, "preference_general_home", listOf("debug", "logs")),
            ExtraEntry(
                "bootloader_spoofer",
                R.string.bootloader_spoofer,
                R.string.bootloader_spoofer_sum,
                "preference_general_home",
            ),
            ExtraEntry(
                "bootloader_spoofer_custom",
                R.string.enable_custom_keybox,
                R.string.enable_custom_keybox_sum,
                "preference_general_home",
                listOf("keybox"),
            ),
            ExtraEntry(
                "bootloader_spoofer_xml",
                R.string.use_custom_keybox_file,
                R.string.no_file_selected,
                "preference_general_home",
                listOf("keybox", "xml"),
            ),
            ExtraEntry(
                "tasker_auth_token",
                R.string.tasker_auth_token,
                R.string.tasker_auth_token_sum,
                "preference_general_home",
                listOf("tasker", "token", "automation"),
            ),
            ExtraEntry(
                "restartbutton",
                R.string.enable_restart_button,
                R.string.enable_restart_button_sum,
                "preference_general_homescreen",
            ),
            ExtraEntry(
                "open_wae",
                R.string.enable_wax_button,
                R.string.enable_wax_button_sum,
                "preference_general_homescreen",
                listOf("wa", "wax", "button"),
            ),
        )

    private val HOME_ACTIONS: List<HomeAction> =
        listOf(
            HomeAction(
                "export_config",
                R.string.export_settings,
                R.string.backup_settings,
                listOf("export", "backup", "settings", "config"),
            ),
            HomeAction(
                "import_config",
                R.string.import_settings,
                R.string.backup_settings,
                listOf("import", "restore", "settings", "config"),
            ),
            HomeAction(
                "reset_config",
                R.string.reset_settings,
                null,
                listOf("reset", "settings", "clear"),
            ),
            HomeAction(
                "reboot_wpp",
                R.string.restart_whatsapp,
                null,
                listOf("restart", "reboot", "whatsapp", "refresh"),
            ),
        )
}
