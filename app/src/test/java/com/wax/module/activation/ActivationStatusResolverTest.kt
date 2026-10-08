package com.wax.module.activation

import com.wax.module.health.HealthFreshness
import com.wax.module.health.RuntimeFailureCode
import com.wax.module.health.SubsystemState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every failure mode #321 names, as a test.
 *
 * The issue lists nine of them: no framework, module disabled, missing scope, stopped target,
 * no injection, a DexKit failure, a stale heartbeat and a restart. Each one used to produce
 * the same sentence, and the reason a table of test cases is the right artefact here is that
 * the sentence is gone: there is no input left for which this resolver can answer with a
 * generic "not enabled in LSPosed".
 *
 * [aGenericActivationFailureIsNeverProducedWithoutProof] is the gate itself, and it is written
 * as a property over every combination rather than as one case, because "we fixed the DexKit
 * message" is a claim about one branch and the defect was structural.
 */
class ActivationStatusResolverTest {
    private val now = 1_000_000L
    private val boot = "boot-1"

    private fun heartbeat(
        state: SubsystemState = SubsystemState.READY,
        code: RuntimeFailureCode? = null,
        ageMillis: Long = 0L,
        bootId: String = boot,
        pid: Int = 4242,
        session: String = "target-1",
    ) = TargetHeartbeat(
        packageName = WHATSAPP,
        processName = WHATSAPP,
        pid = pid,
        bootId = bootId,
        moduleSessionId = "module-1",
        targetSessionId = session,
        stage = "hooks.essential",
        state = state,
        failureCode = code,
        timestampMillis = now - ageMillis,
        moduleVersion = "1.2.0-beta.3",
        targetVersionName = "2.26.39.78",
    )

    private fun resolve(
        installed: Boolean = true,
        process: TargetProcessObservation = TargetProcessObservation.UNOBSERVABLE,
        heartbeat: TargetHeartbeat? = null,
        legacy: Boolean = false,
    ) = ActivationStatusResolver.resolve(
        observation = ActivationObservation(WHATSAPP, installed, process, heartbeat, legacy),
        nowMillis = now,
        currentBootId = boot,
    )

    // ------------------------------------------------------------ the nine simulations

    /** No framework: nothing reported, no self-hook, and the platform would not say. */
    @Test
    fun noFrameworkIsReportedAsAbsenceOfEvidenceNotAsADisabledModule() {
        val status = resolve(installed = true, legacy = false)

        assertEquals(ActivationState.UNKNOWN, status.state)
        assertNull(
            "Nothing observed is not a failure. A code here is what puts 'LSPosed is disabled' " +
                "on the screen for a reason the module never established.",
            status.failureCode,
        )
        assertEquals(ActivationAction.OPEN_TARGET, status.action)
        assertFalse(status.mayReportFrameworkActivationFailure)
    }

    /** Module disabled: a framework loaded WA X nowhere and no target reported. */
    @Test
    fun aDisabledModuleIsNotReportedAsAProvenScopeOrInjectionFailure() {
        val status = resolve(legacy = false, process = TargetProcessObservation.NOT_RUNNING)

        assertEquals(ActivationState.NOT_RUNNING, status.state)
        assertNull(
            "No framework, no target, no heartbeat: the module cannot prove which of the three " +
                "conditions failed, so it must not name one.",
            status.failureCode,
        )
    }

    /** Missing scope: the framework is demonstrably loaded and the target is demonstrably running. */
    @Test
    fun aRunningTargetWithoutTheModuleIsNamedAsInjectionNotObserved() {
        val status = resolve(process = TargetProcessObservation.RUNNING, legacy = true)

        assertEquals(ActivationState.RUNNING_NOT_INJECTED, status.state)
        assertEquals(
            "A framework loaded WA X into its own process and the target's process exists, so " +
                "injection into it was not observed. That is the one case that may carry a " +
                "framework-activation code, because it is proven.",
            RuntimeFailureCode.INJECTION_NOT_OBSERVED,
            status.failureCode,
        )
        assertEquals(ActivationAction.ENABLE_IN_FRAMEWORK, status.action)
        assertTrue(status.mayReportFrameworkActivationFailure)
    }

    /**
     * Missing scope with no proof of a framework.
     *
     * The target is running and nothing reported, but the self-hook is absent too - so either no
     * framework exists or this is not the process a framework would load into. The advice is the
     * same and the diagnosis is not asserted.
     */
    @Test
    fun aRunningTargetWithoutEvidenceOfAFrameworkCarriesNoFailureCode() {
        val status = resolve(process = TargetProcessObservation.RUNNING, legacy = false)

        assertEquals(ActivationState.RUNNING_NOT_INJECTED, status.state)
        assertNull(status.failureCode)
        assertFalse(status.mayReportFrameworkActivationFailure)
    }

