package com.wax.module.activation

import com.wax.module.health.HealthFreshness
import com.wax.module.health.RuntimeFailureCode
import com.wax.module.health.SubsystemState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Regression for the on-device false "WhatsApp has not reported" banner.
 *
 * The runtime writes a heartbeat under package|process, while the Manager used to read
 * just package. A successful probe was persisted but could never be displayed.
 * Exercise the *real* Manager monitor and store together, not only the pure resolver.
 */
class ActivationMonitorTest {
    @get:Rule
    val directory = TemporaryFolder()

    private val now = 1_000_000L
    private val boot = "current-boot"

    private fun monitor(): ActivationMonitor =
        ActivationMonitor(
            store = ActivationStore(directory.newFolder("activation")),
            now = { now },
            currentBoot = { boot },
        )

    private fun heartbeat(
        packageName: String = WHATSAPP,
        processName: String = packageName,
        state: SubsystemState = SubsystemState.READY,
        failureCode: RuntimeFailureCode? = null,
        timestamp: Long = now - 1_000L,
        bootId: String = boot,
    ): TargetHeartbeat =
        TargetHeartbeat(
            packageName = packageName,
            processName = processName,
            pid = 4242,
            bootId = bootId,
            moduleSessionId = "module-session",
            targetSessionId = "target-session",
            stage = "hooks.essential",
            state = state,
            failureCode = failureCode,
            timestampMillis = timestamp,
            moduleVersion = "1.2.0-beta.8",
            targetVersionName = "2.26.39.74",
        )

    private fun status(
        monitor: ActivationMonitor,
        packageName: String = WHATSAPP,
    ): ActivationStatus =
        monitor.status(
            packageName = packageName,
            installed = true,
            process = TargetProcessObservation.UNOBSERVABLE,
            legacySelfHookSignal = true,
        )

    @Test
    fun aProbeReplyForMainProcessIsVisibleAndMarksTargetReady() {
        val monitor = monitor()
        val sent = heartbeat()
        monitor.accept(sent)

        assertEquals(sent, monitor.heartbeatFor(WHATSAPP))
        assertEquals(ActivationState.READY, status(monitor).state)
        assertEquals(HealthFreshness.FRESH, status(monitor).freshness)
    }

    @Test
    fun aRealFailureIsDisplayedInsteadOfTheLegacySelfHookSignal() {
        val monitor = monitor()
        monitor.accept(
            heartbeat(
                state = SubsystemState.DEGRADED,
                failureCode = RuntimeFailureCode.OPTIONAL_FEATURE_FAILED,
            ),
        )

        assertEquals(ActivationState.DEGRADED, status(monitor).state)
        assertEquals(RuntimeFailureCode.OPTIONAL_FEATURE_FAILED, status(monitor).failureCode)
    }

    @Test
    fun aSecondaryProcessMustNotImpersonateTheMainProcess() {
        val monitor = monitor()
        monitor.accept(heartbeat(processName = "com.whatsapp:background"))

        assertNull(monitor.heartbeatFor(WHATSAPP))
        assertEquals(ActivationState.UNKNOWN, status(monitor).state)

        monitor.accept(heartbeat())
        assertEquals(ActivationState.READY, status(monitor).state)
    }

    @Test
    fun whatsappAndBusinessKeepIndependentPrimaryProcessKeys() {
        val monitor = monitor()
        monitor.accept(heartbeat(packageName = WHATSAPP))
        assertNull(monitor.heartbeatFor(BUSINESS))
        assertEquals(ActivationState.UNKNOWN, status(monitor, BUSINESS).state)

        val business = heartbeat(packageName = BUSINESS)
        monitor.accept(business)
        assertEquals(business, monitor.heartbeatFor(BUSINESS))
        assertEquals(ActivationState.READY, status(monitor, BUSINESS).state)
    }

    @Test
    fun anExpiredHeartbeatOrDifferentBootNeverMakesTheScreenGreen() {
        val expiredMonitor = monitor()
        expiredMonitor.accept(heartbeat(timestamp = now - 200_000L))
        assertEquals(ActivationState.UNKNOWN, status(expiredMonitor).state)

        expiredMonitor.accept(heartbeat(bootId = "previous-boot"))
        assertEquals(ActivationState.UNKNOWN, status(expiredMonitor).state)
    }

    companion object {
        private const val WHATSAPP = "com.whatsapp"
        private const val BUSINESS = "com.whatsapp.w4b"
    }
}
