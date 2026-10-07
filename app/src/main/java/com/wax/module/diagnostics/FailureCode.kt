package com.wax.module.diagnostics

/**
 * Stable, machine-readable classification of a feature failure.
 *
 * The code is what tooling aggregates on, so the names and their meaning are part of
 * the module's contract: they may be added to, but existing codes must never be
 * repurposed. [UNEXPECTED] is the honest answer whenever nothing more specific applies,
 * because guessing a specific cause is worse than admitting the category is unknown.
 */
enum class FailureCode {
    /** A resolver ran but matched nothing on this WhatsApp build. */
    RESOLVER_NOT_FOUND,

    /** A resolver matched more than one candidate and refused to choose. */
    RESOLVER_AMBIGUOUS,

    /** A class the feature depends on is not present on this WhatsApp build. */
    CLASS_NOT_FOUND,

    /** A method or field the feature depends on is not present. */
    MEMBER_NOT_FOUND,

    /** Installing the hook itself failed. */
    HOOK_INSTALL_FAILED,

    /** The feature's constructor rejected the class loader or preferences. */
    CONSTRUCTION_FAILED,

    /** Reading or applying a preference failed. */
    PREFERENCE_ERROR,

    /** Resolution or installation exceeded its time budget. */
    TIMEOUT,

    /** A permission or security check refused the operation. */
    ACCESS_DENIED,

    /** The feature is structurally incompatible with this WhatsApp build. */
    INCOMPATIBLE,

    /** The module could not initialise its own DexKit or reflection state. */
    RESOLVER_INIT_FAILED,

    /** Nothing more specific could be determined. */
    UNEXPECTED,

    ;

    companion object {
        /**
         * Text that identifies a failure to start the resolution engine itself.
         *
         * Matched against the caller's stage hint and the throwable's own message, because
         * neither alone is sufficient: the loader knows the stage it was running, and the
         * engine's message is the only thing that names the engine when the failure is
         * raised from inside it.
         */
        private val RESOLVER_INIT_HINTS: List<String> = listOf("dexkit", "unobfuscator", "initwithpath")

        /**
         * Classifies a throwable by its type alone.
         *
         * [featureMessage] lets a caller supply a stronger signal from context — for
         * instance the loader knows a failure happened while resolving rather than while
         * hooking — without duplicating the type inspection here.
         *
         * A failure to initialise the engine is checked before the generic timeout and
         * ambiguity hints, because "the engine never started" is the more precise claim
         * when both are true: a DexKit initialisation that timed out is a resolver-init
         * failure that took too long, and describing it as a timeout loses the part a user
         * can act on.
         */
        fun classify(
            throwable: Throwable,
            featureMessage: String? = null,
        ): FailureCode {
            val hint = featureMessage?.lowercase().orEmpty()
            val text = hint + " " + throwable.message?.lowercase().orEmpty()
            if (RESOLVER_INIT_HINTS.any { text.contains(it) }) return RESOLVER_INIT_FAILED
            if (hint.contains("ambiguous")) return RESOLVER_AMBIGUOUS
            if (hint.contains("timeout") || hint.contains("timed out")) return TIMEOUT

            return when (throwable) {
                is ClassNotFoundException, is NoClassDefFoundError -> CLASS_NOT_FOUND
                is NoSuchMethodException, is NoSuchFieldException -> MEMBER_NOT_FOUND
                is SecurityException -> ACCESS_DENIED
                is java.util.concurrent.TimeoutException -> TIMEOUT
                else -> UNEXPECTED
            }
        }
    }
}
