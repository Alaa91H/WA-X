package com.wmods.wppenhacer.resolver

/**
 * Resolves one piece of WhatsApp internals.
 *
 * Implementations must not throw for a missing target: "this build does not have it" is a
 * [Resolution.NotFound], not an exception. Throwing is reserved for genuine faults such as
 * the resolver itself being broken.
 *
 * @param id a stable identifier, used as the key in diagnostics and the cache. It must not
 *   change between module releases, because T12 keys the cache on the resolver schema.
 * @param T what this resolver produces
 */
interface HookResolver<T : Any> {

    /** Stable identifier for this resolver. */
    val id: String

    /**
     * A short description of what is being looked up, safe to show a user.
     *
     * Must name the WhatsApp class or member pattern, never a chat, contact or message.
     */
    val target: String

    /**
     * Performs the lookup.
     *
     * @param classLoader the loader for the target application
     */
    fun resolve(classLoader: ClassLoader): Resolution<T>

    /**
     * Runs [resolve] and records the outcome, returning it unchanged.
     *
     * This is the entry point features should call: it guarantees every outcome lands in
     * [ResolverRegistry] whether it succeeds, is absent, or is ambiguous, which is the
     * precondition for T15's per-feature diagnostics.
     */
    fun resolveRecorded(classLoader: ClassLoader): Resolution<T> {
        val outcome = resolve(classLoader)
        ResolverRegistry.record(id, target, outcome)
        return outcome
    }
}