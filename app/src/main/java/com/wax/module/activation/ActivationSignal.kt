package com.wax.module.activation

/**
 * Where a statement about activation came from, ordered by how much it is worth.
 *
 * The banner this replaces answered one question with one bit. The reason a bit was wrong is
 * not that the boolean was computed badly, it is that the question had been collapsed before
 * anything computed it: "LSPosed is running", "the module is enabled", "the app is in scope",
 * "the target is running", "the module was injected", "the module is working" are six facts
 * with six different fixes, and one boolean cannot hold them apart.
 *
 * So the signal is not a boolean any more, it is a source, and the source carries its own
 * authority. That is the whole hierarchy:
 *
 * 1. [FRAMEWORK_EVIDENCE] - the module's own entry point executed in *this* target's process.
 *    Nothing else can produce it, and it cannot be produced by the module being merely
 *    installed. This is the only source that may assert a target is injected.
 * 2. [TARGET_PROCESS_OBSERVATION] - the manager observed the target's process itself. It says
 *    the app is running, and says nothing at all about the module.
 * 3. [PACKAGE_METADATA] - what the package manager can be asked: is the app installed, which
 *    version, which processes it declares. Says nothing about the module.
 * 4. [LEGACY_SELF_HOOK_SIGNAL] - the self-hook into the module's *own* process. It proves the
 *    framework loaded the module somewhere, and it is the source this program exists to
 *    demote. It is kept, and it is still true when it is true; what it is no longer allowed to
 *    do is stand in for any of the three above.
 * 5. [NONE] - nothing was observed. This is a real answer, not a missing one: "nothing has
 *    reported" is the state in which the honest response is to say so rather than to guess.
 *
 * The ordering is a property of the enum rather than of the code that reads it, so a consumer
 * that picks the strongest signal it has cannot accidentally pick a weaker one, and a new
 * signal has to be placed deliberately.
 */
enum class ActivationSignal(
    /** Higher wins when more than one source is available for the same question. */
    val authority: Int,
) {
    /** The module's entry point ran inside the target's own process. */
    FRAMEWORK_EVIDENCE(4),

    /** The manager observed the target's process, with no module involvement. */
    TARGET_PROCESS_OBSERVATION(3),

    /** The package manager answered: installed, version, declared processes. */
    PACKAGE_METADATA(2),

    /**
     * The self-hook into the module's own process.
     *
     * Retained deliberately. It answers a real question - "did a framework load WA X at all?"
     * - and it is the only signal available when no target has ever started. What changed is
     * its rank: it can no longer decide whether a target is injected, or whether the module is
     * working, because it is evidence about a different process than the one the user is
     * asking about.
     */
    LEGACY_SELF_HOOK_SIGNAL(1),

    /** Nothing has been observed. */
    NONE(0),
    ;

    /** Whether this source can prove the module's code is executing inside a target. */
    val canProveInjection: Boolean get() = this == FRAMEWORK_EVIDENCE

    companion object {
        /** The strongest of [signals], or [NONE] for an empty collection. */
        fun strongest(signals: Collection<ActivationSignal>): ActivationSignal = signals.maxByOrNull { it.authority } ?: NONE
    }
}
