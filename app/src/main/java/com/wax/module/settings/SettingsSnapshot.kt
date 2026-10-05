package com.wax.module.settings

/**
 * An immutable view of the effective settings for one hooked process.
 *
 * A hook can be invoked thousands of times a second, and a `SharedPreferences` read
 * on each of those is both slow and, on the main thread, a source of jank inside
 * WhatsApp. A snapshot resolves once and answers from memory.
 *
 * Snapshots are immutable and cheap; they are replaced wholesale when settings change
 * rather than mutated, so a hook that captured one keeps seeing a consistent view
 * instead of a half-updated one.
 */
class SettingsSnapshot internal constructor(
    private val resolver: EffectiveSettingsResolver,
    /** The target this snapshot is bound to. */
    val scope: SettingsScope.Target,
) {
    /** The target application this snapshot belongs to. */
    val target = scope.app

    /** The effective boolean for [key]. */
    fun boolean(key: String): Boolean = resolver.effectiveBoolean(key, scope)

    /** The effective string for [key], or null. */
    fun string(key: String): String? = resolver.effectiveString(key, scope)

    /** The effective integer for [key], or [fallback]. */
    fun int(
        key: String,
        fallback: Int = 0,
    ): Int = resolver.effectiveInt(key, scope, fallback)

    /** The effective float for [key], or [fallback]. */
    fun float(
        key: String,
        fallback: Float = 0f,
    ): Float = resolver.effectiveFloat(key, scope, fallback)

    /** The effective string set for [key]. */
    fun stringSet(key: String): Set<String> = resolver.effectiveStringSet(key, scope)

    /** The effective tri-state for [key]. */
    fun triState(key: String): TriState = resolver.triState(key, scope)

    /** Whether [key] diverges from Global in this target. */
    fun isOverridden(key: String): Boolean = resolver.isOverridden(key, scope)

    /** Every key this target overrides. */
    fun overriddenKeys(): List<String> = resolver.overriddenKeys(scope)
}

/**
 * Holds one snapshot per target and hands out the right one for a process.
 *
 * Caches by target rather than globally, because the failure this prevents is subtle:
 * a single cached "current config" object that WhatsApp and WhatsApp Business both
 * read is how Business ends up applying WhatsApp's settings.
 */
class SettingsSnapshotCache(
    private val resolver: EffectiveSettingsResolver,
) {
    private val snapshots = HashMap<SettingsScope.Target, SettingsSnapshot>()

    /** The snapshot for [scope], creating it on first use. */
    fun snapshotFor(scope: SettingsScope.Target): SettingsSnapshot =
        synchronized(snapshots) { snapshots.getOrPut(scope) { resolver.snapshotFor(scope.app) } }

    /** The snapshot for a hooked process, or null when the process is not a target. */
    fun snapshotForPackage(packageName: String?): SettingsSnapshot? {
        val scope = SettingsScope.forPackage(packageName) ?: return null
        return snapshotFor(scope)
    }

    /** Discards cached snapshots so the next read reflects current storage. */
    fun invalidate() {
        synchronized(snapshots) { snapshots.clear() }
    }

    /** Discards the snapshot for one target only, leaving the other intact. */
    fun invalidate(scope: SettingsScope) {
        synchronized(snapshots) {
            if (scope is SettingsScope.Target) snapshots.remove(scope)
        }
    }
}
