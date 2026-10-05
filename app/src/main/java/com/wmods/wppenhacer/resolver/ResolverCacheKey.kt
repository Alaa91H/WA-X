package com.wmods.wppenhacer.resolver

/**
 * Builds the key that resolver cache entries are stored under.
 *
 * The failure this prevents is specific and nasty: a WhatsApp update renames internals,
 * the resolver cache still holds the previous build's answers, and every hook lands on a
 * member that no longer exists or now means something else. Nothing crashes — the module
 * just quietly misbehaves. Binding the key to the identity of the APK makes that
 * impossible.
 *
 * Four inputs, all of which must invalidate:
 *  * the WhatsApp version name, because a rename can happen within one versionCode
 *  * the versionCode, because a build can be swapped without a version bump
 *  * the APK's own hash, because the only reliable statement about "did the binary change"
 *  * the resolver schema version, because a change in how we resolve must not reuse
 *    answers produced by the old logic
 */
object ResolverCacheKey {

    /**
     * Bumped whenever the resolution logic itself changes in a way that could produce a
     * different answer for the same APK.
     *
     * This is a manual constant on purpose: it is the escape hatch for "the cache looks
     * valid but the answers are from an older algorithm", which no amount of hashing the
     * APK can detect.
     */
    const val SCHEMA_VERSION: Int = 1

    /** Separates the parts of a key so two different inputs cannot collide. */
    private const val SEPARATOR: Char = '|'

    /**
     * Builds the cache key.
     *
     * @param whatsAppVersion the target's version name, for example `2.26.40.21`
     * @param versionCode the target's version code
     * @param apkHash a hash of the target APK, or null when it could not be computed
     * @param schemaVersion the resolver schema, defaulting to [SCHEMA_VERSION]
     */
    fun build(
        whatsAppVersion: String?,
        versionCode: Long,
        apkHash: String?,
        schemaVersion: Int = SCHEMA_VERSION
    ): String = buildString {
        append(whatsAppVersion.orEmpty().trim())
        append(SEPARATOR)
        append(versionCode)
        append(SEPARATOR)
        // A null hash must still produce a distinct, visibly-degraded key. Using a literal
        // marker rather than an empty string means "hash unavailable" cannot be confused
        // with "hash of nothing", and it is obvious in a diagnostics dump.
        append(apkHash?.trim()?.takeIf { it.isNotEmpty() } ?: HASH_UNAVAILABLE)
        append(SEPARATOR)
        append(schemaVersion)
    }

    /**
     * Whether a stored key may be reused for the current target.
     *
     * A key built without an APK hash is never reusable: accepting it would reintroduce
     * exactly the stale-cache bug T12 exists to remove, just with less visibility.
     */
    fun isReusable(storedKey: String?, currentKey: String): Boolean {
        if (storedKey.isNullOrBlank()) return false
        if (storedKey == currentKey) return !currentKey.contains("$SEPARATOR$HASH_UNAVAILABLE$SEPARATOR")
        return false
    }

    /** Reads the schema version back out of a key, or null when it cannot be read. */
    fun schemaVersionOf(key: String?): Int? {
        if (key.isNullOrBlank()) return null
        val parts = key.split(SEPARATOR)
        if (parts.size < 4) return null
        return parts[3].toIntOrNull()
    }

    /** Reads the APK hash out of a key, or null when it is absent or unavailable. */
    fun apkHashOf(key: String?): String? {
        if (key.isNullOrBlank()) return null
        val parts = key.split(SEPARATOR)
        if (parts.size < 4) return null
        val hash = parts[2]
        return if (hash == HASH_UNAVAILABLE) null else hash
    }

    /** Marker used when the APK hash could not be computed. */
    const val HASH_UNAVAILABLE: String = "nohash"
}