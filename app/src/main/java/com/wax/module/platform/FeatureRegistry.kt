package com.wax.module.platform

/**
 * The single source of truth for what the platform contains.
 *
 * The registry exists to make the roadmap's Definition of Done enforceable rather than
 * aspirational: a feature that has no tests, no preference keys or no declared
 * compatibility contract is *rejected* at registration time. Without this, metadata is the
 * first thing to rot — features get added, the declaration is skipped, and six months later
 * nobody can say which resolver a toggle depends on.
 *
 * Registration is idempotent for an identical declaration (so reloading a module does not
 * produce duplicate warnings) and rejects a conflicting one, which surfaces accidental id
 * reuse instead of letting the last writer win.
 */
object FeatureRegistry {
    private val features = LinkedHashMap<String, FeatureMetadata>()

    /**
     * Registers [metadata].
     *
     * @return Accepted when the declaration is valid, otherwise Rejected with every reason,
     *   so a developer can fix all problems in one pass instead of one build at a time
     */
    fun register(metadata: FeatureMetadata): RegistrationResult {
        val existing = synchronized(features) { features[metadata.id] }
        if (existing == metadata) {
            return RegistrationResult.Accepted(metadata)
        }
        if (existing != null) {
            return RegistrationResult.Rejected(
                metadata,
                listOf("id '${metadata.id}' is already registered by '${existing.displayName}'"),
            )
        }
        val problems = validate(metadata)
        if (problems.isNotEmpty()) {
            return RegistrationResult.Rejected(metadata, problems)
        }
        synchronized(features) { features[metadata.id] = metadata }
        return RegistrationResult.Accepted(metadata)
    }

    /** Registers many declarations, returning every result in input order. */
    fun registerAll(metadata: List<FeatureMetadata>): List<RegistrationResult> = metadata.map { register(it) }

    /**
     * Checks a declaration without registering it.
     *
     * Rules are deliberately about *declaration completeness*, not about feature quality:
     * a feature with no tests cannot claim to satisfy the Definition of Done, and a
     * critical feature with no required resolvers cannot participate in the canary, so both
     * are statements the registry can verify mechanically.
     */
    fun validate(metadata: FeatureMetadata): List<String> {
        val problems = ArrayList<String>()
        if (!ID_PATTERN.matches(metadata.id)) {
            problems.add("id '${metadata.id}' must be lower-case dot-separated (for example 'privacy.profiles')")
        }
        if (metadata.displayName.isBlank()) {
            problems.add("displayName must not be blank")
        }
        if (metadata.tests.isEmpty()) {
            problems.add("at least one test must be declared (Definition of Done: tests are a condition, not a bonus)")
        }
        if (metadata.tests.any { it.isBlank() }) {
            problems.add("test names must not be blank")
        }
        if (metadata.preferenceKeys.size != metadata.preferenceKeys.count { it.isNotBlank() }) {
            problems.add("preference keys must not be blank")
        }
        val required = metadata.requiredResolvers.toSet()
        val optional = metadata.optionalResolvers.toSet()
        if (required.any { it.isBlank() } || optional.any { it.isBlank() }) {
            problems.add("resolver ids must not be blank")
        }
        val overlap = required.intersect(optional)
        if (overlap.isNotEmpty()) {
            problems.add("resolvers cannot be both required and optional: ${overlap.sorted()}")
        }
        if (!metadata.supportsWhatsApp && !metadata.supportsBusiness) {
            problems.add("at least one supported WhatsApp or Business version must be declared")
        }
        if (metadata.supportedWhatsAppVersions.any { it.isBlank() } ||
            metadata.supportedBusinessVersions.any { it.isBlank() }
        ) {
            problems.add("supported versions must not be blank")
        }
        if (metadata.startupPolicy == StartupPolicy.EAGER_CRITICAL && !metadata.isCritical) {
            problems.add("an EAGER_CRITICAL feature must be declared critical")
        }
        if (metadata.isCritical && metadata.fallbackBehavior == FallbackBehavior.NONE) {
            problems.add("a critical feature must declare a fallback behavior")
        }
        return problems
    }

    /** The declaration for [id], or null when it was never registered. */
    fun get(id: String): FeatureMetadata? = synchronized(features) { features[id] }

    /** Every registered declaration, in registration order. */
    fun all(): List<FeatureMetadata> = synchronized(features) { features.values.toList() }

    /** Registered declarations in [category]. */
    fun byCategory(category: FeatureCategory): List<FeatureMetadata> = all().filter { it.category == category }

    /** Features that gate a release. */
    fun critical(): List<FeatureMetadata> = all().filter { it.isCritical }

    /** The number of registered features. */
    fun count(): Int = synchronized(features) { features.size }

    /** Drops all registrations. Used by tests and by a full reload. */
    fun clear() {
        synchronized(features) { features.clear() }
    }

    private val ID_PATTERN = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)*$")
}

/** The outcome of one registration attempt. */
sealed interface RegistrationResult {
    /** The declaration was stored. */
    data class Accepted(
        val metadata: FeatureMetadata,
    ) : RegistrationResult

    /** The declaration was not stored; [reasons] says exactly why. */
    data class Rejected(
        val metadata: FeatureMetadata,
        val reasons: List<String>,
    ) : RegistrationResult

    /** Whether the declaration is now active. */
    val isAccepted: Boolean get() = this is Accepted
}
