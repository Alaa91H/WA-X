package com.wax.module.activation

import android.app.ActivityManager
import android.content.Context

/**
 * Asks the platform what it is willing to say about a target's process.
 *
 * This is the one place in the package that touches Android for process state, so the rule
 * that decides what to do with the answer - see [TargetProcessObservation.classify] - stays a
 * pure function that every platform behaviour can be tested against, and this file is only the
 * query.
 *
 * The query is wrapped rather than trusted: `getRunningAppProcesses()` is one of the APIs
 * modern Android narrows, and it narrows it silently. Returning this app's own processes and no
 * others is not an answer about the target, and code that read it as one would be the exact
 * "claimed something it had no evidence for" failure this package removes.
 */
object TargetProcessObserver {
    /**
     * What can be learned about [packageName]'s process right now.
     *
     * A platform call that throws is reported as [TargetProcessObservation.UNOBSERVABLE]
     * rather than as absence: an exception is not evidence, and turning one into "not running"
     * is how a card ends up telling a user to open an app that is open.
     */
    fun observe(
        context: Context,
        packageName: String,
    ): TargetProcessObservation {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return TargetProcessObservation.UNOBSERVABLE
        val running =
            runCatching { manager.runningAppProcesses }
                .getOrNull()
                ?.map { it.processName }
                .orEmpty()
        return TargetProcessObservation.classify(
            observedProcessNames = running,
            targetPackage = packageName,
            ownPackage = context.packageName,
        )
    }
}
