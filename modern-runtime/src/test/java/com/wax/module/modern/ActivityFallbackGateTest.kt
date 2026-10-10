package com.wax.module.modern

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityFallbackGateTest {
    @Test fun fallbackCanBeStartedOnlyOncePerActivityAndCanRetryAfterFailure() {
        val gate = ActivityFallbackGate()
        val activity = Any()

        assertTrue(gate.tryBegin(activity))
        assertFalse(gate.tryBegin(activity))
        gate.allowRetry(activity)
        assertTrue(gate.tryBegin(activity))
        gate.markOpened(activity)
        assertFalse(gate.tryBegin(activity))
        gate.release(activity)
        assertTrue(gate.tryBegin(activity))
    }
}
