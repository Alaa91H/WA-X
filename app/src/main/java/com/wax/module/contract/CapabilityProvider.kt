package com.wax.module.contract

/**
 * What this target actually has.
 *
 * A feature's first decision is almost always "does this build have the thing I need", and today
 * it answers that by calling the resolver engine - fifty-two of the feature files reach
 * `Unobfuscator` directly, with `Others.kt` making thirty-seven calls. That made every one of those
 * decisions untestable without a dex file and a class loader, and it is the reason the feature
 * layer sits at zero unit coverage.
 *
 * Two questions are separated here on purpose. [hasClass] is a cheap existence check that any
 * implementation can answer, and it is what "is this build supported" should ask. Anything deeper
 * - finding a member, deciding whether a match is unique - belongs to the resolver layer, which is
 * already pure and is the subject of M08 and A04. A feature that needs more than this is asking
 * for something that has not been abstracted yet, and that is visible rather than hidden.
 */
interface CapabilityProvider {
    /** Whether [className] exists in the target process. */
    fun hasClass(className: String): Boolean

    /** The class named [className], or null when the target does not have it. */
    fun findClass(className: String): Class<*>?

    /** The target application's version name, or null when it could not be read. */
    fun targetVersionName(): String?

    /** The Android API level this process is running on. */
    fun androidSdkInt(): Int

    /**
     * Whether the target's dex files were scanned successfully.
     *
     * A feature that needs to resolve members must check this before it tries: an engine that did
     * not start is a different situation from a build that lacks a class, and telling them apart
     * is what stops "the engine failed" being reported as "this build is unsupported".
     */
    fun resolutionAvailable(): Boolean
}
