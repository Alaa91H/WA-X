package com.wax.module.activation

/**
 * What the Manager was able to learn about a target's process.
 *
 * The tri-state is the point. An earlier shape of this model carried a boolean
 * ("process observed running"), and the two states it could not tell apart were *looked and it
 * is not there* and *could not look*. Collapsing them means the interface claims a process is
 * absent when nothing established that, which is the same class of error as claiming LSPosed is
 * disabled because a resolver failed: a claim with no evidence behind it.
 *
 * [UNOBSERVABLE] is the honest third answer, and it has consequences the interface must
 * respect - a target whose process state could not be observed is never reported as "not
 * running", and a missing heartbeat never becomes a failure code on its own.
 */
enum class TargetProcessObservation {
    /** A process for the target was observed alive. */
    RUNNING,

    /** The process list was authoritative and did not contain the target. */
    NOT_RUNNING,

    /**
     * The platform returned only this app's own processes, so the list says nothing about the
     * target either way.
     */
    UNOBSERVABLE,
    ;

    /** Whether the target was seen alive. */
    val isRunning: Boolean get() = this == RUNNING

    /** Whether absence was actually established rather than merely not checked. */
    val isAuthoritative: Boolean get() = this == RUNNING || this == NOT_RUNNING

    companion object {
        /**
         * Classifies what a process list did and did not show.
         *
         * The question the boolean version could not answer is whether the list is worth
         * believing, and the platform does not say. It is answered here instead, from the list
         * itself: a list containing a process belonging to some other package demonstrably
         * reaches beyond this app, so its silence about [targetPackage] is evidence. A list
         * containing only this app's own processes is the stock restricted result, and its
         * silence is not evidence of anything.
         *
         * [ownPackage] is passed in rather than read from a `Context` so the rule is a pure
         * function of its arguments and can be tested for every platform behaviour rather than
         * reasoned about on a device.
         */
        fun classify(
            observedProcessNames: Collection<String>,
            targetPackage: String,
            ownPackage: String,
        ): TargetProcessObservation {
            if (observedProcessNames.any { it.belongsTo(targetPackage) }) return RUNNING
            val reachesBeyondThisApp = observedProcessNames.any { !it.belongsTo(ownPackage) }
            return if (reachesBeyondThisApp) NOT_RUNNING else UNOBSERVABLE
        }

        /** Whether [processName] is [packageName] itself or one of its secondary processes. */
        private fun String.belongsTo(packageName: String): Boolean = this == packageName || startsWith("$packageName:")
    }
}
