package com.wmods.wppenhacer.xposed.bridge

import java.io.File

/**
 * The single authority on who may talk to the WaEnhancer bridge, and which paths the
 * bridge may touch.
 *
 * The bridge is reached through three surfaces, and they deliberately answer two
 * different questions:
 *
 *  * **Obtaining the binder handle** — [isAllowedBinderRequester]. The settings
 *    provider hook ([ScopeHook]) and [com.wmods.wppenhacer.xposed.bridge.providers.HookProvider]
 *    hand out the handle. They are permissive on purpose, because handing out a handle
 *    grants nothing on its own.
 *  * **Using the file tunnel** — [isAllowedTunnelCaller]. [com.wmods.wppenhacer.xposed.bridge.service.HookBinder]
 *    is the real gate and is the narrowest. Every path operation re-checks it.
 *
 * The split is deliberate defence in depth: a caller allowed to obtain the handle still
 * cannot open a file unless it also passes the tunnel check. Keeping both rules here,
 * rather than inlined per surface, is what stops the three copies from drifting apart —
 * they previously disagreed about the settings provider and about the system uid.
 */
object BridgeAccessPolicy {
    /** Target packages the module hooks and therefore serves. */
    val TARGET_PACKAGES: Set<String> = linkedSetOf("com.whatsapp", "com.whatsapp.w4b")

    /**
     * The settings provider is how [ScopeHook] intercepts `Settings.System` calls from
     * inside the hooked process, so it must be able to obtain the handle.
     */
    const val SETTINGS_PROVIDER: String = "com.android.providers.settings"

    /**
     * Whether a caller may obtain the binder handle.
     *
     * @param packages packages sharing the calling uid, as reported by the package manager
     * @param isSelfUid whether the call originates from the module's own uid
     * @param isSystemUid whether the call originates from the system uid
     * @param modulePackage this module's own application id
     */
    fun isAllowedBinderRequester(
        packages: Array<String>?,
        isSelfUid: Boolean,
        isSystemUid: Boolean,
        modulePackage: String,
    ): Boolean {
        if (isSelfUid || isSystemUid) return true
        val callerPackages = packages ?: return false
        return callerPackages.any { candidate ->
            candidate in TARGET_PACKAGES ||
                candidate == SETTINGS_PROVIDER ||
                candidate == modulePackage
        }
    }

    /**
     * Whether a caller may use the file tunnel.
     *
     * @param packages packages sharing the calling uid
     * @param isSelfUid whether the call originates from the module's own uid
     */
    fun isAllowedTunnelCaller(
        packages: Array<String>?,
        isSelfUid: Boolean,
    ): Boolean {
        if (isSelfUid) return true
        val callerPackages = packages ?: return false
        return callerPackages.any { it in TARGET_PACKAGES }
    }

    /**
     * Whether [targetPath] is [rootPath] itself or lives underneath it.
     *
     * The separator is appended before the prefix test so that `/storage/ABCdef` is not
     * accepted as living under `/storage/ABC`.
     *
     * Separators are normalised rather than taken from [File.separator]: this only ever
     * runs against Android storage, where the separator is always `/`, and normalising
     * keeps the rule verifiable on any JVM host instead of only on a device.
     */
    fun isUnderRoot(
        targetPath: String,
        rootPath: String,
    ): Boolean {
        val target = normaliseSeparators(targetPath)
        val root = normaliseSeparators(rootPath)
        return target == root || target.startsWith(root + "/")
    }

    /** Rewrites Windows-style separators to the `/` convention Android storage uses. */
    private fun normaliseSeparators(path: String): String = path.replace('\\', '/').trimEnd('/')

    /** The canonical path of [file] with separators normalised. */
    private fun canonicalPath(file: File): String = normaliseSeparators(runCatching { file.canonicalFile.path }.getOrElse { file.path })

    /**
     * Computes the storage roots the bridge is allowed to serve.
     *
     * Shared external storage plus, for every app-specific external directory, the
     * volume root above the `/Android/data/` segment. That second part is what lets the
     * bridge work on secondary volumes and on adoptable storage, where the app-specific
     * directory sits under a path the module cannot otherwise name.
     *
     * @param externalStorageDir the platform's primary external storage directory
     * @param externalFilesDirs the app-specific external directories, entries may be null
     */
    fun sharedStorageRoots(
        externalStorageDir: File,
        externalFilesDirs: List<File?>,
    ): List<File> {
        val roots = linkedSetOf<String>()
        roots.add(canonicalPath(externalStorageDir))

        externalFilesDirs.filterNotNull().forEach { appExternalDir ->
            volumeRootOf(canonicalPath(appExternalDir))?.let { roots.add(it) }
        }

        return roots.map { File(it) }
    }

    /** The `/Android/data/` segment whose parent is an allowed volume root. */
    private const val ANDROID_DATA_MARKER: String = "/Android/data/"

    /**
     * Extracts a package name from a `PackageSettings.toString()` dump.
     *
     * The platform's `PackageSettings` exposes no stable accessor across the hidden-API
     * boundary, so its `toString()` has to be scraped. The dump is a field listing such
     * as:
     *
     * ```
     * PackageSettings{38a1b2c userId=10123 pkg=com.whatsapp codePath=/data/user/0/...}
     * ```
     *
     * so the package name is the token following `pkg=`. The previous implementation
     * instead took everything between the last space and the last `/`, which on a real
     * dump yields `"codePath=/data/user/0"` — never a package name. Because both call
     * sites compared the result against this module's own application id, that guard
     * could never succeed and the control it protects was inert.
     *
     * @return the package name, or null when the dump carries no recognisable `pkg=`
     */
    fun packageNameFromPackageSettings(description: String?): String? {
        if (description.isNullOrEmpty()) return null

        val markerIndex = description.indexOf(PACKAGE_FIELD)
        if (markerIndex < 0) return null
        val valueStart = markerIndex + PACKAGE_FIELD.length
        if (valueStart >= description.length) return null

        var valueEnd = valueStart
        while (valueEnd < description.length && !description[valueEnd].isWhitespace() &&
            description[valueEnd] != '}'
        ) {
            valueEnd++
        }

        val name = description.substring(valueStart, valueEnd)
        return name.ifEmpty { null }
    }

    /** The `PackageSettings` field that carries the package name. */
    private const val PACKAGE_FIELD: String = "pkg="

    /**
     * Returns the volume root above the `/Android/data/` segment of [path], or null
     * when [path] has no such segment.
     *
     * A match at index zero is rejected: such a path has no volume root above the
     * marker to extract, and returning the empty string would make the caller treat the
     * filesystem root as an allowed bridge root.
     */
    fun volumeRootOf(path: String): String? {
        val normalised = normaliseSeparators(path)
        val markerIndex = normalised.indexOf(ANDROID_DATA_MARKER)
        if (markerIndex <= 0) return null
        return normalised.substring(0, markerIndex)
    }
}
