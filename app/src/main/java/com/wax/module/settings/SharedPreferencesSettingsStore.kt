package com.wax.module.settings

import android.content.SharedPreferences
import androidx.core.content.edit
import com.wax.module.platform.TargetApp
import java.util.concurrent.ConcurrentHashMap

/**
 * The production [SettingsStore]: the module's own preference file.
 *
 * This is the same file the hooked process reads through `XSharedPreferences`, which is
 * what makes per-target configuration work at all. If the two sides used different files
 * a setting could be written, shown as saved, and still never reach the feature.
 *
 * Reads are served from an in-memory mirror rather than from `SharedPreferences` on every
 * call. A hook can read the same key hundreds of times while a conversation loads, and
 * the mirrored values are refreshed wholesale on [reload] instead.
 */
class SharedPreferencesSettingsStore(
    private val prefs: SharedPreferences,
) : SettingsStore {
    /** physicalKey -> raw string value. */
    private val mirror = ConcurrentHashMap<String, String>()

    /** physicalKey -> declared type, so a read returns the type the writer used. */
    private val types = ConcurrentHashMap<String, ValueType>()

    init {
        reload()
    }

    /** Re-reads the whole file. Called once at attach and whenever settings change. */
    fun reload() {
        mirror.clear()
        types.clear()
        val all = prefs.all ?: return
        for ((physicalKey, value) in all) {
            if (physicalKey == null || value == null) continue
            mirror[physicalKey] = encode(value)
            types[physicalKey] = ValueType.of(value)
        }
    }

    private fun encode(value: Any): String =
        when (value) {
            is Set<*> -> value.filterNotNull().joinToString(SettingsKeys.SET_SEPARATOR) { it.toString() }
            else -> value.toString()
        }

    private fun readRaw(
        scope: SettingsScope,
        key: String,
    ): Pair<String?, ValueType?> {
        val physicalKey = SettingsKeys.physicalKey(scope, key)
        return mirror[physicalKey] to types[physicalKey]
    }

    private fun writeRaw(
        scope: SettingsScope,
        key: String,
        value: String?,
        type: ValueType?,
    ) {
        val physicalKey = SettingsKeys.physicalKey(scope, key)
        prefs.edit {
            if (value == null) {
                remove(physicalKey)
                mirror.remove(physicalKey)
                types.remove(physicalKey)
            } else {
                // Typed on the way out, not encoded into one string. The hooked process reads
                // these through SharedPreferences, which throws ClassCastException when a key
                // written as a String is read with getBoolean, so the wrong type here would
                // crash WhatsApp rather than mis-set a preference.
                putTyped(this, physicalKey, value, type ?: ValueType.Text)
                mirror[physicalKey] = value
                types[physicalKey] = type ?: ValueType.Text
            }
        }
    }

    private fun putTyped(
        editor: SharedPreferences.Editor,
        physicalKey: String,
        value: String,
        type: ValueType,
    ) {
        when (type) {
            ValueType.Flag -> {
                editor.putBoolean(physicalKey, value.toBooleanStrictOrNull() ?: false)
            }

            ValueType.Whole -> {
                editor.putInt(physicalKey, value.toIntOrNull() ?: 0)
            }

            ValueType.Real -> {
                editor.putFloat(physicalKey, value.toFloatOrNull() ?: 0f)
            }

            ValueType.Set -> {
                editor.putStringSet(
                    physicalKey,
                    value.split(SettingsKeys.SET_SEPARATOR).filter { it.isNotEmpty() }.toSet(),
                )
            }

            ValueType.Text -> {
                editor.putString(physicalKey, value)
            }
        }
    }

    override fun readString(
        scope: SettingsScope,
        key: String,
    ): String? {
        val (raw, type) = readRaw(scope, key)
        return when (type) {
            null -> null

            ValueType.Text, ValueType.Flag -> raw

            // A set read as a string is a type confusion in the caller, not a value: the
            // honest answer is null so the caller's own default applies.
            else -> null
        }
    }

    override fun writeString(
        scope: SettingsScope,
        key: String,
        value: String?,
    ) = writeRaw(scope, key, value, ValueType.Text)

    override fun readBoolean(
        scope: SettingsScope,
        key: String,
    ): Boolean? {
        val (raw, type) = readRaw(scope, key)
        return when (type) {
            ValueType.Flag -> raw?.toBooleanStrictOrNull()

            null -> null

            // A preference stored as the string "true" by an older build still means on.
            else -> raw?.toBooleanStrictOrNull()
        }
    }

    override fun writeBoolean(
        scope: SettingsScope,
        key: String,
        value: Boolean?,
    ) = writeRaw(scope, key, value?.toString(), ValueType.Flag)

    override fun readInt(
        scope: SettingsScope,
        key: String,
    ): Int? = readRaw(scope, key).first?.toIntOrNull()

    override fun writeInt(
        scope: SettingsScope,
        key: String,
        value: Int?,
    ) = writeRaw(scope, key, value?.toString(), ValueType.Whole)

    override fun readFloat(
        scope: SettingsScope,
        key: String,
    ): Float? = readRaw(scope, key).first?.toFloatOrNull()

    override fun writeFloat(
        scope: SettingsScope,
        key: String,
        value: Float?,
    ) = writeRaw(scope, key, value?.toString(), ValueType.Real)

    override fun readStringSet(
        scope: SettingsScope,
        key: String,
    ): Set<String>? {
        val (raw, type) = readRaw(scope, key)
        if (type == null || raw == null) return null
        // Only a value written as a set is a set. Reading a plain string as one hands the
        // caller a single-element set that no feature asked for.
        if (type != ValueType.Set) return null
        return raw.split(SettingsKeys.SET_SEPARATOR).filter { it.isNotEmpty() }.toSet()
    }

    override fun writeStringSet(
        scope: SettingsScope,
        key: String,
        value: Set<String>?,
    ) = writeRaw(scope, key, value?.sorted()?.joinToString(SettingsKeys.SET_SEPARATOR), ValueType.Set)

    override fun keysWithOverrides(scope: SettingsScope): Set<String> {
        val prefix = scope.physicalPrefix()
        if (prefix.isEmpty()) {
            // Global holds every key at its original name, so "the keys in this scope" has
            // to mean the global ones and not every namespaced override as well.
            return mirror.keys.filterNot { SettingsKeys.isOverrideKey(it) }.toSet()
        }
        return mirror.keys
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .toSet()
    }

    override fun clearScope(scope: SettingsScope) {
        val doomed =
            when (scope) {
                is SettingsScope.Global -> {
                    mirror.keys.filterNot { SettingsKeys.isOverrideKey(it) }
                }

                is SettingsScope.Target -> {
                    val prefix = scope.physicalPrefix()
                    mirror.keys.filter { it.startsWith(prefix) }
                }
            }
        if (doomed.isEmpty()) return

        prefs.edit {
            for (key in doomed) {
                remove(key)
                mirror.remove(key)
                types.remove(key)
            }
        }
    }

    override fun copyScope(
        from: SettingsScope,
        to: SettingsScope,
    ) {
        if (from == to) return

        // Snapshot the logical source keys before clearing the destination. In
        // particular Global has an empty physical prefix, so prefix matching would
        // otherwise accidentally include every target override as if it were Global.
        val source =
            keysWithOverrides(from).mapNotNull { logicalKey ->
                val sourceKey = SettingsKeys.physicalKey(from, logicalKey)
                val value = mirror[sourceKey] ?: return@mapNotNull null
                val type = types[sourceKey] ?: ValueType.Text
                Triple(logicalKey, value, type)
            }

        clearScope(to)
        if (source.isEmpty()) return

        prefs.edit {
            for ((logicalKey, value, type) in source) {
                val targetKey = SettingsKeys.physicalKey(to, logicalKey)
                putTyped(this, targetKey, value, type)
                mirror[targetKey] = value
                types[targetKey] = type
            }
        }
    }

    override fun replaceAll(
        global: Map<String, String>,
        targets: Map<TargetApp, Map<String, String>>,
    ) {
        // Everything is staged first, with the type each key is declared with, because a
        // restore that fails half way through would otherwise leave the user with a mix of
        // two configurations.
        //
        // The backup format stores one string per key, so the type has to be re-applied
        // from the registry here. Persisting the raw string instead would turn every
        // boolean, integer, float and set into a String, and the hooked process - which
        // reads these through `SharedPreferences.getBoolean` and friends - would then
        // throw `ClassCastException` in WhatsApp rather than use the restored setting.
        val staged = LinkedHashMap<String, Pair<String, ValueType>>()
        global.forEach { (key, value) ->
            staged[key] = value to ValueType.forKey(key)
        }
        targets.forEach { (target, values) ->
            values.forEach { (key, value) ->
                val physicalKey = SettingsKeys.physicalKey(SettingsScope.Target(target), key)
                staged[physicalKey] = value to ValueType.forKey(key)
            }
        }
        prefs.edit {
            clear()
            staged.forEach { (physicalKey, entry) ->
                putTyped(this, physicalKey, entry.first, entry.second)
            }
        }
        reload()
    }

    /** Every physical key currently held, for backup and for diagnostics. */
    fun physicalKeys(): Set<String> = mirror.keys.toSet()

    /** The value type recorded for a physical key, or null when absent. */
    fun typeOf(physicalKey: String): ValueType? = types[physicalKey]

    private fun SettingsScope.physicalPrefix(): String =
        when (this) {
            is SettingsScope.Global -> ""
            is SettingsScope.Target -> SettingsKeys.TARGET_PREFIX + app.code + "."
        }
}

