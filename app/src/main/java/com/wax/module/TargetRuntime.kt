package com.wax.module

import com.wax.module.platform.TargetApp
import com.wax.module.settings.EffectiveSettingsResolver
import com.wax.module.settings.InMemorySettingsStore
import com.wax.module.settings.SettingsScope
import com.wax.module.settings.SettingsSnapshot
import com.wax.module.settings.SettingsSnapshotCache
import com.wax.module.settings.SettingsStore
import de.robv.android.xposed.XposedBridge
import java.util.concurrent.atomic.AtomicReference

/**
 * Per-process runtime state for the module.
 *
 * One APK now serves both WhatsApp builds, so the hooks have to know *which* build
 * they are running inside. That fact is established once, in [ModuleEntryPoint], from
 * the process package name, and every feature reads it from here rather than
 * re-deriving it.
 *
 * The design rule is that nothing is cached across targets. A single "current
 * configuration" object shared by both processes is precisely how Business ends up
 * applying WhatsApp's settings, so the target is stored per process and the settings
 * snapshot is keyed by it.
 */
object TargetRuntime {

    private val current = AtomicReference<TargetApp?>(null)

    @Volatile
    private var store: SettingsStore = InMemorySettingsStore()

    @Volatile
    private var resolver: EffectiveSettingsResolver = EffectiveSettingsResolver(store)

    @Volatile
    private var snapshots: SettingsSnapshotCache = SettingsSnapshotCache(resolver)

    /**
     * Where diagnostics go.
     *
     * Injectable rather than calling [XposedBridge] inline because this class is
     * reachable from unit tests, and the Xposed API is a compile-only dependency that
     * is not on the test classpath. A log call must not be the reason a routing test
     * cannot run.
     */
    @Volatile
    @JvmStatic
    var logger: (String) -> Unit = { message -> XposedBridge.log("WA X: $message") }

    /** The target this process is hooked into, or null when it is not a target. */
    val target: TargetApp? get() = current.get()

    /** The settings scope for this process, or null when it is not a target. */
    val scope: SettingsScope.Target? get() = current.get()?.let { SettingsScope.Target(it) }

    /**
     * Records that this process is [target] and installs its settings source.
     *
     * Called once per process from the entry point, before any feature runs.
     */
    @JvmStatic
    fun attach(
        target: TargetApp,
        store: SettingsStore = InMemorySettingsStore(),
        defaults: Map<String, Any?> = emptyMap(),
    ) {
        current.set(target)
        this.store = store
        this.resolver = EffectiveSettingsResolver(store, defaults)
        this.snapshots = SettingsSnapshotCache(this.resolver)
        logger("attached to ${target.displayName} (${target.packageName})")
    }

    /**
     * The effective settings for this process.
     *
     * Null before [attach], and null in any process that is not a supported target, so
     * a feature can never accidentally read Global defaults inside an unrelated app.
     */
    @JvmStatic
    fun snapshot(): SettingsSnapshot? = scope?.let { snapshots.snapshotFor(it) }

    /** The resolver, for the manager side and for diagnostics. */
    @JvmStatic
    fun resolver(): EffectiveSettingsResolver = resolver

    /** The store, for the manager side and for backup. */
    @JvmStatic
    fun store(): SettingsStore = store

    /** Whether this process is a supported target. */
    @JvmStatic
    fun isTargetProcess(): Boolean = current.get() != null

    /** Drops cached snapshots, so the next read reflects current storage. */
    @JvmStatic
    fun reload() {
        snapshots.invalidate()
        logger("settings reloaded for ${current.get()?.displayName ?: "no target"}")
    }

    /** Test hook: forgets which process this is. */
    @JvmStatic
    fun detach() {
        current.set(null)
        store = InMemorySettingsStore()
        resolver = EffectiveSettingsResolver(store)
        snapshots = SettingsSnapshotCache(resolver)
    }
}