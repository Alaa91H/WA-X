package com.wax.module.modernization

import com.wax.module.bootstrap.BootstrapStage
import com.wax.module.diagnostics.FailureCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The defects M00 confirmed, each pinned by a test with the phase that owns it.
 *
 * Every defect is registered in `docs/modernization/known-runtime-defects.json` under an id, an
 * owner phase, the evidence behind it and its current status, and this class is what keeps that
 * registry honest in both directions:
 *
 * * An **open** defect is characterised: the test asserts the CURRENT, broken behaviour, because a
 *   test asserting the desired behaviour would fail today and block the gate M00 exists to
 *   establish, and because a defect recorded only in a document is a paragraph, and a paragraph
 *   cannot fail. **The forcing function is that fixing the defect breaks its test.**
 * * A **fixed** defect keeps its test as a regression guard: the same defect, asserted the other way
 *   round. Deleting the test instead would put the code back where M00 found it with nothing left
 *   to notice.
 *
 * A failing test in this class therefore means one of three things, and the message says which: the
 * owning phase landed and this registry needs updating, a fixed defect has regressed, or an
 * untouched defect changed behaviour. Check the entry's `owner` and `status` before treating it as
 * a regression.
 *
 * The invariant that outlives the whole modernization program is
 * `selfHookIsReachableOnlyForTheModuleOwnPackage`: the self-hook must stop being the activation
 * signal, and this is the test that eventually notices if it does not.
 */
class RuntimeTruthCharacterizationTest {
    private fun readSource(relativePath: String): String {
        val candidates =
            listOf(
                "../$relativePath",
                "src/main/$relativePath",
                "app/$relativePath",
            )
        return candidates
            .map { java.io.File(it) }
            .firstOrNull { it.isFile }
            ?.readText()
            ?: error(
                "Could not locate $relativePath from ${System.getProperty("user.dir")}. " +
                    "Add the module root to this list; a characterisation test that cannot read " +
                    "the code it characterises is worse than no test.",
            )
    }

    // ---------------------------------------------------------------- M00-DEF-01

    /**
     * A failure to start the resolution engine classifies as an engine failure.
     *
     * M00 recorded the opposite as M00-DEF-01: [FailureCode.RESOLVER_INIT_FAILED] was declared,
     * translated in [com.wax.module.resolver.UserExplanation], and produced by nothing, so the one
     * failure mode the code was written for was indistinguishable from an unclassifiable one.
     * M01 made it reachable, so what was a characterisation of the defect is now a regression test
     * for the fix - and it asserts the same two things in the same two ways, because the reason the
     * original test asserted them still holds:
     *
     * * the function must actually be able to return it (asserted over the source, because
     *   unreachability is a property of the whole function rather than of one input), and
     * * one concrete failure must classify correctly.
     *
     * Deleting this test would put the code back where M00 found it with nothing to notice.
     */
    @Test
    fun aDexKitInitialisationFailureClassifiesAsAResolverInitFailure() {
        val source = readSource("java/com/wax/module/diagnostics/FailureCode.kt")
        assertTrue(
            "M00-DEF-01 has regressed: no path in classify() returns RESOLVER_INIT_FAILED, so the " +
                "code is unreachable again and a DexKit failure would report as UNEXPECTED.",
            Regex("""return\s+RESOLVER_INIT_FAILED""").containsMatchIn(source),
        )

        // The engine names itself in its own message, which is the only signal available when the
        // failure is raised from inside it rather than by the stage that called it.
        assertEquals(
            FailureCode.RESOLVER_INIT_FAILED,
            FailureCode.classify(RuntimeException("could not init DexKit"), featureMessage = "resolve"),
        )

        // A caller that knows the stage but not the message still classifies correctly.
        assertEquals(
            FailureCode.RESOLVER_INIT_FAILED,
            FailureCode.classify(RuntimeException("boo"), featureMessage = "dexkit.init"),
        )

        // And a failure that names neither is still reported as the honest unknown rather than
        // being attributed to the engine.
        assertEquals(
            FailureCode.UNEXPECTED,
            FailureCode.classify(RuntimeException("something else"), featureMessage = "resolve"),
        )
    }

