package com.wax.module.xposed.features.others

import com.wax.module.contract.FeatureContext
import com.wax.module.contract.FeatureStartResult
import com.wax.module.contract.WaFeature

/**
 * The pilot: a feature written against [WaFeature] instead of the old abstract class.
 *
 * It does nothing, which is exactly why it was chosen. A migration needs a first feature, and the
 * first feature must be one whose behaviour cannot change - so that if the migration is wrong, the
 * only thing that can be wrong is the migration. Sixty-three features still extend `Feature`;
 * this one no longer takes an `android.content.SharedPreferences` in its constructor, appears in
 * the same installed set, and is started through the same stage.
 *
 * What it demonstrates, and what `FeatureContractTest` then asserts:
 *
 * * it starts in a plain JVM test with fakes and no framework, and
 * * it reports what it did through [FeatureStartResult] rather than through a log line.
 */
class DebugFeature : WaFeature {
    override val featureId: String = "Debug Feature"

    override fun start(context: FeatureContext): FeatureStartResult =
        FeatureStartResult.Installed(
            summary = "nothing to install",
            hooks = context.hooks.installedCount(),
        )
}
