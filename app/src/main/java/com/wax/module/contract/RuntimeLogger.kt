package com.wax.module.contract

/**
 * Where a feature writes.
 *
 * One interface for three destinations, because today there are three and none of them is
 * reachable from a test. `Feature.log` and `Feature.logDebug` call `XposedBridge.log` and
 * `android.util.Log` directly, one hundred and fifteen call sites, and neither class exists on a
 * JVM test's classpath - which is why the whole `StageRunner` has to route its own logging through
 * a separate sink just to be testable.
 *
 * [debug] is separate from [info] because the destinations differ and collapsing them is how
 * verbose logging ends up in a user's log by default. An implementation may drop debug output
 * entirely; a feature cannot tell.
 */
interface RuntimeLogger {
    /** Something worth recording. */
    fun info(message: String)

    /** Something only useful while developing. May be dropped. */
    fun debug(message: String)

    /** Something went wrong. [throwable] is optional because not every failure has one. */
    fun error(
        message: String,
        throwable: Throwable? = null,
    )
}
