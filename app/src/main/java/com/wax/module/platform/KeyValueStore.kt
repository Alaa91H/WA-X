package com.wax.module.platform

/**
 * The smallest persistence surface the platform engines need.
 *
 * Engines are deliberately kept away from `SharedPreferences` and from Android entirely,
 * because every rule in this platform (kill switch strikes, profile switching, rule
 * evaluation, backup planning) has to be verifiable in plain JVM unit tests. An engine
 * that takes a [KeyValueStore] can be tested with [InMemoryKeyValueStore] and wired to the
 * real preferences at the edge, without changing the logic under test.
 *
 * Values are stored as strings so any engine can encode its model however it wants and no
 * engine has to care which concrete store it got. The typed helpers below are the common
 * encoding, used for counters and timestamps.
 */
interface KeyValueStore {
    /** The raw string for [key], or null when it was never written. */
    fun getString(key: String): String?

    /** Writes [value]; a null value is equivalent to [remove]. */
    fun putString(
        key: String,
        value: String?,
    )

    /** Removes [key], leaving no trace of it behind. */
    fun remove(key: String)

    /** Every key that currently has a value, optionally restricted to a prefix. */
    fun keys(prefix: String = ""): Set<String>

    /** Reads an integer, falling back to [default] when absent or unparsable. */
    fun getInt(
        key: String,
        default: Int = 0,
    ): Int = getString(key)?.toIntOrNull() ?: default

    /** Reads a long, falling back to [default] when absent or unparsable. */
    fun getLong(
        key: String,
        default: Long = 0L,
    ): Long = getString(key)?.toLongOrNull() ?: default

    /** Reads a boolean, treating anything other than `true` as [default]. */
    fun getBoolean(
        key: String,
        default: Boolean = false,
    ): Boolean = getString(key)?.toBooleanStrictOrNull() ?: default

    /** Writes an integer. */
    fun putInt(
        key: String,
        value: Int,
    ) = putString(key, value.toString())

    /** Writes a long. */
    fun putLong(
        key: String,
        value: Long,
    ) = putString(key, value.toString())

    /** Writes a boolean. */
    fun putBoolean(
        key: String,
        value: Boolean,
    ) = putString(key, value.toString())
}

/**
 * A [KeyValueStore] that keeps everything in memory.
 *
 * Used by tests and by engines that only need session state. Not thread safe by design:
 * the platform engines synchronise their own access, and hiding that behind a lock here
 * would make it easy to forget that a real store needs cross-thread care.
 */
class InMemoryKeyValueStore(
    initial: Map<String, String> = emptyMap(),
) : KeyValueStore {
    private val values = LinkedHashMap<String, String>(initial)

    override fun getString(key: String): String? = values[key]

    override fun putString(
        key: String,
        value: String?,
    ) {
        if (value == null) {
            values.remove(key)
        } else {
            values[key] = value
        }
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun keys(prefix: String): Set<String> = values.keys.filter { it.startsWith(prefix) }.toSet()
}
