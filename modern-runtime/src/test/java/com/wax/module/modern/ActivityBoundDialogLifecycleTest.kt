package com.wax.module.modern

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityBoundDialogLifecycleTest {
    @Test fun stoppingTheHostDismissesAndUnregistersExactlyOnce() {
        val host = Any()
        val other = Any()
        var dismissals = 0
        var unregisters = 0
        val lifecycle = ActivityBoundDialogLifecycle(
            hostActivity = host,
            dismissDialog = { dismissals++ },
            unregister = { unregisters++ },
        )

        lifecycle.onActivityStopped(other)
        assertEquals(0, dismissals)
        assertEquals(0, unregisters)

        lifecycle.onActivityStopped(host)
        lifecycle.onActivityDestroyed(host)
        assertEquals(1, dismissals)
        assertEquals(1, unregisters)
    }

    @Test fun dismissingTheDialogOnlyUnregistersTheLifecycleCallback() {
        val host = Any()
        var dismissals = 0
        var unregisters = 0
        val lifecycle = ActivityBoundDialogLifecycle(
            hostActivity = host,
            dismissDialog = { dismissals++ },
            unregister = { unregisters++ },
        )

        lifecycle.onDialogDismissed()
        lifecycle.onActivityDestroyed(host)

        assertEquals(0, dismissals)
        assertEquals(1, unregisters)
    }

    @Test fun closingIsDispatchedToMainAndHostDestroyClosesOnlyOnce() {
        val host = Any()
        val mainQueue = mutableListOf<() -> Unit>()
        var dismissals = 0
        var unregisters = 0
        val lifecycle = ActivityBoundDialogLifecycle(
            hostActivity = host,
            dispatchToMain = { action -> mainQueue.add(action); true },
            dismissDialog = { dismissals++ },
            unregister = { unregisters++ },
        )

        lifecycle.onActivityDestroyed(host)
        lifecycle.onActivityStopped(host)

        assertFalse(lifecycle.isActive)
        assertEquals(0, dismissals)
        assertEquals(0, unregisters)
        assertEquals(1, mainQueue.size)

        mainQueue.single().invoke()
        assertEquals(1, dismissals)
        assertEquals(1, unregisters)
    }

    @Test fun failedShowClosesTheLifecycleAndUnregisters() {
        val host = Any()
        var dismissals = 0
        var unregisters = 0
        val lifecycle = ActivityBoundDialogLifecycle(
            hostActivity = host,
            dispatchToMain = { it(); true },
            dismissDialog = { dismissals++ },
            unregister = { unregisters++ },
        )

        lifecycle.closeForShowFailure()
        lifecycle.onDialogDismissed()

        assertEquals(1, dismissals)
        assertEquals(1, unregisters)
    }

    @Test fun rejectedMainDispatchUnregistersWithoutDismissingOffMain() {
        val host = Any()
        var dismissals = 0
        var unregisters = 0
        var rejected = 0
        val lifecycle = ActivityBoundDialogLifecycle(
            hostActivity = host,
            dispatchToMain = { false },
            dismissDialog = { dismissals++ },
            unregister = { unregisters++ },
            onMainDispatchRejected = { rejected++ },
        )

        lifecycle.onActivityStopped(host)

        assertEquals(0, dismissals)
        assertEquals(1, unregisters)
        assertEquals(1, rejected)
    }
}