/**
 * How a stored value should be read back.
 *
 * Recorded because `SharedPreferences` will happily return a `String` for a key written
 * as a boolean, and a preference library that coerces silently is how a setting ends up
 * meaning something other than what the interface showed.
 */
enum class ValueType {
    Text,
    Flag,
    Whole,
    Real,
    Set,
    ;

    companion object {
        fun of(value: Any): ValueType =
            when (value) {
                is Boolean -> Flag
                is Int -> Whole
                is Long -> Whole
                is Float -> Real
                is Set<*> -> Set
                else -> Text
            }

        /**
         * The type the preference screen declares [key] to hold.
         *
         * Read from the generated [SettingKeyRegistry] rather than remembered per write,
         * so a value that arrives as text - from a backup restore, or from a copy between
         * scopes - still lands in `SharedPreferences` under the type the feature reads it
         * with. A key the registry does not know is text, because no feature reads an
         * unknown key through a typed accessor and guessing a type would only invent one.
         */
        fun forKey(key: String): ValueType =
            when (SettingKeyRegistry.find(key)?.kind) {
                SettingKeyRegistry.Kind.BOOLEAN -> Flag
                SettingKeyRegistry.Kind.INT -> Whole
                SettingKeyRegistry.Kind.FLOAT -> Real
                SettingKeyRegistry.Kind.SET -> Set
                SettingKeyRegistry.Kind.TEXT, null -> Text
            }
    }
}
