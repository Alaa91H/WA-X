package com.wax.module.resolver

/**
 * How much a resolution result can be trusted.
 *
 * The point of this enum is that a hook must never be installed on the strength of a
 * guess. A resolver that matched several candidates, or matched by a weak signal, has
 * produced something that may silently point at the wrong method — and a hook installed
 * on the wrong method corrupts behaviour in ways that are very hard to trace back. So the
 * caller gets a score and a policy decides, rather than the resolver deciding alone.
 */
enum class Confidence {
    /**
     * A single unambiguous match on a strong signal: an exact class name, an exact method
     * name with a matching signature. Safe to install.
     */
    EXACT,

    /**
     * One match, but found through a heuristic (name shape, return type, parameter count)
     * rather than an exact identifier. Usable, and recorded so a report can show it.
     */
    LIKELY,

    /**
     * Several candidates matched. Nothing is returned because choosing would be a coin
     * flip; the ambiguity itself is the finding.
     */
    AMBIGUOUS,

    /**
     * No match on this WhatsApp build. Nothing to install.
     */
    NONE,

    ;

    /** Whether a hook may be installed on the strength of this result. */
    val isInstallable: Boolean
        get() = this == EXACT || this == LIKELY

    companion object {
        /**
         * Central threshold policy.
         *
         * Everything below [minimumToInstall] is recorded and not installed, which is what
         * keeps an ambiguous or absent resolution from becoming a crash or a wrong hook.
         */
        val minimumToInstall: Confidence = LIKELY

        /** Returns true when a result at [confidence] may be installed under current policy. */
        fun mayInstall(confidence: Confidence): Boolean = confidence.isInstallable && confidence.ordinal <= minimumToInstall.ordinal
    }
}
