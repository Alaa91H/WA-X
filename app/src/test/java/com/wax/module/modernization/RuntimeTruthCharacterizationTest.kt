package com.wax.module.modernization

import com.wax.module.diagnostics.FailureCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Characterization of the defects M00 confirmed, not a specification of what they should become.
 *
 * Every test here asserts the CURRENT, broken behaviour, and each one is registered in
 * `docs/modernization/known-runtime-defects.json` under an id, an owner phase and the evidence
 * behind it. That is deliberate and it is the reason these tests are written this way:
 *
 * * If a test asserted the DESIRED behaviour it would fail now and block the gate that M00 exists
 *   to establish.
 * * If these tests were simply absent, the defects would be a paragraph in a document, and a
 *   paragraph cannot fail.
 *
 * So the defects are pinned by tests that pass. **The forcing function is that fixing a defect
 * breaks its test.** When [FailureCode.classify] starts returning [FailureCode.RESOLVER_INIT_FAILED],
 * `resolverInitFailureCodeIsDeclaredButNeverProduced` fails, and the failure is a message telling
 * the reader to update the registry rather than a mysterious regression.
 *
 * A failing test in this class therefore means one of two things, and the message says which:
 * the owning phase landed and this registry needs updating, or a genuine regression. Check the
 * `owner` field on the matching entry before treating it as a regression.
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
     * [FailureCode.RESOLVER_INIT_FAILED] is documented, translated in
     * [com.wax.module.resolver.UserExplanation], and produced by nothing.
     *
     * Asserted against the source of `classify` rather than by calling it, and that detail is
     * load-bearing. An earlier revision of this test called `classify` with a hand-picked throwable
     * and hint and asserted the result was [FailureCode.UNEXPECTED] - and it still passed after
     * `classify` had been given a branch that returns [FailureCode.RESOLVER_INIT_FAILED], because
     * the hand-picked hint did not happen to match it. A characterisation test that cannot fail
     * when the defect is fixed is worse than no test: it certifies a defect that no longer exists.
     *
     * Unreachability is a property of the whole function, not of one input, so it is asserted over
     * the function's body.
     */
    @Test
    fun resolverInitFailureCodeIsDeclaredButNeverProduced() {
        // It exists and means exactly what the registry says it means.
        assertEquals(
            "RESOLVER_INIT_FAILED",
            FailureCode.RESOLVER_INIT_FAILED.name,
        )

        val source = readSource("java/com/wax/module/diagnostics/FailureCode.kt")

        // The only mention in the whole file must be the declaration itself. Any second mention -
        // a `return RESOLVER_INIT_FAILED`, an `is` branch in the `when`, a mapping - is the fix.
        val mentions =
            Regex("""\bRESOLVER_INIT_FAILED\b""")
                .findAll(source)
                .map { it.range.first }
                .toList()
        assertEquals(
            "M01 owns this. FailureCode.kt now mentions RESOLVER_INIT_FAILED $mentions times " +
                "instead of once, so classify() can produce it and M00-DEF-01 is fixed. Update " +
                "docs/modernization/known-runtime-defects.json - do not delete this test.",
            1,
            mentions.size,
        )

        // And the symptom, stated behaviourally so the intent is on the record: a DexKit-style
        // failure is currently indistinguishable from any other unclassifiable failure.
        val actual =
            FailureCode.classify(
                RuntimeException("could not init DexKit"),
                featureMessage = "resolve",
            )
        assertEquals(
            "M01 owns this. A DexKit initialisation failure now classifies as $actual rather " +
                "than UNEXPECTED. Update M00-DEF-01.",
            FailureCode.UNEXPECTED,
            actual,
        )
    }

    // ---------------------------------------------------------------- M00-DEF-02

    /**
     * A DexKit initialisation failure installs nothing and reports nothing.
     *
     * Asserted against the source rather than by invoking it: the failure path needs a real
     * `dex2oat`-loaded dex file and an LSPosed-provided class loader, neither of which exists on
     * a JVM unit test. What can be asserted off-device is the shape of the failure path, which is
     * exactly what makes it silent.
     */
    @Test
    fun dexKitInitFailureInstallsNothingAndReportsNothing() {
        val loader = readSource("java/com/wax/module/xposed/core/FeatureLoader.kt")

        val guard =
            Regex("""if\s*\(\s*!\s*Unobfuscator\.initWithPath\([^)]*\)\s*\)""")
                .find(loader)
                ?.range
                ?: error("M01 owns this. FeatureLoader no longer guards on initWithPath failing. Update M00-DEF-02.")
        val body = loader.substring(guard.last, minOf(loader.length, guard.last + 400))

        assertTrue(
            "M01 owns this. The early return after a DexKit failure is gone. Update M00-DEF-02.",
            Regex("""return\s*$""", RegexOption.MULTILINE).containsMatchIn(body.take(200)),
        )
        assertFalse(
            "M01 owns this. The DexKit failure path now records a report, so it is no longer " +
                "silent. This is the fix; update M00-DEF-02 to describe the new behaviour.",
            Regex("""recordFailure|report|FailureCode\.""").containsMatchIn(body.take(200)),
        )
    }

    // ---------------------------------------------------------------- M00-DEF-03

    /**
     * The activation signal is false without the self-hook, and cannot be anything else.
     *
     * [com.wax.module.ModuleApplication.isXposedEnabled] is `System.currentTimeMillis() == 0L`.
     * Wall-clock milliseconds have not been zero since 1970, so unhooked it is unconditionally
     * false and the placeholder can never report a healthy runtime by itself.
     */
    @Test
    fun activationSignalIsFalseWithoutTheSelfHook() {
        assertTrue(
            "Wall-clock milliseconds must be non-zero, otherwise this test proves nothing.",
            System.currentTimeMillis() != 0L,
        )

        val source = readSource("java/com/wax/module/ModuleApplication.kt")
        assertTrue(
            "M01/A01 own this. ModuleApplication.isXposedEnabled is no longer the " +
                "System.currentTimeMillis() placeholder. That is the fix - update M00-DEF-03.",
            Regex("""fun\s+isXposedEnabled\(\)\s*:\s*Boolean\s*=\s*System\.currentTimeMillis\(\)\s*==\s*0L""")
                .containsMatchIn(source),
        )
    }

    /**
     * The self-hook is reachable only for the module's own package.
     *
     * This is the invariant the entire modernization program is trying to remove: the module must
     * not learn whether it is active by observing itself. It survives M01 and M02 deliberately -
     * if M01 ever makes a non-self-hook path install the replacement, this fails, and that is the
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
                    "M01 owns this. ModuleEntryPoint no longer gates hookSelf on the module's own " +
                        "package. Check that the self-hook did not gain another entry point.",
                )

        val body = source.substring(guard.last, minOf(source.length, guard.last + 200))
        assertTrue(
            "M01 owns this. hookSelf is no longer followed by an early return, so the guard " +
                "stopped meaning what it says. Update M00-DEF-03.",
            Regex("""hookSelf\s*\([^)]*\)\s*;?\s*return""", RegexOption.MULTILINE)
                .containsMatchIn(body),
        )

        // And the replacement it installs is a constant.
        assertTrue(
            "M01 owns this. The self-hook no longer installs returnConstant(true) over " +
                "isXposedEnabled. Update M00-DEF-03.",
            Regex(
                """hookAllMethods\(\s*\w+\s*,\s*"isXposedEnabled"\s*,\s*""" +
                    """XC_MethodReplacement\.returnConstant\(true\)""",
            ).containsMatchIn(source),
        )
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
}
