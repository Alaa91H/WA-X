package com.wax.module.modern

import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlCenterTaskScopeTest {
    @Test fun acceptedPreferenceWritesFinishButUiCallbacksAreDiscardedAfterClose() {
        val executor = ManualExecutorService()
        val mainQueue = mutableListOf<Runnable>()
        var writes = 0
        var uiUpdates = 0
        var removedCallbacks = 0
        val scope = ControlCenterTaskScope(
            executor = executor,
            postToMain = { mainQueue.add(it); true },
            removeMainCallbacks = { removedCallbacks++; mainQueue.clear() },
        )

        assertTrue(scope.submit({ writes++; true }) { uiUpdates++ })
        scope.close()
        executor.runAll()

        assertEquals(1, writes)
        assertEquals(0, uiUpdates)
        assertEquals(1, removedCallbacks)
        assertTrue(executor.isShutdown)
        assertFalse(scope.submit({ writes++; true }) { uiUpdates++ })
        assertEquals(1, writes)
    }

    @Test fun aUiCallbackQueuedBeforeCloseIsRemoved() {
        val executor = ManualExecutorService()
        val mainQueue = mutableListOf<Runnable>()
        var uiUpdates = 0
        val scope = ControlCenterTaskScope(
            executor = executor,
            postToMain = { mainQueue.add(it); true },
            removeMainCallbacks = { mainQueue.clear() },
        )

        assertTrue(scope.submit({ true }) { uiUpdates++ })
        executor.runAll()
        assertEquals(1, mainQueue.size)

        scope.close()
        mainQueue.forEach(Runnable::run)

        assertEquals(0, uiUpdates)
        assertTrue(mainQueue.isEmpty())
    }

    @Test fun rejectedExecutorSubmissionIsReportedWithoutThrowing() {
        val executor = ManualExecutorService().apply { shutdown() }
        val scope = ControlCenterTaskScope(executor, { true }, {})
        assertFalse(scope.submit({ true }) {})
    }

    @Test fun acceptedWritesRemainOrderedAheadOfTheRestartBarrier() {
        val executor = ManualExecutorService()
        val order = mutableListOf<String>()
        val scope = ControlCenterTaskScope(executor, { true }, {})

        assertTrue(scope.submit({ order.add("save"); true }) {})
        assertTrue(scope.submit({ order.add("restart-barrier"); true }) {})
        scope.close()
        executor.runAll()

        assertEquals(listOf("save", "restart-barrier"), order)
    }

    private class ManualExecutorService : AbstractExecutorService() {
        private val tasks = mutableListOf<Runnable>()
        private var stopped = false

        override fun execute(command: Runnable) {
            if (stopped) throw RejectedExecutionException()
            tasks.add(command)
        }

        fun runAll() {
            while (tasks.isNotEmpty()) tasks.removeAt(0).run()
        }

        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> {
            stopped = true
            return tasks.toMutableList().also { tasks.clear() }
        }
        override fun isShutdown(): Boolean = stopped
        override fun isTerminated(): Boolean = stopped && tasks.isEmpty()
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = isTerminated
    }
}
