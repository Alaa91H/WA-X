package com.wax.module.xposed.contract

import android.util.Log
import com.wax.module.contract.RuntimeLogger
import de.robv.android.xposed.XposedBridge

/**
 * Where the injected runtime writes.
 *
 * Two destinations on purpose. The framework's log is what a user with LSPosed reads, and it is
 * the only one that survives a crash; the system log is what the module's own tooling already
 * used and costs nothing to keep.
 *
 * [debug] is routed only to the framework log and is additionally gated on the `enablelogs`
 * preference, which is what `Feature.isDebug` was doing inline at two of its three call sites.
 * Centralising it means a feature cannot accidentally leave verbose logging on.
 */
class XposedRuntimeLogger(
    private val tag: String,
    private val debugEnabled: () -> Boolean,
) : RuntimeLogger {
    override fun info(message: String) {
        XposedBridge.log("$tag: $message")
        Log.i(tag, message)
    }

    override fun debug(message: String) {
        if (!debugEnabled()) return
        XposedBridge.log("$tag: $message")
    }

    override fun error(
        message: String,
        throwable: Throwable?,
    ) {
        XposedBridge.log("$tag: $message")
        Log.e(tag, message, throwable)
        throwable?.let { XposedBridge.log(it) }
    }
}
