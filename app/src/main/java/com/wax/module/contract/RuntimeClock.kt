package com.wax.module.contract

/**
 * The time, so that nothing reads the clock directly.
 *
 * Two readings, not one, and the distinction is not pedantry: [elapsedRealtimeMillis] keeps
 * counting while the device sleeps and is what a duration must be measured with, while
 * [nowMillis] is what a record has to be dated with. A feature that measures a timeout against
 * wall-clock time measures how long the screen was off.
 *
 * Every model in this project that had to reason about time took a `() -> Long` and defaulted it
 * to the system clock, which is how the stage runner, the health reporter and the activation store
 * ended up all needing the same seam. This is that seam, named.
 */
interface RuntimeClock {
    /** Wall-clock milliseconds since the epoch. For dating records. */
    fun nowMillis(): Long

    /** Milliseconds since boot, counting sleep. For measuring durations. */
    fun elapsedRealtimeMillis(): Long
}
