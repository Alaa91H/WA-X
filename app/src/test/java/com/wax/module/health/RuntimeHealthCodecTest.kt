package com.wax.module.health

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The codec, tested for the two things that actually matter at runtime: a document this
 * module wrote reads back identically, and a document that is damaged in any specific way
 * still yields everything that was intact.
 */
class RuntimeHealthCodecTest {
    private val sessions =
        RuntimeSessions(
            bootId = "boot-7",
            moduleSessionId = "module-7",
            targetSessionId = "target-7",
        )

    private fun snapshot(
        overall: SubsystemState = SubsystemState.DEGRADED,
        failureCode: RuntimeFailureCode? = RuntimeFailureCode.DEXKIT_INIT_FAILED,
    ): RuntimeHealthSnapshot =
        RuntimeHealthSnapshot.of(
            sessions = sessions,
            timestampMillis = 1_700_000_000_000L,
            packageName = "com.whatsapp.w4b",
            processName = "com.whatsapp.w4b",
            pid = 1234,
            moduleVersion = "1.2.0-beta.1",
            targetVersionName = "2.26.40.21",
            targetVersionCode = 2_264_021L,
            androidSdk = 35,
            states =
                RuntimeSubsystem.independent.associateWith { subsystem ->
                    if (subsystem == RuntimeSubsystem.DEXKIT) SubsystemState.FAILED else SubsystemState.READY
                },
            featureSummary = FeatureSummary(essential = 4, optional = 9, ready = 12, degraded = 0, failed = 1),
            overallState = overall,
            failureCode = failureCode,
            failureMessage = "Unobfuscator.initWithPath returned false",
        )

    private fun event(
        status: HealthEventStatus = HealthEventStatus.FAILURE,
        code: RuntimeFailureCode? = RuntimeFailureCode.DEXKIT_INIT_FAILED,
    ): HealthEvent =
        HealthEvent(
            subsystem = RuntimeSubsystem.DEXKIT,
            componentId = "resolver.engine.init",
            status = status,
            startedAtMillis = 1_500L,
            durationMillis = 420L,
            sessions = sessions,
            failureCode = code,
        )

    @Test
    fun aWrittenDocumentReadsBackIdentically() {
        val current = snapshot()
        val encoded = RuntimeHealthCodec.encode(current, lastKnownGood = null, history = listOf(event()))

        val decoded = RuntimeHealthCodec.decode(encoded)
        val restored = decoded.current!!
        assertTrue(encoded.contains(RuntimeHealthCodec.SCHEMA))
        assertEquals(current.targetKey, restored.targetKey)
        assertEquals(current.bootId, restored.bootId)
        assertEquals(current.moduleSessionId, restored.moduleSessionId)
        assertEquals(current.targetSessionId, restored.targetSessionId)
        assertEquals(current.timestampMillis, restored.timestampMillis)
        assertEquals(current.pid, restored.pid)
        assertEquals(current.targetVersionName, restored.targetVersionName)
        assertEquals(current.targetVersionCode, restored.targetVersionCode)
        assertEquals(current.androidSdk, restored.androidSdk)
        assertEquals(current.overallState, restored.overallState)
        assertEquals(current.failureCode, restored.failureCode)
        assertEquals(current.failureMessage, restored.failureMessage)
        assertEquals(current.featureSummary, restored.featureSummary)
        assertEquals(current.subsystemStates, restored.subsystemStates)
        assertEquals(SubsystemState.FAILED, restored.dexKitState)

        assertEquals(1, decoded.history.size)
        val restoredEvent = decoded.history.first()
        assertEquals("resolver.engine.init", restoredEvent.componentId)
        assertEquals(420L, restoredEvent.durationMillis)
        assertEquals(RuntimeFailureCode.DEXKIT_INIT_FAILED, restoredEvent.failureCode)
        assertEquals(sessions.bootId, restoredEvent.sessions.bootId)
    }

