package com.wax.module.activation

import com.wax.module.health.HealthFreshness
import com.wax.module.health.RuntimeFailureCode
import com.wax.module.health.SubsystemState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The heartbeat's wire format, tested in both directions.
 *
 * This payload crosses a process boundary, so the reader is the part that matters: a broadcast
 * arrives from code this package does not control, may be truncated, may be produced by a
 * different module version, or may not be a heartbeat at all. Every one of those has to read as
 * "no evidence" rather than as an exception inside a `BroadcastReceiver`.
 */
class TargetHeartbeatCodecTest {
    private fun heartbeat(
        state: SubsystemState = SubsystemState.READY,
        code: RuntimeFailureCode? = null,
        targetVersion: String? = "2.26.39.78",
    ) = TargetHeartbeat(
        packageName = "com.whatsapp",
        processName = "com.whatsapp",
        pid = 4242,
        bootId = "boot-1234",
        moduleSessionId = "module-1",
        targetSessionId = "target-7",
        stage = "hooks.essential",
        state = state,
        failureCode = code,
        timestampMillis = 1_700_000_000_000L,
        moduleVersion = "1.2.0-beta.3",
        targetVersionName = targetVersion,
    )

    @Test
    fun aHeartbeatSurvivesTheRoundTrip() {
        val original = heartbeat(state = SubsystemState.FAILED, code = RuntimeFailureCode.DEXKIT_INIT_FAILED)

        val decoded = TargetHeartbeatCodec.decode(TargetHeartbeatCodec.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun aSecondaryProcessSurvivesTheRoundTrip() {
        val original = heartbeat().copy(processName = "com.whatsapp:business", targetSessionId = "target-8")

        val decoded = TargetHeartbeatCodec.decode(TargetHeartbeatCodec.encode(original))

        assertEquals("com.whatsapp:business", decoded?.processName)
        assertEquals("com.whatsapp|com.whatsapp:business", decoded?.targetKey)
    }

    @Test
    fun anAbsentTargetVersionIsOptionalRatherThanFatal() {
        val decoded = TargetHeartbeatCodec.decode(TargetHeartbeatCodec.encode(heartbeat(targetVersion = null)))

        assertNull(decoded?.targetVersionName)
        assertNotNull(decoded)
    }

    @Test
    fun aNullOrEmptyPayloadIsNoEvidence() {
        assertNull(TargetHeartbeatCodec.decode(null))
        assertNull(TargetHeartbeatCodec.decode(""))
        assertNull(TargetHeartbeatCodec.decode("not json at all"))
    }

    @Test
    fun aDocumentFromAnotherSchemaIsRejected() {
        val text = TargetHeartbeatCodec.encode(heartbeat()).replace(TargetHeartbeatCodec.SCHEMA, "wax.m99.other/9")

        assertNull(TargetHeartbeatCodec.decode(text))
    }

    @Test
    fun aMissingRequiredFieldRejectsTheWholeRecord() {
        val text = TargetHeartbeatCodec.encode(heartbeat()).replace("\"pid\":4242", "\"pid\":null")

        assertNull(
            "A heartbeat without a process id cannot be attributed, and a partial record must " +
                "not be read as a complete one.",
            TargetHeartbeatCodec.decode(text),
        )
    }

    @Test
    fun anUndatedRecordIsRejected() {
        val text = TargetHeartbeatCodec.encode(heartbeat()).replace("\"timestamp\":1700000000000", "\"timestamp\":0")

        assertNull(TargetHeartbeatCodec.decode(text))
    }

    @Test
    fun anUnknownStateNameRejectsTheRecordRatherThanGuessing() {
        val text = TargetHeartbeatCodec.encode(heartbeat()).replace("\"state\":\"READY\"", "\"state\":\"BRILLIANT\"")

        assertNull(
            "The model's own 'nothing reported' value is not a substitute for an unknown one: " +
                "reading a state this version cannot describe would put an invented claim on screen.",
            TargetHeartbeatCodec.decode(text),
        )
    }

    @Test
    fun anUnknownFailureCodeIsDroppedButTheRecordIsKept() {
        val text =
            TargetHeartbeatCodec
                .encode(heartbeat(state = SubsystemState.FAILED, code = RuntimeFailureCode.RESOLVER_FAILED))
                .replace("\"code\":\"RESOLVER_FAILED\"", "\"code\":\"A_CODE_FROM_THE_FUTURE\"")

        val decoded = TargetHeartbeatCodec.decode(text)

        assertNotNull("A failure name this version cannot describe still proves a failure happened.", decoded)
        assertNull(decoded?.failureCode)
        assertEquals(SubsystemState.FAILED, decoded?.state)
    }

    @Test
    fun aFailureCodeOnAWorkingSubsystemIsRefusedAtConstruction() {
        val failure =
            runCatching { heartbeat(state = SubsystemState.READY, code = RuntimeFailureCode.RESOLVER_FAILED) }

        assertTrue(
            "A healthy subsystem cannot carry a failure; the codec would then publish a record " +
                "that says both things at once.",
            failure.isFailure,
        )
    }

    @Test
    fun anUnreportedSubsystemCannotProduceAHeartbeat() {
        val failure =
            runCatching {
                heartbeat().copy(state = SubsystemState.UNKNOWN, failureCode = null)
            }

        assertTrue(
            "A heartbeat whose only content is 'nothing reported' is not evidence of anything.",
            failure.isFailure,
        )
    }

    @Test
    fun anOversizedStringIsRefused() {
        val text = TargetHeartbeatCodec.encode(heartbeat()).replace("hooks.essential", "x".repeat(400))

        assertNull(
            "The payload's size is decided by the sending process, so no single field may be " +
                "allowed to grow without bound.",
            TargetHeartbeatCodec.decode(text),
        )
    }

    @Test
    fun freshnessAndAgeComeFromTheRecordRatherThanTheReader() {
        val record = heartbeat().copy(timestampMillis = 1_000_000L)

        assertEquals(0L, record.ageMillis(1_000_000L))
        assertEquals(HealthFreshness.FRESH, record.freshnessAt(1_000_000L))
        assertEquals(HealthFreshness.STALE, record.freshnessAt(1_000_000L + HealthFreshness.FRESH_UNTIL_MILLIS + 1))
        assertEquals(
            HealthFreshness.EXPIRED,
            record.freshnessAt(1_000_000L + HealthFreshness.EXPIRED_AFTER_MILLIS + 1),
        )
    }
}