    /** Stopped target: the process list was authoritative and did not contain it. */
    @Test
    fun aStoppedTargetIsReportedAsNotRunning() {
        val status = resolve(process = TargetProcessObservation.NOT_RUNNING)

        assertEquals(ActivationState.NOT_RUNNING, status.state)
        assertEquals(ActivationAction.OPEN_TARGET, status.action)
        assertEquals(ActivationSignal.PACKAGE_METADATA, status.signal)
    }

    /** No injection, with an explicit fresh heartbeat from a previous boot: not evidence. */
    @Test
    fun aHeartbeatFromBeforeTheLastRebootIsNotEvidenceAboutThisBoot() {
        val status =
            resolve(
                process = TargetProcessObservation.NOT_RUNNING,
                heartbeat = heartbeat(bootId = "boot-0"),
            )

        assertEquals(ActivationState.NOT_RUNNING, status.state)
        assertEquals(ActivationSignal.PACKAGE_METADATA, status.signal)
        assertNull("A heartbeat from another boot cannot carry a code about this one.", status.failureCode)
    }

    /** A DexKit failure: recorded, named, and never presented as the framework being disabled. */
    @Test
    fun aDexKitFailureIsReportedUnderItsOwnCode() {
        val status = resolve(heartbeat = heartbeat(state = SubsystemState.FAILED, code = RuntimeFailureCode.DEXKIT_INIT_FAILED))

        assertEquals(ActivationState.FAILED, status.state)
        assertEquals(RuntimeFailureCode.DEXKIT_INIT_FAILED, status.failureCode)
        assertEquals(ActivationAction.OPEN_DIAGNOSTICS, status.action)
        assertFalse(
            "This is the exact case M00 recorded: the engine failed, and the old interface showed " +
                "'Module enabled'. It must not be allowed to say the framework is not enabled.",
            status.mayReportFrameworkActivationFailure,
        )
    }

    /** A stale heartbeat: injection happened, the report is too old to describe the target now. */
    @Test
    fun aStaleHeartbeatStopsClaimingToDescribeTheTarget() {
        val status =
            resolve(
                heartbeat = heartbeat(ageMillis = HealthFreshness.FRESH_UNTIL_MILLIS + 1L),
            )

        assertEquals(ActivationState.UNKNOWN, status.state)
        assertEquals(HealthFreshness.STALE, status.freshness)
        assertEquals(
            "Starting the app is what produces new evidence; telling the user to fix the scope " +
                "when the only problem is that nothing has reported since is the old defect.",
            ActivationAction.OPEN_TARGET,
            status.action,
        )
    }

    /** An expired heartbeat: the same, one threshold later, and definitely not green. */
    @Test
    fun anExpiredHeartbeatIsNotGreen() {
        val status =
            resolve(
                heartbeat = heartbeat(ageMillis = HealthFreshness.EXPIRED_AFTER_MILLIS + 1L),
            )

        assertEquals(ActivationState.UNKNOWN, status.state)
        assertEquals(HealthFreshness.EXPIRED, status.freshness)
        assertFalse(status.state.isInjected)
    }

    /**
     * A restart.
     *
     * A new process with a new session is a different process, and the interface has to be able
     * to tell that from a continuing one without waiting for the clock to age the record past
     * its thresholds.
     */
    @Test
    fun aRestartIsRecognisedAsANewSessionEvenWhileTheOldRecordIsStillFresh() {
        val first = heartbeat(pid = 1000, session = "target-1")
        val second = heartbeat(pid = 2000, session = "target-2")

        assertFalse("A new pid is a new process.", first.isSameSessionAs(second))
        assertTrue("The same pid and session is the same process.", first.isSameSessionAs(first))
        assertEquals(ActivationState.READY, resolve(heartbeat = second).state)
    }

    // ------------------------------------------------------------ the gate