    @Test
    fun unknownNamesDegradeRatherThanDiscardTheRecord() {
        val text =
            """
            {
              "current": {
                "package": "com.whatsapp",
                "process": "com.whatsapp",
                "overall": "CONFUSED",
                "failureCode": "INVENTED_FAILURE",
                "subsystems": {"DEXKIT": "MOSTLY_FINE", "FRAMEWORK": "READY"}
              },
              "history": [
                {"subsystem": "DEXKIT", "component": "a", "status": "SUCCESS"},
                {"subsystem": "NOPE", "component": "b", "status": "FAILURE", "failureCode": "DEXKIT_INIT_FAILED"}
              ]
            }
            """.trimIndent()

        val decoded = RuntimeHealthCodec.decode(text)
        assertEquals(SubsystemState.UNKNOWN, decoded.current?.overallState)
        assertEquals(RuntimeFailureCode.UNKNOWN, decoded.current?.failureCode)
        assertEquals(SubsystemState.UNKNOWN, decoded.current?.dexKitState)
        assertEquals(SubsystemState.READY, decoded.current?.frameworkState)
        // The event for a subsystem that does not exist is dropped; the valid one is kept.
        assertEquals(listOf("a"), decoded.history.map { it.componentId })
    }

    @Test
    fun aFailureWithNoCodeIsRepairedRatherThanDropped() {
        val text =
            """
            {"history": [{"subsystem": "RESOLVER", "component": "resolve", "status": "FAILURE"}]}
            """.trimIndent()

        val decoded = RuntimeHealthCodec.decode(text)
        assertEquals(1, decoded.history.size)
        assertEquals(RuntimeFailureCode.UNKNOWN, decoded.history.first().failureCode)
    }

    @Test
    fun aCodeOnASuccessfulEventIsDiscarded() {
        val text =
            """
            {"history": [{"subsystem": "RESOLVER", "component": "resolve", "status": "SUCCESS",
                          "failureCode": "RESOLVER_FAILED"}]}
            """.trimIndent()

        val decoded = RuntimeHealthCodec.decode(text)
        assertNull(decoded.history.first().failureCode)
    }

    @Test
    fun storedTextIsRedactedOnTheWayIn() {
        val text =
            """
            {"current": {"package": "com.whatsapp", "process": "com.whatsapp",
                         "failureMessage": "could not resolve for 4915112345678@s.whatsapp.net"},
             "history": [{"subsystem": "RESOLVER", "component": "resolve", "status": "FAILURE",
                          "message": "chat=4915112345678 failed"}]}
            """.trimIndent()

        val decoded = RuntimeHealthCodec.decode(text)
        assertTrue(
            "a stored identifier must not survive the read: ${decoded.current?.failureMessage}",
            decoded.current?.failureMessage?.contains("4915112345678") == false,
        )
        assertTrue(
            "an event message must not survive the read: ${decoded.history.firstOrNull()?.message}",
            decoded.history
                .first()
                .message
                ?.contains("4915112345678") == false,
        )
    }

    @Test
    fun aFractionalNumberIsReadWithoutLosingTheDocument() {
        val text = """{"current": {"package": "com.whatsapp", "process": "com.whatsapp", "pid": 42.5}}"""

        assertEquals(42, RuntimeHealthCodec.decode(text).current?.pid)
    }

    @Test
    fun anUnreadableDocumentReadsAsNothingRecorded() {
        for (text in listOf(null, "", "   ", "not json", "{", "[1,", "{\"current\": 7}")) {
            val decoded = RuntimeHealthCodec.decode(text)
            assertNull("decoding $text must not invent a snapshot", decoded.current)
            assertTrue(decoded.history.isEmpty())
        }
    }

    @Test
    fun anAggregateEventIsNotStoredAsASubsystemEvent() {
        val text = """{"history": [{"subsystem": "OVERALL_RUNTIME", "component": "runtime", "status": "FAILURE"}]}"""

        assertTrue(RuntimeHealthCodec.decode(text).history.isEmpty())
    }
}
