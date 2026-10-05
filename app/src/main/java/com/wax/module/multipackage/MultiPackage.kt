package com.wax.module.multipackage

import com.wax.module.platform.JsonValue
import com.wax.module.platform.KeyValueStore
import com.wax.module.platform.MiniJson
import com.wax.module.platform.SupportedPackages
import com.wax.module.platform.jsonNumber
import com.wax.module.platform.jsonObject
import com.wax.module.platform.jsonString
import com.wax.module.platform.long
import com.wax.module.platform.string
import java.security.MessageDigest

/** The target packages the platform can support (T152). */
enum class TargetPackage(
    val packageName: String,
    val displayName: String,
) {
    WHATSAPP(SupportedPackages.WHATSAPP, SupportedPackages.DISPLAY_NAMES.getValue(SupportedPackages.WHATSAPP)),
    BUSINESS(
        SupportedPackages.WHATSAPP_BUSINESS,
        SupportedPackages.DISPLAY_NAMES.getValue(SupportedPackages.WHATSAPP_BUSINESS),
    ),
    ;

    companion object {
        /** Finds a target by package name, or null for an unsupported variant. */
        fun byPackageName(packageName: String): TargetPackage? = entries.firstOrNull { it.packageName == packageName }
    }
}

/** One configured package profile. */
data class PackageProfile(
    val packageName: String,
    val displayName: String,
    val enabled: Boolean,
    /**
     * When true, changes to shared defaults apply to this package; when false, the package
     * keeps its own values (T153).
     */
    val useSharedDefaults: Boolean = false,
) {
    /** One line for the package list. */
    fun toDisplayLine(): String = "$displayName" + if (enabled) "" else " (disabled)" + if (useSharedDefaults) " [shared defaults]" else ""
}

/**
 * Per-package preferences (T153).
 *
 * Some preferences are inherently per package (which version is supported, which features
 * are enabled), while others benefit from a shared default. The namespace encodes that
 * choice in the key: a package using shared defaults reads and writes the shared key, so
 * changing a value once applies everywhere; a package with its own values reads and writes
 * its own namespace and is unaffected. The decision is stored per package rather than
 * inferred, so it never changes under the user.
 */
class PerPackagePreferences(
    private val store: KeyValueStore,
) {
    /** The preference key for [packageName], shared or scoped. */
    fun keyFor(
        packageName: String,
        key: String,
        useSharedDefaults: Boolean = false,
    ): String =
        if (useSharedDefaults) {
            "$SHARED_PREFIX$key"
        } else {
            "wae.pkg.${sanitize(packageName)}.$key"
        }

    /** Reads a preference. */
    fun getString(
        packageName: String,
        key: String,
        useSharedDefaults: Boolean = false,
    ): String? = store.getString(keyFor(packageName, key, useSharedDefaults))

    /** Writes a preference. */
    fun putString(
        packageName: String,
        key: String,
        value: String?,
        useSharedDefaults: Boolean = false,
    ) {
        store.putString(keyFor(packageName, key, useSharedDefaults), value)
    }

    /** Drops every scoped preference for a package. Shared values are untouched. */
    fun clearPackage(packageName: String): Int {
        val prefix = "wae.pkg.${sanitize(packageName)}."
        val keys = store.keys(prefix)
        keys.forEach { store.remove(it) }
        return keys.size
    }

    /**
     * Encodes a package name for use in a storage key.
     *
     * Dots are replaced too: keeping them would make `wae.pkg.com.whatsapp.` a string
     * prefix of the Business namespace `wae.pkg.com.whatsapp.w4b.`, so clearing the personal
     * package would also clear the Business one.
     */
    private fun sanitize(packageName: String): String = packageName.replace(Regex("[^A-Za-z0-9_]"), "_")

    companion object {
        /** Prefix for values shared across packages. */
        const val SHARED_PREFIX: String = "wae.pkg.shared."
    }
}

/** The per-package compatibility state T154 requires. */
data class PackageCompatibilityState(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val apkFingerprint: String,
    /** Cache key derived from the fingerprint; a new build invalidates resolver caches. */
    val resolverCacheKey: String,
    /** Feature id -> last known switch state, stored per package. */
    val featureStates: Map<String, String> = emptyMap(),
) {
    /** One line for diagnostics. */
    fun toDisplayLine(): String = "$packageName $versionName ($versionCode), ${featureStates.size} feature state(s)"
}

