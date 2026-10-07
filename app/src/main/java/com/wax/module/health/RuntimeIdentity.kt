package com.wax.module.health

/**
 * Who and what a health record is about.
 *
 * The target's version is part of the identity rather than of the state, because a
 * resolution failure on one WhatsApp build is not the same fact as the same failure on the
 * next one, and a record that cannot say which build it came from cannot be compared with
 * another.
 *
 * Nothing here identifies a person: a package name, a process name, a pid and two version
 * strings.
 */
data class RuntimeIdentity(
    val packageName: String,
    val processName: String,
    val pid: Int,
    val moduleVersion: String,
    val targetVersionName: String? = null,
    val targetVersionCode: Long? = null,
    val androidSdk: Int = 0,
) {
    /** The key that separates this target's stored health from another's. */
    val targetKey: String get() = "$packageName|$processName"

    companion object {
        /** The identity to use before anything is known. */
        fun unknown(moduleVersion: String): RuntimeIdentity =
            RuntimeIdentity(
                packageName = "unknown",
                processName = "unknown",
                pid = 0,
                moduleVersion = moduleVersion,
            )
    }
}
