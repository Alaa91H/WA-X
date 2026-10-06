package com.wax.module.platform

/**
 * One reason a declaration or a piece of text breaks the access contract.
 *
 * @param subject the declaration or string that broke it, so the report names a place to fix
 * @param token the gate-shaped token that was found
 */
data class AccessContractViolation(
    val subject: String,
    val token: String,
    /** What to do about it, in one sentence. */
    val remedy: String,
) {
    /** One line for diagnostics. */
    fun toDisplayLine(): String = "$subject: '$token' — $remedy"
}

/**
 * The all-features-free contract, as executable rules.
 *
 * WA X has no internal paywall: no Premium tier, no Pro tier, no supporter-only feature, no
 * donation-to-unlock, and no remote licence check standing between a user and a feature the
 * project owns. A rule like that decays the moment it is only written down, so this object
 * turns it into three things that can be run: the set of legacy entitlement keys an earlier
 * build may have written, a migration that removes them without disturbing anything else,
 * and a token audit that fails when a gate-shaped name is introduced.
 *
 * What is deliberately *not* here is anything that touches another party's entitlement.
 * WhatsApp's, Meta's and third-party paid capabilities are read and respected; this contract
 * governs WA X's own feature access only.
 */
object NoPaywallContract {
    /**
     * Preference keys earlier builds used to decide whether a project feature was reachable.
     *
     * They are listed so the migration can find and remove them. Nothing in the module reads
     * them, and the list is intentionally the whole history rather than the subset that still
     * appears in code — a key nobody references is exactly the kind that survives for years
     * and is then reused by accident.
     */
    @JvmField
    val LEGACY_ENTITLEMENT_KEYS: Set<String> =
        setOf(
            "is_premium",
            "ispremium",
            "is_premium_user",
            "premium_unlocked",
            "premium_features",
            "premium",
            "is_pro",
            "ispro",
            "pro_mode",
            "has_license",
            "haslicense",
            "license_key",
            "license_activated",
            "license_state",
            "donor_tier",
            "donor_level",
            "donated",
            "supporter",
            "supporter_tier",
            "supporter_badge",
            "vip",
            "vip_level",
            "subscription_active",
            "subscription_status",
            "entitlement",
            "entitlements",
            "unlocked_features",
            "unlock_state",
            "purchased_features",
            "purchase_state",
            "activation_code",
            "activation_state",
            "billing_state",
        )

    /**
     * Single tokens that only ever appear in an access gate.
     *
     * The list is gate-shaped rather than a list of suspicious words: `donor` and `supporter`
     * are here because a feature that is reachable only after donating is the exact thing the
     * contract forbids, while ordinary support wording (a Ko-fi link, a thank-you screen) is
     * not matched at all. That distinction is what keeps the audit useful instead of noisy.
     */
    @JvmField
    val GATE_TOKENS: Set<String> =
        setOf(
            "premium",
            "donor",
            "donate",
            "donation",
            "supporter",
            "paywall",
            "entitlement",
            "billingclient",
            "playbilling",
            "license",
            "licensed",
            "licence",
            "subscription",
        )

    /**
     * Adjacent-token pairs that name a gate even though each half is harmless alone.
     *
     * `proOnly` splits into `pro` and `only`, and neither token is damning; the pair is. This
     * is checked in addition to [GATE_TOKENS] so the audit can catch the compound names the
     * contract names explicitly without putting broad words like `pro`, `only` or `has` on a
     * denylist where they would fire constantly.
     */
    @JvmField
    val GATE_PAIRS: Set<String> =
        setOf(
            "proonly",
            "premiumonly",
            "donoronly",
            "donationonly",
            "supporteronly",
            "viponly",
            "ispro",
            "ispremium",
            "isdonor",
            "issupporter",
            "haslicense",
            "hasentitlement",
            "licensekey",
            "licensekeys",
            "licenseserver",
            "licensedonly",
            "paidonly",
            "featurelocked",
            "lockedfeature",
            "unlockfeature",
            "unlockfor",
            "upgradetounlock",
            "upgradetopro",
            "unlockby",
            "unlockwith",
            "premiumtier",
            "goldtier",
            "donortier",
            "supportertier",
            "premiumfeature",
            "paidfeature",
            "purchasefeature",
            "querypurchases",
            "launchbillingflow",
            "activationcode",
        )

    /**
     * Three-token names that are a gate even though no two adjacent tokens are.
     *
     * `upgradeToUnlock` is the reason this set exists: splitting it gives `upgrade`, `to`
     * and `unlock`, so a two-token scan walks straight past the phrasing the contract names
     * explicitly. Adding `unlock` to [GATE_TOKENS] instead would fire on every sentence that
     * mentions unlocking something; the triple is precise where the single token is not.
     */
    @JvmField
    val GATE_TRIPLES: Set<String> =
        setOf(
            "upgradetounlock",
            "unlockupgrade",
            "unlockpremium",
            "premiumunlock",
            "haspaidaccess",
            "subscribetounlock",
            "donatetounlock",
        )

