package com.wax.module.settings

import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import com.wax.module.platform.TargetApp

/**
 * The preferences object every feature already reads, wrapped so that a read returns
 * the value for the target this process is hooked into.
 *
 * This is the whole reason per-target settings are cheap. Several hundred call sites do
 * `Utils.xprefs.getBoolean("somekey", false)`, and `Utils.xprefs` is a
 * `SharedPreferences`. Replacing it with a decorator changes every one of those reads
 * without touching any of them, and without the risk of having rewritten a few of them
 * and missed the rest.
 *
 * The rule is the one from [EffectiveSettingsResolver], expressed for the read path:
 *
 *  * an override stored for this target wins;
 *  * otherwise the Global value passes straight through, untouched;
 *  * a key that is not a WA X key is never touched at all.
 *
 * That last point is what keeps this safe. The decorator also sees WhatsApp's own
 * preference reads, because it sits under the whole file. It refuses to answer for
 * anything it does not own, so a WhatsApp preference whose name happens to resemble one
 * of ours still reads WhatsApp's value.
 */
class TargetScopedPreferences(
    private val delegate: SharedPreferences,
    private val target: TargetApp,
) : SharedPreferences {
    /** Overrides for this target, keyed by the preference key they override. */
    @Volatile
    private var overrides: Map<String, Any?> = emptyMap()

    /**
     * Rebuilds the override map from storage.
     *
     * Called once when the process attaches, and again whenever the manager writes, so a
     * change made in the module takes effect without restarting WhatsApp.
     */
    fun refresh(store: SharedPreferencesSettingsStore) {
        val prefix = SettingsKeys.TARGET_PREFIX + target.code + "."
        val all = delegate.all ?: emptyMap()
        val rebuilt = HashMap<String, Any?>()
        for ((physicalKey, value) in all) {
            if (physicalKey == null || value == null) continue
            if (!physicalKey.startsWith(prefix)) continue
            // Decoded through the store so a set or a flag comes back as the type the
            // writer used, not as the string the raw value happens to look like.
            val key = physicalKey.removePrefix(prefix)
            if (SettingKeyRegistry.find(key) == null) continue
            rebuilt[key] = decode(store, physicalKey, key, value)
        }
        overrides = rebuilt
        count = rebuilt.size
    }

    /** How many overrides this process is currently applying. */
    @Volatile
    var count: Int = 0
        private set

    /** The unwrapped file, for a refresh that has to re-read storage. */
    val rawDelegate: SharedPreferences get() = delegate

    /**
     * The override for [key] for any type, for the WhatsApp preference read hook.
     *
     * Returns null when the key is not one of ours, which is what keeps this from
     * touching WhatsApp's own preferences.
     */
    fun overrideForHook(
        key: String?,
        current: Any?,
    ): Any? {
        if (key == null || overrides.isEmpty()) return current
        if (!overrides.containsKey(key)) return current
        val value = overrides[key] ?: return current
        if (current == null) return value

        // Match the exact type the hooked preference read expects. Older builds could
        // leave target overrides as strings after a scope copy, so recover those values
        // rather than handing a String to a Boolean/Int hook or silently ignoring it.
        return when (current) {
            is Boolean ->
                when (value) {
                    is Boolean -> value
                    is String -> value.toBooleanStrictOrNull() ?: current
                    else -> current
                }

            is Int ->
                when (value) {
                    is Number -> value.toInt()
                    is String -> value.toIntOrNull() ?: current
                    else -> current
                }

            is Long ->
                when (value) {
                    is Number -> value.toLong()
                    is String -> value.toLongOrNull() ?: current
                    else -> current
                }

            is Float ->
                when (value) {
                    is Number -> value.toFloat()
                    is String -> value.toFloatOrNull() ?: current
                    else -> current
                }

            is String -> value as? String ?: current
            is Set<*> -> value as? Set<*> ?: current
            else -> if (current::class.java.isInstance(value)) value else current
        }
    }

    private fun decode(
        store: SharedPreferencesSettingsStore,
        physicalKey: String,
        key: String,
        raw: Any,
    ): Any? {
        val scope = SettingsScope.Target(target)
        val declaredKind = SettingKeyRegistry.find(key)?.kind

        // The XML contract is authoritative for WA X settings. It also repairs values
        // written by older builds with the wrong SharedPreferences type.
        return when (declaredKind) {
            SettingKeyRegistry.Kind.BOOLEAN -> store.readBoolean(scope, key)
            SettingKeyRegistry.Kind.INT -> store.readInt(scope, key)
            SettingKeyRegistry.Kind.FLOAT -> store.readFloat(scope, key)
            SettingKeyRegistry.Kind.SET ->
                store.readStringSet(scope, key)
                    ?: store.readString(scope, key)
                        ?.split(LEGACY_SET_SEPARATOR)
                        ?.filter { it.isNotEmpty() }
                        ?.toSet()

            SettingKeyRegistry.Kind.TEXT -> store.readString(scope, key)
            null ->
                when (store.typeOf(physicalKey) ?: ValueType.of(raw)) {
                    ValueType.Flag -> store.readBoolean(scope, key)
                    ValueType.Whole -> store.readInt(scope, key)
                    ValueType.Real -> store.readFloat(scope, key)
                    ValueType.Set -> store.readStringSet(scope, key)
                    ValueType.Text -> raw.toString()
                }
        }
    }

    /**
     * The stored override for [key], when it is a [wanted] type.
     *
     * The type check is written as a predicate rather than `Class.isInstance` because
     * `Boolean::class.java` is the primitive `boolean.class` in Kotlin, and a primitive
     * class is never an instance of the boxed value a preference map actually holds. That
     * made every override silently invisible, which is why it is spelled out here.
     *
     * A type the caller did not ask for is not an override for that caller: returning null
     * makes the caller fall through to the Global value instead of throwing.
     */
    private inline fun override(
        key: String?,
        wanted: (Any) -> Boolean,
    ): Any? {
        if (key == null) return null
        if (!overrides.containsKey(key)) return null
        val value = overrides[key] ?: return null
        return if (wanted(value)) value else null
    }

    override fun getAll(): MutableMap<String?, *>? {
        val all = delegate.all ?: return null
        val merged = HashMap<String?, Any?>(all)

        // Physical target namespaces are an implementation detail. Exposing them through
        // getAll() lets a feature iterating preferences see the other app's overrides.
        merged.keys.removeAll { key -> key != null && SettingsKeys.isOverrideKey(key) }

        for ((key, value) in overrides) {
            if (value == null) merged.remove(key) else merged[key] = value
        }
        return merged
    }

    override fun getString(
        s: String?,
        s1: String?,
    ): String? = override(s) { it is String } as String? ?: delegate.getString(s, s1)

    override fun getStringSet(
        s: String?,
        set: MutableSet<String?>?,
    ): MutableSet<String?>? {
        @Suppress("UNCHECKED_CAST")
        val value = override(s) { it is Set<*> } as Set<String>?
        return if (value != null) value.toMutableSet() as MutableSet<String?> else delegate.getStringSet(s, set)
    }

    override fun getInt(
        s: String?,
        i: Int,
    ): Int {
        val value = override(s) { it is Number }
        return when (value) {
            is Int -> value
            is Number -> value.toInt()
            else -> delegate.getInt(s, i)
        }
    }

    override fun getLong(
        s: String?,
        l: Long,
    ): Long {
        val value = override(s) { it is Long } ?: override(s) { it is Int }
        return when (value) {
            is Long -> value
            is Int -> value.toLong()
            else -> delegate.getLong(s, l)
        }
    }

    override fun getFloat(
        s: String?,
        v: Float,
    ): Float {
        val value = override(s) { it is Float } ?: override(s) { it is Int }
        return when (value) {
            is Float -> value
            is Int -> value.toFloat()
            else -> delegate.getFloat(s, v)
        }
    }

    override fun getBoolean(
        s: String?,
        b: Boolean,
    ): Boolean {
        val value = override(s) { it is Boolean }
        return if (value is Boolean) value else delegate.getBoolean(s, b)
    }

    override fun contains(s: String?): Boolean = (s != null && overrides.containsKey(s) && overrides[s] != null) || delegate.contains(s)

    /**
     * Writes go to Global, never to the target.
     *
     * A feature that writes a preference is configuring the module, and the value it
     * intends is the default for every target that has not diverged. Letting a write land
     * on the target scope would silently pin that one target the next time the feature
     * ran, which is how "my setting keeps coming back" happens.
     */
    override fun edit(): SharedPreferences.Editor = GlobalOnlyEditor(delegate.edit())

    override fun registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener: OnSharedPreferenceChangeListener?) =
        delegate.registerOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener)

    override fun unregisterOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener: OnSharedPreferenceChangeListener?) =
        delegate.unregisterOnSharedPreferenceChangeListener(onSharedPreferenceChangeListener)

    /**
     * Forwards writes to the delegate, and refuses any key in the target namespace.
     *
     * Refused rather than forwarded silently: a feature writing
     * `waxtarget.business.x` would otherwise appear to work and quietly change the other
     * build's configuration from inside this process.
     */
    private class GlobalOnlyEditor(
        private val delegate: SharedPreferences.Editor,
    ) : SharedPreferences.Editor {
        override fun putString(
            key: String?,
            value: String?,
        ): SharedPreferences.Editor = apply { safe(key)?.let { delegate.putString(it, value) } }

        override fun putStringSet(
            key: String?,
            values: MutableSet<String?>?,
        ): SharedPreferences.Editor = apply { safe(key)?.let { delegate.putStringSet(it, values) } }

        override fun putInt(
            key: String?,
            value: Int,
        ): SharedPreferences.Editor = apply { safe(key)?.let { delegate.putInt(it, value) } }

        override fun putLong(
            key: String?,
            value: Long,
        ): SharedPreferences.Editor = apply { safe(key)?.let { delegate.putLong(it, value) } }

        override fun putFloat(
            key: String?,
            value: Float,
        ): SharedPreferences.Editor = apply { safe(key)?.let { delegate.putFloat(it, value) } }

        override fun putBoolean(
            key: String?,
            value: Boolean,
        ): SharedPreferences.Editor = apply { safe(key)?.let { delegate.putBoolean(it, value) } }

        override fun remove(key: String?): SharedPreferences.Editor = apply { safe(key)?.let { delegate.remove(it) } }

        override fun clear(): SharedPreferences.Editor = apply(delegate.clear())

        override fun commit(): Boolean = delegate.commit()

        override fun apply() {
            delegate.apply()
        }

        private fun apply(editor: SharedPreferences.Editor): SharedPreferences.Editor = editor

        /** The key to write, or null when it belongs to the target namespace. */
        private fun safe(key: String?): String? = if (key != null && SettingsKeys.isOverrideKey(key)) null else key
    }

    companion object {
        private const val LEGACY_SET_SEPARATOR: String = "\u0001"

        /**
         * Wraps [delegate] when a target is attached, and returns it untouched otherwise.
         *
         * Returning the delegate unchanged outside a target is deliberate: an
         * infrastructure process must see plain Global values and nothing else.
         */
        fun wrap(
            delegate: SharedPreferences,
            target: TargetApp?,
        ): SharedPreferences =
            if (target == null) {
                delegate
            } else {
                TargetScopedPreferences(delegate, target)
            }
    }
}
