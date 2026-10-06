package com.wax.module.resolver

/**
 * The outcome of resolving one piece of WhatsApp internals.
 *
 * Replacing `null` plus `!!` with this type is the whole point of T10. A `null` return
 * cannot distinguish "this build does not have it" from "the lookup was wrong", and `!!`
 * turns either into a crash during startup. Here the four outcomes are explicit and each
 * carries enough to explain itself in a report:
 *
 * ```
 * IF the resolver matched exactly      THEN install with EXACT
 * IF it matched one candidate weakly   THEN install with LIKELY
 * IF it matched several candidates     THEN record AMBIGUOUS and do not install
 * IF it matched nothing                THEN record NOT_FOUND and do not install
 * ```
 *
 * @param T what the resolver produces, typically a `Method`, `Field` or `Class`
 */
sealed interface Resolution<out T> {
    /** Human readable explanation. Never contains user data; see `ReportRedactor`. */
    val reason: String

    /** The confidence this outcome carries. */
    val confidence: Confidence

    /** Returns the value when this is an installable [Resolved], else null. */
    fun valueOrNull(): T? = (this as? Resolved)?.value

    /** Whether a hook may be installed on the strength of this result. */
    val isInstallable: Boolean
        get() = Confidence.mayInstall(confidence)

    /**
     * A single match.
     *
     * @param value the resolved member
     * @param how the candidates were matched, used to pick [confidence] when not given
     */
    data class Resolved<out T>(
        val value: T,
        override val confidence: Confidence = Confidence.EXACT,
        val how: String = "exact",
    ) : Resolution<T> {
        override val reason: String get() = "resolved via $how"
    }

    /**
     * Nothing matched on this build.
     *
     * This is the normal outcome after a WhatsApp update renames something, and it is a
     * reportable state rather than an error: the feature is simply unavailable here.
     */
    data class NotFound(
        override val reason: String = "no match on this build",
        val searched: List<String> = emptyList(),
    ) : Resolution<Nothing> {
        override val confidence: Confidence get() = Confidence.NONE
    }

    /**
     * More than one candidate matched.
     *
     * Deliberately carries no value: picking one would be a guess, and a hook on the wrong
     * method is worse than no hook. The candidate names are recorded so the resolver can be
     * tightened.
     */
    data class Ambiguous(
        val candidates: List<String>,
        override val reason: String = "multiple candidates matched",
    ) : Resolution<Nothing> {
        override val confidence: Confidence get() = Confidence.AMBIGUOUS
    }

    /**
     * The target exists in principle but cannot be used on this build, for example the API
     * level is too low or a signature is incompatible.
     *
     * Distinct from [NotFound] because the remedy differs: this needs a code change, not a
     * rename to follow.
     */
    data class Incompatible(
        override val reason: String,
        val detail: String? = null,
    ) : Resolution<Nothing> {
        override val confidence: Confidence get() = Confidence.NONE
    }

    companion object {
        /** Wraps a value that was found on a strong signal. */
        fun <T> exact(
            value: T,
            how: String = "exact",
        ): Resolution<T> = Resolved(value, Confidence.EXACT, how)

        /**
         * Wraps a value that was found through a heuristic.
         *
         * Named `likely` rather than `inferred` because the distinction that matters at the
         * call site is "weaker evidence", not "we deduced something".
         */
        fun <T> likely(
            value: T,
            how: String,
        ): Resolution<T> = Resolved(value, Confidence.LIKELY, how)

        /** Not found, listing what was searched for. */
        fun notFound(vararg searched: String): Resolution<Nothing> = NotFound(searched = searched.toList())

        /**
         * Chooses between the outcomes of a lookup that produced [candidates].
         *
         * Centralising this is what makes "ambiguous never installs" enforceable: every
         * resolver goes through here rather than each one deciding for itself.
         *
         * @param candidates the matches found, already resolved to their identities
         * @param exact when true the lookup used a strong signal, otherwise a heuristic
         */
        fun <T> ofCandidates(
            candidates: List<T>,
            exact: Boolean,
            how: String,
            describe: (T) -> String,
        ): Resolution<T> =
            when (candidates.size) {
                0 -> {
                    NotFound(searched = listOf(how))
                }

                1 -> {
                    if (exact) {
                        Resolved(candidates[0], Confidence.EXACT, how)
                    } else {
                        Resolved(candidates[0], Confidence.LIKELY, how)
                    }
                }

                else -> {
                    Ambiguous(candidates.map(describe))
                }
            }

        /** Lifts a nullable legacy result into a typed one, preserving current behaviour. */
        fun <T : Any> fromNullable(
            value: T?,
            reason: String = "no match on this build",
        ): Resolution<T> = if (value == null) NotFound(reason) else Resolved(value, Confidence.EXACT, reason)
    }
}