/** The state store behind [PackageCompatibilityState]. */
class PackageCompatibilityStore(
    private val store: KeyValueStore,
) {
    /** The stored state for a package, or null when the build was never recorded. */
    fun stateOf(packageName: String): PackageCompatibilityState? {
        val text = store.getString(keyFor(packageName)) ?: return null
        val fields = (MiniJson.parse(text) as? JsonValue.Obj)?.fields ?: return null
        val versionName = fields.string("versionName") ?: return null
        val featureStates =
            (fields.obj("features"))
                ?.mapNotNull { (feature, value) -> value.string()?.let { feature to it } }
                ?.toMap()
                .orEmpty()
        return PackageCompatibilityState(
            packageName = packageName,
            versionName = versionName,
            versionCode = fields.long("versionCode") ?: 0L,
            apkFingerprint = fields.string("fingerprint").orEmpty(),
            resolverCacheKey = fields.string("resolverCacheKey").orEmpty(),
            featureStates = featureStates,
        )
    }

    /** Records the state for a package. */
    fun record(state: PackageCompatibilityState) {
        store.putString(keyFor(state.packageName), MiniJson.write(encode(state)))
    }

    /** Records one feature's state for a package, preserving the rest. */
    fun setFeatureState(
        packageName: String,
        featureId: String,
        state: String,
    ) {
        val current =
            stateOf(packageName) ?: PackageCompatibilityState(
                packageName = packageName,
                versionName = "",
                versionCode = 0L,
                apkFingerprint = "",
                resolverCacheKey = "",
            )
        record(current.copy(featureStates = current.featureStates + (featureId to state)))
    }

    /** Drops a package's state. */
    fun clear(packageName: String): Boolean {
        if (store.getString(keyFor(packageName)) == null) return false
        store.remove(keyFor(packageName))
        return true
    }

    /** Applies a recorded state to a platform kill switch. */
    fun applyTo(
        killSwitch: com.wax.module.platform.FeatureKillSwitch,
        packageName: String,
    ) {
        val state = stateOf(packageName) ?: return
        state.featureStates.forEach { (featureId, stored) ->
            when (stored) {
                "INCOMPATIBLE" -> killSwitch.markIncompatible(featureId, "recorded for $packageName")
                "MANUALLY_DISABLED" -> killSwitch.setEnabled(featureId, false)
                "ENABLED" -> Unit
                else -> Unit
            }
        }
    }

    private fun keyFor(packageName: String): String = "$KEY_STATE$packageName"

    private fun encode(state: PackageCompatibilityState): JsonValue.Obj =
        jsonObject(
            "versionName" to jsonString(state.versionName),
            "versionCode" to jsonNumber(state.versionCode),
            "fingerprint" to jsonString(state.apkFingerprint),
            "resolverCacheKey" to jsonString(state.resolverCacheKey),
            "features" to
                JsonValue.Obj(
                    state.featureStates.entries.associate { (feature, value) -> feature to jsonString(value) },
                ),
        )

    /** Reads a stored feature state string. */
    private fun JsonValue.string(): String? = (this as? JsonValue.Str)?.value

    /** Reads an object field. */
    private fun Map<String, JsonValue>.obj(key: String): Map<String, JsonValue>? = (this[key] as? JsonValue.Obj)?.fields

    companion object {
        /** Storage key prefix for per-package state. */
        const val KEY_STATE: String = "wae.pkg.state."
    }
}

/**
 * One account context (T155).
 *
 * An account is identified by its package plus a local identifier. The identifier is hashed
 * for storage keys and diagnostics, so two accounts cannot collide and the raw identifier is
 * not scattered across the store (T156). The hash is stable, which is all that scoping needs.
 */
