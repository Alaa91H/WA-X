package com.wax.module.contract

import com.wax.module.settings.SettingsSnapshot

/**
 * Everything a feature is allowed to reach for.
 *
 * Six capabilities and nothing else. Each one is an interface rather than a class so a test can
 * supply its own, and each one exists because sixty-four features needed *something* of that kind
 * and got it by reaching into a process-global singleton instead - `ModuleRuntime`,
 * `Utils.xprefs`, `Unobfuscator`, `XposedBridge`. A singleton is not a dependency: it cannot be
 * replaced, cannot be asserted on, and cannot be told that a test is happening.
 *
 * The absence of a `Context` is deliberate and is the constraint that shapes every feature
 * written against this contract. A feature that needs to show a dialog is asking for something
 * the injected runtime cannot give it, and the honest answer is a [DiagnosticSink] record plus the
 * target's own UI rather than an `Activity` captured from somewhere global.
 *
 * [targetClassLoader] is the one thing that stays concrete, because reflecting on the target's
 * classes is the feature's actual job. It is a `java.lang.ClassLoader` - the JDK type every
 * process has - not an Xposed one, and it is passed in rather than read from a global so that a
 * test can hand a feature a loader it controls.
 */
interface FeatureContext {
    /** The effective settings for this target process. Immutable and already target-scoped. */
    val settings: SettingsSnapshot

    /** What this target actually has: classes, versions, and the platform level. */
    val capabilities: CapabilityProvider

    /** Installs hooks. The only way to change the target's behaviour. */
    val hooks: HookEngine

    /** Records a structured failure against this feature. */
    val diagnostics: DiagnosticSink

    /** The time, so a feature never reads the clock directly and a test can control it. */
    val clock: RuntimeClock

    /** Where to write. */
    val logger: RuntimeLogger

    /** The target's class loader. A JDK type, supplied rather than read from a global. */
    val targetClassLoader: ClassLoader
}