    /**
     * The wording the interface must never use, checked as whole phrases.
     *
     * [FeatureAvailability.explanation] is the only place WA X renders an availability
     * sentence, and the platform test runs this over every one of them. It is a separate
     * check from the token audit because a sentence can be *about* a paywall — a help page
     * explaining that there is none — while never presenting one.
     */
    private val FORBIDDEN_PRESENTATION_PHRASES: List<String> =
        listOf(
            "premium",
            "upgrade to unlock",
            "unlock with",
            "donate to unlock",
            "supporter only",
            "donor only",
            "pro only",
            "subscribe to unlock",
            "purchase to",
            "license required",
            "buy to unlock",
            "paid feature",
        )

    /** Splits an identifier into lower-case tokens on separators and camel-case boundaries. */
    fun tokensOf(text: String): List<String> =
        text
            .split(BOUNDARY)
            .map { it.lowercase() }
            .filter { it.isNotEmpty() }

    /**
     * Every gate-shaped token in [text].
     *
     * @return the matching tokens in the order they appear, de-duplicated. Empty when the
     *   text is clean, which is the expected result for every declaration in the catalog
     */
    fun gateTokensIn(text: String): List<String> {
        val tokens = tokensOf(text)
        val found = LinkedHashSet<String>()
        tokens.forEachIndexed { index, token ->
            if (token in GATE_TOKENS) found.add(token)
            val next = tokens.getOrNull(index + 1)
            if (next != null) {
                val pair = token + next
                if (pair in GATE_PAIRS) found.add(pair)
                val after = tokens.getOrNull(index + 2)
                if (after != null) {
                    val triple = pair + after
                    if (triple in GATE_TRIPLES) found.add(triple)
                }
            }
        }
        return found.toList()
    }

    /**
     * Whether [text] presents a paid tier.
     *
     * Used on every user-facing availability sentence and on the settings surface, so a
     * technical limitation cannot be dressed up as a purchasable one.
     */
    fun containsPaymentWording(text: String): Boolean {
        val normalised = text.lowercase()
        return FORBIDDEN_PRESENTATION_PHRASES.any { normalised.contains(it) }
    }

    /**
     * Audits every declaration for an internal access gate.
     *
     * Only the identifiers and preference keys are scanned. Display names are excluded on
     * purpose: the words a feature is called are reviewed by people, and failing the build
     * because a summary sentence mentions supporting the project would train everyone to
     * ignore the check.
     */
    fun audit(features: List<FeatureMetadata>): List<AccessContractViolation> {
        val violations = ArrayList<AccessContractViolation>()
        features.forEach { metadata ->
            gateTokensIn(metadata.id).forEach { token ->
                violations.add(
                    AccessContractViolation(
                        subject = "feature id '${metadata.id}'",
                        token = token,
                        remedy = "rename it: a WA X feature id must not name an access gate",
                    ),
                )
            }
            metadata.preferenceKeys.forEach { key ->
                gateTokensIn(key).forEach { token ->
                    violations.add(
                        AccessContractViolation(
                            subject = "preference key '$key' of '${metadata.id}'",
                            token = token,
                            remedy = "rename the key: WA X settings are not entitlements",
                        ),
                    )
                }
            }
            if (metadata.accessTier != FeatureAccessTier.FREE) {
                violations.add(
                    AccessContractViolation(
                        subject = "feature '${metadata.id}'",
                        token = metadata.accessTier.name,
                        remedy = "every WA X feature is free; only technical state may limit one",
                    ),
                )
            }
        }
        return violations
    }

    /** Renders an audit for diagnostics. */
    fun describe(violations: List<AccessContractViolation>): String =
        if (violations.isEmpty()) {
            "No internal access gate was found in the feature catalog."
        } else {
            buildString {
                appendLine("Internal access gates: ${violations.size}")
                violations.forEachIndexed { index, violation ->
                    appendLine("* ${index + 1}. ${violation.toDisplayLine()}")
                }
            }
        }

    /**
     * The preference keys of [store] that record an internal entitlement.
     *
     * A key qualifies when its last separator-delimited segment is one of
     * [LEGACY_ENTITLEMENT_KEYS]. That covers the plain key an early build wrote and the
     * per-target form the one-APK model added (`waxtarget.business.is_premium`), without the
     * platform having to know the target-key prefix, which lives in the settings package.
     */
    fun legacyEntitlementKeysIn(store: KeyValueStore): List<String> =
        store
            .keys()
            .filter { canonicalKeyName(it) in LEGACY_ENTITLEMENT_KEYS }
            .sorted()

    /**
     * Removes every legacy entitlement key, leaving every real setting untouched.
     *
     * This is the migration, and it is deliberately one-way and total: previously gated
     * features become normally available, the old keys stop existing so nothing can
     * accidentally read them again, and no unrelated preference is rewritten, reordered or
     * reset. Nothing is inferred about a user's history — a former supporter gets exactly the
     * configuration everyone else has, which is the point.
     *
     * @return the keys that were removed, for the diagnostics record. Empty on a second run,
     *   which makes the migration safe to repeat at every start
     */
    fun stripLegacyEntitlements(store: KeyValueStore): List<String> {
        val removed = legacyEntitlementKeysIn(store)
        removed.forEach { store.remove(it) }
        return removed
    }

    /** The final segment of a stored preference key. */
    private fun canonicalKeyName(physicalKey: String): String = physicalKey.substringAfterLast('.').lowercase()

    /** Separators and camel-case boundaries, so `isPremiumOnly` and `is_premium_only` agree. */
    private val BOUNDARY = Regex("[^A-Za-z0-9]+|(?<=[a-z0-9])(?=[A-Z])")
}