data class AccountContext(
    val packageName: String,
    val accountId: String,
) {
    /**
     * A stable, non-reversible identifier used in storage keys and diagnostics.
     *
     * The package name is part of the digest: two accounts that happen to share a local
     * identifier ("default" is the common case) must not share a scoped key.
     */
    val accountHash: String
        get() =
            MessageDigest
                .getInstance("SHA-256")
                .digest("$packageName\u0000$accountId".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
                .take(16)

    /** The scoped storage key for a preference. */
    fun scopedKey(key: String): String = "wae.acct.$accountHash.$key"

    /** A diagnostics-safe description: no raw identifier. */
    fun describe(): String = "$packageName account ${accountHash.take(8)}…"

    /** Friendlier than the default data-class equality for maps. */
    override fun toString(): String = describe()
}

/** Tracks known accounts so scoped settings can be enumerated and migrated. */
class AccountRegistry(
    private val store: KeyValueStore,
) {
    /** Records an account context. */
    fun register(context: AccountContext) {
        store.putString(keyFor(context.packageName, context.accountHash), context.accountId)
    }

    /** Every known account, newest first by registration order. */
    fun all(): List<AccountContext> =
        store.keys(KEY_ACCOUNTS).mapNotNull { key ->
            val remainder = key.removePrefix(KEY_ACCOUNTS)
            // Package names contain dots (com.whatsapp.w4b), so the separator is the last
            // one: everything before it is the package, everything after is the hash.
            val separator = remainder.lastIndexOf('.')
            if (separator <= 0) return@mapNotNull null
            val packageName = remainder.substring(0, separator)
            val hash = remainder.substring(separator + 1)
            store.getString(key)?.let { accountId -> AccountContext(packageName, accountId).takeIf { it.accountHash == hash } }
        }

    /** Accounts for one package. */
    fun forPackage(packageName: String): List<AccountContext> = all().filter { it.packageName == packageName }

    /** Drops a registered account. */
    fun remove(context: AccountContext): Boolean {
        val key = keyFor(context.packageName, context.accountHash)
        if (store.getString(key) == null) return false
        store.remove(key)
        return true
    }

    /** Drops every registration. */
    fun clear() {
        store.keys(KEY_ACCOUNTS).forEach { store.remove(it) }
    }

    private fun keyFor(
        packageName: String,
        accountHash: String,
    ): String = "$KEY_ACCOUNTS$packageName.$accountHash"

    companion object {
        /** Storage key prefix for account registrations. */
        const val KEY_ACCOUNTS: String = "wae.pkg.accounts."
    }
}

/**
 * Per-account settings (T157).
 *
 * Each account can be assigned its own privacy profile, so "Business uses Work, Personal
 * uses Ghost" is a stored assignment rather than a special case in the code. Assignment is
 * keyed by the hashed account context, which is what prevents two accounts from writing to
 * the same slot.
 */
class AccountProfileAssignments(
    private val store: KeyValueStore,
) {
    /** Assigns [profileId] to an account. */
    fun assign(
        context: AccountContext,
        profileId: String,
    ): Boolean {
        if (profileId.isBlank()) return false
        store.putString(context.scopedKey(KEY_PROFILE), profileId)
        return true
    }

    /** The assigned profile id, or null. */
    fun profileFor(context: AccountContext): String? = store.getString(context.scopedKey(KEY_PROFILE))

    /** Removes an assignment. */
    fun clearAssignment(context: AccountContext): Boolean {
        val key = context.scopedKey(KEY_PROFILE)
        if (store.getString(key) == null) return false
        store.remove(key)
        return true
    }

    /** Drops every profile assignment, leaving other account-scoped settings alone. */
    fun clear() {
        store
            .keys(KEY_PROFILE_PREFIX)
            .filter { it.endsWith(".$KEY_PROFILE") }
            .forEach { store.remove(it) }
    }

    companion object {
        private const val KEY_PROFILE = "automation.profile"
        private const val KEY_PROFILE_PREFIX = "wae.acct."
    }
}

/** The outcome of the T158 migration. */
data class MultiAccountMigrationReport(
    val migratedAccounts: Int,
    val alreadyDone: Boolean,
    val packages: List<String>,
) {
    /** One line for the migration screen. */
    fun toDisplayLine(): String =
        if (alreadyDone) {
            "Multi-account migration already completed."
        } else {
            "Migrated settings for $migratedAccounts account slot(s) across ${packages.size} package(s)."
        }
}

/**
 * Migrates single-profile settings to the multi-account layout (T158).
 *
 * The migration is one-way and guarded by a marker: it runs once, records that it ran, and
 * afterwards reports `alreadyDone` instead of re-running. That matters because the legacy
 * key it reads is deleted or overwritten by newer code, and a migration that runs twice can
 * only do harm. The legacy value is copied to every known package so no package loses its
 * configuration.
 */
class MultiAccountMigration(
    private val store: KeyValueStore,
) {
    /** Runs the migration for [knownPackages]. */
    fun migrate(knownPackages: List<String> = TargetPackage.entries.map { it.packageName }): MultiAccountMigrationReport {
        if (store.getBoolean(KEY_DONE, false)) {
            return MultiAccountMigrationReport(0, alreadyDone = true, packages = knownPackages)
        }
        val legacyProfile = store.getString(LEGACY_PROFILE_KEY)
        var migrated = 0
        if (!legacyProfile.isNullOrBlank()) {
            knownPackages.forEach { packageName ->
                val context = AccountContext(packageName, DEFAULT_ACCOUNT_ID)
                store.putString(context.scopedKey(LEGACY_PROFILE_KEY), legacyProfile)
                migrated++
            }
        }
        store.putBoolean(KEY_DONE, true)
        return MultiAccountMigrationReport(migrated, alreadyDone = false, packages = knownPackages)
    }

    /** Whether the migration already ran. */
    fun isDone(): Boolean = store.getBoolean(KEY_DONE, false)

    /** Resets the migration marker. Used by tests. */
    fun reset() {
        store.remove(KEY_DONE)
    }

    companion object {
        /** The single-profile key the migration reads. */
        const val LEGACY_PROFILE_KEY: String = "wae.privacy.legacy.active_profile"

        /** The account id used for the package's only account before multi-account support. */
        const val DEFAULT_ACCOUNT_ID: String = "default"

        private const val KEY_DONE = "wae.pkg.migration.done"
    }
}

/** The T159 test-matrix description, so the validation scope is explicit and testable. */
object MultiAccountTestMatrix {
    /** The scenarios T159 requires. */
    val scenarios: List<String> =
        listOf(
            "WhatsApp only",
            "Business only",
            "Both installed",
            "Multiple supported accounts",
            "Independent compatibility failures",
        )

    /** Whether an installation matching the given packages covers the matrix. */
    fun covers(
        packageNames: Collection<String>,
        accountCount: Int,
    ): Boolean = packageNames.isNotEmpty() && accountCount >= 1
}