    // ---------------------------------------------------------------- M00-DEF-02

    /**
     * A DexKit initialisation failure is recorded, classified, and no longer stops the bootstrap.
     *
     * M00 recorded M00-DEF-02 as "installs zero hooks and reports nothing". M01 fixed the first
     * half - the record exists before the engine is asked - and deliberately left the early
     * return in place, because *which stages are safe to continue with* was M03's decision.
     *
     * This is M03's decision, taken. There is no `return` after the engine failure any more: the
     * failure is a stage outcome, the stages that need the engine are skipped with the engine
     * named as the reason, and the stages that do not need it still run - which is what lets the
     * runtime attach, answer the Manager and report that the engine is what failed. Asserted over
     * the source because the engine needs a real dex file and an LSPosed class loader, neither of
     * which exists on a JVM unit test; the behaviour itself is covered by
     * `StageRunnerTest.anEngineFailureSkipsOnlyWhatNeedsTheEngine`.
     */
    @Test
    fun dexKitInitFailureIsClassifiedAndDoesNotStopTheBootstrap() {
        val loader = readSource("java/com/wax/module/xposed/core/FeatureLoader.kt")

        val guard =
            Regex("""if\s*\(\s*Unobfuscator\.initWithPath\([^)]*\)\s*\)""")
                .find(loader)
                ?.range
                ?: error("M03 owns this. FeatureLoader no longer guards on initWithPath failing. Update M00-DEF-02.")
        val body = loader.substring(guard.last, minOf(loader.length, guard.last + 1200))

        assertTrue(
            "M03-DEF-02 has regressed: the early return after a DexKit failure is back, so one " +
                "engine failure stops every later stage again - including the stages that never " +
                "needed the engine, which is the defect M00 recorded.",
            !Regex("""return\s*\$""", RegexOption.MULTILINE).containsMatchIn(body),
        )
        assertTrue(
            "M01-DEF-02 has regressed: the DexKit failure path records nothing again, so the " +
                "failure is silent - which is exactly what M00 recorded.",
            body.contains("StageOutcome.FAILED") && body.contains(BootstrapStage.DEX_ENGINE.name),
        )

        // The bootstrap is a declared sequence rather than a straight line, and that is the
        // property the early return used to defeat.
        assertTrue(
            "M03 owns this. FeatureLoader is not running a declared stage sequence any more.",
            loader.contains("StageRunner(") && loader.contains("runner.run("),
        )

        // Ordering, not just presence: health must exist before the first thing that can fail,
        // otherwise a failure recorded during bootstrap has nowhere to go - the root cause M00
        // identified for this defect.
        val healthStart = loader.indexOf("RuntimeHealth.beginForTarget")
        val runnerStart = loader.indexOf("StageRunner(health)")
        val dexKitInit = loader.indexOf("Unobfuscator.initWithPath")
        assertTrue("M03 owns this. The DexKit initialisation is not guarded at all.", dexKitInit > 0)
        assertTrue("M03 owns this. There is no stage runner in the loader.", runnerStart > 0)
        assertTrue(
            "The health reporter must exist before the sequence runs, or a failure recorded " +
                "during bootstrap has nowhere to go - the defect M00 identified.",
            healthStart in 0 until runnerStart,
        )
    }

    // ---------------------------------------------------------------- M00-DEF-03

