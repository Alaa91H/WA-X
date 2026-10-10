package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ControlCenterSessionRegistryTest {
    @Test fun repeatedOpenForTheSameActivityReusesOneSession() {
        val registry = ControlCenterSessionRegistry<Any>()
        val activity = Any()
        val session = Any()
        var creates = 0

        val first = registry.acquire("com.whatsapp", activity, { true }, {
            creates++
            session
        }, {})
        val second = registry.acquire("com.whatsapp", activity, { true }, {
            creates++
            Any()
        }, {})

        assertSame(session, first)
        assertSame(first, second)
        assertEquals(1, creates)
    }

    @Test fun recreationRetiresTheOldActivitySessionAndProtectsTheNewOne() {
        val registry = ControlCenterSessionRegistry<Any>()
        val oldActivity = Any()
        val newActivity = Any()
        val oldSession = Any()
        val newSession = Any()
        var retired = 0

        registry.acquire("com.whatsapp", oldActivity, { true }, { oldSession }, {})
        val acquired = registry.acquire("com.whatsapp", newActivity, { true }, {
            newSession
        }, { replaced ->
            assertSame(oldSession, replaced)
            retired++
        })

        registry.release("com.whatsapp", oldSession)
        assertSame(newSession, acquired)
        assertEquals(1, retired)
        assertEquals(1, registry.activeSessionCount())
    }

    @Test fun closedSessionsCanBeOpenedAgainWithoutAccumulatingEntries() {
        val registry = ControlCenterSessionRegistry<Any>()
        val activity = Any()
        repeat(4) {
            val session = registry.acquire("com.whatsapp", activity, { true }, { Any() }, {})
            registry.release("com.whatsapp", session)
        }
        assertEquals(0, registry.activeSessionCount())
    }
}
