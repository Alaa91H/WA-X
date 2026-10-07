package com.wax.module.settings

import com.wax.module.platform.TargetApp
import java.util.concurrent.ConcurrentHashMap

/**
 * Raw persistence for the three settings scopes.
 *
 * Deliberately thin and storage-agnostic: it knows how to read and write one scope
 * and nothing about resolution. That split is what lets [EffectiveSettingsResolver] be
 * tested exhaustively without a device and without Android, which is the only
 * practical way to prove that one target's overrides cannot reach the other.
 *
 * Implementations must be safe to call from the hooked process, where reads happen on
 * hot hook paths. The in-memory implementation used in tests and by the runtime
 * snapshot satisfies that trivially; a SharedPreferences-backed one must cache too.
 */
interface SettingsStore {
    /** The raw string override for [scope], or null when unset. */
    fun readString(
        scope: SettingsScope,
        key: String,
    ): String?

    /** Writes a raw string override. A null [value] clears the override. */
    fun writeString(
        scope: SettingsScope,
        key: String,
        value: String?,
    )

    /** The raw boolean override for [scope], or null when unset. */
    fun readBoolean(
        scope: SettingsScope,
        key: String,
    ): Boolean?

    /** Writes a raw boolean override. A null [value] clears the override. */
    fun writeBoolean(
        scope: SettingsScope,
        key: String,
        value: Boolean?,
    )

    /** The raw integer override for [scope], or null when unset. */
    fun readInt(
        scope: SettingsScope,
        key: String,
    ): Int?

    /** Writes a raw integer override. A null [value] clears the override. */
    fun writeInt(
        scope: SettingsScope,
        key: String,
        value: Int?,
    )

    /** The raw float override for [scope], or null when unset. */
    fun readFloat(
        scope: SettingsScope,
        key: String,
    ): Float?

    /** Writes a raw float override. A null [value] clears the override. */
    fun writeFloat(
        scope: SettingsScope,
        key: String,
        value: Float?,
    )

    /** The raw string-set override for [scope], or null when unset. */
    fun readStringSet(
        scope: SettingsScope,
        key: String,
    ): Set<String>?

    /** Writes a raw string-set override. A null [value] clears the override. */
    fun writeStringSet(
        scope: SettingsScope,
        key: String,
        value: Set<String>?,
    )

    /** Every key that carries an override in [scope]. */
    fun keysWithOverrides(scope: SettingsScope): Set<String>

    /** Removes every override in [scope], leaving Global untouched. */
    fun clearScope(scope: SettingsScope)

    /** Copies every override from [from] to [to], replacing what was there. */
    fun copyScope(
        from: SettingsScope,
        to: SettingsScope,
    )

    /**
     * Replaces every scope's contents in one step, or does nothing at all.
     *
     * All-or-nothing because a partial restore would leave the user with a mix of
     * two configurations and no way to tell which parts came from where.
     */
    fun replaceAll(
        global: Map<String, String>,
        targets: Map<TargetApp, Map<String, String>>,
    )
}

/**
 * A [SettingsStore] held in memory.
 *
 * Used by the unit tests, and by the runtime snapshot builder before it persists.
 * Writes are recorded per scope, which is what makes the isolation tests able to
 * assert that writing to one target changed nothing in the other.
 */
class InMemorySettingsStore(
    seed: Map<SettingsScope, Map<String, String>> = emptyMap(),
) : SettingsStore {
    private val data: MutableMap<SettingsScope, MutableMap<String, String>> =
        ConcurrentHashMap<SettingsScope, MutableMap<String, String>>().apply {
            seed.forEach { (scope, values) ->
                if (values.isNotEmpty()) put(scope, ConcurrentHashMap(values))
            }
        }

    private fun require(scope: SettingsScope): MutableMap<String, String> = data.getOrPut(scope) { ConcurrentHashMap() }

    private fun readRaw(
        scope: SettingsScope,
        key: String,
    ): String? = data[scope]?.get(key)

    private fun writeRaw(
        scope: SettingsScope,
        key: String,
        value: String?,
    ) {
        if (value == null) data[scope]?.remove(key) else require(scope)[key] = value
    }

    override fun readString(
        scope: SettingsScope,
        key: String,
    ): String? = readRaw(scope, key)

    override fun writeString(
        scope: SettingsScope,
        key: String,
        value: String?,
    ) = writeRaw(scope, key, value)

    override fun readBoolean(
        scope: SettingsScope,
        key: String,
    ): Boolean? = readRaw(scope, key)?.toBooleanStrictOrNull()

    override fun writeBoolean(
        scope: SettingsScope,
        key: String,
        value: Boolean?,
    ) = writeRaw(scope, key, value?.toString())

    override fun readInt(
        scope: SettingsScope,
        key: String,
    ): Int? = readRaw(scope, key)?.toIntOrNull()

    override fun writeInt(
        scope: SettingsScope,
        key: String,
        value: Int?,
    ) = writeRaw(scope, key, value?.toString())

    override fun readFloat(
        scope: SettingsScope,
        key: String,
    ): Float? = readRaw(scope, key)?.toFloatOrNull()

    override fun writeFloat(
        scope: SettingsScope,
        key: String,
        value: Float?,
    ) = writeRaw(scope, key, value?.toString())

    override fun readStringSet(
        scope: SettingsScope,
        key: String,
    ): Set<String>? = readRaw(scope, key)?.split(SettingsKeys.SET_SEPARATOR)?.filter { it.isNotEmpty() }?.toSet()

    override fun writeStringSet(
        scope: SettingsScope,
        key: String,
        value: Set<String>?,
    ) = writeRaw(scope, key, value?.sorted()?.joinToString(SettingsKeys.SET_SEPARATOR))

    override fun keysWithOverrides(scope: SettingsScope): Set<String> = data[scope]?.keys?.toSet() ?: emptySet()

    override fun clearScope(scope: SettingsScope) {
        data.remove(scope)
    }

    override fun copyScope(
        from: SettingsScope,
        to: SettingsScope,
    ) {
        val source = data[from]?.toMap() ?: emptyMap()
        if (source.isEmpty()) {
            data.remove(to)
        } else {
            data[to] = ConcurrentHashMap(source)
        }
    }

    override fun replaceAll(
        global: Map<String, String>,
        targets: Map<TargetApp, Map<String, String>>,
    ) {
        // Built first, swapped second: a failure part-way through leaves the previous
        // contents intact rather than a half-applied restore.
        val rebuilt = ConcurrentHashMap<SettingsScope, MutableMap<String, String>>()
        if (global.isNotEmpty()) rebuilt[SettingsScope.Global] = ConcurrentHashMap(global)
        targets.forEach { (app, values) ->
            if (values.isNotEmpty()) {
                rebuilt[SettingsScope.Target(app)] = ConcurrentHashMap(values)
            }
        }
        data.clear()
        data.putAll(rebuilt)
    }

    /** Read-only view of one scope, for assertions and for the backup writer. */
    fun snapshotOf(scope: SettingsScope): Map<String, String> = data[scope]?.toMap() ?: emptyMap()

    /** Read-only view of every scope, keyed by scope code. */
    fun snapshotAll(): Map<String, Map<String, String>> = data.entries.associate { (scope, values) -> scope.code to values.toMap() }
}