    /**
     * The activation signal is false without the self-hook, and cannot be anything else.
     *
     * What used to be `isXposedEnabled` is now `isLegacySelfHookSignal`, and the rename is the
     * fix M00-DEF-03 asked for: a hook that replaced `isXposedEnabled` was, by its name,
     * claiming to make the module enabled. The value is unchanged - deliberately - because the
     * hook that replaces it with a constant is still the only thing that can make it true, and
     * the next test is what keeps that honest.
     */
    @Test
    fun activationSignalIsFalseWithoutTheSelfHook() {
        assertTrue(
            "Wall-clock milliseconds must be non-zero, otherwise this test proves nothing.",
            System.currentTimeMillis() != 0L,
        )

        val source = readSource("java/com/wax/module/ModuleApplication.kt")
        assertTrue(
            "M02 owns this. ModuleApplication.isLegacySelfHookSignal is no longer the " +
                "System.currentTimeMillis() placeholder, so the legacy signal can no longer be " +
                "distinguished from an absent one. Update M00-DEF-03.",
            Regex("""fun\s+isLegacySelfHookSignal\(\)\s*:\s*Boolean\s*=\s*System\.currentTimeMillis\(\)\s*==\s*0L""")
                .containsMatchIn(source),
        )

        assertTrue(
            "The name is the fix as much as the wiring is: a method called isXposedEnabled that " +
                "only reports the self-hook is the defect, whatever it is used for.",
            !Regex("""\bisXposedEnabled\b""").containsMatchIn(source),
        )
    }

    /**
     * The self-hook is reachable only for the module's own package.
     *
     * This is the invariant the entire modernization program is trying to remove: the module must
     * not learn whether it is active by observing itself. It survives M02 deliberately - if the
     * replacement constant is ever installed for any other package, this fails, and that is the
     * point.
     */
    @Test
    fun selfHookIsReachableOnlyForTheModuleOwnPackage() {
        val source = readSource("java/com/wax/module/ModuleEntryPoint.kt")

        val guard =
            Regex("""if\s*\(\s*packageName\s*==\s*BuildConfig\.APPLICATION_ID\s*\)""")
                .find(source)
                ?.range
                ?: error(
                    "M02 owns this. ModuleEntryPoint no longer gates hookSelf on the module's own " +
                        "package. Check that the self-hook did not gain another entry point.",
                )

        val body = source.substring(guard.last, minOf(source.length, guard.last + 200))
        assertTrue(
            "M02 owns this. hookSelf is no longer followed by an early return, so the guard " +
                "stopped meaning what it says. Update M00-DEF-03.",
            Regex("""hookSelf\s*\([^)]*\)\s*;?\s*return""", RegexOption.MULTILINE)
                .containsMatchIn(body),
        )

        // And the replacement it installs is a constant, over the narrowly named signal.
        assertTrue(
            "M02 owns this. The self-hook no longer installs returnConstant(true) over the " +
                "legacy signal method. Update M00-DEF-03.",
            Regex(
                """hookAllMethods\(\s*\w+\s*,\s*LEGACY_SIGNAL_METHOD\s*,\s*""" +
                    """XC_MethodReplacement\.returnConstant\(true\)""",
            ).containsMatchIn(source),
        )
        assertTrue(
            "The hooked name must exist in exactly one place, so the hook and the method it " +
                "replaces cannot drift apart with nothing failing.",
            Regex("""const\s+val\s+LEGACY_SIGNAL_METHOD\s*:\s*String\s*=\s*"isLegacySelfHookSignal"""").containsMatchIn(source),
        )
    }

    /**
     * The generic activation messages are gone from the interface.
     *
     * M00-DEF-03's symptom was a screen that collapsed six different facts into one sentence.
     * Deleting the strings is what makes that unrepeatable: a status card that cannot name a
     * state has nothing to fall back on. Asserted over the whole source tree rather than over
     * one file, because the message is what the defect looked like wherever it was rendered.
     */
    @Test
    fun noScreenFallsBackOnAGenericActivationMessage() {
        val forbidden =
            listOf(
                "whatsapp_is_not_running_or_has_not_been_activated_in_lsposed",
                "business_is_not_running_or_has_not_been_activated_in_lsposed",
                "module_disabled",
            )

        for (path in sourceFiles("java/com/wax/module/ui").plus(sourceFiles("java/com/wax/module/activation"))) {
            val text = java.io.File(path).readText()
            for (name in forbidden) {
                assertTrue(
                    "$path still refers to R.string.$name. Gate A of #321 forbids a generic " +
                        "activation message unless the failure was proven, and a screen that " +
                        "renders one is exactly the defect M02 removed.",
                    !text.contains(name),
                )
            }
        }
    }

    private fun sourceFiles(relativeRoot: String): List<String> {
        val roots =
            listOf("../$relativeRoot", "src/main/$relativeRoot", "app/$relativeRoot")
        val root = roots.map { java.io.File(it) }.firstOrNull { it.isDirectory } ?: return emptyList()
        return root
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.path }
            .toList()
    }