    /**
     * Gate A of #321, as a property over every combination the model can express.
     *
     * A forbidden message may only ever be reported for the single code that can be proven, and
     * every other code must reach the card under its own name.
     */
    @Test
    fun aGenericActivationFailureIsNeverProducedWithoutProof() {
        val codes = RuntimeFailureCode.entries
        val beats = listOf(null, heartbeat()) + codes.map { heartbeat(state = SubsystemState.FAILED, code = it) }

        for (installed in listOf(true, false)) {
            for (legacy in listOf(true, false)) {
                for (process in TargetProcessObservation.entries) {
                    for (beat in beats) {
                        val status = resolve(installed = installed, process = process, heartbeat = beat, legacy = legacy)
                        if (status.mayReportFrameworkActivationFailure) {
                            assertEquals(
                                "A framework-activation message may only ever be reported for the " +
                                    "one failure that can be proven, never for ${status.failureCode}.",
                                RuntimeFailureCode.INJECTION_NOT_OBSERVED,
                                status.failureCode,
                            )
                        }
                        if (status.failureCode != null) {
                            assertTrue(
                                "A failure code may only travel with a state that reports a proven " +
                                    "failure, but ${status.state} carried ${status.failureCode}.",
                                status.state == ActivationState.FAILED ||
                                    status.state == ActivationState.DEGRADED ||
                                    status.state == ActivationState.RUNNING_NOT_INJECTED,
                            )
                        }
                    }
                }
            }
        }
    }

    /**
     * A scope or module failure is never produced from the Manager side.
     *
     * Neither is provable from a third process, so the resolver must not be able to emit them
     * for any observation at all - which is asserted over the whole matrix rather than by
     * reading the `when`.
     */
    @Test
    fun scopeAndModuleFailuresAreNeverInferredFromAbsence() {
        for (installed in listOf(true, false)) {
            for (legacy in listOf(true, false)) {
                for (process in TargetProcessObservation.entries) {
                    val status = resolve(installed = installed, process = process, legacy = legacy)
                    assertTrue(
                        "Absence produced ${status.failureCode}, which no Manager-side observation " +
                            "can prove.",
                        status.failureCode != RuntimeFailureCode.SCOPE_MISSING &&
                            status.failureCode != RuntimeFailureCode.MODULE_DISABLED,
                    )
                }
            }
        }
    }

    /** A degraded runtime whose failure was optional stays degraded; one that was not does not. */
    @Test
    fun anOptionalFailureIsDegradedAndARequiredOneIsFailed() {
        val optional =
            resolve(
                heartbeat =
                    heartbeat(
                        state = SubsystemState.DEGRADED,
                        code = RuntimeFailureCode.OPTIONAL_FEATURE_FAILED,
                    ),
            )
        val required =
            resolve(
                heartbeat =
                    heartbeat(
                        state = SubsystemState.DEGRADED,
                        code = RuntimeFailureCode.DEXKIT_INIT_FAILED,
                    ),
            )

        assertEquals(ActivationState.DEGRADED, optional.state)
        assertEquals(
            "The aggregate ranks a non-core subsystem failure as DEGRADED because some of them are " +
                "optional; this one was not, and calling a runtime with no installed hooks " +
                "\"reduced capability\" is the soft-pedal Gate A exists to stop.",
            ActivationState.FAILED,
            required.state,
        )
    }

    /** An unloaded target is never anything other than NOT_INSTALLED, whatever else is known. */
    @Test
    fun anUninstalledTargetIsNotInstalledWhateverElseIsKnown() {
        val status = resolve(installed = false, process = TargetProcessObservation.RUNNING, heartbeat = heartbeat(), legacy = true)

        assertEquals(ActivationState.NOT_INSTALLED, status.state)
        assertEquals(ActivationAction.INSTALL_TARGET, status.action)
        assertEquals(HealthFreshness.ABSENT, status.freshness)
    }

    /** Injection is proven only by the framework's own evidence. */
    @Test
    fun onlyTheRuntimeItselfCanProveInjection() {
        assertTrue(ActivationSignal.FRAMEWORK_EVIDENCE.canProveInjection)
        assertFalse(ActivationSignal.LEGACY_SELF_HOOK_SIGNAL.canProveInjection)
        assertFalse(ActivationSignal.TARGET_PROCESS_OBSERVATION.canProveInjection)
        assertFalse(ActivationSignal.PACKAGE_METADATA.canProveInjection)
        assertFalse(ActivationSignal.NONE.canProveInjection)
    }

    /** The hierarchy is a property of the enum, so the strongest source is never a guess. */
    @Test
    fun theStrongestSignalWins() {
        assertEquals(
            ActivationSignal.FRAMEWORK_EVIDENCE,
            ActivationSignal.strongest(
                listOf(ActivationSignal.LEGACY_SELF_HOOK_SIGNAL, ActivationSignal.FRAMEWORK_EVIDENCE),
            ),
        )
        assertEquals(ActivationSignal.NONE, ActivationSignal.strongest(emptyList()))
    }

