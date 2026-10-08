package com.wax.module.xposed.contract

import android.os.SystemClock
import com.wax.module.contract.RuntimeClock

/**
 * The real clock, for the injected runtime.
 *
 * The two readings are read from the two places they are actually correct in, and the comment on
 * [RuntimeClock] explains why they are not interchangeable.
 */
object SystemRuntimeClock : RuntimeClock {
    override fun nowMillis(): Long = System.currentTimeMillis()

    override fun elapsedRealtimeMillis(): Long = SystemClock.elapsedRealtime()
}
