package com.wax.module.xposed.registry

import android.content.SharedPreferences
import com.wax.module.contract.FeatureContext
import com.wax.module.contract.FeatureStartResult
import com.wax.module.contract.WaFeature
import com.wax.module.xposed.core.Feature

/**
 * How one feature is constructed and started.
 *
 * This type exists to delete three reflective construction sites that all did the same thing:
 * look a constructor up by parameter type, instantiate through it, and hope the parameter types
 * still match. Every one of those was a runtime failure with a message that named neither the
 * feature nor the reason - `NoSuchMethodException` from a class whose constructor had simply
 * changed shape is indistinguishable from one that was never registered.
 *
 * Sealed, and that is the load-bearing word. The loader starts a feature without branching on a
 * kind, because a third kind cannot be added without the compiler forcing the caller to handle it.
 * That is the difference between a registry that stays authoritative and one that grows a path
 * nobody has to think about.
 *
 * The two factories are the two contracts features are written against, and they are genuinely
 * different: [Contract] needs nothing but a no-arg constructor because its dependencies arrive in
 * [FeatureContext], and [Legacy] needs the class loader and the preferences because that is what
 * its signature has always been. #342 A08 removes the second one; nothing here has to change when
 * it does, because the loader never learns which kind it is starting.
 */
sealed interface FeatureFactory {
    /**
     * The stable id of this feature.
     *
     * Used in logs, failure reports and the diagnostics dialog, so it is the class's simple name by
     * construction rather than a display label that changes with the locale. Every feature's id must
     * be unique, which `check_feature_registry.py` and `FeatureRegistryTest` both enforce.
     */
    val featureId: String

    /**
     * Constructs and starts the feature.
     *
     * Returns what happened rather than throwing for the ordinary outcomes: an unsupported build is
     * a [FeatureStartResult.Skipped] and a partial install is a [FeatureStartResult.Degraded], and a
     * feature that throws for either is a feature that makes a user's list look broken on a build
     * it simply does not target. A genuine failure may still throw, and the loader records it.
     *
     * [legacyPreferences] is ignored by [Contract]; it is here because [Legacy] cannot be built
     * without it and passing both keeps the call site free of a branch.
     */
    fun start(
        context: FeatureContext,
        legacyPreferences: SharedPreferences,
    ): FeatureStartResult

    /** A feature written against `WaFeature`: constructed with nothing, given everything. */
    class Contract(
        override val featureId: String,
        val create: () -> WaFeature,
    ) : FeatureFactory {
        override fun start(
            context: FeatureContext,
            legacyPreferences: SharedPreferences,
        ): FeatureStartResult = create().start(context)
    }

    /**
     * A feature still written against `Feature(classLoader, prefs)`.
     *
     * The class loader comes from [FeatureContext.targetClassLoader] rather than being passed
     * separately: it is the same object the reflective path used, and taking it from the context
     * means a feature cannot be constructed against one target's loader and started with another's.
     */
    class Legacy(
        override val featureId: String,
        val create: (ClassLoader, SharedPreferences) -> Feature,
    ) : FeatureFactory {
        override fun start(
            context: FeatureContext,
            legacyPreferences: SharedPreferences,
        ): FeatureStartResult {
            val feature = create(context.targetClassLoader, legacyPreferences)
            feature.doHook()
            return FeatureStartResult.Installed(summary = feature.getPluginName())
        }
    }
}