    // ---------------------------------------------------------------- M00-DEF-04

    /**
     * `modulePath` is assigned in exactly one place, and that place is not its point of use.
     */
    @Test
    fun modulePathIsOnlyAssignedByInitZygote() {
        val source = readSource("java/com/wax/module/ModuleEntryPoint.kt")

        val declarations = Regex("""var\s+modulePath\s*:\s*String\?\s*=\s*null""").findAll(source).count()
        assertEquals(
            "M07 owns this. modulePath changed shape. Update M00-DEF-04.",
            1,
            declarations,
        )

        val assignments =
            Regex("""modulePath\s*=""")
                .findAll(source)
                .map { it.range.first }
                .toList()
        assertEquals(
            "M03/M07 own this. modulePath now has $assignments assignments instead of one. If a " +
                "second assignment was added so the resource path can be made safe, update " +
                "M00-DEF-04 rather than re-adding the guard here.",
            1,
            assignments.size,
        )
        assertTrue(
            "M03/M07 own this. modulePath is no longer assigned from initZygote's parameter. " +
                "Update M00-DEF-04.",
            Regex("""modulePath\s*=\s*startupParam\.modulePath""").containsMatchIn(source),
        )
    }

    // ---------------------------------------------------------------- M00-DEF-05

    /**
     * Resource injection swallows every failure, so it fails silently.
     */
    @Test
    fun resourceInjectionSwallowsFailures() {
        val source = readSource("java/com/wax/module/ModuleEntryPoint.kt")

        val emptyCatches = Regex("""catch\s*\(\s*_\s*:\s*[\w.]+\s*\)\s*\{\s*\}""").findAll(source).count()
        assertTrue(
            "M03 owns this. ModuleEntryPoint has no empty catch left, so resource injection " +
                "either reports or does not exist. Update M00-DEF-05.",
            emptyCatches > 0,
        )
    }

    /**
     * A runtime probe must never be sent before Home registered its response receiver.
     * On Android 17 the injected runtime can be starting while the Manager Fragment is
     * inflating: the old onCreateView broadcast raced the later onStart registration.
     */
    @Test
    fun managerRegistersForActivationRepliesBeforeSendingTheProbe() {
        val source = readSource("java/com/wax/module/ui/fragments/HomeFragment.kt")
        val onStart = source.substringAfter("override fun onStart() {").substringBefore("private fun scheduleActivationProbeRetries()")
        val onCreateView = source.substringAfter("override fun onCreateView(").substringBefore("private fun startCardAnimations()")
        val onStop = source.substringAfter("override fun onStop() {").substringBefore("override fun onCreateView(")
        val receiver = source.substringAfter("private val statusReceiver =").substringBefore("override fun onStart() {")

        assertFalse("Cold-start probes must not be sent during view inflation.", onCreateView.contains("checkWpp("))
        assertTrue("Missing receiver registration.", onStart.contains("ContextCompat.registerReceiver("))
        assertTrue(
            "A reply can race the receiver if the first probe is sent before registration.",
            onStart.indexOf("statusReceiverRegistered = true") in 0 until onStart.indexOf("checkWpp(requireActivity())"),
        )
        assertTrue("Probe retry must be scheduled for slow WhatsApp bootstrap.", onStart.contains("scheduleActivationProbeRetries()"))
        assertTrue("The delayed probes must be cancelled off-screen.", onStop.contains("pendingActivationProbes.clear()"))
        assertTrue("An accepted reply must re-render both target and module cards.", receiver.contains("renderActivation()"))
        assertTrue("Malformed or missing reports cannot be silently ignored.", receiver.contains("Log.w("))
    }
}
