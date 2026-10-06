package com.wax.module.ui.targets

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.preference.PreferenceManager
import com.wax.module.BuildConfig
import com.wax.module.platform.TargetApp
import com.wax.module.settings.EffectiveSettingsResolver
import com.wax.module.settings.SettingKeyRegistry
import com.wax.module.settings.SettingsKeys
import com.wax.module.settings.SettingsScope
import com.wax.module.settings.SettingsStore
import com.wax.module.settings.SharedPreferencesSettingsStore
import com.wax.module.settings.TriState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * State for the per-target settings screen.
 *
 * The screen shows one scope at a time. Global is the default for both targets, so it is
 * presented as an ordinary value; a target is presented as a tri-state, because the
 * meaningful difference there is not on or off but inherited, on or off.
 */
class TargetSettingsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val prefs = PreferenceManager.getDefaultSharedPreferences(application)

    private val store: SettingsStore = SharedPreferencesSettingsStore(prefs)
    private val resolver = EffectiveSettingsResolver(store)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { reload() }
    }

    private fun reload() {
        (store as? SharedPreferencesSettingsStore)?.reload()
        val scope = _state.value.scope
        val overrideable = SettingKeyRegistry.entries
        val rows =
            overrideable.map { entry ->
                Row(
                    entry = entry,
                    triState = resolver.triState(entry.key, scope),
                    effective = resolver.effectiveString(entry.key, scope),
                )
            }
        _state.value =
            _state.value.copy(
                rows = rows,
                overrideCount = store.keysWithOverrides(scope).size,
                totalOverrideCount = TargetApp.entries.sumOf { app -> store.keysWithOverrides(SettingsScope.Target(app)).size },
            )
    }

    /** Re-reads storage, for the refresh action in the app bar. */
    fun reloadFromUi() = reload()

    /** Switches the scope being edited. */
    fun selectScope(scope: SettingsScope) {
        _state.value = _state.value.copy(scope = scope)
        reload()
    }

    /** Filters the visible rows by title, summary or key. */
    fun search(query: String) {
        _state.value = _state.value.copy(query = query)
        reload()
    }

    /**
     * Applies a tri-state choice.
     *
     * INHERIT removes the stored override. It never writes the global value into the
     * target: that would make the setting stop following Global, which is the one thing
     * "Use Global" has to mean.
     */
    fun setTriState(
        entry: SettingKeyRegistry.Entry,
        state: TriState,
    ) {
        val scope = _state.value.scope
        if (scope !is SettingsScope.Target) return
        when (state) {
            TriState.INHERIT -> store.writeBoolean(scope, entry.key, null)
            TriState.ENABLED -> store.writeBoolean(scope, entry.key, true)
            TriState.DISABLED -> store.writeBoolean(scope, entry.key, false)
        }
        notifyRuntime(scope)
        reload()
    }

    /**
     * Pins the current Global value onto the target.
     *
     * For a setting that is not a plain toggle there is nothing to choose here, so the
     * choice is between following Global and holding the value it has today.
     */
    fun pinGlobalValue(entry: SettingKeyRegistry.Entry) {
        val scope = _state.value.scope
        if (scope !is SettingsScope.Target) return
        val global = SettingsScope.Global
        when (entry.kind) {
            SettingKeyRegistry.Kind.BOOLEAN ->
                store.writeBoolean(scope, entry.key, resolver.effectiveBoolean(entry.key, global))

            SettingKeyRegistry.Kind.INT ->
                store.writeInt(scope, entry.key, resolver.effectiveInt(entry.key, global))

            SettingKeyRegistry.Kind.FLOAT ->
                store.writeFloat(scope, entry.key, resolver.effectiveFloat(entry.key, global))

            SettingKeyRegistry.Kind.SET ->
                store.writeStringSet(scope, entry.key, resolver.effectiveStringSet(entry.key, global))

            SettingKeyRegistry.Kind.TEXT ->
                store.writeString(scope, entry.key, resolver.effectiveString(entry.key, global))
        }
        notifyRuntime(scope)
        reload()
    }

    /**
     * Edits the Global value, which is what every inheriting target follows.
     *
     * Only toggles are editable here. A list or a text setting has its own editor on its
     * own screen, and pretending a boolean can write one of those would store a value the
     * original screen cannot read back.
     */
    fun setGlobalToggle(
        entry: SettingKeyRegistry.Entry,
        enabled: Boolean,
    ) {
        if (entry.kind != SettingKeyRegistry.Kind.BOOLEAN) return
        store.writeBoolean(SettingsScope.Global, entry.key, enabled)
        notifyRuntime(SettingsScope.Global)
        reload()
    }

    /** Removes every override on the current target, leaving Global alone. */
    fun resetTarget() {
        val scope = _state.value.scope
        if (scope !is SettingsScope.Target) return
        store.clearScope(scope)
        notifyRuntime(scope)
        reload()
    }

    /** Copies Global onto the current target, turning every setting into an explicit one. */
    fun copyGlobalToTarget() {
        val scope = _state.value.scope
        if (scope !is SettingsScope.Target) return
        store.copyScope(SettingsScope.Global, scope)
        notifyRuntime(scope)
        reload()
    }

    /** Removes every override on every target. */
    fun resetEverything() {
        for (app in TargetApp.entries) {
            store.clearScope(SettingsScope.Target(app))
        }
        notifyRuntime(SettingsScope.Global)
        reload()
    }

    /**
     * Uses the same restart signal as the ordinary preference screens.
     *
     * A target-only change is addressed to that WhatsApp package so Business is not
     * asked to restart for a WhatsApp-only override. Global changes are broadcast to
     * both targets because both inherit them.
     */
    private fun notifyRuntime(scope: SettingsScope) {
        val intent = Intent("${BuildConfig.APPLICATION_ID}.MANUAL_RESTART")
        if (scope is SettingsScope.Target) {
            intent.setPackage(scope.app.packageName)
        }
        getApplication<Application>().sendBroadcast(intent)
    }

    /** The physical key an override occupies, for the interface's diagnostics line. */
    fun physicalKeyOf(entry: SettingKeyRegistry.Entry): String = SettingsKeys.physicalKey(_state.value.scope, entry.key)

    /** One row of the list. */
    data class Row(
        val entry: SettingKeyRegistry.Entry,
        val triState: TriState,
        /** The value this scope resolves to, as text, for the summary line. */
        val effective: String?,
    )

    /** Everything the screen renders. */
    data class UiState(
        val scope: SettingsScope = SettingsScope.Global,
        val query: String = "",
        val rows: List<Row> = emptyList(),
        val overrideCount: Int = 0,
        val totalOverrideCount: Int = 0,
    )
}
