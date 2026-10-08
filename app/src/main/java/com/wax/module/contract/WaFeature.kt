package com.wax.module.contract

/**
 * The contract a runtime feature is written against.
 *
 * Everything a feature needs arrives in [FeatureContext], and everything it reports leaves as a
 * [FeatureStartResult] or through the context's [DiagnosticSink]. Nothing in either signature is
 * an Android, Xposed or DexKit type, and that is the whole point: a feature written against this
 * interface can be started in a plain JVM test with fakes, on a machine with no framework
 * installed and no phone.
 *
 * The alternative is what the module did before. A feature extended an abstract class whose
 * constructor took an `android.content.SharedPreferences`, so the only way to run one was inside
 * a hooked process, which meant the only way to test one was on a device. Sixty-four features
 * were untestable for that reason alone, and the coverage record shows it: the feature classes
 * are the largest body of code in the repository at zero unit coverage.
 *
 * So this interface is deliberately narrow. A feature gets a context, returns a result, and is
 * otherwise free to do whatever it does to the target process - because that part genuinely
 * cannot be abstracted and pretending otherwise would produce a contract nobody could honour.
 */
interface WaFeature {
    /**
     * A stable identifier for this feature, used in diagnostics and in the installed set.
     *
     * It must be stable across releases and must not carry anything identifying: it is written
     * to a diagnostics document the user can share.
     */
    val featureId: String

    /**
     * Starts the feature.
     *
     * Called once per target process. A feature that finds its dependency missing should return
     * [FeatureStartResult.Skipped] naming what was missing rather than throwing, because "this
     * WhatsApp build does not have that class" is an ordinary outcome and not a failure.
     *
     * @throws Throwable allowed, and a throwable is recorded against this feature's own code
     *   rather than against the runtime as a whole.
     */
    fun start(context: FeatureContext): FeatureStartResult
}
