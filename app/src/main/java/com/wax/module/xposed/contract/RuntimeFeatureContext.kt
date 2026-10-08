package com.wax.module.xposed.contract

import android.os.Build
import com.wax.module.contract.CapabilityProvider
import com.wax.module.contract.DiagnosticSink
import com.wax.module.contract.FeatureContext
import com.wax.module.contract.HookEngine
import com.wax.module.contract.RuntimeClock
import com.wax.module.contract.RuntimeLogger
import com.wax.module.diagnostics.FailureCode
import com.wax.module.diagnostics.FeatureFailureReport
import com.wax.module.settings.SettingsSnapshot
import com.wax.module.xposed.core.devkit.Unobfuscator

/**
 * The one [FeatureContext] an injected process hands to its features.
 *
 * A value rather than a factory, because the six capabilities are process-scoped: there is one
 * target class loader, one clock, one logger. Building a context per feature would mean six
 * objects each doing the same work sixty-four times, and a feature that installed a hook through
 * one context and read the clock through another would be relying on two identities for one
 * process.
 *
 * [settings] is required rather than looked up, because the snapshot is target-scoped and the
 * process is the only thing that knows which target it is. Passing it in is what makes WhatsApp
 * and WhatsApp Business impossible to confuse, and that confusion is the one bug this project's
 * multi-target tests exist to prevent.
 */
data class RuntimeFeatureContext(
    override val settings: SettingsSnapshot,
    override val capabilities: CapabilityProvider,
    override val hooks: HookEngine,
    override val diagnostics: DiagnosticSink,
    override val clock: RuntimeClock,
    override val logger: RuntimeLogger,
    override val targetClassLoader: ClassLoader,
) : FeatureContext

/**
 * Builds the context for a target process.
 *
 * Separated from [RuntimeFeatureContext] so the context is a plain value that a test can construct
 * directly and this - which reads the engine's state and the platform's level - is the only part
 * that needs a device.
 */
object RuntimeFeatureContexts {
    /**
     * The context for a hooked process.
     *
     * [targetVersionName] may be null: a target whose version could not be read is a fact the
     * context carries rather than a reason to fail, and a feature that needs the version asks
     * [CapabilityProvider.targetVersionName] for it.
     */
    fun forTarget(
        settings: SettingsSnapshot,
        classLoader: ClassLoader,
        targetVersionName: String?,
        debugEnabled: () -> Boolean,
        reportFailure: (featureId: String, code: FailureCode, message: String?, stage: String?) -> FeatureFailureReport,
    ): RuntimeFeatureContext =
        RuntimeFeatureContext(
            settings = settings,
            capabilities =
                FrameworkCapabilityProvider(
                    classLoader = classLoader,
                    targetVersionName = targetVersionName,
                    androidSdkInt = Build.VERSION.SDK_INT,
                    // Read once, here, so that "the engine did not start" is a fact every
                    // feature sees rather than something each of them has to infer from a failed
                    // lookup.
                    resolutionAvailable = Unobfuscator.isInitialised(),
                ),
            hooks = XposedHookEngine(classLoader) { message -> XposedRuntimeLogger(TAG, debugEnabled).debug(message) },
            diagnostics = RuntimeDiagnosticSink(reportFailure),
            clock = SystemRuntimeClock,
            logger = XposedRuntimeLogger(TAG, debugEnabled),
            targetClassLoader = classLoader,
        )

    private const val TAG = "WA X"
}

/**
 * The production [DiagnosticSink], over the loader's existing report funnel.
 *
 * There is exactly one producer of failure reports in the module and it is the loader's, which
 * persists to disk, adds the version and package, and keeps the in-memory list the diagnostics
 * dialog reads. This class is the seam that lets a feature reach it without that funnel becoming
 * public API.
 */
class RuntimeDiagnosticSink(
    private val report: (featureId: String, code: FailureCode, message: String?, stage: String?) -> FeatureFailureReport,
) : DiagnosticSink {
    override fun report(
        featureId: String,
        code: FailureCode,
        message: String?,
        stage: String?,
    ): FeatureFailureReport = report(featureId, code, message, stage)

    override fun reportThrowable(
        featureId: String,
        throwable: Throwable,
        stage: String?,
    ): FeatureFailureReport =
        report(
            featureId,
            FailureCode.classify(throwable, stage),
            throwable.message,
            stage,
        )
}