    /** The legacy signal is reported as what it is, and never as a target's state. */
    @Test
    fun theLegacySelfHookSignalIsNeverAHealthyTarget() {
        val status = ActivationStatusResolver.resolveModuleStatus(legacySelfHookSignal = true, targetStatuses = emptyList())

        assertEquals(ActivationState.RUNNING_NOT_INJECTED, status.state)
        assertEquals(ActivationSignal.LEGACY_SELF_HOOK_SIGNAL, status.signal)
        assertFalse(
            "A framework loading the module into its own process proves nothing about WhatsApp.",
            status.state.isInjected,
        )
    }

    /** A target that reported for itself outranks the legacy signal entirely. */
    @Test
    fun moduleStatusFollowsTheTargetsAndNotTheSelfHook() {
        val ready = resolve(heartbeat = heartbeat())
        val status = ActivationStatusResolver.resolveModuleStatus(legacySelfHookSignal = false, targetStatuses = listOf(ready))

        assertEquals(ActivationState.READY, status.state)
        assertEquals(ActivationAction.NONE, status.action)
    }

    /** The worst failure across targets is the one reported, not whichever came first. */
    @Test
    fun moduleStatusReportsTheWorstFailureAcrossTargets() {
        val failed =
            ActivationStatus(
                packageName = WHATSAPP_BUSINESS,
                state = ActivationState.FAILED,
                freshness = HealthFreshness.FRESH,
                failureCode = RuntimeFailureCode.ESSENTIAL_HOOK_FAILED,
                action = ActivationAction.OPEN_DIAGNOSTICS,
                signal = ActivationSignal.FRAMEWORK_EVIDENCE,
            )
        val degraded =
            ActivationStatus(
                packageName = WHATSAPP,
                state = ActivationState.DEGRADED,
                freshness = HealthFreshness.FRESH,
                failureCode = RuntimeFailureCode.OPTIONAL_FEATURE_FAILED,
                action = ActivationAction.OPEN_DIAGNOSTICS,
                signal = ActivationSignal.FRAMEWORK_EVIDENCE,
            )

        val status = ActivationStatusResolver.resolveModuleStatus(legacySelfHookSignal = true, targetStatuses = listOf(failed, degraded))

        assertEquals(ActivationState.FAILED, status.state)
        assertEquals(RuntimeFailureCode.ESSENTIAL_HOOK_FAILED, status.failureCode)
    }

    /** Two builds, two processes, two states - one must never overwrite the other. */
    @Test
    fun whatsappAndBusinessAreNeverTheSameTarget() {
        val wpp = heartbeat()
        val business =
            TargetHeartbeat(
                packageName = WHATSAPP_BUSINESS,
                processName = WHATSAPP_BUSINESS,
                pid = 9999,
                bootId = boot,
                moduleSessionId = "module-1",
                targetSessionId = "target-9",
                stage = "resolver.session",
                state = SubsystemState.FAILED,
                failureCode = RuntimeFailureCode.DEXKIT_INIT_FAILED,
                timestampMillis = now,
                moduleVersion = "1.2.0-beta.3",
            )

        assertFalse("A Business heartbeat must not read as a WhatsApp one.", wpp.targetKey == business.targetKey)
        assertEquals(
            ActivationState.READY,
            resolve(heartbeat = wpp).state,
        )
        assertEquals(
            ActivationState.FAILED,
            ActivationStatusResolver
                .resolve(
                    ActivationObservation(WHATSAPP_BUSINESS, true, TargetProcessObservation.RUNNING, business, false),
                    now,
                    boot,
                ).state,
        )
    }

    /** An observable list is trusted; a restricted one is not. This is the whole tri-state. */
    @Test
    fun anUnobservableProcessListIsNotEvidenceOfAbsence() {
        assertEquals(
            TargetProcessObservation.UNOBSERVABLE,
            TargetProcessObservation.classify(listOf("com.wax.module"), WHATSAPP, "com.wax.module"),
        )
        assertEquals(
            TargetProcessObservation.NOT_RUNNING,
            TargetProcessObservation.classify(listOf("com.wax.module", "com.android.systemui"), WHATSAPP, "com.wax.module"),
        )
        assertEquals(
            TargetProcessObservation.RUNNING,
            TargetProcessObservation.classify(listOf("com.whatsapp:business"), WHATSAPP, "com.wax.module"),
        )
        assertEquals(
            "A secondary process counts as the app running.",
            TargetProcessObservation.RUNNING,
            TargetProcessObservation.classify(listOf("com.wax.module", "com.whatsapp:business"), WHATSAPP, "com.wax.module"),
        )
    }

    private companion object {
        const val WHATSAPP = "com.whatsapp"
        const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    }
}
