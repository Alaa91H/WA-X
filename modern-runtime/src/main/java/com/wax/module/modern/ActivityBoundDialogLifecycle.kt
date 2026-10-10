package com.wax.module.modern

import java.util.concurrent.atomic.AtomicBoolean

/** Coordinates one target Activity-bound window and makes its close path idempotent. */
internal class ActivityBoundDialogLifecycle(
    private val hostActivity: Any,
    private val dispatchToMain: (() -> Unit) -> Boolean = { action -> action(); true },
    private val dismissDialog: () -> Unit,
    private val unregister: () -> Unit,
    private val onClosed: (ActivityDialogCloseReason) -> Unit = {},
    private val onCleanupFailure: (ActivityDialogCloseReason, String) -> Unit = { _, _ -> },
    private val onMainDispatchRejected: (ActivityDialogCloseReason) -> Unit = {},
) {
    private val closed = AtomicBoolean(false)

    val isActive: Boolean get() = !closed.get()

    fun onActivityStopped(activity: Any?) {
        if (activity === hostActivity) close(ActivityDialogCloseReason.HOST_STOPPED, dismiss = true)
    }

    fun onActivityDestroyed(activity: Any?) {
        if (activity === hostActivity) close(ActivityDialogCloseReason.HOST_DESTROYED, dismiss = true)
    }

    fun onDialogDismissed() {
        close(ActivityDialogCloseReason.DISMISSED, dismiss = false)
    }

    fun closeForShowFailure() {
        close(ActivityDialogCloseReason.SHOW_FAILED, dismiss = true)
    }

    fun closeForReplacement() {
        close(ActivityDialogCloseReason.REPLACED, dismiss = true)
    }

    private fun close(reason: ActivityDialogCloseReason, dismiss: Boolean) {
        if (!closed.compareAndSet(false, true)) return
        fun report(code: String) {
            try {
                onCleanupFailure(reason, code)
            } catch (_: RuntimeException) {
                // Diagnostics must never make a lifecycle callback fatal.
            }
        }
        val cleanup = {
            try {
                unregister()
            } catch (_: RuntimeException) {
                report("LIFECYCLE_UNREGISTER_FAILED")
            }
            if (dismiss) {
                try {
                    dismissDialog()
                } catch (_: RuntimeException) {
                    report("WINDOW_DISMISS_FAILED")
                }
            }
            try {
                onClosed(reason)
            } catch (_: RuntimeException) {
                report("SESSION_CLEANUP_FAILED")
            }
        }
        val dispatched = try {
            dispatchToMain(cleanup)
        } catch (_: RuntimeException) {
            false
        }
        if (!dispatched) {
            // Unregister off-main only as a last resort; never touch the Window there.
            try {
                unregister()
            } catch (_: RuntimeException) {
                report("LIFECYCLE_UNREGISTER_FAILED")
            }
            try {
                onMainDispatchRejected(reason)
            } catch (_: RuntimeException) {
                report("MAIN_DISPATCH_REJECTED")
            }
        }
    }
}

internal enum class ActivityDialogCloseReason {
    HOST_STOPPED,
    HOST_DESTROYED,
    DISMISSED,
    SHOW_FAILED,
    REPLACED,
}
