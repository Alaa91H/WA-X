package com.wax.module.settings

import com.wax.module.platform.TargetApp

/**
 * A tri-state control over one setting for one scope.
 *
 * The three states are what makes one APK able to hold two independently configured
 * targets. A plain boolean cannot express "this target should do whatever Global
 * says", so a boolean-per-target model forces the UI to duplicate the Global control
 * and to guess on every read whether the two are meant to be linked.
 */
enum class TriState {
    /** Use whatever Global says. */
    INHERIT,

    /** Force on for this scope. */
    ENABLED,

    /** Force off for this scope. */
    DISABLED,
    ;

    /** The effective boolean when this state is combined with a global default. */
    fun resolve(global: Boolean): Boolean =
        when (this) {
            INHERIT -> global
            ENABLED -> true
            DISABLED -> false
        }

    val isOverride: Boolean get() = this != INHERIT

    companion object {
        /**
         * The state that produces [value], given the global default.
         *
         * Used when the user picks a concrete state, not when reading one back. An
         * explicit choice that happens to match the global value is still stored as an
         * explicit choice: reporting it as INHERIT would make the target silently follow
         * the next global change the user makes, which is not what choosing a value
         * means.
         */
        fun fromValue(
            value: Boolean,
            global: Boolean,
        ): TriState = if (value) ENABLED else DISABLED
    }
}

/**
 * A generic override.
 *
 * Booleans are the common case and get [TriState], but most of WA X's settings are
 * strings, integers or string sets: a status style, an audio type, a call-recording
 * mode, a wall-paper alpha, a filter list. A boolean-only model would leave those
 * either globally shared with no way to diverge, or duplicated per target with no
 * way to say "same as Global". [OverrideValue] covers both without a second code
 * path.
 */
sealed interface OverrideValue<out T> {
    /** No opinion here; the global value applies. */
    data object Inherit : OverrideValue<Nothing>

    /** A concrete value for this scope. */
    data class Value<out T>(
        val value: T,
    ) : OverrideValue<T>

    /** The override value, or null when this scope inherits. */
    fun valueOrNull(): T? = (this as? Value<T>)?.value

    val isOverride: Boolean get() = this is Value<*>

    /**
     * Resolves against the global value.
     *
     * Declared on the companion rather than as a member because `T` is `out`: a member
     * taking a `T` would put it in an `in` position.
     */
    companion object {
        fun <T> resolve(
            override: OverrideValue<T>,
            global: T,
        ): T =
            when (override) {
                is OverrideValue.Inherit -> global
                is OverrideValue.Value -> override.value
            }
    }
}

/** One key in the settings surface, identified by the legacy preference key. */
data class FeatureKey(
    val key: String,
    val category: String,
    val title: String,
    val summary: String = "",
    /** Feature ids this setting drives, for search and favourites. */
    val featureIds: List<String> = emptyList(),
    /** Extra terms search should match, for older or colloquial wording. */
    val searchAliases: List<String> = emptyList(),
    /** Whether the setting can carry a per-target override. */
    val targetOverrideable: Boolean = true,
    /** Whether the setting affects the module itself rather than WhatsApp. */
    val moduleOnly: Boolean = false,
    /** Whether the setting may increase account risk or break the build. */
    val risky: Boolean = false,
    /** Whether a change needs the target restarted to take effect. */
    val restartRequired: Boolean = false,
) {
    /** The fully qualified key used in storage: `global.<key>` / `whatsapp.<key>`. */
    fun storageKey(scope: SettingsScope): String = "${scope.code}.$key"
}

/**
 * Where a value is being read or written.
 *
 * [GLOBAL] holds concrete defaults. A [TargetApp] scope holds overrides that either
 * inherit or diverge. This is the distinction that lets a single APK keep the two
 * WhatsApp builds independent.
 */
sealed interface SettingsScope {
    /** The stable short code used in persisted keys and in logs. */
    val code: String

    /** A concrete default, shared by every target that does not override it. */
    data object Global : SettingsScope {
        const val CODE: String = "global"
        override val code: String get() = CODE
    }

    /** An override for one WhatsApp target. */
    data class Target(
        val app: TargetApp,
    ) : SettingsScope {
        override val code: String get() = app.code
    }

    companion object {
        /** The scope for a hooked process, or null when the process is not a target. */
        fun forPackage(packageName: String?): SettingsScope.Target? = TargetApp.fromPackageName(packageName)?.let { Target(it) }

        /** The scope matching a persisted code such as "business". */
        fun fromCode(code: String?): SettingsScope? =
            when (code) {
                Global.code -> Global
                else -> TargetApp.fromCode(code)?.let { Target(it) }
            }
    }
}
