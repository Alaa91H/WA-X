package com.wax.module.modern

import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the Control Center's asynchronous preference writes and their UI replies.
 * Closing drains accepted saves, rejects new ones and drops queued UI callbacks.
 */
internal class ControlCenterTaskScope(
    private val executor: ExecutorService,
    private val postToMain: (Runnable) -> Boolean,
    private val removeMainCallbacks: () -> Unit,
    private val onUiPostFailure: () -> Unit = {},
) {
    private val closed = AtomicBoolean(false)

    @Synchronized
    fun submit(operation: () -> Boolean, onComplete: (Boolean) -> Unit): Boolean {
        if (closed.get()) return false
        return try {
            executor.execute {
                val result = try {
                    operation()
                } catch (_: RuntimeException) {
                    false
                }
                if (!closed.get()) {
                    val posted = try {
                        postToMain(Runnable {
                            if (!closed.get()) onComplete(result)
                        })
                    } catch (_: RuntimeException) {
                        false
                    }
                    if (!posted && !closed.get()) onUiPostFailure()
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            false
        }
    }

    @Synchronized
    fun close() {
        if (!closed.compareAndSet(false, true)) return
        // shutdown() drains accepted persistence work; shutdownNow() could lose a user save.
        executor.shutdown()
        removeMainCallbacks()
    }
}

internal enum class ControlCenterFailureReason {
    BAD_TOKEN,
    ACTIVITY_UNAVAILABLE,
    WINDOW_CREATE_FAILED;

    companion object {
        fun fromClassName(className: String): ControlCenterFailureReason = when {
            className == "android.view.WindowManager\$BadTokenException" -> BAD_TOKEN
            className == "android.app.ActivityNotFoundException" -> ACTIVITY_UNAVAILABLE
            else -> WINDOW_CREATE_FAILED
        }
    }
}
